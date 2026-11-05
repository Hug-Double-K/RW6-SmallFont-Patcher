package io.github.hugdoublek.rw6noti;

import android.app.Notification;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** 폰 알림을 받아 카드 이미지로 그려 워치로 보낸다 */
public class NotiListener extends NotificationListenerService {

    private final Map<String, String> lastSent = new HashMap<>();

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        try {
            handle(sbn);
        } catch (Throwable t) {
            new Prefs(this).setLastLog(now() + " 오류: " + t);
        }
    }

    private void handle(StatusBarNotification sbn) {
        if (sbn == null) return;
        Prefs prefs = new Prefs(this);
        String pkg = sbn.getPackageName();
        if (!prefs.wants(pkg)) return;

        Notification n = sbn.getNotification();
        if (n == null) return;
        // 묶음 요약, 진행 중(음악·다운로드 등) 알림은 건너뜀
        if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
        if ((n.flags & Notification.FLAG_ONGOING_EVENT) != 0) return;
        Bundle ex = n.extras;
        if (ex == null) return;

        String title = str(ex.getCharSequence(Notification.EXTRA_TITLE));
        String text = str(ex.getCharSequence(Notification.EXTRA_TEXT));
        String conv = str(ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE));
        if (conv.isEmpty()) conv = str(ex.getCharSequence(Notification.EXTRA_SUB_TEXT));
        String sender = title;
        Bitmap picture = null;

        // 메신저형 알림이면 마지막 메시지를 사용
        Parcelable[] msgs = ex.getParcelableArray(Notification.EXTRA_MESSAGES);
        if (msgs != null && msgs.length > 0 && msgs[msgs.length - 1] instanceof Bundle) {
            Bundle last = (Bundle) msgs[msgs.length - 1];
            CharSequence t = last.getCharSequence("text");
            if (t != null && t.length() > 0) text = t.toString();
            CharSequence s = last.getCharSequence("sender");
            if (s == null && Build.VERSION.SDK_INT >= 28) {
                Object person = last.get("sender_person");
                if (person instanceof android.app.Person) s = ((android.app.Person) person).getName();
            }
            if (s != null && s.length() > 0) sender = s.toString();
            String type = last.getString("type");
            Object uri = last.get("uri");
            if (prefs.pictures() && type != null && type.startsWith("image/") && uri instanceof Uri) {
                picture = loadUri((Uri) uri);
            }
        }
        if (prefs.pictures() && picture == null) picture = bigPicture(ex);
        if (text.isEmpty() && picture == null) return;
        if (prefs.photoOnly() && picture == null) return; // 글 알림은 기본 팝업으로

        // 같은 내용이 연달아 오면 한 번만
        String key = sbn.getKey();
        String sig = sender + "|" + text + "|" + (picture != null);
        if (sig.equals(lastSent.get(key))) return;
        lastSent.put(key, sig);
        if (lastSent.size() > 200) lastSent.clear();

        PackageManager pm = getPackageManager();
        String appName = pkg;
        Drawable icon = null;
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            appName = pm.getApplicationLabel(ai).toString();
            icon = ai.loadIcon(pm);
        } catch (PackageManager.NameNotFoundException ignored) { }

        Bitmap card = Renderer.render(icon, appName, conv, sender, text, picture,
                prefs.fontSize(), sbn.getPostTime());
        String b64 = Renderer.toBase64Png(card);
        if (b64.length() > 1_500_000 && picture != null) { // 너무 크면 사진 없이 다시
            card = Renderer.render(icon, appName, conv, sender, text + "\n[사진]", null,
                    prefs.fontSize(), sbn.getPostTime());
            b64 = Renderer.toBase64Png(card);
        }
        final String summary = appName + " / " + sender;
        WatchSender.send(this, b64, (ok, msg) -> prefs.setLastLog(now() + " " + summary + " → " + msg));
    }

    private Bitmap bigPicture(Bundle ex) {
        try {
            Object p = ex.get(Notification.EXTRA_PICTURE);
            if (p instanceof Bitmap) return (Bitmap) p;
            if (Build.VERSION.SDK_INT >= 31) {
                Object ic = ex.get(Notification.EXTRA_PICTURE_ICON);
                if (ic instanceof Icon) {
                    Drawable d = ((Icon) ic).loadDrawable(this);
                    if (d instanceof BitmapDrawable) return ((BitmapDrawable) d).getBitmap();
                }
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private Bitmap loadUri(Uri uri) {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 2;
            return BitmapFactory.decodeStream(in, null, o);
        } catch (Throwable e) {
            return null; // 앱이 사진 접근을 허용하지 않은 경우
        }
    }

    private static String str(CharSequence cs) { return cs == null ? "" : cs.toString(); }

    static String now() { return new SimpleDateFormat("MM/dd HH:mm:ss", Locale.KOREA).format(new Date()); }
}
