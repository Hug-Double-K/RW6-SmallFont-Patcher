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
            TEMPLATE_TOAST = 256, LLM_LOADING_CARD = 289, LLM_TOAST_STREAM = 292, LLM_FINISH_STREAM = 293;

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

    private XiaoAi(Context app) {
        this.app = app;
        this.prefs = app.getSharedPreferences("hub", Context.MODE_PRIVATE);
        HandlerThread t = new HandlerThread("xiaoai");
        t.start();
        h = new Handler(t.getLooper());
    }

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
        h.postDelayed(new Runnable() {   // 끝 감시: 무음·끊김·최대 길이
            @Override public void run() {
                if (myGen != gen || !active || stopped) return;
                long now = System.currentTimeMillis();
                boolean silentAfterSpeech = speechSeen && now - lastLoudAt > 1000;
                boolean noFrames = now - lastFrameAt > 900;
                boolean tooLong = now - startAt > 12_000;
                boolean nothingSaid = !speechSeen && now - startAt > 6_000;
                if (silentAfterSpeech || noFrames || tooLong || nothingSaid) finishListening();
                else h.postDelayed(this, 100);
            }
        }, 300);
    }

    private void audio(byte[] d, int off, int len) {
        if (!active) begin(0); // 시작 신호를 놓쳤어도 받음
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
        dbCount++;
        if (dbCount <= 10) { noiseDb = dbCount == 1 ? db : Math.min(noiseDb, db) * 0.5 + noiseDb * 0.5; return; }
        peakDb = Math.max(peakDb, db);
        boolean loud = db > Math.max(noiseDb + 9, -42);
        if (loud) { speechSeen = true; lastLoudAt = System.currentTimeMillis(); }
    }

    // ======================= 답하기 =======================

    private void finishListening() {
        if (stopped) return;
        stopped = true;
        closeDecoder();
        send(instr(STOP_CAPTURE));
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
        send(instr(RECOGNIZE_RESULT).msg(2, new Pb.W().bool(1, false).msg(2, new Pb.W().msg(1, new Pb.W().str(1, "…")))));
        send(instr(LLM_LOADING_CARD).msg(30, new Pb.W().varint(1, 20)));
        byte[] ogg = Ogg.opus(new ArrayList<>(frames), 16000);
        final int myGen = gen;
        new Thread(() -> {
            String q = "", a;
            try {
                long t0 = System.currentTimeMillis();
                String reply = gemini(key, prefs.getString("aiModel", DEFAULT_MODEL), ogg);
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
                if (!fq.isEmpty()) {
                    send(instr(RECOGNIZE_RESULT).msg(2, new Pb.W().bool(1, true).msg(2, new Pb.W().msg(1, new Pb.W().str(1, fq)))));
                }
                showText(fa, false);
                active = false;
            });
        }, "xiaoai-gemini").start();
    }

    /** 답(또는 오류)을 화면에 띄우고 대화를 끝낸다 */
    private void showText(String text, boolean isError) {
        send(instr(LLM_TOAST_STREAM).msg(33, new Pb.W().str(1, text)));
        send(instr(LLM_FINISH_STREAM));
        send(instr(TEMPLATE_TOAST).msg(3, new Pb.W().str(1, text)));
        send(instr(DIALOG_FINISH));
    }

    private Pb.W instr(int resultType) {
        return new Pb.W().varint(1, resultType).varint(13, session);
    }

    private void send(Pb.W instruction) {
        Pb.W packet = new Pb.W().varint(1, 14).varint(2, 0)
                .msg(16, new Pb.W().msg(1, new Pb.W().msg(1, instruction)));
        if (!link().send(packet)) log("워치에 보내지 못함 (연결 없음)");
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
