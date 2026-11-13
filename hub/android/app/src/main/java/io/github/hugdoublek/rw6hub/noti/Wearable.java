package io.github.hugdoublek.rw6hub.noti;

import android.content.Context;

import io.github.hugdoublek.rw6hub.wear.AuthApi;
import io.github.hugdoublek.rw6hub.wear.MessageApi;
import io.github.hugdoublek.rw6hub.wear.NodeApi;
import io.github.hugdoublek.rw6hub.wear.NotifyApi;
import io.github.hugdoublek.rw6hub.wear.WearClient;

/**
 * 이 기능의 워치 퀵앱(RW6 알림, io.github.hugdoublek.rw6noti)과 통하는 창구.
 * 예전 Mi Fitness SDK의 Wearable과 같은 모양이라 옮겨 온 코드를 거의 고치지 않아도 된다.
 */
public final class Wearable {
    private Wearable() {}

    public static final String WATCH_PACKAGE = "io.github.hugdoublek.rw6noti";
    public static final String LABEL = "RW6 알림";
    /** 워치 퀵앱과 버전을 맞춰 보는 데 쓰는 이 기능의 버전 */
    public static final String PHONE_VERSION = "4.3";

    private static WearClient client;

    public static synchronized WearClient client(Context c) {
        if (client == null) client = new WearClient(c, WATCH_PACKAGE, LABEL);
        return client;
    }

    static NodeApi getNodeApi(Context c) { return client(c).nodeApi(); }
    static MessageApi getMessageApi(Context c) { return client(c).messageApi(); }
    static AuthApi getAuthApi(Context c) { return client(c).authApi(); }
    static NotifyApi getNotifyApi(Context c) { return client(c).notifyApi(); }
}
