package io.github.hugdoublek.rw6hub;

/**
 * 워치가 블루투스 네트워크 통로(채널 7)로 보내는 DHCP 요청에 답한다.
 * 워치는 IP 주소를 못 받으면 1분쯤 뒤 연결을 끊는 것으로 보여, 주소만이라도 먼저 준다.
 * (실제 인터넷 중계는 4단계)
 * 워치 10.1.10.2 / 폰(게이트웨이) 10.1.10.1
 */
public final class Dhcp {
    private Dhcp() {}

    public static final byte[] ROUTER = {10, 1, 10, 1};
    public static final byte[] CLIENT = {10, 1, 10, 2};
    private static final byte[][] DNS = {{8, 8, 8, 8}, {1, 1, 1, 1}};

    /** DHCP 요청이면 응답 IP 패킷을, 아니면 null. type[0]에 요청 종류(1=Discover, 3=Request)를 넣어 준다. */
    public static byte[] reply(byte[] ip, int[] type) {
        if (ip.length < 20 || (ip[0] & 0xF0) != 0x40 || (ip[9] & 0xFF) != 17) return null;
        int ihl = (ip[0] & 0x0F) * 4;
        if (ip.length < ihl + 8 + 240) return null;
        int sport = u16(ip, ihl), dport = u16(ip, ihl + 2);
        if (sport != 68 || dport != 67) return null;
        int d = ihl + 8;
        if ((ip[d] & 0xFF) != 1) return null; // BOOTREQUEST
        if ((ip[d + 236] & 0xFF) != 99 || (ip[d + 237] & 0xFF) != 130 || (ip[d + 238] & 0xFF) != 83 || (ip[d + 239] & 0xFF) != 99) return null;

        int msgType = 0;
        for (int i = d + 240; i < ip.length; ) {
            int code = ip[i] & 0xFF;
            if (code == 255) break;
            if (code == 0) { i++; continue; }
            if (i + 1 >= ip.length) break;
            int len = ip[i + 1] & 0xFF;
            if (code == 53 && len >= 1 && i + 2 < ip.length) msgType = ip[i + 2] & 0xFF;
            i += 2 + len;
        }
        int replyType;
        if (msgType == 1) replyType = 2;       // DISCOVER → OFFER
        else if (msgType == 3) replyType = 5;  // REQUEST → ACK
        else return null;
        if (type != null && type.length > 0) type[0] = msgType;

        // DHCP 본문
        byte[] opts = new byte[3 + 6 + 6 + 2 + 4 * DNS.length + 6 + 6 + 1];
        int o = 0;
        opts[o++] = 53; opts[o++] = 1; opts[o++] = (byte) replyType;
        opts[o++] = 1; opts[o++] = 4; opts[o++] = (byte) 255; opts[o++] = (byte) 255; opts[o++] = (byte) 255; opts[o++] = 0;
        opts[o++] = 3; opts[o++] = 4; System.arraycopy(ROUTER, 0, opts, o, 4); o += 4;
        opts[o++] = 6; opts[o++] = (byte) (4 * DNS.length);
        for (byte[] dns : DNS) { System.arraycopy(dns, 0, opts, o, 4); o += 4; }
        opts[o++] = 51; opts[o++] = 4; opts[o++] = 0x10; opts[o++] = 0; opts[o++] = 0; opts[o++] = 0; // 임대 약 3년
        opts[o++] = 54; opts[o++] = 4; System.arraycopy(ROUTER, 0, opts, o, 4); o += 4;
        opts[o++] = (byte) 255;

        byte[] dhcp = new byte[240 + opts.length];
        dhcp[0] = 2;                                    // BOOTREPLY
        dhcp[1] = ip[d + 1]; dhcp[2] = ip[d + 2];       // htype, hlen
        System.arraycopy(ip, d + 4, dhcp, 4, 4);        // xid
        System.arraycopy(CLIENT, 0, dhcp, 16, 4);       // yiaddr
        System.arraycopy(ROUTER, 0, dhcp, 20, 4);       // siaddr
        System.arraycopy(ip, d + 28, dhcp, 28, 16);     // chaddr
        dhcp[236] = 99; dhcp[237] = (byte) 130; dhcp[238] = 83; dhcp[239] = 99;
        System.arraycopy(opts, 0, dhcp, 240, opts.length);

        byte[] src = ROUTER, dst = {(byte) 255, (byte) 255, (byte) 255, (byte) 255};
        int udpLen = 8 + dhcp.length;
        byte[] out = new byte[20 + udpLen];
        out[0] = 0x45;
        put16(out, 2, out.length);
        out[8] = 64;
        out[9] = 17;
        System.arraycopy(src, 0, out, 12, 4);
        System.arraycopy(dst, 0, out, 16, 4);
        put16(out, 10, checksum(out, 0, 20, 0));
        put16(out, 20, 67);
        put16(out, 22, 68);
        put16(out, 24, udpLen);
        System.arraycopy(dhcp, 0, out, 28, dhcp.length);
        // UDP 체크섬 (의사 헤더 포함)
        long pseudo = sum(src, 0, 4) + sum(dst, 0, 4) + 17 + udpLen;
        int cs = checksum(out, 20, udpLen, pseudo);
        put16(out, 26, cs == 0 ? 0xFFFF : cs);
        return out;
    }

    static int u16(byte[] b, int i) { return ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF); }

    static void put16(byte[] b, int i, int v) { b[i] = (byte) (v >>> 8); b[i + 1] = (byte) v; }

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
}
