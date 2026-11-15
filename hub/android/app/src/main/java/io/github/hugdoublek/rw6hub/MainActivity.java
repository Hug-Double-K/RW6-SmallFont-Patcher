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

    private static final int REQ_LOGIN = 1, REQ_PERM = 2, REQ_LOC = 3;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private WatchLink link;
    private TextView accountView, keyView, addrView, stateView, infoView, logView;
    private Button connectBtn, mifitBtn;
    private TextView mifitWarn, aiView;
    private static final String[] MIFIT = {"com.mi.health", "com.xiaomi.wearable"};

    /** 설치돼 있고 '근처 기기'(블루투스) 권한이 켜진 Mi Fitness 패키지, 없으면 null */
    private String miFitnessWithBt() {
        PackageManager pm = getPackageManager();
        for (String pkg : MIFIT) {
            try {
                pm.getPackageInfo(pkg, 0);
                String perm = Build.VERSION.SDK_INT >= 31 ? Manifest.permission.BLUETOOTH_CONNECT : Manifest.permission.BLUETOOTH;
                if (pm.checkPermission(perm, pkg) == PackageManager.PERMISSION_GRANTED) return pkg;
            } catch (PackageManager.NameNotFoundException ignored) {}
        }
        return null;
    }

    private String installedMiFitness() {
        for (String pkg : MIFIT) {
            try { getPackageManager().getPackageInfo(pkg, 0); return pkg; } catch (PackageManager.NameNotFoundException ignored) {}
        }
        return null;
    }

    private void openMiFitnessSettings() {
        String pkg = installedMiFitness();
        if (pkg == null) return;
        Intent i = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:" + pkg));
        startActivity(i);
    }

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

    private static final int BG = 0xFF101114, CARD = 0xFF1C1E24, LINE = 0xFF2B2E37, TEXT = 0xFFF2F3F5, SUB = 0xFF9AA0AC,
            ACCENT = 0xFFFF6FA8, OK = 0xFF3DDC97, WARN = 0xFFFFB067, BAD = 0xFFFF6B6B;

    private View stateDot;
    private TextView placeSub;
    private TextView weatherView, langSub, aiKeySub, setupTitle, logToggle;
    private android.widget.Switch aiSwitch, voiceSwitch, notiSwitch, diagSwitch;
    private LinearLayout setupBox;
    private Button disconnectBtn;
    private boolean updating, logFull;

    private void buildUi() {
        if (getActionBar() != null) getActionBar().hide();
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(18), dp(14), dp(28));

        // 제목
        LinearLayout head = new LinearLayout(this);
        head.setGravity(android.view.Gravity.BOTTOM);
        TextView title = label("Mi Manager", 26, TEXT, true);
        TextView ver = label("  " + version(), 13, SUB, false);
        head.addView(title);
        head.addView(ver);
        root.addView(head);

        // ── 연결 상태 ──
        LinearLayout c = card(root, null);
        LinearLayout st = new LinearLayout(this);
        st.setGravity(android.view.Gravity.CENTER_VERTICAL);
        stateDot = new View(this);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(12), dp(12));
        dl.rightMargin = dp(10);
        st.addView(stateDot, dl);
        stateView = label("", 20, TEXT, true);
        st.addView(stateView);
        c.addView(st);
        infoView = label("", 14, SUB, false);
        infoView.setPadding(0, dp(6), 0, 0);
        c.addView(infoView);
        weatherView = label("", 14, SUB, false);
        weatherView.setPadding(0, dp(2), 0, 0);
        c.addView(weatherView);
        // Mi Fitness가 워치를 몇 분마다 가져가 연결이 끊기던 원인 → 블루투스 권한이 켜져 있으면 경고
        mifitWarn = label("", 14, WARN, false);
        mifitWarn.setPadding(0, dp(10), 0, 0);
        c.addView(mifitWarn);
        mifitBtn = pill("Mi Fitness 블루투스 권한 끄러 가기", false, v -> openMiFitnessSettings());
        c.addView(mifitBtn, topMargin(8));
        LinearLayout btns = new LinearLayout(this);
        connectBtn = pill("연결", true, v -> { askPermissions(); LinkService.start(this); });
        disconnectBtn = pill("해제 · Mi Fitness에 양보", false, v -> yieldToMiFitness());
        LinearLayout.LayoutParams b1 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        b1.rightMargin = dp(8);
        btns.addView(connectBtn, b1);
        btns.addView(disconnectBtn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2));
        c.addView(btns, topMargin(12));

        // ── 기능 ──
        c = card(root, "기능");
        row(c, "메신저 알림", "카톡·문자 등을 워치로 보내기 (RW6 알림)", v ->
                startActivity(new Intent(this, io.github.hugdoublek.rw6hub.noti.MainActivity.class)));
        line(c);
        row(c, "메모", "폰 메모를 워치에서 보기 (RW6 메모)", v ->
                startActivity(new Intent(this, io.github.hugdoublek.rw6hub.memo.MainActivity.class)));
        line(c);
        row(c, "설치", "퀵앱 · 워치페이스 · 펌웨어 파일을 워치에 넣기", v -> startActivity(new Intent(this, InstallActivity.class)));
        line(c);
        row(c, "워치의 퀵앱", "설치된 앱 보기 · 실행 · 삭제", v -> showInstalled());

        // ── AI 비서 ──
        c = card(root, "AI 비서 · 크라운 길게 누르기");
        aiView = label("", 14, SUB, false);
        aiView.setPadding(0, 0, 0, dp(4));
        c.addView(aiView);
        aiSwitch = toggle(c, "AI 비서 사용", "샤오아이 대신 Gemini가 답해요", on -> {
            prefs.edit().putBoolean("aiOn", on).apply();
            refresh();
        });
        line(c);
        voiceSwitch = toggle(c, "음성 답변", "워치 스피커로 읽기 · \"조용히 해\" / \"소리로 말해\"로도 바뀜",
                on -> prefs.edit().putBoolean("aiVoice", on).apply());
        line(c);
        aiKeySub = row(c, "Gemini API 키 · 모델", "", v -> aiSettings());

        // ── 워치 ──
        c = card(root, "워치");
        langSub = row(c, "언어", "", v -> pickLanguage());
        line(c);
        placeSub = row(c, "현재 위치로 날씨 업데이트", "", v -> updateLocation());
        line(c);
        row(c, "날씨 지금 보내기", "평소엔 1시간마다 자동으로 보내요 (현재·시간별·7일)", v -> {
            if (link.isReady()) { WatchWeather.get(this).sendNow(); toast("날씨를 보내는 중"); } else toast("워치가 연결되어 있지 않아요");
        });
        line(c);
        row(c, "시간 다시 맞추기", "연결할 때마다 자동으로 맞춰요", v -> {
            if (link.isReady()) { link.syncTime(); toast("시간을 맞췄어요"); } else toast("워치가 연결되어 있지 않아요");
        });
        line(c);
        notiSwitch = toggle(c, "상단 연결 알림", "꺼도 연결은 유지돼요", on -> {
            LinkService.setStatusNotification(this, on);
            log(on ? "상단 연결 알림을 켬" : "상단 연결 알림을 끔 (연결 유지는 그대로)");
            if (!on) askBatteryExemption();
        });

        // ── 계정 · 연결 정보 (설정이 끝났으면 접어 둠) ──
        c = card(root, null);
        setupTitle = label("", 13, ACCENT, true);
        setupTitle.setPadding(0, dp(2), 0, dp(2));
        c.addView(setupTitle);
        setupBox = new LinearLayout(this);
        setupBox.setOrientation(LinearLayout.VERTICAL);
        setupBox.setPadding(0, dp(8), 0, 0);
        boolean done = !prefs.getString("authKey", "").isEmpty() && !prefs.getString("mac", "").isEmpty();
        setupBox.setVisibility(done ? View.GONE : View.VISIBLE);
        setupTitle.setOnClickListener(v -> { setupBox.setVisibility(setupBox.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE); refresh(); });
        c.addView(setupBox);
        accountView = label("", 14, SUB, false);
        setupBox.addView(accountView);
        row(setupBox, "웹 화면으로 로그인", "샤오미 계정 (중국 지역)", v -> startLogin(null));
        line(setupBox);
        row(setupBox, "아이디·비밀번호로 로그인", "비밀번호는 저장하지 않아요", v -> passwordLogin());
        line(setupBox);
        row(setupBox, "인증키 다시 가져오기", "로그인한 계정에서 받아 와요", v -> fetchKey());
        line(setupBox);
        addrView = row(setupBox, "워치 선택", "", v -> pickDevice());
        line(setupBox);
        keyView = row(setupBox, "인증키 직접 입력", "", v -> enterKey());
        line(setupBox);
        row(setupBox, "인증키 전체 복사", "32자리를 클립보드로", v -> {
            String k = prefs.getString("authKey", "");
            if (k.isEmpty()) { toast("인증키가 없어요"); return; }
            copy("인증키", k);
            toast("인증키 32자리를 복사했어요");
        });

        // ── 기록 ──
        c = card(root, "기록");
        logView = label("", 12, 0xFFC9CDD6, false);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        logView.setLineSpacing(dp(2), 1f);
        c.addView(logView);
        LinearLayout lb = new LinearLayout(this);
        logToggle = pill("전체 보기", false, v -> { logFull = !logFull; refresh(); });
        Button copyBtn = pill("복사", false, v -> { copy("기록", link.logText()); toast("기록을 복사했어요"); });
        Button clearBtn = pill("지우기", false, v -> link.clearLog());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        lp.rightMargin = dp(8);
        lb.addView(logToggle, lp);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        lp2.rightMargin = dp(8);
        lb.addView(copyBtn, lp2);
        lb.addView(clearBtn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        c.addView(lb, topMargin(12));
        line(c);
        diagSwitch = toggle(c, "분석용 기록 모으기", "문제를 찾을 때만 켜요 (워치가 보낸 내용을 저장)", on -> AivsCapture.enabled = on);
        row(c, "분석용 기록 파일로 저장", "다운로드 폴더에 저장", v -> {
            String r = AivsCapture.save(this);
            toast(r == null ? "모인 기록이 없어요. '분석용 기록 모으기'를 켜고 다시 해 보세요" : "저장: " + r);
        });

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        sv.addView(root);
        setContentView(sv);
    }

    private void refresh() {
        updating = true;
        // 연결 상태
        String state = link.state();
        boolean ready = link.isReady();
        boolean busy = state.contains("중");
        stateView.setText(state);
        android.graphics.drawable.GradientDrawable dot = new android.graphics.drawable.GradientDrawable();
        dot.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        dot.setColor(ready ? OK : busy ? WARN : BAD);
        stateDot.setBackground(dot);
        StringBuilder info = new StringBuilder();
        if (ready && !link.watchModel().isEmpty()) info.append(link.watchModel()).append(" · 펌웨어 ").append(link.watchFirmware());
        if (ready && link.battery >= 0) info.append(info.length() > 0 ? " · " : "").append("배터리 ").append(link.battery).append("%");
        infoView.setText(info);
        infoView.setVisibility(info.length() > 0 ? View.VISIBLE : View.GONE);
        String wx = WatchWeather.get(this).sentText();
        weatherView.setText(wx.isEmpty() ? "" : "날씨 " + wx);
        weatherView.setVisibility(wx.isEmpty() ? View.GONE : View.VISIBLE);
        connectBtn.setEnabled(!link.wantsConnection());
        connectBtn.setAlpha(link.wantsConnection() ? 0.4f : 1f);
        connectBtn.setText(link.wantsConnection() ? "연결 유지 중" : "연결");
        boolean yielded = prefs.getBoolean("yielded", false);
        boolean warn = !yielded && miFitnessWithBt() != null;
        mifitWarn.setText(warn ? "⚠ Mi Fitness가 워치를 몇 분마다 가져가서 연결이 끊기고, 통화 소리가 스피커↔일반으로 바뀔 수 있어요. "
                + "Mi Fitness → 권한 → '근처 기기'를 '허용 안 함'으로 바꿔 주세요." : "");
        mifitWarn.setVisibility(warn ? View.VISIBLE : View.GONE);
        mifitBtn.setVisibility(warn ? View.VISIBLE : View.GONE);

        // AI
        boolean aiOn = prefs.getBoolean("aiOn", true);
        String aiKey = prefs.getString("aiKey", "");
        aiView.setText(!aiOn ? "꺼짐 — 크라운을 누르면 원래 샤오아이가 나와요"
                : aiKey.isEmpty() ? "⚠ Gemini API 키를 넣어야 쓸 수 있어요" : "켜짐 — 크라운을 길게 누르고 말하세요");
        aiView.setTextColor(aiOn && aiKey.isEmpty() ? WARN : SUB);
        aiSwitch.setChecked(aiOn);
        voiceSwitch.setChecked(prefs.getBoolean("aiVoice", true));
        aiKeySub.setText("키 " + (aiKey.isEmpty() ? "없음" : "있음") + " · " + prefs.getString("aiModel", XiaoAi.DEFAULT_MODEL));

        // 워치
        String loc = prefs.getString("locale", "ko_KR");
        langSub.setText(loc.equals("ko_KR") ? "한국어" : loc.equals("zh_CN") ? "中文" : "English");
        notiSwitch.setChecked(LinkService.statusNotification(this));
        placeSub.setText("지금 위치: " + prefs.getString("place", "서울 광진구 구의1동"));

        // 계정 · 연결 정보
        String user = prefs.getString("userId", "");
        String addr = prefs.getString("mac", "");
        String key = prefs.getString("authKey", "");
        boolean open = setupBox.getVisibility() == View.VISIBLE;
        setupTitle.setText((open ? "▾ " : "▸ ") + "계정 · 연결 정보"
                + (open ? "" : key.isEmpty() || addr.isEmpty() ? "  (설정 필요)" : "  (설정 완료 · 눌러서 펼치기)"));
        accountView.setText(user.isEmpty() ? "로그인 안 됨" : "로그인됨 (userId " + user + ")");
        addrView.setText(addr.isEmpty() ? "선택 안 됨 — 페어링된 기기에서 고르기" : addr);
        keyView.setText(key.isEmpty() ? "없음" : key.substring(0, 4) + "…" + key.substring(28) + " (32자리)");

        // 기록: 평소엔 마지막 몇 줄만
        String all = link.logText();
        if (!logFull) {
            String[] lines = all.split("\n");
            int from = Math.max(0, lines.length - 8);
            StringBuilder sb = new StringBuilder();
            for (int i = from; i < lines.length; i++) sb.append(lines[i]).append(i < lines.length - 1 ? "\n" : "");
            all = sb.toString();
        }
        logView.setText(all.isEmpty() ? "(기록 없음)" : all);
        logToggle.setText(logFull ? "접기" : "전체 보기");
        diagSwitch.setChecked(AivsCapture.enabled);
        updating = false;
    }

    private void toast(String s) { android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show(); }

    private void copy(String label, String text) {
        android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(android.content.ClipData.newPlainText(label, text));
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

    private void aiSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        EditText key = new EditText(this);
        key.setHint("Gemini API 키 (aistudio.google.com에서 발급)");
        key.setText(prefs.getString("aiKey", ""));
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        EditText model = new EditText(this);
        model.setHint("모델 이름");
        model.setText(prefs.getString("aiModel", XiaoAi.DEFAULT_MODEL));
        box.addView(key);
        box.addView(model);
        new AlertDialog.Builder(this)
                .setTitle("AI 설정")
                .setMessage("RW6 AI 앱에 넣었던 것과 같은 키를 쓰면 돼요.")
                .setView(box)
                .setPositiveButton("저장", (d, w) -> {
                    prefs.edit().putString("aiKey", key.getText().toString().trim())
                            .putString("aiModel", model.getText().toString().trim().isEmpty() ? XiaoAi.DEFAULT_MODEL : model.getText().toString().trim())
                            .apply();
                    log("AI 설정 저장");
                })
                .setNegativeButton("취소", null)
                .show();
    }

    /** 배터리 최적화에서 빼 달라고 요청 (백그라운드에서 연결이 끊기지 않게) */
    private void askBatteryExemption() {
        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        if (pm == null) return;
        if (pm.isIgnoringBatteryOptimizations(getPackageName())) { log("배터리 최적화 제외: 이미 허용됨"); return; }
        try {
            startActivity(new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            startActivity(new Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    private void yieldToMiFitness() {
        LinkService.stop(this, "Mi Fitness에 양보");
        log("연결을 놓음.");
        if (installedMiFitness() == null) return;
        if (miFitnessWithBt() != null) {
            for (String pkg : MIFIT) {
                Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
                if (i != null) { startActivity(i); break; }
            }
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Mi Fitness에 양보")
                .setMessage("Mi Fitness의 '근처 기기' 권한이 꺼져 있어서 지금은 워치에 붙을 수 없어요.\n\n"
                        + "Mi Fitness → 권한 → 근처 기기를 '허용'으로 바꾼 뒤 Mi Fitness를 여세요. "
                        + "다 쓰고 Mi Manager로 돌아올 때는 다시 '허용 안 함'으로 바꾸고 '연결'을 누르면 돼요.")
                .setPositiveButton("권한 설정 열기", (d, w) -> openMiFitnessSettings())
                .setNegativeButton("닫기", null)
                .show();
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

    // ---------------- 위치 ----------------

    private boolean hasLocation() {
        return checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressWarnings("MissingPermission")
    private void updateLocation() {
        if (!hasLocation()) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION}, REQ_LOC);
            return;
        }
        android.location.LocationManager lm = (android.location.LocationManager) getSystemService(LOCATION_SERVICE);
        if (lm == null) { toast("위치를 쓸 수 없어요"); return; }
        String provider = lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER) ? android.location.LocationManager.NETWORK_PROVIDER
                : lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ? android.location.LocationManager.GPS_PROVIDER : null;
        if (provider == null) { toast("폰의 위치(GPS)를 켜 주세요"); return; }
        toast("현재 위치를 찾는 중…");
        final boolean[] done = {false};
        java.util.function.Consumer<android.location.Location> got = loc -> {
            if (done[0]) return;
            done[0] = true;
            if (loc == null) { toast("위치를 찾지 못했어요. 잠시 뒤 다시 해 보세요"); return; }
            onLocation(loc);
        };
        if (Build.VERSION.SDK_INT >= 30) {
            lm.getCurrentLocation(provider, null, getMainExecutor(), got::accept);
        } else {
            lm.requestSingleUpdate(provider, new android.location.LocationListener() {
                @Override public void onLocationChanged(android.location.Location l) { got.accept(l); }
                @Override public void onStatusChanged(String p, int s, Bundle e) {}
                @Override public void onProviderEnabled(String p) {}
                @Override public void onProviderDisabled(String p) {}
            }, Looper.getMainLooper());
        }
        // 15초 안에 못 찾으면 마지막으로 알던 위치라도 씀
        ui.postDelayed(() -> got.accept(lm.getLastKnownLocation(provider)), 15000);
    }

    private void onLocation(android.location.Location loc) {
        double lat = loc.getLatitude(), lon = loc.getLongitude();
        new Thread(() -> {
            String place = String.format(java.util.Locale.US, "%.3f, %.3f", lat, lon);
            try {
                List<android.location.Address> list = new android.location.Geocoder(this, java.util.Locale.KOREA).getFromLocation(lat, lon, 1);
                if (list != null && !list.isEmpty()) {
                    android.location.Address a = list.get(0);
                    StringBuilder sb = new StringBuilder();
                    for (String p : new String[]{a.getAdminArea(), a.getSubLocality() != null ? a.getSubLocality() : a.getLocality(), a.getThoroughfare()}) {
                        if (p != null && !p.isEmpty() && sb.indexOf(p) < 0) sb.append(sb.length() > 0 ? " " : "").append(p);
                    }
                    if (sb.length() > 0) place = sb.toString();
                }
            } catch (Exception ignored) {}
            final String fp = place;
            ui.post(() -> {
                WatchWeather.get(this).useLocation(lat, lon, fp);
                toast("위치: " + fp);
                refresh();
            });
        }).start();
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req == REQ_LOC) {
            if (hasLocation()) updateLocation(); else toast("위치 권한이 없어 현재 위치를 쓸 수 없어요");
            return;
        }
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

    // ---------------- 도우미 ----------------

    private interface OnToggle { void set(boolean on); }

    private TextView label(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private LinearLayout.LayoutParams topMargin(int d) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(d);
        return lp;
    }

    /** 둥근 카드. title이 있으면 위에 작은 제목 */
    private LinearLayout card(LinearLayout root, String title) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(dp(18));
        c.setBackground(bg);
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        root.addView(c, topMargin(14));
        if (title != null) {
            TextView t = label(title, 13, ACCENT, true);
            t.setPadding(0, 0, 0, dp(6));
            c.addView(t);
        }
        return c;
    }

    private void line(LinearLayout parent) {
        View v = new View(this);
        v.setBackgroundColor(LINE);
        parent.addView(v, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(1) / 2)));
    }

    private LinearLayout rowBase(String title, String sub, TextView[] subOut) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(android.view.Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(11), 0, dp(11));
        r.setMinimumHeight(dp(52));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(label(title, 16, TEXT, false));
        TextView s = label(sub, 13, SUB, false);
        s.setPadding(0, dp(2), 0, 0);
        texts.addView(s);
        subOut[0] = s;
        r.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        return r;
    }

    /** 누르는 줄 (제목 + 설명 + ›). 설명 글자를 돌려줌 */
    private TextView row(LinearLayout parent, String title, String sub, View.OnClickListener l) {
        TextView[] s = new TextView[1];
        LinearLayout r = rowBase(title, sub, s);
        TextView arrow = label("›", 22, SUB, false);
        arrow.setPadding(dp(10), 0, dp(2), 0);
        r.addView(arrow);
        android.util.TypedValue tv = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        r.setBackgroundResource(tv.resourceId);
        r.setClickable(true);
        r.setOnClickListener(l);
        parent.addView(r);
        return s[0];
    }

    /** 켬/끔 줄 */
    private android.widget.Switch toggle(LinearLayout parent, String title, String sub, OnToggle l) {
        TextView[] s = new TextView[1];
        LinearLayout r = rowBase(title, sub, s);
        android.widget.Switch sw = new android.widget.Switch(this);
        sw.setOnCheckedChangeListener((b, on) -> { if (!updating) l.set(on); });
        r.addView(sw);
        r.setOnClickListener(v -> sw.toggle());
        parent.addView(r);
        return sw;
    }

    /** 둥근 버튼. primary면 강조색으로 채움 */
    private Button pill(String text, boolean primary, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTextColor(primary ? 0xFF1A0A12 : TEXT);
        if (primary) b.setTypeface(Typeface.DEFAULT_BOLD);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(dp(22));
        if (primary) bg.setColor(ACCENT); else { bg.setColor(0xFF272A33); bg.setStroke(Math.max(1, dp(1)), LINE); }
        b.setBackground(bg);
        b.setStateListAnimator(null);
        b.setMinHeight(dp(44));
        b.setMinimumHeight(dp(44));
        b.setPadding(dp(14), 0, dp(14), 0);
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
