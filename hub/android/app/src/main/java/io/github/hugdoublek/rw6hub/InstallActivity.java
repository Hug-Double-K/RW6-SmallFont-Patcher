package io.github.hugdoublek.rw6hub;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/** 퀵앱 · 워치페이스 · 펌웨어 설치 화면 */
public class InstallActivity extends Activity {

    private static final int PICK = 1;
    private Installer.Kind picking;
    private TextView status;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("설치");
        int pad = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        root.addView(header("퀵앱 · 워치페이스"));
        root.addView(text("파일을 고르면 내용을 확인한 뒤 설치해요. 설치하는 동안 워치를 폰 가까이 두세요."));
        root.addView(button("퀵앱 설치 (.rpk)", v -> pick(Installer.Kind.QUICKAPP)));
        root.addView(button("워치페이스 설치 (.bin)", v -> pick(Installer.Kind.WATCHFACE)));

        root.addView(header("펌웨어 (위험)"));
        TextView warn = text("잘못된 파일을 설치하면 워치가 켜지지 않을 수 있어요. 이 워치(REDMI Watch 6 중국판, "
                + "miwear.watch.p65)용 공식 펌웨어 파일만 고르세요. 워치 배터리 50% 이상, 설치 중에는 앱을 닫지 마세요.");
        warn.setTextColor(Color.rgb(255, 140, 120));
        root.addView(warn);
        root.addView(button("펌웨어 설치…", v -> pick(Installer.Kind.FIRMWARE)));

        status = text("");
        root.addView(status);

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);
    }

    private void pick(Installer.Kind k) {
        if (Installer.get(this).busy()) { say("이미 설치 중이에요"); return; }
        if (!WatchLink.get(this).isReady()) { say("워치가 연결되어 있지 않아요. 첫 화면에서 연결을 확인하세요"); return; }
        picking = k;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, PICK);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != PICK || res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        Installer.Kind kind = picking;
        say("파일 읽는 중…");
        new Thread(() -> {
            try {
                byte[] bytes = read(uri);
                String name = fileName(uri);
                Installer.Package p = Installer.inspect(kind, bytes, name);
                runOnUiThread(() -> confirm(p, name));
            } catch (Exception e) {
                runOnUiThread(() -> say("파일을 읽지 못했어요: " + e.getMessage()));
            }
        }).start();
    }

    private void confirm(Installer.Package p, String fileName) {
        if (p.error != null) {
            new AlertDialog.Builder(this).setTitle("설치할 수 없어요").setMessage(p.error)
                    .setPositiveButton("확인", null).show();
            say("✘ " + p.error);
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("파일: ").append(fileName).append("\n크기: ").append(String.format("%.1f", p.data.length / 1048576.0)).append("MB\n");
        if (p.kind == Installer.Kind.QUICKAPP) sb.append("이름: ").append(p.name).append("\n패키지: ").append(p.id).append(" (v").append(p.versionCode).append(")");
        if (p.kind == Installer.Kind.WATCHFACE) sb.append("워치페이스 ID: ").append(p.id);
        if (p.kind != Installer.Kind.FIRMWARE) {
            new AlertDialog.Builder(this)
                    .setTitle(p.kind.label + " 설치")
                    .setMessage(sb)
                    .setPositiveButton("설치", (d, w) -> run(p))
                    .setNegativeButton("취소", null)
                    .show();
            return;
        }
        // 펌웨어: 배터리 확인 + '설치'를 직접 입력해야 버튼이 켜짐
        int bat = WatchLink.get(this).battery;
        if (bat >= 0 && bat < 50) {
            new AlertDialog.Builder(this).setTitle("배터리가 부족해요")
                    .setMessage("워치 배터리가 " + bat + "%예요. 50% 이상 충전한 뒤 다시 하세요.")
                    .setPositiveButton("확인", null).show();
            return;
        }
        sb.append("\n워치 배터리: ").append(bat >= 0 ? bat + "%" : "모름")
                .append("\n\n⚠ 이 워치용 공식 펌웨어가 맞는지 다시 확인하세요. 설치 중 워치가 재시작해요.\n\n계속하려면 아래 칸에 '설치'라고 적으세요.");
        EditText et = new EditText(this);
        et.setHint("설치");
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("⚠ 펌웨어 설치")
                .setMessage(sb)
                .setView(et)
                .setPositiveButton("펌웨어 설치", (x, w) -> run(p))
                .setNegativeButton("취소", null)
                .show();
        Button ok = d.getButton(AlertDialog.BUTTON_POSITIVE);
        ok.setEnabled(false);
        et.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b2, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b2, int c) {}
            @Override public void afterTextChanged(Editable s) { ok.setEnabled(s.toString().trim().equals("설치")); }
        });
    }

    /** 진행 창(진행바 · 퍼센트 · 남은 시간)을 띄우고 설치 */
    private void run(Installer.Package p) {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        box.setPadding(pad, pad / 2, pad, 0);
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        TextView stage = new TextView(this);
        stage.setTextSize(13);
        TextView pct = new TextView(this);
        pct.setTextSize(15);
        box.addView(stage);
        box.addView(bar);
        box.addView(pct);
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(p.kind.label + " 설치 중")
                .setView(box)
                .setCancelable(false)
                .setNegativeButton(p.kind == Installer.Kind.FIRMWARE ? "창 닫기" : "취소", (d, w) -> {
                    if (p.kind != Installer.Kind.FIRMWARE) Installer.get(this).cancel();
                })
                .show();
        final long[] t0 = {0, 0};
        Installer.get(this).start(p, new Installer.Listener() {
            @Override public void stage(String s) { stage.setText(s); }

            @Override public void progress(long done, long total) {
                long now = System.currentTimeMillis();
                if (t0[0] == 0) { t0[0] = now; t0[1] = done; }
                double f = total == 0 ? 0 : done / (double) total;
                bar.setProgress((int) Math.round(f * 1000));
                String eta = "";
                long sent = done - t0[1], el = now - t0[0];
                if (sent > 0 && el > 1500) {
                    long remain = (long) (el * (total - done) / (double) sent / 1000);
                    eta = " · 남은 시간 약 " + (remain >= 60 ? remain / 60 + "분 " : "") + remain % 60 + "초";
                }
                pct.setText(Math.round(f * 100) + "% (" + done / 1024 + " / " + total / 1024 + "KB)" + eta);
            }

            @Override public void done(boolean ok, String message) {
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                try { dlg.dismiss(); } catch (RuntimeException ignored) {}
                say((ok ? "✔ " : "✘ ") + message);
                new AlertDialog.Builder(InstallActivity.this)
                        .setTitle(ok ? "완료" : "실패")
                        .setMessage(message)
                        .setPositiveButton("확인", null)
                        .show();
            }
        });
    }

    // ---------------- 도우미 ----------------

    private byte[] read(Uri uri) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new Exception("열 수 없음");
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                bo.write(buf, 0, n);
                if (bo.size() > 64 * 1024 * 1024) throw new Exception("파일이 너무 커요 (64MB 넘음)");
            }
            return bo.toByteArray();
        }
    }

    private String fileName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {}
        String s = uri.getLastPathSegment();
        return s == null ? "" : s;
    }

    private void say(String s) { status.setText(s); }

    private TextView header(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(16);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(16), 0, dp(4));
        return t;
    }

    private TextView text(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(14);
        t.setPadding(0, dp(2), 0, dp(6));
        return t;
    }

    private Button button(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
