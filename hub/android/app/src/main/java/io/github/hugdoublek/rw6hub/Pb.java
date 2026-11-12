package io.github.hugdoublek.rw6hub;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 아주 작은 Protobuf 인코더/디코더.
 * 워치 메시지는 필드 몇 개만 쓰므로 라이브러리 없이 직접 다룬다.
 */
public final class Pb {
    private Pb() {}

    public static final int VARINT = 0;
    public static final int LEN = 2;

    // ---------------- 쓰기 ----------------
    public static final class W {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        private void raw(long v) {
            while ((v & ~0x7FL) != 0) {
                out.write((int) ((v & 0x7F) | 0x80));
                v >>>= 7;
            }
            out.write((int) v);
        }

        public W varint(int field, long value) {
            raw(((long) field << 3) | VARINT);
            raw(value);
            return this;
        }

        public W bool(int field, boolean value) {
            return varint(field, value ? 1 : 0);
        }

        public W bytes(int field, byte[] value) {
            raw(((long) field << 3) | LEN);
            raw(value.length);
            out.write(value, 0, value.length);
            return this;
        }

        public W str(int field, String value) {
            return bytes(field, value.getBytes(StandardCharsets.UTF_8));
        }

        public W msg(int field, W sub) {
            return bytes(field, sub.toBytes());
        }

        public byte[] toBytes() {
            return out.toByteArray();
        }
    }

    // ---------------- 읽기 ----------------
    public static final class Field {
        public final int number;
        public final int wireType;
        public final long varint;
        public final byte[] bytes;

        Field(int number, int wireType, long varint, byte[] bytes) {
            this.number = number;
            this.wireType = wireType;
            this.varint = varint;
            this.bytes = bytes;
        }

        public String str() {
            return bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8);
        }
    }

    /** 최상위 필드 목록. 형식이 깨지면 IllegalArgumentException. */
    public static List<Field> parse(byte[] buf) {
        List<Field> list = new ArrayList<>();
        int[] pos = {0};
        while (pos[0] < buf.length) {
            long key = readVarint(buf, pos);
            int number = (int) (key >>> 3);
            int type = (int) (key & 7);
            switch (type) {
                case 0:
                    list.add(new Field(number, type, readVarint(buf, pos), null));
                    break;
                case 1: {
                    need(buf, pos[0], 8);
                    long v = 0;
                    for (int i = 7; i >= 0; i--) v = (v << 8) | (buf[pos[0] + i] & 0xFF);
                    pos[0] += 8;
                    list.add(new Field(number, type, v, null));
                    break;
                }
                case 2: {
                    int n = (int) readVarint(buf, pos);
                    need(buf, pos[0], n);
                    byte[] b = new byte[n];
                    System.arraycopy(buf, pos[0], b, 0, n);
                    pos[0] += n;
                    list.add(new Field(number, type, 0, b));
                    break;
                }
                case 5: {
                    need(buf, pos[0], 4);
                    long v = (buf[pos[0]] & 0xFFL) | ((buf[pos[0] + 1] & 0xFFL) << 8)
                            | ((buf[pos[0] + 2] & 0xFFL) << 16) | ((buf[pos[0] + 3] & 0xFFL) << 24);
                    pos[0] += 4;
                    list.add(new Field(number, type, v, null));
                    break;
                }
                default:
                    throw new IllegalArgumentException("protobuf wire type " + type);
            }
        }
        return list;
    }

    public static Field find(List<Field> fields, int number) {
        for (Field f : fields) if (f.number == number) return f;
        return null;
    }

    /** 하위 메시지를 꺼내 파싱. 없으면 null. */
    public static List<Field> sub(List<Field> fields, int number) {
        Field f = find(fields, number);
        if (f == null || f.bytes == null) return null;
        return parse(f.bytes);
    }

    private static void need(byte[] buf, int pos, int n) {
        if (n < 0 || pos + n > buf.length) throw new IllegalArgumentException("protobuf truncated");
    }

    private static long readVarint(byte[] buf, int[] pos) {
        long result = 0;
        int shift = 0;
        while (true) {
            if (pos[0] >= buf.length) throw new IllegalArgumentException("protobuf varint truncated");
            int b = buf[pos[0]++] & 0xFF;
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return result;
            shift += 7;
            if (shift > 63) throw new IllegalArgumentException("protobuf varint too long");
        }
    }
}
