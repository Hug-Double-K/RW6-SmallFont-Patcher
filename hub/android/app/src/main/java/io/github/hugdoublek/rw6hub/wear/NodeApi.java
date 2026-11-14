package io.github.hugdoublek.rw6hub.wear;

import java.util.List;

import android.os.Handler;
import android.os.Looper;

import io.github.hugdoublek.rw6hub.LinkService;
import io.github.hugdoublek.rw6hub.QuickApps;
import io.github.hugdoublek.rw6hub.WatchLink;

public final class NodeApi {
    private final WearClient c;

    NodeApi(WearClient c) { this.c = c; }

    /** 연결·인증된 워치가 있으면 하나, 없으면 빈 목록. */
    public Task<List<Node>> getConnectedNodes() {
        WatchLink l = c.link();
        if (l.isReady()) return Task.success(c.nodes());
        // 연결 중이거나 잠깐 끊긴 사이(다시 붙는 중)면 최대 12초 기다린다.
        // 연결 유지가 꺼져 있으면(앱이 막 켜진 경우 등) 켠다. 단, 사용자가 Mi Fitness에 양보했으면 그대로 둔다.
        LinkService.autoStart(c.app);
        Task<List<Node>> t = new Task<>();
        if (!l.wantsConnection()) { t.succeed(c.nodes()); return t; } // 양보 중이거나 설정이 없음
        Handler main = new Handler(Looper.getMainLooper());
        WatchLink.StateListener[] h = new WatchLink.StateListener[1];
        Runnable timeout = () -> { l.removeStateListener(h[0]); t.succeed(c.nodes()); };
        h[0] = () -> {
            if (l.isReady()) {
                l.removeStateListener(h[0]);
                main.removeCallbacks(timeout);
                t.succeed(c.nodes());
            }
        };
        l.addStateListener(h[0]);
        main.postDelayed(timeout, 12_000);
        return t;
    }

    public Task<Boolean> isWearAppInstalled(String nodeId) {
        Task<Boolean> t = new Task<>();
        if (!c.link().isReady()) { t.fail(new Exception("워치가 연결되어 있지 않음")); return t; }
        c.apps().refreshInstalled(apps -> t.succeed(c.apps().isInstalled(c.pkg)));
        return t;
    }

    /** 워치에서 퀵앱을 연다. */
    public Task<Void> launchWearApp(String nodeId, String uri) {
        QuickApps.Done[] h = new QuickApps.Done[1];
        Task<Void> t = WearClient.fromDone(h, null);
        c.apps().launch(c.pkg, uri, h[0]);
        return t;
    }
}
