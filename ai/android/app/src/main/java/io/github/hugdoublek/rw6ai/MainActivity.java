package io.github.hugdoublek.rw6ai;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.xiaomi.xms.wearable.Wearable;
import com.xiaomi.xms.wearable.auth.Permission;
import com.xiaomi.xms.wearable.node.Node;

import java.util.ArrayList;
import java.util.List;

/** 설정 화면: API 키, 권한, 대기 켜기/끄기, 기록 */
public class MainActivity extends Activity {
    private SharedPreferences sp;
    private TextView status, log;
    private EditText keyBox, modelBox;
    private Button toggle;
    private Node node;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = getSharedPreferences("rw6ai", MODE_PRIVATE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(p, p, p, p);

        TextView title = new TextView(this);
        title.setText("RW6 AI 0.6");
        title.setTextSize(24);
        root.addView(title);
        status = label(root, "상태 확인 중…");

        label(root, "\n처음 한 번만");
        button(root, "① 워치 권한 요청 (Mi Fitness 화면에서 허용)", this::requestWatchPermission);
        button(root, "② 배터리 최적화 제외", this::askBattery);

        label(root, "\nGemini API 키 (이 폰에만 저장됩니다)");
        keyBox = new EditText(this);
        keyBox.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        keyBox.setSingleLine(true);
        keyBox.setText(sp.getString("key", ""));
        root.addView(keyBox);
        label(root, "모델 이름 (모르면 그대로 두세요)");
        modelBox = new EditText(this);
        modelBox.setSingleLine(true);
        modelBox.setText(sp.getString("model", AiService.DEFAULT_MODEL));
        root.addView(modelBox);

        toggle = button(root, "", this::toggleService);
        button(root, "폰에서 바로 물어보기 (시험)", () -> {
            save();
            if (!AiService.alive) { toast("먼저 'AI 대기 켜기'를 눌러 주세요."); return; }
            startService(new Intent(this, AiService.class).setAction(AiService.ACTION_ASK));
        });
        button(root, "기록 복사", () -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("rw6ai", AiService.logText()));
        });

        label(root, "\n기록");
        log = label(root, "");
        log.setTextIsSelectable(true);

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);
    }

    private TextView label(LinearLayout root, String s) {
        TextView t = new TextView(this);
        t.setText(s);
        root.addView(t);
        return t;
    }

    private Button button(LinearLayout root, String s, Runnable r) {
        Button bt = new Button(this);
        bt.setAllCaps(false);
        bt.setText(s);
        bt.setOnClickListener(v -> r.run());
        root.addView(bt);
        return bt;
    }

    private void toast(String s) { android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_LONG).show(); }

    private void save() {
        sp.edit().putString("key", keyBox.getText().toString().trim())
                .putString("model", modelBox.getText().toString().trim()).apply();
    }

    @Override
    protected void onResume() {
        super.onResume();
        AiService.ui = () -> runOnUiThread(this::refresh);
        refresh();
        refreshWatch();
    }

    @Override
    protected void onPause() {
        save();
        AiService.ui = null;
        super.onPause();
    }

    private void refresh() {
        toggle.setText(AiService.alive ? "AI 대기 끄기 (지금: 켜짐)" : "③ AI 대기 켜기 (지금: 꺼짐)");
        log.setText(AiService.logText());
    }

    private void toggleService() {
        save();
        if (AiService.alive) {
            stopService(new Intent(this, AiService.class));
            toggle.postDelayed(this::refresh, 300);
            return;
        }
        List<String> need = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.BLUETOOTH_CONNECT);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED)
            need.add("android.permission.POST_NOTIFICATIONS");
        if (!need.isEmpty()) {
            requestPermissions(need.toArray(new String[0]), 1);
            toast("권한을 허용한 뒤 다시 눌러 주세요.");
            return;
        }
        if (keyBox.getText().toString().trim().isEmpty()) toast("Gemini API 키를 넣어야 답을 받을 수 있어요.");
        startForegroundService(new Intent(this, AiService.class));
        toggle.postDelayed(this::refresh, 500);
    }

    private void refreshWatch() {
        status.setText("워치: 확인 중…");
        Wearable.getNodeApi(this).getConnectedNodes()
                .addOnSuccessListener(nodes -> {
                    node = (nodes == null || nodes.isEmpty()) ? null : nodes.get(0);
                    if (node == null) { status.setText("워치: 연결 안 됨 (Mi Fitness 연결 확인)"); return; }
                    Wearable.getAuthApi(this).checkPermission(node.id, Permission.DEVICE_MANAGER)
                            .addOnSuccessListener(ok -> status.setText("워치: " + node.name + " / 워치 권한 " + (ok ? "허용됨" : "없음 ← ①을 누르세요")))
                            .addOnFailureListener(e -> status.setText("워치: " + node.name + " / 권한 확인 실패"));
                    Wearable.getNodeApi(this).isWearAppInstalled(node.id)
                            .addOnSuccessListener(inst -> { if (!inst) status.setText("워치: " + node.name + " / 워치 앱 미설치 ← RW6AI rpk 설치"); });
                })
                .addOnFailureListener(e -> status.setText("워치: 조회 실패 (" + e.getMessage() + ")"));
    }

    private void requestWatchPermission() {
        if (node == null) { toast("워치가 연결되어 있지 않아요."); return; }
        Wearable.getAuthApi(this).requestPermission(node.id, Permission.DEVICE_MANAGER, Permission.NOTIFY)
                .addOnSuccessListener(perms -> { toast("워치 권한 허용됨"); refreshWatch(); })
                .addOnFailureListener(e -> toast("워치 권한 요청 실패: " + e.getMessage()));
    }

    private void askBattery() {
        PowerManager pm = getSystemService(PowerManager.class);
        if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) { toast("이미 제외되어 있어요."); return; }
        startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
    }
}
