package io.github.hugdoublek.rw6hub;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;

/**
 * 워치와의 한 번의 연결: 세션 시작 → 인증 → (암호화된) 메시지 주고받기.
 * 메시지 형식은 Xiaomi WearPacket(protobuf).
 */
public final class WatchSession implements SarLink.Listener {

    public interface Callback {
        void onLog(String msg);
        void onAuthed();
        void onDeviceInfo(String model, String firmware, String serial);
        void onClosed(String reason);
        /** 인증 뒤 들어온 그 밖의 메시지 (다음 단계에서 사용). */
        void onPacket(int type, int id, List<Pb.Field> packet);
    }

    // WearPacket
    private static final int F_TYPE = 1, F_ID = 2, F_ACCOUNT = 3, F_SYSTEM = 4, F_ERROR = 100;
    private static final int TYPE_ACCOUNT = 1, TYPE_SYSTEM = 2;
    // Account
    private static final int ID_AUTH_VERIFY = 26, ID_AUTH_CONFIRM = 27;
    private static final int A_ERROR = 3, A_APP_VERIFY = 30, A_DEVICE_VERIFY = 31,
            A_APP_CONFIRM = 32, A_DEVICE_CONFIRM = 33;
    // System
    private static final int ID_GET_DEVICE_INFO = 2;
    private static final int S_DEVICE_INFO = 3;

    private static final int CH_PB = 1, OP_WRITE = 1, OP_WRITE_ENC = 2;

    private final byte[] authKey;
    private final String phoneName;
    private final Callback cb;
    private SarLink link;

    private byte[] phoneRandom;
    private byte[] encKey, decKey;
    private volatile boolean authed = false;

    public WatchSession(byte[] authKey, String phoneName, Callback cb) {
        this.authKey = authKey;
        this.phoneName = phoneName;
        this.cb = cb;
    }

    public boolean isAuthed() { return authed; }

    public void start(InputStream in, OutputStream out) throws IOException {
        link = new SarLink(in, out, this);
        link.start();
    }

    public String stats() { return link == null ? "" : link.stats(); }

    public void close() {
        if (link != null) link.close("연결 해제함");
    }

    /** 인증 뒤 암호화해서 보낸다. */
    public void sendPacket(Pb.W packet) {
        byte[] plain = packet.toBytes();
        if (authed) sendL2(OP_WRITE_ENC, MiCrypto.aesCtr(encKey, plain));
        else sendL2(OP_WRITE, plain);
    }

    public void requestDeviceInfo() {
        sendPacket(new Pb.W().varint(F_TYPE, TYPE_SYSTEM).varint(F_ID, ID_GET_DEVICE_INFO));
    }

    // ---------------- SarLink.Listener ----------------

    @Override
    public void onSessionReady() {
        cb.onLog("인증 시작");
        phoneRandom = MiCrypto.random(16);
        Pb.W verify = new Pb.W().bytes(1, phoneRandom);
        Pb.W packet = new Pb.W()
                .varint(F_TYPE, TYPE_ACCOUNT)
                .varint(F_ID, ID_AUTH_VERIFY)
                .msg(F_ACCOUNT, new Pb.W().msg(A_APP_VERIFY, verify));
        sendL2(OP_WRITE, packet.toBytes());
    }

    @Override
    public void onL2(int channel, int opcode, byte[] body) {
        if (channel != CH_PB) {
            cb.onLog("다른 채널 데이터 " + channel + " (" + body.length + "B) 무시");
            return;
        }
        byte[] plain = body;
        if (opcode == OP_WRITE_ENC) {
            if (decKey == null) { cb.onLog("인증 전 암호 패킷 무시"); return; }
            plain = MiCrypto.aesCtr(decKey, body);
        }
        List<Pb.Field> pkt;
        try {
            pkt = Pb.parse(plain);
        } catch (IllegalArgumentException e) {
            cb.onLog("메시지 해석 실패: " + e.getMessage());
            return;
        }
        Pb.Field t = Pb.find(pkt, F_TYPE), i = Pb.find(pkt, F_ID);
        int type = t == null ? -1 : (int) t.varint;
        int id = i == null ? -1 : (int) i.varint;
        Pb.Field err = Pb.find(pkt, F_ERROR);
        if (err != null) cb.onLog("워치 오류 코드 " + err.varint + " (type " + type + ", id " + id + ")");

        if (type == TYPE_ACCOUNT) { onAccount(id, Pb.sub(pkt, F_ACCOUNT)); return; }
        if (type == TYPE_SYSTEM && id == ID_GET_DEVICE_INFO) {
            List<Pb.Field> sys = Pb.sub(pkt, F_SYSTEM);
            List<Pb.Field> info = sys == null ? null : Pb.sub(sys, S_DEVICE_INFO);
            if (info != null) {
                cb.onDeviceInfo(str(info, 4), str(info, 2), str(info, 1));
                return;
            }
        }
        cb.onPacket(type, id, pkt);
    }

    @Override
    public void onLog(String msg) { cb.onLog(msg); }

    @Override
    public void onClosed(String reason) {
        authed = false;
        cb.onClosed(reason);
    }

    // ------------------------------------------------------------------

    private void onAccount(int id, List<Pb.Field> acc) {
        if (acc == null) return;
        Pb.Field accErr = Pb.find(acc, A_ERROR);
        if (accErr != null && (id == ID_AUTH_VERIFY || id == ID_AUTH_CONFIRM)) {
            fail("워치가 인증을 거부함 (오류 " + accErr.varint + ")");
            return;
        }
        List<Pb.Field> dv = Pb.sub(acc, A_DEVICE_VERIFY);
        if (dv != null) { onDeviceVerify(dv); return; }
        List<Pb.Field> dc = Pb.sub(acc, A_DEVICE_CONFIRM);
        if (dc != null) {
            Pb.Field ok = Pb.find(dc, 1);
            if (ok != null && ok.varint != 0 && encKey != null) {
                authed = true;
                cb.onLog("인증 성공");
                cb.onAuthed();
            } else {
                fail("워치가 인증 확인을 거부함");
            }
        }
    }

    private void onDeviceVerify(List<Pb.Field> dv) {
        Pb.Field r = Pb.find(dv, 1), s = Pb.find(dv, 2);
        if (r == null || s == null || r.bytes.length != 16 || s.bytes.length != 32 || phoneRandom == null) {
            fail("워치 인증 응답 형식이 이상함");
            return;
        }
        byte[] watchRandom = r.bytes;
        byte[] okm = MiCrypto.kdf(authKey, phoneRandom, watchRandom);
        byte[] dk = Arrays.copyOfRange(okm, 0, 16);
        byte[] ek = Arrays.copyOfRange(okm, 16, 32);
        byte[] encNonce = Arrays.copyOfRange(okm, 36, 40);

        byte[] expect = MiCrypto.hmacSha256(dk, watchRandom, phoneRandom);
        if (!Arrays.equals(expect, s.bytes)) {
            fail("인증키가 맞지 않음 (워치 서명 불일치)");
            return;
        }
        decKey = dk;
        encKey = ek;

        byte[] appSign = MiCrypto.hmacSha256(ek, phoneRandom, watchRandom);
        byte[] companion = new Pb.W()
                .varint(1, 0)                 // ANDROID
                .str(3, phoneName)
                .varint(4, 0xFFFFFFFFL)       // app_capability
                .toBytes();
        byte[] nonce = new byte[12];
        System.arraycopy(encNonce, 0, nonce, 0, 4);
        byte[] encCompanion = MiCrypto.ccmEncrypt(ek, nonce, companion);

        Pb.W confirm = new Pb.W().bytes(1, appSign).bytes(2, encCompanion);
        Pb.W packet = new Pb.W()
                .varint(F_TYPE, TYPE_ACCOUNT)
                .varint(F_ID, ID_AUTH_CONFIRM)
                .msg(F_ACCOUNT, new Pb.W().msg(A_APP_CONFIRM, confirm));
        sendL2(OP_WRITE, packet.toBytes());
    }

    private void fail(String why) {
        cb.onLog(why);
        if (link != null) link.close(why);
    }

    private void sendL2(int opcode, byte[] payload) {
        byte[] l2 = new byte[payload.length + 2];
        l2[0] = CH_PB;
        l2[1] = (byte) opcode;
        System.arraycopy(payload, 0, l2, 2, payload.length);
        link.send(l2);
    }

    private static String str(List<Pb.Field> f, int n) {
        Pb.Field x = Pb.find(f, n);
        return x == null ? "" : x.str();
    }
}
