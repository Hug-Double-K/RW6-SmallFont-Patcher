package io.github.hugdoublek.rw6hub;

/** 16진수 문자열 ↔ 바이트 변환. */
public final class Hex {
    private Hex() {}

    private static final char[] DIGITS = "0123456789abcdef".toCharArray();

    public static String of(byte[] b) {
        if (b == null) return "";
        char[] out = new char[b.length * 2];
        for (int i = 0; i < b.length; i++) {
            out[i * 2] = DIGITS[(b[i] >> 4) & 0xf];
            out[i * 2 + 1] = DIGITS[b[i] & 0xf];
        }
        return new String(out);
    }

    /** 공백·콜론·0x 접두어는 무시. 잘못된 문자가 있으면 null. */
    public static byte[] parse(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.startsWith("0x") || t.startsWith("0X")) t = t.substring(2);
        t = t.replace(":", "").replace("-", "").replace(" ", "");
        if (t.length() % 2 != 0) return null;
        byte[] out = new byte[t.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(t.charAt(i * 2), 16);
            int lo = Character.digit(t.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) return null;
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    /** 인증키로 쓸 수 있는 32자리 16진수인지 확인하고 소문자로 정리. 아니면 null. */
    public static String normalizeKey(String s) {
        byte[] b = parse(s);
        if (b == null || b.length != 16) return null;
        return of(b);
    }
}
