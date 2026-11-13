package io.github.hugdoublek.rw6hub;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 워치 연결 하나를 앱 전체에서 같이 쓴다.
 * 연결 · 인증 · 자동 재연결 · 기록 · 받은 메시지 나눠 주기를 맡는다.
 */
public final class WatchLink {

    public interface StateListener { void onStateChanged(); }
    public interface PacketListener { void onPacket(int type, int id, List<Pb.Field> packet); }

    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final long[] BACKOFF_MS = {3_000, 10_000, 30_000, 60_000};

    private static WatchLink INSTANCE;

    public static synchronized WatchLink get(Context c) {
        if (INSTANCE == null) {
            INSTANCE = new WatchLink(c.getApplicationContext());
            QuickApps.get(c); // 퀵앱 메시지 처리 등록 (INSTANCE가 생긴 뒤에)
        }
        return INSTANCE;
    }

    private final Context app;
    private final SharedPreferences prefs;
    private final Handler worker;
    private final CopyOnWriteArrayList<StateListener> stateListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<PacketListener> packetListeners = new CopyOnWriteArrayList<>();
    private final StringBuilder log = new StringBuilder();

    private volatile WatchSession session;
    private volatile BluetoothSocket socket;
    private volatile boolean authed = false;
    private volatile boolean connecting = false;
    private volatile boolean wantConnected = false;
    private volatile String state = "연결 안 됨";
    private volatile String watchModel = "", watchFirmware = "", watchSerial = "";
    private int failCount = 0;
    private volatile long authedAt = 0;
    private final java.util.Set<String> seenKinds = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
    /** 워치가 오래 조용하면 연결을 닫는 경우에 대비해 가끔 가벼운 요청(기기 상태)을 보낸다 */
    private static final long KEEPALIVE_MS = 20_000;
    private final Runnable keepalive = new Runnable() {
        @Override public void run() {
            if (!authed) return;
            send(new Pb.W().varint(1, 2).varint(2, 1)); // SYSTEM / GET_DEVICE_STATUS
            worker.postDelayed(this, KEEPALIVE_MS);
        }
    };

    private WatchLink(Context app) {
        this.app = app;
        this.prefs = app.getSharedPreferences("hub", Context.MODE_PRIVATE);
        HandlerThread t = new HandlerThread("watch-link");
        t.start();
        worker = new Handler(t.getLooper());
    }

    // ---------------- 상태 ----------------

    public boolean isReady() { return authed; }
    public String state() { return state; }
    public String watchModel() { return watchModel; }
    public String watchFirmware() { return watchFirmware; }
    public String watchSerial() { return watchSerial; }
    public String address() { return prefs.getString("mac", ""); }
    public boolean wantsConnection() { return wantConnected; }

    public void addStateListener(StateListener l) { stateListeners.addIfAbsent(l); }
    public void removeStateListener(StateListener l) { stateListeners.remove(l); }
    public void addPacketListener(PacketListener l) { packetListeners.addIfAbsent(l); }

    public String logText() { synchronized (log) { return log.toString(); } }

    public void clearLog() {
        synchronized (log) { log.setLength(0); }
        changed();
    }

    public void log(String msg) {
        String line = new SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(new Date()) + " " + msg + "\n";
        synchronized (log) {
            log.append(line);
            if (log.length() > 30000) log.delete(0, log.length() - 22000);
        }
        changed();
    }

    private void setState(String s) {
        state = s;
        changed();
    }

    private void changed() {
        for (StateListener l : stateListeners) l.onStateChanged();
    }

    // ---------------- 연결 ----------------

    /** 연결을 유지하고 싶음 → 지금 연결하고, 끊기면 다시 붙는다. */
    public void start() {
        wantConnected = true;
        failCount = 0;
        worker.removeCallbacks(reconnect);
        worker.post(this::connectNow);
    }

    /** 연결을 놓는다 (Mi Fitness에 양보). 다시 start() 할 때까지 붙지 않음. */
    public void stop(String why) {
        wantConnected = false;
        worker.removeCallbacks(reconnect);
        WatchSession s = session;
        if (s != null) s.close();
        closeSocket();
        session = null;
        authed = false;
        setState("연결 안 됨 (" + why + ")");
    }

    private final Runnable reconnect = this::connectNow;

    private boolean hasBtPermission() {
        return Build.VERSION.SDK_INT < 31
                || app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressWarnings("MissingPermission")
    private void connectNow() {
        if (!wantConnected || session != null || connecting) return;
        String mac = prefs.getString("mac", "");
        String key = prefs.getString("authKey", "");
        if (mac.isEmpty() || key.isEmpty()) { setState("설정 필요 (워치·인증키)"); return; }
        if (!hasBtPermission()) { setState("블루투스 권한 필요"); return; }
        BluetoothManager bm = (BluetoothManager) app.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
        if (ad == null || !ad.isEnabled()) { setState("블루투스 꺼짐"); scheduleRetry(); return; }

        connecting = true;
        setState("연결 중…");
        BluetoothDevice dev = ad.getRemoteDevice(mac);
        try { ad.cancelDiscovery(); } catch (SecurityException ignored) {}
        BluetoothSocket sock;
        try {
            // 이 워치는 insecure 방식으로 붙음 (1단계 확인). 안 되면 secure도 시도
            try {
                sock = dev.createInsecureRfcommSocketToServiceRecord(SPP_UUID);
                sock.connect();
            } catch (Exception first) {
                sock = dev.createRfcommSocketToServiceRecord(SPP_UUID);
                sock.connect();
            }
        } catch (Exception e) {
            connecting = false;
            log("연결 실패: " + e.getMessage());
            setState("연결 안 됨 (다시 시도 예정)");
            scheduleRetry();
            return;
        }
        socket = sock;
        final WatchSession[] holder = new WatchSession[1];
        WatchSession s = new WatchSession(Hex.parse(key), Build.MODEL, new WatchSession.Callback() {
            @Override public void onLog(String msg) { log(msg); }

            @Override public void onAuthed() {
                authed = true;
                authedAt = System.currentTimeMillis();
                seenKinds.clear();
                worker.removeCallbacks(keepalive);
                worker.postDelayed(keepalive, KEEPALIVE_MS);
                failCount = 0;
                setState("연결됨");
                holder[0].requestDeviceInfo();
                QuickApps.get(app).onConnected();
            }

            @Override public void onDeviceInfo(String model, String fw, String serial) {
                watchModel = model;
                watchFirmware = fw;
                watchSerial = serial;
                changed();
            }

            @Override public void onClosed(String reason) {
                boolean wasAuthed = authed;
                authed = false;
                closeSocket();
                if (session == holder[0]) session = null;
                log("연결 끊김: " + reason + " [" + holder[0].stats() + ", 연결 " + (System.currentTimeMillis() - authedAt) / 1000 + "초]");
                log("  직전 프레임: " + holder[0].recentFrames());
                if (wantConnected) {
                    setState("끊김 (다시 연결 예정)");
                    if (wasAuthed) failCount = 0;
                    scheduleRetry();
                } else {
                    changed();
                }
            }

            @Override public void onPacket(int type, int id, List<Pb.Field> p) {
                // 처음 보는 종류는 한 번씩 기록 (끊김 원인 찾기용). 기기 상태 응답(2/1)은 조용히
                if (type != 20 && !(type == 2 && id == 1) && seenKinds.add(type + "/" + id)) {
                    log("워치 메시지 type=" + type + " id=" + id);
                }
                // 워치가 '공유 기기 목록'(23/2)을 물으면 빈 목록으로 답함 (Mi Fitness가 하던 응답)
                if (type == 23 && id == 2) {
                    send(new Pb.W().varint(1, 23).varint(2, 3).msg(25, new Pb.W().msg(2, new Pb.W())));
                }
                for (PacketListener l : packetListeners) {
                    try { l.onPacket(type, id, p); } catch (RuntimeException e) { log("메시지 처리 오류: " + e); }
                }
            }
        });
        holder[0] = s;
        session = s;
        connecting = false;
        setState("인증 중…");
        try {
            s.start(sock.getInputStream(), sock.getOutputStream());
        } catch (Exception e) {
            log("세션 시작 실패: " + e.getMessage());
            s.close();
        }
    }

    private void scheduleRetry() {
        if (!wantConnected) return;
        long d = BACKOFF_MS[Math.min(failCount, BACKOFF_MS.length - 1)];
        failCount++;
        worker.removeCallbacks(reconnect);
        worker.postDelayed(reconnect, d);
    }

    private void closeSocket() {
        BluetoothSocket s = socket;
        socket = null;
        if (s != null) try { s.close(); } catch (Exception ignored) {}
    }

    /** 인증된 연결로 메시지를 보낸다. 연결이 없으면 false. */
    public boolean send(Pb.W packet) {
        WatchSession s = session;
        if (s == null || !authed) return false;
        s.sendPacket(packet);
        return true;
    }
}
