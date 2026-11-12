package io.github.hugdoublek.rw6hub;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;

/** 샤오미 클라우드 요청 서명·RC4 암호화 (안드로이드 의존 없음). */
public final class MiSign {
    private MiSign() {}

    private static final Base64.Encoder B64 = Base64.getEncoder();
    private static final Base64.Decoder B64D = Base64.getDecoder();

    /** nonce = base64(난수 8바이트 ‖ 현재 분(minute) 32비트 빅엔디언) */
    public static String nonce(long nowMillis) {
        ByteBuffer b = ByteBuffer.allocate(12);
        b.put(MiCrypto.random(8));
        b.putInt((int) (nowMillis / 60_000L));
        return B64.encodeToString(b.array());
    }

    /** signedNonce = base64(SHA256(ssecurity ‖ nonce)) */
    public static String signedNonce(String ssecurity, String nonce) {
        return B64.encodeToString(MiCrypto.digest("SHA-256", B64D.decode(ssecurity), B64D.decode(nonce)));
    }

    /** base64(SHA1("POST&경로&k=v&...&signedNonce")), 키는 사전순 */
    public static String sha1Sign(String method, String path, Map<String, String> params, String signedNonce) {
        StringBuilder sb = new StringBuilder(method.toUpperCase()).append('&').append(path);
        for (Map.Entry<String, String> e : new TreeMap<>(params).entrySet()) {
            sb.append('&').append(e.getKey()).append('=').append(e.getValue());
        }
        sb.append('&').append(signedNonce);
        return B64.encodeToString(MiCrypto.digest("SHA-1", sb.toString().getBytes(StandardCharsets.UTF_8)));
    }

    /** 보낼 폼 필드(암호화된 값 + rc4_hash__ + signature + _nonce)를 만든다. */
    public static Map<String, String> encryptParams(String method, String path, Map<String, String> plain,
                                                    String ssecurity, String nonce) {
        String sn = signedNonce(ssecurity, nonce);
        TreeMap<String, String> p = new TreeMap<>(plain);
        p.put("rc4_hash__", sha1Sign(method, path, p, sn));
        MiCrypto.Rc4 rc4 = new MiCrypto.Rc4(B64D.decode(sn));
        TreeMap<String, String> enc = new TreeMap<>();
        for (Map.Entry<String, String> e : p.entrySet()) {
            enc.put(e.getKey(), B64.encodeToString(rc4.crypt(e.getValue().getBytes(StandardCharsets.UTF_8))));
        }
        String signature = sha1Sign(method, path, enc, sn);
        enc.put("signature", signature);
        enc.put("_nonce", nonce);
        return enc;
    }

    public static String decryptResponse(String body, String ssecurity, String nonce) {
        String sn = signedNonce(ssecurity, nonce);
        MiCrypto.Rc4 rc4 = new MiCrypto.Rc4(B64D.decode(sn));
        String t = body.trim();
        if (t.startsWith("\"") && t.endsWith("\"")) t = t.substring(1, t.length() - 1);
        return new String(rc4.crypt(B64D.decode(t)), StandardCharsets.UTF_8);
    }
}
