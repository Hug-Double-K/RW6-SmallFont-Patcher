package io.github.hugdoublek.rw6noti;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.xiaomi.xms.wearable.Wearable;
import com.xiaomi.xms.wearable.message.MessageApi;
import com.xiaomi.xms.wearable.node.Node;
import com.xiaomi.xms.wearable.node.NodeApi;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 카드 이미지(Base64 PNG)를 조각내어 Mi Fitness 연결을 통해 워치 앱으로 보낸다.
 * 조각 형식: 순번(2자리) + 구분(d=중간, e=마지막) + 파일명(13자리 시각) + 데이터
 * (룽즈님 RedmiWatch5-Noti와 같은 형식)
 */
public final class WatchSender {
    private static final String TAG = "RW6Noti";
    private static final int CHUNK = 20000;

    public interface Result { void done(boolean ok, String msg); }

    // 여러 사진이 동시에 오면 서로 섞이지 않게 한 번에 하나씩 보낸다
    private static final java.util.ArrayDeque<Runnable> QUEUE = new java.util.ArrayDeque<>();
    private static boolean running = false;

    public static void send(Context ctx, String base64, Result cb) {
        Handler main = new Handler(Looper.getMainLooper());
        main.post(() -> {
            QUEUE.add(() -> sendWithRetry(ctx, base64, 0, (ok, msg) -> {
                if (cb != null) cb.done(ok, msg);
                main.post(() -> { running = false; pump(); });
            }));
            pump();
        });
    }

    private static void pump() {
        if (running || QUEUE.isEmpty()) return;
        running = true;
        QUEUE.poll().run();
    }

    private static void sendWithRetry(Context ctx, String base64, int attempt, Result cb) {
        sendNow(ctx, base64, (ok, msg) -> {
            if (!ok && attempt == 0) { // 한 번 더 시도
                new Handler(Looper.getMainLooper()).postDelayed(() -> sendWithRetry(ctx, base64, 1, cb), 3000);
            } else {
                cb.done(ok, attempt > 0 && ok ? msg + " (재시도)" : msg);
            }
        });
    }

    /** 워치 앱이 "준비됨"을 알려오면 실행할 일 (없으면 null) */
    private static volatile Runnable readyWaiter = null;
    private static volatile boolean listening = false;
    private static String lastSetting = "";

    private static void sendNow(Context ctx, String base64, Result cb) {
        Context app = ctx.getApplicationContext();
        if (!listening) listen(app);
        NodeApi nodeApi = Wearable.getNodeApi(app);
        MessageApi msgApi = Wearable.getMessageApi(app);
        Handler main = new Handler(Looper.getMainLooper());

        String stamp = String.valueOf(System.currentTimeMillis());
        List<byte[]> chunks = new ArrayList<>();
        // 설정(S + 진동 + 깨우기 + 글자크기)은 바뀌었을 때만 같이 보낸다 → 메시지 한 번 절약
        Prefs p = new Prefs(app);
        String settingStr = "S" + (p.vibrate() ? "1" : "0") + (p.wake() ? "1" : "0") + String.format("%02d", p.fontSize());
        if (!settingStr.equals(lastSetting)) chunks.add(settingStr.getBytes(StandardCharsets.UTF_8));
        // 종류: 글(JSON) = D/E, 사진 JPEG = f/g, PNG = d/e
        boolean text = base64.startsWith("{");
        boolean jpeg = base64.startsWith("/9j/");
        int size = text ? 6000 : CHUNK;
        int idx = 0;
        for (int start = 0; start < base64.length(); start += size) {
            int end = Math.min(start + size, base64.length());
            boolean last = end == base64.length();
            char flag = text ? (last ? 'E' : 'D') : jpeg ? (last ? 'g' : 'f') : (last ? 'e' : 'd');
            chunks.add((String.format("%02d", idx++) + flag + stamp + base64.substring(start, end)).getBytes(StandardCharsets.UTF_8));
        }
        if (chunks.size() > 100) { report(cb, false, "내용이 너무 큽니다"); return; }

        nodeApi.getConnectedNodes()
                .addOnSuccessListener(nodes -> {
                    if (nodes == null || nodes.isEmpty()) { report(cb, false, "연결된 워치 없음 (Mi Fitness 연결 확인)"); return; }
                    Node node = nodes.get(0);
                    java.util.concurrent.atomic.AtomicBoolean started = new java.util.concurrent.atomic.AtomicBoolean(false);
                    Runnable go = () -> {
                        if (!started.compareAndSet(false, true)) return;
                        readyWaiter = null;
                        lastSetting = settingStr;
                        sendNext(msgApi, node.id, chunks, 0, cb);
                    };
                    // 1) 워치 앱이 이미 켜져 있으면 바로 "준비됨"이 돌아온다 → 즉시 전송
                    readyWaiter = go;
                    msgApi.sendMessage(node.id, "Q".getBytes(StandardCharsets.UTF_8));
                    // 2) 0.4초 안에 답이 없으면 워치 앱을 열고, 앱이 뜨면서 보내는 "준비됨"을 기다림 (최대 3초)
                    main.postDelayed(() -> {
                        if (started.get()) return;
                        nodeApi.launchWearApp(node.id, "/index")
                                .addOnSuccessListener(v -> main.postDelayed(go, 3000))
                                .addOnFailureListener(e -> {
                                    if (started.compareAndSet(false, true)) {
                                        readyWaiter = null;
                                        report(cb, false, "워치 앱 열기 실패: " + e.getMessage() + "\n(워치 앱 설치·워치 권한 확인)");
                                    }
                                });
                    }, 400);
                })
                .addOnFailureListener(e -> report(cb, false, "워치 조회 실패: " + e.getMessage()));
    }

    /** 워치 알림 목록(알림 센터)에 한 줄 띄운다. 워치 앱과 연결된 알림이라 누르면 워치 앱이 열린다 */
    public static void notify(Context ctx, String title, String message) {
        Context app = ctx.getApplicationContext();
        Wearable.getNodeApi(app).getConnectedNodes().addOnSuccessListener(nodes -> {
            if (nodes == null || nodes.isEmpty()) return;
            Wearable.getNotifyApi(app).sendNotify(nodes.get(0).id, title, message)
                    .addOnSuccessListener(st -> Log.d(TAG, "notify ok"))
                    .addOnFailureListener(e -> new Prefs(app).addHistory(NotiListener.now() + " 워치 알림 목록 표시 실패: " + e.getMessage()));
        });
    }

    /** 워치 앱이 보내는 개수 정보를 받는다 ({"n":개수} 또는 {"del":1}) */
    public static void listen(Context ctx) {
        Context app = ctx.getApplicationContext();
        listening = true;
        Wearable.getNodeApi(app).getConnectedNodes().addOnSuccessListener(nodes -> {
            if (nodes == null || nodes.isEmpty()) return;
            Wearable.getMessageApi(app).addListener(nodes.get(0).id, (id, data) -> {
                String s = new String(data, StandardCharsets.UTF_8);
                if (s.contains("ready")) { // 워치 앱 준비됨 → 기다리던 전송 시작
                    Runnable r = readyWaiter;
                    if (r != null) new Handler(Looper.getMainLooper()).post(r);
                    return;
                }
                Prefs p = new Prefs(app);
                if (s.contains("del")) { p.setUnread(p.unread() - 1); return; }
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d+").matcher(s);
                if (m.find()) p.setUnread(Integer.parseInt(m.group()));
            });
        });
    }

    /** 조각을 순서대로 하나씩 보낸다 */
    private static void sendNext(MessageApi api, String nodeId, List<byte[]> chunks, int i, Result cb) {
        if (i >= chunks.size()) { report(cb, true, "전송 완료 (" + chunks.size() + "조각)"); return; }
        api.sendMessage(nodeId, chunks.get(i))
                .addOnSuccessListener(v -> sendNext(api, nodeId, chunks, i + 1, cb))
                .addOnFailureListener(e -> report(cb, false, "전송 실패 " + (i + 1) + "/" + chunks.size() + ": " + e.getMessage()));
    }

    private static void report(Result cb, boolean ok, String msg) {
        Log.d(TAG, msg);
        if (cb != null) new Handler(Looper.getMainLooper()).post(() -> cb.done(ok, msg));
    }
}
