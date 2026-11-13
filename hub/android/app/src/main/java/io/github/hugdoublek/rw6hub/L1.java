package io.github.hugdoublek.rw6hub;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * SAR v2 1계층 프레임.
 * A5 A5 | type(하위4비트)+frx(비트4) | seq | len(LE16) | crc16(LE16, 페이로드) | 페이로드
 */
public final class L1 {
    public static final int NAK = 0, ACK = 1, CMD = 2, DATA = 3;
    public static final int HEADER = 8;

    public final int type;
    public final boolean frx;
    public final int seq;
    public final byte[] payload;

    public L1(int type, boolean frx, int seq, byte[] payload) {
        this.type = type;
        this.frx = frx;
        this.seq = seq & 0xFF;
        this.payload = payload == null ? new byte[0] : payload;
    }

    /** CRC-16/ARC (다항식 0xA001, 초기값 0). */
    public static int crc16(byte[] d, int off, int len) {
        int crc = 0;
        for (int i = off; i < off + len; i++) {
            crc ^= d[i] & 0xFF;
            for (int k = 0; k < 8; k++) crc = (crc & 1) != 0 ? (crc >>> 1) ^ 0xA001 : crc >>> 1;
        }
        return crc & 0xFFFF;
    }

    public byte[] toBytes() {
        int len = payload.length;
        int crc = crc16(payload, 0, len);
        byte[] out = new byte[HEADER + len];
        out[0] = (byte) 0xA5;
        out[1] = (byte) 0xA5;
        out[2] = (byte) ((type & 0x0F) | (frx ? 0x10 : 0));
        out[3] = (byte) seq;
        out[4] = (byte) len;
        out[5] = (byte) (len >>> 8);
        out[6] = (byte) crc;
        out[7] = (byte) (crc >>> 8);
        System.arraycopy(payload, 0, out, HEADER, len);
        return out;
    }

    // ---------------- 1계층 명령 (세션 시작) ----------------
    public static final int CMD_START_REQ = 1, CMD_START_RSP = 2;

    /** 시작 명령: 버전 1.0.0, 최대 패킷 64512, 창 32, 재전송 10초 (안드로이드 공식 앱 값). */
    public static byte[] startCmd(int cmd) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(cmd);
        tlv(b, 1, new byte[]{1, 0, 0});
        tlv(b, 2, le16(64512));
        tlv(b, 3, le16(32));
        tlv(b, 4, le16(10000));
        return b.toByteArray();
    }

    private static void tlv(ByteArrayOutputStream b, int key, byte[] v) {
        b.write(key);
        b.write(v.length & 0xFF);
        b.write((v.length >>> 8) & 0xFF);
        b.write(v, 0, v.length);
    }

    private static byte[] le16(int v) {
        return new byte[]{(byte) v, (byte) (v >>> 8)};
    }

    // ---------------- 수신 버퍼에서 프레임 잘라내기 ----------------
    public static final class Parser {
        private byte[] buf = new byte[0];
        /** 프레임으로 못 읽고 버린 바이트 수 (진단용) */
        public long skipped = 0;

        private static boolean plausible(byte[] b, int i) {
            return (b[i] & 0xFF) == 0xA5 && (b[i + 1] & 0xFF) == 0xA5
                    && (b[i + 2] & 0x0F) <= 3 && (b[i + 2] & 0xE0) == 0;
        }

        private static int frameLen(byte[] b, int i, int end) {
            if (i + HEADER > end || !plausible(b, i)) return -1;
            int len = (b[i + 4] & 0xFF) | ((b[i + 5] & 0xFF) << 8);
            if (i + HEADER + len > end) return -1;
            int crc = (b[i + 6] & 0xFF) | ((b[i + 7] & 0xFF) << 8);
            return crc16(b, i + HEADER, len) == crc ? HEADER + len : -1;
        }

        /** 새로 받은 바이트를 붙이고, 완성된(CRC 맞는) 프레임을 모두 돌려준다. */
        public List<L1> feed(byte[] data, int n) {
            byte[] nb = new byte[buf.length + n];
            System.arraycopy(buf, 0, nb, 0, buf.length);
            System.arraycopy(data, 0, nb, buf.length, n);
            buf = nb;

            List<L1> frames = new ArrayList<>();
            int i = 0;
            int end = buf.length;
            while (i + HEADER <= end) {
                if (!plausible(buf, i)) { i++; skipped++; continue; }
                int len = (buf[i + 4] & 0xFF) | ((buf[i + 5] & 0xFF) << 8);
                if (i + HEADER + len > end) {
                    // 아직 덜 왔음. 뒤에 완성된 진짜 프레임이 있으면 이 머리는 쓰레기로 보고 건너뜀
                    int next = -1;
                    for (int k = i + 1; k + HEADER <= end; k++) {
                        if (frameLen(buf, k, end) > 0) { next = k; break; }
                    }
                    if (next < 0) break;
                    skipped += next - i;
                    i = next;
                    continue;
                }
                int total = frameLen(buf, i, end);
                if (total < 0) { i++; skipped++; continue; }
                byte[] p = new byte[len];
                System.arraycopy(buf, i + HEADER, p, 0, len);
                frames.add(new L1(buf[i + 2] & 0x0F, (buf[i + 2] & 0x10) != 0, buf[i + 3] & 0xFF, p));
                i += total;
            }
            if (i > 0) {
                byte[] rest = new byte[end - i];
                System.arraycopy(buf, i, rest, 0, rest.length);
                buf = rest;
            }
            return frames;
        }
    }
}
