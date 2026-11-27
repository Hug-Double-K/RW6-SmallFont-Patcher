package io.github.hugdoublek.rw6hub;

import android.app.Application;

/** 앱 프로세스가 뜨면(폰 재시작, 알림 서비스 연결 등) 연결 유지가 켜져 있을 때 다시 붙는다. */
public class HubApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        WatchDrop.dir = getFilesDir();   // 워치가 올린 보고 글을 파일로도 남김
        // 워치(Lua 워치페이스)가 폰에 시키는 일: /do/launch-noti = 워치에서 RW6 알림 열기
        WatchDrop.command = name -> {
            if (name.equals("launch-noti")) {
                QuickApps q = QuickApps.get(this);
                String pkg = q.target(io.github.hugdoublek.rw6hub.noti.Wearable.WATCH_PACKAGE);
                q.launch(pkg, "/index", err -> WatchLink.get(this).log(err == null
                        ? "워치 요청: RW6 알림 열기 보냄 (" + pkg + ")" : "워치 요청: RW6 알림 열기 실패 — " + err));
                return "ok " + pkg;
            }
            return "err unknown " + name;
        };
        // 알림·메모 기능이 워치 메시지를 받을 수 있게 미리 등록
        io.github.hugdoublek.rw6hub.noti.Wearable.client(this);
        io.github.hugdoublek.rw6hub.memo.Wearable.client(this);
        // 워치의 '카메라 셔터' 퀵앱: {cam:1 사진·2 동영상 시작/끝·3 일시정지/다시}가 오면 폰 카메라 버튼을 대신 누르고 결과를 {r:"…"}로 돌려줌
        QuickApps.get(this).register(ShutterService.WATCH_PACKAGE, "카메라 셔터");
        QuickApps.get(this).addListener(ShutterService.WATCH_PACKAGE, (pkg, data) -> {
            String s = new String(data, java.nio.charset.StandardCharsets.UTF_8);
            int n = 0, t = 0;
            try { org.json.JSONObject o = new org.json.JSONObject(s); n = o.optInt("cam", 0); t = o.optInt("t", 0); } catch (Exception e) { if (s.contains("cam")) n = 1; }
            if (n > 0) ShutterService.trigger(this, n, t);
        });
        // 워치가 연결되면 알림 기능의 '워치 답 듣기'를 바로 등록 (예전엔 첫 전송 때 등록됨)
        WatchLink link = WatchLink.get(this);
        final boolean[] was = {false};
        link.addStateListener(() -> {
            boolean now = link.isReady();
            if (now && !was[0]) {
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                        io.github.hugdoublek.rw6hub.noti.WatchSender.listen(this));
            }
            was[0] = now;
        });
        if (LinkService.keep(this)) LinkService.start(this);
    }
}
