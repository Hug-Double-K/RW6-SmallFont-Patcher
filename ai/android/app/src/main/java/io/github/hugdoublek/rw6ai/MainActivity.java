package io.github.hugdoublek.rw6ai;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 시험용: 폰이 워치를 '블루투스 통화 장치'로 써서 워치 마이크로 녹음하고 워치 스피커로 재생할 수 있는지 확인.
 * 되면 → 워치에 대고 한국어로 말하고(폰이 인식·AI 호출) 워치로 답을 듣는 기능을 Mi Fitness 연결 그대로 만들 수 있음.
 */
public class MainActivity extends Activity {
    private TextView log;
    private AudioManager am;
    private volatile boolean running = false;
    private android.widget.EditText keyBox, modelBox;
    private android.speech.tts.TextToSpeech tts;
    private volatile boolean ttsReady = false;
    private final StringBuilder text = new StringBuilder();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        am = (AudioManager) getSystemService(AUDIO_SERVICE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(p, p, p, p);

        TextView title = new TextView(this);
        title.setText("RW6 AI 시험");
        title.setTextSize(24);
        root.addView(title);

        TextView desc = new TextView(this);
        desc.setText("워치를 손목에 차고(폰과 블루투스 연결된 상태) 아래 버튼을 누른 뒤,\n"
                + "Mi Fitness 연결은 그대로 둔 채 사용합니다.\n"
                + "★ 버튼: 삑 소리 뒤 워치에 대고 한국어로 물어보면, 답을 여기 글로 보여 주고 워치로 읽어 줍니다.");
        root.addView(desc);

        android.content.SharedPreferences sp = getSharedPreferences("rw6ai", MODE_PRIVATE);
        TextView kl = new TextView(this);
        kl.setText("\nGemini API 키 (이 폰에만 저장됩니다)");
        root.addView(kl);
        keyBox = new android.widget.EditText(this);
        keyBox.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        keyBox.setSingleLine(true);
        keyBox.setText(sp.getString("key", ""));
        root.addView(keyBox);
        TextView ml = new TextView(this);
        ml.setText("모델 이름 (모르면 그대로 두세요)");
        root.addView(ml);
        modelBox = new android.widget.EditText(this);
        modelBox.setSingleLine(true);
        modelBox.setText(sp.getString("model", "gemini-3.5-flash-lite"));
        root.addView(modelBox);

        Button askBtn = new Button(this);
        askBtn.setAllCaps(false);
        askBtn.setText("★ 워치에 말하고 답 듣기");
        askBtn.setOnClickListener(v -> {
            String key = keyBox.getText().toString().trim();
            String model = modelBox.getText().toString().trim();
            sp.edit().putString("key", key).putString("model", model).apply();
            if (key.isEmpty()) { text.setLength(0); say("먼저 Gemini API 키를 넣어 주세요."); return; }
            if (!permitted()) return;
            if (running) return;
            running = true;
            text.setLength(0);
            new Thread(() -> ask(key, model.isEmpty() ? "gemini-3.5-flash-lite" : model)).start();
        });
        root.addView(askBtn);

        tts = new android.speech.tts.TextToSpeech(this, st -> {
            if (st != android.speech.tts.TextToSpeech.SUCCESS) return;
            int r = tts.setLanguage(java.util.Locale.KOREAN);
            ttsReady = r != android.speech.tts.TextToSpeech.LANG_MISSING_DATA && r != android.speech.tts.TextToSpeech.LANG_NOT_SUPPORTED;
        });

        Button fake = new Button(this);
        fake.setAllCaps(false);
        fake.setText("(시험 0.4) 가짜 전화로 워치 통화 연결 깨우기");
        fake.setOnClickListener(v -> {
            if (running || !permitted()) return;
            running = true;
            text.setLength(0);
            new Thread(this::fakeCall).start();
        });
        root.addView(fake);

        Button go = new Button(this);
        go.setAllCaps(false);
        go.setText("(점검용) 워치 마이크·스피커 시험");
        go.setOnClickListener(v -> start());
        root.addView(go);

        Button copy = new Button(this);
        copy.setAllCaps(false);
        copy.setText("결과 복사");
        copy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("rw6ai", text.toString()));
        });
        root.addView(copy);

        log = new TextView(this);
        log.setTextIsSelectable(true);
        root.addView(log);

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);
    }

    @Override protected void onDestroy() {
        try { if (tts != null) tts.shutdown(); } catch (Throwable ignored) { }
        super.onDestroy();
    }

    private void start() {
        if (running) return;
        if (!permitted()) return;
        running = true;
        text.setLength(0);
        new Thread(this::test).start();
    }

    private boolean permitted() {
        List<String> need = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.BLUETOOTH_CONNECT);
        if (!need.isEmpty()) {
            requestPermissions(need.toArray(new String[0]), 1);
            say("권한을 허용한 뒤 버튼을 다시 눌러 주세요.");
            return false;
        }
        return true;
    }

    private void say(String s) {
        runOnUiThread(() -> { text.append(s).append('\n'); log.setText(text); });
    }

    private static String typeName(int t) {
        switch (t) {
            case 1: return "폰 수화부";
            case 2: return "폰 스피커";
            case 3: return "유선 헤드셋";
            case 7: return "블루투스 통화(SCO)";
            case 8: return "블루투스 음악(A2DP)";
            case 15: return "폰 마이크";
            case 26: return "블루투스 LE 헤드셋";
            case 27: return "블루투스 LE 스피커";
            default: return "종류 " + t;
        }
    }

    private static String name(AudioDeviceInfo d) {
        return d == null ? "모름" : typeName(d.getType()) + " / " + d.getProductName();
    }

    private static final int RATE = 16000;

    /** 짧은 소리를 통화 소리 길(워치 스피커)로 재생 */
    private void playPcm(short[] data, int n, int rate) throws InterruptedException {
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
        AudioDeviceInfo outDev = findSco(AudioManager.GET_DEVICES_OUTPUTS);
        if (outDev != null) tr.setPreferredDevice(outDev);
        tr.write(data, 0, n);
        tr.play();
        Thread.sleep(n * 1000L / rate + 250);
        tr.release();
    }

    private void beep() throws InterruptedException {
        int n = RATE * 15 / 100;
        short[] b = new short[n];
        for (int i = 0; i < n; i++) b[i] = (short) (Math.sin(2 * Math.PI * 880 * i / RATE) * 7000);
        playPcm(b, n, RATE);
    }

    /** 말이 끝날 때까지 녹음 (말 뒤 1.3초 조용하면 끝, 6초 동안 말이 없으면 포기, 최대 12초) */
    private short[] listen() {
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, RATE));
        if (rec.getState() != AudioRecord.STATE_INITIALIZED) { say("   ✘ 녹음 준비 실패"); return null; }
        AudioDeviceInfo in = findSco(AudioManager.GET_DEVICES_INPUTS);
        if (in != null) rec.setPreferredDevice(in);
        short[] buf = new short[RATE * 12];
        int chunk = RATE / 10, got = 0, n = 0, quiet = 0, speechChunks = 0;
        double floor = Double.MAX_VALUE, maxLv = 0;
        boolean speech = false;
        rec.startRecording();
        say("2) ▶ 워치에 대고 말하세요 (말이 끝나면 저절로 멈춥니다)…");
        while (got + chunk <= buf.length) {
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
            if (speech && quiet >= 13) break;
            if (!speech && n >= 60) break;
        }
        AudioDeviceInfo mic = rec.getRoutedDevice();
        rec.stop();
        rec.release();
        say(String.format(java.util.Locale.US, "   마이크: %s / 소음 %.0f · 최대 %.0f · %.1f초", name(mic),
                floor == Double.MAX_VALUE ? 0 : floor, maxLv, got / (float) RATE));
        if (!speech || speechChunks < 2) return null;
        return java.util.Arrays.copyOf(buf, got);
    }

    private static byte[] wav(short[] pcm) {
        int len = pcm.length * 2;
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(44 + len).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(36 + len).put("WAVE".getBytes());
        b.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1).putInt(RATE).putInt(RATE * 2).putShort((short) 2).putShort((short) 16);
        b.put("data".getBytes()).putInt(len);
        for (short v : pcm) b.putShort(v);
        return b.array();
    }

    /** 음성을 Gemini에 보내 '들은 말 + 답'을 받는다 */
    private String gemini(String key, String model, byte[] wav) throws Exception {
        String now = new java.text.SimpleDateFormat("yyyy-MM-dd (E) HH:mm", java.util.Locale.KOREA).format(new java.util.Date());
        String sys = "너는 손목시계 속 한국어 음성 비서야. 사용자의 음성을 듣고 한국어로 답해. "
                + "답은 시계 화면에서 읽고 소리로 듣기 좋게 2~4문장으로 짧고 분명하게, 마크다운·이모지·특수기호 없이 평문으로 써. "
                + "출력 형식은 반드시 다음과 같아. 첫 줄: 'Q: ' 뒤에 사용자가 한 말을 그대로 받아 적은 것. 둘째 줄부터: 'A: ' 뒤에 답. "
                + "소리가 없거나 알아들을 수 없으면 Q는 '(안 들림)'으로 하고 A에서 다시 말해 달라고 해. "
                + "지금은 한국 시간 " + now + " 이야.";
        org.json.JSONObject body = new org.json.JSONObject();
        body.put("system_instruction", new org.json.JSONObject().put("parts",
                new org.json.JSONArray().put(new org.json.JSONObject().put("text", sys))));
        org.json.JSONArray parts = new org.json.JSONArray();
        parts.put(new org.json.JSONObject().put("inline_data", new org.json.JSONObject()
                .put("mime_type", "audio/wav").put("data", android.util.Base64.encodeToString(wav, android.util.Base64.NO_WRAP))));
        parts.put(new org.json.JSONObject().put("text", "이 음성에 답해 줘."));
        body.put("contents", new org.json.JSONArray().put(new org.json.JSONObject().put("role", "user").put("parts", parts)));

        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(
                "https://generativelanguage.googleapis.com/v1beta/models/" + java.net.URLEncoder.encode(model, "UTF-8") + ":generateContent").openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(45000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("x-goog-api-key", key);
        try (java.io.OutputStream o = c.getOutputStream()) { o.write(body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
        int code = c.getResponseCode();
        java.io.InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        if (is != null) { byte[] t = new byte[8192]; int r; while ((r = is.read(t)) > 0) bo.write(t, 0, r); is.close(); }
        String resp = new String(bo.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        if (code != 200) {
            String msg = resp;
            try { msg = new org.json.JSONObject(resp).getJSONObject("error").optString("message", resp); } catch (Throwable ignored) { }
            throw new java.io.IOException("Gemini 응답 " + code + ": " + (msg.length() > 300 ? msg.substring(0, 300) : msg));
        }
        org.json.JSONArray ps = new org.json.JSONObject(resp).getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < ps.length(); i++) {
            org.json.JSONObject pt = ps.getJSONObject(i);
            if (pt.optBoolean("thought", false)) continue;
            out.append(pt.optString("text", ""));
        }
        return out.toString().trim();
    }

    /** 답을 한국어 음성으로 만들어 워치 스피커로 재생 */
    private void speak(String textToSay) throws Exception {
        if (!ttsReady) { say("   (폰에 한국어 음성 합성이 준비되지 않아 글로만 보여 드립니다)"); return; }
        java.io.File f = new java.io.File(getCacheDir(), "tts.wav");
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        tts.setOnUtteranceProgressListener(new android.speech.tts.UtteranceProgressListener() {
            @Override public void onStart(String id) { }
            @Override public void onDone(String id) { latch.countDown(); }
            @Override public void onError(String id) { latch.countDown(); }
        });
        if (tts.synthesizeToFile(textToSay, null, f, "ans") != android.speech.tts.TextToSpeech.SUCCESS) { say("   (음성 합성 실패)"); return; }
        if (!latch.await(40, java.util.concurrent.TimeUnit.SECONDS)) { say("   (음성 합성이 너무 오래 걸림)"); return; }
        byte[] all = java.nio.file.Files.readAllBytes(f.toPath());
        if (all.length < 44) { say("   (음성 파일이 비어 있음)"); return; }
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(all).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int channels = bb.getShort(22), rate = bb.getInt(24), bits = bb.getShort(34);
        int pos = 12, dataAt = -1, dataLen = 0;
        while (pos + 8 <= all.length) { // 'data' 덩어리 찾기
            int size = bb.getInt(pos + 4);
            if (all[pos] == 'd' && all[pos + 1] == 'a' && all[pos + 2] == 't' && all[pos + 3] == 'a') { dataAt = pos + 8; dataLen = Math.min(size < 0 ? Integer.MAX_VALUE : size, all.length - dataAt); break; }
            pos += 8 + Math.max(0, size) + (size & 1);
        }
        if (dataAt < 0 || bits != 16 || channels < 1 || channels > 2 || rate <= 0) { say("   (음성 형식을 재생할 수 없음: " + rate + "Hz " + bits + "bit " + channels + "ch)"); return; }
        int frames = dataLen / (2 * channels);
        short[] mono = new short[frames];
        for (int i = 0; i < frames; i++) mono[i] = bb.getShort(dataAt + i * 2 * channels);
        playPcm(mono, frames, rate);
    }

    private void ask(String key, String model) {
        android.content.BroadcastReceiver rcv = new android.content.BroadcastReceiver() {
            @Override public void onReceive(android.content.Context c, android.content.Intent i) {
                scoState = i.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1);
            }
        };
        try {
            long t0 = System.currentTimeMillis();
            say("1) 워치와 소리 길 여는 중…");
            android.content.IntentFilter f = new android.content.IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(rcv, f, 2 /* RECEIVER_EXPORTED */); else registerReceiver(rcv, f);
            AudioDeviceInfo bt = null;
            if (Build.VERSION.SDK_INT >= 31) {
                for (AudioDeviceInfo di : am.getAvailableCommunicationDevices()) if (isBt(di)) bt = di;
            }
            scoState = -1;
            am.setMode(AudioManager.MODE_IN_COMMUNICATION);
            boolean open;
            if (Build.VERSION.SDK_INT >= 31 && bt != null) { am.setCommunicationDevice(bt); open = waitSco(5000); }
            else { am.startBluetoothSco(); am.setBluetoothScoOn(true); open = waitSco(6000); }
            say(open ? String.format(java.util.Locale.US, "   열림 (%.1f초)", (System.currentTimeMillis() - t0) / 1000f)
                    : "   ⚠ 워치 소리 길이 안 열림 → 폰 마이크·스피커로 진행");
            beep();
            short[] pcm = listen();
            if (pcm == null) { say("결과: 말소리가 들리지 않았어요. 다시 눌러 주세요."); return; }
            say("3) Gemini에 묻는 중…");
            long t1 = System.currentTimeMillis();
            String reply = gemini(key, model, wav(pcm));
            say(String.format(java.util.Locale.US, "   응답까지 %.1f초", (System.currentTimeMillis() - t1) / 1000f));
            String q = "", a = reply;
            int ai = reply.indexOf("A:");
            if (reply.startsWith("Q:") && ai > 0) { q = reply.substring(2, ai).trim(); a = reply.substring(ai + 2).trim(); }
            say("\n들은 말: " + (q.isEmpty() ? "(표시 없음)" : q));
            say("답: " + a + "\n");
            say("4) 워치로 답을 읽어 주는 중…");
            speak(a);
        } catch (Throwable t) {
            say("오류: " + t.getMessage());
        } finally {
            try {
                if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice();
                am.setBluetoothScoOn(false);
                am.stopBluetoothSco();
                am.setMode(AudioManager.MODE_NORMAL);
            } catch (Throwable ignored) { }
            try { unregisterReceiver(rcv); } catch (Throwable ignored) { }
            running = false;
            say("끝.");
        }
    }

    /** 가짜 수신 전화를 만들어 워치가 통화용으로 붙는지, 붙으면 워치 마이크로 녹음되는지 확인 */
    private void fakeCall() {
        android.bluetooth.BluetoothAdapter adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
        AiConnectionService.logger = this::say;
        try {
            say("RW6 AI 시험 0.4 — 가짜 수신 전화 (SDK " + Build.VERSION.SDK_INT + ")");
            if (adapter != null) {
                adapter.getProfileProxy(this, new android.bluetooth.BluetoothProfile.ServiceListener() {
                    @Override public void onServiceConnected(int profile, android.bluetooth.BluetoothProfile proxy) { headset = (android.bluetooth.BluetoothHeadset) proxy; }
                    @Override public void onServiceDisconnected(int profile) { headset = null; }
                }, android.bluetooth.BluetoothProfile.HEADSET);
                for (int i = 0; i < 15 && headset == null; i++) Thread.sleep(200);
            }
            android.telecom.TelecomManager tm = (android.telecom.TelecomManager) getSystemService(TELECOM_SERVICE);
            android.telecom.PhoneAccountHandle handle = new android.telecom.PhoneAccountHandle(
                    new android.content.ComponentName(this, AiConnectionService.class), "rw6ai");
            tm.registerPhoneAccount(android.telecom.PhoneAccount.builder(handle, "RW6 AI")
                    .setCapabilities(android.telecom.PhoneAccount.CAPABILITY_SELF_MANAGED)
                    .addSupportedUriScheme(android.telecom.PhoneAccount.SCHEME_TEL).build());
            AiConnectionService.current = null;
            Bundle ex = new Bundle();
            ex.putParcelable(android.telecom.TelecomManager.EXTRA_INCOMING_CALL_ADDRESS, android.net.Uri.fromParts("tel", "000", null));
            tm.addNewIncomingCall(handle, ex);
            say("1) 가짜 전화를 만들었습니다. 워치에 전화 화면이 뜨는지 보세요 (뜨면 '받기'를 누르세요)…");
            AiConnectionService.AiConnection conn = null;
            for (int i = 0; i < 30 && conn == null; i++) { Thread.sleep(100); conn = AiConnectionService.current; }
            if (conn == null) { say("결론: ✘ 전화가 만들어지지 않음"); return; }

            String last = null;
            int hfpSecs = 0;
            for (int sec = 1; sec <= 20 && !conn.answered && !conn.ended; sec++) {
                Thread.sleep(1000);
                String now = "없음";
                android.bluetooth.BluetoothHeadset hs = headset;
                if (hs != null) {
                    List<android.bluetooth.BluetoothDevice> ds = hs.getConnectedDevices();
                    if (!ds.isEmpty()) now = String.valueOf(ds.get(0).getName());
                }
                if (!now.equals(last)) { say("   " + sec + "초: 통화용 블루투스 연결 = " + now); last = now; }
                if (!"없음".equals(now)) hfpSecs++;
                if (hfpSecs >= 4 && !conn.answered && !conn.ended) { say("   (워치가 붙었으니 자동으로 받습니다)"); conn.onAnswer(); }
            }
            if (conn.ended) { say("결론: 전화가 거절되거나 끊김"); return; }
            if (!conn.answered) {
                say("결론: 20초 동안 워치가 통화용으로 붙지 않았고 받지도 않음 → 이 방식으로는 워치를 깨우지 못함");
                conn.end(android.telecom.DisconnectCause.MISSED);
                return;
            }
            say("2) 통화 중 상태. 워치로 소리 길을 엽니다…");
            conn.setAudioRoute(android.telecom.CallAudioState.ROUTE_BLUETOOTH);
            Thread.sleep(2500);
            boolean ok = recordAndPlay(MediaRecorder.AudioSource.VOICE_COMMUNICATION, "통화용");
            conn.end(android.telecom.DisconnectCause.LOCAL);
            say(ok ? "결론: ★ 가짜 전화 방식으로 워치 마이크를 쓸 수 있음" : "결론: 전화는 됐지만 워치 마이크로 녹음되지 않음");
        } catch (Throwable t) {
            say("오류: " + t);
        } finally {
            try { AiConnectionService.AiConnection c = AiConnectionService.current; if (c != null) c.end(android.telecom.DisconnectCause.LOCAL); } catch (Throwable ignored) { }
            try { if (adapter != null && headset != null) adapter.closeProfileProxy(android.bluetooth.BluetoothProfile.HEADSET, headset); } catch (Throwable ignored) { }
            headset = null;
            running = false;
            say("끝. '결과 복사'를 눌러 대화창에 붙여 넣어 주세요.");
        }
    }

    private volatile int scoState = -1;
    private volatile android.bluetooth.BluetoothHeadset headset = null;

    private static String scoName(int st) {
        switch (st) {
            case AudioManager.SCO_AUDIO_STATE_CONNECTED: return "연결됨";
            case AudioManager.SCO_AUDIO_STATE_CONNECTING: return "연결 중";
            case AudioManager.SCO_AUDIO_STATE_DISCONNECTED: return "끊김";
            case AudioManager.SCO_AUDIO_STATE_ERROR: return "오류";
            default: return "모름(" + st + ")";
        }
    }

    private AudioDeviceInfo findSco(int flag) {
        for (AudioDeviceInfo d : am.getDevices(flag)) if (d.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) return d;
        return null;
    }

    private static boolean isBt(AudioDeviceInfo d) {
        return d != null && (d.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || d.getType() == 26);
    }

    /** SCO(통화 소리 길)가 실제로 연결될 때까지 기다림 */
    private boolean waitSco(int ms) throws InterruptedException {
        for (int i = 0; i < ms / 200; i++) {
            if (scoState == AudioManager.SCO_AUDIO_STATE_CONNECTED) return true;
            Thread.sleep(200);
        }
        return scoState == AudioManager.SCO_AUDIO_STATE_CONNECTED;
    }

    /** 3초 녹음 → 워치 마이크로 녹음됐으면 재생까지. 워치 마이크였으면 true */
    private boolean recordAndPlay(int source, String sourceName) throws InterruptedException {
        int rate = 16000;
        int min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord rec = new AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, rate));
        if (rec.getState() != AudioRecord.STATE_INITIALIZED) { say("   ✘ 녹음 준비 실패 (" + sourceName + ")"); return false; }
        AudioDeviceInfo in = findSco(AudioManager.GET_DEVICES_INPUTS);
        if (in != null) say("   워치 마이크 지정: " + rec.setPreferredDevice(in));
        else say("   (입력 장치 목록에 워치 마이크가 없음)");
        short[] pcm = new short[rate * 3];
        rec.startRecording();
        say("   ▶ 지금 워치에 대고 말하세요 (3초, " + sourceName + ")…");
        int got = 0;
        while (got < pcm.length) {
            int n = rec.read(pcm, got, Math.min(1600, pcm.length - got));
            if (n <= 0) break;
            got += n;
        }
        AudioDeviceInfo mic = rec.getRoutedDevice();
        rec.stop();
        rec.release();
        int peak = 0;
        long sum = 0;
        for (int i = 0; i < got; i++) { int a = Math.abs(pcm[i]); if (a > peak) peak = a; sum += a; }
        say("   녹음에 쓰인 마이크: " + name(mic) + " / 최대 " + peak + " 평균 " + (got > 0 ? sum / got : 0));
        if (!isBt(mic) || got == 0) return false;

        say("   ★ 워치 마이크로 녹음됨! 이제 재생합니다. 워치에서 소리가 나는지 들어 보세요…");
        AudioTrack tr = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(rate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(got * 2)
                .build();
        AudioDeviceInfo outDev = findSco(AudioManager.GET_DEVICES_OUTPUTS);
        if (outDev != null) tr.setPreferredDevice(outDev);
        tr.write(pcm, 0, got);
        tr.play();
        Thread.sleep(500);
        AudioDeviceInfo out = tr.getRoutedDevice();
        Thread.sleep(got * 1000L / rate + 300);
        tr.release();
        say("   재생에 쓰인 스피커: " + name(out));
        return true;
    }

    private void test() {
        android.content.BroadcastReceiver rcv = new android.content.BroadcastReceiver() {
            @Override public void onReceive(android.content.Context c, android.content.Intent i) {
                scoState = i.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1);
                say("   [통화 소리 길 상태] " + scoName(scoState));
            }
        };
        android.bluetooth.BluetoothAdapter adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
        try {
            say("RW6 AI 시험 0.2 (안드로이드 SDK " + Build.VERSION.SDK_INT + ")");
            android.content.IntentFilter f = new android.content.IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(rcv, f, 2 /* RECEIVER_EXPORTED */); else registerReceiver(rcv, f);

            // 0) 워치가 '통화용'으로 연결돼 있는지
            say("0) 통화용 블루투스(HFP) 연결 확인");
            android.bluetooth.BluetoothDevice watch = null;
            if (adapter != null) {
                adapter.getProfileProxy(this, new android.bluetooth.BluetoothProfile.ServiceListener() {
                    @Override public void onServiceConnected(int profile, android.bluetooth.BluetoothProfile proxy) { headset = (android.bluetooth.BluetoothHeadset) proxy; }
                    @Override public void onServiceDisconnected(int profile) { headset = null; }
                }, android.bluetooth.BluetoothProfile.HEADSET);
                for (int i = 0; i < 15 && headset == null; i++) Thread.sleep(200);
            }
            if (headset == null) say("   (통화용 블루투스 정보를 못 읽음)");
            else {
                for (android.bluetooth.BluetoothDevice d : headset.getConnectedDevices()) {
                    say("   - " + d.getName() + " / 소리 연결: " + headset.isAudioConnected(d));
                    if (watch == null || String.valueOf(d.getName()).toLowerCase().contains("watch")) watch = d;
                }
                if (watch == null) say("   ⚠ 통화용으로 연결된 블루투스 기기가 없음");
            }
            AudioDeviceInfo bt = null;
            if (Build.VERSION.SDK_INT >= 31) {
                for (AudioDeviceInfo di : am.getAvailableCommunicationDevices()) if (isBt(di)) bt = di;
            }

            boolean ok = false;

            // 1) 새 방식 (안드로이드 12+): setCommunicationDevice
            if (Build.VERSION.SDK_INT >= 31 && bt != null) {
                say("1) 방식 1: 통화 장치로 지정");
                scoState = -1;
                am.setMode(AudioManager.MODE_IN_COMMUNICATION);
                say("   지정 요청: " + am.setCommunicationDevice(bt));
                say("   소리 길 연결: " + (waitSco(5000) ? "됨" : "안 됨 (" + scoName(scoState) + ")"));
                ok = recordAndPlay(MediaRecorder.AudioSource.VOICE_COMMUNICATION, "통화용");
                am.clearCommunicationDevice();
                am.setMode(AudioManager.MODE_NORMAL);
                Thread.sleep(800);
            }

            // 2) 옛 방식: startBluetoothSco
            if (!ok) {
                say("2) 방식 2: 블루투스 통화 소리 길 직접 열기");
                scoState = -1;
                am.setMode(AudioManager.MODE_IN_COMMUNICATION);
                am.startBluetoothSco();
                am.setBluetoothScoOn(true);
                say("   소리 길 연결: " + (waitSco(6000) ? "됨" : "안 됨 (" + scoName(scoState) + ")"));
                ok = recordAndPlay(MediaRecorder.AudioSource.VOICE_COMMUNICATION, "통화용");
                if (!ok) ok = recordAndPlay(MediaRecorder.AudioSource.MIC, "일반 마이크");
                am.setBluetoothScoOn(false);
                am.stopBluetoothSco();
                am.setMode(AudioManager.MODE_NORMAL);
                Thread.sleep(800);
            }

            // 3) 음성 인식 방식: 헤드셋에 '음성 인식 시작'을 알림
            if (!ok && headset != null && watch != null) {
                say("3) 방식 3: 음성 인식 시작 신호");
                scoState = -1;
                boolean started = headset.startVoiceRecognition(watch);
                say("   신호 보내기: " + (started ? "받아들여짐" : "거절됨"));
                boolean audio = false;
                for (int i = 0; i < 30 && !audio; i++) { Thread.sleep(200); audio = headset.isAudioConnected(watch); }
                say("   워치와 소리 연결: " + (audio ? "됨" : "안 됨"));
                if (started) {
                    am.setBluetoothScoOn(true);
                    ok = recordAndPlay(MediaRecorder.AudioSource.VOICE_RECOGNITION, "음성 인식용");
                    if (!ok) ok = recordAndPlay(MediaRecorder.AudioSource.VOICE_COMMUNICATION, "통화용");
                    am.setBluetoothScoOn(false);
                    headset.stopVoiceRecognition(watch);
                }
            }

            say(ok ? "결론: ★ 워치 마이크를 쓸 수 있음" : "결론: 세 방식 모두 워치 마이크로 녹음되지 않음");
        } catch (Throwable t) {
            say("오류: " + t);
        } finally {
            try {
                if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice();
                am.setBluetoothScoOn(false);
                am.stopBluetoothSco();
                am.setMode(AudioManager.MODE_NORMAL);
            } catch (Throwable ignored) { }
            try { unregisterReceiver(rcv); } catch (Throwable ignored) { }
            try { if (adapter != null && headset != null) adapter.closeProfileProxy(android.bluetooth.BluetoothProfile.HEADSET, headset); } catch (Throwable ignored) { }
            headset = null;
            running = false;
            say("끝. '결과 복사'를 눌러 대화창에 붙여 넣어 주세요.");
        }
    }
}
