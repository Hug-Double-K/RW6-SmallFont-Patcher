package io.github.hugdoublek.rw6memo;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.xiaomi.xms.wearable.Wearable;
import com.xiaomi.xms.wearable.message.MessageApi;
import com.xiaomi.xms.wearable.node.Node;
import com.xiaomi.xms.wearable.node.NodeApi;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 워치로 보내는 메시지 (글자 그대로 보내고 워치가 그린다)
 *  F + 글자크기                    : 글자 크기
 *  C                               : 워치 메모 모두 지우기
 *  X + id(13)                      : 메모 하나 지우기
 *  NN + d|e + id(13) + JSON 조각   : 메모 내용 (e = 마지막)
 */
public final class WatchSender {
    private static final int CHUNK = 6000; // 글자 수 (한글 3바이트 고려)

    public interface Result { void done(boolean ok, String msg); }
    public interface Progress { void at(int done, int total); }

    public static List<byte[]> memoMessages(Memo m) {
        String json = m.toJson();
        List<byte[]> out = new ArrayList<>();
        int idx = 0;
        for (int s = 0; s < json.length(); s += CHUNK) {
            int e = Math.min(s + CHUNK, json.length());
            String head = String.format("%02d", idx++) + (e == json.length() ? "e" : "d") + m.id;
            out.add((head + json.substring(s, e)).getBytes(StandardCharsets.UTF_8));
        }
        return out;
    }

    public static byte[] fontMessage(int size) { return ("F" + size).getBytes(StandardCharsets.UTF_8); }
    public static byte[] clearMessage() { return "C".getBytes(StandardCharsets.UTF_8); }
    public static byte[] deleteMessage(String id) { return ("X" + id).getBytes(StandardCharsets.UTF_8); }

    public static void sendOne(Context ctx, Memo m, int fontSize, Result cb) {
        List<byte[]> msgs = new ArrayList<>();
        msgs.add(fontMessage(fontSize));
        msgs.addAll(memoMessages(m));
        send(ctx, msgs, null, cb);
    }

    public static void send(Context ctx, byte[] one, Result cb) {
        send(ctx, Collections.singletonList(one), null, cb);
    }

    /** 여러 메시지를 순서대로 보낸다. 진행 상황을 알려줌 */
    public static void send(Context ctx, List<byte[]> msgs, Progress pg, Result cb) {
        Context app = ctx.getApplicationContext();
        NodeApi nodeApi = Wearable.getNodeApi(app);
        MessageApi api = Wearable.getMessageApi(app);
        nodeApi.getConnectedNodes()
                .addOnSuccessListener(nodes -> {
                    if (nodes == null || nodes.isEmpty()) { post(cb, false, "연결된 워치 없음 (Mi Fitness 연결 확인)"); return; }
                    Node node = nodes.get(0);
                    // 워치 앱이 열려 있어야 받으므로 먼저 연다
                    nodeApi.launchWearApp(node.id, "/index")
                            .addOnSuccessListener(v -> new Handler(Looper.getMainLooper())
                                    .postDelayed(() -> next(api, node.id, msgs, 0, pg, cb), 1500))
                            .addOnFailureListener(e -> post(cb, false, "워치 앱 열기 실패: " + e.getMessage() + "\n(워치 앱 설치·워치 권한 확인)"));
                })
                .addOnFailureListener(e -> post(cb, false, "워치 조회 실패: " + e.getMessage()));
    }

    private static void next(MessageApi api, String nodeId, List<byte[]> msgs, int i, Progress pg, Result cb) {
        if (pg != null && (i % 10 == 0 || i == msgs.size())) {
            int d = i;
            new Handler(Looper.getMainLooper()).post(() -> pg.at(d, msgs.size()));
        }
        if (i >= msgs.size()) { post(cb, true, "워치로 보냈어요"); return; }
        api.sendMessage(nodeId, msgs.get(i))
                .addOnSuccessListener(v -> next(api, nodeId, msgs, i + 1, pg, cb))
                .addOnFailureListener(e -> post(cb, false, "전송 실패 " + (i + 1) + "/" + msgs.size() + ": " + e.getMessage()));
    }

    private static void post(Result cb, boolean ok, String msg) {
        if (cb != null) new Handler(Looper.getMainLooper()).post(() -> cb.done(ok, msg));
    }
}
