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
    private static final long[] TEXT_RETRY = {200, 600, 1100, 1700, 2500, 3500, 5000, 6500, 8000, 9500};
    /** 사진은 조각이 많아 드물게: 앱이 뜰 시간 뒤 한 번, 확인이 없으면 몇 번 더 */
    private static final long[] IMG_RETRY = {1500, 7000, 12000};
    private static final java.util.Map<String, Runnable> ACK = new java.util.concurrent.ConcurrentHashMap<>();
    /** 워치가 확인 응답을 보낸 적이 있는지 (지원되는 걸 확인하면 중복 전송을 줄임) */
    private static volatile boolean ackSeen = false;
    /** 확인 결과를 기록에 남길 곳 (NotiListener가 설정) */
    static volatile Result ackLog = null;

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
                    java.util.concurrent.atomic.AtomicBoolean acked = new java.util.concurrent.atomic.AtomicBoolean(false);
                    Result once = (ok, msg) -> {
                        if (!done.compareAndSet(false, true)) return;
                        readyWaiter = null;
                        if (ok) { lastOk = System.currentTimeMillis(); lastSetting = settingStr; }
                        report(cb, ok, msg);
                    };
                    // 워치가 "받았어(ack)"라고 답하면 더 보내지 않음
                    ACK.put(stamp, () -> {
                        if (!acked.compareAndSet(false, true)) return;
                        ackSeen = true;
                        once.done(true, "워치 수신 확인");
                        if (ackLog != null) ackLog.done(true, stamp);
                    });
                    // 한 번에 한 묶음만 보냄 (겹치면 워치에서 조각 순서가 섞임)
                    java.util.concurrent.atomic.AtomicBoolean inFlight = new java.util.concurrent.atomic.AtomicBoolean(false);
                    java.util.concurrent.atomic.AtomicInteger sentTimes = new java.util.concurrent.atomic.AtomicInteger(0);
                    Runnable sendAll = () -> {
                        if (acked.get() || !inFlight.compareAndSet(false, true)) return;
                        // 사진은 확인 응답이 지원될 때만 다시 보냄 (아니면 같은 사진을 여러 번 보내 느려짐)
                        if (!text && sentTimes.get() > 0 && !ackSeen) { inFlight.set(false); return; }
                        sentTimes.incrementAndGet();
                        sendNext(msgApi, node.id, chunks, 0, (ok, m) -> {
                            inFlight.set(false);
                            if (ok && !ackSeen) once.done(true, m);
                            if (!ok && !text) once.done(false, m);
                        });
                    };
                    readyWaiter = sendAll;
                    // 화면이 꺼져 있으면 워치 앱이 잠들어 있을 수 있어 매번 연다 (켜져 있으면 금방 끝남)
                    Runnable launch = () -> { if (!acked.get()) nodeApi.launchWearApp(node.id, "/index"); };
                    nodeApi.launchWearApp(node.id, "/index")
                            .addOnSuccessListener(v -> {
                                long[] times = text ? TEXT_RETRY : IMG_RETRY;
                                for (long t : times) main.postDelayed(sendAll, t);
                                // 그래도 안 받으면 앱을 한 번 더 열고 계속
                                main.postDelayed(launch, text ? 4200 : 9000);
                                main.postDelayed(() -> {
                                    ACK.remove(stamp);
                                    if (!acked.get()) {
                                        once.done(true, ackSeen ? "워치 확인 없음 (못 받았을 수 있음)" : "전송함");
                                        if (ackLog != null && ackSeen) ackLog.done(false, stamp);
                                    }
                                }, text ? 11000 : 20000);
                            })
                            .addOnFailureListener(e -> { ACK.remove(stamp); once.done(false, "워치 앱 열기 실패: " + e.getMessage() + "\n(워치 앱 설치·워치 권한 확인)"); });
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
                if (s.contains("ack")) { // 워치가 이 이름을 받았다고 확인
                    java.util.regex.Matcher am = java.util.regex.Pattern.compile("\\d{13}").matcher(s);
                    if (am.find()) { Runnable r = ACK.remove(am.group()); if (r != null) new Handler(Looper.getMainLooper()).post(r); }
                    return;
                }
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
