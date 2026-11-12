package io.github.hugdoublek.rw6hub;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * 샤오미 계정(중국 지역)으로 Mi Fitness 서비스 토큰을 받고,
 * 계정에 묶인 기기 목록에서 워치 인증키를 꺼낸다.
 * 로그인 자체는 LoginActivity(웹 화면)에서 하고, 여기서는 그때 생긴 passToken을 쓴다.
 */
public final class MiCloud {
    private MiCloud() {}

    public static final String SDK_VERSION = "accountsdk-18.8.15";
    public static final String SID = "miothealth";
    public static final String LOGIN_PAGE =
            "https://account.xiaomi.com/pass/serviceLogin?sid=" + SID + "&_locale=ko_KR";
    private static final String SOURCE_LIST = "https://hlth.io.mi.com/app/v1/source/get_source_list";
    private static final String UA = "Android-13-9.8.348i-MiManager";

    /** 로그인 결과 (저장해 두는 값). */
    public static final class Token {
        public String userId = "", cUserId = "", passToken = "", deviceId = "";
        public String ssecurity = "", serviceToken = "";
    }

    /** 기기 목록의 한 항목. */
    public static final class Device {
        public String name = "", mac = "";
        public final List<String> keys = new ArrayList<>();
        @Override public String toString() { return name + " " + mac + " 키 " + keys.size() + "개"; }
    }

    public static final class CloudException extends Exception {
        public CloudException(String m) { super(m); }
    }

    // ------------------------------------------------------------------

    /** passToken으로 서비스 로그인 → ssecurity, serviceToken을 채운다. */
    public static void serviceLogin(Token t) throws IOException, CloudException {
        String cookie = "sdkVersion=" + SDK_VERSION + "; deviceId=" + t.deviceId
                + "; userId=" + t.userId + "; passToken=" + t.passToken
                + (t.cUserId.isEmpty() ? "" : "; cUserId=" + t.cUserId);
        HttpURLConnection c = open("https://account.xiaomi.com/pass/serviceLogin?sid=" + SID + "&_json=true");
        c.setRequestProperty("Cookie", cookie);
        Resp r = read(c);
        if (r.code != 200) throw new CloudException("serviceLogin HTTP " + r.code);
        String body = r.body.startsWith("&&&START&&&") ? r.body.substring(11) : r.body;
        JSONObject j;
        try { j = new JSONObject(body); } catch (JSONException e) { throw new CloudException("serviceLogin 응답 해석 실패"); }
        if (!j.optString("_sign").isEmpty() && j.optString("location").isEmpty()) {
            throw new CloudException("로그인이 만료됨. 다시 로그인해 주세요.");
        }
        int code = j.optInt("code", -1);
        if (code != 0) throw new CloudException("serviceLogin 실패 code=" + code + " " + j.optString("description"));

        String ssecurity = j.optString("ssecurity");
        String pragma = r.header("extension-pragma");
        if (ssecurity.isEmpty() && pragma != null) {
            try { ssecurity = new JSONObject(pragma).optString("ssecurity"); } catch (JSONException ignored) {}
        }
        if (ssecurity.isEmpty()) throw new CloudException("ssecurity 없음");
        String location = j.optString("location");
        if (location.isEmpty()) throw new CloudException("location 없음");
        if (!j.optString("cUserId").isEmpty()) t.cUserId = j.optString("cUserId");
        if (j.optLong("userId", 0) != 0) t.userId = String.valueOf(j.optLong("userId"));
        if (!j.optString("passToken").isEmpty()) t.passToken = j.optString("passToken");
        t.ssecurity = ssecurity;

        // location(sts-hlth) → serviceToken 쿠키
        HttpURLConnection s = open(location);
        s.setInstanceFollowRedirects(false);
        Resp sr = read(s);
        String token = null;
        for (String setCookie : sr.setCookies) {
            String kv = setCookie.split(";", 2)[0].trim();
            int eq = kv.indexOf('=');
            if (eq <= 0) continue;
            String name = kv.substring(0, eq), val = kv.substring(eq + 1);
            if ((name.equals("serviceToken") || name.endsWith("_serviceToken")) && !val.isEmpty()) token = val;
            if (name.equals("cUserId") && !val.isEmpty()) t.cUserId = val;
        }
        if (token == null) throw new CloudException("serviceToken을 받지 못함 (HTTP " + sr.code + ")");
        t.serviceToken = token;
    }

    /** 계정에 묶인 기기 목록 (인증키 후보 포함). */
    public static List<Device> devices(Token t, StringBuilder log) throws IOException, CloudException {
        List<Device> all = new ArrayList<>();
        for (int status : new int[]{1, 2}) {
            JSONObject data = new JSONObject();
            try {
                data.put("page_size", 50);
                data.put("status", status);
            } catch (JSONException e) { throw new IllegalStateException(e); }
            JSONObject resp = call(t, SOURCE_LIST, "/app/v1/source/get_source_list", data.toString());
            int code = resp.optInt("code", -1);
            if (code != 0 && code != 200) {
                throw new CloudException("기기 목록 실패 code=" + code + " " + resp.optString("message"));
            }
            List<Device> got = parseDevices(resp);
            if (log != null) log.append("status=").append(status).append(": 기기 ").append(got.size()).append("개\n");
            all.addAll(got);
            if (!got.isEmpty()) break;
        }
        return all;
    }

    // ------------------------------------------------------------------

    private static JSONObject call(Token t, String url, String signPath, String data)
            throws IOException, CloudException {
        String nonce = MiSign.nonce(System.currentTimeMillis());
        Map<String, String> plain = new HashMap<>();
        plain.put("data", data);
        Map<String, String> form = MiSign.encryptParams("POST", signPath, plain, t.ssecurity, nonce);

        HttpURLConnection c = open(url);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        c.setRequestProperty("region_tag", "cn");
        c.setRequestProperty("HandleParams", "true");
        c.setRequestProperty("Cookie", "sdkVersion=" + SDK_VERSION + "; locale=zh_CN; deviceId=" + t.deviceId
                + "; userId=" + t.userId + "; cUserId=" + t.cUserId + "; serviceToken=" + t.serviceToken);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : form.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(URLEncoder.encode(e.getKey(), "UTF-8")).append('=').append(URLEncoder.encode(e.getValue(), "UTF-8"));
        }
        try (OutputStream os = c.getOutputStream()) {
            os.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
        Resp r = read(c);
        if (r.code == 401) throw new CloudException("로그인이 만료됨 (401). 다시 로그인해 주세요.");
        if (r.code != 200) throw new CloudException("HTTP " + r.code);
        try {
            return new JSONObject(MiSign.decryptResponse(r.body, t.ssecurity, nonce));
        } catch (RuntimeException | JSONException e) {
            throw new CloudException("응답 복호화 실패 (로그인 만료일 수 있음)");
        }
    }

    static List<Device> parseDevices(JSONObject resp) {
        List<Device> out = new ArrayList<>();
        JSONObject result = resp.optJSONObject("result");
        JSONArray list = result != null ? result.optJSONArray("list") : null;
        if (list == null) list = resp.optJSONArray("list");
        if (list == null) return out;
        for (int i = 0; i < list.length(); i++) {
            Object item = list.opt(i);
            Device d = new Device();
            walk(item, d, 0);
            if (!d.keys.isEmpty()) out.add(d);
        }
        return out;
    }

    private static final String[] KEY_FIELDS = {"encrypt_key", "encryptKey", "auth_key", "authKey", "huamiAuthKey", "token"};

    /** 중첩 JSON(문자열로 된 JSON 포함)에서 이름·MAC·인증키 후보를 모은다. */
    private static void walk(Object o, Device d, int depth) {
        if (depth > 6 || o == null) return;
        if (o instanceof String) {
            String s = ((String) o).trim();
            if (s.startsWith("{")) {
                try { walk(new JSONObject(s), d, depth + 1); } catch (JSONException ignored) {}
            }
            return;
        }
        if (o instanceof JSONArray) {
            JSONArray a = (JSONArray) o;
            for (int i = 0; i < a.length(); i++) walk(a.opt(i), d, depth + 1);
            return;
        }
        if (!(o instanceof JSONObject)) return;
        JSONObject j = (JSONObject) o;
        for (String k : KEY_FIELDS) {
            String v = Hex.normalizeKey(j.optString(k, ""));
            if (v != null && !d.keys.contains(v)) d.keys.add(v);
        }
        for (String k : new String[]{"mac", "macAddress", "mac_address"}) {
            String v = j.optString(k, "");
            if (d.mac.isEmpty() && v.length() >= 11) d.mac = v.toUpperCase(Locale.ROOT);
        }
        for (String k : new String[]{"name", "productName", "model"}) {
            String v = j.optString(k, "");
            if (d.name.isEmpty() && !v.isEmpty() && !(j.opt(k) instanceof JSONObject)) d.name = v;
        }
        Iterator<String> it = j.keys();
        while (it.hasNext()) {
            Object v = j.opt(it.next());
            if (v instanceof JSONObject || v instanceof JSONArray || v instanceof String) walk(v, d, depth + 1);
        }
    }

    // ---------------- HTTP ----------------

    private static final class Resp {
        int code;
        String body = "";
        final List<String> setCookies = new ArrayList<>();
        Map<String, List<String>> headers = new HashMap<>();

        String header(String name) {
            for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                if (e.getKey() != null && e.getKey().equalsIgnoreCase(name) && !e.getValue().isEmpty()) {
                    return e.getValue().get(0);
                }
            }
            return null;
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept-Encoding", "gzip");
        return c;
    }

    private static Resp read(HttpURLConnection c) throws IOException {
        Resp r = new Resp();
        r.code = c.getResponseCode();
        r.headers = c.getHeaderFields();
        for (Map.Entry<String, List<String>> e : r.headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase("Set-Cookie")) r.setCookies.addAll(e.getValue());
        }
        InputStream is = r.code >= 400 ? c.getErrorStream() : c.getInputStream();
        if (is != null) {
            if ("gzip".equalsIgnoreCase(c.getContentEncoding())) is = new GZIPInputStream(is);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
            is.close();
            r.body = bo.toString("UTF-8");
        }
        c.disconnect();
        return r;
    }
}
