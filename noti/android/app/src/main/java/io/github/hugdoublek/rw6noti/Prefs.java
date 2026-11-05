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

    public boolean others() { return sp.getBoolean("others", false); }
    public void setOthers(boolean v) { sp.edit().putBoolean("others", v).apply(); }

    public boolean pictures() { return sp.getBoolean("pictures", true); }
    public void setPictures(boolean v) { sp.edit().putBoolean("pictures", v).apply(); }

    /** 사진이 있는 알림만 워치 앱으로 보냄 (글 알림은 기본 팝업에 맡김) */
    public boolean photoOnly() { return sp.getBoolean("photoOnly", true); }
    public void setPhotoOnly(boolean v) { sp.edit().putBoolean("photoOnly", v).apply(); }

    public String diag() { return sp.getString("diag", "아직 받은 카톡 없음"); }
    public void setDiag(String v) { sp.edit().putString("diag", v).apply(); }

    public String lastLog() { return sp.getString("lastLog", "-"); }
    public void setLastLog(String v) { sp.edit().putString("lastLog", v).apply(); }

    public boolean wants(String pkg) {
        if (KAKAO.equals(pkg)) return kakao();
        for (String s : SMS) if (s.equals(pkg)) return sms();
        if (BuildConfigGuard.isSelf(pkg)) return false;
        return others();
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
