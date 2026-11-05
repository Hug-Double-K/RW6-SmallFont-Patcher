package io.github.hugdoublek.rw6noti;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import com.xiaomi.xms.wearable.Wearable;
import com.xiaomi.xms.wearable.auth.Permission;
import com.xiaomi.xms.wearable.node.Node;

/** 설정 화면: 권한, 글자 크기, 대상 앱, 테스트 */
public class MainActivity extends Activity {

    private Prefs prefs;
    private TextView status, log, sizeLabel, diag;
    private ImageView preview;
    private Node node;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        root.setPadding(p, p, p, p);

        root.addView(title("RW6 알림"));
        status = text("상태 확인 중…");
        root.addView(status);

        root.addView(section("1. 처음 한 번만"));
        root.addView(button("알림 접근 허용 (RW6 알림 켜기)", v ->
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))));
        root.addView(button("워치 권한 요청 (Mi Fitness 화면에서 허용)", v -> requestWatchPermission()));
        root.addView(button("배터리 최적화 제외", v -> askBattery()));

        root.addView(section("2. 글자 크기"));
        sizeLabel = text("");
        root.addView(sizeLabel);
        SeekBar bar = new SeekBar(this);
        bar.setMax(40 - 14);
        bar.setProgress(prefs.fontSize() - 14);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int v, boolean u) {
                prefs.setFontSize(v + 14);
                refreshPreview();
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        root.addView(bar);
        preview = new ImageView(this);
        preview.setAdjustViewBounds(true);
        preview.setBackgroundColor(Color.BLACK);
        root.addView(preview, new LinearLayout.LayoutParams(dp(216), LinearLayout.LayoutParams.WRAP_CONTENT));

        root.addView(section("3. 보낼 알림"));
        root.addView(check("카카오톡", prefs.kakao(), prefs::setKakao));
        root.addView(check("문자 메시지", prefs.sms(), prefs::setSms));
        root.addView(check("그 밖의 모든 앱", prefs.others(), prefs::setOthers));
        root.addView(check("사진 함께 보내기", prefs.pictures(), prefs::setPictures));
        root.addView(check("사진이 있는 알림만 보내기 (글 알림은 워치 기본 팝업)", prefs.photoOnly(), prefs::setPhotoOnly));

        root.addView(section("4. 확인"));
        root.addView(button("워치로 테스트 알림 보내기", v -> sendTest()));
        log = text("");
        root.addView(log);

        root.addView(section("5. 마지막 카톡 알림 분석 (사진 문제 확인용)"));
        diag = text("");
        diag.setTextSize(12);
        diag.setTextIsSelectable(true);
        root.addView(diag);

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);
        refreshPreview();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void refreshStatus() {
        boolean listener = isListenerEnabled();
        if (listener && !NotiListener.connected) {
            // 앱 업데이트 뒤에는 시스템이 수신 서비스를 다시 붙여주지 않는 경우가 많아 직접 요청
            try { NotificationListenerService.requestRebind(new ComponentName(this, NotiListener.class)); } catch (Throwable ignored) { }
        }
        status.setText(listenerLine(listener) + "\n워치: 확인 중…");
        log.setText("최근 전송: " + prefs.lastLog());
        diag.setText(prefs.diag());
        Wearable.getNodeApi(this).getConnectedNodes()
                .addOnSuccessListener(nodes -> {
                    node = (nodes == null || nodes.isEmpty()) ? null : nodes.get(0);
                    if (node == null) { setStatus(listener, "연결 안 됨 (Mi Fitness 연결 확인)"); return; }
                    Wearable.getAuthApi(this).checkPermission(node.id, Permission.DEVICE_MANAGER)
                            .addOnSuccessListener(ok -> setStatus(listener, node.name + " / 워치 권한 " + (ok ? "허용됨" : "없음 ← 요청하세요")))
                            .addOnFailureListener(e -> setStatus(listener, node.name + " / 권한 확인 실패"));
                    Wearable.getNodeApi(this).isWearAppInstalled(node.id)
                            .addOnSuccessListener(inst -> { if (!inst) setStatus(listener, node.name + " / 워치 앱 미설치 ← .rpk 설치"); });
                })
                .addOnFailureListener(e -> setStatus(listener, "조회 실패: " + e.getMessage()));
    }

    private void setStatus(boolean listener, String watch) {
        runOnUiThread(() -> status.setText(listenerLine(listener) + "\n워치: " + watch));
    }

    private void requestWatchPermission() {
        if (node == null) { log.setText("워치가 연결되어 있지 않아요."); return; }
        Wearable.getAuthApi(this).requestPermission(node.id, Permission.DEVICE_MANAGER, Permission.NOTIFY)
                .addOnSuccessListener(perms -> { log.setText("워치 권한 허용됨"); refreshStatus(); })
                .addOnFailureListener(e -> log.setText("워치 권한 요청 실패: " + e.getMessage()));
    }

    private void askBattery() {
        PowerManager pm = getSystemService(PowerManager.class);
        if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) { log.setText("이미 제외되어 있어요."); return; }
        startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
    }

    private void sendTest() {
        log.setText("전송 중…");
        String b64 = Renderer.toBase64Png(Renderer.sample(prefs.fontSize()));
        WatchSender.send(this, b64, (ok, msg) -> {
            prefs.setLastLog(NotiListener.now() + " 테스트 → " + msg);
            log.setText((ok ? "✔ " : "✘ ") + msg);
        });
    }

    private void refreshPreview() {
        sizeLabel.setText("본문 " + prefs.fontSize() + "px (워치 화면 폭 432px 기준)");
        preview.setImageBitmap(Renderer.sample(prefs.fontSize()));
    }

    private String listenerLine(boolean enabled) {
        if (!enabled) return "알림 접근: 꺼짐 ← 먼저 허용하세요";
        return "알림 접근: 허용됨 / 수신 서비스: " + (NotiListener.connected ? "연결됨" : "끊김 ← 알림 접근을 껐다가 다시 켜거나 폰 재시작");
    }

    private boolean isListenerEnabled() {
        String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return flat != null && flat.contains(new ComponentName(this, NotiListener.class).flattenToString());
    }

    // ---- 간단한 화면 부품 ----
    interface Setter { void set(boolean v); }

    private CheckBox check(String label, boolean value, Setter s) {
        CheckBox c = new CheckBox(this);
        c.setText(label);
        c.setChecked(value);
        c.setOnCheckedChangeListener((v, on) -> s.set(on));
        return c;
    }

    private TextView title(String s) {
        TextView t = text(s);
        t.setTextSize(24);
        t.setPadding(0, 0, 0, dp(8));
        return t;
    }

    private TextView section(String s) {
        TextView t = text(s);
        t.setTextSize(18);
        t.setPadding(0, dp(20), 0, dp(6));
        return t;
    }

    private TextView text(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(15);
        return t;
    }

    private Button button(String s, View.OnClickListener l) {
        Button btn = new Button(this);
        btn.setText(s);
        btn.setAllCaps(false);
        btn.setGravity(Gravity.CENTER);
        btn.setOnClickListener(l);
        return btn;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
