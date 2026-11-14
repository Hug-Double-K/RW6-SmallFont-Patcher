package io.github.hugdoublek.rw6hub;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 크라운(샤오아이) 가로채기.
 * 워치는 크라운을 누르면 음성 채널(3)로 [종류 2B][길이 2B][내용]을 보낸다.
 *   종류 1 = 시작(내용 2바이트), 종류 2 = 음성 400바이트 = Opus(16kHz, 20ms) 80바이트 × 5
 * 말이 끝나면(무음) 녹음을 멈추게 하고, 음성을 Gemini에 보내 답을 샤오아이 화면(AIVS type 14)으로 돌려준다.
 */
public final class XiaoAi {

    public static final String DEFAULT_MODEL = "gemini-3.5-flash-lite";
    // AIVS 결과 종류
    private static final int START_LISTENING = 0, END_LISTENING = 1, RECOGNIZE_RESULT = 2, STOP_CAPTURE = 3,
            DIALOG_FINISH = 4, ERROR_NETWORK = 80, ERROR_AUTH = 81, ERROR_AUDIO_EMPTY = 85, ERROR_UNKNOWN = 91,
            TEMPLATE_TOAST = 256, TEMPLATE_GENERAL = 257, ALERTS_SETALERT = 263, SPEECHSYNTHESIZER_SPEAK = 264, LLM_LOADING_CARD = 289, LLM_TOAST_STREAM = 292, LLM_FINISH_STREAM = 293;

    private static XiaoAi INSTANCE;

    public static synchronized XiaoAi get(Context c) {
        if (INSTANCE == null) INSTANCE = new XiaoAi(c.getApplicationContext());
        return INSTANCE;
    }

    private final Context app;
    private final SharedPreferences prefs;
    private final Handler h;

    // 한 번의 질문
    private boolean active = false, stopped = false, speechSeen = false;
    private int session = 1;
    private final List<byte[]> frames = new ArrayList<>();
    private long startAt, lastFrameAt, lastLoudAt;
    private double noiseDb = -60, peakDb = -90;
    private int dbCount = 0;
    private MediaCodec dec;
    private int gen = 0;
    private final int sendSession = 0;  // 워치는 세션 0만 받아들임 (0.34 시험으로 확인)
    private final WatchVoice voice;

    private XiaoAi(Context app) {
        this.app = app;
        this.prefs = app.getSharedPreferences("hub", Context.MODE_PRIVATE);
        HandlerThread t = new HandlerThread("xiaoai");
        t.start();
        h = new Handler(t.getLooper());
        voice = new WatchVoice(app);
    }

    public boolean isActive() { return active; }

    /** 질문 중 워치가 보낸 PB 메시지 기록 (응답 형식 찾기용) */
    public void onPacket(int type, int id, List<Pb.Field> p) {
        if (!active && System.currentTimeMillis() - lastEndAt > 15_000) return;
        if (type == 2 && id == 1) return;
        Pb.Field err = Pb.find(p, 100);
        log("워치→ type=" + type + " id=" + id + (err != null ? " 오류=" + err.varint : ""));
    }

    private long lastEndAt = 0;

    public boolean enabled() { return prefs.getBoolean("aiOn", true); }

    private WatchLink link() { return WatchLink.get(app); }

    private void log(String s) { link().log("[AI] " + s); }

    // ======================= 음성 받기 =======================

    /** 음성 채널(3) 데이터 (복호화된 것) */
    public void onVoice(byte[] d) {
        if (!enabled()) return;
        final byte[] data = d.clone();
        h.post(() -> handle(data));
    }

    private void handle(byte[] d) {
        int i = 0;
        while (i + 4 <= d.length) {
            int type = ((d[i] & 0xFF) << 8) | (d[i + 1] & 0xFF);
            int len = ((d[i + 2] & 0xFF) << 8) | (d[i + 3] & 0xFF);
            i += 4;
            if (i + len > d.length) break;
            if (type == 1) begin(len >= 2 ? ((d[i] & 0xFF) << 8) | (d[i + 1] & 0xFF) : 0);
            else if (type == 2) audio(d, i, len);
            else log("음성 채널 종류 " + type + " (" + len + "B)");
            i += len;
        }
    }

    private void begin(int value) {
        closeDecoder();
        gen++;
        active = true;
        stopped = false;
        speechSeen = false;
        frames.clear();
        startAt = lastFrameAt = lastLoudAt = System.currentTimeMillis();
        noiseDb = -60; peakDb = -90; dbCount = 0;
        session = value > 0 ? value : session + 1;
        log("크라운 질문 시작 (세션 " + session + ")");
        openDecoder();
        final int myGen = gen;
        // 시험: 듣는 동안 화면에 글자가 뜨는지 세션 번호별로 확인
        h.postDelayed(() -> { if (myGen == gen && !stopped) send(partial("듣는 중…"), "듣는 중 글자"); }, 800);
        h.postDelayed(new Runnable() {   // 끝 감시: 무음·끊김·최대 길이
            @Override public void run() {
                if (myGen != gen || !active || stopped) return;
                long now = System.currentTimeMillis();
                boolean silentAfterSpeech = speechSeen && now - lastLoudAt > 1000;
                boolean noFrames = now - lastFrameAt > 900;
                boolean tooLong = now - startAt > 10_000;
                boolean nothingSaid = !speechSeen && now - startAt > 6_000;
                if (silentAfterSpeech || noFrames || tooLong || nothingSaid) finishListening();
                else h.postDelayed(this, 100);
            }
        }, 300);
    }

    private void audio(byte[] d, int off, int len) {
        if (!active) {
            // 질문이 끝난 뒤에도 워치가 남은 음성을 몇 초 더 보냄 → 새 질문으로 착각하지 않게 무시
            if (System.currentTimeMillis() - lastEndAt < 10_000) return;
            begin(0); // 시작 신호를 놓쳤어도 받음
        }
        if (stopped) return;
        lastFrameAt = System.currentTimeMillis();
        for (int j = off; j + 80 <= off + len; j += 80) {
            byte[] f = new byte[80];
            System.arraycopy(d, j, f, 0, 80);
            frames.add(f);
            measure(f);
        }
    }

    // ---------------- 말 끝 찾기 (Opus를 풀어 소리 크기 측정) ----------------

    private void openDecoder() {
        try {
            MediaFormat fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, 16000, 1);
            fmt.setByteBuffer("csd-0", ByteBuffer.wrap(opusHead()));
            fmt.setByteBuffer("csd-1", ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(0, 6_500_000L));
            fmt.setByteBuffer("csd-2", ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(0, 80_000_000L));
            dec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS);
            dec.configure(fmt, null, null, 0);
            dec.start();
        } catch (Exception e) {
            dec = null;
            log("소리 크기 측정 불가 → 시간 기준으로 끝냄 (" + e.getMessage() + ")");
        }
    }

    private void closeDecoder() {
        if (dec != null) {
            try { dec.stop(); } catch (Exception ignored) {}
            try { dec.release(); } catch (Exception ignored) {}
            dec = null;
        }
    }

    private void measure(byte[] f) {
        if (dec == null) { speechSeen = true; lastLoudAt = lastFrameAt; return; }
        try {
            int in = dec.dequeueInputBuffer(5000);
            if (in >= 0) {
                ByteBuffer b = dec.getInputBuffer(in);
                b.clear();
                b.put(f);
                dec.queueInputBuffer(in, 0, f.length, 0, 0);
            }
            MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
            int out;
            while ((out = dec.dequeueOutputBuffer(bi, 2000)) >= 0) {
                ByteBuffer ob = dec.getOutputBuffer(out);
                if (ob != null && bi.size > 1) {
                    ob.position(bi.offset);
                    ob.order(ByteOrder.LITTLE_ENDIAN);
                    long sum = 0;
                    int n = bi.size / 2;
                    for (int k = 0; k < n; k++) { int s = ob.getShort(); sum += (long) s * s; }
                    double rms = Math.sqrt(sum / (double) Math.max(1, n));
                    double db = 20 * Math.log10(Math.max(1, rms) / 32768.0);
                    onLevel(db);
                }
                dec.releaseOutputBuffer(out, false);
            }
        } catch (Exception e) {
            closeDecoder();
            speechSeen = true;
            lastLoudAt = lastFrameAt;
        }
    }

    private void onLevel(double db) {
        if (db < -85) return;              // 디코더 준비 중의 완전 무음은 무시
        dbCount++;
        if (dbCount == 1) noiseDb = db;
        else if (db < noiseDb) noiseDb = db;
        else noiseDb += (db - noiseDb) * 0.01;   // 바닥 잡음을 천천히 따라감
        if (dbCount <= 5) return;
        peakDb = Math.max(peakDb, db);
        boolean loud = db > Math.max(noiseDb + 12, -50);
        if (loud) { speechSeen = true; lastLoudAt = System.currentTimeMillis(); }
    }

    // ======================= 답하기 =======================

    private void finishListening() {
        if (stopped) return;
        stopped = true;
        closeDecoder();
        lastEndAt = System.currentTimeMillis();
        // 시험: 녹음 멈춤(STOP_CAPTURE)이 대화 화면을 닫는지 몰라 답을 다 보낸 뒤에 보냄
        int n = frames.size();
        log(String.format(Locale.US, "녹음 끝: %.1f초 (최대 %.0fdB, 잡음 %.0fdB)", n * 0.02, peakDb, noiseDb));
        if (n < 10) {
            showText("말소리가 들리지 않았어요", true);
            active = false;
            return;
        }
        String key = prefs.getString("aiKey", "").trim();
        if (key.isEmpty()) {
            showText("Mi Manager 앱에서 Gemini API 키를 먼저 넣어 주세요", true);
            active = false;
            return;
        }
        send(partial("생각 중…"), "생각 중 글자");
        byte[] ogg = Ogg.opus(new ArrayList<>(frames), 16000);
        final int myGen = gen;
        final String[] cmd = {null};
        new Thread(() -> {
            String q = "", a;
            try {
                long t0 = System.currentTimeMillis();
                String reply = gemini(key, prefs.getString("aiModel", DEFAULT_MODEL), ogg);
                // 명령 줄(CMD:)은 따로 떼어 냄
                StringBuilder rest = new StringBuilder();
                for (String line : reply.split("\n")) {
                    String t = line.trim();
                    if (t.startsWith("CMD:")) cmd[0] = t.substring(4).trim();
                    else rest.append(line).append('\n');
                }
                reply = rest.toString().trim();
                int ai = reply.indexOf("A:");
                if (reply.startsWith("Q:") && ai > 0) { q = reply.substring(2, ai).trim(); a = reply.substring(ai + 2).trim(); }
                else a = reply;
                log(String.format(Locale.US, "Gemini %.1f초 · 들은 말: %s", (System.currentTimeMillis() - t0) / 1000f, q));
                log("답: " + a);
            } catch (Exception e) {
                log("✘ " + e.getMessage());
                final String msg = "AI 오류: " + e.getMessage();
                h.post(() -> { if (myGen == gen) { showText(msg, true); active = false; } });
                return;
            }
            final String fq = q, fa = a;
            h.post(() -> {
                if (myGen != gen) return;
                if (!fq.isEmpty()) send(partial(fq), "들은 말");   // 말풍선에는 사용자가 한 말
                if (cmd[0] != null) runCommand(cmd[0]);
                showText(fa, false);
                voice.speak(fa);
                active = false;
                lastEndAt = System.currentTimeMillis();
            });
        }, "xiaoai-gemini").start();
    }

    /** 답(또는 오류)을 화면에 띄우고 대화를 끝낸다 */
    private void showText(String text, boolean isError) {
        // 0.37 시험 결과: 토스트(TEMPLATE_TOAST)가 화면에 바로 뜸. 녹음 멈춤은 답을 띄운 뒤에.
        final int myGen = gen;
        send(instr(TEMPLATE_TOAST).msg(3, new Pb.W().str(1, text)), "답 표시");
        h.postDelayed(() -> { if (myGen == gen) send(instr(STOP_CAPTURE), "녹음 멈춤"); }, 3000);
        h.postDelayed(() -> { if (myGen == gen) send(instr(DIALOG_FINISH), "대화 끝"); }, 15000);
    }

    /** Gemini가 낸 명령을 워치에서 실행 (샤오아이 알림 지시 ALERTS_SETALERT) */
    private void runCommand(String c) {
        log("명령: " + c);
        try {
            String[] w = c.split("\\s+");
            SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            iso.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Seoul"));
            if (w[0].equalsIgnoreCase("TIMER") && w.length >= 2) {
                long sec = Long.parseLong(w[1]);
                String dt = iso.format(new Date(System.currentTimeMillis() + sec * 1000));
                String off = "PT" + (sec / 3600 > 0 ? sec / 3600 + "H" : "") + (sec % 3600 / 60 > 0 ? sec % 3600 / 60 + "M" : "") + (sec % 60 > 0 ? sec % 60 + "S" : "");
                send(instr(ALERTS_SETALERT).msg(10, alert(2, dt, off, "타이머")), "타이머 " + sec + "초");
            } else if (w[0].equalsIgnoreCase("ALARM") && w.length >= 2) {
                send(instr(ALERTS_SETALERT).msg(10, alert(0, w[1] + (w[1].length() == 16 ? ":00" : ""), "", w.length > 2 ? c.substring(c.indexOf(w[2])) : "알람")), "알람 " + w[1]);
            } else log("모르는 명령");
        } catch (Exception e) { log("명령 오류: " + e.getMessage()); }
    }

    private static Pb.W alert(int type, String datetime, String offset, String event) {
        // AlertsSetAlert: id·type·datetime·circle(ONCE)·circle_extra·offset·event (모두 필수 문자열은 빈 값이라도 넣음)
        return new Pb.W().str(1, String.valueOf(System.currentTimeMillis() / 1000)).varint(2, type).str(3, datetime)
                .varint(4, 0).str(5, "").str(6, offset).str(7, event);
    }

    private Pb.W partial(String text) {
        return instr(RECOGNIZE_RESULT).msg(2, new Pb.W().bool(1, false).msg(2, new Pb.W().msg(1, new Pb.W().str(1, text))));
    }

    private Pb.W instr(int resultType) {
        return new Pb.W().varint(1, resultType).varint(13, sendSession);
    }

    private void send(Pb.W instruction, String label) {
        Pb.W packet = new Pb.W().varint(1, 14).varint(2, 0)
                .msg(16, new Pb.W().msg(1, new Pb.W().msg(1, instruction)));
        if (!link().send(packet, ok -> log("→ " + label + (ok ? " ✓" : " ✘")))) log("워치에 보내지 못함 (연결 없음)");
    }

    private static byte[] opusHead() {
        ByteBuffer b = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN);
        b.put("OpusHead".getBytes(StandardCharsets.US_ASCII));
        b.put((byte) 1).put((byte) 1).putShort((short) 312).putInt(16000).putShort((short) 0).put((byte) 0);
        return b.array();
    }

    // ======================= Gemini =======================

    private String gemini(String key, String model, byte[] ogg) throws Exception {
        if (model == null || model.trim().isEmpty()) model = DEFAULT_MODEL;
        String now = new SimpleDateFormat("yyyy-MM-dd (E) HH:mm", Locale.KOREA).format(new Date());
        String sys = "너는 손목시계 속 한국어 음성 비서야. 사용자의 음성을 듣고 한국어로 답해. "
                + "답은 작은 시계 화면에서 읽기 좋게 2~4문장으로 짧고 분명하게, 이모지 없이 써. "
                + "출력 형식은 반드시 다음과 같아. 첫 줄: 'Q: ' 뒤에 사용자가 한 말을 그대로 받아 적은 것. 둘째 줄부터: 'A: ' 뒤에 답. "
                + "소리가 없거나 알아들을 수 없으면 Q는 '(안 들림)'으로 하고 A에서 다시 말해 달라고 해. "
                + "사용자가 타이머를 맞춰 달라고 하면 마지막 줄에 'CMD: TIMER <전체 초>'를 써(예: 1분 45초 → CMD: TIMER 105). "
                + "알람을 맞춰 달라고 하면 마지막 줄에 'CMD: ALARM <yyyy-MM-ddTHH:mm> <이름>'을 써(지금 이후 가장 가까운 그 시각). 그때 A는 맞췄다고 짧게 말해. 그 밖에는 CMD 줄을 쓰지 마. "
                + "사용자는 서울 광진구 구의1동에 있어. 지금은 한국 시간 " + now + " 이야.";
        JSONObject body = new JSONObject();
        body.put("system_instruction", new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", sys))));
        JSONArray parts = new JSONArray();
        parts.put(new JSONObject().put("inline_data", new JSONObject()
                .put("mime_type", "audio/ogg").put("data", Base64.encodeToString(ogg, Base64.NO_WRAP))));
        body.put("contents", new JSONArray().put(new JSONObject().put("role", "user").put("parts", parts)));
        body.put("generationConfig", new JSONObject().put("maxOutputTokens", 400));
        HttpURLConnection c = (HttpURLConnection) new URL("https://generativelanguage.googleapis.com/v1beta/models/"
                + URLEncoder.encode(model.trim(), "UTF-8") + ":generateContent").openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(30000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("x-goog-api-key", key);
        try (OutputStream o = c.getOutputStream()) { o.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
        int code = c.getResponseCode();
        InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        if (is != null) { byte[] t = new byte[8192]; int r; while ((r = is.read(t)) > 0) bo.write(t, 0, r); is.close(); }
        String resp = new String(bo.toByteArray(), StandardCharsets.UTF_8);
        if (code != 200) {
            String msg = resp;
            try { msg = new JSONObject(resp).getJSONObject("error").optString("message", resp); } catch (Exception ignored) {}
            throw new java.io.IOException("Gemini " + code + ": " + (msg.length() > 150 ? msg.substring(0, 150) : msg));
        }
        JSONArray ps = new JSONObject(resp).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < ps.length(); i++) {
            JSONObject pt = ps.getJSONObject(i);
            if (pt.optBoolean("thought", false)) continue;
            out.append(pt.optString("text", ""));
        }
        return out.toString().trim();
    }

    // ======================= Ogg Opus 만들기 =======================

    static final class Ogg {
        private static final int[] CRC = new int[256];
        static {
            for (int i = 0; i < 256; i++) {
                int r = i << 24;
                for (int k = 0; k < 8; k++) r = (r & 0x80000000) != 0 ? (r << 1) ^ 0x04C11DB7 : r << 1;
                CRC[i] = r;
            }
        }

        static byte[] opus(List<byte[]> frames, int rate) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            int seq = 0;
            page(o, opusHead(), 0, seq++, 2);
            ByteBuffer tags = ByteBuffer.allocate(8 + 4 + 2 + 4).order(ByteOrder.LITTLE_ENDIAN);
            tags.put("OpusTags".getBytes(StandardCharsets.US_ASCII)).putInt(2).put((byte) 'm').put((byte) 'i').putInt(0);
            page(o, tags.array(), 0, seq++, 0);
            long gran = 312;
            for (int i = 0; i < frames.size(); i++) {
                gran += 960; // 20ms @ 48kHz
                page(o, frames.get(i), gran, seq++, i == frames.size() - 1 ? 4 : 0);
            }
            return o.toByteArray();
        }

        private static void page(ByteArrayOutputStream o, byte[] data, long gran, int seq, int flags) {
            int nseg = data.length / 255 + 1;
            ByteBuffer b = ByteBuffer.allocate(27 + nseg + data.length).order(ByteOrder.LITTLE_ENDIAN);
            b.put("OggS".getBytes(StandardCharsets.US_ASCII)).put((byte) 0).put((byte) flags)
                    .putLong(gran).putInt(1234).putInt(seq).putInt(0).put((byte) nseg);
            int n = data.length;
            for (int i = 0; i < nseg - 1; i++) { b.put((byte) 255); n -= 255; }
            b.put((byte) n);
            b.put(data);
            byte[] p = b.array();
            int c = 0;
            for (byte x : p) c = (c << 8) ^ CRC[((c >>> 24) ^ (x & 0xFF)) & 0xFF];
            p[22] = (byte) c; p[23] = (byte) (c >>> 8); p[24] = (byte) (c >>> 16); p[25] = (byte) (c >>> 24);
            o.write(p, 0, p.length);
        }
    }
}
