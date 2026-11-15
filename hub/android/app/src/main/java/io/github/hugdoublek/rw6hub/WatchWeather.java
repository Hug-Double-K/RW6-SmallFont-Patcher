package io.github.hugdoublek.rw6hub;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * 워치 날씨 (type 10). Mi Fitness 대신 Open-Meteo(무료, 키 없음)에서 받아 보냄.
 * 연결될 때 · 1시간마다 · 워치가 요청(10/3)할 때 보냄. Gemini 답에도 이 날씨를 씀.
 */
public final class WatchWeather {

    private static WatchWeather inst;
    public static synchronized WatchWeather get(Context c) {
        if (inst == null) inst = new WatchWeather(c.getApplicationContext());
        return inst;
    }

    private static final long EVERY_MS = 60 * 60_000L;
    private final Context app;
    private final SharedPreferences prefs;
    private final Handler h;
    private volatile String summary = "";   // Gemini에 줄 한 줄 요약
    private long lastFetch = 0;
    private JSONObject lastWx, lastAir;

    private WatchWeather(Context app) {
        this.app = app;
        this.prefs = app.getSharedPreferences("hub", Context.MODE_PRIVATE);
        try {
            String w = prefs.getString("wxCache", ""), a = prefs.getString("airCache", "");
            if (!w.isEmpty()) lastWx = new JSONObject(w);
            if (!a.isEmpty()) lastAir = new JSONObject(a);
        } catch (Exception ignored) {}
        HandlerThread t = new HandlerThread("weather");
        t.start();
        h = new Handler(t.getLooper());
    }

    private WatchLink link() { return WatchLink.get(app); }
    private void log(String s) { link().log("[날씨] " + s); }

    public String summary() { return summary; }

    /** 연결 직후 호출 */
    public void onConnected() {
        h.removeCallbacks(tick);
        h.postDelayed(tick, 3000);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            update(true);
            h.removeCallbacks(this);
            h.postDelayed(this, EVERY_MS);
        }
    };

    public void sendNow() { log("날씨 받는 중…"); lastSent = 0; h.post(() -> update(true)); }

    /** 워치가 보낸 type 10 메시지 */
    public void onPacket(int id, List<Pb.Field> p) {
        if (id == 3) { log("워치가 날씨를 요청함"); h.post(() -> update(false)); }
        // id 5 = 워치가 가진 도시 키 목록(응답) — 따로 할 일 없음
    }

    /** Gemini가 쓸 수 있게 날씨만 받아 둠 (워치로는 안 보냄) */
    public void refreshForAi() {
        if (System.currentTimeMillis() - lastFetch > 20 * 60_000L) {
            try { fetch(); } catch (Exception e) { /* 무시 */ }
        }
    }

    private void update(boolean force) {
        boolean fresh = !force && lastWx != null && System.currentTimeMillis() - lastFetch < 15 * 60_000L;
        if (!fresh) {
            Exception err = null;
            for (int i = 0; i < 3; i++) {
                try { fetch(); err = null; break; }
                catch (Exception e) { err = e; try { Thread.sleep(3000); } catch (InterruptedException ie) { return; } }
            }
            if (err != null) {
                log("✘ 날씨 받기 실패: " + err.getMessage() + (lastWx != null ? " → 지난 날씨를 보냄" : " → 2분 뒤 다시"));
                if (lastWx == null) { h.postDelayed(() -> update(true), 120_000); return; }
            }
        }
        try {
            if (link().isReady()) sendAll();
        } catch (Exception e) {
            log("✘ 보내기 오류: " + e.getMessage());
        }
    }

    private synchronized void fetch() throws Exception {
        String lat = prefs.getString("lat", "37.5428"), lon = prefs.getString("lon", "127.0868");
        String tz = prefs.getString("tz", "Asia/Seoul");
        lastWx = getJson("https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                + "&current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m,wind_direction_10m,surface_pressure,uv_index"
                + "&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset"
                + "&timezone=" + java.net.URLEncoder.encode(tz, "UTF-8") + "&forecast_days=7");
        try {
            lastAir = getJson("https://air-quality-api.open-meteo.com/v1/air-quality?latitude=" + lat + "&longitude=" + lon
                    + "&current=us_aqi&hourly=us_aqi&forecast_days=5&timezone=" + java.net.URLEncoder.encode(tz, "UTF-8"));
        } catch (Exception e) { lastAir = null; }
        lastFetch = System.currentTimeMillis();
        prefs.edit().putString("wxCache", lastWx.toString()).putString("airCache", lastAir == null ? "" : lastAir.toString()).apply();
        JSONObject cur = lastWx.getJSONObject("current");
        JSONObject d = lastWx.getJSONObject("daily");
        summary = String.format(Locale.KOREA, "현재 %s, %.0f℃, 습도 %d%%, 바람 %.0fkm/h; 오늘 최저 %.0f℃ 최고 %.0f℃(%s); 내일 최저 %.0f℃ 최고 %.0f℃(%s)%s",
                wmoKo(cur.optInt("weather_code")), cur.optDouble("temperature_2m"), cur.optInt("relative_humidity_2m"),
                cur.optDouble("wind_speed_10m"),
                d.getJSONArray("temperature_2m_min").optDouble(0), d.getJSONArray("temperature_2m_max").optDouble(0),
                wmoKo(d.getJSONArray("weather_code").optInt(0)),
                d.getJSONArray("temperature_2m_min").optDouble(1), d.getJSONArray("temperature_2m_max").optDouble(1),
                wmoKo(d.getJSONArray("weather_code").optInt(1)),
                aqiNow() >= 0 ? ", 대기질 AQI " + aqiNow() + "(" + aqiKo(aqiNow()) + ")" : "");
    }

    private int aqiNow() {
        if (lastAir == null) return -1;
        JSONObject c = lastAir.optJSONObject("current");
        return c == null ? -1 : c.optInt("us_aqi", -1);
    }

    private int aqiDay(int day) {
        if (lastAir == null) return day == 0 ? Math.max(0, aqiNow()) : 0;
        JSONArray a = lastAir.optJSONObject("hourly") == null ? null : lastAir.optJSONObject("hourly").optJSONArray("us_aqi");
        if (a == null) return 0;
        int max = -1;
        for (int i = day * 24; i < Math.min(a.length(), day * 24 + 24); i++) if (!a.isNull(i)) max = Math.max(max, a.optInt(i));
        return Math.max(max, 0);
    }

    private long lastSent = 0;

    private void sendAll() throws Exception {
        if (lastWx == null) return;
        // 워치가 몇 초 간격으로 여러 번 요청해도 1분 안에는 한 번만 보냄
        if (System.currentTimeMillis() - lastSent < 60_000L) return;
        lastSent = System.currentTimeMillis();
        JSONObject cur = lastWx.getJSONObject("current");
        JSONObject d = lastWx.getJSONObject("daily");
        Pb.W id = weatherId();
        // Mi Fitness처럼 먼저 도시 키 목록을 우리 위치로 갱신(10/6)
        link().send(new Pb.W().varint(1, 10).varint(2, 6).msg(12, new Pb.W().msg(4, new Pb.W().msg(1,
                new Pb.W().str(1, locationKey()).str(2, place())))), null);

        int aqi = Math.max(aqiNow(), 0);
        Pb.W latest = new Pb.W()
                .msg(1, id)
                .varint(2, cma(cur.optInt("weather_code")))
                .msg(3, kv("℃", (int) Math.round(cur.optDouble("temperature_2m"))))
                .msg(4, kv("%", cur.optInt("relative_humidity_2m")))
                .msg(5, kv(String.valueOf(cur.optInt("wind_direction_10m")), beaufort(cur.optDouble("wind_speed_10m"))))
                .msg(6, kv("", (int) Math.round(cur.optDouble("uv_index", 0))))
                .msg(7, kv(aqiEn(aqi), aqi))
                .msg(8, new Pb.W())
                .fixed32(9, (float) (cur.optDouble("surface_pressure", 1013) * 100));
        link().send(new Pb.W().varint(1, 10).varint(2, 0).msg(12, new Pb.W().msg(1, latest)), ok -> { });

        Pb.W list = new Pb.W();
        JSONArray codes = d.getJSONArray("weather_code"), mx = d.getJSONArray("temperature_2m_max"),
                mn = d.getJSONArray("temperature_2m_min"), sr = d.getJSONArray("sunrise"), ss = d.getJSONArray("sunset");
        String off = offset();
        for (int i = 0; i < Math.min(7, codes.length()); i++) {
            int c = cma(codes.optInt(i));
            int a = aqiDay(i);
            list.msg(1, new Pb.W()
                    .msg(1, kv(aqiEn(a), a))
                    .msg(2, range(c, c))
                    .msg(3, range((int) Math.round(mx.optDouble(i)), (int) Math.round(mn.optDouble(i))))   // from=최고, to=최저 (Mi Fitness 기록)
                    .str(4, "℃")
                    .msg(5, new Pb.W().str(1, sr.optString(i) + ":00" + off).str(2, ss.optString(i) + ":00" + off)));
        }
        Pb.W forecast = new Pb.W().msg(1, id).msg(2, list);
        link().send(new Pb.W().varint(1, 10).varint(2, 1).msg(12, new Pb.W().msg(2, forecast)), ok ->
                log(ok ? String.format(Locale.KOREA, "워치에 보냄: %s %.0f℃", wmoKo(cur.optInt("weather_code")), cur.optDouble("temperature_2m")) : "보내기 실패"));
    }

    private void sendCityKeys() {
        Pb.W key = new Pb.W().str(1, locationKey()).str(2, place());
        link().send(new Pb.W().varint(1, 10).varint(2, 5).msg(12, new Pb.W().msg(4, new Pb.W().msg(1, key))), ok -> { });
    }

    private Pb.W weatherId() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone(prefs.getString("tz", "Asia/Seoul")));
        return new Pb.W().str(1, f.format(new Date()) + offset()).str(2, "").str(3, place())
                .str(4, locationKey()).bool(5, true);
    }

    private String place() {
        String p = prefs.getString("place", "서울 광진구 구의1동");
        String[] w = p.trim().split("\\s+");
        return w[w.length - 1];
    }

    private String locationKey() {
        return "accu:" + Math.abs(prefs.getString("place", "seoul").hashCode() % 1000000);
    }

    private String offset() {
        int ms = TimeZone.getTimeZone(prefs.getString("tz", "Asia/Seoul")).getOffset(System.currentTimeMillis());
        int m = Math.abs(ms / 60000);
        return String.format(Locale.US, "%s%02d:%02d", ms < 0 ? "-" : "+", m / 60, m % 60);
    }

    // HCI 기록: 이 펌웨어는 값을 sint32(지그재그)로 읽음 (Mi Fitness가 15℃를 30으로 보냄)
    private static Pb.W kv(String key, int value) { return new Pb.W().str(1, key).sint(2, value); }
    private static Pb.W range(int from, int to) { return new Pb.W().sint(1, from).sint(2, to); }

    private static int beaufort(double kmh) {
        double[] lim = {1, 6, 12, 20, 29, 39, 50, 62, 75, 89, 103, 118};
        for (int i = 0; i < lim.length; i++) if (kmh < lim[i]) return i;
        return 12;
    }

    /** WMO 날씨 코드 → 샤오미(중국 기상국) 날씨 코드 */
    private static int cma(int w) {
        switch (w) {
            case 0: case 1: return 0;          // 맑음
            case 2: return 1;                  // 구름 조금/많음
            case 3: return 2;                  // 흐림
            case 45: case 48: return 18;       // 안개
            case 51: case 53: case 55: case 61: return 7;  // 약한 비
            case 63: return 8;                 // 비
            case 65: return 9;                 // 강한 비
            case 56: case 57: case 66: case 67: return 19; // 어는 비
            case 71: case 77: return 14;       // 약한 눈
            case 73: return 15;
            case 75: return 16;
            case 80: case 81: return 3;        // 소나기
            case 82: return 10;
            case 85: case 86: return 13;       // 소낙눈
            case 95: return 4;                 // 뇌우
            case 96: case 99: return 5;
            default: return 1;
        }
    }

    static String wmoKo(int w) {
        switch (w) {
            case 0: return "맑음"; case 1: return "대체로 맑음"; case 2: return "구름 조금"; case 3: return "흐림";
            case 45: case 48: return "안개";
            case 51: case 53: case 55: return "이슬비";
            case 61: return "약한 비"; case 63: return "비"; case 65: return "강한 비";
            case 56: case 57: case 66: case 67: return "어는 비";
            case 71: return "약한 눈"; case 73: return "눈"; case 75: return "많은 눈"; case 77: return "싸락눈";
            case 80: case 81: return "소나기"; case 82: return "강한 소나기";
            case 85: case 86: return "소낙눈";
            case 95: return "뇌우"; case 96: case 99: return "우박 동반 뇌우";
            default: return "알 수 없음";
        }
    }

    private static String aqiEn(int a) {
        if (a <= 50) return "Good";
        if (a <= 100) return "Moderate";
        if (a <= 150) return "Unhealthy for sensitive groups";
        if (a <= 200) return "Unhealthy";
        return "Very unhealthy";
    }

    private static String aqiKo(int a) {
        if (a <= 50) return "좋음";
        if (a <= 100) return "보통";
        if (a <= 150) return "민감군 나쁨";
        if (a <= 200) return "나쁨";
        return "매우 나쁨";
    }

    private static JSONObject getJson(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "MiManager/1.0");
        int code = c.getResponseCode();
        InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        if (code >= 400) throw new Exception("날씨 서버 " + code);
        return new JSONObject(b.toString("UTF-8"));
    }
}
