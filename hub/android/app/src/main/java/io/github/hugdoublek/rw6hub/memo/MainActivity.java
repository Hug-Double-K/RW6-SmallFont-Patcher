package io.github.hugdoublek.rw6hub.memo;

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

import io.github.hugdoublek.rw6hub.wear.Permission;
import io.github.hugdoublek.rw6hub.wear.Node;

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
        root.addView(text("컬러노트 → 더보기 → 설정 → 백업에서 백업 파일을 기기(다운로드 폴더 등)에 저장한 뒤, 아래 버튼으로 그 파일을 고르세요. 파일을 고르면 \"최근 30개만 / 직접 고르기 / 전체\" 중에서 보낼 메모를 정할 수 있어요. 잠긴 메모는 빠져요. 일부만 보내면 워치에 있던 메모는 그대로 두고 날짜 순서에 맞춰 추가(같은 메모는 새 내용으로)되고, \"전체\"를 고르면 워치 메모가 백업 내용으로 통째로 바뀌어요.", 14));
        root.addView(button("컬러노트 백업 파일 고르기 → 워치로 보내기", v -> pickBackup()));
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

    /** 백업에서 무엇을 보낼지 고른다: 전체 / 최근 N개 / 직접 고르기 (memos는 최근순) */
    private void confirmSend(List<Memo> memos, int locked, int bad) {
        int n = Math.min(store.recentCount(), memos.size());
        String info = "메모 " + memos.size() + "개를 찾았어요. [" + ColorNoteBackup.lastMethod + "]\n(잠긴 메모 " + locked + "개 제외"
                + (bad > 0 ? ", 읽지 못한 " + bad + "개" : "") + ")\n어떤 메모를 보낼까요?\n· 최근 N개 / 직접 고르기: 워치 메모에 추가\n· 전체: 워치 메모를 통째로 바꿈";
        String[] items = {
                "최근 " + n + "개만",
                "최근 몇 개인지 정하기…",
                "직접 고르기…",
                "전체 " + memos.size() + "개"
        };
        TextView head = text(info, 14);
        head.setPadding(dp(24), dp(16), dp(24), 0);
        new AlertDialog.Builder(this)
                .setTitle("컬러노트 백업")
                .setCustomTitle(head)
                .setItems(items, (d, which) -> {
                    if (which == 0) startSend(new ArrayList<>(memos.subList(0, n)), false);
                    else if (which == 1) askRecent(memos, locked, bad);
                    else if (which == 2) pickMemos(memos);
                    else startSend(memos, true);
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void askRecent(List<Memo> memos, int locked, int bad) {
        EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setText(String.valueOf(store.recentCount()));
        et.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this)
                .setTitle("최근 몇 개를 보낼까요?")
                .setMessage("가장 최근에 수정한 메모부터 이 개수만큼 보내요. (다음에도 이 숫자를 기억해요)")
                .setView(et)
                .setPositiveButton("확인", (d, w) -> {
                    int v;
                    try { v = Integer.parseInt(et.getText().toString().trim()); } catch (Exception e) { v = 30; }
                    store.setRecentCount(Math.max(1, v));
                    confirmSend(memos, locked, bad);
                })
                .setNegativeButton("취소", null)
                .show();
    }

    /** 목록에서 체크해서 고르기 (최근순, 지난번에 고른 메모는 미리 체크) */
    private void pickMemos(List<Memo> memos) {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yy/MM/dd", java.util.Locale.KOREA);
        String[] labels = new String[memos.size()];
        boolean[] checked = new boolean[memos.size()];
        java.util.Set<String> before = store.picked();
        for (int i = 0; i < memos.size(); i++) {
            Memo m = memos.get(i);
            labels[i] = m.title + "  (" + f.format(new java.util.Date(m.time)) + ")";
            checked[i] = before.contains(m.id);
        }
        new AlertDialog.Builder(this)
                .setTitle("보낼 메모 고르기")
                .setMultiChoiceItems(labels, checked, (d, which, on) -> checked[which] = on)
                .setPositiveButton("보내기", (d, w) -> {
                    List<Memo> out = new ArrayList<>();
                    java.util.Set<String> ids = new java.util.HashSet<>();
                    for (int i = 0; i < memos.size(); i++) if (checked[i]) { out.add(memos.get(i)); ids.add(memos.get(i).id); }
                    if (out.isEmpty()) { say("고른 메모가 없어요."); return; }
                    store.setPicked(ids);
                    startSend(out, false);
                })
                .setNegativeButton("취소", null)
                .show();
    }

    /** replace = 워치 메모를 지우고 통째로 바꿈 / false = 추가만 */
    private void startSend(List<Memo> memos, boolean replace) {
        new AlertDialog.Builder(this)
                .setMessage("메모 " + memos.size() + "개를 워치로 보낼까요?\n" + (replace
                        ? "워치에 있던 메모는 지워지고 이 메모들로 바뀌어요."
                        : "워치에 있던 메모는 그대로 두고 날짜 순서에 맞춰 추가돼요. (같은 메모는 새 내용으로 바뀜)"))
                .setPositiveButton("보내기", (d, w) -> sendAll(memos, replace))
                .setNegativeButton("취소", null)
                .show();
    }

    private WatchSender.Plan lastPlan;

    private void sendAll(List<Memo> memos, boolean replace) {
        lastPlan = WatchSender.importPlan(memos, store.fontSize(), replace);
        sendPlan(0);
    }

    /** 처음부터 또는 끊긴 곳부터 보낸다 */
    private void sendPlan(int from) {
        WatchSender.Plan plan = lastPlan;
        busy = true;
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WatchSender.send(this, plan, from,
                (done, total) -> say("보내는 중… " + done + " / " + total + " 묶음 (메모 " + plan.memoCount + "개)\n(화면을 켜 두고 워치를 폰 가까이 두세요)"),
                (ok, res) -> {
                    busy = false;
                    getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    String line = ok ? "✔ 메모 " + plan.memoCount + "개를 워치로 보냈어요" : "✘ " + res;
                    store.setLastLog(line);
                    say(line);
                    if (!ok && plan.resumeAt > 0) {
                        new AlertDialog.Builder(this)
                                .setTitle("보내다 끊겼어요")
                                .setMessage(res + "\n\n끊긴 곳부터 이어서 보낼까요?")
                                .setPositiveButton("이어서 보내기", (d, w) -> sendPlan(plan.resumeAt))
                                .setNeutralButton("처음부터", (d, w) -> sendPlan(0))
                                .setNegativeButton("닫기", null)
                                .show();
                    }
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
