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
    private boolean callWait = false;
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
        watchAclEvents();
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
    /**
     * 진단: 블루투스 링크 자체(ACL)가 끊기는지, 우리 소켓만 닫히는지 기록.
     * 소켓만 닫히면(ACL 유지) 같은 폰의 다른 앱(Mi Fitness 등)이 워치를 가져갔을 가능성이 큼.
     */
    private void watchAclEvents() {
        android.content.IntentFilter f = new android.content.IntentFilter();
        f.addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED);
        f.addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED);
        f.addAction("android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED");
        android.content.BroadcastReceiver r = new android.content.BroadcastReceiver() {
            @Override public void onReceive(Context c, android.content.Intent i) {
                BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (d == null || !d.getAddress().equalsIgnoreCase(prefs.getString("mac", ""))) return;
                String a = i.getAction();
                if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(a)) log("  [블루투스 링크 연결됨]");
                else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(a)) log("  [블루투스 링크 자체가 끊김]");
                else {
                    int st = i.getIntExtra("android.bluetooth.profile.extra.STATE", -1);
                    log("  [워치 통화(HFP) " + (st == 2 ? "연결됨" : st == 0 ? "끊김" : "상태 " + st) + "]");
                }
            }
        };
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(r, f, Context.RECEIVER_EXPORTED);
        else app.registerReceiver(r, f);
    }

    /** 통화 중인지 (권한 없이 오디오 모드로 판단) */
    private boolean inCall() {
        android.media.AudioManager am = (android.media.AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return false;
        int m = am.getMode();
        return m == android.media.AudioManager.MODE_IN_CALL || m == android.media.AudioManager.MODE_IN_COMMUNICATION
                || m == android.media.AudioManager.MODE_RINGTONE;
    }

    private void connectNow() {
        if (!wantConnected || session != null || connecting) return;
        String mac = prefs.getString("mac", "");
        String key = prefs.getString("authKey", "");
        if (mac.isEmpty() || key.isEmpty()) { setState("설정 필요 (워치·인증키)"); log("연결 못 함: " + "설정 필요 (워치·인증키)"); return; }
        if (!hasBtPermission()) { setState("블루투스 권한 필요"); log("연결 못 함: " + "블루투스 권한 필요"); return; }
        BluetoothManager bm = (BluetoothManager) app.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
        if (ad == null || !ad.isEnabled()) { setState("블루투스 꺼짐"); log("연결 못 함: " + "블루투스 꺼짐"); scheduleRetry(); return; }

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
                send(new Pb.W().varint(1, 2).varint(2, 1)); // 배터리 등 기기 상태
                syncTime();
                sendLanguage(prefs.getString("locale", "ko_KR"));
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

            @Override public void onRaw(int channel, int opcode, byte[] body) {
                AivsCapture.addRaw(channel, body);
                if (seenKinds.add("raw" + channel)) log("워치 채널 " + channel + " 데이터 받기 시작 (" + body.length + "B, op " + opcode + ")");
            }

            @Override public void onPlainPacket(byte[] plain) {
                // 샤오아이(AIVS = type 14) 패킷만 캡처
                if (plain.length >= 2 && (plain[0] & 0xFF) == 0x08 && (plain[1] & 0xFF) == 14) AivsCapture.addPb(plain);
            }

            @Override public void onPacket(int type, int id, List<Pb.Field> p) {
                if (type == 14) log("샤오아이 메시지 id=" + id);
                if (type == 2 && id == 1) {
                    List<Pb.Field> sys = Pb.sub(p, 4);
                    List<Pb.Field> st = sys == null ? null : Pb.sub(sys, 2);
                    List<Pb.Field> bat = st == null ? null : Pb.sub(st, 1);
                    Pb.Field cap = bat == null ? null : Pb.find(bat, 1);
                    if (cap != null && cap.varint != battery) { battery = (int) cap.varint; changed(); }
                }
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

    /** 대용량 전송(채널 2 등)용. 연결이 없으면 false. */
    public boolean sendRaw(int channel, byte[] payload, SarLink.Delivery cb) {
        WatchSession s = session;
        if (s == null || !authed) return false;
        s.sendRaw(channel, 1, payload, cb);
        return true;
    }

    /** 워치 표시 언어 설정 (Mi Fitness가 연결할 때마다 폰 언어로 맞추던 것). 예: ko_KR, zh_CN, en_US */
    public void sendLanguage(String locale) {
        if (send(new Pb.W().varint(1, 2).varint(2, 6).msg(4, new Pb.W().msg(20, new Pb.W().str(1, locale))))) {
            log("워치 언어 설정: " + locale);
        }
    }

    /** 폰 시각·시간대를 워치에 맞춘다 */
    public void syncTime() {
        // 기본 시간대: 서울 (사용자 위치 광진구 구의1동). 설정 "tz"로 바꿀 수 있음
        java.util.TimeZone tz = java.util.TimeZone.getTimeZone(prefs.getString("tz", "Asia/Seoul"));
        java.util.Calendar c = java.util.Calendar.getInstance(tz);
        // 시간대 오프셋은 15분 단위, sint32(지그재그)로 보내야 함. 예전엔 일반 정수로 보내서
        // 워치가 +4.5시간으로 읽었음 (시간이 엉뚱하게 표시되던 원인)
        int offsetQuarters = tz.getRawOffset() / (15 * 60 * 1000);
        int dstQuarters = (tz.inDaylightTime(c.getTime()) ? tz.getDSTSavings() : 0) / (15 * 60 * 1000);
        boolean h12 = !android.text.format.DateFormat.is24HourFormat(app);
        Pb.W st = new Pb.W()
                .msg(1, new Pb.W().varint(1, c.get(java.util.Calendar.YEAR)).varint(2, c.get(java.util.Calendar.MONTH) + 1)
                        .varint(3, c.get(java.util.Calendar.DAY_OF_MONTH)))
                .msg(2, new Pb.W().varint(1, c.get(java.util.Calendar.HOUR_OF_DAY)).varint(2, c.get(java.util.Calendar.MINUTE))
                        .varint(3, c.get(java.util.Calendar.SECOND)).varint(4, c.get(java.util.Calendar.MILLISECOND)))
                .msg(3, new Pb.W().sint(1, offsetQuarters).sint(2, dstQuarters).str(3, tz.getID()).str(4, ""))
                .bool(4, h12);
        if (send(new Pb.W().varint(1, 2).varint(2, 3).msg(4, new Pb.W().msg(4, st)))) {
            log(String.format(java.util.Locale.US, "워치 시간 맞춤: %02d:%02d:%02d (%s, UTC%+d)",
                    c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE), c.get(java.util.Calendar.SECOND),
                    tz.getID(), tz.getRawOffset() / 3600000));
        }
    }

    /** 워치 배터리(%) — 연결 유지 신호의 응답에서 읽음. 모르면 -1 */
    public volatile int battery = -1;

    /** 인증된 연결로 메시지를 보낸다. 연결이 없으면 false. */
    public boolean send(Pb.W packet) { return send(packet, null); }

    /** cb는 워치가 받았다고 확인하면 true, 끊겨서 못 보내면 false (보내지 못하면 즉시 false 반환, cb는 안 부름). */
    public boolean send(Pb.W packet, SarLink.Delivery cb) {
        WatchSession s = session;
        if (s == null || !authed) return false;
        s.sendPacket(packet, cb);
        return true;
    }
}
