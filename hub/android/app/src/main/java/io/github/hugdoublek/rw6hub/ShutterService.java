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

    private static final String[] TAB_VIDEO = {"동영상", "video", "비디오"};
    private static final String[] TAB_PHOTO = {"사진", "photo", "picture"};
    private static final String[] NOT_TAB = {"촬영", "녹화", "record", "설정", "setting", "크기", "size", "해상도", "화질", "보기", "갤러리", "gallery", "편집", "필터"};

    /** 카메라 아래쪽 모드 줄('사진 | 동영상 | 더보기')에서 그 모드를 눌러 바꿈. 눌렀으면 이름, 못 찾으면 null */
    private String tab(String[] names) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return null;
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);
        AccessibilityNodeInfo best = null;
        int bestScore = 0, seen = 0;
        while (!q.isEmpty() && seen++ < 800) {
            AccessibilityNodeInfo n = q.poll();
            if (n == null) continue;
            if (n.isVisibleToUser()) {
                String d = n.getContentDescription() == null ? "" : n.getContentDescription().toString().trim().toLowerCase(),
                        t = n.getText() == null ? "" : n.getText().toString().trim().toLowerCase();
                int score = 0;
                for (String w : names) {
                    if (t.equals(w) || d.equals(w)) score = Math.max(score, 4);
                    else if ((t.startsWith(w) && t.length() <= 14) || (d.startsWith(w) && d.length() <= 14)) score = Math.max(score, 2);
                }
                if (score > 0) for (String w : NOT_TAB) if (t.contains(w) || d.contains(w)) { score = 0; break; }
                if (score > 0 && n.isClickable()) score++;
                if (score > bestScore) { bestScore = score; best = n; }
            }
            for (int i = 0; i < n.getChildCount(); i++) q.add(n.getChild(i));
        }
        if (best == null) return null;
        Rect b = new Rect(); best.getBoundsInScreen(b);
        // 모드 줄은 글자를 직접 톡 눌러야 바뀌는 경우가 많아 자리 누르기를 먼저, 안 되면 클릭 동작
        if (!b.isEmpty() && tap(b.centerX(), b.centerY())) return label(best);
        AccessibilityNodeInfo t = best;
        for (int i = 0; i < 3 && t != null && !t.isClickable(); i++) t = t.getParent();
        if (t != null && t.isClickable() && t.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return label(best);
        return null;
    }

    /** 가운데 셔터 버튼(삼성: normal_center_button 등 …center_button)과 그 설명. 없으면 null */
    private AccessibilityNodeInfo center() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return null;
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);
        int seen = 0;
        while (!q.isEmpty() && seen++ < 800) {
            AccessibilityNodeInfo n = q.poll();
            if (n == null) continue;
            String id = n.getViewIdResourceName();
            if (id != null && id.contains("center_button") && n.isVisibleToUser()) return n;
            for (int i = 0; i < n.getChildCount(); i++) q.add(n.getChild(i));
        }
        return null;
    }

    private String centerDesc() {
        AccessibilityNodeInfo n = center();
        return n == null ? "" : label(n).toLowerCase();
    }

    private static boolean videoWords(String d) { return d.contains("동영상") || d.contains("녹화") || d.contains("video") || d.contains("record"); }

    /** 미리보기 화면을 옆으로 밀어 모드 바꾸기 (next: 사진→동영상 쪽) */
    private boolean swipe(boolean next) {
        DisplayMetrics m = getResources().getDisplayMetrics();
        int w = m.widthPixels, h = m.heightPixels;
        Path p = new Path();
        if (w < h) { p.moveTo(w * (next ? 0.78f : 0.22f), h * 0.45f); p.lineTo(w * (next ? 0.22f : 0.78f), h * 0.45f); }
        else { p.moveTo(w * 0.45f, h * (next ? 0.22f : 0.78f)); p.lineTo(w * 0.45f, h * (next ? 0.78f : 0.22f)); }
        return dispatchGesture(new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(p, 0, 160)).build(), null, null);
    }

    /** 기록용: 화면에 보이는 모든 글자·설명 (누를 수 없는 것 포함) */
    private String dumpAll() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return "(화면을 읽지 못함)";
        StringBuilder sb = new StringBuilder();
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);
        int seen = 0, got = 0;
        while (!q.isEmpty() && seen++ < 800 && got < 45) {
            AccessibilityNodeInfo n = q.poll();
            if (n == null) continue;
            if (n.isVisibleToUser()) {
                String t = n.getText() == null ? "" : n.getText().toString().trim();
                String l = (t.isEmpty() ? "" : "'" + t + "' ") + label(n).trim();
                if (!l.trim().isEmpty()) sb.append(got++ == 0 ? "" : " / ").append(l.trim()).append(n.isClickable() ? "" : "(x)");
            }
            for (int i = 0; i < n.getChildCount(); i++) q.add(n.getChild(i));
        }
        return sb.length() == 0 ? "(글자 없음)" : sb.toString();
    }

    private boolean clickCenter() {
        AccessibilityNodeInfo n = center();
        if (n != null) {
            if (n.isClickable() && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
            Rect b = new Rect(); n.getBoundsInScreen(b);
            if (!b.isEmpty() && tap(b.centerX(), b.centerY())) return true;
        }
        return tapShutterSpot();
    }

    private void startRecord(Context app, String how) {
        if (!clickCenter()) { reply(app, "녹화 버튼을 못 눌렀어요 화면: " + dump(), "녹화를 시작하지 못했어요"); return; }
        video = 1;
        reply(app, "녹화 시작 (모드 바꾼 방법: " + how + ")", "녹화 중");
        // 확인용: 1.5초 뒤 화면의 버튼 이름을 기록 (끝·일시정지 버튼 이름을 알아내기 위해)
        MAIN.postDelayed(() -> { try { WatchLink.get(app).log("워치 셔터: 녹화 중 화면: " + dumpAll()); } catch (Exception ignored) {} }, 1500);
    }

    /** 화면에서 아이디에 key가 든 보이는 것들 */
    private java.util.ArrayList<AccessibilityNodeInfo> byId(String key) {
        java.util.ArrayList<AccessibilityNodeInfo> out = new java.util.ArrayList<>();
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return out;
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);
        int seen = 0;
        while (!q.isEmpty() && seen++ < 800) {
            AccessibilityNodeInfo n = q.poll();
            if (n == null) continue;
            String id = n.getViewIdResourceName();
            if (id != null && id.contains(key) && n.isVisibleToUser()) out.add(n);
            for (int i = 0; i < n.getChildCount(); i++) q.add(n.getChild(i));
        }
        return out;
    }

    /** 지금 카메라 모드 이름 (삼성: shooting_mode_list 의 설명 "사진, 모드" → "사진"). 모르면 "" */
    private String mode() {
        for (AccessibilityNodeInfo n : byId("shooting_mode_list")) {
            CharSequence d = n.getContentDescription();
            if (d == null) continue;
            String t = d.toString();
            int c = t.indexOf(',');
            return (c > 0 ? t.substring(0, c) : t).trim();
        }
        return "";
    }

    private static boolean isVideoMode(String m) { String t = m.toLowerCase(); return t.equals("동영상") || t.equals("video") || t.equals("비디오"); }
    private static boolean isPhotoMode(String m) { String t = m.toLowerCase(); return t.equals("사진") || t.equals("photo") || t.equals("picture"); }

    /** 모드 줄에서 지금 고른 것(화면 가운데에 가장 가까운 것)의 옆 칸을 톡 누름. right: 사진→동영상 쪽 */
    private boolean tapNeighbor(boolean right) {
        java.util.ArrayList<AccessibilityNodeInfo> items = byId("shooting_mode_item");
        if (items.size() < 2) return false;
        final java.util.ArrayList<Rect> rs = new java.util.ArrayList<>();
        for (AccessibilityNodeInfo n : items) { Rect r = new Rect(); n.getBoundsInScreen(r); if (!r.isEmpty()) rs.add(r); }
        if (rs.size() < 2) return false;
        int minX = Integer.MAX_VALUE, maxX = 0, minY = Integer.MAX_VALUE, maxY = 0;
        for (Rect r : rs) { minX = Math.min(minX, r.centerX()); maxX = Math.max(maxX, r.centerX()); minY = Math.min(minY, r.centerY()); maxY = Math.max(maxY, r.centerY()); }
        final boolean horiz = (maxX - minX) >= (maxY - minY);
        java.util.Collections.sort(rs, (x, y) -> horiz ? x.centerX() - y.centerX() : x.centerY() - y.centerY());
        DisplayMetrics m = getResources().getDisplayMetrics();
        int mid = horiz ? m.widthPixels / 2 : m.heightPixels / 2, cur = 0, best = Integer.MAX_VALUE;
        for (int i = 0; i < rs.size(); i++) { int d = Math.abs((horiz ? rs.get(i).centerX() : rs.get(i).centerY()) - mid); if (d < best) { best = d; cur = i; } }
        int to = cur + (right ? 1 : -1);
        if (to < 0 || to >= rs.size()) return false;
        return tap(rs.get(to).centerX(), rs.get(to).centerY());
    }

    private interface Done { void run(boolean ok, String path); }

    /** 모드 줄을 한 칸씩 눌러 가며 원하는 모드(동영상/사진)까지 감. 모드 이름을 읽어 확인하므로 엉뚱한 데서 멈추지 않음 */
    private void goMode(boolean wantVideo, int step, String path, Done done) {
        String m = mode();
        String p2 = path + (path.isEmpty() ? "" : "→") + (m.isEmpty() ? "?" : m);
        if (wantVideo ? isVideoMode(m) : isPhotoMode(m)) { done.run(true, p2); return; }
        if (step >= 4 || m.isEmpty()) { done.run(false, p2); return; }
        // 삼성 모드 줄 순서: 싱글테이크 | 사진 | 동영상 | 더보기
        boolean right = wantVideo ? (isPhotoMode(m) || m.contains("싱글") || m.toLowerCase().contains("single")) : !(isVideoMode(m) || m.contains("더보기"));
        if (!wantVideo) right = !isVideoMode(m) && (m.contains("싱글") || m.toLowerCase().contains("single"));
        if (!tapNeighbor(right)) { done.run(false, p2 + "(옆 칸 못 누름)"); return; }
        MAIN.postDelayed(() -> { try { goMode(wantVideo, step + 1, p2, done); } catch (Exception e) { done.run(false, p2 + " " + e); } }, 1200);
    }

    private void videoToggle(Context app) {
        if (video != 0) {   // 녹화 끝
            String b = press(STOP, NOT_STOP);
            if (b == null && isCamera(pkg()) && clickCenter()) b = "가운데 버튼";
            video = 0;
            if (b == null) { reply(app, "녹화 끝 버튼을 못 눌렀어요 (" + pkg() + ") 화면: " + dumpAll(), "끝내지 못했어요"); return; }
            reply(app, "녹화 끝 (버튼: " + b + ")", "녹화를 끝냈어요");
            MAIN.postDelayed(() -> { try { goMode(false, 0, "", (ok, path) -> WatchLink.get(app).log("워치 셔터: 사진 모드로 " + (ok ? "돌림" : "못 돌림") + " (" + path + ")")); } catch (Exception ignored) {} }, 1800);
            return;
        }
        long wait = 0;
        if (!isCamera(pkg())) {
            try { startActivity(new Intent("android.media.action.STILL_IMAGE_CAMERA").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
            catch (Exception e) { reply(app, "카메라를 열지 못했어요 (" + e.getClass().getSimpleName() + ")", "카메라를 못 열었어요"); return; }
            wait = 2200;
        }
        MAIN.postDelayed(() -> {
            try {
                String p = pkg();
                if (!isCamera(p)) { reply(app, "카메라 화면이 아니에요 (" + p + ")", "폰에서 카메라를 열어 주세요"); return; }
                goMode(true, 0, "", (ok, path) -> {
                    if (ok) startRecord(app, path);
                    else reply(app, "동영상 모드로 못 바꿨어요 (" + path + ") 화면: " + dumpAll(), "동영상 모드로 못 바꿨어요");
                });
            } catch (Exception e) { reply(app, "실패 " + e, "실패했어요"); }
        }, wait);
    }

    private void pauseToggle(Context app) {
        if (video == 0) { reply(app, "녹화 중이 아니에요", "녹화 중이 아니에요"); return; }
        boolean toPause = video == 1;
        String b = press(toPause ? PAUSE : RESUME, NOT_BTN);
        if (b == null) b = press(toPause ? RESUME : PAUSE, NOT_BTN);   // 버튼 이름이 안 바뀌는 카메라 대비
        if (b == null) { reply(app, (toPause ? "일시정지" : "다시 촬영") + " 버튼을 못 찾았어요 (" + pkg() + ") 화면: " + dumpAll(), "버튼을 못 찾았어요"); return; }
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
