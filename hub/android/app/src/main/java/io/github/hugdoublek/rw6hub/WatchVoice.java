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

    private volatile int lastVgs = -1;
    private volatile long lastVgsAt = 0;
    private volatile boolean scoUp = false;   // 실제 SCO 소리 연결 상태 (방송으로 받음)

    WatchVoice(Context app) {
        this.app = app;
        android.content.BroadcastReceiver r = new android.content.BroadcastReceiver() {
            @Override public void onReceive(Context c, android.content.Intent i) {
                if ("android.media.VOLUME_CHANGED_ACTION".equals(i.getAction())) {
                    // 워치가 HFP로 알려 주는 스피커 음량(AT+VGS)이 폰의 '블루투스 통화 음량'(스트림 6)에 반영됨
                    if (i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1) == 6) {
                        int v = i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", -1);
                        log("워치 통화 음량 바뀜: " + v);
                        lastVgs = v; lastVgsAt = System.currentTimeMillis();
                    }
                    return;
                }
                if (android.bluetooth.BluetoothHeadset.ACTION_VENDOR_SPECIFIC_HEADSET_EVENT.equals(i.getAction())) {
                    Object[] args = (Object[]) i.getSerializableExtra(android.bluetooth.BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_ARGS);
                    log("워치 헤드셋 신호: " + i.getStringExtra(android.bluetooth.BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_CMD)
                            + " " + (args == null ? "" : java.util.Arrays.toString(args)));
                    return;
                }
                if (android.bluetooth.BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED.equals(i.getAction())) {
                    int hst = i.getIntExtra(android.bluetooth.BluetoothProfile.EXTRA_STATE, -1);
                    log("워치 헤드셋 소리 " + (hst == android.bluetooth.BluetoothHeadset.STATE_AUDIO_CONNECTED ? "연결됨" : hst == android.bluetooth.BluetoothHeadset.STATE_AUDIO_CONNECTING ? "연결 중" : "끊김"));
                    scoUp = hst == android.bluetooth.BluetoothHeadset.STATE_AUDIO_CONNECTED;
                    return;
                }
                int st = i.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1);
                boolean up = st == AudioManager.SCO_AUDIO_STATE_CONNECTED;
                if (up != scoUp) log("워치 소리 연결(SCO) " + (up ? "열림" : st == AudioManager.SCO_AUDIO_STATE_CONNECTING ? "여는 중" : "닫힘"));
                scoUp = up;
            }
        };
        android.content.IntentFilter f = new android.content.IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED);
        f.addAction(android.bluetooth.BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED);
        f.addAction("android.media.VOLUME_CHANGED_ACTION");
        f.addAction(android.bluetooth.BluetoothHeadset.ACTION_VENDOR_SPECIFIC_HEADSET_EVENT);
        f.addCategory(android.bluetooth.BluetoothHeadset.VENDOR_SPECIFIC_HEADSET_EVENT_COMPANY_ID_CATEGORY + ".76");
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(r, f, Context.RECEIVER_EXPORTED);
        else app.registerReceiver(r, f);
        try {
            android.bluetooth.BluetoothManager bm = (android.bluetooth.BluetoothManager) app.getSystemService(Context.BLUETOOTH_SERVICE);
            bm.getAdapter().getProfileProxy(app, new android.bluetooth.BluetoothProfile.ServiceListener() {
                @Override public void onServiceConnected(int p, android.bluetooth.BluetoothProfile proxy) { hs = (android.bluetooth.BluetoothHeadset) proxy; }
                @Override public void onServiceDisconnected(int p) { hs = null; }
            }, android.bluetooth.BluetoothProfile.HEADSET);
        } catch (Exception e) { /* 권한 없음 등 */ }
    }

    private volatile android.bluetooth.BluetoothHeadset hs;
    private android.bluetooth.BluetoothDevice vrDev;

    /** 헤드셋 '음성 인식' 모드로 SCO를 엶 (통화로 착각하지 않게) */
    private boolean startVr() {
        try {
            if (hs == null) { log("헤드셋 프로필 준비 안 됨"); return false; }
            String mac = app.getSharedPreferences("hub", Context.MODE_PRIVATE).getString("mac", "");
            for (android.bluetooth.BluetoothDevice d : hs.getConnectedDevices()) {
                if (mac.equalsIgnoreCase(d.getAddress())) {
                    boolean ok = hs.startVoiceRecognition(d);
                    log("음성 인식 모드로 워치 소리 요청: " + (ok ? "받아들여짐" : "거절됨"));
                    if (ok) vrDev = d;
                    return ok;
                }
            }
            log("워치 헤드셋 연결이 없음");
        } catch (Exception e) { log("음성 인식 모드 오류: " + e.getMessage()); }
        return false;
    }

    private void stopVr() {
        try { if (hs != null && vrDev != null) hs.stopVoiceRecognition(vrDev); } catch (Exception ignored) {}
        vrDev = null;
    }

    private void log(String s) { WatchLink.get(app).log("[AI] " + s); }

    void speak(String text) { speak(text, 0); }

    /** 워치는 '대화 끝'을 받은 뒤 소리 통로가 닫힐 때 화면을 닫는 듯 → 정한 시각까지 통로를 열어 둠 */
    private volatile long holdUntil = 0;
    void speak(String text, long holdUntilMs) { holdUntil = holdUntilMs; main.post(() -> speakMain(text)); }

    /** 질문이 시작될 때(워치가 HFP를 여는 순간) 미리 워치 소리 통로를 열어 둠 */
    void prepare() {
        main.post(() -> {
            AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            int m = am.getMode();
            if (m == AudioManager.MODE_IN_CALL || m == AudioManager.MODE_RINGTONE) return;
            if (tts == null) speakMain(null);   // TTS 미리 준비
            main.removeCallbacks(giveUp);
            main.postDelayed(giveUp, 40_000);
        });
    }

    /** 폰이 무음·진동이거나 방해 금지 중인지 */
    private boolean silentNow(AudioManager am) {
        if (am.getRingerMode() != AudioManager.RINGER_MODE_NORMAL) return true;
        try {
            android.app.NotificationManager nm = (android.app.NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
            return nm != null && nm.getCurrentInterruptionFilter() > android.app.NotificationManager.INTERRUPTION_FILTER_ALL;
        } catch (Exception e) { return false; }
    }

    private final Runnable giveUp = this::unroute;

    /** 워치 통로가 지금 살아 있는지 */
    private boolean onWatch(AudioManager am) {
        if (!routed) return false;
        if (Build.VERSION.SDK_INT < 31) return am.isBluetoothScoOn() || scoUp;
        AudioDeviceInfo d = am.getCommunicationDevice();
        return scoUp || (d != null && d.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO);
    }

    private String state(AudioManager am) {
        String dev = "-";
        if (Build.VERSION.SDK_INT >= 31) {
            AudioDeviceInfo d = am.getCommunicationDevice();
            if (d != null) dev = d.getType() + "/" + d.getProductName();
        }
        return "mode=" + am.getMode() + " 통로=" + dev + " SCO=" + (scoUp ? "열림" : "닫힘") + " routed=" + routed;
    }

    private void speakMain(String text) {
        AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        int m = am.getMode();
        if (m == AudioManager.MODE_IN_CALL || m == AudioManager.MODE_RINGTONE) { log("통화 중이라 소리 생략"); return; }
        if (tts == null) {
            pending = text;   // null이면 준비만
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
        if (text == null) return;
        if (!ttsReady) { pending = text; return; }
        // TTS 엔진이 오디오 설정을 무시하고 폰 스피커로 내는 경우가 있어, 파일로 만든 뒤 직접 '통화 음성'으로 재생
        java.io.File f = new java.io.File(app.getCacheDir(), "answer.wav");
        String id = "ans" + System.currentTimeMillis();
        int r = tts.synthesizeToFile(text, new Bundle(), f, id);
        if (r != TextToSpeech.SUCCESS) log("음성 만들기 실패");
    }

    /** Mi Fitness 방식(2026-11-15 HCI 기록): 폰이 +BVRA:1(음성 인식 시작)을 보내면 폰이 SCO를 열고, 거기로 답을 재생한 뒤 +BVRA:0.
     *  통화 모드(MODE_IN_COMMUNICATION)는 쓰지 않음 — 그러면 워치가 전화로 착각해 SCO를 닫음. */
    private void play(java.io.File f) {
        AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        if (!startVr()) { log("워치 소리 통로 요청 실패 → 소리 생략"); return; }
        waitSco(am, f, 0);
    }

    private boolean vrAudio() {
        try { if (hs != null && vrDev != null && hs.isAudioConnected(vrDev)) return true; } catch (Exception ignored) {}
        return scoUp;
    }

    /** SCO가 열릴 때까지 최대 4초 기다렸다가 재생 */
    private void waitSco(AudioManager am, java.io.File f, int tries) {
        if (vrAudio()) { log("워치 소리 통로 열림"); start(am, f); return; }
        if (tries >= 20) {
            log("워치 소리 통로(SCO)가 안 열림 → 소리 생략 (폰에서 안 냄)");
            unroute();
            return;
        }
        main.postDelayed(() -> waitSco(am, f, tries + 1), 200);
    }

    private void start(AudioManager am, java.io.File f) {
        main.postDelayed(() -> {
            if (!vrAudio()) { log("워치 소리 통로가 끊김 → 소리 생략"); unroute(); return; }
            try {
                AudioDeviceInfo sco = null;
                for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS))
                    if (d.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) sco = d;
                android.media.MediaPlayer mp = new android.media.MediaPlayer();
                mp.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
                if (sco != null) mp.setPreferredDevice(sco);
                else log("SCO 출력 장치가 목록에 없음 → 기본 경로로 재생 시도");
                mp.setDataSource(f.getAbsolutePath());
                mp.setOnCompletionListener(m -> {
                    m.release();
                    long wait = Math.max(300, holdUntil - System.currentTimeMillis());
                    if (wait > 300) log(String.format(java.util.Locale.US, "다 읽음 → %.1f초 뒤 닫기", wait / 1000f));
                    main.postDelayed(this::unroute, wait);
                });
                Runnable watch = new Runnable() { public void run() {
                    try { if (!mp.isPlaying()) return; } catch (Exception e) { return; }
                    if (!vrAudio()) { log("재생 중 워치 통로 끊김 → 멈춤"); try { mp.stop(); mp.release(); } catch (Exception ignored) {} unroute(); return; }
                    main.postDelayed(this, 150);
                }};
                main.postDelayed(watch, 150);
                mp.setOnErrorListener((m, w, x) -> { m.release(); unroute(); return true; });
                mp.prepare();
                mp.start();
                log("답을 워치 스피커로 읽는 중" + (sco != null ? " (" + sco.getProductName() + ")" : ""));
            } catch (Exception e) {
                log("재생 오류: " + e.getMessage());
                unroute();
            }
        }, 300);
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
                log("워치 통로 요청: " + (routed ? "받아들여짐" : "거절됨"));
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
        stopVr();
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
