package io.github.hugdoublek.rw6hub;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * 샤오미 공식 로그인 웹 화면. 비밀번호·인증 코드·보안 문자는 모두 샤오미 페이지에서 처리하고,
 * 로그인이 끝나면 생기는 passToken / userId 쿠키만 꺼내 돌려준다. (비밀번호는 이 앱이 보지 않음)
 */
public class LoginActivity extends Activity {

    public static final String EXTRA_DEVICE_ID = "deviceId";
    public static final String EXTRA_USER_ID = "userId";
    public static final String EXTRA_CUSER_ID = "cUserId";
    public static final String EXTRA_PASS_TOKEN = "passToken";

    private WebView web;
    private boolean finished = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("샤오미 로그인 (지역: 중국)");
        String deviceId = getIntent().getStringExtra(EXTRA_DEVICE_ID);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.removeAllCookies(null);
        for (String host : new String[]{"https://account.xiaomi.com", "https://xiaomi.com", "https://mi.com"}) {
            cm.setCookie(host, "deviceId=" + deviceId + "; path=/");
            cm.setCookie(host, "sdkVersion=" + MiCloud.SDK_VERSION + "; path=/");
        }
        cm.flush();

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        cm.setAcceptThirdPartyCookies(web, true);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                return check(req.getUrl().toString());
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                check(url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                check(url);
            }
        });
        setContentView(web);
        web.loadUrl(MiCloud.LOGIN_PAGE);
    }

    /** passToken 쿠키가 생겼으면 끝낸다. 콜백(sts-hlth) 주소로 넘어가면 더 진행하지 않음. */
    private boolean check(String url) {
        if (finished) return true;
        CookieManager cm = CookieManager.getInstance();
        String all = join(cm.getCookie("https://account.xiaomi.com/pass/serviceLogin"),
                cm.getCookie("https://account.xiaomi.com/"),
                cm.getCookie("https://xiaomi.com/"));
        String pass = cookie(all, "passToken");
        String user = cookie(all, "userId");
        if (pass != null && user != null) {
            finished = true;
            Intent r = new Intent();
            r.putExtra(EXTRA_USER_ID, user);
            r.putExtra(EXTRA_PASS_TOKEN, pass);
            String cu = cookie(all, "cUserId");
            if (cu != null) r.putExtra(EXTRA_CUSER_ID, cu);
            setResult(RESULT_OK, r);
            finish();
            return true;
        }
        return url != null && url.contains("sts-hlth.io.mi.com");
    }

    private static String join(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) if (p != null) sb.append(p).append("; ");
        return sb.toString();
    }

    private static String cookie(String header, String name) {
        for (String kv : header.split(";")) {
            String t = kv.trim();
            if (t.startsWith(name + "=")) {
                String v = t.substring(name.length() + 1);
                if (!v.isEmpty()) return v;
            }
        }
        return null;
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
