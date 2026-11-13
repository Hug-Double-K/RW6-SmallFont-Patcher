package io.github.hugdoublek.rw6hub.wear;

import java.util.List;

import io.github.hugdoublek.rw6hub.QuickApps;

public final class NodeApi {
    private final WearClient c;

    NodeApi(WearClient c) { this.c = c; }

    /** 연결·인증된 워치가 있으면 하나, 없으면 빈 목록. */
    public Task<List<Node>> getConnectedNodes() {
        return Task.success(c.nodes());
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
