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
        send(ctx, base64, String.valueOf(System.currentTimeMillis()), cb);
    }

    /** stamp = 워치에서 쓰는 이름(13자리). 사진은 '먼저 보낸 글 알림'과 같은 이름으로 보내 자리를 바꿔 끼움 */
    public static void send(Context ctx, String base64, String stamp, Result cb) {
        Handler main = new Handler(Looper.getMainLooper());
        main.post(() -> {
            QUEUE.add(() -> sendWithRetry(ctx, base64, stamp, 0, (ok, msg) -> {
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

    private static void sendWithRetry(Context ctx, String base64, String stamp, int attempt, Result cb) {
        sendNow(ctx, base64, stamp, (ok, msg) -> {
            if (!ok && attempt == 0) { // 한 번 더 시도
                new Handler(Looper.getMainLooper()).postDelayed(() -> sendWithRetry(ctx, base64, stamp, 1, cb), 2000);
            } else {
                cb.done(ok, attempt > 0 && ok ? msg + " (재시도)" : msg);
            }
        });
    }

    /** 워치 앱이 "준비됨"을 알려오면 실행할 일 (없으면 null) */
    private static volatile Runnable readyWaiter = null;
    private static volatile boolean listening = false;
    private static String lastSetting = "";

    /** 마지막으로 워치에 잘 보낸 시각 (그 직후엔 워치 앱이 아직 켜져 있음) */
    private static volatile long lastOk = 0;
    /** 워치 앱을 연 직후 글 알림을 거듭 보내는 시점(ms). 워치는 같은 이름이면 한 번만 처리 */
    private static final long[] TEXT_RETRY = {250, 700, 1200, 1800, 2600, 3600};

    private static void sendNow(Context ctx, String base64, String stamp, Result cb) {
        Context app = ctx.getApplicationContext();
        if (!listening) listen(app);
        NodeApi nodeApi = Wearable.getNodeApi(app);
        MessageApi msgApi = Wearable.getMessageApi(app);
        Handler main = new Handler(Looper.getMainLooper());

        List<byte[]> chunks = new ArrayList<>();
        // 설정(S + 진동 + 깨우기 + 글자크기)은 바뀌었을 때만 같이 보낸다
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
                    java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean(false);
                    Result once = (ok, msg) -> {
                        if (!done.compareAndSet(false, true)) return;
                        readyWaiter = null;
                        if (ok) { lastOk = System.currentTimeMillis(); lastSetting = settingStr; }
                        report(cb, ok, msg);
                    };
                    // 방금 보낸 직후라 워치 앱이 켜져 있으면 바로
                    if (System.currentTimeMillis() - lastOk < 8000) {
                        sendNext(msgApi, node.id, chunks, 0, once);
                        return;
                    }
                    // 워치 앱이 "준비됨"을 보내오면 그때 바로 (지원되는 경우)
                    readyWaiter = () -> { if (!done.get()) sendNext(msgApi, node.id, chunks, 0, once); };
                    nodeApi.launchWearApp(node.id, "/index")
                            .addOnSuccessListener(v -> {
                                if (text) {
                                    // 글: 앱이 뜨는 동안 짧은 간격으로 거듭 보냄 → 앱이 받을 수 있게 되는 순간 도착
                                    for (long t : TEXT_RETRY) {
                                        main.postDelayed(() -> sendNext(msgApi, node.id, chunks, 0,
                                                (ok, m) -> { if (ok) once.done(true, m); }), t);
                                    }
                                    main.postDelayed(() -> once.done(false, "워치 앱이 응답하지 않음"), 6000);
                                } else {
                                    // 사진: 조각이 많아 거듭 보내지 않고, 준비됨 신호 또는 1.6초 뒤 한 번
                                    main.postDelayed(() -> { if (!done.get()) sendNext(msgApi, node.id, chunks, 0, once); }, 1600);
                                }
                            })
                            .addOnFailureListener(e -> once.done(false, "워치 앱 열기 실패: " + e.getMessage() + "\n(워치 앱 설치·워치 권한 확인)"));
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
