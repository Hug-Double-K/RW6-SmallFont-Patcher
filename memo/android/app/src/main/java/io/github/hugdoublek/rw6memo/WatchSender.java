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
 *  F + 글자크기                            : 글자 크기
 *  C + 전체 개수                           : 워치 메모 모두 지우고 받기 시작 (화면 켜 둠)
 *  A + 개수                                : 지우지 않고 추가로 받기 시작 (화면 켜 둠)
 *  E                                       : 받기 끝 (목록 저장, 화면 꺼짐 허용)
 *  X + id(13)                              : 메모 하나 지우기
 *  P + 순번(3) + d|e + 묶음이름(13) + 조각 : 메모 여러 개를 묶은 JSON 배열 (e = 마지막 조각)
 * 작은 메모 수십 개를 한 메시지로 묶어서 메시지 수를 크게 줄인다.
 */
public final class WatchSender {
    private static final int MAX_BYTES = 18000;   // 메시지 하나 크기 (바이트)
    private static final int BUNDLE_BYTES = 16000; // 묶음 하나 크기 목표

    public interface Result { void done(boolean ok, String msg); }
    public interface Progress { void at(int done, int total); }

    /** 보낼 메시지 목록 + 이어 보내기 할 수 있는 지점(묶음 시작) */
    public static final class Plan {
        public final List<byte[]> msgs = new ArrayList<>();
        final List<Integer> starts = new ArrayList<>();
        /** 실패하면 여기부터 이어 보내면 된다 (-1 = 처음부터) */
        public int resumeAt = -1;
        public int memoCount = 0;
        void add(String s) { msgs.add(s.getBytes(StandardCharsets.UTF_8)); }
        public static Plan of(byte[] one) { Plan p = new Plan(); p.msgs.add(one); return p; }
    }

    static int utf8(String s) { return s.getBytes(StandardCharsets.UTF_8).length; }

    /** UTF-8 바이트 기준으로 자른다 (한글이 깨지지 않게 글자 단위로) */
    static List<String> splitBytes(String s, int max) {
        List<String> out = new ArrayList<>();
        int start = 0, bytes = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            int n = Character.charCount(cp);
            int b = cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
            if (bytes + b > max && i > start) { out.add(s.substring(start, i)); start = i; bytes = 0; }
            bytes += b;
            i += n;
        }
        out.add(s.substring(start));
        return out;
    }

    static void addBundle(Plan p, String name, List<String> jsons) {
        String arr = "[" + String.join(",", jsons) + "]";
        List<String> parts = splitBytes(arr, MAX_BYTES - 40);
        p.starts.add(p.msgs.size());
        for (int i = 0; i < parts.size(); i++) {
            p.add("P" + String.format("%03d", i) + (i == parts.size() - 1 ? "e" : "d") + name + parts.get(i));
        }
        p.memoCount += jsons.size();
    }

    /** 컬러노트 백업 전체: 지우기 → 글자 크기 → 묶음들 → 끝 */
    public static Plan importPlan(List<Memo> memos, int fontSize, boolean replace) {
        Plan p = new Plan();
        p.add((replace ? "C" : "A") + memos.size());
        // 묶음 이름은 보낼 때마다 달라야 워치에 있던 다른 묶음을 덮어쓰지 않는다 (시각 기반 13자리)
        long base = System.currentTimeMillis() % 1_000_000_000_000L * 10;
        p.add(fontMessageStr(fontSize));
        List<String> cur = new ArrayList<>();
        int curBytes = 0, n = 0;
        for (Memo m : memos) {
            String j = m.toJson();
            int b = utf8(j) + 1;
            if (!cur.isEmpty() && curBytes + b > BUNDLE_BYTES) {
                addBundle(p, String.format("%013d", base + n++), cur);
                cur = new ArrayList<>();
                curBytes = 0;
            }
            cur.add(j);
            curBytes += b;
        }
        if (!cur.isEmpty()) addBundle(p, String.format("%013d", base + n), cur);
        p.add("E");
        return p;
    }

    static String fontMessageStr(int size) { return "F" + size; }
    public static byte[] fontMessage(int size) { return fontMessageStr(size).getBytes(StandardCharsets.UTF_8); }
    public static byte[] clearMessage() { return "C0".getBytes(StandardCharsets.UTF_8); }
    public static byte[] deleteMessage(String id) { return ("X" + id).getBytes(StandardCharsets.UTF_8); }

    public static void sendOne(Context ctx, Memo m, int fontSize, Result cb) {
        Plan p = new Plan();
        p.add(fontMessageStr(fontSize));
        addBundle(p, m.id, Collections.singletonList(m.toJson()));
        send(ctx, p, 0, null, cb);
    }

    public static void send(Context ctx, byte[] one, Result cb) {
        send(ctx, Plan.of(one), 0, null, cb);
    }

    /** from 번째 메시지부터 순서대로 보낸다 */
    public static void send(Context ctx, Plan plan, int from, Progress pg, Result cb) {
        Context app = ctx.getApplicationContext();
        NodeApi nodeApi = Wearable.getNodeApi(app);
        MessageApi api = Wearable.getMessageApi(app);
        nodeApi.getConnectedNodes()
                .addOnSuccessListener(nodes -> {
                    if (nodes == null || nodes.isEmpty()) { fail(plan, from, cb, "연결된 워치 없음 (Mi Fitness 연결 확인)"); return; }
                    Node node = nodes.get(0);
                    // 워치 앱이 열려 있어야 받으므로 먼저 연다
                    nodeApi.launchWearApp(node.id, "/index")
                            .addOnSuccessListener(v -> new Handler(Looper.getMainLooper())
                                    .postDelayed(() -> next(api, node.id, plan, from, 0, pg, cb), 2500))
                            .addOnFailureListener(e -> fail(plan, from, cb, "워치 앱 열기 실패: " + e.getMessage() + "\n(워치 앱 설치·워치 권한 확인)"));
                })
                .addOnFailureListener(e -> fail(plan, from, cb, "워치 조회 실패: " + e.getMessage()));
    }

    private static void next(MessageApi api, String nodeId, Plan plan, int i, int tries, Progress pg, Result cb) {
        List<byte[]> msgs = plan.msgs;
        if (pg != null && tries == 0) {
            int d = i;
            new Handler(Looper.getMainLooper()).post(() -> pg.at(d, msgs.size()));
        }
        if (i >= msgs.size()) { plan.resumeAt = -1; post(cb, true, "워치로 보냈어요"); return; }
        api.sendMessage(nodeId, msgs.get(i))
                .addOnSuccessListener(v -> next(api, nodeId, plan, i + 1, 0, pg, cb))
                .addOnFailureListener(e -> {
                    if (tries < 3) { // 잠깐 끊긴 거면 다시
                        new Handler(Looper.getMainLooper()).postDelayed(
                                () -> next(api, nodeId, plan, i, tries + 1, pg, cb), 1500L * (tries + 1));
                    } else {
                        fail(plan, i, cb, "전송 실패 " + (i + 1) + "/" + msgs.size() + ": " + e.getMessage());
                    }
                });
    }

    /** 실패 지점이 들어 있는 묶음의 처음부터 이어 보내도록 기억 */
    private static void fail(Plan plan, int i, Result cb, String msg) {
        int r = -1;
        for (int s : plan.starts) if (s <= i) r = s;
        plan.resumeAt = r;
        post(cb, false, msg);
    }

    private static void post(Result cb, boolean ok, String msg) {
        if (cb != null) new Handler(Looper.getMainLooper()).post(() -> cb.done(ok, msg));
    }
}
