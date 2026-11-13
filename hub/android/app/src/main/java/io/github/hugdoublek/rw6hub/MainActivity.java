package io.github.hugdoublek.rw6hub;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** 1단계 화면: 샤오미 로그인 → 인증키 → SPP 연결·인증 → 워치 정보 확인. */
public class MainActivity extends Activity {

    private static final int REQ_LOGIN = 1, REQ_PERM = 2;
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    // 화면이 다시 만들어져도 연결은 유지 (2단계에서 포그라운드 서비스로 옮길 예정)
    private static WatchSession session;
    private static BluetoothSocket socket;
    private static String state = "연결 안 됨";
    private static String watchInfo = "";
    private static final StringBuilder LOG = new StringBuilder();
    private static MainActivity current;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private TextView accountView, keyView, addrView, stateView, infoView, logView;
    private Button connectBtn;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        current = this;
        prefs = getSharedPreferences("hub", MODE_PRIVATE);
        if (prefs.getString("deviceId", "").isEmpty()) {
            prefs.edit().putString("deviceId", "an_" + Hex.of(MiCrypto.random(16))).apply();
        }
        buildUi();
        refresh();
        askPermissions();
    }

    @Override
    protected void onDestroy() {
        if (current == this) current = null;
        super.onDestroy();
    }

    // ---------------- 화면 ----------------

    private void buildUi() {
        int pad = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Mi Manager " + version());
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        root.addView(header("계정 (샤오미, 중국 지역)"));
        accountView = text();
        root.addView(accountView);
        root.addView(button("샤오미 로그인 (아이디·비밀번호)", v -> passwordLogin()));
        root.addView(button("웹 화면으로 로그인", v -> startLogin(null)));
        root.addView(button("인증키 가져오기", v -> fetchKey()));

        root.addView(header("워치"));
        addrView = text();
        root.addView(addrView);
        root.addView(button("페어링된 기기에서 선택", v -> pickDevice()));
        keyView = text();
        root.addView(keyView);
        root.addView(button("인증키 직접 입력", v -> enterKey()));

        root.addView(header("연결"));
        stateView = text();
        stateView.setTextSize(17);
        root.addView(stateView);
        infoView = text();
        root.addView(infoView);
        connectBtn = button("연결", v -> connect());
        root.addView(connectBtn);
        root.addView(button("연결 해제 · Mi Fitness에 양보", v -> yieldToMiFitness()));

        root.addView(header("기록"));
        logView = text();
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextSize(12);
        logView.setTextIsSelectable(true);
        root.addView(logView);
        root.addView(button("기록 지우기", v -> { synchronized (LOG) { LOG.setLength(0); } refresh(); }));

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);
    }

    private void refresh() {
        String user = prefs.getString("userId", "");
        accountView.setText(user.isEmpty() ? "로그인 안 됨" : "로그인됨 (userId " + user + ")");
        String addr = prefs.getString("mac", "");
        addrView.setText(addr.isEmpty() ? "워치 주소: 선택 안 됨" : "워치 주소: " + addr);
        String key = prefs.getString("authKey", "");
        keyView.setText(key.isEmpty() ? "인증키: 없음" : "인증키: " + key.substring(0, 4) + "…" + key.substring(28) + " (32자리)");
        stateView.setText("상태: " + state);
        infoView.setText(watchInfo);
        boolean busy = session != null;
        connectBtn.setEnabled(!busy);
        synchronized (LOG) { logView.setText(LOG); }
    }

    private static void log(String msg) {
        String line = new SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(new Date()) + " " + msg + "\n";
        synchronized (LOG) {
            LOG.append(line);
            if (LOG.length() > 20000) LOG.delete(0, LOG.length() - 15000);
        }
        post();
    }

    private static void post() {
        MainActivity a = current;
        if (a != null) a.ui.post(a::refresh);
    }

    // ---------------- 계정 ----------------

    private void startLogin(String url) {
        Intent i = new Intent(this, LoginActivity.class);
        i.putExtra(LoginActivity.EXTRA_DEVICE_ID, prefs.getString("deviceId", ""));
        if (url != null) i.putExtra(LoginActivity.EXTRA_URL, url);
        startActivityForResult(i, REQ_LOGIN);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_LOGIN && res == RESULT_OK && data != null) {
            prefs.edit()
                    .putString("userId", data.getStringExtra(LoginActivity.EXTRA_USER_ID))
                    .putString("passToken", data.getStringExtra(LoginActivity.EXTRA_PASS_TOKEN))
                    .putString("cUserId", nz(data.getStringExtra(LoginActivity.EXTRA_CUSER_ID)))
                    .apply();
            log("로그인 완료");
            fetchKey();
        }
    }

    private void passwordLogin() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        EditText id = new EditText(this);
        id.setHint("샤오미 계정 (이메일·전화번호·ID)");
        id.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        id.setText(prefs.getString("account", ""));
        EditText pw = new EditText(this);
        pw.setHint("비밀번호");
        pw.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(id);
        box.addView(pw);
        new AlertDialog.Builder(this)
                .setTitle("샤오미 로그인 (지역: 중국)")
                .setMessage("비밀번호는 저장하지 않고 로그인에만 씁니다.")
                .setView(box)
                .setPositiveButton("로그인", (d, w) -> {
                    String user = id.getText().toString().trim();
                    String pass = pw.getText().toString();
                    if (user.isEmpty() || pass.isEmpty()) { log("계정과 비밀번호를 넣어 주세요"); return; }
                    prefs.edit().putString("account", user).apply();
                    log("로그인 중…");
                    new Thread(() -> {
                        MiCloud.Token t = new MiCloud.Token();
                        t.deviceId = prefs.getString("deviceId", "");
                        try {
                            MiCloud.passwordLogin(t, user, pass);
                            prefs.edit().putString("userId", t.userId).putString("cUserId", t.cUserId)
                                    .putString("passToken", t.passToken).apply();
                            log("로그인 완료");
                            fetchDevices(t);
                        } catch (MiCloud.NeedVerification v) {
                            log("추가 인증이 필요함 → 웹 화면에서 확인해 주세요");
                            ui.post(() -> startLogin(v.url));
                        } catch (Exception e) {
                            log("로그인 실패: " + e.getMessage());
                        }
                    }).start();
                })
                .setNegativeButton("취소", null)
                .show();
    }

    /** 서비스 토큰이 준비된 상태에서 기기 목록을 받아 인증키를 고른다. (작업 스레드에서 호출) */
    private void fetchDevices(MiCloud.Token t) {
        try {
            StringBuilder sb = new StringBuilder();
            List<MiCloud.Device> list = MiCloud.devices(t, sb);
            log(sb.toString().trim());
            ui.post(() -> chooseCloudDevice(list));
        } catch (Exception e) {
            log("인증키 가져오기 실패: " + e.getMessage());
        }
    }

    private void fetchKey() {
        if (prefs.getString("passToken", "").isEmpty()) {
            log("먼저 샤오미 로그인을 해 주세요");
            return;
        }
        log("인증키 가져오는 중…");
        new Thread(() -> {
            MiCloud.Token t = new MiCloud.Token();
            t.userId = prefs.getString("userId", "");
            t.cUserId = prefs.getString("cUserId", "");
            t.passToken = prefs.getString("passToken", "");
            t.deviceId = prefs.getString("deviceId", "");
            try {
                MiCloud.serviceLogin(t);
                prefs.edit().putString("cUserId", t.cUserId).putString("passToken", t.passToken).apply();
                fetchDevices(t);
            } catch (Exception e) {
                log("인증키 가져오기 실패: " + e.getMessage());
            }
        }).start();
    }

    private void chooseCloudDevice(List<MiCloud.Device> list) {
        if (list.isEmpty()) {
            log("계정에서 인증키가 있는 기기를 찾지 못함");
            return;
        }
        String mac = prefs.getString("mac", "");
        for (MiCloud.Device d : list) {
            if (!mac.isEmpty() && d.mac.equalsIgnoreCase(mac)) {
                saveCloudDevice(d);
                return;
            }
        }
        if (list.size() == 1) {
            saveCloudDevice(list.get(0));
            return;
        }
        String[] names = new String[list.size()];
        for (int i = 0; i < names.length; i++) names[i] = list.get(i).name + "\n" + list.get(i).mac;
        new AlertDialog.Builder(this)
                .setTitle("어느 워치인가요?")
                .setItems(names, (d, w) -> saveCloudDevice(list.get(w)))
                .show();
    }

    private void saveCloudDevice(MiCloud.Device d) {
        SharedPreferences.Editor e = prefs.edit();
        e.putString("authKey", d.keys.get(0));
        e.putString("authKeyAlt", String.join(",", d.keys));
        if (prefs.getString("mac", "").isEmpty() && d.mac.length() == 17) e.putString("mac", d.mac);
        e.apply();
        log("인증키 저장: " + d.name + " " + d.mac + (d.keys.size() > 1 ? " (후보 " + d.keys.size() + "개)" : ""));
        refresh();
    }

    private void enterKey() {
        EditText et = new EditText(this);
        et.setHint("32자리 16진수");
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        new AlertDialog.Builder(this)
                .setTitle("인증키 직접 입력")
                .setView(et)
                .setPositiveButton("저장", (d, w) -> {
                    String k = Hex.normalizeKey(et.getText().toString());
                    if (k == null) { log("인증키 형식이 아님 (32자리 16진수)"); return; }
                    prefs.edit().putString("authKey", k).remove("authKeyAlt").apply();
                    log("인증키 저장 (직접 입력)");
                })
                .setNegativeButton("취소", null)
                .show();
    }

    // ---------------- 블루투스 ----------------

    private BluetoothAdapter adapter() {
        BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        return bm == null ? null : bm.getAdapter();
    }

    private boolean hasBtPermission() {
        return Build.VERSION.SDK_INT < 31
                || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private void askPermissions() {
        if (!hasBtPermission()) requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQ_PERM);
    }

    @SuppressWarnings("MissingPermission")
    private void pickDevice() {
        if (!hasBtPermission()) { askPermissions(); return; }
        BluetoothAdapter ad = adapter();
        if (ad == null) { log("블루투스 없음"); return; }
        Set<BluetoothDevice> bonded = ad.getBondedDevices();
        List<BluetoothDevice> list = new ArrayList<>(bonded);
        if (list.isEmpty()) { log("페어링된 기기가 없음"); return; }
        String[] names = new String[list.size()];
        for (int i = 0; i < names.length; i++) names[i] = list.get(i).getName() + "\n" + list.get(i).getAddress();
        new AlertDialog.Builder(this)
                .setTitle("워치 선택")
                .setItems(names, (d, w) -> {
                    prefs.edit().putString("mac", list.get(w).getAddress()).apply();
                    log("워치 선택: " + names[w].replace('\n', ' '));
                })
                .show();
    }

    @SuppressWarnings("MissingPermission")
    private void connect() {
        if (session != null) return;
        if (!hasBtPermission()) { askPermissions(); return; }
        String mac = prefs.getString("mac", "");
        String key = prefs.getString("authKey", "");
        if (mac.isEmpty()) { log("먼저 워치를 선택해 주세요"); return; }
        if (key.isEmpty()) { log("먼저 인증키를 가져오거나 입력해 주세요"); return; }
        BluetoothAdapter ad = adapter();
        if (ad == null || !ad.isEnabled()) { log("블루투스가 꺼져 있음"); return; }

        final BluetoothDevice dev = ad.getRemoteDevice(mac);
        final WatchSession[] holder = new WatchSession[1];
        final WatchSession s =new WatchSession(Hex.parse(key), Build.MODEL, new WatchSession.Callback() {
            @Override public void onLog(String msg) { log(msg); }

            @Override public void onAuthed() {
                state = "연결됨 · 인증 성공";
                post();
                holder[0].requestDeviceInfo();
            }

            @Override public void onDeviceInfo(String model, String fw, String serial) {
                watchInfo = "모델 " + model + "\n펌웨어 " + fw + "\n시리얼 " + serial;
                log("워치 정보 받음: " + model + " / " + fw);
            }

            @Override public void onClosed(String reason) {
                state = "연결 안 됨 (" + reason + ")";
                if (reason.startsWith("인증키가 맞지 않음")) rotateKey();
                closeSocket();
                session = null;
                post();
            }

            @Override public void onPacket(int type, int id, List<Pb.Field> p) {
                log("워치 메시지 type=" + type + " id=" + id);
            }
        });
        holder[0] = s;
        session = s;
        state = "연결 중…";
        watchInfo = "";
        refresh();
        new Thread(() -> {
            try {
                ad.cancelDiscovery();
                BluetoothSocket sock;
                try {
                    sock = dev.createRfcommSocketToServiceRecord(SPP_UUID);
                    sock.connect();
                } catch (Exception first) {
                    log("보안 연결 실패, 다른 방식 시도: " + first.getMessage());
                    sock = dev.createInsecureRfcommSocketToServiceRecord(SPP_UUID);
                    sock.connect();
                }
                socket = sock;
                log("SPP 연결됨, 세션 시작");
                state = "연결됨 · 인증 중…";
                post();
                s.start(sock.getInputStream(), sock.getOutputStream());
            } catch (Exception e) {
                log("SPP 연결 실패: " + e.getMessage()
                        + " → Mi Fitness·MiFaceDown이 워치를 잡고 있으면 끊고 다시 시도");
                state = "연결 안 됨";
                closeSocket();
                session = null;
                post();
            }
        }).start();
    }

    /** 클라우드에서 키 후보가 여러 개 왔으면 다음 후보로 바꿔 둔다. */
    private void rotateKey() {
        String alt = prefs.getString("authKeyAlt", "");
        String cur = prefs.getString("authKey", "");
        if (alt.isEmpty()) return;
        String[] keys = alt.split(",");
        if (keys.length < 2) return;
        int i = 0;
        while (i < keys.length && !keys[i].equals(cur)) i++;
        String next = keys[(i + 1) % keys.length];
        prefs.edit().putString("authKey", next).apply();
        log("다른 인증키 후보로 바꿈. '연결'을 다시 눌러 보세요.");
    }

    private static void closeSocket() {
        BluetoothSocket s = socket;
        socket = null;
        if (s != null) try { s.close(); } catch (Exception ignored) {}
    }

    private void yieldToMiFitness() {
        WatchSession s = session;
        if (s != null) s.close();
        closeSocket();
        session = null;
        state = "연결 안 됨 (Mi Fitness에 양보)";
        log("연결을 놓음. Mi Fitness를 열면 다시 연결됩니다.");
        for (String pkg : new String[]{"com.mi.health", "com.xiaomi.wearable"}) {
            Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
            if (i != null) { startActivity(i); break; }
        }
        refresh();
    }

    // ---------------- 도우미 ----------------

    private TextView header(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(15);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(18), 0, dp(4));
        return t;
    }

    private TextView text() {
        TextView t = new TextView(this);
        t.setTextSize(15);
        t.setPadding(0, dp(2), 0, dp(2));
        return t;
    }

    private Button button(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private String version() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
