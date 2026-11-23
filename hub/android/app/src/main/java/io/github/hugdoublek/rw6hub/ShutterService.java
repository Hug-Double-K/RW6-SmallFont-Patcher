package io.github.hugdoublek.rw6hub;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;

/**
 * 워치 셔터 (워치 퀵앱 '카메라 셔터' io.github.hugdoublek.rw6cam → {cam:1 사진 / 2 동영상 시작·끝 / 3 일시정지·다시}) → 폰에 열려 있는 카메라 앱의 셔터를 대신 누른다.
 * 워치 기본 카메라 앱은 블루투스 HID 방식인데 중국판 펌웨어가 HID 켜기(2/8)에 답하지 않아 못 씀 → 접근성으로 대신함.
 *  1) 화면에서 '셔터/촬영' 버튼을 찾아 누름  2) 못 찾았는데 카메라 앱이면 셔터 자리(아래 가운데)를 톡 누름
 *  3) 카메라 앱이 아니면 카메라를 열어 줌 (한 번 더 누르면 찍힘)
 * 화면 내용은 셔터 버튼을 찾을 때만 보고, 저장하거나 밖으로 보내지 않는다.
 */
public class ShutterService extends AccessibilityService {

    public static final String WATCH_PACKAGE = "io.github.hugdoublek.rw6cam";
    private static volatile ShutterService inst;
    private static final String[] WORDS = {"shutter", "셔터", "촬영", "take picture", "take photo", "capture", "사진 찍기"};

    public static boolean enabled(Context c) {
        if (inst != null) return true;
        try {
            String s = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return s != null && s.contains(new ComponentName(c, ShutterService.class).flattenToString());
        } catch (Exception e) { return false; }
    }

    private static final String[] REC = {"record", "녹화", "동영상 촬영", "shutter", "셔터", "촬영", "capture"};
    private static final String[] STOP = {"stop", "중지", "정지", "종료", "녹화 끝"};
    private static final String[] PAUSE = {"pause", "일시정지", "일시 정지", "일시중지", "일시 중지"};
    private static final String[] RESUME = {"resume", "다시 시작", "재개", "계속", "이어서"};
    private static final String[] NOT_PHOTO = {"동영상", "video", "record", "녹화", "설정", "setting"};
    private static final String[] NOT_BTN = {"설정", "setting"};
    private static final String[] NOT_STOP = {"pause", "일시", "resume", "설정", "setting"};
    /** 동영상 상태: 0 안 찍는 중, 1 녹화 중, 2 일시정지 (우리가 누른 것 기준 — 폰에서 직접 누르면 어긋날 수 있음) */
    private static volatile int video;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** 워치가 버튼을 눌렀을 때 (아무 스레드). what: 1 사진, 2 동영상 시작/끝, 3 일시정지/다시 촬영 */
    public static void trigger(Context c, int what) {
        final Context app = c.getApplicationContext();
        MAIN.post(() -> {
            ShutterService s = inst;
            if (s == null) { reply(app, "접근성 권한이 꺼져 있어요 (Mi Manager 첫 화면 → 카메라 셔터)", "폰에서 권한을 켜 주세요"); return; }
            try {
                if (what == 2) s.videoToggle(app);
                else if (what == 3) s.pauseToggle(app);
                else {
                    String r = s.shoot(video != 0);
                    reply(app, r, r.startsWith("찍음") ? "찍었어요" : r.startsWith("카메라를 열었어요") ? "카메라를 열었어요\n한 번 더 누르세요" : r.startsWith("셔터 버튼을 못 찾아") ? "셔터 자리를 눌렀어요" : "못 찍었어요");
                }
            } catch (Exception e) { reply(app, "실패 " + e, "실패했어요"); }
        });
    }

    private static void reply(Context app, String log, String watch) {
        WatchLink.get(app).log("워치 셔터: " + log);
        try {
            String json = new org.json.JSONObject().put("r", watch).put("v", video).toString();
            QuickApps.get(app).sendMessage(WATCH_PACKAGE, json.getBytes(java.nio.charset.StandardCharsets.UTF_8), err -> {});
        } catch (Exception ignored) {}
    }

    private String pkg() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        return root == null || root.getPackageName() == null ? "" : root.getPackageName().toString();
    }

    private static boolean isCamera(String pkg) {
        String p = pkg.toLowerCase();
        return p.contains("camera") || p.contains("cam.") || p.contains("snowcorp");
    }

    /** 찾은 버튼 누르기. 눌렀으면 버튼 이름, 아니면 null */
    private String press(String[] words, String[] not) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return null;
        AccessibilityNodeInfo n = find(root, words, not);
        if (n == null) return null;
        AccessibilityNodeInfo t = n;
        for (int i = 0; i < 4 && t != null && !t.isClickable(); i++) t = t.getParent();
        if (t != null && t.isClickable() && t.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return label(n);
        Rect b = new Rect(); n.getBoundsInScreen(b);
        if (!b.isEmpty() && tap(b.centerX(), b.centerY())) return label(n) + " (자리 누름)";
        return null;
    }

    private boolean tapShutterSpot() {
        DisplayMetrics m = getResources().getDisplayMetrics();
        int w = m.widthPixels, h = m.heightPixels;
        return w < h ? tap(w / 2f, h * 0.86f) : tap(w * 0.88f, h / 2f);
    }

    /** 못 찾았을 때 기록용: 화면에 보이는 누를 수 있는 것들의 이름 */
    private String dump() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return "(화면을 읽지 못함)";
        StringBuilder sb = new StringBuilder();
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);
        int seen = 0, got = 0;
        while (!q.isEmpty() && seen++ < 600 && got < 30) {
            AccessibilityNodeInfo n = q.poll();
            if (n == null) continue;
            if (n.isVisibleToUser() && n.isClickable()) {
                String l = label(n).trim();
                if (!l.isEmpty()) { sb.append(got++ == 0 ? "" : " / ").append(l); }
            }
            for (int i = 0; i < n.getChildCount(); i++) q.add(n.getChild(i));
        }
        return sb.length() == 0 ? "(이름 있는 버튼 없음)" : sb.toString();
    }

    private void videoToggle(Context app) {
        if (video != 0) {   // 녹화 끝
            String b = press(STOP, NOT_STOP);
            if (b == null && isCamera(pkg()) && tapShutterSpot()) b = "가운데 자리";
            if (b == null) { reply(app, "녹화 끝 버튼을 못 눌렀어요 (" + pkg() + ") 화면: " + dump(), "끝내지 못했어요"); return; }
            video = 0;
            reply(app, "녹화 끝 (버튼: " + b + ")", "녹화를 끝냈어요");
            return;
        }
        // 녹화 시작: 카메라를 동영상 모드로 연 다음 조금 기다렸다가 녹화 버튼
        try {
            startActivity(new Intent("android.media.action.VIDEO_CAMERA").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            reply(app, "동영상 카메라를 열지 못했어요 (" + e.getClass().getSimpleName() + ")", "카메라를 못 열었어요");
            return;
        }
        MAIN.postDelayed(() -> {
            try {
                String p = pkg();
                String b = press(REC, NOT_BTN);
                if (b == null && isCamera(p) && tapShutterSpot()) b = "가운데 자리";
                if (b == null) { reply(app, "녹화 버튼을 못 눌렀어요 (" + p + ") 화면: " + dump(), "녹화를 시작하지 못했어요"); return; }
                video = 1;
                reply(app, "녹화 시작 (버튼: " + b + ", " + p + ")", "녹화 중");
            } catch (Exception e) { reply(app, "실패 " + e, "실패했어요"); }
        }, 2000);
    }

    private void pauseToggle(Context app) {
        if (video == 0) { reply(app, "녹화 중이 아니에요", "녹화 중이 아니에요"); return; }
        boolean toPause = video == 1;
        String b = press(toPause ? PAUSE : RESUME, NOT_BTN);
        if (b == null) b = press(toPause ? RESUME : PAUSE, NOT_BTN);   // 버튼 이름이 안 바뀌는 카메라 대비
        if (b == null) { reply(app, (toPause ? "일시정지" : "다시 촬영") + " 버튼을 못 찾았어요 (" + pkg() + ") 화면: " + dump(), "버튼을 못 찾았어요"); return; }
        video = toPause ? 2 : 1;
        reply(app, (toPause ? "일시정지" : "다시 촬영") + " (버튼: " + b + ")", toPause ? "일시정지" : "녹화 중");
    }

    @Override protected void onServiceConnected() { inst = this; }
    @Override public boolean onUnbind(Intent i) { inst = null; return super.onUnbind(i); }
    @Override public void onDestroy() { inst = null; super.onDestroy(); }
    @Override public void onAccessibilityEvent(AccessibilityEvent e) {}
    @Override public void onInterrupt() {}

    private String shoot(boolean recording) {
        String pkg = pkg();
        String b = press(WORDS, NOT_PHOTO);
        if (b != null) return "찍음 (버튼: " + b + ")";
        if (recording) return "녹화 중 사진 버튼을 못 찾았어요 (" + pkg + ") 화면: " + dump();
        if (isCamera(pkg)) {
            return tapShutterSpot() ? "셔터 버튼을 못 찾아 셔터 자리를 눌렀어요 (" + pkg + ")" : "셔터를 누르지 못했어요 (" + pkg + ")";
        }
        try {
            startActivity(new Intent("android.media.action.STILL_IMAGE_CAMERA").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return "카메라를 열었어요. 한 번 더 누르면 찍어요";
        } catch (Exception e) {
            return "폰에서 카메라를 먼저 열어 주세요 (" + e.getClass().getSimpleName() + ")";
        }
    }

    private static String label(AccessibilityNodeInfo n) {
        String id = n.getViewIdResourceName();
        CharSequence d = n.getContentDescription();
        return (d != null ? d.toString() : "") + (id != null ? " " + id.substring(id.indexOf('/') + 1) : "");
    }

    /** 화면에서 셔터로 보이는 버튼 찾기 (아이디·설명·글자에 셔터/촬영 등이 든 것) */
    private static AccessibilityNodeInfo find(AccessibilityNodeInfo root, String[] words, String[] not) {
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);
        AccessibilityNodeInfo best = null;
        int bestScore = 0, seen = 0;
        while (!q.isEmpty() && seen++ < 600) {
            AccessibilityNodeInfo n = q.poll();
            if (n == null) continue;
            if (n.isVisibleToUser()) {
                String id = n.getViewIdResourceName(), d = n.getContentDescription() == null ? "" : n.getContentDescription().toString(),
                        t = n.getText() == null ? "" : n.getText().toString();
                String all = ((id == null ? "" : id.substring(id.indexOf('/') + 1)) + "|" + d + "|" + t).toLowerCase();
                int score = 0;
                for (String w : words) if (all.contains(w)) { score = 2; break; }
                if (score > 0) for (String w : not) if (all.contains(w)) { score = 0; break; }
                if (score > 0 && n.isClickable()) score++;
                if (score > bestScore) { bestScore = score; best = n; }
            }
            for (int i = 0; i < n.getChildCount(); i++) q.add(n.getChild(i));
        }
        return best;
    }

    private boolean tap(float x, float y) {
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription g = new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(p, 0, 60)).build();
        return dispatchGesture(g, null, null);
    }
}
