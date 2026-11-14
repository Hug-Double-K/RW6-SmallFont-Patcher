package io.github.hugdoublek.rw6hub;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/**
 * 워치 연결을 계속 붙잡아 두는 서비스. 상단에 조용한 상태 알림 하나를 띄운다
 * (안드로이드가 백그라운드 연결을 끊지 않게 하려면 꼭 필요함).
 */
public class LinkService extends Service implements WatchLink.StateListener {

    private static final String CHANNEL = "link";
    private static final int ID = 1;

    /** 연결 유지를 켠다. 서비스를 못 띄우는 상황이면 연결만 시작한다. */
    public static void start(Context c) {
        Context app = c.getApplicationContext();
        app.getSharedPreferences("hub", MODE_PRIVATE).edit().putBoolean("keep", true).putBoolean("yielded", false).apply();
        if (!statusNotification(app)) {
            // 상단 알림 끔: 서비스 없이 연결만 유지 (알림 접근 권한이 있으면 앱이 계속 살아 있음)
            app.stopService(new Intent(app, LinkService.class));
            WatchLink.get(app).start();
            return;
        }
        try {
            app.startForegroundService(new Intent(app, LinkService.class));
        } catch (Exception e) {
            WatchLink.get(app).log("상태 알림 서비스를 못 띄움: " + e.getMessage());
            WatchLink.get(app).start();
        }
    }

    /** 연결 유지를 끈다 (Mi Fitness에 양보). */
    public static void stop(Context c, String why) {
        Context app = c.getApplicationContext();
        app.getSharedPreferences("hub", MODE_PRIVATE).edit().putBoolean("keep", false).putBoolean("yielded", true).apply();
        WatchLink.get(app).stop(why);
        app.stopService(new Intent(app, LinkService.class));
    }

    /** 워치·인증키가 다 있고, 사용자가 Mi Fitness에 양보한 상태가 아니면 연결을 시작한다. */
    public static void autoStart(Context c) {
        android.content.SharedPreferences p = c.getApplicationContext().getSharedPreferences("hub", MODE_PRIVATE);
        if (p.getBoolean("yielded", false)) return;
        if (p.getString("mac", "").isEmpty() || p.getString("authKey", "").isEmpty()) return;
        if (!WatchLink.get(c).wantsConnection()) start(c);
    }

    /** 상단 연결 상태 알림을 띄울지 (기본 켬) */
    public static boolean statusNotification(Context c) {
        return c.getApplicationContext().getSharedPreferences("hub", MODE_PRIVATE).getBoolean("statusNoti", true);
    }

    public static void setStatusNotification(Context c, boolean on) {
        Context app = c.getApplicationContext();
        app.getSharedPreferences("hub", MODE_PRIVATE).edit().putBoolean("statusNoti", on).apply();
        if (keep(app)) start(app); // 바뀐 설정대로 다시 시작 (연결은 끊지 않음)
    }

    public static boolean keep(Context c) {
        return c.getApplicationContext().getSharedPreferences("hub", MODE_PRIVATE).getBoolean("keep", false);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "워치 연결 상태", NotificationManager.IMPORTANCE_MIN);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
        Notification n = build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        else startForeground(ID, n);
        WatchLink.get(this).addStateListener(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        WatchLink.get(this).start();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        WatchLink.get(this).removeStateListener(this);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private Notification build() {
        WatchLink l = WatchLink.get(this);
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(io.github.hugdoublek.rw6hub.R.drawable.ic_stat_watch)
                .setColor(0xFFFF6FA8)
                .setContentTitle("Mi Manager · " + l.state())
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    private String last = "";

    @Override
    public void onStateChanged() {
        String s = WatchLink.get(this).state();
        if (s.equals(last)) return;
        last = s;
        getSystemService(NotificationManager.class).notify(ID, build());
    }
}
