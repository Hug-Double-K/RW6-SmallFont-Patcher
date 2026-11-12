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

    /** 같은 알림을 두 번 보내지 않기 위한 기록 (오래된 것부터 하나씩 버림. 서비스가 다시 붙어도 유지) */
    private static final Map<String, String> lastSent = new java.util.LinkedHashMap<String, String>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, String> e) { return size() > 200; }
    };
    /** 이 앱(프로세스)이 시작된 시각. 그보다 한참 전의 사진은 이미 보냈거나 지나간 것 → 다시 보내지 않음 */
    private static final long STARTED = System.currentTimeMillis();
    /** 앱 아이콘을 워치에 보낸 시각 (1시간마다 다시 보냄) */
    private static final Map<String, Long> ICON_AT = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Set<String> sentUris = new java.util.LinkedHashSet<>();
    /** 이미 워치로 보낸 메시지 (대화방 알림에 쌓인 최근 메시지 중 놓친 것만 골라 보내기 위해) */
    private static final java.util.Set<String> sentMsgs = new java.util.LinkedHashSet<>();
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
        Prefs lp = new Prefs(this);
        WatchSender.ackLog = (ok, stamp) -> { if (!ok) lp.addHistory(now() + " ⚠ 워치가 받았다는 확인이 없음 (" + stamp.substring(7) + ")"); };
    }
    @Override public void onListenerDisconnected() {
        connected = false;
        // 시스템이 연결을 끊으면 스스로 다시 붙여 달라고 요청 (알림 접근을 껐다 켜는 수고를 줄임)
        try { requestRebind(new android.content.ComponentName(this, NotiListener.class)); } catch (Throwable ignored) { }
    }

    /**
     * 앱 업데이트 뒤 수신 서비스가 안 붙는 문제 대비: 시스템에 다시 붙여 달라고 요청.
     * (서비스를 껐다 켜는 방법은 쓰지 않음: 최근 안드로이드에선 그 사이에 알림 접근 허용이 지워질 수 있음)
     */
    static void rebind(android.content.Context ctx) {
        try { requestRebind(new android.content.ComponentName(ctx, NotiListener.class)); } catch (Throwable ignored) { }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        // 그림 그리기·사진 읽기는 작업 스레드에서 (메인 스레드가 막히면 다음 알림 수신이 늦어짐)
        worker.post(() -> {
            try {
                handle(sbn);
            } catch (Throwable t) {
                new Prefs(this).setLastLog(now() + " 오류: " + t);
            }
        });
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
        // 알림 하나에 메시지가 여러 개 쌓여 오면(연달아 왔을 때) 마지막 것만 보내던 문제 → 놓친 글 메시지도 찾아 보냄
        List<String[]> missed = new ArrayList<>(); // {보낸 사람, 글, 시각}
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
                long mt = m.getLong("time", 0);
                // 앱이 다시 시작된 뒤 새 메시지가 오면, 알림에 같이 들어 있는 예전 사진까지 또 보내던 문제 방지
                // (가장 마지막 메시지는 방금 온 것이므로 항상 보냄)
                boolean stale = i != msgs.length - 1 && mt > 0 && mt < STARTED - 60_000;
                // 글 메시지: 처음 보는 것이면 기록. 마지막이 아닌데 아직 안 보낸 것은 '놓친 메시지'
                CharSequence mtxt = m.getCharSequence("text");
                boolean isImg = type != null && type.startsWith("image/");
                if (!isImg && mtxt != null && mtxt.length() > 0) {
                    String id = sbn.getKey() + "|" + mt + "|" + who + "|" + mtxt.toString().hashCode();
                    boolean fresh = sentMsgs.add(id);
                    long lastT = msgs[msgs.length - 1] instanceof Bundle ? ((Bundle) msgs[msgs.length - 1]).getLong("time", 0) : 0;
                    // 시각이 있고, 앱이 켜진 뒤의 것이며, 마지막 메시지와 10분 안에 온 것만 (오래된 대화를 다시 보내지 않게)
                    if (fresh && i != msgs.length - 1 && mt > 0 && !stale && lastT > 0 && lastT - mt < 10 * 60 * 1000L)
                        missed.add(0, new String[]{who, mtxt.toString(), String.valueOf(mt)});
                }
                if (isImg && uri instanceof Uri && !stale && !sentUris.contains(uri.toString())) {
                    CharSequence t = m.getCharSequence("text");
                    String ts = t == null ? "" : t.toString();
                    // 카톡 이모티콘: '이모티콘을 보냈습니다.' + 그림 → 사진보다 작게 보냄
                    boolean emo = ts.contains(EMOTICON) || (i == msgs.length - 1 && text.contains(EMOTICON));
                    photos.add(0, new PhotoMsg(who, ts, (Uri) uri, m.getLong("time", sbn.getPostTime()), emo));
                }
            }
        }

        { // 기록이 너무 커지지 않게 오래된 것부터 버림
            java.util.Iterator<String> it = sentMsgs.iterator();
            while (sentMsgs.size() > 1000 && it.hasNext()) { it.next(); it.remove(); }
        }

        // 펼친 알림(긴 글)에 더 많은 글자가 있으면 그걸 사용
        text = longer(text, str(ex.getCharSequence(Notification.EXTRA_BIG_TEXT)));
        // 채널·봇(북클럽, 카카오이모티콘 등) 알림은 여러 줄 묶음(InboxStyle)으로 오기도 함
        CharSequence[] lines = ex.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
        if (lines != null && lines.length > 0) {
            StringBuilder lb = new StringBuilder();
            for (CharSequence l : lines) if (l != null && l.length() > 0) lb.append(lb.length() > 0 ? "\n" : "").append(l);
            if (lb.length() > text.length()) text = lb.toString();
        }
        // 최대한 많은 글자를 보여 주는 게 원칙. 워치 메모리 보호를 위해 6000자까지만
        if (text.length() > MAX_TEXT) text = text.substring(0, MAX_TEXT) + "…";

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

        // 놓친 글 메시지를 먼저 (시간 순서대로) 보냄. 이름(시각)은 지금보다 조금 앞으로 → 목록에서도 순서가 맞음
        if (!missed.isEmpty() && !prefs.photoOnly()) {
            long base = System.currentTimeMillis() - missed.size() - 1;
            for (int j = 0; j < missed.size(); j++) {
                String[] mm = missed.get(j);
                String t = mm[1].length() > MAX_TEXT ? mm[1].substring(0, MAX_TEXT) + "…" : mm[1];
                long when = Long.parseLong(mm[2]);
                prefs.addHistory(now() + " " + mm[0] + ": 한꺼번에 온 메시지라 따로 보냄");
                renderAndSend(prefs, icon, appName, conv, mm[0], t, null, when, String.valueOf(base + j), t.contains(EMOTICON));
            }
        }

        if (prefs.pictures() && !photos.isEmpty()) {
            for (PhotoMsg ph : photos) {
                sentUris.add(ph.uri.toString());
                // 사진은 받는 데 시간이 걸리니, 먼저 '보낸 사람: 사진' 글 알림을 즉시 보내고 같은 자리에 사진을 끼움
                sendText(prefs, fIcon, fApp, fConv, ph.sender, ph.emoticon ? "이모티콘" : "사진", ph.time, ph.stamp, true);
                // 카톡은 알림을 먼저 띄우고 사진을 나중에 내려받는 일이 있어, 실패하면 잠시 뒤 다시 읽는다
                worker.post(() -> loadAndSend(prefs, fIcon, fApp, fConv, ph, fs, 0));
            }
            // 오래된 것부터 하나씩 버림 (한꺼번에 비우면 예전 사진이 다시 갈 수 있음)
            java.util.Iterator<String> it = sentUris.iterator();
            while (sentUris.size() > 500 && it.hasNext()) { it.next(); it.remove(); }
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

        renderAndSend(prefs, icon, appName, conv, sender, text, picture, sbn.getPostTime(), String.valueOf(System.currentTimeMillis()),
                text.contains(EMOTICON));
    }

    private static final long[] RETRY_MS = {0, 1500, 3000, 6000, 10000};

    private void loadAndSend(Prefs prefs, Drawable icon, String app, String conv, PhotoMsg ph, int fs, int attempt) {
        Bitmap pic = loadUri(ph.uri, ph.emoticon); // 이모티콘은 원래 작아서 줄이지 않고 읽음
        if (pic == null && attempt + 1 < RETRY_MS.length) {
            worker.postDelayed(() -> loadAndSend(prefs, icon, app, conv, ph, fs, attempt + 1), RETRY_MS[attempt + 1]);
            return;
        }
        if (pic == null) {
            prefs.addHistory(now() + " " + ph.sender + ": 사진 읽기 실패 (" + RETRY_MS.length + "번 시도) — " + lastLoadError);
            return;
        }
        renderAndSend(prefs, icon, app, conv, ph.sender, ph.text, pic, ph.time, ph.stamp, ph.emoticon);
    }

    /** 글 알림 JSON (워치가 직접 그림). pending = 사진이 뒤따라옴 */
    private String textJson(Drawable icon, String appName, String conv, String sender, String text, long when, boolean pending) {
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("a", appName); o.put("c", conv == null ? "" : conv); o.put("s", sender == null ? "" : sender);
            o.put("b", text == null ? "" : text); o.put("w", when);
            if (pending) o.put("p", 1);
            Bitmap ib = Renderer.iconBitmap(icon, 40);
            String k = String.valueOf(Math.abs(appName.hashCode()));
            o.put("k", k);
            Long at = ICON_AT.get(k);
            if (ib != null && (at == null || System.currentTimeMillis() - at > 3_600_000L)) {
                o.put("i", Renderer.toBase64Png(ib));
                ICON_AT.put(k, System.currentTimeMillis());
            }
            return o.toString();
        } catch (org.json.JSONException e) { return null; }
    }

    /**
     * 보낸 사람(si)·윗줄 '앱 · 대화방'(hi)에 이모지가 있으면 그 줄을 폰에서 그린 그림으로 붙인다.
     * 워치는 그림이 있으면 글 대신 그림을 보여 줌 (글은 이모지를 뺀 채로 남겨 목록 미리보기 등에 사용)
     */
    private static void addEmojiLines(org.json.JSONObject o, String appName, String conv, String sender, int fs) {
        try {
            if (hasEmoji(sender)) {
                Bitmap b = Renderer.lineBitmap(sender, fs, 0xFFFFFFFF, true, 384);
                o.put("si", Renderer.toBase64Png(b)); o.put("siw", b.getWidth()); o.put("sih", b.getHeight());
            }
            String head = conv == null || conv.isEmpty() ? appName : appName + " · " + conv;
            if (hasEmoji(head)) {
                Bitmap b = Renderer.lineBitmap(head, 19, 0xFFAAAAAA, false, 250); // 목록에서 옆에 시간이 들어갈 자리를 남김
                o.put("hi", Renderer.toBase64Png(b)); o.put("hiw", b.getWidth()); o.put("hih", b.getHeight());
            }
        } catch (Throwable ignored) { }
    }

    private void sendText(Prefs prefs, Drawable icon, String appName, String conv, String sender, String text,
                          long when, String stamp, boolean pending) {
        String json = hasEmoji(sender) || hasEmoji(conv) || hasEmoji(appName)
                ? textJson(icon, appName, stripEmoji(conv == null ? "" : conv), stripEmoji(sender == null ? "" : sender), text, when, pending)
                : textJson(icon, appName, conv, sender, text, when, pending);
        if (json == null) return;
        if (hasEmoji(sender) || hasEmoji(conv) || hasEmoji(appName)) {
            try {
                org.json.JSONObject o = new org.json.JSONObject(json);
                addEmojiLines(o, appName, conv, sender, prefs.fontSize());
                json = o.toString();
            } catch (org.json.JSONException ignored) { }
        }
        final String q = WatchSender.quiet(this) ? "[조용히] " : "";
        WatchSender.sendKeep(this, json, stamp, (ok, msg) ->
                prefs.addHistory(now() + " " + q + sender + (pending ? " [사진 먼저 알림]" : "") + " → " + (ok ? "✔ " : "✘ ") + msg));
    }

    private void renderAndSend(Prefs prefs, Drawable icon, String appName, String conv, String sender,
                               String text, Bitmap picture, long when, String stamp, boolean emoticon) {
        // 이모티콘은 원본과 화면 폭 70% 중 작은 쪽으로 (확대하지 않음 → 선명하고 데이터도 작음)
        final int maxPicW = emoticon ? Math.round(Renderer.WIDTH * 0.7f) : 0;
        String b64 = null;
        boolean emoji = hasEmoji(text) || hasEmoji(sender) || hasEmoji(conv);
        if (picture == null && text != null && hasEmoji(text) && stripEmoji(text).trim().isEmpty() && text.length() <= 24) {
            // 이모지만 있는 메시지: 카드 전체가 아니라 이모지만 작게 그려 글 알림에 붙임 → 메시지 1번, 가로도 딱 맞게
            Bitmap eb = Renderer.emojiBitmap(text, 54); // 72의 75%: 데이터·워치 메모리 약 절반
            b64 = textJson(icon, appName, stripEmoji(conv == null ? "" : conv), stripEmoji(sender == null ? "" : sender), "", when, false);
            if (b64 == null) return;
            try {
                org.json.JSONObject o = new org.json.JSONObject(b64);
                o.put("e", Renderer.toBase64Png(eb)); o.put("ew", eb.getWidth()); o.put("eh", eb.getHeight());
                b64 = o.toString();
            } catch (org.json.JSONException ex) { return; }
        } else if (picture == null && text != null && hasEmoji(text)) {
            // 이모지는 워치 글꼴에 없어 X 박스가 됨 → 이모지가 든 문단만 작은 그림으로, 나머지는 글 그대로 (길이 상관없이)
            b64 = textJson(icon, appName, stripEmoji(conv == null ? "" : conv), stripEmoji(sender == null ? "" : sender), stripEmoji(text), when, false);
            if (b64 == null) return;
            try {
                org.json.JSONObject o = new org.json.JSONObject(b64);
                o.put("sg", segments(text, prefs.fontSize()));
                b64 = o.toString();
            } catch (org.json.JSONException ex) { return; }
        } else if (picture == null) {
            // 글만 있는 알림: 이미지로 그리지 않고 글 그대로 (워치가 직접 그림). 아주 긴 글의 이모지는 빼고 보냄
            b64 = emoji ? textJson(icon, appName, stripEmoji(conv == null ? "" : conv), stripEmoji(sender == null ? "" : sender), stripEmoji(text == null ? "" : text), when, false)
                    : textJson(icon, appName, conv, sender, text, when, false);
            if (b64 == null) return;
        }
        if (picture == null && (hasEmoji(sender) || hasEmoji(conv) || hasEmoji(appName))) {
            try {
                org.json.JSONObject o = new org.json.JSONObject(b64);
                addEmojiLines(o, appName, conv, sender, prefs.fontSize());
                b64 = o.toString();
            } catch (org.json.JSONException ex) { return; }
        }
        if (picture != null) {
            b64 = Renderer.toBase64Jpeg(Renderer.render(icon, appName, conv, sender, text, picture, prefs.fontSize(), when, maxPicW));
        }
        if (b64.length() > 1_500_000 && picture != null) { // 너무 크면 사진을 더 줄여서
            Bitmap small = Bitmap.createScaledBitmap(picture, Math.max(1, picture.getWidth() / 2), Math.max(1, picture.getHeight() / 2), true);
            b64 = Renderer.toBase64Jpeg(Renderer.render(icon, appName, conv, sender, text, small, prefs.fontSize(), when, maxPicW));
        }
        final boolean quiet = WatchSender.quiet(this);
        final String summary = (quiet ? "[조용히] " : "") + sender + (picture == null ? "" : emoticon
                ? " [이모티콘 " + picture.getWidth() + "→" + Math.min(picture.getWidth(), maxPicW) + "px]" : " [사진]");
        final int kb = b64.length() / 1024;
        final String app = appName;
        final String line = sender + ": " + (picture == null ? text : emoticon ? "이모티콘" : "사진");
        WatchSender.sendKeep(this, b64, stamp, (ok, msg) -> {
            prefs.setLastLog(now() + " " + summary + " → " + msg);
            prefs.addHistory(now() + " " + summary + " " + kb + "KB → " + (ok ? "✔ " : "✘ ") + msg);
            if (ok && prefs.watchList() && !quiet) { // 방해금지 중엔 워치 알림 목록 표시도 안 함 (진동·화면 켜짐 방지)
                prefs.setUnread(prefs.unread() + 1);
                // 워치가 실제 개수를 알려줄 시간을 조금 준 뒤 표시
                worker.postDelayed(() -> WatchSender.notify(this, app + " 알림 - " + prefs.unread() + "건", line), 3000);
            }
        });
    }

    private static int SEQ = 0;
    static final int MAX_TEXT = 6000;
    static final String EMOTICON = "이모티콘을 보냈습니다";

    static final class PhotoMsg {
        final String sender, text; final Uri uri; final long time;
        /** 워치에서 쓰는 이름 (글 알림 먼저 → 사진이 같은 이름으로 대체) */
        final String stamp = String.valueOf(System.currentTimeMillis() + (SEQ++ % 1000));
        final boolean emoticon;
        PhotoMsg(String s, String t, Uri u, long w, boolean e) { sender = s; text = t; uri = u; time = w; emoticon = e; }
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
            if (uri instanceof Uri) {
                int[] wh = bounds((Uri) uri);
                b.append(" / 읽기=").append(wh != null ? "성공" : "실패");
                if (wh != null) b.append("\n원본 크기=").append(wh[0]).append("×").append(wh[1]);
            }
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

    /** 사진 가로·세로만 읽음 (풀지 않음). 실패하면 null */
    private int[] bounds(Uri uri) {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(in, null, o);
            return o.outWidth > 0 && o.outHeight > 0 ? new int[]{o.outWidth, o.outHeight} : null;
        } catch (Throwable e) { return null; }
    }

    /**
     * 사진 읽기. 카드에 들어갈 크기(가로 404 · 세로 640)보다 작아지지 않는 선에서 최대한 줄여 읽음
     * → 작은 사진은 원본 그대로(선명), 큰 사진은 2·4·8배 줄여(빠르고 메모리 적음). keepFull = 이모티콘은 원본 그대로
     */
    private Bitmap loadUri(Uri uri, boolean keepFull) {
        int sample = 1;
        if (!keepFull) {
            int[] wh = bounds(uri);
            if (wh != null) {
                int needW = Renderer.WIDTH - 28, needH = 640;
                // 줄인 뒤에도 '가로 ≥ 404' 또는 '세로 ≥ 640' 중 카드에 맞출 쪽은 유지
                float fit = Math.min((float) needW / wh[0], (float) needH / wh[1]);
                while (fit * sample * 2 <= 1f) sample *= 2;
            }
        }
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) { lastLoadError = "빈 파일"; return null; }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            Bitmap b = BitmapFactory.decodeStream(in, null, o);
            if (b == null) lastLoadError = "사진 형식을 읽지 못함(아직 내려받는 중일 수 있음)";
            return b;
        } catch (Throwable e) {
            lastLoadError = e.getClass().getSimpleName() + ": " + e.getMessage();
            return null; // 앱이 사진 접근을 허용하지 않았거나 아직 내려받는 중
        }
    }

    /**
     * 글을 조각으로: 이모지 없는 줄은 글({"t"}), 이모지가 든 줄은 그림({"i","w","h"}).
     * 긴 줄은 150자 안팎으로 나눠 그림이 너무 커지지 않게. 그림이 너무 많으면(약 200KB) 나머지는 이모지만 빼고 글로.
     */
    private org.json.JSONArray segments(String text, int fs) throws org.json.JSONException {
        org.json.JSONArray arr = new org.json.JSONArray();
        java.util.List<String> buf = new java.util.ArrayList<>();
        int total = 0;
        for (String para : text.split("\n", -1)) {
            if (!hasEmoji(para) || total > 200_000) { buf.add(hasEmoji(para) ? stripEmoji(para) : para); continue; }
            if (!buf.isEmpty()) { arr.put(new org.json.JSONObject().put("t", String.join("\n", buf))); buf.clear(); }
            int i = 0;
            while (i < para.length()) {
                int end = Math.min(para.length(), i + 150);
                if (end < para.length()) { // 공백에서 끊고, 글자(이모지)를 반으로 자르지 않게
                    int sp = para.lastIndexOf(' ', end);
                    if (sp > i + 60) end = sp + 1;
                    while (end < para.length() && (Character.isLowSurrogate(para.charAt(end)) || para.charAt(end) == 0x200D
                            || para.charAt(end) == 0xFE0F)) end++;
                }
                String piece = para.substring(i, end);
                Bitmap pb = Renderer.paraBitmap(piece, fs, 384);
                String png = Renderer.toBase64Png(pb);
                total += png.length();
                arr.put(new org.json.JSONObject().put("i", png).put("w", pb.getWidth()).put("h", pb.getHeight()));
                i = end;
            }
        }
        if (!buf.isEmpty()) arr.put(new org.json.JSONObject().put("t", String.join("\n", buf)));
        return arr;
    }

    /** 워치 글꼴에 없는 이모지인지 */
    static boolean isEmoji(int cp) {
        if (cp >= 0x1F000 || (cp >= 0x2600 && cp <= 0x27BF) || (cp >= 0x2300 && cp <= 0x23FF)
                || (cp >= 0x2B00 && cp <= 0x2BFF) || cp == 0xFE0F || cp == 0x200D || (cp >= 0xE000 && cp <= 0xF8FF)) return true;
        // 큰 범위 밖의 이모지들 (워치 글꼴에 없어 X 박스가 되는 것): 키캡 1️⃣의 ⃣, ‼ ⁉ ™ ℹ ↔~↙ ↩ ↪ Ⓜ ▪ ▫ ▶ ◀ ◻~◾ ⤴ ⤵ 〰 〽 ㊗ ㊙, 변형 선택자
        switch (cp) {
            case 0x20E3: case 0x203C: case 0x2049: case 0x2122: case 0x2139: case 0x21A9: case 0x21AA:
            case 0x24C2: case 0x25AA: case 0x25AB: case 0x25B6: case 0x25C0: case 0x2934: case 0x2935:
            case 0x3030: case 0x303D: case 0x3297: case 0x3299: case 0xFE0E:
                return true;
        }
        return (cp >= 0x2194 && cp <= 0x2199) || (cp >= 0x25FB && cp <= 0x25FE);
    }

    static boolean hasEmoji(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); ) { int cp = s.codePointAt(i); if (isEmoji(cp)) return true; i += Character.charCount(cp); }
        return false;
    }

    static String stripEmoji(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); ) { int cp = s.codePointAt(i); if (!isEmoji(cp)) b.appendCodePoint(cp); i += Character.charCount(cp); }
        return b.toString();
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
