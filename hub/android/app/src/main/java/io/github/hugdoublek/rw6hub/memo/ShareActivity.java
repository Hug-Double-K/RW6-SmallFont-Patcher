package io.github.hugdoublek.rw6hub.memo;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.TextView;

/** 다른 앱(컬러노트 등)에서 공유받은 메모 하나를 바로 워치로 보낸다 */
public class ShareActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        TextView tv = new TextView(this);
        int p = Math.round(24 * getResources().getDisplayMetrics().density);
        tv.setPadding(p, p, p, p);
        tv.setTextSize(16);
        tv.setGravity(Gravity.CENTER);
        setContentView(tv);
        setTitle("워치로 보내기");

        Intent in = getIntent();
        String text = in == null ? null : in.getStringExtra(Intent.EXTRA_TEXT);
        String subject = in == null ? null : in.getStringExtra(Intent.EXTRA_SUBJECT);
        if (text == null || text.trim().isEmpty()) {
            tv.setText("보낼 글이 없어요.\n(잠긴 메모는 공유되지 않아요)");
            later(2000);
            return;
        }
        Memo.Store store = new Memo.Store(this);
        Memo m = Memo.fromShare(subject, text);
        tv.setText("「" + m.title + "」\n워치로 보내는 중…");
        WatchSender.sendOne(this, m, store.fontSize(), (ok, msg) -> {
            store.setLastLog(m.title + " → " + msg);
            tv.setText("「" + m.title + "」\n" + (ok ? "✔ " : "✘ ") + msg);
            later(ok ? 1200 : 4000);
        });
    }

    private void later(long ms) { new Handler(Looper.getMainLooper()).postDelayed(this::finish, ms); }
}
