package io.github.hugdoublek.rw6hub;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.WindowManager;

/**
 * 워치 셔터용: 폰 화면이 꺼져 있거나 잠겨 있을 때 화면을 켜고 카메라를 연다.
 * 잠겨 있으면 잠금화면 위에서 뜨는 카메라(…_SECURE)로 열기 때문에 잠금은 풀리지 않는다(카메라만 쓸 수 있음).
 * 화면에 아무것도 그리지 않는 투명한 화면이고, 카메라를 띄운 뒤 곧 닫힌다.
 */
public class WakeCameraActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true); }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // 화면을 확실히 깨움 (일부 기기는 위 설정만으로는 안 켜짐)
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            @SuppressWarnings("deprecation")
            PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, "rw6hub:shutter");
            wl.acquire(4000);
        } catch (Exception ignored) {}
        final Handler h = new Handler(Looper.getMainLooper());
        h.postDelayed(() -> {
            boolean locked = false;
            try { locked = ((KeyguardManager) getSystemService(KEYGUARD_SERVICE)).isKeyguardLocked(); } catch (Exception ignored) {}
            String log;
            try {
                startActivity(new Intent(locked ? "android.media.action.STILL_IMAGE_CAMERA_SECURE" : "android.media.action.STILL_IMAGE_CAMERA"));
                log = locked ? "잠금화면 위 카메라를 열었어요" : "카메라를 열었어요";
            } catch (Exception e) {
                try { startActivity(new Intent("android.media.action.STILL_IMAGE_CAMERA")); log = "카메라를 열었어요 (잠금화면용은 실패: " + e.getClass().getSimpleName() + ")"; }
                catch (Exception e2) { log = "카메라를 열지 못했어요 (" + e2.getClass().getSimpleName() + ")"; }
            }
            try { WatchLink.get(getApplicationContext()).log("워치 셔터: 화면을 켜고 " + log); } catch (Exception ignored) {}
            h.postDelayed(this::finish, 1200);
        }, 350);
    }
}
