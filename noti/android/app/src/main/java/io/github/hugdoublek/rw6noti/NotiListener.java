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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 폰 알림을 받아 카드 이미지로 그려 워치로 보낸다 */
public class NotiListener extends NotificationListenerService {

    private final Map<String, String> lastSent = new HashMap<>();
    private final java.util.Set<String> sentUris = new java.util.HashSet<>();
    private static String lastLoadError = "";
    private static final android.os.HandlerThread THREAD = new android.os.HandlerThread("rw6noti");
    static { THREAD.start(); }
    private final android.os.Handler worker = new android.os.Handler(THREAD.getLooper());
    /** 시스템이 이 서비스에 연결돼 있는지 (앱 업데이트 후 끊기는 일이 잦음) */
    static volatile boolean connected = false;

    @Override public void onListenerConnected() {
        connected = true;
        new Prefs(this).setLastLog(now() + " 알림 수신 서비스 연결됨");
        try { WatchSender.listen(this); } catch (Throwable ignored) { }
    }
    @Override public void onListenerDisconnected() { connected = false; }

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
        if (Prefs.KAKAO.equals(pkg)) prefs.setDiag(diagnose(sbn));
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

        // 메신저형 알림: 최근 메시지들 중 아직 안 보낸 사진을 모두 찾는다
        // (사진 뒤에 글이 바로 오거나, 여러 장을 한 번에 보내는 경우 대비)
        List<PhotoMsg> photos = new ArrayList<>();
        Parcelable[] msgs = ex.getParcelableArray(Notification.EXTRA_MESSAGES);
        if (msgs != null && msgs.length > 0) {
            for (int i = msgs.length - 1; i >= Math.max(0, msgs.length - 6); i--) {
                if (!(msgs[i] instanceof Bundle)) continue;
                Bundle m = (Bundle) msgs[i];
                String who = sender;
                CharSequence s = m.getCharSequence("sender");
                if (s == null && Build.VERSION.SDK_INT >= 28) {
                    Object person = m.get("sender_person");
                    if (person instanceof android.app.Person) s = ((android.app.Person) person).getName();
                }
                if (s != null && s.length() > 0) who = s.toString();
                if (i == msgs.length - 1) {
                    CharSequence t = m.getCharSequence("text");
                    if (t != null && t.length() > 0) text = longer(t.toString(), text);
                    sender = who;
                }
                String type = m.getString("type");
                Object uri = m.get("uri");
                if (type != null && type.startsWith("image/") && uri instanceof Uri && !sentUris.contains(uri.toString())) {
                    CharSequence t = m.getCharSequence("text");
                    photos.add(0, new PhotoMsg(who, t == null ? "" : t.toString(), (Uri) uri, m.getLong("time", sbn.getPostTime())));
                }
            }
        }

        // 펼친 알림(긴 글)에 더 많은 글자가 있으면 그걸 사용
        text = longer(text, str(ex.getCharSequence(Notification.EXTRA_BIG_TEXT)));

        PackageManager pm = getPackageManager();
        String appName = pkg;
        Drawable icon = null;
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            appName = pm.getApplicationLabel(ai).toString();
            icon = ai.loadIcon(pm);
        } catch (PackageManager.NameNotFoundException ignored) { }
        final String fApp = appName, fConv = conv;
        final Drawable fIcon = icon;
        final int fs = prefs.fontSize();

        if (prefs.pictures() && !photos.isEmpty()) {
            for (PhotoMsg ph : photos) {
                sentUris.add(ph.uri.toString());
                // 카톡은 알림을 먼저 띄우고 사진을 나중에 내려받는 일이 있어, 실패하면 잠시 뒤 다시 읽는다
                worker.post(() -> loadAndSend(prefs, fIcon, fApp, fConv, ph, fs, 0));
            }
            if (sentUris.size() > 500) sentUris.clear();
            return;
        }

        Bitmap picture = prefs.pictures() ? bigPicture(ex) : null;
        if (picture == null && text.contains("사진")) {
            prefs.addHistory(now() + " " + sender + ": '" + text + "' — 알림에 사진 정보가 없음");
        }
        if (text.isEmpty() && picture == null) return;
        if (prefs.photoOnly() && picture == null) return; // 글 알림은 기본 팝업으로

        // 같은 내용이 연달아 오면 한 번만
        String key = sbn.getKey();
        String sig = sender + "|" + text + "|" + (picture != null);
        if (sig.equals(lastSent.get(key))) return;
        lastSent.put(key, sig);
        if (lastSent.size() > 200) lastSent.clear();

        renderAndSend(prefs, icon, appName, conv, sender, text, picture, sbn.getPostTime());
    }

    private static final long[] RETRY_MS = {0, 1500, 3000, 6000, 10000};

    private void loadAndSend(Prefs prefs, Drawable icon, String app, String conv, PhotoMsg ph, int fs, int attempt) {
        Bitmap pic = loadUri(ph.uri);
        if (pic == null && attempt + 1 < RETRY_MS.length) {
            worker.postDelayed(() -> loadAndSend(prefs, icon, app, conv, ph, fs, attempt + 1), RETRY_MS[attempt + 1]);
            return;
        }
        if (pic == null) {
            prefs.addHistory(now() + " " + ph.sender + ": 사진 읽기 실패 (" + RETRY_MS.length + "번 시도) — " + lastLoadError);
            return;
        }
        renderAndSend(prefs, icon, app, conv, ph.sender, ph.text, pic, ph.time);
    }

    private void renderAndSend(Prefs prefs, Drawable icon, String appName, String conv, String sender,
                               String text, Bitmap picture, long when) {
        String b64;
        if (picture == null) {
            // 글만 있는 알림: 이미지로 그리지 않고 글 그대로 (워치가 직접 그림)
            try {
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("a", appName); o.put("c", conv == null ? "" : conv); o.put("s", sender == null ? "" : sender);
                o.put("b", text == null ? "" : text); o.put("w", when);
                b64 = o.toString();
            } catch (org.json.JSONException e) { return; }
        } else {
            b64 = Renderer.toBase64Png(Renderer.render(icon, appName, conv, sender, text, picture, prefs.fontSize(), when));
        }
        if (b64.length() > 1_500_000 && picture != null) { // 너무 크면 사진을 더 줄여서
            Bitmap small = Bitmap.createScaledBitmap(picture, Math.max(1, picture.getWidth() / 2), Math.max(1, picture.getHeight() / 2), true);
            b64 = Renderer.toBase64Png(Renderer.render(icon, appName, conv, sender, text, small, prefs.fontSize(), when));
        }
        final String summary = sender + (picture != null ? " [사진]" : "");
        final int kb = b64.length() / 1024;
        final String app = appName;
        final String line = sender + ": " + (picture != null ? "사진" : text);
        WatchSender.send(this, b64, (ok, msg) -> {
            prefs.setLastLog(now() + " " + summary + " → " + msg);
            prefs.addHistory(now() + " " + summary + " " + kb + "KB → " + (ok ? "✔ " : "✘ ") + msg);
            if (ok && prefs.watchList()) {
                prefs.setUnread(prefs.unread() + 1);
                // 워치가 실제 개수를 알려줄 시간을 조금 준 뒤 표시
                worker.postDelayed(() -> WatchSender.notify(this, app + " 알림 - " + prefs.unread() + "건", line), 3000);
            }
        });
    }

    static final class PhotoMsg {
        final String sender, text; final Uri uri; final long time;
        PhotoMsg(String s, String t, Uri u, long w) { sender = s; text = t; uri = u; time = w; }
    }

    private String diagnose(StatusBarNotification sbn) {
        StringBuilder b = new StringBuilder(now()).append("\n");
        Notification n = sbn.getNotification();
        Bundle ex = n.extras;
        b.append("요약알림=").append((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0);
        b.append(" / 내용=").append(ex == null ? "-" : str(ex.getCharSequence(Notification.EXTRA_TEXT))).append("\n");
        if (ex == null) return b.toString();
        b.append("항목: ").append(ex.keySet()).append("\n");
        b.append("큰사진=").append(ex.get(Notification.EXTRA_PICTURE) != null);
        if (Build.VERSION.SDK_INT >= 31) b.append(" / 큰사진아이콘=").append(ex.get(Notification.EXTRA_PICTURE_ICON) != null);
        Parcelable[] msgs = ex.getParcelableArray(Notification.EXTRA_MESSAGES);
        b.append(" / 메시지수=").append(msgs == null ? 0 : msgs.length).append("\n");
        if (msgs != null && msgs.length > 0 && msgs[msgs.length - 1] instanceof Bundle) {
            Bundle last = (Bundle) msgs[msgs.length - 1];
            b.append("마지막메시지 항목: ").append(last.keySet()).append("\n");
            b.append("type=").append(last.getString("type"));
            Object uri = last.get("uri");
            b.append(" / uri=").append(uri == null ? "없음" : String.valueOf(uri).replaceAll("(?<=://[^/]{0,40}/).*", "…"));
            if (uri instanceof Uri) b.append(" / 읽기=").append(loadUri((Uri) uri) != null ? "성공" : "실패");
        }
        return b.toString();
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
            if (in == null) { lastLoadError = "빈 파일"; return null; }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 2;
            Bitmap b = BitmapFactory.decodeStream(in, null, o);
            if (b == null) lastLoadError = "사진 형식을 읽지 못함(아직 내려받는 중일 수 있음)";
            return b;
        } catch (Throwable e) {
            lastLoadError = e.getClass().getSimpleName() + ": " + e.getMessage();
            return null; // 앱이 사진 접근을 허용하지 않았거나 아직 내려받는 중
        }
    }

    /** 같은 글의 잘린 버전과 전체 버전 중 긴 쪽 */
    private static String longer(String a, String b) {
        if (b == null || b.isEmpty()) return a;
        if (a == null || a.isEmpty()) return b;
        String ca = a.replace("…", "").trim(), cb = b.replace("…", "").trim();
        if (cb.length() > ca.length() && cb.startsWith(ca.substring(0, Math.min(10, ca.length())))) return b;
        return a;
    }

    private static String str(CharSequence cs) { return cs == null ? "" : cs.toString(); }

    static String now() { return new SimpleDateFormat("MM/dd HH:mm:ss", Locale.KOREA).format(new Date()); }
}
