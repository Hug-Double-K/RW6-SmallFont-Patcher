package io.github.hugdoublek.rw6ai;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothProfile;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.telecom.CallAudioState;
import android.telecom.DisconnectCause;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.util.Base64;

import com.xiaomi.xms.wearable.Wearable;
import com.xiaomi.xms.wearable.message.MessageApi;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 대기 서비스: 워치 앱의 '말하기' 신호(Mi Fitness 경유)를 받으면
 * 가짜 수신 전화로 워치의 통화용 블루투스를 깨움 → 워치 마이크로 듣기 → Gemini → 답을 워치 스피커로 읽고 워치 화면에 글로 보냄.
 */
public class AiService extends Service {
    static final String ACTION_ASK = "io.github.hugdoublek.rw6ai.ASK";
    static final String DEFAULT_MODEL = "gemini-3.5-flash-lite";
    static volatile boolean alive = false;
    /** 화면(MainActivity)이 열려 있으면 기록이 바뀔 때 알려 줌 */
    static volatile Runnable ui;
    private static final StringBuilder LOG = new StringBuilder();

    private static final int RATE = 16000;
    private AudioManager am;
    private TextToSpeech tts;
    private volatile boolean ttsReady = false;
    private final HandlerThread thread = new HandlerThread("rw6ai");
    private Handler worker;
    private Handler main;
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private volatile BluetoothHeadset headset;
    private volatile String nodeId;
    private volatile String lastResult = "";
    private volatile long lastAt = 0;

    static String logText() { synchronized (LOG) { return LOG.toString(); } }

    static void say(String s) {
        synchronized (LOG) {
            LOG.append(new SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(new Date())).append(' ').append(s).append('\n');
            if (LOG.length() > 8000) LOG.delete(0, LOG.length() - 6000);
        }
        Runnable r = ui;
        if (r != null) r.run();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        alive = true;
        am = (AudioManager) getSystemService(AUDIO_SERVICE);
        main = new Handler(Looper.getMainLooper());
        thread.start();
        worker = new Handler(thread.getLooper());

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel("ai", "RW6 AI 대기", NotificationManager.IMPORTANCE_LOW));
        Notification n = new Notification.Builder(this, "ai")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("RW6 AI 대기 중")
                .setContentText("워치에서 '말하기'를 누르면 응답합니다")
                .setOngoing(true)
                .build();
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            else startForeground(1, n);
        } catch (Throwable t) {
            try { startForeground(1, n); } catch (Throwable ignored) { }
            say("대기 서비스 시작 경고: " + t.getMessage());
        }

        tts = new TextToSpeech(this, st -> {
            if (st != TextToSpeech.SUCCESS) return;
            int r = tts.setLanguage(Locale.KOREAN);
            ttsReady = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED;
        });
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter != null) adapter.getProfileProxy(this, new BluetoothProfile.ServiceListener() {
                @Override public void onServiceConnected(int profile, BluetoothProfile proxy) { headset = (BluetoothHeadset) proxy; }
                @Override public void onServiceDisconnected(int profile) { headset = null; }
            }, BluetoothProfile.HEADSET);
        } catch (Throwable t) { say("블루투스 정보 읽기 실패: " + t.getMessage()); }
        AiConnectionService.logger = null; // 전화 내부 기록은 평소엔 남기지 않음
        say("대기 시작");
        relisten.run();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_ASK.equals(intent.getAction())) startAsk("폰");
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        alive = false;
        main.removeCallbacksAndMessages(null);
        try { if (tts != null) tts.shutdown(); } catch (Throwable ignored) { }
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter != null && headset != null) adapter.closeProfileProxy(BluetoothProfile.HEADSET, headset);
        } catch (Throwable ignored) { }
        thread.quitSafely();
        say("대기 종료");
        super.onDestroy();
    }

    // ---------- 워치와 주고받기 (Mi Fitness 경유) ----------

    /** 워치 앱의 신호를 듣도록 등록. Mi Fitness가 재시작되면 등록이 사라질 수 있어 5분마다 다시 등록 */
    private final Runnable relisten = new Runnable() {
        @Override public void run() {
            registerListener();
            main.postDelayed(this, 5 * 60 * 1000);
        }
    };

    private void registerListener() {
        try {
            Wearable.getNodeApi(this).getConnectedNodes().addOnSuccessListener(nodes -> {
                if (nodes == null || nodes.isEmpty()) { nodeId = null; return; }
                final String id = nodes.get(0).id;
                nodeId = id;
                final MessageApi api = Wearable.getMessageApi(this);
                final AtomicBoolean once = new AtomicBoolean(false);
                Runnable add = () -> {
                    if (!once.compareAndSet(false, true)) return;
                    api.addListener(id, (nid, data) -> onWatch(new String(data, StandardCharsets.UTF_8)))
                            .addOnFailureListener(e -> say("워치 신호 듣기 등록 실패: " + e.getMessage()));
                };
                api.removeListener(id).addOnSuccessListener(v -> add.run()).addOnFailureListener(e -> add.run());
                main.postDelayed(add, 2000);
            }).addOnFailureListener(e -> nodeId = null);
        } catch (Throwable t) { say("워치 연결 확인 실패: " + t.getMessage()); }
    }

    private void onWatch(String s) {
        if (s.contains("ask")) startAsk("워치");
        else if (s.contains("hello")) {
            if (lastAt > 0 && System.currentTimeMillis() - lastAt < 5 * 60 * 1000) toWatch("R" + lastResult);
        }
    }

    private void toWatch(String msg) {
        String id = nodeId;
        if (id == null) { registerListener(); return; }
        try {
            Wearable.getMessageApi(this).sendMessage(id, msg.getBytes(StandardCharsets.UTF_8))
                    .addOnFailureListener(e -> say("워치로 보내기 실패: " + e.getMessage()));
        } catch (Throwable t) { say("워치로 보내기 오류: " + t.getMessage()); }
    }

    /** 통화가 끝난 뒤 워치 앱을 앞으로 띄우고 결과를 몇 번 보냄 (워치 앱이 잠들어 있을 수 있음) */
    private void finishToWatch(String msg) {
        String id = nodeId;
        if (id == null) return;
        try { Wearable.getNodeApi(this).launchWearApp(id, "/index"); } catch (Throwable ignored) { }
        for (long t : new long[]{500, 1500, 3000}) main.postDelayed(() -> toWatch(msg), t);
    }

    // ---------- 질문 한 번 ----------

    private void startAsk(String from) {
        if (!busy.compareAndSet(false, true)) { toWatch("S이미 진행 중입니다"); return; }
        worker.post(() -> {
            String end = null;
            try { end = ask(from); }
            catch (Throwable t) { end = "E오류: " + t.getMessage(); say("오류: " + t); }
            finally { busy.set(false); }
            if (end != null) finishToWatch(end);
        });
    }

    /** @return 워치에 마지막으로 보낼 메시지 (R = 결과, E = 오류) */
    private String ask(String from) throws Exception {
        long t0 = System.currentTimeMillis();
        say("— 질문 시작 (" + from + ") —");
        SharedPreferences sp = getSharedPreferences("rw6ai", MODE_PRIVATE);
        String key = sp.getString("key", "").trim();
        String model = sp.getString("model", DEFAULT_MODEL).trim();
        if (model.isEmpty()) model = DEFAULT_MODEL;
        if (key.isEmpty()) return fail("폰 앱에 Gemini API 키를 먼저 넣어 주세요");
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return fail("폰 앱에서 마이크 권한을 허용해 주세요");
        toWatch("S워치를 통화로 연결하는 중…");

        AiConnectionService.AiConnection conn = null;
        try {
            // 1) 가짜 수신 전화 → Mi Fitness가 워치에 알리고 워치가 통화용 블루투스로 붙음
            TelecomManager tm = (TelecomManager) getSystemService(TELECOM_SERVICE);
            PhoneAccountHandle handle = new PhoneAccountHandle(new ComponentName(this, AiConnectionService.class), "rw6ai");
            tm.registerPhoneAccount(PhoneAccount.builder(handle, "RW6 AI")
                    .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
                    .addSupportedUriScheme(PhoneAccount.SCHEME_TEL).build());
            AiConnectionService.current = null;
            Bundle ex = new Bundle();
            ex.putParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS, Uri.fromParts("tel", "000", null));
            tm.addNewIncomingCall(handle, ex);
            for (int i = 0; i < 30 && conn == null; i++) { Thread.sleep(100); conn = AiConnectionService.current; }
            if (conn == null) return fail("전화 연결을 만들지 못했어요");

            boolean hfp = false;
            for (int i = 0; i < 100 && !conn.ended && !conn.answered && !hfp; i++) { // 최대 10초
                BluetoothHeadset hs = headset;
                try { hfp = hs != null && !hs.getConnectedDevices().isEmpty(); } catch (Throwable ignored) { }
                if (!hfp) Thread.sleep(100);
            }
            if (conn.ended) return fail("워치에서 전화를 끊어 취소됐어요");
            if (!hfp && !conn.answered) return fail("워치가 통화용으로 연결되지 않았어요 (Mi Fitness 연결 확인)");
            if (!conn.answered) conn.onAnswer(); // 워치에서 받기를 누를 필요 없이 바로 받음
            conn.setAudioRoute(CallAudioState.ROUTE_BLUETOOTH);
            for (int i = 0; i < 40 && conn.route != CallAudioState.ROUTE_BLUETOOTH && !conn.ended; i++) Thread.sleep(100);
            Thread.sleep(900);
            say(String.format(Locale.US, "워치 통화 연결 %.1f초", (System.currentTimeMillis() - t0) / 1000f));
            if (conn.ended) return fail("워치에서 전화를 끊어 취소됐어요");

            // 2) 삑 → 듣기
            beep(conn);
            short[] pcm = listen(conn);
            if (conn.ended) return fail("워치에서 전화를 끊어 취소됐어요");
            if (pcm == null) { speak("잘 못 들었어요. 다시 눌러 주세요.", conn); return fail("말소리가 들리지 않았어요"); }

            // 3) Gemini
            toWatch("S생각 중…");
            long t1 = System.currentTimeMillis();
            String reply = gemini(key, model, wav(pcm));
            say(String.format(Locale.US, "Gemini 응답 %.1f초", (System.currentTimeMillis() - t1) / 1000f));
            String q = "", a = reply;
            int ai = reply.indexOf("A:");
            if (reply.startsWith("Q:") && ai > 0) { q = reply.substring(2, ai).trim(); a = reply.substring(ai + 2).trim(); }
            say("들은 말: " + q);
            say("답: " + a);
            lastResult = new JSONObject().put("id", System.currentTimeMillis()).put("q", q).put("a", a).toString();
            lastAt = System.currentTimeMillis();
            toWatch("R" + lastResult);

            // 4) 답 읽어 주기
            speak(a, conn);
            say(String.format(Locale.US, "— 끝 (전체 %.1f초) —", (System.currentTimeMillis() - t0) / 1000f));
            return "R" + lastResult;
        } finally {
            if (conn != null) conn.end(DisconnectCause.LOCAL);
        }
    }

    private String fail(String msg) {
        say("✘ " + msg);
        return "E" + msg;
    }

    // ---------- 소리 ----------

    private AudioDeviceInfo findSco(int flag) {
        for (AudioDeviceInfo d : am.getDevices(flag)) if (d.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) return d;
        return null;
    }

    private static String devName(AudioDeviceInfo d) {
        if (d == null) return "모름";
        int t = d.getType();
        return (t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ? "워치(블루투스 통화)" : t == 15 ? "폰 마이크" : "종류 " + t) + " / " + d.getProductName();
    }

    /** 통화 소리 길(워치 스피커)로 재생. 통화가 끊기면 멈춤 */
    private void playPcm(short[] data, int n, int rate, AiConnectionService.AiConnection conn) throws InterruptedException {
        if (n <= 0) return;
        AudioTrack tr = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(rate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(n * 2)
                .build();
        try {
            AudioDeviceInfo outDev = findSco(AudioManager.GET_DEVICES_OUTPUTS);
            if (outDev != null) tr.setPreferredDevice(outDev);
            tr.write(data, 0, n);
            tr.play();
            long until = System.currentTimeMillis() + n * 1000L / rate + 250;
            while (System.currentTimeMillis() < until) {
                if (conn != null && conn.ended) { tr.stop(); break; }
                Thread.sleep(80);
            }
        } finally { tr.release(); }
    }

    private void beep(AiConnectionService.AiConnection conn) throws InterruptedException {
        int n = RATE * 15 / 100;
        short[] b = new short[n];
        for (int i = 0; i < n; i++) b[i] = (short) (Math.sin(2 * Math.PI * 880 * i / RATE) * 7000);
        playPcm(b, n, RATE, conn);
    }

    /** 말이 끝날 때까지 녹음 (말 뒤 1초 조용하면 끝, 6초 동안 말이 없으면 포기, 최대 15초) */
    private short[] listen(AiConnectionService.AiConnection conn) {
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, RATE));
        if (rec.getState() != AudioRecord.STATE_INITIALIZED) { say("녹음 준비 실패"); return null; }
        AudioDeviceInfo in = findSco(AudioManager.GET_DEVICES_INPUTS);
        if (in != null) rec.setPreferredDevice(in);
        short[] buf = new short[RATE * 15];
        int chunk = RATE / 10, got = 0, n = 0, quiet = 0, speechChunks = 0;
        double floor = Double.MAX_VALUE, maxLv = 0;
        boolean speech = false;
        rec.startRecording();
        while (got + chunk <= buf.length && !conn.ended) {
            int r = rec.read(buf, got, chunk);
            if (r <= 0) break;
            long sum = 0;
            for (int i = 0; i < r; i++) sum += Math.abs(buf[got + i]);
            double lv = sum / (double) r;
            got += r;
            n++;
            if (n <= 3) { floor = Math.min(floor, lv); continue; } // 처음 0.3초로 주변 소음 크기를 잼
            if (lv > maxLv) maxLv = lv;
            double th = Math.max(floor * 3, 45);
            if (lv > th) { speech = true; speechChunks++; quiet = 0; } else if (speech) quiet++;
            if (speech && quiet >= 10) break;
            if (!speech && n >= 60) break;
        }
        AudioDeviceInfo mic = rec.getRoutedDevice();
        rec.stop();
        rec.release();
        say(String.format(Locale.US, "마이크: %s / 소음 %.0f · 최대 %.0f · %.1f초", devName(mic),
                floor == Double.MAX_VALUE ? 0 : floor, maxLv, got / (float) RATE));
        if (!speech || speechChunks < 2) return null;
        return Arrays.copyOf(buf, got);
    }

    private static byte[] wav(short[] pcm) {
        int len = pcm.length * 2;
        ByteBuffer b = ByteBuffer.allocate(44 + len).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(36 + len).put("WAVE".getBytes());
        b.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1).putInt(RATE).putInt(RATE * 2).putShort((short) 2).putShort((short) 16);
        b.put("data".getBytes()).putInt(len);
        for (short v : pcm) b.putShort(v);
        return b.array();
    }

    /** 음성을 Gemini에 보내 '들은 말 + 답'을 받는다 */
    private String gemini(String key, String model, byte[] wav) throws Exception {
        String now = new SimpleDateFormat("yyyy-MM-dd (E) HH:mm", Locale.KOREA).format(new Date());
        String sys = "너는 손목시계 속 한국어 음성 비서야. 사용자의 음성을 듣고 한국어로 답해. "
                + "답은 시계 화면에서 읽고 소리로 듣기 좋게 2~4문장으로 짧고 분명하게, 마크다운·이모지·특수기호 없이 평문으로 써. "
                + "출력 형식은 반드시 다음과 같아. 첫 줄: 'Q: ' 뒤에 사용자가 한 말을 그대로 받아 적은 것. 둘째 줄부터: 'A: ' 뒤에 답. "
                + "소리가 없거나 알아들을 수 없으면 Q는 '(안 들림)'으로 하고 A에서 다시 말해 달라고 해. "
                + "지금은 한국 시간 " + now + " 이야.";
        JSONObject body = new JSONObject();
        body.put("system_instruction", new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", sys))));
        JSONArray parts = new JSONArray();
        parts.put(new JSONObject().put("inline_data", new JSONObject()
                .put("mime_type", "audio/wav").put("data", Base64.encodeToString(wav, Base64.NO_WRAP))));
        parts.put(new JSONObject().put("text", "이 음성에 답해 줘."));
        body.put("contents", new JSONArray().put(new JSONObject().put("role", "user").put("parts", parts)));

        HttpURLConnection c = (HttpURLConnection) new URL(
                "https://generativelanguage.googleapis.com/v1beta/models/" + URLEncoder.encode(model, "UTF-8") + ":generateContent").openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(45000);
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
            try { msg = new JSONObject(resp).getJSONObject("error").optString("message", resp); } catch (Throwable ignored) { }
            throw new java.io.IOException("Gemini 응답 " + code + ": " + (msg.length() > 200 ? msg.substring(0, 200) : msg));
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

    /** 글을 한국어 음성으로 만들어 워치 스피커로 재생 */
    private void speak(String textToSay, AiConnectionService.AiConnection conn) throws Exception {
        if (!ttsReady) { say("(폰에 한국어 음성 합성이 없어 글로만 보냄)"); return; }
        File f = new File(getCacheDir(), "tts.wav");
        final CountDownLatch latch = new CountDownLatch(1);
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) { }
            @Override public void onDone(String id) { latch.countDown(); }
            @Override public void onError(String id) { latch.countDown(); }
        });
        if (tts.synthesizeToFile(textToSay, null, f, "ans") != TextToSpeech.SUCCESS) { say("(음성 합성 실패)"); return; }
        if (!latch.await(40, TimeUnit.SECONDS)) { say("(음성 합성이 너무 오래 걸림)"); return; }
        byte[] all = Files.readAllBytes(f.toPath());
        if (all.length < 44) { say("(음성 파일이 비어 있음)"); return; }
        ByteBuffer bb = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN);
        int channels = bb.getShort(22), rate = bb.getInt(24), bits = bb.getShort(34);
        int pos = 12, dataAt = -1, dataLen = 0;
        while (pos + 8 <= all.length) { // 'data' 덩어리 찾기
            int size = bb.getInt(pos + 4);
            if (all[pos] == 'd' && all[pos + 1] == 'a' && all[pos + 2] == 't' && all[pos + 3] == 'a') {
                dataAt = pos + 8;
                dataLen = Math.min(size < 0 ? Integer.MAX_VALUE : size, all.length - dataAt);
                break;
            }
            pos += 8 + Math.max(0, size) + (size & 1);
        }
        if (dataAt < 0 || bits != 16 || channels < 1 || channels > 2 || rate <= 0) { say("(음성 형식을 재생할 수 없음: " + rate + "Hz " + bits + "bit " + channels + "ch)"); return; }
        int frames = dataLen / (2 * channels);
        short[] mono = new short[frames];
        for (int i = 0; i < frames; i++) mono[i] = bb.getShort(dataAt + i * 2 * channels);
        playPcm(mono, frames, rate, conn);
    }
}
