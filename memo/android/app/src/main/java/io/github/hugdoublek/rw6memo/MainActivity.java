package io.github.hugdoublek.rw6memo;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import com.xiaomi.xms.wearable.Wearable;
import com.xiaomi.xms.wearable.auth.Permission;
import com.xiaomi.xms.wearable.node.Node;

/** 설정 화면: 권한, 글자 크기, 보낸 메모 관리 */
public class MainActivity extends Activity {

    private Memo.Store store;
    private TextView status, log, sizeLabel;
    private ImageView preview;
    private LinearLayout listBox;
    private Node node;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        store = new Memo.Store(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        root.setPadding(p, p, p, p);

        root.addView(text("RW6 메모", 24));
        status = text("워치 확인 중…", 15);
        root.addView(status);
        root.addView(text("컬러노트에서 메모를 열고 메뉴 → 보내기(공유) → \"워치로 보내기\"를 누르면 워치에 저장돼요. 같은 제목으로 다시 보내면 최신 내용으로 바뀌어요.", 14));

        root.addView(section("1. 처음 한 번만"));
        root.addView(button("워치 권한 요청 (Mi Fitness 화면에서 허용)", v -> requestWatchPermission()));
        root.addView(button("배터리 최적화 제외", v -> askBattery()));

        root.addView(section("2. 글자 크기"));
        sizeLabel = text("", 15);
        root.addView(sizeLabel);
        SeekBar bar = new SeekBar(this);
        bar.setMax(40 - 14);
        bar.setProgress(store.fontSize() - 14);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int v, boolean u) { store.setFontSize(v + 14); refreshPreview(); }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        root.addView(bar);
        preview = new ImageView(this);
        preview.setAdjustViewBounds(true);
        preview.setBackgroundColor(Color.BLACK);
        root.addView(preview, new LinearLayout.LayoutParams(dp(216), LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(button("글자 크기 바꾼 뒤: 보낸 메모 모두 다시 보내기", v -> resendAll()));

        root.addView(section("3. 보낸 메모 (눌러서 다시 보내기·워치에서 지우기)"));
        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(listBox);

        root.addView(section("4. 확인"));
        root.addView(button("워치로 샘플 메모 보내기", v -> send(MemoRenderer.sample(), false)));
        log = text("", 14);
        root.addView(log);

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);
        refreshPreview();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshList();
        log.setText("최근: " + store.lastLog());
        Wearable.getNodeApi(this).getConnectedNodes()
                .addOnSuccessListener(nodes -> {
                    node = (nodes == null || nodes.isEmpty()) ? null : nodes.get(0);
                    if (node == null) { status.setText("워치: 연결 안 됨 (Mi Fitness 연결 확인)"); return; }
                    Wearable.getAuthApi(this).checkPermission(node.id, Permission.DEVICE_MANAGER)
                            .addOnSuccessListener(ok -> status.setText("워치: " + node.name + " / 권한 " + (ok ? "허용됨" : "없음 ← 요청하세요")))
                            .addOnFailureListener(e -> status.setText("워치: " + node.name + " / 권한 확인 실패"));
                })
                .addOnFailureListener(e -> status.setText("워치 조회 실패: " + e.getMessage()));
    }

    private void refreshList() {
        listBox.removeAllViews();
        java.util.List<Memo> all = store.all();
        if (all.isEmpty()) { listBox.addView(text("아직 보낸 메모가 없어요.", 14)); return; }
        for (Memo m : all) {
            Button btn = button(m.title, v -> new AlertDialog.Builder(this)
                    .setTitle(m.title)
                    .setItems(new String[]{"다시 보내기", "워치에서 지우기"}, (d, which) -> {
                        if (which == 0) send(m, true);
                        else WatchSender.deleteMemo(this, m.id, (ok, msg) -> {
                            if (ok) { store.remove(m.id); refreshList(); }
                            log.setText((ok ? "✔ 지웠어요: " : "✘ ") + (ok ? m.title : msg));
                        });
                    }).show());
            btn.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            listBox.addView(btn);
        }
    }

    private void send(Memo m, boolean remember) {
        log.setText("보내는 중… " + m.title);
        Memo fresh = new Memo(m.id, m.title, m.body, System.currentTimeMillis());
        String b64 = MemoRenderer.toBase64Png(MemoRenderer.render(fresh, store.fontSize()));
        WatchSender.sendMemo(this, fresh, b64, (ok, msg) -> {
            if (ok && remember) store.put(fresh);
            store.setLastLog(m.title + " → " + msg);
            log.setText((ok ? "✔ " : "✘ ") + m.title + ": " + msg);
            refreshList();
        });
    }

    private void resendAll() {
        java.util.List<Memo> all = store.all();
        if (all.isEmpty()) { log.setText("보낸 메모가 없어요."); return; }
        resendNext(all, all.size() - 1);
    }

    private void resendNext(java.util.List<Memo> all, int i) {
        if (i < 0) { log.setText("✔ 모두 다시 보냈어요"); refreshList(); return; }
        Memo m = all.get(i);
        Memo fresh = new Memo(m.id, m.title, m.body, m.time);
        log.setText("다시 보내는 중… (" + (all.size() - i) + "/" + all.size() + ")");
        WatchSender.sendMemo(this, fresh, MemoRenderer.toBase64Png(MemoRenderer.render(fresh, store.fontSize())),
                (ok, msg) -> { if (ok) resendNext(all, i - 1); else log.setText("✘ " + m.title + ": " + msg); });
    }

    private void requestWatchPermission() {
        if (node == null) { log.setText("워치가 연결되어 있지 않아요."); return; }
        Wearable.getAuthApi(this).requestPermission(node.id, Permission.DEVICE_MANAGER)
                .addOnSuccessListener(x -> { log.setText("워치 권한 허용됨"); onResume(); })
                .addOnFailureListener(e -> log.setText("워치 권한 요청 실패: " + e.getMessage()));
    }

    private void askBattery() {
        PowerManager pm = getSystemService(PowerManager.class);
        if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) { log.setText("이미 제외되어 있어요."); return; }
        startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
    }

    private void refreshPreview() {
        sizeLabel.setText("본문 " + store.fontSize() + "px (워치 화면 폭 432px 기준)");
        preview.setImageBitmap(MemoRenderer.render(MemoRenderer.sample(), store.fontSize()));
    }

    private TextView section(String s) { TextView t = text(s, 18); t.setPadding(0, dp(20), 0, dp(6)); return t; }

    private TextView text(String s, int size) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        return t;
    }

    private Button button(String s, View.OnClickListener l) {
        Button btn = new Button(this);
        btn.setText(s);
        btn.setAllCaps(false);
        btn.setOnClickListener(l);
        return btn;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
