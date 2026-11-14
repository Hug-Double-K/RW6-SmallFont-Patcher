package io.github.hugdoublek.rw6hub;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Mi Manager 첫 화면: 연결 상태 · 기능(알림·메모) · 계정·워치 설정 · 기록. */
public class MainActivity extends Activity implements WatchLink.StateListener {

    private static final int REQ_LOGIN = 1, REQ_PERM = 2;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private WatchLink link;
    private TextView accountView, keyView, addrView, stateView, infoView, logView;
    private Button connectBtn;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("hub", MODE_PRIVATE);
        // 기본 위치(시간대·나중에 날씨용): 서울 광진구 구의1동 (좌표는 동 중심 근처 대략값)
        if (prefs.getString("place", "").isEmpty()) {
            prefs.edit().putString("place", "서울 광진구 구의1동").putString("tz", "Asia/Seoul")
                    .putString("lat", "37.5428").putString("lon", "127.0868").apply();
        }
        if (prefs.getString("deviceId", "").isEmpty()) {
            prefs.edit().putString("deviceId", "an_" + Hex.of(MiCrypto.random(16))).apply();
        }
        link = WatchLink.get(this);
        buildUi();
        askPermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        link.addStateListener(this);
        refresh();
        if (hasBtPermission()) LinkService.autoStart(this);
    }

    @Override
    protected void onPause() {
        link.removeStateListener(this);
        super.onPause();
    }

    @Override
    public void onStateChanged() { ui.post(this::refresh); }

    private void log(String msg) { link.log(msg); }

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

        root.addView(header("연결"));
        stateView = text();
        stateView.setTextSize(17);
        root.addView(stateView);
        infoView = text();
        root.addView(infoView);
        connectBtn = button("연결 (계속 유지)", v -> { askPermissions(); LinkService.start(this); });
        root.addView(connectBtn);
        root.addView(button("연결 해제 · Mi Fitness에 양보", v -> yieldToMiFitness()));

        root.addView(header("기능"));
        root.addView(button("메신저 알림 (RW6 알림)", v ->
                startActivity(new Intent(this, io.github.hugdoublek.rw6hub.noti.MainActivity.class))));
        root.addView(button("메모 (RW6 메모)", v ->
                startActivity(new Intent(this, io.github.hugdoublek.rw6hub.memo.MainActivity.class))));
        root.addView(button("퀵앱 · 워치페이스 · 펌웨어 설치", v -> startActivity(new Intent(this, InstallActivity.class))));
        root.addView(button("워치에 설치된 퀵앱 (실행 · 삭제)", v -> showInstalled()));
        root.addView(button("워치 언어", v -> pickLanguage()));

        root.addView(header("계정 (샤오미, 중국 지역)"));
        accountView = text();
        root.addView(accountView);
        root.addView(button("웹 화면으로 로그인", v -> startLogin(null)));
        root.addView(button("아이디·비밀번호로 로그인", v -> passwordLogin()));
        root.addView(button("인증키 다시 가져오기", v -> fetchKey()));

        root.addView(header("워치"));
        addrView = text();
        root.addView(addrView);
        root.addView(button("페어링된 기기에서 선택", v -> pickDevice()));
        keyView = text();
        root.addView(keyView);
        root.addView(button("인증키 직접 입력", v -> enterKey()));

        root.addView(header("기록"));
        logView = text();
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextSize(12);
        logView.setTextIsSelectable(true);
        root.addView(logView);
        root.addView(button("기록 지우기", v -> link.clearLog()));

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
        stateView.setText("상태: " + link.state());
        infoView.setText(link.isReady() && !link.watchModel().isEmpty()
                ? "모델 " + link.watchModel() + " · 펌웨어 " + link.watchFirmware() : "");
        connectBtn.setEnabled(!link.wantsConnection());
        logView.setText(link.logText());
    }

    private void showInstalled() {
        if (!link.isReady()) { log("워치가 연결되어 있지 않음"); return; }
        QuickApps.get(this).refreshInstalled(apps -> ui.post(() -> {
            if (apps.isEmpty()) { log("퀵앱 목록을 받지 못함"); return; }
            String[] names = new String[apps.size()];
            for (int i = 0; i < names.length; i++) {
                QuickApps.App a = apps.get(i);
                names[i] = a.name + "\n" + a.pkg + " (v" + a.versionCode + ")";
            }
            new AlertDialog.Builder(this)
                    .setTitle("워치 퀵앱 " + apps.size() + "개")
                    .setItems(names, (d, w) -> appMenu(apps.get(w)))
                    .setPositiveButton("닫기", null)
                    .show();
        }));
    }

    private void pickLanguage() {
        String[] names = {"한국어", "中文 (중국어)", "English (영어)"};
        String[] codes = {"ko_KR", "zh_CN", "en_US"};
        String cur = prefs.getString("locale", "ko_KR");
        int sel = 0;
        for (int i = 0; i < codes.length; i++) if (codes[i].equals(cur)) sel = i;
        new AlertDialog.Builder(this)
                .setTitle("워치 언어 (연결할 때마다 맞춤)")
                .setSingleChoiceItems(names, sel, (d, w) -> {
                    prefs.edit().putString("locale", codes[w]).apply();
                    if (link.isReady()) link.sendLanguage(codes[w]);
                    else log("워치가 연결되면 " + names[w] + "로 맞출게요");
                    d.dismiss();
                })
                .show();
    }

    private void appMenu(QuickApps.App a) {
        AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle(a.name)
                .setMessage(a.pkg + " (v" + a.versionCode + ")")
                .setPositiveButton("워치에서 실행", (d, w) -> QuickApps.get(this).launch(a.pkg, "/index",
                        err -> log(err == null ? "실행 요청: " + a.name : "실행 실패: " + err)))
                .setNeutralButton("닫기", null);
        if (a.canRemove) {
            b.setNegativeButton("삭제", (d, w) -> new AlertDialog.Builder(this)
                    .setTitle("삭제할까요?")
                    .setMessage(a.name + "을(를) 워치에서 지워요.")
                    .setPositiveButton("삭제", (d2, w2) -> QuickApps.get(this).remove(a.pkg,
                            err -> log(err == null ? "삭제함: " + a.name : "삭제 실패: " + err)))
                    .setNegativeButton("취소", null)
                    .show());
        }
        b.show();
    }

    private void yieldToMiFitness() {
        LinkService.stop(this, "Mi Fitness에 양보");
        log("연결을 놓음. Mi Fitness를 열면 다시 연결됩니다.");
        for (String pkg : new String[]{"com.mi.health", "com.xiaomi.wearable"}) {
            Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
            if (i != null) { startActivity(i); break; }
        }
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
        if (hasBtPermission()) LinkService.autoStart(this); else askPermissions();
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
                    if (hasBtPermission()) LinkService.autoStart(this);
                    refresh();
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
        List<String> need = new ArrayList<>();
        if (!hasBtPermission()) need.add(Manifest.permission.BLUETOOTH_CONNECT);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            need.add("android.permission.POST_NOTIFICATIONS");
        }
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), REQ_PERM);
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (hasBtPermission()) LinkService.autoStart(this);
        else log("블루투스(근처 기기) 권한이 없어 워치에 연결할 수 없어요");
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
                    LinkService.autoStart(this);
                    log("워치 선택: " + names[w].replace('\n', ' '));
                })
                .show();
    }

    @SuppressWarnings("MissingPermission")

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
