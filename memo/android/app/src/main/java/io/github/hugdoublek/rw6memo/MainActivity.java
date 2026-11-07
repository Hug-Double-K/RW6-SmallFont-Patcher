package io.github.hugdoublek.rw6memo;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import com.xiaomi.xms.wearable.Wearable;
import com.xiaomi.xms.wearable.auth.Permission;
import com.xiaomi.xms.wearable.node.Node;

import org.json.JSONObject;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 설정 화면: 권한, 글자 크기, 컬러노트 백업 한꺼번에 보내기 */
public class MainActivity extends Activity {
    private static final int PICK_BACKUP = 7;

    private Memo.Store store;
    private TextView status, log, sizeLabel;
    private ImageView preview;
    private Node node;
    private boolean busy = false;

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

        root.addView(section("1. 처음 한 번만"));
        root.addView(button("워치 권한 요청 (Mi Fitness 화면에서 허용)", v -> requestWatchPermission()));
        root.addView(button("배터리 최적화 제외", v -> askBattery()));

        root.addView(section("2. 컬러노트 메모 한꺼번에 보내기"));
        root.addView(text("컬러노트 → 더보기 → 설정 → 백업에서 백업 파일을 기기(다운로드 폴더 등)에 저장한 뒤, 아래 버튼으로 그 파일을 고르세요. 잠긴 메모는 빠지고, 워치의 메모는 백업 내용으로 모두 바뀝니다.", 14));
        root.addView(button("컬러노트 백업 파일 고르기 → 워치로 모두 보내기", v -> pickBackup()));
        root.addView(button("백업 비밀번호 바꾸기 (기본 0000)", v -> askPassword(null)));

        root.addView(section("3. 메모 하나만 보내기"));
        root.addView(text("컬러노트에서 메모를 열고 메뉴 → 보내기(공유) → \"워치로 보내기\". 같은 제목이면 덮어써요.", 14));

        root.addView(section("4. 글자 크기"));
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
        root.addView(text("(대략적인 미리보기 — 워치에서는 워치 글꼴로 그려져요)", 12));
        root.addView(button("이 글자 크기를 워치에 적용", v -> WatchSender.send(this, WatchSender.fontMessage(store.fontSize()),
                (ok, msg) -> say((ok ? "✔ 글자 크기 적용: " + store.fontSize() + "px" : "✘ " + msg)))));

        root.addView(section("5. 관리"));
        root.addView(button("워치로 샘플 메모 보내기", v -> WatchSender.sendOne(this, MemoRenderer.sample(), store.fontSize(),
                (ok, msg) -> say((ok ? "✔ " : "✘ ") + msg))));
        root.addView(button("워치의 메모 모두 지우기", v -> new AlertDialog.Builder(this)
                .setMessage("워치에 저장된 메모를 모두 지울까요? (폰의 컬러노트는 그대로예요)")
                .setPositiveButton("지우기", (d, w) -> WatchSender.send(this, WatchSender.clearMessage(),
                        (ok, msg) -> say(ok ? "✔ 워치 메모를 모두 지웠어요" : "✘ " + msg)))
                .setNegativeButton("취소", null).show()));
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
        if (!busy) log.setText("최근: " + store.lastLog());
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

    // ---------- 컬러노트 백업 ----------
    private Uri lastBackup;

    private void pickBackup() {
        if (busy) { say("보내는 중이에요. 끝날 때까지 기다려 주세요."); return; }
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, PICK_BACKUP);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == PICK_BACKUP && res == RESULT_OK && data != null && data.getData() != null) {
            lastBackup = data.getData();
            importBackup(lastBackup);
        }
    }

    private void importBackup(Uri uri) {
        say("백업 파일을 여는 중…");
        new Thread(() -> {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                List<String> recs = ColorNoteBackup.decryptRecords(in, store.password());
                List<Memo> memos = new ArrayList<>();
                int locked = 0, bad = 0;
                for (String r : recs) {
                    try {
                        JSONObject o = new JSONObject(r);
                        if (o.optInt("encrypted", 0) != 0) { locked++; continue; }
                        Memo m = Memo.fromColorNote(o);
                        if (m != null) memos.add(m);
                    } catch (Exception e) { bad++; }
                }
                Collections.sort(memos, (a, c) -> Long.compare(c.time, a.time));
                int lockedF = locked, badF = bad;
                runOnUiThread(() -> confirmSend(memos, lockedF, badF));
            } catch (Exception e) {
                runOnUiThread(() -> {
                    say("✘ " + e.getMessage());
                    if (String.valueOf(e.getMessage()).contains("비밀번호")) askPassword(uri);
                });
            }
        }).start();
    }

    private void confirmSend(List<Memo> memos, int locked, int bad) {
        String msg = "메모 " + memos.size() + "개를 찾았어요. [" + ColorNoteBackup.lastMethod + "]\n(잠긴 메모 " + locked + "개 제외" + (bad > 0 ? ", 읽지 못한 " + bad + "개" : "") + ")\n\n"
                + "워치의 메모를 지우고 이 메모들로 바꿀까요? 블루투스로 보내서 몇 분 걸릴 수 있어요.";
        new AlertDialog.Builder(this)
                .setTitle("컬러노트 백업")
                .setMessage(msg)
                .setPositiveButton("보내기", (d, w) -> sendAll(memos))
                .setNegativeButton("취소", null)
                .show();
    }

    private void sendAll(List<Memo> memos) {
        List<byte[]> msgs = new ArrayList<>();
        msgs.add(WatchSender.clearMessage());
        msgs.add(WatchSender.fontMessage(store.fontSize()));
        for (Memo m : memos) msgs.addAll(WatchSender.memoMessages(m));
        busy = true;
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WatchSender.send(this, msgs,
                (done, total) -> say("보내는 중… " + done + " / " + total + "\n(화면을 켜 두고 워치를 폰 가까이 두세요)"),
                (ok, res) -> {
                    busy = false;
                    getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    String line = ok ? "✔ 메모 " + memos.size() + "개를 워치로 보냈어요" : "✘ " + res + "\n(다시 시도하면 처음부터 다시 보내요)";
                    store.setLastLog(line);
                    say(line);
                });
    }

    private void askPassword(Uri retry) {
        EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        et.setHint("컬러노트 백업 비밀번호");
        new AlertDialog.Builder(this)
                .setTitle("백업 비밀번호")
                .setMessage("컬러노트에서 백업 비밀번호(마스터 비밀번호)를 정했다면 입력하세요. 정한 적 없으면 0000입니다.")
                .setView(et)
                .setPositiveButton("확인", (d, w) -> {
                    String pw = et.getText().toString();
                    store.setPassword(pw.isEmpty() ? "0000" : pw);
                    if (retry != null) importBackup(retry);
                    else say("비밀번호를 저장했어요.");
                })
                .setNegativeButton("취소", null)
                .show();
    }

    // ---------- 기타 ----------
    private void requestWatchPermission() {
        if (node == null) { say("워치가 연결되어 있지 않아요."); return; }
        Wearable.getAuthApi(this).requestPermission(node.id, Permission.DEVICE_MANAGER)
                .addOnSuccessListener(x -> { say("워치 권한 허용됨"); onResume(); })
                .addOnFailureListener(e -> say("워치 권한 요청 실패: " + e.getMessage()));
    }

    private void askBattery() {
        PowerManager pm = getSystemService(PowerManager.class);
        if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) { say("이미 제외되어 있어요."); return; }
        startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
    }

    private void refreshPreview() {
        sizeLabel.setText("본문 " + store.fontSize() + "px (워치 화면 폭 432px 기준)");
        preview.setImageBitmap(MemoRenderer.render(MemoRenderer.sample(), store.fontSize()));
    }

    private void say(String s) { log.setText(s); }

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
