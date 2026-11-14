package io.github.hugdoublek.rw6hub.wear;

import android.content.Context;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.github.hugdoublek.rw6hub.QuickApps;
import io.github.hugdoublek.rw6hub.WatchLink;

/**
 * 퀵앱 하나(패키지)에 묶인 "Mi Fitness SDK 대용". 알림·메모 코드를 거의 그대로 쓰려고
 * getNodeApi / getMessageApi / getAuthApi / getNotifyApi 모양을 맞췄다.
 */
public final class WearClient {
    public final Context app;
    final String pkg;
    final String label;

    public WearClient(Context c, String pkg, String label) {
        this.app = c.getApplicationContext();
        this.pkg = pkg;
        this.label = label;
        QuickApps.get(app).register(pkg, label);
    }

    WatchLink link() { return WatchLink.get(app); }
    QuickApps apps() { return QuickApps.get(app); }

    List<Node> nodes() {
        WatchLink l = link();
        if (!l.isReady()) return Collections.emptyList();
        List<Node> out = new ArrayList<>();
        String model = l.watchModel().isEmpty() ? "워치" : l.watchModel();
        out.add(new Node(l.address(), model));
        return out;
    }

    public NodeApi nodeApi() { return new NodeApi(this); }
    public MessageApi messageApi() { return new MessageApi(this); }
    public AuthApi authApi() { return new AuthApi(); }
    public NotifyApi notifyApi() { return new NotifyApi(this); }

    static <T> Task<T> fromDone(QuickApps.Done[] holder, T value) {
        Task<T> t = new Task<>();
        holder[0] = err -> { if (err == null) t.succeed(value); else t.fail(new Exception(err)); };
        return t;
    }
}
