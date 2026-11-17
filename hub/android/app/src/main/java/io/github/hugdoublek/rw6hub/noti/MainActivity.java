package io.github.hugdoublek.rw6hub.noti;

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

import io.github.hugdoublek.rw6hub.wear.Permission;
import io.github.hugdoublek.rw6hub.wear.Node;

/** 설정 화면: 권한, 글자 크기, 대상 앱, 테스트 */
public class MainActivity extends Activity {

    private Prefs prefs;
    private TextView status, log, sizeLabel, diag, hist;
    private Button otherBtn;
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
        otherBtn = button("", v -> pickOtherApps());
        root.addView(otherBtn);
        refreshOtherLabel();
        root.addView(check("사진 함께 보내기", prefs.pictures(), prefs::setPictures));
        root.addView(check("사진이 있는 알림만 보내기 (글 알림은 워치 기본 팝업)", prefs.photoOnly(), prefs::setPhotoOnly));

        root.addView(text("워치에 알림이 오면"));
        root.addView(check("진동 울리기", prefs.vibrate(), prefs::setVibrate));
        root.addView(check("화면 깨우기", prefs.wake(), prefs::setWake));
        // 켜 두는 시간: 3~30초, 0.5초 간격 (막대 한 칸 = 0.5초)
        TextView wakeLabel = text("");
        root.addView(wakeLabel);
        SeekBar wakeBar = new SeekBar(this);
        wakeBar.setMax(54); // (30 - 3) / 0.5
        wakeBar.setProgress((prefs.wakeTenths() - 30) / 5);
        Runnable showWake = () -> wakeLabel.setText(String.format(java.util.Locale.US,
                "알림을 보여 주는 시간: %.1f초 (지나면 닫혀 메인 화면으로)\n· 화면 깨우기가 켜져 있으면 이 시간 동안 화면을 켜 둠\n· 화면을 만지면 그때부터 15초, 눌러서 읽는 동안은 최대 3분", prefs.wakeTenths() / 10f));
        showWake.run();
        wakeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int v, boolean u) { prefs.setWakeTenths(30 + v * 5); showWake.run(); }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        root.addView(wakeBar);
        root.addView(check("폰이 방해금지면 진동·화면 켜기 안 함", prefs.followDnd(), prefs::setFollowDnd));
        root.addView(check("워치 목록에서 같은 대화방 알림 묶어 보기 (누르면 그 방 메시지 전부)", prefs.group(), prefs::setGroup));
        // 워치 알림 센터에 올린 줄은 누르면 '무시'만 있고 앱이 열리지 않음 (샤오미 개발 도구의 한계)
        root.addView(check("워치 알림 목록에 'OO 알림 - N건' 한 줄 표시 (알림만, 눌러도 앱은 안 열림)", prefs.watchList(), prefs::setWatchList));

        root.addView(section("4. 확인"));
        root.addView(button("워치로 테스트 알림 보내기", v -> sendTest()));
        root.addView(button("지금 상태 점검 (6시간마다 자동으로도 점검)", v -> {
            log.setText("점검 중…");
            HealthCheck.run(this, false, (text, problem) -> log.setText(text));
        }));
        log = text("");
        root.addView(log);

        root.addView(section("5. 최근 기록 (사진이 빠지면 여기 이유가 남아요)"));
        hist = text("");
        hist.setTextSize(12);
        hist.setTextIsSelectable(true);
        root.addView(hist);

        root.addView(section("6. 마지막 카톡 알림 분석"));
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
        HealthCheck.schedule(this);
        // 안드로이드 13 이상은 점검 알림을 띄우려면 허락이 필요
        // (한 번만 물음: 거절한 뒤 계속 물으면 화면이 깜빡이며 반복될 수 있음)
        if (android.os.Build.VERSION.SDK_INT >= 33 && !prefs.askedNotif()
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            prefs.setAskedNotif();
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 3);
        }
    }

    private void refreshStatus() {
        boolean listener = isListenerEnabled();
        if (listener && !NotiListener.connected) {
            // 앱 업데이트 뒤에는 시스템이 수신 서비스를 다시 붙여주지 않는 경우가 많아 직접 요청
            try { NotificationListenerService.requestRebind(new ComponentName(this, NotiListener.class)); } catch (Throwable ignored) { }
        }
        status.setText(listenerLine(listener) + "\n워치: 확인 중…" + verLine());
        log.setText("최근 전송: " + prefs.lastLog());
        diag.setText(prefs.diag());
        hist.setText(prefs.history());
        Wearable.getNodeApi(this).getConnectedNodes()
                .addOnSuccessListener(nodes -> {
                    node = (nodes == null || nodes.isEmpty()) ? null : nodes.get(0);
                    if (node == null) { setStatus(listener, "연결 안 됨 (Mi Manager 첫 화면에서 연결 확인)"); return; }
                    Wearable.getAuthApi(this).checkPermission(node.id, Permission.DEVICE_MANAGER)
                            .addOnSuccessListener(ok -> setStatus(listener, node.name + " / 워치 권한 " + (ok ? "허용됨" : "없음 ← 요청하세요")))
                            .addOnFailureListener(e -> setStatus(listener, node.name + " / 권한 확인 실패"));
                    Wearable.getNodeApi(this).isWearAppInstalled(node.id)
                            .addOnSuccessListener(inst -> { if (!inst) setStatus(listener, node.name + " / 워치 앱 미설치 ← .rpk 설치"); });
                })
                .addOnFailureListener(e -> setStatus(listener, "조회 실패: " + e.getMessage()));
    }

    private void setStatus(boolean listener, String watch) {
        runOnUiThread(() -> status.setText(listenerLine(listener) + "\n워치: " + watch + verLine()));
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
        String b64 = Renderer.toBase64Jpeg(Renderer.sample(prefs.fontSize()));
        WatchSender.send(this, b64, (ok, msg) -> {
            prefs.setLastLog(NotiListener.now() + " 테스트 → " + msg);
            log.setText((ok ? "✔ " : "✘ ") + msg);
        });
    }

    private void refreshPreview() {
        sizeLabel.setText("본문 " + prefs.fontSize() + "px (워치 화면 폭 432px 기준)");
        preview.setImageBitmap(Renderer.sample(prefs.fontSize()));
    }

    /** 설치된 앱 목록에서 알림을 보낼 앱을 직접 고른다 */
    /** 설치된 앱 목록에서 알림을 보낼 앱을 직접 고른다 (아이콘 표시, 선택한 앱은 위로) */
    private void pickOtherApps() {
        final android.content.pm.PackageManager pm = getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        java.util.List<android.content.pm.ResolveInfo> found = pm.queryIntentActivities(launcher, 0);
        java.util.Set<String> skip = new java.util.HashSet<>(java.util.Arrays.asList(Prefs.SMS));
        skip.add(Prefs.KAKAO);
        skip.add(getPackageName());
        final java.util.Set<String> chosen = new java.util.HashSet<>(prefs.otherApps());

        // 패키지별로 한 번만 (여러 런처 액티비티 중복 제거)
        java.util.LinkedHashMap<String, android.content.pm.ResolveInfo> byPkg = new java.util.LinkedHashMap<>();
        for (android.content.pm.ResolveInfo ri : found) {
            String pkg = ri.activityInfo.packageName;
            if (skip.contains(pkg) || byPkg.containsKey(pkg)) continue;
            byPkg.put(pkg, ri);
        }
        final java.util.List<Row> rows = new java.util.ArrayList<>();
        for (android.content.pm.ResolveInfo ri : byPkg.values()) {
            Row r = new Row();
            r.pkg = ri.activityInfo.packageName;
            r.label = ri.loadLabel(pm).toString();
            r.ri = ri;
            r.checked = chosen.contains(r.pkg);
            rows.add(r);
        }
        sortRows(rows);

        final java.util.HashMap<String, android.graphics.drawable.Drawable> iconCache = new java.util.HashMap<>();
        final android.widget.BaseAdapter adapter = new android.widget.BaseAdapter() {
            @Override public int getCount() { return rows.size(); }
            @Override public Object getItem(int p) { return rows.get(p); }
            @Override public long getItemId(int p) { return p; }
            @Override public android.view.View getView(int p, android.view.View cv, android.view.ViewGroup parent) {
                android.widget.LinearLayout row;
                if (cv instanceof android.widget.LinearLayout) row = (android.widget.LinearLayout) cv;
                else {
                    row = new android.widget.LinearLayout(MainActivity.this);
                    row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
                    row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                    int pd = Math.round(12 * getResources().getDisplayMetrics().density);
                    row.setPadding(pd, pd, pd, pd);
                    android.widget.ImageView icon = new android.widget.ImageView(MainActivity.this);
                    int sz = Math.round(32 * getResources().getDisplayMetrics().density);
                    android.widget.LinearLayout.LayoutParams il = new android.widget.LinearLayout.LayoutParams(sz, sz);
                    il.rightMargin = pd;
                    row.addView(icon, il);
                    android.widget.TextView name = new android.widget.TextView(MainActivity.this);
                    name.setTextSize(16);
                    row.addView(name, new android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                    android.widget.CheckBox cb = new android.widget.CheckBox(MainActivity.this);
                    cb.setClickable(false);
                    cb.setFocusable(false);
                    row.addView(cb);
                    row.setTag(new android.view.View[]{icon, name, cb});
                }
                Row r = rows.get(p);
                android.view.View[] v = (android.view.View[]) row.getTag();
                android.graphics.drawable.Drawable d = iconCache.get(r.pkg);
                if (d == null) { try { d = r.ri.loadIcon(pm); } catch (Throwable t) { d = null; } iconCache.put(r.pkg, d); }
                ((android.widget.ImageView) v[0]).setImageDrawable(d);
                ((android.widget.TextView) v[1]).setText(r.label);
                ((android.widget.CheckBox) v[2]).setChecked(r.checked);
                return row;
            }
        };
        android.widget.ListView list = new android.widget.ListView(this);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, pos, id) -> {
            Row r = rows.get(pos);
            r.checked = !r.checked;
            adapter.notifyDataSetChanged();
        });
        new android.app.AlertDialog.Builder(this)
                .setTitle("알림을 보낼 앱 선택")
                .setView(list)
                .setPositiveButton("저장", (d, w) -> {
                    java.util.Set<String> out = new java.util.HashSet<>();
                    for (Row r : rows) if (r.checked) out.add(r.pkg);
                    prefs.setOtherApps(out);
                    refreshOtherLabel();
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private static final class Row {
        String pkg, label;
        android.content.pm.ResolveInfo ri;
        boolean checked;
    }

    /** 선택된 앱을 위로, 그 안에서는 이름순 */
    private static void sortRows(java.util.List<Row> rows) {
        java.util.Collections.sort(rows, (a, b) -> {
            if (a.checked != b.checked) return a.checked ? -1 : 1;
            return a.label.compareToIgnoreCase(b.label);
        });
    }

    private void refreshOtherLabel() {
        int n = prefs.otherApps().size();
        otherBtn.setText("그 밖의 앱 선택 (" + (n == 0 ? "없음" : n + "개") + ")");
    }

    /** 폰 앱·워치 앱 버전. 서로 다르면 알려 줌 (두 앱은 같은 버전이어야 함) */
    private String verLine() {
        String mine = "?";
        try { mine = Wearable.PHONE_VERSION; } catch (Throwable ignored) { }
        String w = prefs.watchVer();
        if (w.isEmpty()) return "\n버전: 폰 " + mine + " / 워치 (알림을 한 번 보내면 표시)";
        return "\n버전: 폰 " + mine + " / 워치 " + w + (w.equals(mine) ? "" : " ← 다릅니다. 워치 앱도 " + mine + "(으)로 설치하세요");
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
        io.github.hugdoublek.rw6hub.Ui.spaced(btn);
        return btn;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
