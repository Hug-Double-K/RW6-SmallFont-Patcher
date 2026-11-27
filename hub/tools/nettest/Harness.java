import io.github.hugdoublek.rw6hub.NetRelay;
import io.github.hugdoublek.rw6hub.WatchDrop;
import java.io.*;
import java.net.*;

/** 시험용: bridge.py 와 소켓으로 이어 NetRelay 를 돌린다. 10.1.10.5x 를 localhost 로 바꿔 붙인다. */
public class Harness {
    public static void main(String[] a) throws Exception {
        int port = Integer.parseInt(a[0]);
        ServerSocket ss = new ServerSocket(port, 1, InetAddress.getByName("127.0.0.1"));
        System.out.println("자바 준비"); System.out.flush();
        Socket s = ss.accept();
        s.setTcpNoDelay(true);
        final DataOutputStream os = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
        DataInputStream is = new DataInputStream(new BufferedInputStream(s.getInputStream()));
        final long[] cnt = new long[2];
        NetRelay relay = new NetRelay(new NetRelay.Out() {
            public void send(byte[] p) {
                synchronized (os) { try { os.writeShort(p.length); os.write(p); os.flush(); cnt[0]++; } catch (IOException e) {} }
            }
            public void log(String l) { System.out.println("[기록] " + l); System.out.flush(); }
        }, new NetRelay.AddressMap() {
            public InetSocketAddress tcp(InetAddress x, int p) {
                String h = x.getHostAddress();
                if (h.equals("10.1.10.1") && p == 80) {
                    try { WatchDrop d = WatchDrop.get(); WatchDrop.dir = new File("drop"); d.setSink(l -> { System.out.println("[기록] " + l); System.out.flush(); }); return new InetSocketAddress("127.0.0.1", d.port()); }
                    catch (IOException e) { throw new RuntimeException(e); }
                }
                if (h.equals("10.1.10.50")) return new InetSocketAddress("127.0.0.1", p);
                if (h.equals("10.1.10.59")) return new InetSocketAddress("10.255.255.1", p);   // 닿지 않는 곳
                return new InetSocketAddress(x, p);
            }
            public InetSocketAddress udp(InetAddress x, int p) {
                String h = x.getHostAddress();
                if (h.equals("10.1.10.53")) return new InetSocketAddress("127.0.0.1", 5353);    // 가짜 DNS
                if (h.equals("10.1.10.54")) return new InetSocketAddress("127.0.0.1", 5999);    // 답 없는 DNS
                if (h.equals("10.1.10.50")) return new InetSocketAddress("127.0.0.1", p);
                return new InetSocketAddress(x, p);
            }
        });
        Thread st = new Thread(() -> { try { while (true) { Thread.sleep(2000); System.out.println("[상태] 다시 보냄 " + relay.retransmits() + " · 열린 연결 " + relay.openConnections()); System.out.flush(); } } catch (InterruptedException e) {} });
        st.setDaemon(true); st.start();
        try {
            while (true) {
                int n = is.readUnsignedShort();
                byte[] p = new byte[n];
                is.readFully(p);
                cnt[1]++;
                relay.onPacket(p);
            }
        } catch (EOFException e) {}
        relay.close();
    }
}
