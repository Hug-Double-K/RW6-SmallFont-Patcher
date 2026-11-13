package io.github.hugdoublek.rw6hub.wear;

/** 직접 연결이라 워치 권한 요청이 따로 없다 → 늘 허용으로 답한다. */
public final class AuthApi {
    AuthApi() {}

    public Task<Boolean> checkPermission(String nodeId, Permission p) {
        return Task.success(true);
    }

    public Task<Permission[]> requestPermission(String nodeId, Permission... ps) {
        return Task.success(ps);
    }
}
