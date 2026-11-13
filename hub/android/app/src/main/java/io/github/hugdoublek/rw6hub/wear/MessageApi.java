package io.github.hugdoublek.rw6hub.wear;

import io.github.hugdoublek.rw6hub.QuickApps;

public final class MessageApi {

    /** 워치 퀵앱이 보낸 메시지를 받는 곳 (SDK와 같은 모양: nodeId, 내용). */
    public interface OnMessageReceivedListener { void onMessageReceived(String nodeId, byte[] message); }

    private final WearClient c;

    MessageApi(WearClient c) { this.c = c; }

    public Task<Void> sendMessage(String nodeId, byte[] data) {
        QuickApps.Done[] h = new QuickApps.Done[1];
        Task<Void> t = WearClient.fromDone(h, null);
        c.apps().sendMessage(c.pkg, data, h[0]);
        return t;
    }

    /** 이 퀵앱의 메시지 받기를 등록한다 (같은 퀵앱에 다시 등록하면 앞의 것은 빠짐). */
    public Task<Void> addListener(String nodeId, OnMessageReceivedListener l) {
        c.apps().removeListeners(c.pkg);
        final String id = nodeId;
        c.apps().addListener(c.pkg, (pkg, data) -> android.os.AsyncTask.THREAD_POOL_EXECUTOR.execute(
                () -> l.onMessageReceived(id, data)));
        return Task.success(null);
    }

    public Task<Void> removeListener(String nodeId) {
        c.apps().removeListeners(c.pkg);
        return Task.success(null);
    }
}
