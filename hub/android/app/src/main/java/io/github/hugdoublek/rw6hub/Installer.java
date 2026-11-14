package io.github.hugdoublek.rw6hub;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 퀵앱(.rpk) · 워치페이스(.bin) · 펌웨어 설치.
 * 순서: ① 설치 준비 요청 → ② 대용량(MASS) 준비 → ③ 조각 전송(채널 2) → ④ 결과.
 * 전송 중 연결이 끊기면 다시 붙은 뒤 워치가 이미 받은 만큼 건너뛰고 이어 보낸다.
 */
public final class Installer implements WatchLink.PacketListener {

    public enum Kind {
        QUICKAPP(64, "퀵앱"), WATCHFACE(16, "워치페이스"), FIRMWARE(32, "펌웨어");
        final int massType;
        public final String label;
        Kind(int t, String l) { massType = t; label = l; }
    }

    public interface Listener {
        void stage(String text);
        void progress(long done, long total);
        void done(boolean ok, String message);
    }

    /** 설치할 파일 정보 (파일을 읽고 확인한 결과) */
    public static final class Package {
        public Kind kind;
        public byte[] data;
        public String name = "";      // 화면 표시용
        public String id = "";        // 퀵앱 패키지 이름 / 워치페이스 ID
        public long versionCode = 0;
        public String error = null;   // 설치하면 안 되는 이유 (있으면 설치 막음)
    }

    private static final int MAX_OUTSTANDING = 6;
    private static final int MAX_RESUMES = 6;

    private static Installer INSTANCE;

    public static synchronized Installer get(Context c) {
        if (INSTANCE == null) {
            INSTANCE = new Installer(c.getApplicationContext());
            WatchLink.get(c).addPacketListener(INSTANCE);
        }
        return INSTANCE;
    }

    private final Context app;
    private final Handler h;
    private final Handler main = new Handler(Looper.getMainLooper());

    // 진행 중인 설치
    private Package pkg;
    private Listener ls;
    private byte[] md5;
    private int stage = 0; // 0 없음, 1 설치 준비, 2 대용량 준비, 3 전송, 4 결과 대기
    private int sliceHint = 0;
    private int resumes = 0;
    private int gen = 0;
    private boolean triedHighVersion = false;
    // 전송 상태
    private byte[] inner;
    private int fragLen, totalParts, nextPart, outstanding, ackedParts;
    private long baseSent;

    private Installer(Context app) {
        this.app = app;
        HandlerThread t = new HandlerThread("installer");
        t.start();
        h = new Handler(t.getLooper());
    }

    private WatchLink link() { return WatchLink.get(app); }

    public boolean busy() { return stage != 0; }

    // ======================= 파일 읽기 =======================

    public static Package inspect(Kind kind, byte[] data, String fileName) {
        Package p = new Package();
        p.kind = kind;
        p.data = data;
        p.name = fileName == null ? "" : fileName;
        boolean zip = data.length >= 4 && data[0] == 'P' && data[1] == 'K' && data[2] == 3 && data[3] == 4;
        boolean faceMagic = data.length >= 8 && (data[0] & 0xFF) == 0x5A && (data[1] & 0xFF) == 0xA5
                && (data[2] & 0xFF) == 0x34 && (data[3] & 0xFF) == 0x12;
        switch (kind) {
            case QUICKAPP: {
                if (!zip) { p.error = "퀵앱(.rpk) 파일이 아니에요"; break; }
                try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(data))) {
                    ZipEntry e;
                    while ((e = z.getNextEntry()) != null) {
                        if (e.getName().equals("manifest.json")) {
                            ByteArrayOutputStream bo = new ByteArrayOutputStream();
                            byte[] buf = new byte[8192];
                            int n;
                            while ((n = z.read(buf)) > 0) bo.write(buf, 0, n);
                            JSONObject m = new JSONObject(bo.toString("UTF-8"));
                            p.id = m.optString("package");
                            p.versionCode = m.optLong("versionCode", 1);
                            String nm = m.optString("name");
                            if (!nm.isEmpty()) p.name = nm;
                            break;
                        }
                    }
                } catch (Exception ex) {
                    p.error = "퀵앱 파일을 읽지 못했어요: " + ex.getMessage();
                }
                if (p.error == null && p.id.isEmpty()) p.error = "퀵앱 파일에 패키지 이름(manifest.json)이 없어요";
                break;
            }
            case WATCHFACE: {
                if (zip) { p.error = "압축(zip) 파일이에요. 워치페이스 .bin 파일을 골라 주세요"; break; }
                p.id = watchfaceId(data);
                if (p.id == null) { p.error = "워치페이스 ID를 찾지 못했어요 (워치페이스 .bin이 맞나요?)"; break; }
                p.versionCode = faceMagic ? le32(data, 4) : 0x010000;
                break;
            }
            case FIRMWARE: {
                // 예전 사고(워치페이스를 펌웨어로 설치) 방지: 다른 종류로 보이는 파일은 막음
                if (faceMagic || watchfaceId(data) != null && data.length < 8 * 1024 * 1024) {
                    p.error = "이 파일은 워치페이스로 보여요. 펌웨어로 설치하면 안 됩니다";
                } else if (zip) {
                    p.error = "압축(zip/rpk) 파일이에요. 펌웨어 파일이 아니에요";
                } else if (data.length < 1024 * 1024) {
                    p.error = "파일이 너무 작아요 (" + data.length / 1024 + "KB). 펌웨어 파일이 아닌 것 같아요";
                }
                p.id = "firmware";
                break;
            }
        }
        return p;
    }

    /** .bin 34번째 바이트부터 24바이트 안에서 9자리 또는 12자리 영숫자 */
    static String watchfaceId(byte[] d) {
        int off = 34, len = 24;
        if (d.length < off + len) return null;
        int i = off;
        while (i < off + len) {
            if (!alnum(d[i])) { i++; continue; }
            int s = i;
            while (i < off + len && alnum(d[i])) i++;
            int n = i - s;
            if (n == 9 || n == 12) return new String(d, s, n, StandardCharsets.US_ASCII);
        }
        return null;
    }

    private static boolean alnum(byte b) {
        return (b >= '0' && b <= '9') || (b >= 'a' && b <= 'z') || (b >= 'A' && b <= 'Z');
    }

    private static long le32(byte[] d, int i) {
        return (d[i] & 0xFFL) | ((d[i + 1] & 0xFFL) << 8) | ((d[i + 2] & 0xFFL) << 16) | ((d[i + 3] & 0xFFL) << 24);
    }

    // ======================= 설치 시작 =======================

    public void start(Package p, Listener l) {
        h.post(() -> {
            if (stage != 0) { post(() -> l.done(false, "이미 설치 중이에요")); return; }
            if (p.error != null) { post(() -> l.done(false, p.error)); return; }
            if (!link().isReady()) { post(() -> l.done(false, "워치가 연결되어 있지 않아요")); return; }
            pkg = p;
            ls = l;
            resumes = 0;
            triedHighVersion = false;
            try {
                md5 = MessageDigest.getInstance("MD5").digest(p.data);
            } catch (Exception e) {
                finish(false, "MD5 계산 실패");
                return;
            }
            link().log(p.kind.label + " 설치 시작: " + p.name + " (" + p.data.length / 1024 + "KB)");
            beginRound();
        });
    }

    public void cancel() {
        h.post(() -> { if (stage != 0) finish(false, "취소했어요"); });
    }

    /** ①부터 한 바퀴 (처음 또는 끊긴 뒤 이어 보내기) */
    private void beginRound() {
        gen++;
        stage = 1;
        sliceHint = 0;
        stageText(resumes == 0 ? "워치에 설치 준비를 요청하는 중…" : "다시 연결됨 → 이어 보내기 준비 중…");
        Pb.W req;
        switch (pkg.kind) {
            case QUICKAPP:
                req = new Pb.W().varint(1, 20).varint(2, 1)
                        .msg(22, new Pb.W().msg(2, new Pb.W()
                                .str(1, pkg.id)
                                .varint(2, triedHighVersion ? 114514 : pkg.versionCode)
                                .varint(3, pkg.data.length)));
                break;
            case WATCHFACE:
                req = new Pb.W().varint(1, 4).varint(2, 4)
                        .msg(6, new Pb.W().msg(6, new Pb.W()
                                .str(1, pkg.id)
                                .varint(2, pkg.data.length)
                                .varint(3, pkg.versionCode)));
                break;
            default: // FIRMWARE
                req = new Pb.W().varint(1, 2).varint(2, 5)
                        .msg(4, new Pb.W().msg(16, new Pb.W()
                                .bool(1, true)
                                .varint(2, 0)
                                .str(3, "99.99.99")
                                .str(4, Hex.of(md5))
                                .str(5, "Mi Manager firmware update")
                                .str(6, "")
                                .varint(7, pkg.data.length)));
                break;
        }
        if (!link().send(req)) { lostLink(); return; }
        timeout(30_000, "워치가 설치 준비에 답하지 않아요");
    }

    private void onInstallPrepare(int status, int slice) {
        if (stage != 1) return;
        if (status != 0) {
            if (pkg.kind == Kind.QUICKAPP && (status == 2 || status == 5) && !triedHighVersion) {
                // 같은 버전/낮은 버전이라 거절 → 높은 버전 번호로 한 번 더
                triedHighVersion = true;
                link().log("워치가 설치 준비를 거절(" + statusText(status) + ") → 다시 시도");
                beginRound();
                return;
            }
            finish(false, "워치가 설치를 거절했어요: " + statusText(status));
            return;
        }
        sliceHint = slice;
        stage = 2;
        gen++;
        stageText("전송 준비 중…");
        Pb.W req = new Pb.W().varint(1, 22).varint(2, 0)
                .msg(24, new Pb.W().msg(1, new Pb.W()
                        .varint(1, pkg.kind.massType)
                        .bytes(2, md5)
                        .varint(3, pkg.data.length)));
        if (!link().send(req)) { lostLink(); return; }
        timeout(30_000, "워치가 전송 준비에 답하지 않아요");
    }

    private void onMassPrepare(int status, long remained, int slice) {
        if (stage != 2) return;
        if (status != 0) { finish(false, "워치가 전송을 거절했어요: " + statusText(status)); return; }
        int sl = slice > 0 ? slice : sliceHint > 0 ? sliceHint : 4096;
        baseSent = Math.max(0, Math.min(remained, pkg.data.length));
        if (baseSent > 0) link().log("워치가 이미 " + baseSent / 1024 + "KB 받아 둠 → 그 뒤부터 보냄");
        // 본문 = 0 | 종류 | md5(16) | 남은 길이(LE32) | 남은 데이터 | CRC32(LE)
        int remain = (int) (pkg.data.length - baseSent);
        inner = new byte[1 + 1 + 16 + 4 + remain + 4];
        int o = 0;
        inner[o++] = 0;
        inner[o++] = (byte) pkg.kind.massType;
        System.arraycopy(md5, 0, inner, o, 16); o += 16;
        inner[o++] = (byte) remain; inner[o++] = (byte) (remain >>> 8); inner[o++] = (byte) (remain >>> 16); inner[o++] = (byte) (remain >>> 24);
        System.arraycopy(pkg.data, (int) baseSent, inner, o, remain); o += remain;
        CRC32 crc = new CRC32();
        crc.update(inner, 0, o);
        long c = crc.getValue();
        inner[o++] = (byte) c; inner[o++] = (byte) (c >>> 8); inner[o++] = (byte) (c >>> 16); inner[o] = (byte) (c >>> 24);

        fragLen = Math.max(64, sl - 6); // 채널(1)+op(1)+전체 조각 수(2)+조각 번호(2) 빼고
        totalParts = (inner.length + fragLen - 1) / fragLen;
        if (totalParts > 0xFFFF) { finish(false, "파일이 너무 커요"); return; }
        nextPart = 0;
        outstanding = 0;
        ackedParts = 0;
        stage = 3;
        gen++;
        stageText("보내는 중…");
        progress();
        pump();
    }

    /** 확인이 오는 만큼만 몇 조각씩 앞서 보낸다 */
    private void pump() {
        final int myGen = gen;
        while (stage == 3 && outstanding < MAX_OUTSTANDING && nextPart < totalParts) {
            int idx = nextPart++;
            int from = idx * fragLen;
            int n = Math.min(fragLen, inner.length - from);
            byte[] l2 = new byte[4 + n];
            l2[0] = (byte) totalParts; l2[1] = (byte) (totalParts >>> 8);
            l2[2] = (byte) (idx + 1); l2[3] = (byte) ((idx + 1) >>> 8);
            System.arraycopy(inner, from, l2, 4, n);
            outstanding++;
            boolean ok = link().sendRaw(2, l2, delivered -> h.post(() -> {
                if (myGen != gen || stage != 3) return;
                outstanding--;
                if (!delivered) { lostLink(); return; }
                ackedParts++;
                progress();
                if (ackedParts >= totalParts) {
                    stage = 4;
                    gen++;
                    stageText(pkg.kind == Kind.FIRMWARE ? "전송 끝 → 워치가 펌웨어를 확인하는 중…" : "전송 끝 → 워치가 설치하는 중…");
                    timeout(pkg.kind == Kind.FIRMWARE ? 180_000 : 90_000,
                            pkg.kind == Kind.FIRMWARE ? null : "설치 결과를 받지 못했어요 (워치에서 확인해 보세요)");
                } else {
                    pump();
                }
            }));
            if (!ok) { outstanding--; lostLink(); return; }
        }
    }

    private void progress() {
        long total = pkg.data.length;
        long done = baseSent + (long) ((total - baseSent) * (ackedParts / (double) Math.max(1, totalParts)));
        post(() -> ls.progress(Math.min(done, total), total));
    }

    private void onResult(boolean ok, String detail) {
        if (stage != 3 && stage != 4) return;
        if (stage == 3 && ok) return; // 전송 중 결과는 무시
        finish(ok, ok ? pkg.kind.label + " 설치 완료" + (detail == null ? "" : " (" + detail + ")")
                : pkg.kind.label + " 설치 실패" + (detail == null ? "" : ": " + detail));
    }

    // ======================= 끊김 · 시간 초과 =======================

    private void lostLink() {
        if (stage == 0) return;
        if (stage == 4 && pkg.kind == Kind.FIRMWARE) {
            finish(true, "펌웨어를 다 보냈고 워치가 재시작했어요. 업데이트가 끝날 때까지 워치를 그대로 두세요");
            return;
        }
        if (resumes >= MAX_RESUMES) { finish(false, "연결이 자꾸 끊겨서 멈췄어요. 다시 시도하면 이어서 보내요"); return; }
        resumes++;
        stage = 5; // 재연결 대기
        gen++;
        final int myGen = gen;
        stageText("연결이 끊김 → 다시 붙기를 기다리는 중… (" + resumes + "/" + MAX_RESUMES + ")");
        WatchLink.StateListener[] l = new WatchLink.StateListener[1];
        l[0] = () -> h.post(() -> {
            if (myGen != gen || stage != 5) { link().removeStateListener(l[0]); return; }
            if (link().isReady()) {
                link().removeStateListener(l[0]);
                h.postDelayed(() -> { if (myGen == gen && stage == 5) beginRound(); }, 1500);
            }
        });
        link().addStateListener(l[0]);
        h.postDelayed(() -> {
            link().removeStateListener(l[0]);
            if (myGen == gen && stage == 5) finish(false, "워치가 다시 연결되지 않아요");
        }, 60_000);
        l[0].onStateChanged();
    }

    private void timeout(long ms, String failText) {
        final int myGen = gen;
        h.postDelayed(() -> {
            if (myGen != gen || stage == 0) return;
            if (failText == null) finish(true, "펌웨어를 다 보냈어요. 워치가 업데이트 후 재시작할 거예요");
            else finish(false, failText);
        }, ms);
    }

    private void finish(boolean ok, String msg) {
        stage = 0;
        gen++;
        inner = null;
        Listener l = ls;
        link().log((ok ? "✔ " : "✘ ") + msg);
        if (l != null) post(() -> l.done(ok, msg));
        if (ok && pkg != null && pkg.kind == Kind.QUICKAPP) QuickApps.get(app).refreshInstalled(null);
        pkg = null;
        ls = null;
    }

    private void stageText(String s) {
        Listener l = ls;
        if (l != null) post(() -> l.stage(s));
    }

    private void post(Runnable r) { main.post(r); }

    static String statusText(int s) {
        switch (s) {
            case 1: return "워치가 바쁨";
            case 2: return "이미 같은 것이 설치됨";
            case 3: return "워치 저장 공간 부족";
            case 4: return "워치 배터리 부족";
            case 5: return "더 낮은 버전 (버전을 내릴 수 없음)";
            case 6: return "지원하지 않는 작업";
            case 7: return "설치 개수 한도 초과";
            case 8: return "네트워크 오류";
            default: return "실패 (코드 " + s + ")";
        }
    }

    // ======================= 워치 응답 =======================

    @Override
    public void onPacket(int type, int id, List<Pb.Field> p) {
        if (type == 4 || type == 20 || type == 22 || (type == 2 && id == 5)) {
            h.post(() -> handle(type, id, p));
        }
    }

    private void handle(int type, int id, List<Pb.Field> p) {
        if (stage == 0) return;
        switch (type) {
            case 4: { // 워치페이스
                if (pkg.kind != Kind.WATCHFACE) return;
                List<Pb.Field> wf = Pb.sub(p, 6);
                if (wf == null) return;
                Pb.Field st = Pb.find(wf, 5);
                if (st != null && st.bytes == null) { onInstallPrepare((int) st.varint, 0); return; }
                List<Pb.Field> rep = Pb.sub(wf, 9);
                if (rep != null) { onInstallPrepare(v(rep, 2), v(rep, 4)); return; }
                List<Pb.Field> res = Pb.sub(wf, 7);
                if (res != null) {
                    int code = v(res, 2);
                    onResult(code == 2 || code == 3, code == 0 ? "검증 실패" : code == 1 ? "설치 실패" : null);
                }
                return;
            }
            case 20: { // 퀵앱
                if (pkg.kind != Kind.QUICKAPP) return;
                List<Pb.Field> tp = Pb.sub(p, 22);
                if (tp == null) return;
                List<Pb.Field> resp = Pb.sub(tp, 3);
                if (resp != null) { onInstallPrepare(v(resp, 1), v(resp, 2)); return; }
                List<Pb.Field> res = Pb.sub(tp, 4);
                if (res != null) {
                    int code = v(res, 1);
                    onResult(code == 0, code == 2 ? "검증 실패" : code == 1 ? "설치 실패" : null);
                }
                return;
            }
            case 2: { // 펌웨어 준비 응답 / 진행
                if (pkg.kind != Kind.FIRMWARE) return;
                List<Pb.Field> sys = Pb.sub(p, 4);
                List<Pb.Field> r = sys == null ? null : Pb.sub(sys, 17);
                if (r == null) return;
                int status = v(r, 1);
                if (stage == 1) onInstallPrepare(status, v(r, 2));
                else if (stage == 4) onResult(status == 0, status == 0 ? null : statusText(status));
                return;
            }
            case 22: { // 대용량 준비 응답
                List<Pb.Field> m = Pb.sub(p, 24);
                List<Pb.Field> r = m == null ? null : Pb.sub(m, 2);
                if (r == null) return;
                Pb.Field rem = Pb.find(r, 4);
                onMassPrepare(v(r, 2), rem == null ? 0 : rem.varint, v(r, 5));
                return;
            }
        }
    }

    private static int v(List<Pb.Field> f, int n) {
        Pb.Field x = Pb.find(f, n);
        return x == null ? 0 : (int) x.varint;
    }
}
