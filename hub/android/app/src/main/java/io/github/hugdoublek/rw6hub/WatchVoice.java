package io.github.hugdoublek.rw6hub;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.Locale;

/**
 * 답을 소리로 읽어 워치 스피커로 보낸다.
 * 샤오아이를 켜면 워치가 통화(HFP) 연결을 여니, 그 통화 음성 통로(SCO)로 폰 TTS를 흘려 보낸다.
 * 통화 중이면 건드리지 않는다.
 */
final class WatchVoice {

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private String pending;
    private int savedMode = AudioManager.MODE_NORMAL;
    private boolean routed = false;

    WatchVoice(Context app) { this.app = app; }

    private void log(String s) { WatchLink.get(app).log("[AI] " + s); }

    void speak(String text) { main.post(() -> speakMain(text)); }

    private void speakMain(String text) {
        AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        int m = am.getMode();
        if (m == AudioManager.MODE_IN_CALL || m == AudioManager.MODE_RINGTONE) { log("통화 중이라 소리 생략"); return; }
        if (tts == null) {
            pending = text;
            tts = new TextToSpeech(app, st -> main.post(() -> {
                ttsReady = st == TextToSpeech.SUCCESS;
                if (!ttsReady) { log("폰 TTS를 쓸 수 없음"); return; }
                tts.setLanguage(Locale.KOREAN);
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String id) {}
                    @Override public void onDone(String id) { main.post(() -> play(new java.io.File(app.getCacheDir(), "answer.wav"))); }
                    @Override public void onError(String id) { log("음성 만들기 오류"); }
                });
                String p = pending; pending = null;
                if (p != null) speakMain(p);
            }));
            return;
        }
        if (!ttsReady) { pending = text; return; }
        // TTS 엔진이 오디오 설정을 무시하고 폰 스피커로 내는 경우가 있어, 파일로 만든 뒤 직접 '통화 음성'으로 재생
        java.io.File f = new java.io.File(app.getCacheDir(), "answer.wav");
        String id = "ans" + System.currentTimeMillis();
        int r = tts.synthesizeToFile(text, new Bundle(), f, id);
        if (r != TextToSpeech.SUCCESS) log("음성 만들기 실패");
    }

    private void play(java.io.File f) {
        AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        boolean ok = route(am);
        if (!ok) { log("워치 소리 통로(SCO)를 못 찾음 → 소리 생략"); return; }
        main.postDelayed(() -> {
            try {
                android.media.MediaPlayer mp = new android.media.MediaPlayer();
                mp.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
                mp.setDataSource(f.getAbsolutePath());
                mp.setOnCompletionListener(m -> { m.release(); main.postDelayed(this::unroute, 300); });
                mp.setOnErrorListener((m, w, x) -> { m.release(); unroute(); return true; });
                mp.prepare();
                mp.start();
                log("답을 워치 스피커로 읽는 중");
            } catch (Exception e) {
                log("재생 오류: " + e.getMessage());
                unroute();
            }
        }, 800);
    }

    private boolean route(AudioManager am) {
        String mac = app.getSharedPreferences("hub", Context.MODE_PRIVATE).getString("mac", "");
        try {
            savedMode = am.getMode();
            if (Build.VERSION.SDK_INT >= 31) {
                AudioDeviceInfo pick = null;
                for (AudioDeviceInfo d : am.getAvailableCommunicationDevices()) {
                    if (d.getType() != AudioDeviceInfo.TYPE_BLUETOOTH_SCO) continue;
                    if (mac.equalsIgnoreCase(d.getAddress())) pick = d;
                    else log("다른 블루투스 소리 장치 무시: " + d.getProductName());
                }
                if (pick == null) return false;
                log("소리 통로: " + pick.getProductName());
                am.setMode(AudioManager.MODE_IN_COMMUNICATION);
                routed = am.setCommunicationDevice(pick);
                if (!routed) am.setMode(savedMode);
                return routed;
            } else {
                if (!am.isBluetoothScoAvailableOffCall()) return false;
                am.setMode(AudioManager.MODE_IN_COMMUNICATION);
                am.startBluetoothSco();
                am.setBluetoothScoOn(true);
                routed = true;
                return true;
            }
        } catch (Exception e) {
            log("소리 통로 오류: " + e.getMessage());
            return false;
        }
    }

    private void unroute() {
        if (!routed) return;
        routed = false;
        AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice();
            else { am.setBluetoothScoOn(false); am.stopBluetoothSco(); }
            am.setMode(savedMode == AudioManager.MODE_IN_COMMUNICATION ? AudioManager.MODE_NORMAL : savedMode);
        } catch (Exception ignored) {}
    }
}
