package io.github.hugdoublek.rw6hub;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 워치 퀵앱과 메시지 주고받기 (WearPacket type 20 = THIRDPARTY_APP)와
 * 워치 알림 목록 띄우기 (type 7 = NOTIFICATION).
 * Mi Fitness가 하던 "폰 쪽 짝 앱" 역할을 Mi Manager 안의 기능들이 나눠 맡는다.
 */
public final class QuickApps implements WatchLink.PacketListener {

    public interface MessageListener { void onMessage(String pkg, byte[] data); }
    public interface ListCallback { void done(List<App> apps); }

    public static final class App {
        public final String pkg, name;
        public final byte[] fingerprint;
        public final int versionCode;
        public final boolean canRemove;
        App(String pkg, String name, byte[] fp, int vc, boolean rm) {
            this.pkg = pkg; this.name = name; this.fingerprint = fp; this.versionCode = vc; this.canRemove = rm;
        }
    }

    // WearPacket
    private static final int F_TYPE = 1, F_ID = 2, F_THIRDPARTY = 22, F_NOTIFICATION = 9;
    private static final int TYPE_NOTIFICATION = 7, TYPE_THIRDPARTY = 20;
    // ThirdpartyApp id
    private static final int GET_INSTALLED_LIST = 0, LAUNCH_APP = 4, REQUEST_PHONE_APP_STATUS = 6,
            SYNC_PHONE_APP_STATUS = 7, SEND_PHONE_MESSAGE = 8, SEND_WEAR_MESSAGE = 9;
    // ThirdpartyApp payload 필드
    private static final int P_APP_LIST = 1, P_BASIC_INFO = 5, P_LAUNCH_INFO = 6, P_APP_STATUS = 8, P_MESSAGE = 9;
    private static final int STATUS_CONNECTED = 1, STATUS_DISCONNECTED = 2;

    private static QuickApps INSTANCE;

    public static synchronized QuickApps get(Context c) {
        if (INSTANCE == null) {
            INSTANCE = new QuickApps(c.getApplicationContext());
            WatchLink.get(c).addPacketListener(INSTANCE);
        }
        return INSTANCE;
    }

    private final Context app;
    private final SharedPreferences fpStore;
    private final Map<String, CopyOnWriteArrayList<MessageListener>> listeners = new ConcurrentHashMap<>();
    private final Map<String, String> handled = new ConcurrentHashMap<>(); // 패키지 → 표시 이름
    private volatile List<App> installed = null;
    private final List<ListCallback> waitingList = new ArrayList<>();

    private QuickApps(Context app) {
        this.app = app;
        this.fpStore = app.getSharedPreferences("quickapp_fp", Context.MODE_PRIVATE);
    }

    private WatchLink link() { return WatchLink.get(app); }

    // ---------------- 등록 ----------------

    /** 이 퀵앱의 짝 역할을 Mi Manager가 맡는다고 알림 (워치가 물으면 "연결됨"으로 답함). */
    public void register(String pkg, String name) { handled.put(pkg, name); }

    // ---------------- 다른 이름표로 설치한 같은 퀵앱 (자리 빌리기) ----------------
    // 워치페이스 버튼은 워치에 원래 있던 퀵앱만 열 수 있다(예: 253번 = com.tencent.wechatrtos).
    // 그래서 우리 퀵앱을 그 이름표로 한 벌 더 만들어 설치하면 워치페이스에서 바로 열 수 있다.
    // 폰 쪽에서는 원래 이름표(primary)와 빌린 이름표(alt)를 같은 앱으로 다루고,
    // 마지막으로 말을 걸어 온 쪽(= 사용자가 실제로 쓰는 쪽)으로 메시지를 보낸다.
    private final Map<String, String> altOf = new ConcurrentHashMap<>();     // primary → alt
    private final Map<String, String> primaryOf = new ConcurrentHashMap<>(); // alt → primary

    public void registerAlias(String primary, String alt) {
        if (primary == null || alt == null || alt.isEmpty() || alt.equals(primary)) return;
        altOf.put(primary, alt);
        primaryOf.put(alt, primary);
    }

    /** 빌린 이름표의 퀵앱이 정말 우리 것인지: 서명 지문이 원래 앱과 같아야 한다(원래 워치에 있던 남의 앱과 구분). */
    private boolean aliasIsOurs(String alt, byte[] fp) {
        String primary = primaryOf.get(alt);
        if (primary == null) return false;
        byte[] mine = fingerprint(primary);
        if (mine != null && fp != null && fp.length > 0 && java.util.Arrays.equals(mine, fp)) return true;
        // 지문을 모르거나 다르게 계산되는 경우엔 버전으로 구분 (워치에 원래 있던 앱은 버전이 아주 낮음: 위챗 v2)
        List<App> list = installed;
        if (list != null) for (App a : list) if (a.pkg.equals(alt)) return a.versionCode >= 20;
        return false;
    }

    /** 우리가 맡는 퀵앱인가 (빌린 이름표면 지문까지 확인). */
    private boolean isOurs(String pkg, byte[] fp) {
        if (primaryOf.containsKey(pkg)) return aliasIsOurs(pkg, fp);
        return handled.containsKey(pkg);
    }

    private void heardFrom(String pkg) {
        String primary = primaryOf.containsKey(pkg) ? primaryOf.get(pkg) : (altOf.containsKey(pkg) ? pkg : null);
        if (primary == null) return;
        String key = "active:" + primary;
        if (!pkg.equals(fpStore.getString(key, null))) {
            fpStore.edit().putString(key, pkg).apply();
            link().log("퀵앱 " + shortName(primary) + " → 이제 " + pkg + " 쪽으로 보냄");
        }
    }

    /** 이 퀵앱에 보낼 때 실제로 쓸 이름표. */
    public String target(String primary) {
        String alt = altOf.get(primary);
        if (alt == null) return primary;
        String active = fpStore.getString("active:" + primary, primary);
        List<App> list = installed;
        if (list != null) {
            boolean hasP = false, hasA = false;
            for (App a : list) {
                if (a.pkg.equals(primary)) hasP = true;
                else if (a.pkg.equals(alt) && aliasIsOurs(alt, a.fingerprint)) hasA = true;
            }
            if (hasP && !hasA) return primary;
            if (hasA && !hasP) return alt;
            if (!hasP && !hasA) return primary;
        }
        if (active.equals(alt) && !aliasIsOurs(alt, fingerprint(alt))) return primary;
        return active;
    }

    public boolean isInstalledAny(String primary) {
        if (isInstalled(primary)) return true;
        String alt = altOf.get(primary);
        if (alt == null) return false;
        List<App> list = installed;
        if (list == null) return aliasIsOurs(alt, fingerprint(alt));
        for (App a : list) if (a.pkg.equals(alt) && aliasIsOurs(alt, a.fingerprint)) return true;
        return false;
    }

    public void addListener(String pkg, MessageListener l) {
        listeners.computeIfAbsent(pkg, k -> new CopyOnWriteArrayList<>()).addIfAbsent(l);
    }

    public void removeListeners(String pkg) { listeners.remove(pkg); }

    public void onConnected() {
        refreshInstalled(null);
        // 우리가 맡은 퀵앱들에 "폰 앱 연결됨"을 먼저 알려 둔다
        for (String pkg : handled.keySet()) {
            byte[] fp = fingerprint(pkg);
            if (fp != null) syncStatus(pkg, fp, STATUS_CONNECTED);
        }
        for (String alt : primaryOf.keySet()) {
            byte[] fp = fingerprint(alt);
            if (fp != null && aliasIsOurs(alt, fp)) syncStatus(alt, fp, STATUS_CONNECTED);
        }
    }

    // ---------------- 보내기 ----------------

    public byte[] fingerprint(String pkg) {
        String hex = fpStore.getString(pkg, null);
        return hex == null ? null : Hex.parse(hex);
    }

    private void rememberFingerprint(String pkg, byte[] fp) {
        if (pkg == null || pkg.isEmpty() || fp == null || fp.length == 0) return;
        String hex = Hex.of(fp);
        if (!hex.equals(fpStore.getString(pkg, null))) fpStore.edit().putString(pkg, hex).apply();
    }

    private static Pb.W basicInfo(String pkg, byte[] fp) {
        return new Pb.W().str(1, pkg).bytes(2, fp == null ? new byte[0] : fp);
    }

    private static Pb.W thirdparty(int id, int field, Pb.W payload) {
        return new Pb.W().varint(F_TYPE, TYPE_THIRDPARTY).varint(F_ID, id)
                .msg(F_THIRDPARTY, new Pb.W().msg(field, payload));
    }

    /** 지문(fingerprint)을 모르면 설치 목록을 받아 온 뒤 보낸다. 결과: null = 성공, 아니면 오류 문구. */
    public interface Done { void done(String error); }
    /** 퀵앱이 폰 앱 상태를 물을 때(= 워치에서 그 앱이 막 켜졌을 때) 불림. */
    public interface AskListener { void asked(String primaryPkg); }
    private final java.util.concurrent.ConcurrentHashMap<String, AskListener> askListeners = new java.util.concurrent.ConcurrentHashMap<>();
    public void setAskListener(String primaryPkg, AskListener l) { askListeners.put(primaryPkg, l); }

    private void withFingerprint(String pkg, Done onError, java.util.function.Consumer<byte[]> then) {
        byte[] fp = fingerprint(pkg);
        if (fp != null) { then.accept(fp); return; }
        refreshInstalled(apps -> {
            byte[] f = fingerprint(pkg);
            if (f != null) then.accept(f);
            else onError.done("워치에 이 퀵앱이 없음 (" + pkg + ")");
        });
    }

    public void sendMessage(String pkg, byte[] data, Done cb) {
        if (!link().isReady()) { cb.done("워치가 연결되어 있지 않음"); return; }
        withFingerprint(pkg, cb, fp -> {
            Pb.W msg = new Pb.W().msg(1, basicInfo(pkg, fp)).bytes(2, data);
            // 워치가 받았다고 확인할 때 성공으로 알림 (예전 Mi Fitness와 같음 → 진행률이 실제 전송을 따라감)
            boolean queued = link().send(thirdparty(SEND_PHONE_MESSAGE, P_MESSAGE, msg),
                    ok -> cb.done(ok ? null : "전송 중 연결이 끊김"));
            if (!queued) cb.done("워치가 연결되어 있지 않음");
        });
    }

    public void launch(String pkg, String uri, Done cb) {
        if (!link().isReady()) { cb.done("워치가 연결되어 있지 않음"); return; }
        withFingerprint(pkg, cb, fp -> {
            Pb.W info = new Pb.W().msg(1, basicInfo(pkg, fp)).str(2, uri == null ? "" : uri);
            cb.done(link().send(thirdparty(LAUNCH_APP, P_LAUNCH_INFO, info)) ? null : "워치가 연결되어 있지 않음");
        });
    }

    /** 워치에서 퀵앱 지우기 */
    public void remove(String pkg, Done cb) {
        if (!link().isReady()) { cb.done("워치가 연결되어 있지 않음"); return; }
        withFingerprint(pkg, cb, fp -> {
            boolean ok = link().send(thirdparty(3, P_BASIC_INFO, basicInfo(pkg, fp)), d -> {
                cb.done(d ? null : "전송 중 연결이 끊김");
                refreshInstalled(null);
            });
            if (!ok) cb.done("워치가 연결되어 있지 않음");
        });
    }

    private void syncStatus(String pkg, byte[] fp, int status) {
        Pb.W st = new Pb.W().msg(1, basicInfo(pkg, fp)).varint(2, status);
        link().send(thirdparty(SYNC_PHONE_APP_STATUS, P_APP_STATUS, st));
    }

    /** 워치에 설치된 퀵앱 목록을 새로 받는다 (cb는 null 가능, 실패하면 빈 목록). */
    public void refreshInstalled(ListCallback cb) {
        synchronized (waitingList) {
            if (cb != null) waitingList.add(cb);
        }
        boolean sent = link().send(new Pb.W().varint(F_TYPE, TYPE_THIRDPARTY).varint(F_ID, GET_INSTALLED_LIST));
        if (!sent) { finishList(Collections.emptyList()); return; }
        // 5초 안에 답이 없으면 있는 것으로 끝냄
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            List<App> cur = installed;
            finishList(cur == null ? Collections.emptyList() : cur);
        }, 5000);
    }

    private void finishList(List<App> apps) {
        List<ListCallback> cbs;
        synchronized (waitingList) {
            cbs = new ArrayList<>(waitingList);
            waitingList.clear();
        }
        for (ListCallback c : cbs) c.done(apps);
    }

    public List<App> installed() { return installed; }

    public boolean isInstalled(String pkg) {
        List<App> list = installed;
        if (list == null) return fingerprint(pkg) != null;
        for (App a : list) if (a.pkg.equals(pkg)) return true;
        return false;
    }

    /**
     * 워치 알림 목록에 한 줄 띄운다 (Mi Fitness의 sendNotify 대신).
     * appId는 퀵앱 패키지 이름을 쓴다.
     */
    public boolean notify(String appId, String appName, String title, String text) {
        int uid = (int) (System.currentTimeMillis() & 0x7FFFFFFF);
        String date = new SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US).format(new Date());
        Pb.W data = new Pb.W()
                .str(1, appId).str(2, appName).str(3, title).str(4, "").str(5, text).str(6, date)
                .varint(7, uid).str(9, "").str(10, "").bool(11, false)
                .str(12, appId + "|" + uid).bool(13, false);
        Pb.W packet = new Pb.W().varint(F_TYPE, TYPE_NOTIFICATION).varint(F_ID, 0)
                .msg(F_NOTIFICATION, new Pb.W().msg(1, data));
        return link().send(packet);
    }

    // ---------------- 받기 ----------------

    @Override
    public void onPacket(int type, int id, List<Pb.Field> packet) {
        if (type != TYPE_THIRDPARTY) return;
        List<Pb.Field> tp = Pb.sub(packet, F_THIRDPARTY);
        if (tp == null) tp = Collections.emptyList();
        switch (id) {
            case REQUEST_PHONE_APP_STATUS: {
                List<Pb.Field> bi = Pb.sub(tp, P_BASIC_INFO);
                if (bi == null) return;
                String pkg = str(bi, 1);
                byte[] fp = bytes(bi, 2);
                rememberFingerprint(pkg, fp);
                boolean ours = isOurs(pkg, fp);
                if (ours) heardFrom(pkg);
                syncStatus(pkg, fp, ours ? STATUS_CONNECTED : STATUS_DISCONNECTED);
                link().log("퀵앱 " + shortName(pkg) + " 이 폰 앱 상태를 물음 → " + (ours ? "연결됨" : "맡지 않음"));
                if (ours) { // 퀵앱이 막 켜졌다는 가장 이른 신호 → 듣는 쪽에 알림 (메신저 알림: '보낼 것 없음'을 미리 답함)
                    String prim = primaryOf.containsKey(pkg) ? primaryOf.get(pkg) : pkg;
                    AskListener al = askListeners.get(prim);
                    if (al != null) { try { al.asked(prim); } catch (Exception ignored) {} }
                }
                break;
            }
            case SEND_WEAR_MESSAGE: {
                List<Pb.Field> mc = Pb.sub(tp, P_MESSAGE);
                if (mc == null) return;
                List<Pb.Field> bi = Pb.sub(mc, 1);
                String pkg = bi == null ? "" : str(bi, 1);
                if (bi != null) rememberFingerprint(pkg, bytes(bi, 2));
                byte[] data = bytes(mc, 2);
                String listenKey = pkg;
                if (primaryOf.containsKey(pkg)) {
                    // 빌린 이름표로 설치한 우리 퀵앱이면 원래 이름표의 받는 곳으로 넘긴다
                    if (bi != null && aliasIsOurs(pkg, bytes(bi, 2))) { listenKey = primaryOf.get(pkg); heardFrom(pkg); }
                } else if (altOf.containsKey(pkg)) heardFrom(pkg);
                CopyOnWriteArrayList<MessageListener> ls = listeners.get(listenKey);
                if (ls == null || ls.isEmpty()) {
                    link().log("퀵앱 " + shortName(pkg) + " 메시지 (" + data.length + "B) → 받을 곳 없음");
                    return;
                }
                for (MessageListener l : ls) l.onMessage(pkg, data);
                break;
            }
            case GET_INSTALLED_LIST: {
                List<Pb.Field> list = Pb.sub(tp, P_APP_LIST);
                if (list == null) return;
                Map<String, App> apps = new LinkedHashMap<>();
                for (Pb.Field f : list) {
                    if (f.number != 1 || f.bytes == null) continue;
                    List<Pb.Field> it = Pb.parse(f.bytes);
                    Pb.Field vc = Pb.find(it, 3), rm = Pb.find(it, 4);
                    App a = new App(str(it, 1), str(it, 5), bytes(it, 2),
                            vc == null ? 0 : (int) vc.varint, rm != null && rm.varint != 0);
                    rememberFingerprint(a.pkg, a.fingerprint);
                    apps.put(a.pkg, a);
                }
                installed = new ArrayList<>(apps.values());
                link().log("워치 퀵앱 " + installed.size() + "개 확인");
                finishList(installed);
                break;
            }
            default:
                link().log("퀵앱 메시지 id=" + id + " (처리 안 함)");
        }
    }

    private static String shortName(String pkg) {
        int i = pkg.lastIndexOf('.');
        return i >= 0 ? pkg.substring(i + 1) : pkg;
    }

    private static String str(List<Pb.Field> f, int n) {
        Pb.Field x = Pb.find(f, n);
        return x == null ? "" : x.str();
    }

    private static byte[] bytes(List<Pb.Field> f, int n) {
        Pb.Field x = Pb.find(f, n);
        return x == null || x.bytes == null ? new byte[0] : x.bytes;
    }
}
