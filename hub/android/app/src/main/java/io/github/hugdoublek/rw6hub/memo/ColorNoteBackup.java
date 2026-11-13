package io.github.hugdoublek.rw6hub.memo;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 컬러노트 .backup 파일 해독 (공개된 해독 방식과 동일)
 *  - AES-128-CBC / PKCS7, 고정 솔트 "ColorNote Fixed Salt"
 *  - key = MD5(password + salt), iv = MD5(key + password + salt)
 *  - 앞 28바이트는 평문 헤더라 건너뜀
 *  - 해독된 본문에서 첫 {"_id":숫자 앞 4바이트(길이)부터, [길이(4, big-endian) + JSON] 레코드가 이어짐
 * 참고: github.com/BeLi4L/ColorNote-backup-decryptor
 */
public final class ColorNoteBackup {
    private static final byte[] SALT = "ColorNote Fixed Salt".getBytes(StandardCharsets.UTF_8);

    /** 마지막으로 성공한 방법 (화면 표시용) */
    public static String lastMethod = "";

    public static List<String> decryptRecords(InputStream in, String password) throws Exception {
        byte[] all = readAll(in);
        if (all.length < 48) throw new IOException("백업 파일이 너무 작아요 (" + all.length + "바이트) — 다른 파일을 고른 것 같아요");

        // 암호화되지 않은 파일이면 그대로 읽기
        try { List<String> r = records(all); if (!r.isEmpty()) { lastMethod = "암호화 없음"; return r; } } catch (IOException ignored) { }
        if (all[0] == 'P' && all[1] == 'K') throw new IOException("압축(zip) 파일이에요 — 컬러노트의 '.backup' 파일을 골라 주세요");

        // 입력한 비밀번호 → 0000 → 빈 비밀번호 순서로, 헤더 길이도 여러 가지 시도
        List<String> pws = new ArrayList<>();
        if (password != null) pws.add(password);
        if (!pws.contains("0000")) pws.add("0000");
        if (!pws.contains("")) pws.add("");
        int[] offsets = {28, 0, 16, 32, 12, 20, 24, 44, 48, 60, 64};
        for (String pwStr : pws) {
            byte[] pw = pwStr.getBytes(StandardCharsets.UTF_8);
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            md5.update(pw); md5.update(SALT);
            byte[] key = md5.digest();
            md5.reset();
            md5.update(key); md5.update(pw); md5.update(SALT);
            byte[] iv = md5.digest();
            for (int offset : offsets) {
                if ((all.length - offset) % 16 != 0 || all.length - offset < 32) continue;
                byte[] plain;
                try {
                    Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
                    c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
                    plain = c.doFinal(all, offset, all.length - offset);
                } catch (Exception e) { continue; }
                try {
                    List<String> r = records(plain);
                    if (!r.isEmpty()) {
                        lastMethod = (pwStr.equals(password) ? "입력한 비밀번호" : pwStr.isEmpty() ? "빈 비밀번호" : "기본 비밀번호 0000") + ", 헤더 " + offset;
                        return r;
                    }
                } catch (IOException ignored) { }
            }
        }
        throw new IOException("해독 실패 — 백업 비밀번호가 다를 수 있어요\n" + describe(all));
    }

    /** 파일 앞부분 정보 (문제 확인용) */
    static String describe(byte[] all) {
        StringBuilder b = new StringBuilder("[파일 정보] 크기 " + all.length + "바이트, 16으로 나눈 나머지 " + (all.length % 16) + "\n앞부분: ");
        for (int i = 0; i < Math.min(32, all.length); i++) b.append(String.format("%02x", all[i] & 0xff));
        b.append("\n글자: ");
        for (int i = 0; i < Math.min(32, all.length); i++) { int ch = all[i] & 0xff; b.append(ch >= 32 && ch < 127 ? (char) ch : '.'); }
        return b.toString();
    }

    static List<String> records(byte[] data) throws IOException {
        int start = indexOf(data, "{\"_id\":".getBytes(StandardCharsets.UTF_8));
        while (start >= 0 && !digitAfter(data, start + 7)) {
            start = indexOf(data, "{\"_id\":".getBytes(StandardCharsets.UTF_8), start + 1);
        }
        if (start < 4) throw new IOException("메모를 찾지 못했어요 — 백업 비밀번호가 다를 수 있어요");
        List<String> out = new ArrayList<>();
        int p = start - 4;
        List<String> scanned = scan(data, start);
        while (p + 4 <= data.length) {
            long len = ((data[p] & 0xffL) << 24) | ((data[p + 1] & 0xffL) << 16) | ((data[p + 2] & 0xffL) << 8) | (data[p + 3] & 0xffL);
            p += 4;
            if (len == 0 || p + len > data.length) break;
            out.add(new String(data, p, (int) len, StandardCharsets.UTF_8));
            p += (int) len;
        }
        return scanned.size() > out.size() ? scanned : out;
    }

    /** 길이 정보가 맞지 않을 때를 대비해 {"_id": 로 시작하는 JSON 덩어리를 직접 찾아낸다 */
    static List<String> scan(byte[] d, int from) {
        List<String> out = new ArrayList<>();
        byte[] mark = "{\"_id\":".getBytes(StandardCharsets.UTF_8);
        int i = from;
        while (i >= 0 && i < d.length) {
            int depth = 0, j = i; boolean str = false, esc = false;
            for (; j < d.length; j++) {
                byte ch = d[j];
                if (str) { if (esc) esc = false; else if (ch == '\\') esc = true; else if (ch == '"') str = false; continue; }
                if (ch == '"') str = true;
                else if (ch == '{') depth++;
                else if (ch == '}') { if (--depth == 0) break; }
            }
            if (j >= d.length) break;
            out.add(new String(d, i, j - i + 1, StandardCharsets.UTF_8));
            i = indexOf(d, mark, j + 1);
        }
        return out;
    }

    private static boolean digitAfter(byte[] d, int i) {
        while (i < d.length && d[i] == ' ') i++;
        return i < d.length && Character.isDigit(d[i]);
    }

    private static int indexOf(byte[] a, byte[] b) { return indexOf(a, b, 0); }

    private static int indexOf(byte[] a, byte[] b, int from) {
        outer:
        for (int i = Math.max(0, from); i <= a.length - b.length; i++) {
            for (int j = 0; j < b.length; j++) if (a[i + j] != b[j]) continue outer;
            return i;
        }
        return -1;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toByteArray();
    }
}
