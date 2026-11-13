package io.github.hugdoublek.rw6hub.wear;

public final class NotifyApi {
    private final WearClient c;

    NotifyApi(WearClient c) { this.c = c; }

    /** 워치 알림 목록에 한 줄 띄우기. */
    public Task<Integer> sendNotify(String nodeId, String title, String message) {
        boolean ok = c.apps().notify(c.pkg, c.label, title, message);
        return ok ? Task.success(0) : Task.failure("워치가 연결되어 있지 않음");
    }
}
