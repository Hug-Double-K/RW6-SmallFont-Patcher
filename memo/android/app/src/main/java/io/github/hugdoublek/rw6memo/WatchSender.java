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
import java.util.List;

/**
 * 워치로 보내는 메시지 형식
 *  T + id(13) + 제목            : 메모 제목 등록
 *  NN + d|e + id(13) + Base64   : 메모 그림 조각 (e = 마지막)
 *  X + id(13)                    : 메모 삭제
 */
public final class WatchSender {
    private static final int CHUNK = 20000;

    public interface Result { void done(boolean ok, String msg); }

    public static void sendMemo(Context ctx, Memo m, String b64, Result cb) {
        List<byte[]> msgs = new ArrayList<>();
        msgs.add(("T" + m.id + m.title).getBytes(StandardCharsets.UTF_8));
        int idx = 0;
        for (int s = 0; s < b64.length(); s += CHUNK) {
            int e = Math.min(s + CHUNK, b64.length());
            String head = String.format("%02d", idx++) + (e == b64.length() ? "e" : "d") + m.id;
            msgs.add((head + b64.substring(s, e)).getBytes(StandardCharsets.UTF_8));
        }
        if (idx > 99) { post(cb, false, "메모가 너무 깁니다"); return; }
        send(ctx, msgs, cb);
    }

    public static void deleteMemo(Context ctx, String id, Result cb) {
        List<byte[]> msgs = new ArrayList<>();
        msgs.add(("X" + id).getBytes(StandardCharsets.UTF_8));
        send(ctx, msgs, cb);
    }

    private static void send(Context ctx, List<byte[]> msgs, Result cb) {
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
                                    .postDelayed(() -> next(api, node.id, msgs, 0, cb), 1500))
                            .addOnFailureListener(e -> post(cb, false, "워치 앱 열기 실패: " + e.getMessage() + "\n(워치 앱 설치·워치 권한 확인)"));
                })
                .addOnFailureListener(e -> post(cb, false, "워치 조회 실패: " + e.getMessage()));
    }

    private static void next(MessageApi api, String nodeId, List<byte[]> msgs, int i, Result cb) {
        if (i >= msgs.size()) { post(cb, true, "워치로 보냈어요"); return; }
        api.sendMessage(nodeId, msgs.get(i))
                .addOnSuccessListener(v -> next(api, nodeId, msgs, i + 1, cb))
                .addOnFailureListener(e -> post(cb, false, "전송 실패 " + (i + 1) + "/" + msgs.size() + ": " + e.getMessage()));
    }

    private static void post(Result cb, boolean ok, String msg) {
        if (cb != null) new Handler(Looper.getMainLooper()).post(() -> cb.done(ok, msg));
    }
}
