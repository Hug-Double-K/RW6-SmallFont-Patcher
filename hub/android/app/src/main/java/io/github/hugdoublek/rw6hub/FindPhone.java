package io.github.hugdoublek.rw6hub;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;

import java.util.List;

/**
 * 워치의 '폰 찾기' (Mi Fitness가 하던 일). 워치가 2/17 {system(4).findDevice(5)=0}을 보내면 폰이 크게 울리고,
 * 1이면 멈춘다. 폰에서 멈추면 워치에도 2/17 {findDevice=1}로 알린다. 무음·진동 모드여도 알람 소리로 울림.
 */
public final class FindPhone {

    private static final String CHANNEL = "find_phone", ACTION_STOP = "io.github.hugdoublek.rw6hub.FIND_STOP";
    private static final int NOTI_ID = 4217;

    private static FindPhone inst;
    public static synchronized FindPhone get(Context c) {
        if (inst == null) inst = new FindPhone(c.getApplicationContext());
        return inst;
    }

    private final Context app;
    private final Handler h = new Handler(Looper.getMainLooper());
    private MediaPlayer player;
    private int oldVolume = -1;
    private boolean ringing;

    private FindPhone(Context c) {
        app = c;
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) { stop(true); }
        };
        IntentFilter f = new IntentFilter(ACTION_STOP);
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED);
        else app.registerReceiver(r, f);
    }

    /** WatchLink가 2/17을 넘겨줌 (연결 스레드) */
    public void onPacket(List<Pb.Field> p) {
        List<Pb.Field> sys = Pb.sub(p, 4);
        Pb.Field f = sys == null ? null : Pb.find(sys, 5);
        final boolean start = f == null || f.varint == 0;
        h.post(() -> { if (start) start(); else stop(false); });
    }

    private final Runnable timeout = () -> stop(true);

    private void start() {
        WatchLink.get(app).log("워치에서 '폰 찾기' → 폰이 울려요");
        if (ringing) return;
        ringing = true;
        try {
            AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
            oldVolume = am.getStreamVolume(AudioManager.STREAM_ALARM);
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0);
            Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            player.setDataSource(app, uri);
            player.setLooping(true);
            player.prepare();
            player.start();
        } catch (Exception e) {
            WatchLink.get(app).log("폰 찾기 소리 실패: " + e);
        }
        try {
            Vibrator v = (Vibrator) app.getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) v.vibrate(VibrationEffect.createWaveform(new long[]{0, 600, 400}, 0));
        } catch (Exception ignored) {}
        try {
            NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "폰 찾기", NotificationManager.IMPORTANCE_HIGH));
            PendingIntent stop = PendingIntent.getBroadcast(app, 0, new Intent(ACTION_STOP).setPackage(app.getPackageName()),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification n = new Notification.Builder(app, CHANNEL)
                    .setSmallIcon(R.drawable.ic_stat_watch)
                    .setColor(0xFFFF6FA8)
                    .setContentTitle("폰 찾는 중")
                    .setContentText("여기를 누르면 소리를 멈춰요")
                    .setContentIntent(stop)
                    .setOngoing(true)
                    .addAction(new Notification.Action.Builder(null, "멈추기", stop).build())
                    .build();
            nm.notify(NOTI_ID, n);
        } catch (Exception ignored) {}
        h.removeCallbacks(timeout);
        h.postDelayed(timeout, 60_000);
    }

    /** tellWatch: 폰에서 멈춘 경우 워치에도 '찾았음'을 알림 */
    private void stop(boolean tellWatch) {
        h.removeCallbacks(timeout);
        if (!ringing) return;
        ringing = false;
        try { if (player != null) { player.stop(); player.release(); } } catch (Exception ignored) {}
        player = null;
        try {
            if (oldVolume >= 0) ((AudioManager) app.getSystemService(Context.AUDIO_SERVICE)).setStreamVolume(AudioManager.STREAM_ALARM, oldVolume, 0);
        } catch (Exception ignored) {}
        oldVolume = -1;
        try { Vibrator v = (Vibrator) app.getSystemService(Context.VIBRATOR_SERVICE); if (v != null) v.cancel(); } catch (Exception ignored) {}
        try { ((NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(NOTI_ID); } catch (Exception ignored) {}
        if (tellWatch) WatchLink.get(app).send(new Pb.W().varint(1, 2).varint(2, 17).msg(4, new Pb.W().varint(5, 1)));
        WatchLink.get(app).log("폰 찾기 멈춤");
    }
}
