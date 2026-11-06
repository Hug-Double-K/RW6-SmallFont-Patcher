package io.github.hugdoublek.rw6memo;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/** 보낸 메모 기록과 설정 */
public final class Memo {
    public final String id, title, body;
    public final long time;

    Memo(String id, String title, String body, long time) {
        this.id = id; this.title = title; this.body = body; this.time = time;
    }

    /** 같은 제목이면 같은 id → 워치에서 덮어씀 */
    public static String idFor(String title) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-1").digest(title.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.substring(0, 13);
        } catch (Exception e) {
            return String.format("%013d", Math.abs(title.hashCode()));
        }
    }

    /** 워치로 보낼 JSON (i=id, t=제목, b=본문, ts=시각) */
    public String toJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("i", id); o.put("t", title); o.put("b", body); o.put("ts", time);
            return o.toString();
        } catch (Exception e) { return "{}"; }
    }

    /** 컬러노트 체크리스트 표시([V], [ ])를 보기 좋게 */
    static String tidy(String s) {
        if (s == null) return "";
        return s.replace("\r", "").replaceAll("(?m)^\\[V\\] ?", "■ ").replaceAll("(?m)^\\[ \\] ?", "□ ");
    }

    /** 컬러노트 백업의 메모 한 건 → Memo (잠긴 메모면 null) */
    public static Memo fromColorNote(JSONObject o) {
        if (o.optInt("encrypted", 0) != 0) return null;
        String title = o.optString("title", "").trim();
        String body = tidy(o.optString("note", "")).trim();
        if (title.isEmpty() && body.isEmpty()) return null;
        if (title.isEmpty()) {
            int nl = body.indexOf('\n');
            title = nl < 0 ? body : body.substring(0, nl);
        }
        if (title.length() > 40) title = title.substring(0, 40) + "…";
        String id = String.format("cn%011d", o.optLong("_id", Math.abs((long) title.hashCode())));
        long ts = o.optLong("modified_date", System.currentTimeMillis());
        return new Memo(id, title, body, ts);
    }

    /** 공유받은 글에서 제목과 본문을 나눈다 */
    public static Memo fromShare(String subject, String text) {
        String t = text == null ? "" : text.replace("\r", "").trim();
        String title = subject == null ? "" : subject.trim();
        String body = t;
        if (title.isEmpty()) {
            int nl = t.indexOf('\n');
            title = nl < 0 ? t : t.substring(0, nl);
            body = nl < 0 ? "" : t.substring(nl + 1).trim();
        } else if (t.startsWith(title)) {
            body = t.substring(title.length()).trim();
        }
        title = title.trim();
        if (title.length() > 40) title = title.substring(0, 40) + "…";
        if (title.isEmpty()) title = "제목 없음";
        return new Memo(idFor(title), title, tidy(body), System.currentTimeMillis());
    }

    // ---- 저장소 ----
    public static final class Store {
        private final SharedPreferences sp;

        public Store(Context c) { sp = c.getApplicationContext().getSharedPreferences("rw6memo", Context.MODE_PRIVATE); }

        public int fontSize() { return sp.getInt("fontSize", 24); }
        public void setFontSize(int v) { sp.edit().putInt("fontSize", v).apply(); }

        public String password() { return sp.getString("password", "0000"); }
        public void setPassword(String v) { sp.edit().putString("password", v).apply(); }

        public String lastLog() { return sp.getString("lastLog", "-"); }
        public void setLastLog(String v) { sp.edit().putString("lastLog", v).apply(); }

        public List<Memo> all() {
            List<Memo> out = new ArrayList<>();
            try {
                JSONArray a = new JSONArray(sp.getString("memos", "[]"));
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.getJSONObject(i);
                    out.add(new Memo(o.getString("id"), o.getString("title"), o.optString("body"), o.optLong("time")));
                }
            } catch (Exception ignored) { }
            return out;
        }

        public void put(Memo m) {
            List<Memo> list = all();
            list.removeIf(x -> x.id.equals(m.id));
            list.add(0, m);
            save(list);
        }

        public void remove(String id) {
            List<Memo> list = all();
            list.removeIf(x -> x.id.equals(id));
            save(list);
        }

        private void save(List<Memo> list) {
            JSONArray a = new JSONArray();
            try {
                for (Memo m : list) {
                    JSONObject o = new JSONObject();
                    o.put("id", m.id); o.put("title", m.title); o.put("body", m.body); o.put("time", m.time);
                    a.put(o);
                }
            } catch (Exception ignored) { }
            sp.edit().putString("memos", a.toString()).apply();
        }
    }
}
