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

    /** 워치가 받았다고 확인(ACK)했는지 알려 줌. ok=false면 연결이 끊겨 못 보냄. */
    public interface Delivery { void done(boolean ok); }

    private static final class Out {
        final byte[] l2;
        final Delivery cb;
        Out(byte[] l2, Delivery cb) { this.l2 = l2; this.cb = cb; }
    }

    private static final class Pending {
        final L1 frame;
        final Delivery cb;
        long sentAt;
        int retries;
        Pending(L1 f, Delivery cb) { frame = f; this.cb = cb; }
    }

    /** 확인 알림은 따로 돌려서, 받는 쪽이 다음 전송을 바로 해도 잠금이 꼬이지 않게 */
    private final java.util.concurrent.ExecutorService callbacks = Executors.newSingleThreadExecutor();

    private void notifyDelivery(Delivery cb, boolean ok) {
        if (cb == null) return;
        try { callbacks.execute(() -> cb.done(ok)); } catch (RuntimeException ignored) {}
    }

    private final ArrayDeque<Out> waiting = new ArrayDeque<>();
    private final ArrayDeque<Pending> inFlight = new ArrayDeque<>();
    private int txNextSeq = 0;
    private int rxExpect = 0;
    private boolean rxSeen = false;
    private int window = 32;
    private boolean ready = false;
    private int startTries = 0;
    private long startSentAt = 0;
    private volatile boolean closed = false;
    // 진단용
    private volatile long lastRxAt = System.currentTimeMillis(), lastTxAt = System.currentTimeMillis();
    private volatile int rxFrames, txFrames, naksSent, naksGot, retrans;
    private L1.Parser parser;
    private final java.util.ArrayDeque<String> trace = new java.util.ArrayDeque<>();

    private void trace(String dir, L1 f) {
        long t = System.currentTimeMillis() % 100000;
        String kind = f.type == L1.DATA ? "D" : f.type == L1.ACK ? "A" : f.type == L1.NAK ? "N" : "C";
        String extra = f.type == L1.CMD && f.payload.length > 0 ? ("cmd" + (f.payload[0] & 0xFF))
                : f.type == L1.DATA && f.payload.length >= 2 ? ("ch" + (f.payload[0] & 0xFF) + "/" + (f.payload[1] & 0xFF) + " " + f.payload.length + "B") : "";
        synchronized (trace) {
            trace.add(String.format(java.util.Locale.US, "%05.1f%s%s%d%s%s", t / 1000.0, dir, kind, f.seq, f.frx ? "f" : "", extra.isEmpty() ? "" : " " + extra));
            while (trace.size() > 14) trace.poll();
        }
    }

    /** 끊기기 직전 주고받은 1계층 프레임들 (→보냄 ←받음) */
    public String recentFrames() {
        synchronized (trace) { return String.join(" | ", trace); }
    }

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
    public void send(byte[] l2) { send(l2, null); }

    public void send(byte[] l2, Delivery cb) {
        synchronized (lock) {
            if (closed) { notifyDelivery(cb, false); return; }
            waiting.add(new Out(l2, cb));
            pump();
        }
    }

    /** 끊길 때 원인 짐작용 요약 */
    public String stats() {
        long now = System.currentTimeMillis();
        return "받음 " + rxFrames + " · 보냄 " + txFrames + " · 마지막 받음 " + (now - lastRxAt) / 1000 + "초 전"
                + " · 마지막 보냄 " + (now - lastTxAt) / 1000 + "초 전"
                + (naksSent + naksGot + retrans > 0 ? " · NAK " + naksSent + "/" + naksGot + " · 재전송 " + retrans : "")
                + (parser != null && parser.skipped > 0 ? " · 버린 바이트 " + parser.skipped : "")
                + " · 대기 " + inFlight.size() + "/" + waiting.size();
    }

    public void close(String reason) {
        if (closed) return;
        closed = true;
        timer.shutdownNow();
        synchronized (lock) {
            for (Pending p : inFlight) notifyDelivery(p.cb, false);
            for (Out o : waiting) notifyDelivery(o.cb, false);
            inFlight.clear();
            waiting.clear();
        }
        callbacks.shutdown();
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
        trace("→", f);
        txFrames++;
        lastTxAt = System.currentTimeMillis();
        if (f.type == L1.NAK) naksSent++;
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
        // 보내던 것은 새 번호로 다시 보낸다 (워치가 세션을 새로 시작한 경우)
        java.util.List<Pending> again = new ArrayList<>(inFlight);
        inFlight.clear();
        for (int i = again.size() - 1; i >= 0; i--) {
            Pending p = again.get(i);
            byte[] l2 = p.frame.payload;
            waiting.addFirst(new Out(l2, p.cb));
        }
        txNextSeq = 0;
        rxExpect = 0;
        rxSeen = false;
    }

    /** 창이 허락하는 만큼 대기열을 내보낸다. lock 안에서 호출. */
    private void pump() {
        if (!ready) return;
        while (!waiting.isEmpty() && inFlight.size() < window) {
            Out o = waiting.poll();
            Pending p = new Pending(new L1(L1.DATA, false, txNextSeq, o.l2), o.cb);
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
        while (!inFlight.isEmpty() && seqLe(inFlight.peek().frame.seq, seq)) notifyDelivery(inFlight.poll().cb, true);
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
                        retrans++;
                        writeQuiet(p.frame);
                    }
                }
            }
        }
        if (!fail.isEmpty()) close(fail.get(0));
    }

    private void readLoop() {
        parser = new L1.Parser();
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
        trace("←", f);
        rxFrames++;
        lastRxAt = System.currentTimeMillis();
        if (f.type == L1.NAK) naksGot++;
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
                    } else if (cmd != L1.CMD_START_REQ) {
                        listener.onLog("워치 1계층 명령 " + cmd + " 받음" + (cmd == 3 ? " (연결 끝내기 요청)" : ""));
                        if (cmd == 3) writeQuiet(new L1(L1.CMD, false, 0, new byte[]{4})); // STOP_RSP
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
                    // 네트워크(7)·멀티모달(10) 채널은 순서 번호를 쓰지 않고 ACK도 하지 않는다 (공식 앱 방식).
                    // 예전엔 여기에 ACK를 보내서 워치 쪽 순서가 꼬였고, 워치가 연결을 끊었다.
                    int ch = f.payload.length > 0 ? f.payload[0] & 0xFF : -1;
                    if (!ready || f.frx || ch == 7 || ch == 10) { deliver = f.payload; break; }
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
