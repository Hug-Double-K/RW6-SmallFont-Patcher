package io.github.hugdoublek.rw6hub;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * SAR v2 전송 계층. 블루투스 소켓의 입출력 스트림 위에서
 * 세션 시작, 순서 번호, ACK/NAK, 재전송을 처리한다. (안드로이드 의존 없음)
 */
public final class SarLink {

    public interface Listener {
        void onSessionReady();
        /** 2계층 패킷: channel(1=PB), opcode(1=평문, 2=암호), 나머지 페이로드 */
        void onL2(int channel, int opcode, byte[] body);
        void onLog(String msg);
        void onClosed(String reason);
    }

    /** SPP 연결 직후 공식 앱이 보내는 인사 바이트. */
    private static final byte[] SPP_HELLO = Hex.parse("badcfe00c00300000100ef");
    private static final long SEND_TIMEOUT_MS = 10_000;
    private static final long START_RETRY_MS = 3_000;
    private static final int MAX_RETRIES = 5;

    private final InputStream in;
    private final OutputStream out;
    private final Listener listener;
    private final Object lock = new Object();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();

    private static final class Pending {
        final L1 frame;
        long sentAt;
        int retries;
        Pending(L1 f) { frame = f; }
    }

    private final ArrayDeque<byte[]> waiting = new ArrayDeque<>();
    private final ArrayDeque<Pending> inFlight = new ArrayDeque<>();
    private int txNextSeq = 0;
    private int rxExpect = 0;
    private boolean rxSeen = false;
    private int window = 32;
    private boolean ready = false;
    private int startTries = 0;
    private long startSentAt = 0;
    private volatile boolean closed = false;

    public SarLink(InputStream in, OutputStream out, Listener listener) {
        this.in = in;
        this.out = out;
        this.listener = listener;
    }

    public boolean isReady() {
        synchronized (lock) { return ready; }
    }

    public void start() throws IOException {
        write(SPP_HELLO);
        Thread reader = new Thread(this::readLoop, "sar-reader");
        reader.setDaemon(true);
        reader.start();
        synchronized (lock) { sendStartReq(); }
        timer.scheduleWithFixedDelay(this::tick, 500, 500, TimeUnit.MILLISECONDS);
    }

    /** 2계층 바이트(channel, opcode, payload)를 보낸다. 세션 전이면 줄 세워 둔다. */
    public void send(byte[] l2) {
        synchronized (lock) {
            waiting.add(l2);
            pump();
        }
    }

    public void close(String reason) {
        if (closed) return;
        closed = true;
        timer.shutdownNow();
        try { in.close(); } catch (IOException ignored) {}
        try { out.close(); } catch (IOException ignored) {}
        listener.onClosed(reason);
    }

    // ------------------------------------------------------------------

    private void write(byte[] b) throws IOException {
        synchronized (out) {
            out.write(b);
            out.flush();
        }
    }

    private void writeQuiet(L1 f) {
        try {
            write(f.toBytes());
        } catch (IOException e) {
            close("보내기 실패: " + e.getMessage());
        }
    }

    private void sendStartReq() {
        startTries++;
        startSentAt = System.currentTimeMillis();
        writeQuiet(new L1(L1.CMD, false, 0, L1.startCmd(L1.CMD_START_REQ)));
    }

    private void resetSeq() {
        inFlight.clear();
        txNextSeq = 0;
        rxExpect = 0;
        rxSeen = false;
    }

    /** 창이 허락하는 만큼 대기열을 내보낸다. lock 안에서 호출. */
    private void pump() {
        if (!ready) return;
        while (!waiting.isEmpty() && inFlight.size() < window) {
            Pending p = new Pending(new L1(L1.DATA, false, txNextSeq, waiting.poll()));
            txNextSeq = (txNextSeq + 1) & 0xFF;
            p.sentAt = System.currentTimeMillis();
            inFlight.add(p);
            writeQuiet(p.frame);
        }
    }

    private static boolean seqLe(int a, int b) {
        return ((b - a) & 0xFF) < 128;
    }

    private void ackThrough(int seq) {
        while (!inFlight.isEmpty() && seqLe(inFlight.peek().frame.seq, seq)) inFlight.poll();
    }

    private void tick() {
        List<String> fail = new ArrayList<>();
        synchronized (lock) {
            long now = System.currentTimeMillis();
            if (!ready) {
                if (now - startSentAt > START_RETRY_MS) {
                    if (startTries >= 4) fail.add("워치가 세션 시작에 응답하지 않음");
                    else sendStartReq();
                }
            } else {
                for (Pending p : inFlight) {
                    if (now - p.sentAt > SEND_TIMEOUT_MS) {
                        if (++p.retries > MAX_RETRIES) { fail.add("워치가 응답하지 않음(재전송 한도)"); break; }
                        p.sentAt = now;
                        writeQuiet(p.frame);
                    }
                }
            }
        }
        if (!fail.isEmpty()) close(fail.get(0));
    }

    private void readLoop() {
        L1.Parser parser = new L1.Parser();
        byte[] buf = new byte[4096];
        try {
            while (!closed) {
                int n = in.read(buf);
                if (n < 0) break;
                for (L1 f : parser.feed(buf, n)) onFrame(f);
            }
            close("연결이 끊김");
        } catch (IOException e) {
            close("연결이 끊김: " + e.getMessage());
        } catch (RuntimeException e) {
            close("수신 처리 오류: " + e);
        }
    }

    private void onFrame(L1 f) {
        boolean readyNow = false;
        byte[] deliver = null;
        synchronized (lock) {
            switch (f.type) {
                case L1.ACK:
                    ackThrough(f.seq);
                    pump();
                    break;
                case L1.NAK: {
                    ackThrough((f.seq - 1) & 0xFF);
                    long now = System.currentTimeMillis();
                    for (Pending p : inFlight) {
                        if (seqLe(f.seq, p.frame.seq)) { p.sentAt = now; writeQuiet(p.frame); }
                    }
                    break;
                }
                case L1.CMD: {
                    int cmd = f.payload.length > 0 ? f.payload[0] & 0xFF : -1;
                    int win = readTxWin(f.payload);
                    if (cmd == L1.CMD_START_RSP) {
                        if (!ready) readyNow = true;
                        ready = true;
                        if (win > 0) window = Math.min(32, win);
                        listener.onLog("세션 시작됨 (창 " + window + ")");
                        pump();
                    } else if (cmd == L1.CMD_START_REQ) {
                        // 워치가 세션을 새로 시작함 → 번호 초기화 후 응답
                        listener.onLog("워치가 세션을 다시 시작함");
                        resetSeq();
                        if (win > 0) window = Math.min(32, win);
                        writeQuiet(new L1(L1.CMD, false, 0, L1.startCmd(L1.CMD_START_RSP)));
                        if (!ready) readyNow = true;
                        ready = true;
                        pump();
                    }
                    break;
                }
                case L1.DATA: {
                    if (!ready || f.frx) { deliver = f.payload; break; }
                    if (f.seq != rxExpect) {
                        boolean ahead = ((f.seq - rxExpect) & 0xFF) < 128;
                        if (ahead) writeQuiet(new L1(L1.NAK, false, rxExpect, null));
                        else if (rxSeen) writeQuiet(new L1(L1.ACK, false, (rxExpect - 1) & 0xFF, null));
                        break;
                    }
                    writeQuiet(new L1(L1.ACK, false, f.seq, null));
                    rxExpect = (rxExpect + 1) & 0xFF;
                    rxSeen = true;
                    deliver = f.payload;
                    break;
                }
            }
        }
        if (readyNow) listener.onSessionReady();
        if (deliver != null && deliver.length >= 2) {
            byte[] body = new byte[deliver.length - 2];
            System.arraycopy(deliver, 2, body, 0, body.length);
            listener.onL2(deliver[0] & 0xFF, deliver[1] & 0xFF, body);
        }
    }

    private static int readTxWin(byte[] p) {
        int i = 1;
        while (i + 3 <= p.length) {
            int key = p[i] & 0xFF;
            int n = (p[i + 1] & 0xFF) | ((p[i + 2] & 0xFF) << 8);
            i += 3;
            if (i + n > p.length) break;
            if (key == 3 && n == 2) return (p[i] & 0xFF) | ((p[i + 1] & 0xFF) << 8);
            i += n;
        }
        return -1;
    }
}
