package com.mermergi.ttsrelay;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 内置网页登录，登完把值绑回配置。
 *
 * 逻辑照抄 TTS Server 的 {@code PluginLoginActivity}（插件里 {@code loginUrl} +
 * {@code binding} + {@code ua} 那三个字段就是喂给它的），一点没自创：
 *
 * <pre>
 *   binding = "cookies"            → CookieManager.getCookie(loginUrl) 整串
 *   binding = "cookies.xxx"        → 只要名为 xxx 的那条
 *   binding = "locals.xxx"         → localStorage.getItem('xxx')
 *   binding = "sessions.xxx"       → sessionStorage.getItem('xxx')
 * </pre>
 *
 * 注意：就取 <b>loginUrl 那一个地址</b>的 Cookie。不要去扫别的域、也不要合并 ——
 * 合并出来的 Cookie 头域名一乱，服务端就不认了。
 *
 * UA：{@code pc} / 留空 = 桌面 UA；{@code mobile} / {@code android} = WebView 默认（设备手机 UA）；
 * 别的值 = 当成自定义 UA 直接用。
 */
public final class LoginActivity extends Activity {

    public static final String EXTRA_URL = "url";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_BINDING = "binding";
    public static final String EXTRA_UA = "ua";
    public static final String RESULT_VALUE = "value";

    /** 老名字，早先版本用的是它 —— 留着免得旧调用方拿到空。 */
    public static final String RESULT_COOKIE = RESULT_VALUE;

    private static final String UA_PC =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36";

    private WebView web;
    private Palette p;
    private Ui ui;
    private TextView status;
    private String loginUrl = "";
    private String binding = "cookies";

    @Override
    protected void onCreate(Bundle b) {
        p = Palette.apply(this);
        super.onCreate(b);
        ui = new Ui(this, p);

        Intent in = getIntent();
        if (in != null) {
            String u = in.getStringExtra(EXTRA_URL);
            if (u != null && u.trim().length() > 0) loginUrl = u.trim();
            String bd = in.getStringExtra(EXTRA_BINDING);
            if (bd != null && bd.trim().length() > 0) binding = bd.trim();
        }
        if (loginUrl.length() == 0) loginUrl = "about:blank";
        String title = in == null ? null : in.getStringExtra(EXTRA_TITLE);
        if (title == null || title.length() == 0) title = "登录后点右边的按钮取回";

        String uaArg = in == null ? "" : in.getStringExtra(EXTRA_UA);
        String userAgent;
        String u = uaArg == null ? "" : uaArg.trim().toLowerCase(java.util.Locale.US);
        if (u.length() == 0 || "pc".equals(u)) userAgent = UA_PC;
        else if ("mobile".equals(u) || "android".equals(u)) userAgent = "";   // WebView 默认
        else userAgent = uaArg.trim();

        LinearLayout root = ui.col();
        root.setBackgroundColor(p.bg);

        LinearLayout bar = ui.row();
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(p.card);
        bar.setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8));
        bar.addView(ui.btn("返回", false, new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        }));
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(p.textSecondary);
        t.setTextSize(12);
        t.setPadding(ui.dp(10), 0, ui.dp(10), 0);
        bar.addView(t, ui.weight(1f));
        bar.addView(ui.btn("取回", true, new View.OnClickListener() {
            public void onClick(View v) { grab(); }
        }));
        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        status = new TextView(this);
        status.setTextColor(p.textMuted);
        status.setTextSize(11);
        status.setPadding(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(6));
        status.setText("绑定目标：" + binding + "  ·  " + loginUrl);
        root.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        if (userAgent.length() > 0) s.setUserAgentString(userAgent);
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= 21) cm.setAcceptThirdPartyCookies(web, true);
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView v, String url) { peek(); }
        });

        root.addView(web, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        try { web.loadUrl(loginUrl); } catch (Throwable e) {
            status.setText("打不开：" + e.getMessage());
        }
    }

    /** 已经在 loginUrl 上看到的条数，给个反馈（不参与取值）。 */
    private void peek() {
        if (status == null) return;
        try {
            String ck = CookieManager.getInstance().getCookie(loginUrl);
            int n = 0;
            if (ck != null) for (String x : ck.split(";")) if (x.trim().length() > 0) n++;
            status.setText(n > 0
                    ? "绑定：" + binding + "  ·  已就绪（" + n + " 条）"
                    : "绑定：" + binding + "  ·  还没登录 —— 登完再点「取回」");
            status.setTextColor(n > 0 ? p.ok : p.textMuted);
        } catch (Throwable ignored) { }
    }

    /** 照抄 TTS Server 的 parseBinding。 */
    private String parseBinding() {
        String b = binding == null ? "" : binding.trim();
        if (b.length() == 0) b = "cookies";
        String[] split = b.split("\\.");
        String start = split.length > 0 ? split[0] : "";
        String end = split.length > 1 ? split[1] : "";
        boolean all = end.length() == 0;

        if ("cookies".equals(start)) {
            String cookies = "";
            try { cookies = CookieManager.getInstance().getCookie(loginUrl); }
            catch (Throwable ignored) { }
            if (cookies == null) cookies = "";
            if (all) return cookies;
            return cookieValue(cookies, end);
        }
        if ("locals".equals(start)) return jsGet("localStorage.getItem('" + end + "')");
        if ("sessions".equals(start)) return jsGet("sessionStorage.getItem('" + end + "')");
        return "";
    }

    private static String cookieValue(String header, String name) {
        if (header == null || header.length() == 0) return "";
        for (String part : header.split(";")) {
            String s = part.trim();
            int eq = s.indexOf('=');
            if (eq <= 0) continue;
            if (s.substring(0, eq).trim().equals(name)) return s.substring(eq + 1);
        }
        return "";
    }

    private String jsGet(final String expr) {
        final String[] out = new String[]{""};
        final CountDownLatch latch = new CountDownLatch(1);
        try {
            runOnUiThread(new Runnable() {
                public void run() {
                    try {
                        web.evaluateJavascript(expr, new ValueCallback<String>() {
                            public void onReceiveValue(String v) {
                                out[0] = v;
                                latch.countDown();
                            }
                        });
                    } catch (Throwable t) {
                        latch.countDown();
                    }
                }
            });
            latch.await(5, TimeUnit.SECONDS);
        } catch (Throwable ignored) { }
        String v = out[0];
        if (v == null || "null".equals(v)) return "";
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            v = v.substring(1, v.length() - 1);
        }
        return v;
    }

    private void grab() {
        try { if (Build.VERSION.SDK_INT >= 21) CookieManager.getInstance().flush(); }
        catch (Throwable ignored) { }
        String v = parseBinding();
        if (v == null) v = "";
        v = v.trim();
        if (v.length() == 0) {
            peek();
            status.setText("没取到 —— 先把账号登进去再点「取回」");
            status.setTextColor(p.warn);
            return;
        }
        Intent r = new Intent();
        r.putExtra(RESULT_VALUE, v);
        setResult(RESULT_OK, r);
        finish();
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        try {
            if (web != null) {
                web.stopLoading();
                web.setWebViewClient(new WebViewClient());
                web.destroy();
                web = null;
            }
        } catch (Throwable ignored) { }
        super.onDestroy();
    }
}
