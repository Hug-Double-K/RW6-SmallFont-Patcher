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
                + "'말하세요'가 뜨면 폰은 멀리 두거나 마이크를 가리고 워치에 대고 4초 동안 말해 보세요.\n"
                + "그다음 녹음한 소리가 워치에서 들리는지 확인합니다.");
        root.addView(desc);

        Button go = new Button(this);
        go.setAllCaps(false);
        go.setText("워치 마이크·스피커 시험 (4초 녹음 → 재생)");
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

    private void test() {
        AudioDeviceInfo bt = null;
        try {
            say("1) 통화용 블루투스 장치 찾기 (안드로이드 SDK " + Build.VERSION.SDK_INT + ")");
            if (Build.VERSION.SDK_INT >= 31) {
                for (AudioDeviceInfo di : am.getAvailableCommunicationDevices()) {
                    say("   - " + name(di));
                    if (di.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || di.getType() == AudioDeviceInfo.TYPE_BLE_HEADSET) bt = di;
                }
            } else {
                for (AudioDeviceInfo di : am.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
                    say("   - " + name(di));
                    if (di.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) bt = di;
                }
            }
            if (bt == null) say("   ⚠ 블루투스 통화 장치가 안 보임 → 폰 블루투스 설정에서 워치의 '통화' 스위치가 켜져 있는지 확인");

            am.setMode(AudioManager.MODE_IN_COMMUNICATION);
            boolean routed = false;
            if (Build.VERSION.SDK_INT >= 31) {
                if (bt != null) {
                    boolean ok = am.setCommunicationDevice(bt);
                    say("   워치로 소리 길 열기 요청: " + (ok ? "받아들여짐" : "거절됨"));
                    for (int i = 0; i < 25 && !routed; i++) {
                        Thread.sleep(200);
                        AudioDeviceInfo cur = am.getCommunicationDevice();
                        routed = cur != null && cur.getId() == bt.getId();
                    }
                }
            } else {
                am.startBluetoothSco();
                am.setBluetoothScoOn(true);
                Thread.sleep(2500);
                routed = am.isBluetoothScoOn();
            }
            say("   워치 소리 길: " + (routed ? "열림" : "안 열림"));

            int rate = 16000;
            int min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            AudioRecord rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, rate));
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) { say("   ✘ 녹음 준비 실패"); return; }
            short[] pcm = new short[rate * 4];
            rec.startRecording();
            say("2) 지금 워치에 대고 말하세요 (4초)…");
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
            say("   녹음에 쓰인 마이크: " + name(mic));
            say("   소리 크기: 최대 " + peak + " / 평균 " + (got > 0 ? sum / got : 0) + " (최대가 1000 이상이면 소리가 들어온 것)");
            if (got == 0) { say("   ✘ 녹음된 소리가 없음"); return; }

            say("3) 방금 녹음을 재생합니다. 워치에서 소리가 나는지 들어 보세요…");
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
            tr.write(pcm, 0, got);
            tr.play();
            Thread.sleep(400);
            AudioDeviceInfo out = tr.getRoutedDevice();
            Thread.sleep(got * 1000L / rate + 300);
            tr.release();
            say("   재생에 쓰인 스피커: " + name(out));
        } catch (Throwable t) {
            say("오류: " + t);
        } finally {
            try {
                if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice();
                else { am.setBluetoothScoOn(false); am.stopBluetoothSco(); }
                am.setMode(AudioManager.MODE_NORMAL);
            } catch (Throwable ignored) { }
            running = false;
            say("끝. '결과 복사'를 눌러 대화창에 붙여 넣어 주세요.");
        }
    }
}
