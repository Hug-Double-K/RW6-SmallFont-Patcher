package io.github.hugdoublek.rw6noti;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;

import com.xiaomi.xms.wearable.Wearable;

import java.util.ArrayList;
import java.util.List;

/**
 * 자가 점검: 하루 4번쯤(6시간마다) 스스로 상태를 확인하고, 문제가 있으면 폰 알림으로 알려 준다.
 * 알림 접근 꺼짐·수신 서비스 끊김·워치로 못 보냄·워치 앱 없음·버전 다름·배터리 최적화를 본다.
 */
public class HealthCheck extends BroadcastReceiver {
    static final String ACTION = "io.github.hugdoublek.rw6noti.CHECK";
    private static final long EVERY = 6 * 60 * 60 * 1000L;

    public interface Done { void report(String text, boolean problem); }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String a = intent == null ? null : intent.getAction();
        Context app = ctx.getApplicationContext();
        if (Intent.ACTION_BOOT_COMPLETED.equals(a) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) { schedule(app); return; }
        if (!ACTION.equals(a)) return;
        final PendingResult pr = goAsync();
        run(app, true, (text, problem) -> { try { pr.finish(); } catch (Throwable ignored) { } });
    }

    /** 6시간마다 점검하도록 예약 (이미 있으면 덮어씀) */
    static void schedule(Context ctx) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            PendingIntent pi = PendingIntent.getBroadcast(ctx, 7, new Intent(ctx, HealthCheck.class).setAction(ACTION),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + EVERY, EVERY, pi);
        } catch (Throwable ignored) { }
    }

    static boolean listenerEnabled(Context ctx) {
        String flat = Settings.Secure.getString(ctx.getContentResolver(), "enabled_notification_listeners");
        return flat != null && flat.contains(ctx.getPackageName());
    }

    /**
     * 점검 실행. notify = 문제가 있으면 폰 알림으로 알림 (같은 문제는 12시간에 한 번만).
     * 결과는 done으로 (메인 스레드).
     */
    static void run(Context ctx, boolean notify, Done done) {
        final Context app = ctx.getApplicationContext();
        final Handler main = new Handler(Looper.getMainLooper());
        final List<String> bad = new ArrayList<>();
        final List<String> good = new ArrayList<>();
        final Prefs p = new Prefs(app);

        // 1) 알림 접근
        boolean enabled = listenerEnabled(app);
        if (!enabled) bad.add("알림 접근이 꺼져 있음 → 앱에서 '알림 접근 허용'을 켜 주세요");
        else good.add("알림 접근 켜짐");

        // 2) 배터리 최적화
        try {
            PowerManager pm = app.getSystemService(PowerManager.class);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(app.getPackageName()))
                bad.add("배터리 최적화가 켜져 있음 → 알림이 늦거나 빠질 수 있어요 ('배터리 최적화 제외')");
        } catch (Throwable ignored) { }

        // 3) 최근에 워치로 못 보낸 채로 10분 넘게 회복되지 않음 (워치가 연결돼 있는데도 그럴 때만 문제로 봄 — 아래 6에서)
        long ok = p.lastOkAt(), fail = p.lastFailAt(), now = System.currentTimeMillis();
        final boolean stuck = fail > ok && now - fail > WatchSender.KEEP_MS && now - fail < 24 * 60 * 60 * 1000L;
        int pend = WatchSender.pendingCount();
        if (pend > 0) good.add("다시 보낼 알림 " + pend + "개 대기 중");

        // 4) 버전
        String mine = "?";
        try { mine = app.getPackageManager().getPackageInfo(app.getPackageName(), 0).versionName; } catch (Throwable ignored) { }
        final String myVer = mine;
        String w = p.watchVer();
        if (!w.isEmpty() && !w.equals(mine)) bad.add("폰 앱(" + mine + ")과 워치 앱(" + w + ") 버전이 달라요 → 워치 앱도 " + mine + "(으)로 설치");

        // 5) 수신 서비스 연결: 꺼져 있으면 다시 붙여 달라고 요청하고 5초 뒤 다시 확인
        if (enabled && !NotiListener.connected) {
            try { NotificationListenerService.requestRebind(new ComponentName(app, NotiListener.class)); } catch (Throwable ignored) { }
        }
        main.postDelayed(() -> {
            if (enabled && !NotiListener.connected)
                bad.add("수신 서비스가 끊겨 있음 → 알림 접근을 껐다 켜 주세요 (안 되면 폰 재시작)");
            else if (enabled) good.add("수신 서비스 연결됨");

            // 6) 워치 연결·워치 앱 설치 (워치가 멀리 있는 건 문제로 보지 않음)
            final boolean[] fin = {false};
            Runnable finish = () -> {
                if (fin[0]) return;
                fin[0] = true;
                StringBuilder b = new StringBuilder(NotiListener.now()).append(" 점검 (폰 ").append(myVer).append(")\n");
                if (bad.isEmpty()) b.append("✔ 문제 없음\n");
                for (String s : bad) b.append("⚠ ").append(s).append('\n');
                for (String s : good) b.append("· ").append(s).append('\n');
                String text = b.toString().trim();
                if (notify) alert(app, bad);
                if (done != null) done.report(text, !bad.isEmpty());
            };
            try {
                Wearable.getNodeApi(app).getConnectedNodes().addOnSuccessListener(nodes -> {
                    if (nodes == null || nodes.isEmpty()) { good.add("워치: 지금 연결 안 됨 (멀리 있으면 정상)"); main.post(finish); return; }
                    good.add("워치 연결됨: " + nodes.get(0).name);
                    if (stuck) bad.add("워치가 연결돼 있는데 최근 알림을 워치로 못 보냈어요 → 워치 앱·워치 권한을 확인해 주세요");
                    Wearable.getNodeApi(app).isWearAppInstalled(nodes.get(0).id)
                            .addOnSuccessListener(inst -> { if (!inst) bad.add("워치에 메신저 알림 앱이 없어요 → .rpk 설치"); main.post(finish); })
                            .addOnFailureListener(e -> main.post(finish));
                }).addOnFailureListener(e -> { good.add("워치 조회 실패: " + e.getMessage()); main.post(finish); });
            } catch (Throwable t) { main.post(finish); }
            main.postDelayed(finish, 8000); // 응답이 없어도 8초 뒤엔 끝냄
        }, enabled && !NotiListener.connected ? 5000 : 0);
    }

    private static String ago(long ms) {
        long m = ms / 60000;
        return m < 60 ? m + "분" : (m / 60) + "시간";
    }

    /** 문제가 있으면 폰 알림. 같은 문제는 12시간에 한 번만, 문제가 사라지면 알림을 지움 */
    private static void alert(Context app, List<String> bad) {
        NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        Prefs p = new Prefs(app);
        if (bad.isEmpty()) {
            nm.cancel(71);
            p.setCheck("", 0);
            return;
        }
        String sig = String.join("|", bad).replaceAll("\\d+", "#");
        long now = System.currentTimeMillis();
        if (sig.equals(p.checkSig()) && now - p.checkAt() < 12 * 60 * 60 * 1000L) return;
        p.setCheck(sig, now);
        nm.createNotificationChannel(new NotificationChannel("check", "RW6 알림 자가 점검", NotificationManager.IMPORTANCE_DEFAULT));
        PendingIntent open = PendingIntent.getActivity(app, 0, new Intent(app, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String body = String.join("\n", bad);
        Notification n = new Notification.Builder(app, "check")
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("RW6 알림: 확인이 필요해요 (" + bad.size() + ")")
                .setContentText(bad.get(0))
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build();
        try { nm.notify(71, n); } catch (Throwable ignored) { }
    }
}
