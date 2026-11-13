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
    /** 처음 열 주소 (2단계 인증 주소 등). 없으면 기본 로그인 페이지. */
    public static final String EXTRA_URL = "url";

    private android.widget.TextView status;

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
        web.setBackgroundColor(android.graphics.Color.WHITE);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        // 일부 로그인 페이지가 WebView 표시(wv)를 싫어해서 일반 크롬처럼 보이게 함
        s.setUserAgentString(s.getUserAgentString().replace("; wv)", ")"));
        web.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int p) {
                if (p < 100) show("불러오는 중 " + p + "% · " + host(view.getUrl()));
                else show("열림 · " + host(view.getUrl()) + "  (빈 화면이면 ↻)");
            }
        });
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

            @Override
            public void onReceivedError(WebView view, android.webkit.WebResourceRequest req,
                                        android.webkit.WebResourceError err) {
                if (req.isForMainFrame()) show("오류: " + err.getDescription() + " · " + host(req.getUrl().toString()));
            }

            @Override
            public void onReceivedHttpError(WebView view, android.webkit.WebResourceRequest req,
                                            android.webkit.WebResourceResponse resp) {
                if (req.isForMainFrame()) show("HTTP " + resp.getStatusCode() + " · " + host(req.getUrl().toString()));
            }

            @Override
            public void onReceivedSslError(WebView view, android.webkit.SslErrorHandler h, android.net.http.SslError e) {
                show("보안 연결 오류 (" + e.getPrimaryError() + ")");
                h.cancel();
            }
        });

        status = new android.widget.TextView(this);
        status.setTextSize(13);
        status.setPadding(24, 12, 24, 12);
        android.widget.Button reload = new android.widget.Button(this);
        reload.setText("↻");
        reload.setOnClickListener(v -> web.reload());
        android.widget.LinearLayout bar = new android.widget.LinearLayout(this);
        bar.addView(status, new android.widget.LinearLayout.LayoutParams(0, -2, 1f));
        bar.addView(reload);
        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.addView(bar);
        root.addView(web, new android.widget.LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);

        String url = getIntent().getStringExtra(EXTRA_URL);
        web.loadUrl(url != null && !url.isEmpty() ? url : MiCloud.LOGIN_PAGE);
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

    private void show(String msg) {
        if (status != null) status.setText(msg);
    }

    private static String host(String url) {
        if (url == null) return "";
        try { return android.net.Uri.parse(url).getHost(); } catch (Exception e) { return ""; }
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
