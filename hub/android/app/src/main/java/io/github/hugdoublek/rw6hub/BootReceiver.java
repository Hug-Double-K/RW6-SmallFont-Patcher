package io.github.hugdoublek.rw6hub;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 폰을 켜거나 앱이 업데이트되면 연결 유지를 다시 시작 (HubApp.onCreate가 처리). */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (LinkService.keep(c)) LinkService.start(c);
    }
}
