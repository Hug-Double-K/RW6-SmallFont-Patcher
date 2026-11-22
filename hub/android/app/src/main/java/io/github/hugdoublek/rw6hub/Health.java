package io.github.hugdoublek.rw6hub;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 건강 데이터 (Mi Fitness가 하던 일). 1단계:
 *  - 실시간 값: 8/45 시작 · 8/46 끝 · 8/47 워치가 주기적으로 보냄 (걸음·칼로리·심박·서 있기)
 *  - 기록 파일: 8/1(오늘)·8/2(지난 것)로 파일 번호(7바이트) 목록을 받고, 8/3으로 하나씩 요청하면
 *    채널 5로 [전체 조각 수 LE16 | 조각 번호 LE16 | 내용]이 옴. 모은 내용 = 파일 번호 7B + 0 + 본문 + CRC32.
 *  - 받은 파일은 그대로 저장만 하고, 워치에 '받았음'(8/5)은 보내지 않는다 → 워치에서 지워지지 않아
 *    해석이 틀려도 다시 받을 수 있고 Mi Fitness도 나중에 그대로 가져갈 수 있다.
 * 형식은 공개된 Gadgetbridge 문서·동작을 참고해 새로 작성 (코드 복사 아님).
 */
public final class Health {

    public interface Listener { void onHealthChanged(); }

    private static Health inst;
    public static synchronized Health get(Context c) {
        if (inst == null) inst = new Health(c.getApplicationContext());
        return inst;
    }

    private static final int TYPE = 8;
    private static final int ID_TODAY = 1, ID_PAST = 2, ID_REQUEST = 3, ID_LIVE_START = 45, ID_LIVE_STOP = 46, ID_LIVE = 47;
    private static final int F_HEALTH = 10;
    private static final int CH_ACTIVITY = 5;

    private final Context app;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    // 실시간
    public volatile int steps = -1, calories = -1, heartRate = -1, standing = -1;
    public volatile long liveAt = 0;
    private boolean liveWanted = false;

    // 기록 받기
    private final ArrayDeque<byte[]> queue = new ArrayDeque<>();
    private boolean syncing = false, pastAsked = false, pastGot = false;
    private byte[] current;
    private ByteArrayOutputStream buf = new ByteArrayOutputStream();
    private int total = 0, done = 0, saved = 0, skipped = 0, failed = 0;
    private volatile String status = "";

    private Health(Context c) {
        app = c;
        h.postDelayed(autoTick, 60_000);
    }

    // ------------------------------------------------------------------ 자동 받기

    /** 자동으로 받는 주기(분). 0 = 끔. 기본 60 */
    public int autoMinutes() { return app.getSharedPreferences("hub", Context.MODE_PRIVATE).getInt("healthAutoMin", 60); }
    public void setAutoMinutes(int m) { app.getSharedPreferences("hub", Context.MODE_PRIVATE).edit().putInt("healthAutoMin", m).apply(); changed(); }
    public long lastSyncAt() { return app.getSharedPreferences("hub", Context.MODE_PRIVATE).getLong("healthSyncAt", 0); }

    // 1분마다 확인: 연결돼 있고, 정한 주기가 지났으면 기록을 받는다
    private final Runnable autoTick = new Runnable() {
        @Override public void run() {
            try {
                int m = autoMinutes();
                if (m > 0 && !syncing && WatchLink.get(app).isReady()
                        && System.currentTimeMillis() - lastSyncAt() >= m * 60_000L) sync();
            } catch (RuntimeException ignored) {}
            h.postDelayed(this, 60_000);
        }
    };

    public void addListener(Listener l) { listeners.addIfAbsent(l); }
    public void removeListener(Listener l) { listeners.remove(l); }
    private void changed() { for (Listener l : listeners) { try { l.onHealthChanged(); } catch (RuntimeException ignored) {} } }
    private void log(String s) { WatchLink.get(app).log("건강: " + s); }
    public String status() { return status; }
    public boolean isSyncing() { return syncing; }

    // ------------------------------------------------------------------ 실시간

    public void startLive() {
        liveWanted = true;
        WatchLink.get(app).send(new Pb.W().varint(1, TYPE).varint(2, ID_LIVE_START));
    }

    public void stopLive() {
        if (!liveWanted) return;
        liveWanted = false;
        WatchLink.get(app).send(new Pb.W().varint(1, TYPE).varint(2, ID_LIVE_STOP));
    }

    // ------------------------------------------------------------------ 워치에서 온 것

    /** WatchLink가 type 8 메시지를 넘겨줌 (연결 스레드) */
    public void onPacket(int id, List<Pb.Field> p) {
        final List<Pb.Field> health = Pb.sub(p, F_HEALTH);
        if (id == ID_LIVE) {
            List<Pb.Field> s = health == null ? null : Pb.sub(health, 39);
            if (s == null) return;
            steps = num(s, 1, steps); calories = num(s, 2, calories); heartRate = num(s, 4, heartRate); standing = num(s, 6, standing);
            liveAt = System.currentTimeMillis();
            if (!liveWanted) WatchLink.get(app).send(new Pb.W().varint(1, TYPE).varint(2, ID_LIVE_STOP));
            h.post(this::changed);
            return;
        }
        if (id == ID_TODAY || id == ID_PAST) {
            Pb.Field f = health == null ? null : Pb.find(health, 2);
            final byte[] ids = f == null || f.bytes == null ? new byte[0] : f.bytes;
            final int which = id;
            h.post(() -> onList(which, ids));
        }
    }

    private static int num(List<Pb.Field> l, int n, int old) {
        Pb.Field f = Pb.find(l, n);
        return f == null ? old : (int) f.varint;
    }

    /** WatchLink가 채널 5 데이터를 넘겨줌 (연결 스레드, 이미 복호화됨) */
    public void onChunk(byte[] body) {
        final byte[] b = body.clone();
        h.post(() -> addChunk(b));
    }

    // ------------------------------------------------------------------ 기록 받기 (메인 스레드)

    public void sync() {
        if (syncing) return;
        if (!WatchLink.get(app).isReady()) { status = "워치가 연결돼 있지 않아요"; changed(); return; }
        syncing = true; pastAsked = false; pastGot = false; queue.clear(); current = null;
        total = done = saved = skipped = failed = 0;
        status = "기록 목록을 받는 중…"; changed();
        log("기록 동기화 시작");
        WatchLink.get(app).send(new Pb.W().varint(1, TYPE).varint(2, ID_TODAY)
                .msg(F_HEALTH, new Pb.W().msg(5, new Pb.W().varint(1, 0))));
        h.removeCallbacks(listTimeout);
        h.postDelayed(listTimeout, 10_000);
    }

    private final Runnable listTimeout = () -> {
        if (!syncing) return;
        if (!pastAsked) { askPast(); return; }
        pastGot = true;
        if (current != null) return;
        if (total == 0) finish("워치가 기록 목록을 보내지 않았어요"); else next();
    };

    private void askPast() {
        pastAsked = true;
        WatchLink.get(app).send(new Pb.W().varint(1, TYPE).varint(2, ID_PAST));
        h.removeCallbacks(listTimeout);
        h.postDelayed(listTimeout, 10_000);
    }

    private void onList(int which, byte[] ids) {
        if (!syncing) return;
        int n = 0;
        for (int i = 0; i + 7 <= ids.length; i += 7) {
            byte[] id = Arrays.copyOfRange(ids, i, i + 7);
            if (FileId.ts(id) == 0 && id[5] == 0) continue;
            boolean dup = false;
            for (byte[] q : queue) if (Arrays.equals(q, id)) { dup = true; break; }
            if (!dup) { queue.add(id); n++; }
        }
        total += n;
        log((which == ID_TODAY ? "오늘" : "지난") + " 기록 파일 " + n + "개");
        if (which == ID_PAST) { pastGot = true; h.removeCallbacks(listTimeout); }
        else if (!pastAsked) askPast();
        if (current == null) next();
    }

    private final Runnable fileTimeout = () -> {
        if (current == null) return;
        log("응답 없음: " + FileId.label(current));
        failed++; done++;
        next();
    };

    private void next() {
        h.removeCallbacks(fileTimeout);
        buf = new ByteArrayOutputStream();
        while (true) {
            current = queue.poll();
            if (current == null) {
                if (!pastGot) { status = "지난 기록 목록을 기다리는 중…"; changed(); return; }   // 목록이 오면 onList가 다시 부름
                finish(null);
                return;
            }
            if (isComplete(current)) { skipped++; done++; continue; }
            break;
        }
        status = "받는 중 " + (done + 1) + "/" + total + " · " + FileId.label(current);
        changed();
        WatchLink.get(app).send(new Pb.W().varint(1, TYPE).varint(2, ID_REQUEST)
                .msg(F_HEALTH, new Pb.W().bytes(2, current)));
        h.postDelayed(fileTimeout, 8_000);
    }

    private void addChunk(byte[] c) {
        if (c.length < 4) return;
        int tot = (c[0] & 0xff) | (c[1] & 0xff) << 8, num = (c[2] & 0xff) | (c[3] & 0xff) << 8;
        if (num == 1) buf = new ByteArrayOutputStream();
        buf.write(c, 4, c.length - 4);
        h.removeCallbacks(fileTimeout);
        if (num != tot) { if (current != null) h.postDelayed(fileTimeout, 8_000); return; }
        byte[] data = buf.toByteArray();
        buf = new ByteArrayOutputStream();
        if (data.length >= 13) {
            CRC32 crc = new CRC32();
            crc.update(data, 0, data.length - 4);
            long want = (data[data.length - 4] & 0xffL) | (data[data.length - 3] & 0xffL) << 8
                    | (data[data.length - 2] & 0xffL) << 16 | (data[data.length - 1] & 0xffL) << 24;
            if (crc.getValue() == want) {
                byte[] id = Arrays.copyOfRange(data, 0, 7);
                try {
                    File f = fileFor(id);
                    try (FileOutputStream o = new FileOutputStream(f)) { o.write(data); }
                    saved++;
                } catch (Exception e) { failed++; log("저장 실패: " + e); }
            } else { failed++; log("내용 검사(CRC) 불일치: " + (current == null ? "?" : FileId.label(current))); }
        } else failed++;
        if (current != null) { done++; next(); }
    }

    private void finish(String err) {
        h.removeCallbacks(fileTimeout); h.removeCallbacks(listTimeout);
        syncing = false; current = null;
        status = err != null ? err
                : "끝 · 새로 받음 " + saved + " · 이미 있음 " + skipped + (failed > 0 ? " · 실패 " + failed : "");
        log("기록 동기화 " + status);
        app.getSharedPreferences("hub", Context.MODE_PRIVATE).edit().putLong("healthSyncAt", System.currentTimeMillis()).apply();
        changed();
    }

    // ------------------------------------------------------------------ 파일

    public File rawDir() {
        File d = new File(app.getFilesDir(), "health/raw");
        d.mkdirs();
        return d;
    }

    private File fileFor(byte[] id) { return new File(rawDir(), FileId.name(id)); }

    /** 이미 받았고 더 늘어날 일이 없는 파일인가 (그날이 끝난 뒤에 받은 것, 또는 운동 기록) */
    private boolean isComplete(byte[] id) {
        File f = fileFor(id);
        if (!f.exists() || f.length() < 13) return false;
        if (FileId.type(id) == 1) return true;
        return f.lastModified() > (FileId.ts(id) + 26 * 3600L) * 1000L;
    }

    public List<File> rawFiles() {
        File[] fs = rawDir().listFiles();
        List<File> l = new ArrayList<>();
        if (fs != null) for (File f : fs) if (f.getName().endsWith(".bin")) l.add(f);
        Collections.sort(l);
        return l;
    }

    /** 받은 파일을 종류·버전별로 센 설명 */
    public String fileSummary() {
        TreeMap<String, Integer> m = new TreeMap<>();
        for (File f : rawFiles()) {
            byte[] id = FileId.fromName(f.getName());
            if (id == null) continue;
            String k = FileId.kind(id) + " v" + (id[5] & 0xff) + (Days.canParse(id) ? "" : " (아직 해석 못 함)");
            Integer n = m.get(k);
            m.put(k, n == null ? 1 : n + 1);
        }
        if (m.isEmpty()) return "받은 기록 파일이 없어요";
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, Integer> e : m.entrySet()) sb.append(e.getKey()).append(" × ").append(e.getValue()).append('\n');
        return sb.toString().trim();
    }

    /** 받은 원본 파일을 zip으로 다운로드 폴더에 저장 (분석용). 파일 이름을 돌려줌, 없으면 null */
    public String exportZip() {
        List<File> fs = rawFiles();
        if (fs.isEmpty()) return null;
        String name = "MiManager_health_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".zip";
        try {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, "application/zip");
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download");
            Uri uri = app.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) return null;
            try (OutputStream o = app.getContentResolver().openOutputStream(uri); ZipOutputStream z = new ZipOutputStream(o)) {
                byte[] b = new byte[16384];
                for (File f : fs) {
                    z.putNextEntry(new ZipEntry(f.getName()));
                    try (FileInputStream in = new FileInputStream(f)) { int n; while ((n = in.read(b)) > 0) z.write(b, 0, n); }
                    z.closeEntry();
                }
            }
            return name;
        } catch (Exception e) {
            log("내보내기 실패: " + e);
            return null;
        }
    }

    // ------------------------------------------------------------------ 파일 번호 (7바이트)

    /** [시각 LE32(초) | 시간대(15분 단위) | 버전 | 종류 1비트·세부 5비트·상세/요약/GPS 2비트] */
    public static final class FileId {
        private FileId() {}
        public static long ts(byte[] id) { return (id[0] & 0xffL) | (id[1] & 0xffL) << 8 | (id[2] & 0xffL) << 16 | (id[3] & 0xffL) << 24; }
        public static int type(byte[] id) { return (id[6] >> 7) & 1; }
        public static int subtype(byte[] id) { return (id[6] & 0x7f) >> 2; }
        public static int detail(byte[] id) { return id[6] & 3; }
        public static int version(byte[] id) { return id[5] & 0xff; }

        public static String name(byte[] id) {
            return String.format(Locale.ROOT, "%010d_%02X%02X%02X%02X%02X%02X%02X.bin", ts(id), id[0], id[1], id[2], id[3], id[4], id[5], id[6]);
        }

        public static byte[] fromName(String n) {
            int u = n.indexOf('_');
            if (u < 0 || n.length() < u + 15) return null;
            try {
                byte[] id = new byte[7];
                for (int i = 0; i < 7; i++) id[i] = (byte) Integer.parseInt(n.substring(u + 1 + i * 2, u + 3 + i * 2), 16);
                return id;
            } catch (NumberFormatException e) { return null; }
        }

        public static String kind(byte[] id) {
            int st = subtype(id), d = detail(id);
            String tail = d == 0 ? "상세" : d == 1 ? "요약" : d == 2 ? "경로" : "기타" + d;
            if (type(id) == 1) return "운동(" + st + ") " + tail;
            switch (st) {
                case 0: return "하루 활동 " + tail;
                case 3: return "수면 단계 " + tail;
                case 6: return "직접 측정 " + tail;
                case 8: return "수면 " + tail;
                default: return "활동(" + st + ") " + tail;
            }
        }

        public static String label(byte[] id) {
            return new SimpleDateFormat("M/d HH:mm", Locale.KOREA).format(new Date(ts(id) * 1000L)) + " " + kind(id) + " v" + version(id);
        }
    }

    // ------------------------------------------------------------------ 하루 요약 (받은 파일에서 계산)

    public static final class Day {
        public String date;          // yyyy-MM-dd
        public int steps, calories;
        public int hrMin = 999, hrMax = 0; long hrSum; int hrN;
        long spo2Sum; int spo2N; long stressSum; int stressN;
        public int hrAvg() { return hrN == 0 ? 0 : (int) (hrSum / hrN); }
        public int spo2Avg() { return spo2N == 0 ? 0 : (int) (spo2Sum / spo2N); }
        public int stressAvg() { return stressN == 0 ? 0 : (int) (stressSum / stressN); }
    }

    /** 하루 활동 상세 파일(분 단위)을 읽어 날짜별로 더함. 형식을 아는 버전(1~4)만 */
    public static final class Days {
        private Days() {}

        public static boolean canParse(byte[] id) {
            return FileId.type(id) == 0 && FileId.subtype(id) == 0 && FileId.detail(id) == 0 && headerSize(FileId.version(id)) > 0;
        }

        private static int headerSize(int version) {
            switch (version) { case 1: case 2: return 4; case 3: return 5; case 4: return 6; default: return 0; }
        }

        public static List<Day> load(Health hh) {
            TreeMap<String, Day> days = new TreeMap<>(Collections.reverseOrder());
            SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            for (File f : hh.rawFiles()) {
                byte[] id = FileId.fromName(f.getName());
                if (id == null || !canParse(id)) continue;
                try {
                    byte[] d = new byte[(int) f.length()];
                    try (FileInputStream in = new FileInputStream(f)) { int o = 0, n; while (o < d.length && (n = in.read(d, o, d.length - o)) > 0) o += n; }
                    parse(id, d, days, df);
                } catch (Exception ignored) {}
            }
            return new ArrayList<>(days.values());
        }

        // 분마다: 머리말의 4비트 묶음이 '이 항목이 있는가'를 알려 주고, 있으면 8/16비트 값을 읽는다
        private static void parse(byte[] id, byte[] d, TreeMap<String, Day> days, SimpleDateFormat df) {
            int version = FileId.version(id), hs = headerSize(version);
            int end = d.length - 4, pos = 8;
            if (pos + hs > end) return;
            byte[] header = Arrays.copyOfRange(d, pos, pos + hs);
            pos += hs;
            long t = FileId.ts(id) * 1000L;
            int[] p = {pos};
            while (p[0] < end) {
                int[] g = {-1};
                Day day = null;
                String key = df.format(new Date(t));
                day = days.get(key);
                if (day == null) { day = new Day(); day.date = key; days.put(key, day); }
                int extra = 0;
                int v = group(header, g, d, p, end, 16);
                if (v >= 0) {
                    if (has(header, g[0], 1)) extra = bits(v, 16, 1, 1);
                    if (has(header, g[0], 2)) day.steps += bits(v, 16, 2, 14);
                }
                v = group(header, g, d, p, end, 8);
                if (v >= 0 && has(header, g[0], 1)) day.calories += bits(v, 8, 2, 6);
                group(header, g, d, p, end, 8);
                group(header, g, d, p, end, 16);
                v = group(header, g, d, p, end, 8);
                if (v >= 0 && has(header, g[0], 0) && v > 0 && v < 255) {
                    day.hrSum += v; day.hrN++;
                    if (v < day.hrMin) day.hrMin = v;
                    if (v > day.hrMax) day.hrMax = v;
                }
                group(header, g, d, p, end, 8);
                group(header, g, d, p, end, 16);
                if (version >= 3) {
                    v = group(header, g, d, p, end, 8);
                    if (v > 0 && v <= 100 && has(header, g[0], 0)) { day.spo2Sum += v; day.spo2N++; }
                    v = group(header, g, d, p, end, 8);
                    if (v > 0 && v < 255 && has(header, g[0], 0)) { day.stressSum += v; day.stressN++; }
                }
                if (extra == 1) group(header, g, d, p, end, 8);
                if (version >= 4) { group(header, g, d, p, end, 16); group(header, g, d, p, end, 16); }
                t += 60_000L;
            }
        }

        private static int nibble(byte[] header, int g) {
            int b = header[g / 2] & 0xff;
            return g % 2 == 0 ? b >> 4 : b & 0x0f;
        }

        /** 다음 묶음으로 넘어가 값이 있으면 읽어 돌려줌. 없으면 -1 */
        private static int group(byte[] header, int[] g, byte[] d, int[] p, int end, int nBits) {
            g[0]++;
            int n = nBits / 8;
            if (g[0] >= header.length * 2) { p[0] += n; return -1; }
            if ((nibble(header, g[0]) & 8) == 0) return -1;
            if (p[0] + n > end) { p[0] = end; return -1; }
            int v = n == 1 ? d[p[0]] & 0xff : (d[p[0]] & 0xff) | (d[p[0] + 1] & 0xff) << 8;
            p[0] += n;
            return v;
        }

        private static boolean has(byte[] header, int g, int idx) {
            return g < header.length * 2 && (nibble(header, g) & (1 << (2 - idx))) != 0;
        }

        private static int bits(int v, int size, int idx, int n) {
            int shift = size - idx - n;
            return (v >> shift) & ((1 << n) - 1);
        }
    }
}
