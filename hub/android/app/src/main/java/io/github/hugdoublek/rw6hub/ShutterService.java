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
 * 워치 셔터 (워치 퀵앱 '카메라 셔터' io.github.hugdoublek.rw6cam → {cam:1}) → 폰에 열려 있는 카메라 앱의 셔터를 대신 누른다.
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

    /** 워치가 셔터를 눌렀을 때 (아무 스레드) */
    public static void trigger(Context c) {
        final Context app = c.getApplicationContext();
        new Handler(Looper.getMainLooper()).post(() -> {
            ShutterService s = inst;
            String r, watch;
            if (s == null) { r = "접근성 권한이 꺼져 있어요 (Mi Manager 첫 화면 → 카메라 셔터)"; watch = "폰에서 권한을 켜 주세요"; }
            else {
                try { r = s.shoot(); } catch (Exception e) { r = "실패 " + e; }
                watch = r.startsWith("찍음") ? "찍었어요" : r.startsWith("카메라를 열었어요") ? "카메라를 열었어요\n한 번 더 누르세요" : r.startsWith("셔터 버튼을 못 찾아") ? "셔터 자리를 눌렀어요" : "못 찍었어요";
            }
            WatchLink.get(app).log("워치 셔터: " + r);
            try {
                String json = new org.json.JSONObject().put("r", watch).toString();
                QuickApps.get(app).sendMessage(WATCH_PACKAGE, json.getBytes(java.nio.charset.StandardCharsets.UTF_8), err -> {});
            } catch (Exception ignored) {}
        });
    }

    @Override protected void onServiceConnected() { inst = this; }
    @Override public boolean onUnbind(Intent i) { inst = null; return super.onUnbind(i); }
    @Override public void onDestroy() { inst = null; super.onDestroy(); }
    @Override public void onAccessibilityEvent(AccessibilityEvent e) {}
    @Override public void onInterrupt() {}

    private String shoot() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        String pkg = root == null || root.getPackageName() == null ? "" : root.getPackageName().toString();
        boolean camera = pkg.toLowerCase().contains("camera") || pkg.toLowerCase().contains("cam.") || pkg.contains("snowcorp") || pkg.contains("GoogleCamera");
        if (root != null) {
            AccessibilityNodeInfo n = find(root);
            if (n != null) {
                AccessibilityNodeInfo t = n;
                for (int i = 0; i < 4 && t != null && !t.isClickable(); i++) t = t.getParent();
                if (t != null && t.isClickable() && t.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return "찍음 (버튼: " + label(n) + ")";
                Rect b = new Rect(); n.getBoundsInScreen(b);
                if (!b.isEmpty() && tap(b.centerX(), b.centerY())) return "찍음 (버튼 자리 누름: " + label(n) + ")";
            }
        }
        if (camera) {
            DisplayMetrics m = getResources().getDisplayMetrics();
            int w = m.widthPixels, h = m.heightPixels;
            boolean ok = w < h ? tap(w / 2f, h * 0.86f) : tap(w * 0.88f, h / 2f);
            return ok ? "셔터 버튼을 못 찾아 셔터 자리를 눌렀어요 (" + pkg + ")" : "셔터를 누르지 못했어요 (" + pkg + ")";
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
    private static AccessibilityNodeInfo find(AccessibilityNodeInfo root) {
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
                for (String w : WORDS) if (all.contains(w)) { score = 2; break; }
                if (score > 0 && (all.contains("동영상") || all.contains("video") || all.contains("record") || all.contains("녹화") || all.contains("설정") || all.contains("setting"))) score = 0;
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
