package io.github.hugdoublek.rw6hub.noti;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 앱이 업데이트되면 알림 수신 서비스를 다시 붙인다 (업데이트 뒤 실제 알림만 안 오던 문제 대비) */
public class UpdateReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context ctx, Intent intent) {
        if (intent == null || !Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) return;
        NotiListener.rebind(ctx.getApplicationContext());
        new Prefs(ctx).addHistory(NotiListener.now() + " 앱 업데이트됨 → 알림 수신 서비스 다시 연결 요청");
    }
}
