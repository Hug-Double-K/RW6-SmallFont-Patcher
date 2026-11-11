package io.github.hugdoublek.rw6ai;

import android.net.Uri;
import android.telecom.CallAudioState;
import android.telecom.Connection;
import android.telecom.ConnectionRequest;
import android.telecom.ConnectionService;
import android.telecom.DisconnectCause;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;

/**
 * 시험용 '가짜 인터넷 전화'. 안드로이드에 "앱 전화가 왔다"고 정식으로 알려,
 * Mi Fitness가 워치에 전화로 전달하고 워치가 통화용 블루투스로 붙는지 확인한다. (실제로 어디에도 전화를 걸지 않음)
 */
public class AiConnectionService extends ConnectionService {
    interface Log { void say(String s); }
    static volatile Log logger;
    static volatile AiConnection current;

    static void log(String s) { Log l = logger; if (l != null) l.say(s); }

    @Override
    public Connection onCreateIncomingConnection(PhoneAccountHandle h, ConnectionRequest req) {
        AiConnection c = new AiConnection();
        c.setConnectionProperties(Connection.PROPERTY_SELF_MANAGED);
        c.setAudioModeIsVoip(true);
        c.setAddress(Uri.fromParts("tel", "000", null), TelecomManager.PRESENTATION_ALLOWED);
        c.setCallerDisplayName("RW6 AI", TelecomManager.PRESENTATION_ALLOWED);
        c.setRinging();
        current = c;
        log("   [전화] 수신 전화가 만들어짐 (울리는 중)");
        return c;
    }

    @Override
    public void onCreateIncomingConnectionFailed(PhoneAccountHandle h, ConnectionRequest req) {
        log("   [전화] ✘ 시스템이 수신 전화를 거절함");
    }

    static final class AiConnection extends Connection {
        volatile boolean answered = false;
        volatile boolean ended = false;
        /** 지금 소리 경로 (CallAudioState.ROUTE_*) */
        volatile int route = 0;

        @Override public void onShowIncomingCallUi() { log("   [전화] 시스템이 전화로 받아들임"); }

        @Override public void onAnswer() {
            if (answered || ended) return;
            answered = true;
            setActive();
            log("   [전화] 받음 → 통화 중");
        }

        @Override public void onAnswer(int videoState) { onAnswer(); }

        @Override public void onReject() { log("   [전화] 거절됨"); end(DisconnectCause.REJECTED); }

        @Override public void onDisconnect() { log("   [전화] 끊김"); end(DisconnectCause.LOCAL); }

        @Override public void onAbort() { end(DisconnectCause.CANCELED); }

        @Override public void onCallAudioStateChanged(CallAudioState st) {
            route = st.getRoute();
            String r;
            switch (st.getRoute()) {
                case CallAudioState.ROUTE_BLUETOOTH: r = "블루투스"; break;
                case CallAudioState.ROUTE_EARPIECE: r = "폰 수화부"; break;
                case CallAudioState.ROUTE_SPEAKER: r = "폰 스피커"; break;
                case CallAudioState.ROUTE_WIRED_HEADSET: r = "유선"; break;
                default: r = "기타(" + st.getRoute() + ")";
            }
            boolean btOk = (st.getSupportedRouteMask() & CallAudioState.ROUTE_BLUETOOTH) != 0;
            log("   [전화] 소리 경로: " + r + " / 블루투스 선택 가능: " + btOk);
        }

        void end(int cause) {
            if (ended) return;
            ended = true;
            try { setDisconnected(new DisconnectCause(cause)); destroy(); } catch (Throwable ignored) { }
            if (current == this) current = null;
        }
    }
}
