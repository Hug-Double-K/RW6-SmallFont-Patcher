package io.github.hugdoublek.rw6noti;

import android.content.Context;
import android.content.SharedPreferences;

/** 폰 앱 설정 (글자 크기, 대상 앱, 사진 포함 여부) */
public final class Prefs {
    public static final String KAKAO = "com.kakao.talk";
    public static final String[] SMS = {
            "com.google.android.apps.messaging",
            "com.samsung.android.messaging",
            "com.android.mms"
    };

    private final SharedPreferences sp;

    public Prefs(Context c) {
        sp = c.getApplicationContext().getSharedPreferences("rw6noti", Context.MODE_PRIVATE);
    }

    /** 본문 글자 크기(px). 워치 화면 폭 432px 기준 */
    public int fontSize() { return sp.getInt("fontSize", 26); }
    public void setFontSize(int v) { sp.edit().putInt("fontSize", v).apply(); }

    public boolean kakao() { return sp.getBoolean("kakao", true); }
    public void setKakao(boolean v) { sp.edit().putBoolean("kakao", v).apply(); }

    public boolean sms() { return sp.getBoolean("sms", true); }
    public void setSms(boolean v) { sp.edit().putBoolean("sms", v).apply(); }

    /** 직접 고른 그 밖의 앱 (패키지 이름 목록) */
    public java.util.Set<String> otherApps() {
        return new java.util.HashSet<>(sp.getStringSet("otherApps", new java.util.HashSet<>()));
    }
    public void setOtherApps(java.util.Set<String> v) { sp.edit().putStringSet("otherApps", new java.util.HashSet<>(v)).apply(); }

    public boolean pictures() { return sp.getBoolean("pictures", true); }
    public void setPictures(boolean v) { sp.edit().putBoolean("pictures", v).apply(); }

    /** 사진이 있는 알림만 워치 앱으로 보냄 (글 알림은 기본 팝업에 맡김) */
    public boolean photoOnly() { return sp.getBoolean("photoOnly", true); }
    public void setPhotoOnly(boolean v) { sp.edit().putBoolean("photoOnly", v).apply(); }

    /** 알림이 오면 워치에서 진동 / 화면 깨우기 */
    public boolean vibrate() { return sp.getBoolean("vibrate", false); }
    public void setVibrate(boolean v) { sp.edit().putBoolean("vibrate", v).apply(); }
    public boolean wake() { return sp.getBoolean("wake", false); }
    /** 화면 깨우기: 켜 두는 시간(0.1초 단위). 3.0~30.0초, 0.5초 간격, 기본 5초 */
    public int wakeTenths() { return Math.max(30, Math.min(300, sp.getInt("wakeTenths", 50))); }
    public void setWakeTenths(int v) { sp.edit().putInt("wakeTenths", Math.max(30, Math.min(300, v))).apply(); }
    /** 폰이 방해금지면 워치도 조용히 (진동·화면 켜기·워치 알림 목록 표시 안 함) */
    public boolean followDnd() { return sp.getBoolean("followDnd", true); }
    public void setFollowDnd(boolean v) { sp.edit().putBoolean("followDnd", v).apply(); }
    public void setWake(boolean v) { sp.edit().putBoolean("wake", v).apply(); }

    /** 워치 알림 목록(위→아래 스와이프)에 'OO 알림 - N건' 표시 */
    public boolean watchList() { return sp.getBoolean("watchList", false); }
    public void setWatchList(boolean v) { sp.edit().putBoolean("watchList", v).apply(); }
    /** 워치에 쌓인(아직 안 지운) 사진 알림 수 */
    public int unread() { return sp.getInt("unread", 0); }
    public void setUnread(int v) { sp.edit().putInt("unread", Math.max(0, v)).apply(); }

    public String diag() { return sp.getString("diag", "아직 받은 카톡 없음"); }
    public void setDiag(String v) { sp.edit().putString("diag", v).apply(); }

    /** 최근 20건 기록 (사진이 빠진 이유 확인용) */
    public synchronized void addHistory(String line) {
        String h = sp.getString("history", "");
        String[] lines = (line + "\n" + h).split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(20, lines.length); i++) if (!lines[i].isEmpty()) sb.append(lines[i]).append("\n");
        sp.edit().putString("history", sb.toString()).apply();
    }
    public String history() { return sp.getString("history", "아직 기록 없음"); }

    /** 워치 앱이 알려 온 버전 (폰 앱과 다르면 상태 줄에 경고) */
    public String watchVer() { return sp.getString("watchVer", ""); }
    public void setWatchVer(String v) { sp.edit().putString("watchVer", v).apply(); }

    public String lastLog() { return sp.getString("lastLog", "-"); }
    public void setLastLog(String v) { sp.edit().putString("lastLog", v).apply(); }

    public boolean wants(String pkg) {
        if (KAKAO.equals(pkg)) return kakao();
        for (String s : SMS) if (s.equals(pkg)) return sms();
        if (BuildConfigGuard.isSelf(pkg)) return false;
        return otherApps().contains(pkg);
    }

    /** 자기 자신과 시스템 잡음은 제외 */
    static final class BuildConfigGuard {
        static boolean isSelf(String pkg) {
            return pkg == null
                    || pkg.equals("io.github.hugdoublek.rw6noti")
                    || pkg.equals("android")
                    || pkg.equals("com.android.systemui")
                    || pkg.equals("com.mi.health")
                    || pkg.equals("com.xiaomi.wearable");
        }
    }
}
