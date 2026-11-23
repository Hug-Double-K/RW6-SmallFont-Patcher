package io.github.hugdoublek.rw6hub;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 건강 화면 (1단계): 실시간 걸음·심박, 워치의 건강 기록 받기, 날짜별 요약 */
public class HealthActivity extends Activity implements Health.Listener {

    private static final int BG = 0xFF101114, CARD = 0xFF1C1E24, TEXT = 0xFFF2F3F5, SUB = 0xFF9AA0AA, ACCENT = 0xFFFF6FA8;

    private Health health;
    private final Handler h = new Handler(Looper.getMainLooper());
    private TextView stepsV, hrV, calV, standV, liveNote, syncStatus, daysV, filesV;
    private Button syncBtn, autoBtn;
    private HourChart chart;
    private static final int[] AUTO_MIN = {0, 1, 3, 5, 10, 15, 30, 60, 180, 360, 720};
    private boolean daysDirty = true;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("건강");
        health = Health.get(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(8), dp(16), dp(24));

        LinearLayout c = card(root, "지금 (워치에서 실시간)");
        LinearLayout r1 = new LinearLayout(this);
        stepsV = big(r1, "걸음");
        hrV = big(r1, "심박");
        c.addView(r1);
        LinearLayout r2 = new LinearLayout(this);
        calV = big(r2, "칼로리");
        standV = big(r2, "서 있기");
        c.addView(r2);
        liveNote = label("", 13, SUB, false);
        liveNote.setPadding(0, dp(8), 0, 0);
        c.addView(liveNote);

        c = card(root, "워치의 건강 기록");
        c.addView(label("워치에 쌓인 걸음·심박·수면·운동 기록을 폰으로 받아요. 워치에서는 지워지지 않아요.", 13, SUB, false));
        syncBtn = button("지금 동기화", v -> { health.sync(); });
        c.addView(syncBtn);
        syncStatus = label("", 13, TEXT, false);
        c.addView(syncStatus);
        autoBtn = button("", v -> pickAuto());
        c.addView(autoBtn);
        c.addView(label("워치가 연결돼 있을 때, 정한 주기마다 자동으로 받아요. 주기가 짧을수록 워치와 폰의 배터리를 더 써요.", 13, SUB, false));

        c = card(root, "오늘 시간대별 걸음");
        chart = new HourChart(this);
        c.addView(chart, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(150)));

        c = card(root, "날짜별 요약");
        daysV = label("", 14, TEXT, false);
        daysV.setLineSpacing(dp(4), 1f);
        c.addView(daysV);

        c = card(root, "받은 기록 파일");
        filesV = label("", 13, SUB, false);
        c.addView(filesV);
        c.addView(button("기록 파일 내보내기 (분석용 zip)", v -> {
            String n = health.exportZip();
            Toast.makeText(this, n == null ? "내보낼 파일이 없어요" : "다운로드 폴더에 저장: " + n, Toast.LENGTH_LONG).show();
        }));

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        sv.addView(root);
        setContentView(sv);
    }

    @Override
    protected void onResume() {
        super.onResume();
        health.addListener(this);
        health.startLive();
        daysDirty = true;
        refresh();
        h.postDelayed(tick, 5000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        h.removeCallbacks(tick);
        health.removeListener(this);
        health.stopLive();
    }

    // 실시간 값이 한동안 안 오면 다시 요청 (연결이 다시 붙었을 때 등)
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (System.currentTimeMillis() - health.liveAt > 15_000 && WatchLink.get(HealthActivity.this).isReady()) health.startLive();
            refresh();
            h.postDelayed(this, 5000);
        }
    };

    @Override
    public void onHealthChanged() {
        if (!health.isSyncing()) daysDirty = true;
        refresh();
    }

    private void refresh() {
        stepsV.setText(health.steps < 0 ? "–" : String.format(Locale.KOREA, "%,d", health.steps));
        hrV.setText(health.heartRate <= 0 ? "–" : String.valueOf(health.heartRate));
        calV.setText(health.calories < 0 ? "–" : health.calories + " kcal");
        standV.setText(health.standing < 0 ? "–" : health.standing + " 시간");
        boolean ready = WatchLink.get(this).isReady();
        if (!ready) liveNote.setText("워치가 연결돼 있지 않아요");
        else if (health.liveAt == 0) liveNote.setText("워치에 요청하는 중…");
        else liveNote.setText("마지막 갱신 " + new SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(new Date(health.liveAt)));

        syncBtn.setEnabled(ready && !health.isSyncing());
        autoBtn.setText("자동 동기화 주기: " + autoName(health.autoMinutes()));
        String st = health.status();
        long at = health.lastSyncAt();
        if (at > 0 && !health.isSyncing()) st = (st.isEmpty() ? "" : st + "\n") + "마지막 동기화 " + new SimpleDateFormat("M/d HH:mm", Locale.KOREA).format(new Date(at));
        syncStatus.setText(st);

        if (daysDirty) {
            daysDirty = false;
            List<Health.Day> days = Health.Days.load(health);
            StringBuilder sb = new StringBuilder();
            SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd", Locale.US), out = new SimpleDateFormat("M/d (E)", Locale.KOREA), hm = new SimpleDateFormat("HH:mm", Locale.KOREA);
            String today = in.format(new Date());
            int n = 0;
            Health.Day todayDay = null;
            for (Health.Day d : days) {
                if (d.date.equals(today)) todayDay = d;
                if (n++ >= 30) break;
                String date = d.date;
                try { date = out.format(in.parse(d.date)); } catch (Exception ignored) {}
                sb.append(date).append("  걸음 ").append(String.format(Locale.KOREA, "%,d", d.steps));
                if (!d.fromSummary) sb.append(" (받은 구간만)");
                if (d.calories > 0) sb.append(" · ").append(d.calories).append("kcal");
                sb.append('\n');
                if (d.hrMax > 0) {
                    sb.append("      심박 ").append(d.hrMin).append("~").append(d.hrMax).append(" · 평균 ").append(d.hrAvg());
                    if (d.hrRest > 0) sb.append(" · 안정 ").append(d.hrRest);
                    sb.append('\n');
                }
                StringBuilder ex = new StringBuilder();
                if (d.standHours >= 0) ex.append("서 있기 ").append(d.standHours).append("시간");
                if (d.spo2Avg() > 0) ex.append(ex.length() > 0 ? " · " : "").append("산소 ").append(d.spo2Avg()).append("%");
                if (d.stressAvg() > 0) ex.append(ex.length() > 0 ? " · " : "").append("스트레스 ").append(d.stressAvg());
                if (ex.length() > 0) sb.append("      ").append(ex).append('\n');
                for (Health.Sleep sl : d.sleeps) {
                    int m = sl.minutes();
                    sb.append("      수면 ").append(hm.format(new Date(sl.bed * 1000L))).append("~").append(hm.format(new Date(sl.wake * 1000L)))
                            .append(" (").append(m / 60).append("시간 ").append(m % 60).append("분)\n");
                }
            }
            daysV.setText(sb.length() == 0 ? "아직 보여 줄 기록이 없어요. '지금 동기화'를 눌러 보세요." : sb.toString().trim());
            chart.setData(todayDay == null ? new int[24] : todayDay.hourSteps);
            filesV.setText(health.fileSummary());
        }
    }

    private static String autoName(int m) {
        if (m <= 0) return "끔";
        return m < 60 ? m + "분마다" : (m / 60) + "시간마다";
    }

    private void pickAuto() {
        String[] names = new String[AUTO_MIN.length];
        int cur = 0;
        for (int i = 0; i < AUTO_MIN.length; i++) { names[i] = autoName(AUTO_MIN[i]); if (AUTO_MIN[i] == health.autoMinutes()) cur = i; }
        new android.app.AlertDialog.Builder(this)
                .setTitle("자동 동기화 주기")
                .setSingleChoiceItems(names, cur, (d, w) -> { health.setAutoMinutes(AUTO_MIN[w]); d.dismiss(); refresh(); })
                .setNegativeButton("취소", null)
                .show();
    }

    /** 0~23시 막대 그래프 */
    static final class HourChart extends View {
        private int[] data = new int[24];
        private final android.graphics.Paint bar = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint txt = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        HourChart(android.content.Context c) { super(c); bar.setColor(ACCENT); txt.setColor(SUB); }
        void setData(int[] d) { data = d; invalidate(); }
        @Override protected void onDraw(android.graphics.Canvas cv) {
            float den = getResources().getDisplayMetrics().density;
            float w = getWidth(), h = getHeight(), bottom = h - 18 * den, top = 16 * den;
            txt.setTextSize(11 * den);
            int max = 1, maxHour = -1;
            for (int i = 0; i < 24; i++) if (data[i] > max) { max = data[i]; maxHour = i; }
            float slot = w / 24f, bw = slot * 0.62f;
            for (int i = 0; i < 24; i++) {
                float x = i * slot + (slot - bw) / 2;
                float bh = data[i] <= 0 ? 0 : Math.max(2 * den, (bottom - top) * data[i] / max);
                bar.setAlpha(data[i] <= 0 ? 40 : 255);
                float y = data[i] <= 0 ? bottom - 2 * den : bottom - bh;
                cv.drawRoundRect(x, y, x + bw, bottom, 3 * den, 3 * den, bar);
                if (i % 6 == 0) cv.drawText(i + "시", i * slot, h - 4 * den, txt);
            }
            if (maxHour >= 0) {
                String s = String.format(Locale.KOREA, "%,d", max);
                float tx = Math.min(w - txt.measureText(s), Math.max(0, maxHour * slot + slot / 2 - txt.measureText(s) / 2));
                cv.drawText(s, tx, top - 4 * den, txt);
            }
        }
    }

    // ---------------------------------------------------------------- 화면 도우미

    private LinearLayout card(LinearLayout root, String title) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(dp(18));
        c.setBackground(bg);
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(14);
        root.addView(c, lp);
        TextView t = label(title, 13, ACCENT, true);
        t.setPadding(0, 0, 0, dp(6));
        c.addView(t);
        return c;
    }

    /** 큰 숫자 + 아래 작은 이름 (한 줄에 두 개씩) */
    private TextView big(LinearLayout row, String name) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, dp(6), 0, dp(6));
        TextView v = label("–", 30, TEXT, true);
        col.addView(v);
        col.addView(label(name, 13, SUB, false));
        row.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        return v;
    }

    private TextView label(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(ACCENT);
        bg.setCornerRadius(dp(24));
        b.setBackground(bg);
        b.setGravity(Gravity.CENTER);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        lp.topMargin = dp(10); lp.bottomMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
