package io.github.hugdoublek.rw6hub;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 워치 인터넷 중계. 워치는 블루투스 네트워크 통로(채널 7)로 보통의 IPv4 패킷을 그대로 보낸다
 * (DHCP는 Dhcp.java가 답함: 워치 10.1.10.2, 폰 10.1.10.1). 여기서는 그 패킷을 받아
 *  - TCP: 폰이 상대 서버인 것처럼 연결을 받아 주고(SYN-ACK), 실제 서버에는 폰의 보통 소켓으로 붙어 내용을 옮긴다.
 *  - UDP: 폰의 소켓으로 대신 보내고 답을 돌려준다. DNS(53)는 답이 없으면 폰의 이름 풀이로 대신 답한다.
 *  - ICMP 핑: 바로 답한다.
 * 블루투스는 느리고 순서가 보장되므로 TCP는 단순하게 구현했다: 한 번에 보내 두는 양을 작게 잡고(FLIGHT_CAP),
 * 워치가 받았다고 한 만큼만 더 보내며, 오래 답이 없을 때만 다시 보낸다.
 * 안드로이드에 기대지 않는 순수 자바라 PC에서 시험할 수 있다.
 */
public final class NetRelay {

    public interface Out {
        /** 워치로 보낼 IP 패킷 */
        void send(byte[] ipPacket);
        void log(String line);
    }

    /** 시험용: 실제로 붙을 주소를 바꿔치기 (기본은 그대로) */
    public interface AddressMap {
        InetSocketAddress tcp(InetAddress addr, int port);
        InetSocketAddress udp(InetAddress addr, int port);
    }

    static final int MSS_CAP = 1360;            // 워치로 보내는 조각의 최대 크기 (워치가 더 작게 알려 주면 그 값)
    static final int FLIGHT_CAP = 8 * 1024;     // 워치의 확인을 기다리며 보내 둘 수 있는 양
    static final int BUFFER_CAP = 32 * 1024;    // 서버에서 미리 읽어 둘 양
    static final int UP_QUEUE_CAP = 256 * 1024; // 워치→서버로 아직 못 쓴 양의 한도 (그만큼 창을 줄여 알림)
    static final long RTO_FIRST_MS = 3000, RTO_MAX_MS = 12000;
    static final int RTO_TRIES = 6;
    static final long IDLE_MS = 5 * 60 * 1000, LINGER_MS = 60 * 1000, UDP_IDLE_MS = 20 * 1000;
    static final int CONNECT_TIMEOUT_MS = 15000;

    private static final int FIN = 1, SYN = 2, RST = 4, PSH = 8, ACK = 16;

    private final Out out;
    private final AddressMap map;
    private final Map<String, Conn> conns = new ConcurrentHashMap<>();
    private final Map<String, UdpFlow> flows = new ConcurrentHashMap<>();
    private final Map<String, String> names = new ConcurrentHashMap<>();   // IP → 이름 (기록을 읽기 쉽게)
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "net-relay");
        t.setDaemon(true);
        return t;
    });
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "net-relay-timer");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean closed;
    private int ipId = 1;
    // 기록용 합계
    private final Object statLock = new Object();
    private long statDown, statUp, statSince;
    private int statConns, statFails;
    private final java.util.Set<String> seenHosts = java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());

    public NetRelay(Out out) { this(out, null); }

    public NetRelay(Out out, AddressMap map) {
        this.out = out;
        this.map = map;
        timer.scheduleWithFixedDelay(this::tick, 500, 500, TimeUnit.MILLISECONDS);
    }

    public void close() {
        closed = true;
        timer.shutdownNow();
        for (Conn c : new ArrayList<>(conns.values())) c.destroy(false);
        for (UdpFlow f : new ArrayList<>(flows.values())) f.close();
        conns.clear();
        flows.clear();
        pool.shutdownNow();
    }

    /** 지금 열려 있는 TCP 연결 수 (진단·시험용) */
    public int openConnections() { return conns.size(); }

    private volatile int retransmits;
    /** 워치가 답하지 않아 다시 보낸 횟수 (진단·시험용) */
    public int retransmits() { return retransmits; }

    // ---------------------------------------------------------------- 워치에서 온 패킷

    /** 워치가 보낸 IP 패킷 하나. 오래 걸리는 일은 하지 않는다(연결·쓰기는 다른 스레드). */
    public void onPacket(byte[] p) {
        if (closed || p == null || p.length < 20) return;
        try {
            if ((p[0] & 0xF0) != 0x40) {                            // IPv4만
                logOnce("ver" + ((p[0] & 0xF0) >> 4), "워치 인터넷: IPv" + ((p[0] & 0xF0) >> 4) + " 패킷은 처리하지 않음");
                return;
            }
            int ihl = (p[0] & 0x0F) * 4;
            int total = Math.min(u16(p, 2), p.length);
            if (ihl < 20 || total < ihl) return;
            if ((u16(p, 6) & 0x3FFF) != 0) { logOnce("frag", "워치 인터넷: 조각난 IP 패킷은 처리하지 않음"); return; }
            int proto = p[9] & 0xFF;
            if (proto == 6) onTcp(p, ihl, total);
            else if (proto == 17) onUdp(p, ihl, total);
            else if (proto == 1) onIcmp(p, ihl, total);
        } catch (RuntimeException e) {
            logOnce("err:" + e.getClass().getSimpleName(), "워치 인터넷: 패킷 처리 오류 " + e);
        }
    }

    private void onIcmp(byte[] p, int ihl, int total) {
        if (total < ihl + 8 || (p[ihl] & 0xFF) != 8) return;        // echo request만
        byte[] icmp = new byte[total - ihl];
        System.arraycopy(p, ihl, icmp, 0, icmp.length);
        icmp[0] = 0;
        icmp[2] = 0; icmp[3] = 0;
        put16(icmp, 2, checksum(icmp, 0, icmp.length, 0));
        byte[] src = new byte[4], dst = new byte[4];
        System.arraycopy(p, 16, src, 0, 4);                          // 답의 보낸 곳 = 원래 받는 곳
        System.arraycopy(p, 12, dst, 0, 4);
        out.send(ipPacket(1, src, dst, icmp));
    }

    // ---------------------------------------------------------------- UDP

    private void onUdp(byte[] p, int ihl, int total) {
        if (total < ihl + 8) return;
        int sport = u16(p, ihl), dport = u16(p, ihl + 2);
        int len = Math.min(u16(p, ihl + 4), total - ihl) - 8;
        if (len < 0) return;
        byte[] watch = new byte[4], dst = new byte[4];
        System.arraycopy(p, 12, watch, 0, 4);
        System.arraycopy(p, 16, dst, 0, 4);
        if (dport == 67 || dport == 68) return;                      // DHCP는 Dhcp.java 몫
        if ((dst[0] & 0xFF) >= 224 || isBroadcast(dst)) return;      // 멀티캐스트·브로드캐스트는 버림
        byte[] data = new byte[len];
        System.arraycopy(p, ihl + 8, data, 0, len);
        String key = sport + ">" + ip(dst) + ":" + dport;
        UdpFlow f = flows.get(key);
        if (f == null || f.dead) {
            f = new UdpFlow(key, watch, sport, dst, dport);
            flows.put(key, f);
            final UdpFlow started = f;
            pool.execute(started::run);
        }
        f.fromWatch(data);
    }

    private final class UdpFlow {
        final String key;
        final byte[] watch, dst;
        final int sport, dport;
        final LinkedBlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();
        volatile boolean dead;
        volatile DatagramSocket sock;
        volatile byte[] lastQuery;
        volatile long lastQueryAt;
        volatile boolean answered;

        UdpFlow(String key, byte[] watch, int sport, byte[] dst, int dport) {
            this.key = key; this.watch = watch; this.sport = sport; this.dst = dst; this.dport = dport;
        }

        void fromWatch(byte[] data) {
            if (dport == 53) { lastQuery = data; lastQueryAt = System.currentTimeMillis(); answered = false; noteDnsQuery(data); }
            queue.offer(data);
        }

        void close() {
            dead = true;
            DatagramSocket s = sock;
            if (s != null) s.close();
        }

        void run() {
            try (DatagramSocket s = new DatagramSocket()) {
                sock = s;
                InetAddress addr = InetAddress.getByAddress(dst);
                InetSocketAddress to = map != null ? map.udp(addr, dport) : new InetSocketAddress(addr, dport);
                s.setSoTimeout(250);
                byte[] buf = new byte[4096];
                long idleSince = System.currentTimeMillis();
                while (!dead && !closed) {
                    byte[] q;
                    while ((q = queue.poll()) != null) {
                        s.send(new DatagramPacket(q, q.length, to));
                        idleSince = System.currentTimeMillis();
                    }
                    try {
                        DatagramPacket in = new DatagramPacket(buf, buf.length);
                        s.receive(in);
                        byte[] data = new byte[in.getLength()];
                        System.arraycopy(buf, 0, data, 0, data.length);
                        if (dport == 53) { answered = true; noteDnsAnswer(data); }
                        out.send(udpPacket(dst, dport, watch, sport, data));
                        idleSince = System.currentTimeMillis();
                    } catch (SocketTimeoutException ignored) {
                        // DNS: 2.5초 안에 답이 없으면 폰의 이름 풀이로 대신 답함 (바깥 DNS가 막힌 망 대비)
                        byte[] lq = lastQuery;
                        if (dport == 53 && lq != null && !answered && System.currentTimeMillis() - lastQueryAt > 2500) {
                            answered = true;
                            byte[] ans = DnsFallback.answer(lq);
                            if (ans != null) { noteDnsAnswer(ans); out.send(udpPacket(dst, dport, watch, sport, ans)); }
                        }
                        if (System.currentTimeMillis() - idleSince > UDP_IDLE_MS) break;
                    }
                }
            } catch (IOException e) {
                if (!closed && !dead) logOnce("udp:" + e.getClass().getSimpleName(), "워치 인터넷: UDP 실패 " + ip(dst) + ":" + dport + " " + e.getMessage());
            } finally {
                dead = true;
                flows.remove(key, this);
            }
        }
    }

    /** DNS 질문·답에서 이름과 주소를 적어 둔다 (기록에 주소 대신 이름을 쓰려고) */
    private void noteDnsQuery(byte[] q) {
        String n = DnsFallback.name(q);
        if (n != null && seenHosts.add("dns:" + n)) out.log("워치 인터넷: 이름 찾기 " + n);
    }

    private void noteDnsAnswer(byte[] a) {
        try {
            String n = DnsFallback.name(a);
            if (n == null) return;
            for (byte[] addr : DnsFallback.addresses(a)) names.put(ip(addr), n);
        } catch (RuntimeException ignored) {}
    }

    // ---------------------------------------------------------------- TCP

    private void onTcp(byte[] p, int ihl, int total) {
        if (total < ihl + 20) return;
        int sport = u16(p, ihl), dport = u16(p, ihl + 2);
        int seq = i32(p, ihl + 4), ack = i32(p, ihl + 8);
        int doff = ((p[ihl + 12] & 0xF0) >> 4) * 4;
        int flags = p[ihl + 13] & 0x3F;
        int wnd = u16(p, ihl + 14);
        if (doff < 20 || total < ihl + doff) return;
        int len = total - ihl - doff;
        byte[] watch = new byte[4], dst = new byte[4];
        System.arraycopy(p, 12, watch, 0, 4);
        System.arraycopy(p, 16, dst, 0, 4);
        String key = sport + ">" + ip(dst) + ":" + dport;
        Conn c = conns.get(key);

        if ((flags & SYN) != 0 && (flags & ACK) == 0) {
            if (c != null && c.clientIsn == seq) { c.onDupSyn(); return; }
            if (c != null) c.destroy(false);                          // 같은 포트를 새 연결로 다시 씀
            int mss = 536;
            for (int i = ihl + 20; i + 1 < ihl + doff; ) {             // 옵션에서 MSS 찾기
                int kind = p[i] & 0xFF;
                if (kind == 0) break;
                if (kind == 1) { i++; continue; }
                int olen = p[i + 1] & 0xFF;
                if (olen < 2) break;
                if (kind == 2 && olen == 4 && i + 3 < ihl + doff) mss = u16(p, i + 2);
                i += olen;
            }
            Conn n = new Conn(key, watch, sport, dst, dport, seq, Math.max(64, Math.min(mss, MSS_CAP)), wnd);
            conns.put(key, n);
            pool.execute(n::connectAndRead);
            return;
        }
        if (c == null) {
            if ((flags & RST) == 0) {                                  // 모르는 연결 → 끊으라고 알림
                if ((flags & ACK) != 0) out.send(tcpPacket(dst, dport, watch, sport, ack, 0, RST, 0, null, 0, 0, 0));
                else out.send(tcpPacket(dst, dport, watch, sport, 0, seq + len + ((flags & (SYN | FIN)) != 0 ? 1 : 0), RST | ACK, 0, null, 0, 0, 0));
            }
            return;
        }
        c.onSegment(seq, ack, flags, wnd, p, ihl + doff, len);
    }

    private final class Conn {
        final String key;
        final byte[] watch, dst;
        final int cport, dport, clientIsn, iss, mss;
        final long startedAt = System.currentTimeMillis();
        int rcvNxt;                       // 워치에게서 다음에 받을 번호
        int peerWnd;                      // 워치가 받을 수 있다고 한 양
        boolean synSent, synAcked;
        // 서버→워치 데이터: buf에는 [ackedBytes, bufferedBytes) 구간이 들어 있다
        byte[] buf = new byte[BUFFER_CAP];
        int bufLen;
        long ackedBytes, sentBytes, maxSent, bufferedBytes;
        boolean upstreamEof, finSent, finEverSent, finAcked, finFromWatch;
        boolean dead;
        long lastProgress = System.currentTimeMillis(), lastActivity = lastProgress, rto = RTO_FIRST_MS, closedAt;
        int tries, dupAcks;
        long up, down;
        volatile Socket sock;
        final LinkedBlockingQueue<byte[]> toServer = new LinkedBlockingQueue<>();
        int queuedUp;                     // toServer에 쌓인 바이트 (창 계산용)
        private final byte[] EOF = new byte[0];

        Conn(String key, byte[] watch, int cport, byte[] dst, int dport, int clientIsn, int mss, int wnd) {
            this.key = key; this.watch = watch; this.cport = cport; this.dst = dst; this.dport = dport;
            this.clientIsn = clientIsn; this.mss = mss; this.peerWnd = wnd;
            this.rcvNxt = clientIsn + 1;
            this.iss = (int) (System.nanoTime() * 2654435761L);
        }

        String host() {
            String n = names.get(ip(dst));
            return (n != null ? n : ip(dst)) + ":" + dport;
        }

        // ---- 서버 쪽 (각 연결마다 스레드 하나: 붙고, 읽는다)
        void connectAndRead() {
            Socket s = new Socket();
            try {
                InetAddress addr = InetAddress.getByAddress(dst);
                InetSocketAddress to = map != null ? map.tcp(addr, dport) : new InetSocketAddress(addr, dport);
                s.setTcpNoDelay(true);
                s.connect(to, CONNECT_TIMEOUT_MS);
            } catch (IOException e) {
                try { s.close(); } catch (IOException ignored) {}
                synchronized (this) {
                    if (dead) return;
                    send(iss, rcvNxt, RST | ACK, null, 0, 0);          // 붙지 못함 → 워치에 거절
                }
                fail("연결 실패 " + host() + " (" + e.getClass().getSimpleName() + ")");
                destroy(false);
                return;
            }
            synchronized (this) {
                if (dead) { try { s.close(); } catch (IOException ignored) {} return; }
                sock = s;
                sendSynAck();
            }
            if (seenHosts.add("tcp:" + host())) out.log("워치 인터넷: " + host() + " 에 연결 (워치 조각 " + mss + ", 창 " + peerWnd + ")");
            synchronized (statLock) { statConns++; }
            pool.execute(this::writeLoop);
            try {
                InputStream in = s.getInputStream();
                byte[] tmp = new byte[4096];
                while (true) {
                    int room;
                    synchronized (this) {
                        while (!dead && (room = buf.length - bufLen) <= 0) wait(1000);   // 워치가 받아 갈 때까지 기다림
                        if (dead) return;
                        room = buf.length - bufLen;
                    }
                    int n = in.read(tmp, 0, Math.min(tmp.length, room));
                    synchronized (this) {
                        if (dead) return;
                        if (n < 0) { upstreamEof = true; pump(); return; }
                        System.arraycopy(tmp, 0, buf, bufLen, n);
                        bufLen += n;
                        bufferedBytes += n;
                        pump();
                    }
                }
            } catch (IOException e) {
                synchronized (this) {
                    if (dead) return;
                    send(iss + 1 + (int) sentBytes, rcvNxt, RST | ACK, null, 0, 0);
                }
                fail("끊김 " + host() + " (" + e.getClass().getSimpleName() + ")");
                destroy(false);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        void writeLoop() {
            try {
                Socket s = sock;
                OutputStream os = s.getOutputStream();
                while (true) {
                    byte[] d = toServer.take();
                    if (d == EOF) { try { s.shutdownOutput(); } catch (IOException ignored) {} return; }
                    os.write(d);
                    os.flush();
                    boolean opened;
                    synchronized (this) {
                        if (dead) return;
                        boolean wasTight = UP_QUEUE_CAP - queuedUp < 2 * mss;
                        queuedUp -= d.length;
                        opened = wasTight && UP_QUEUE_CAP - queuedUp >= 2 * mss;
                        if (opened) send(iss + 1 + (int) sentBytes + (finSent ? 1 : 0), rcvNxt, ACK, null, 0, 0);   // 창이 다시 열렸다고 알림
                    }
                }
            } catch (IOException e) {
                synchronized (this) {
                    if (dead) return;
                    send(iss + 1 + (int) sentBytes, rcvNxt, RST | ACK, null, 0, 0);
                }
                fail("쓰기 실패 " + host() + " (" + e.getClass().getSimpleName() + ")");
                destroy(false);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // ---- 워치 쪽 (모두 synchronized(this) 안에서)
        private int window() { return Math.max(0, Math.min(65535, UP_QUEUE_CAP - queuedUp)); }

        private void send(int seq, int ack, int flags, byte[] data, int off, int len) {
            out.send(tcpPacket(dst, dport, watch, cport, seq, ack, flags, window(), data, off, len, 0));
        }

        private void sendSynAck() {
            out.send(tcpPacket(dst, dport, watch, cport, iss, rcvNxt, SYN | ACK, window(), null, 0, 0, mss));
            synSent = true;
            lastProgress = System.currentTimeMillis();
        }

        synchronized void onDupSyn() {
            if (!dead && synSent && !synAcked) sendSynAck();          // 우리 답을 못 받은 것 → 다시
        }

        /** 보낼 수 있는 만큼 워치로 보냄 */
        private void pump() {
            if (dead || !synAcked) return;
            long limit = ackedBytes + Math.min(peerWnd, FLIGHT_CAP);
            while (sentBytes < bufferedBytes && sentBytes < limit) {
                int n = (int) Math.min(Math.min(mss, bufferedBytes - sentBytes), limit - sentBytes);
                if (sentBytes == ackedBytes && !finSent) lastProgress = System.currentTimeMillis();   // 기다림 시작
                boolean last = sentBytes + n == bufferedBytes;
                send(iss + 1 + (int) sentBytes, rcvNxt, ACK | (last ? PSH : 0), buf, (int) (sentBytes - ackedBytes), n);
                sentBytes += n;
                if (sentBytes > maxSent) maxSent = sentBytes;
            }
            if (upstreamEof && sentBytes == bufferedBytes && !finSent) {
                if (sentBytes == ackedBytes) lastProgress = System.currentTimeMillis();
                send(iss + 1 + (int) bufferedBytes, rcvNxt, FIN | ACK, null, 0, 0);
                finSent = true;
                finEverSent = true;
            }
        }

        synchronized void onSegment(int seq, int ack, int flags, int wnd, byte[] p, int off, int len) {
            if (dead) return;
            lastActivity = System.currentTimeMillis();
            if ((flags & RST) != 0) { destroyLocked(true); return; }
            if ((flags & SYN) != 0) { if (synSent && !synAcked) sendSynAck(); return; }
            if (!synSent) return;                                      // 아직 서버에 붙는 중
            boolean sameWnd = wnd == peerWnd;
            peerWnd = wnd;

            if ((flags & ACK) != 0) {
                int rel = ack - (iss + 1);                             // 워치가 받았다고 한 데이터 바이트 수 (아래 32비트)
                if (!synAcked) {
                    if (rel >= 0) { synAcked = true; tries = 0; rto = RTO_FIRST_MS; lastProgress = System.currentTimeMillis(); }
                    else return;
                }
                long acked = ackedBytes + (rel - (int) ackedBytes);    // 32비트 넘김을 고려해 되살림
                long top = maxSent + (finEverSent ? 1 : 0);
                if (acked > ackedBytes && acked <= top) {
                    if (finEverSent && acked == bufferedBytes + 1) { finAcked = true; finSent = true; acked = bufferedBytes; }
                    int drop = (int) (acked - ackedBytes);
                    if (drop > 0) {
                        System.arraycopy(buf, drop, buf, 0, bufLen - drop);
                        bufLen -= drop;
                        ackedBytes = acked;
                        if (sentBytes < ackedBytes) sentBytes = ackedBytes;
                        down += drop;
                        synchronized (statLock) { statDown += drop; }
                        notifyAll();                                    // 읽는 스레드 깨움
                    }
                    tries = 0;
                    rto = RTO_FIRST_MS;
                    lastProgress = System.currentTimeMillis();
                    dupAcks = 0;
                } else if (acked == ackedBytes && len == 0 && (flags & FIN) == 0 && sameWnd && sentBytes > ackedBytes) {
                    // 같은 확인이 세 번 = 중간 것이 사라졌다는 뜻 → 기다리지 않고 바로 다시 보냄
                    if (++dupAcks == 3) {
                        sentBytes = ackedBytes;
                        if (!finAcked) finSent = false;
                        retransmits++;
                        lastProgress = System.currentTimeMillis();
                    }
                }
            }
            if (!synAcked) return;

            boolean fin = (flags & FIN) != 0;
            if (len > 0 || fin) {
                int skip = rcvNxt - seq;                               // 이미 받은 앞부분
                if (skip >= 0 && skip <= len && !finFromWatch && (skip < len || fin)) {
                    int take = len - skip;
                    if (take > 0) {
                        if (take > UP_QUEUE_CAP - queuedUp) take = Math.max(0, UP_QUEUE_CAP - queuedUp);   // 넘치면 받은 만큼만
                        if (take > 0) {
                            byte[] d = new byte[take];
                            System.arraycopy(p, off + skip, d, 0, take);
                            queuedUp += take;
                            toServer.offer(d);
                            rcvNxt += take;
                            up += take;
                            synchronized (statLock) { statUp += take; }
                        }
                    }
                    if (fin && take == len - skip) {                    // 데이터를 다 받았을 때만 끝 표시도 받음
                        rcvNxt += 1;
                        finFromWatch = true;
                        toServer.offer(EOF);
                    }
                }
                send(iss + 1 + (int) sentBytes + (finSent ? 1 : 0), rcvNxt, ACK, null, 0, 0);   // 받은 데까지 확인 (중복·건너뜀도 같은 답)
            }
            pump();
            if (finAcked && finFromWatch) destroyLocked(true);
            else if (finAcked && closedAt == 0) closedAt = System.currentTimeMillis();
        }

        /** 0.5초마다: 오래 답이 없으면 다시 보내고, 너무 오래면 끊음 */
        synchronized void onTick(long now) {
            if (dead) return;
            if (now - lastActivity > IDLE_MS && now - lastProgress > IDLE_MS) { abort("오래 조용해서 닫음 " + host()); return; }
            if (closedAt != 0 && now - closedAt > LINGER_MS) { destroyLocked(true); return; }
            if (!synSent) return;
            boolean waiting = !synAcked || ackedBytes < sentBytes || (finSent && !finAcked);
            if (waiting) {
                if (now - lastProgress < rto) return;
                if (++tries > RTO_TRIES) { abort("워치가 답하지 않아 닫음 " + host()); return; }
                rto = Math.min(RTO_MAX_MS, rto * 2);
                lastProgress = now;
                retransmits++;
                if (!synAcked) { sendSynAck(); return; }
                sentBytes = ackedBytes;                                 // 확인 안 된 데부터 다시
                if (!finAcked) finSent = false;
                pump();
            } else if (peerWnd == 0 && sentBytes < bufferedBytes && now - lastProgress > 5000) {
                // 워치가 '더 못 받음'이라고 한 채 멈춤 → 한 바이트만 보내 다시 물어봄
                lastProgress = now;
                send(iss + 1 + (int) sentBytes, rcvNxt, ACK, buf, (int) (sentBytes - ackedBytes), 1);
                sentBytes += 1;
                if (sentBytes > maxSent) maxSent = sentBytes;
            }
        }

        private void abort(String why) {
            send(iss + 1 + (int) sentBytes, rcvNxt, RST | ACK, null, 0, 0);
            fail(why);
            destroyLocked(false);
        }

        void destroy(boolean clean) { synchronized (this) { destroyLocked(clean); } }

        private void destroyLocked(boolean clean) {
            if (dead) return;
            dead = true;
            conns.remove(key, this);
            notifyAll();
            toServer.clear();
            toServer.offer(EOF);
            Socket s = sock;
            if (s != null) { try { s.close(); } catch (IOException ignored) {} }
        }
    }

    private void fail(String why) {
        synchronized (statLock) { statFails++; }
        logOnce("fail:" + why, "워치 인터넷: " + why);
    }

    private void tick() {
        try {
            long now = System.currentTimeMillis();
            for (Conn c : conns.values()) c.onTick(now);
            // 30초마다, 오간 것이 있으면 한 줄 요약
            String line = null;
            synchronized (statLock) {
                if (statSince == 0) statSince = now;
                if (now - statSince >= 30000) {
                    if (statDown + statUp > 0 || statConns > 0)
                        line = "워치 인터넷: 최근 30초 연결 " + statConns + "개 · 받음 " + size(statDown) + " · 보냄 " + size(statUp) + (statFails > 0 ? " · 실패 " + statFails : "");
                    statDown = statUp = 0; statConns = statFails = 0; statSince = now;
                    onceKeys.clear();
                }
            }
            if (line != null) out.log(line);
        } catch (RuntimeException ignored) {}
    }

    private final java.util.Set<String> onceKeys = java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());

    /** 같은 내용은 30초에 한 번만 기록 */
    private void logOnce(String key, String line) {
        if (onceKeys.size() < 200 && onceKeys.add(key)) out.log(line);
    }

    // ---------------------------------------------------------------- 패킷 만들기

    private synchronized int nextId() { ipId = (ipId + 1) & 0xFFFF; return ipId; }

    private byte[] ipPacket(int proto, byte[] src, byte[] dst, byte[] payload) {
        byte[] o = new byte[20 + payload.length];
        o[0] = 0x45;
        put16(o, 2, o.length);
        put16(o, 4, nextId());
        o[6] = 0x40;                                                   // 조각내지 말 것
        o[8] = 64;
        o[9] = (byte) proto;
        System.arraycopy(src, 0, o, 12, 4);
        System.arraycopy(dst, 0, o, 16, 4);
        put16(o, 10, checksum(o, 0, 20, 0));
        System.arraycopy(payload, 0, o, 20, payload.length);
        return o;
    }

    private byte[] udpPacket(byte[] src, int sport, byte[] dst, int dport, byte[] data) {
        byte[] u = new byte[8 + data.length];
        put16(u, 0, sport);
        put16(u, 2, dport);
        put16(u, 4, u.length);
        System.arraycopy(data, 0, u, 8, data.length);
        long pseudo = sum(src, 0, 4) + sum(dst, 0, 4) + 17 + u.length;
        int cs = checksum(u, 0, u.length, pseudo);
        put16(u, 6, cs == 0 ? 0xFFFF : cs);
        return ipPacket(17, src, dst, u);
    }

    /** mssOpt > 0 이면 MSS 옵션을 붙인다 (SYN-ACK) */
    private byte[] tcpPacket(byte[] src, int sport, byte[] dst, int dport, int seq, int ack, int flags, int wnd,
                             byte[] data, int off, int len, int mssOpt) {
        int hl = mssOpt > 0 ? 24 : 20;
        byte[] t = new byte[hl + len];
        put16(t, 0, sport);
        put16(t, 2, dport);
        put32(t, 4, seq);
        put32(t, 8, ack);
        t[12] = (byte) ((hl / 4) << 4);
        t[13] = (byte) flags;
        put16(t, 14, wnd);
        if (mssOpt > 0) { t[20] = 2; t[21] = 4; put16(t, 22, mssOpt); }
        if (len > 0) System.arraycopy(data, off, t, hl, len);
        long pseudo = sum(src, 0, 4) + sum(dst, 0, 4) + 6 + t.length;
        put16(t, 16, checksum(t, 0, t.length, pseudo));
        return ipPacket(6, src, dst, t);
    }

    // ---------------------------------------------------------------- 도우미

    static int u16(byte[] b, int i) { return ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF); }
    static int i32(byte[] b, int i) { return ((b[i] & 0xFF) << 24) | ((b[i + 1] & 0xFF) << 16) | ((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF); }
    static void put16(byte[] b, int i, int v) { b[i] = (byte) (v >>> 8); b[i + 1] = (byte) v; }
    static void put32(byte[] b, int i, int v) { b[i] = (byte) (v >>> 24); b[i + 1] = (byte) (v >>> 16); b[i + 2] = (byte) (v >>> 8); b[i + 3] = (byte) v; }

    static long sum(byte[] b, int off, int len) {
        long s = 0;
        for (int i = 0; i + 1 < len; i += 2) s += u16(b, off + i);
        if ((len & 1) != 0) s += (b[off + len - 1] & 0xFF) << 8;
        return s;
    }

    static int checksum(byte[] b, int off, int len, long initial) {
        long s = initial + sum(b, off, len);
        while ((s >> 16) != 0) s = (s & 0xFFFF) + (s >> 16);
        return (int) (~s & 0xFFFF);
    }

    static String ip(byte[] a) { return (a[0] & 0xFF) + "." + (a[1] & 0xFF) + "." + (a[2] & 0xFF) + "." + (a[3] & 0xFF); }

    private static boolean isBroadcast(byte[] a) {
        return (a[0] & 0xFF) == 255 && (a[1] & 0xFF) == 255 && (a[2] & 0xFF) == 255 && (a[3] & 0xFF) == 255;
    }

    private static String size(long n) {
        if (n < 1024) return n + "B";
        if (n < 1024 * 1024) return (n / 1024) + "KB";
        return String.format(java.util.Locale.US, "%.1fMB", n / 1048576.0);
    }

    /** DNS 글자 풀기와, 바깥 DNS가 막혔을 때 폰의 이름 풀이로 대신 답하기 */
    static final class DnsFallback {
        private DnsFallback() {}

        /** 질문(또는 답)의 첫 이름. 풀 수 없으면 null */
        static String name(byte[] m) {
            if (m == null || m.length < 17 || u16(m, 4) < 1) return null;
            StringBuilder sb = new StringBuilder();
            int i = 12;
            while (i < m.length) {
                int l = m[i] & 0xFF;
                if (l == 0) break;
                if (l > 63 || i + 1 + l > m.length) return null;
                if (sb.length() > 0) sb.append('.');
                for (int k = 0; k < l; k++) sb.append((char) (m[i + 1 + k] & 0x7F));
                i += 1 + l;
            }
            return sb.length() == 0 ? null : sb.toString();
        }

        private static int questionEnd(byte[] m) {
            int i = 12;
            while (i < m.length && (m[i] & 0xFF) != 0) {
                if ((m[i] & 0xC0) != 0) return -1;
                i += 1 + (m[i] & 0xFF);
            }
            return i + 5 <= m.length ? i + 5 : -1;                    // 0 + 종류 2 + 분류 2
        }

        /** 답에 든 IPv4 주소들 */
        static java.util.List<byte[]> addresses(byte[] m) {
            java.util.List<byte[]> r = new ArrayList<>();
            if (m == null || m.length < 12) return r;
            int an = u16(m, 6), i = questionEnd(m);
            if (i < 0) return r;
            for (int n = 0; n < an && i < m.length; n++) {
                while (i < m.length) {                                 // 이름 건너뛰기
                    int l = m[i] & 0xFF;
                    if ((l & 0xC0) == 0xC0) { i += 2; break; }
                    if (l == 0) { i += 1; break; }
                    i += 1 + l;
                }
                if (i + 10 > m.length) break;
                int type = u16(m, i), rd = u16(m, i + 8);
                i += 10;
                if (i + rd > m.length) break;
                if (type == 1 && rd == 4) { byte[] a = new byte[4]; System.arraycopy(m, i, a, 0, 4); r.add(a); }
                i += rd;
            }
            return r;
        }

        /** 폰의 이름 풀이로 만든 답. A(1) 질문만 주소를 넣고, 그 밖에는 '없음'으로 답한다 */
        static byte[] answer(byte[] q) {
            String n = name(q);
            int qe = questionEnd(q);
            if (n == null || qe < 0) return null;
            int qtype = u16(q, qe - 4);
            java.util.List<byte[]> addrs = new ArrayList<>();
            boolean failed = false;
            if (qtype == 1) {
                try {
                    for (InetAddress a : InetAddress.getAllByName(n)) if (a.getAddress().length == 4 && addrs.size() < 6) addrs.add(a.getAddress());
                } catch (IOException e) { failed = true; }
            }
            byte[] o = new byte[qe + addrs.size() * 16];
            System.arraycopy(q, 0, o, 0, qe);
            o[2] = (byte) (0x81 | (q[2] & 0x01));                      // 답, 되묻기 원함 유지
            o[3] = (byte) (0x80 | (failed ? 3 : 0));                   // 되묻기 가능, 실패면 '그런 이름 없음'
            put16(o, 4, 1);
            put16(o, 6, addrs.size());
            put16(o, 8, 0);
            put16(o, 10, 0);
            int i = qe;
            for (byte[] a : addrs) {
                o[i] = (byte) 0xC0; o[i + 1] = 12;                     // 이름 = 질문의 이름
                put16(o, i + 2, 1);
                put16(o, i + 4, 1);
                put32(o, i + 6, 60);
                put16(o, i + 10, 4);
                System.arraycopy(a, 0, o, i + 12, 4);
                i += 16;
            }
            return o;
        }
    }
}
