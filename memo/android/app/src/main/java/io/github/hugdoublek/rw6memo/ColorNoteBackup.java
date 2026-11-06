package io.github.hugdoublek.rw6memo;

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

    public static List<String> decryptRecords(InputStream in, String password) throws Exception {
        byte[] all = readAll(in);
        int offset = 28;
        if (all.length <= offset + 16) throw new IOException("백업 파일이 너무 작아요");
        byte[] pw = password.getBytes(StandardCharsets.UTF_8);

        MessageDigest md5 = MessageDigest.getInstance("MD5");
        md5.update(pw); md5.update(SALT);
        byte[] key = md5.digest();
        md5.reset();
        md5.update(key); md5.update(pw); md5.update(SALT);
        byte[] iv = md5.digest();

        Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        byte[] plain;
        try {
            plain = c.doFinal(all, offset, all.length - offset);
        } catch (Exception e) {
            throw new IOException("해독 실패 — 백업 비밀번호가 다를 수 있어요");
        }
        return records(plain);
    }

    static List<String> records(byte[] data) throws IOException {
        int start = indexOf(data, "{\"_id\":".getBytes(StandardCharsets.UTF_8));
        while (start >= 0 && (start + 7 >= data.length || !Character.isDigit(data[start + 7]))) {
            start = indexOf(data, "{\"_id\":".getBytes(StandardCharsets.UTF_8), start + 1);
        }
        if (start < 4) throw new IOException("메모를 찾지 못했어요 — 백업 비밀번호가 다를 수 있어요");
        List<String> out = new ArrayList<>();
        int p = start - 4;
        while (p + 4 <= data.length) {
            long len = ((data[p] & 0xffL) << 24) | ((data[p + 1] & 0xffL) << 16) | ((data[p + 2] & 0xffL) << 8) | (data[p + 3] & 0xffL);
            p += 4;
            if (len == 0 || p + len > data.length) break;
            out.add(new String(data, p, (int) len, StandardCharsets.UTF_8));
            p += (int) len;
        }
        return out;
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
