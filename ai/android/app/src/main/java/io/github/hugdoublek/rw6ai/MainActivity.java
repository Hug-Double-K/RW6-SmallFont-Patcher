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
                + "'말하세요'가 뜰 때마다(여러 번) 폰 마이크를 가리고 워치에 대고 3초씩 말해 보세요.\n"
                + "워치 마이크로 녹음되면 그 소리를 워치에서 다시 들려줍니다. 워치 화면에 뭔가 뜨면 그것도 알려 주세요.");
        root.addView(desc);

        Button go = new Button(this);
        go.setAllCaps(false);
        go.setText("워치 마이크·스피커 시험 (방식 3가지, 약 30초)");
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

    private void start() {
        if (running) return;
        List<String> need = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.BLUETOOTH_CONNECT);
        if (!need.isEmpty()) {
            requestPermissions(need.toArray(new String[0]), 1);
            say("권한을 허용한 뒤 버튼을 다시 눌러 주세요.");
            return;
        }
        running = true;
        text.setLength(0);
        new Thread(this::test).start();
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
