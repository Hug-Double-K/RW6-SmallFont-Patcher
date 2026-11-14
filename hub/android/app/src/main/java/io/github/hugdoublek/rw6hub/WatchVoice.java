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
                    @Override public void onDone(String id) { main.postDelayed(WatchVoice.this::unroute, 400); }
                    @Override public void onError(String id) { main.post(WatchVoice.this::unroute); }
                });
                String p = pending; pending = null;
                if (p != null) speakMain(p);
            }));
            return;
        }
        if (!ttsReady) { pending = text; return; }
        boolean ok = route(am);
        log(ok ? "답을 워치 스피커로 읽는 중" : "워치 소리 통로 없음 → 폰에서 읽음");
        Bundle b = new Bundle();
        if (ok) {
            tts.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
            b.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_VOICE_CALL);
        } else {
            tts.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
        }
        // SCO가 열리는 데 조금 걸림
        main.postDelayed(() -> tts.speak(text, TextToSpeech.QUEUE_FLUSH, b, "ans" + System.currentTimeMillis()), ok ? 700 : 0);
    }

    private boolean route(AudioManager am) {
        String mac = app.getSharedPreferences("hub", Context.MODE_PRIVATE).getString("mac", "");
        try {
            savedMode = am.getMode();
            if (Build.VERSION.SDK_INT >= 31) {
                AudioDeviceInfo pick = null;
                for (AudioDeviceInfo d : am.getAvailableCommunicationDevices()) {
                    if (d.getType() != AudioDeviceInfo.TYPE_BLUETOOTH_SCO) continue;
                    if (pick == null || mac.equalsIgnoreCase(d.getAddress())) pick = d;
                }
                if (pick == null) return false;
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
