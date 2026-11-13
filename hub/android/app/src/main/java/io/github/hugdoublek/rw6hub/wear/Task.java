package io.github.hugdoublek.rw6hub.wear;

import android.os.Handler;
import android.os.Looper;

/**
 * Mi Fitness SDK의 Task와 같은 모양(addOnSuccessListener / addOnFailureListener)의 작은 대용품.
 * 결과는 늘 메인 스레드로 전달한다. 결과가 먼저 나와도 나중에 붙인 리스너가 받는다.
 */
public final class Task<T> {

    public interface OnSuccessListener<T> { void onSuccess(T result); }
    public interface OnFailureListener { void onFailure(Exception e); }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private boolean done, ok;
    private T result;
    private Exception error;
    private OnSuccessListener<? super T> onSuccess;
    private OnFailureListener onFailure;

    public static <T> Task<T> success(T v) {
        Task<T> t = new Task<>();
        t.succeed(v);
        return t;
    }

    public static <T> Task<T> failure(String msg) {
        Task<T> t = new Task<>();
        t.fail(new Exception(msg));
        return t;
    }

    public synchronized void succeed(T v) {
        if (done) return;
        done = true; ok = true; result = v;
        deliver();
    }

    public synchronized void fail(Exception e) {
        if (done) return;
        done = true; ok = false; error = e;
        deliver();
    }

    public synchronized Task<T> addOnSuccessListener(OnSuccessListener<? super T> l) {
        onSuccess = l;
        if (done && ok) post(() -> l.onSuccess(result));
        return this;
    }

    public synchronized Task<T> addOnFailureListener(OnFailureListener l) {
        onFailure = l;
        if (done && !ok) post(() -> l.onFailure(error));
        return this;
    }

    private void deliver() {
        if (ok && onSuccess != null) { OnSuccessListener<? super T> l = onSuccess; T r = result; post(() -> l.onSuccess(r)); }
        if (!ok && onFailure != null) { OnFailureListener l = onFailure; Exception e = error; post(() -> l.onFailure(e)); }
    }

    private static void post(Runnable r) { MAIN.post(r); }
}
