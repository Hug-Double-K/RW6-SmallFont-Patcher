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

    private static void sendNow(Context ctx, String base64, Result cb) {
        Context app = ctx.getApplicationContext();
        NodeApi nodeApi = Wearable.getNodeApi(app);
        MessageApi msgApi = Wearable.getMessageApi(app);

        String stamp = String.valueOf(System.currentTimeMillis());
        List<byte[]> chunks = new ArrayList<>();
        // 먼저 진동·화면 깨우기 설정을 보낸다 (S + 진동 + 깨우기)
        Prefs p = new Prefs(app);
        // S + 진동 + 깨우기 + 글자크기(2자리)
        byte[] setting = ("S" + (p.vibrate() ? "1" : "0") + (p.wake() ? "1" : "0") + String.format("%02d", p.fontSize())).getBytes(StandardCharsets.UTF_8);
        // 글 알림은 이미지 대신 글(JSON) 그대로 보낸다 → 워치가 직접 그림 (가볍고 긴 글도 OK)
        boolean text = base64.startsWith("{");
        int size = text ? 6000 : CHUNK;
        int idx = 0;
        for (int start = 0; start < base64.length(); start += size) {
            int end = Math.min(start + size, base64.length());
            boolean last = end == base64.length();
            String head = String.format("%02d", idx++) + (text ? (last ? "E" : "D") : (last ? "e" : "d")) + stamp;
            chunks.add((head + base64.substring(start, end)).getBytes(StandardCharsets.UTF_8));
        }
        if (chunks.size() > 99) { report(cb, false, "내용이 너무 큽니다"); return; }

        nodeApi.getConnectedNodes()
                .addOnSuccessListener(nodes -> {
                    if (nodes == null || nodes.isEmpty()) { report(cb, false, "연결된 워치 없음 (Mi Fitness 연결 확인)"); return; }
                    Node node = nodes.get(0);
                    // 워치 앱이 닫혀 있으면 메시지를 못 받으므로 먼저 연다
                    nodeApi.launchWearApp(node.id, "/index")
                            .addOnSuccessListener(v -> new Handler(Looper.getMainLooper())
                                    .postDelayed(() -> msgApi.sendMessage(node.id, setting)
                                            .addOnSuccessListener(x -> sendNext(msgApi, node.id, chunks, 0, cb))
                                            .addOnFailureListener(x -> sendNext(msgApi, node.id, chunks, 0, cb)), 2500)) // 워치 앱이 뜰 시간
                            .addOnFailureListener(e -> report(cb, false, "워치 앱 열기 실패: " + e.getMessage()
                                    + "\n(워치 앱 설치·워치 권한 확인)"));
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
        Wearable.getNodeApi(app).getConnectedNodes().addOnSuccessListener(nodes -> {
            if (nodes == null || nodes.isEmpty()) return;
            Wearable.getMessageApi(app).addListener(nodes.get(0).id, (id, data) -> {
                String s = new String(data, StandardCharsets.UTF_8);
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
