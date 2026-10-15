package com.mermergi.ttsrelay;

import java.util.Locale;

/**
 * 从任意文本里把 Cookie 抠出来。
 *
 * 为什么要有它：网页登录抓不到的时候（站点把登录态放在别的域、或者干脆没有网页版），
 * 用户手上其实已经有 Cookie 了 —— 浏览器开发者工具里一复制就有。常见的三种长相都认：
 *
 * <pre>
 *   ① 裸串：      a=1; b=2; c=3
 *   ② 请求头块：   Cookie: a=1; b=2
 *                  User-Agent: ...
 *   ③ cURL：      curl 'https://x' -H 'Cookie: a=1; b=2' -H 'User-Agent: ...'
 * </pre>
 *
 * 纯静态、不碰 Android，方便单独验证。
 */
public final class CookieGrab {

    /** 认不出返回空串。 */
    public static String extract(String raw) {
        if (raw == null) return "";
        // cURL 里的反斜杠续行先接回去
        String t = raw.replace("\\\r\n", " ").replace("\\\n", " ").replace("\r", "");
        String lower = t.toLowerCase(Locale.US);

        int at = lower.indexOf("cookie");
        while (at >= 0) {
            int after = at + "cookie".length();
            int i = after;
            while (i < t.length() && (t.charAt(i) == ' ' || t.charAt(i) == '\t')) i++;
            if (i < t.length() && t.charAt(i) == ':') {
                i++;
                char quote = 0;
                int back = at - 1;
                while (back >= 0 && (t.charAt(back) == ' ' || t.charAt(back) == '\t')) back--;
                if (back >= 0 && (t.charAt(back) == '\'' || t.charAt(back) == '"')) {
                    quote = t.charAt(back);
                }
                int end;
                if (quote != 0) {
                    int close = t.indexOf(quote, i);
                    end = close > i ? close : t.length();
                } else {
                    int nl = t.indexOf('\n', i);
                    end = nl > i ? nl : t.length();
                }
                String v = t.substring(i, end).trim();
                if (looksLikeCookie(v)) return v;
            }
            at = lower.indexOf("cookie", after);
        }

        String only = t.trim();
        // 多行的整块文本，如果第一行就是 cookie 串也认
        int nl = only.indexOf('\n');
        if (nl > 0) {
            String first = only.substring(0, nl).trim();
            if (looksLikeCookie(first)) return first;
        }
        return looksLikeCookie(only) ? only : "";
    }

    /** 长得像 "名字=值; 名字=值" 才算数，免得把随便一段文字当 Cookie。 */
    public static boolean looksLikeCookie(String v) {
        if (v == null) return false;
        String s = v.trim();
        if (s.length() < 3 || s.length() > 20000) return false;
        if (s.indexOf('\n') >= 0) return false;
        int eq = s.indexOf('=');
        if (eq <= 0) return false;
        String name = s.substring(0, eq).trim();
        if (name.length() == 0 || name.length() > 64) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = Character.isLetterOrDigit(c)
                    || c == '_' || c == '-' || c == '.' || c == '$' || c == '+';
            if (!ok) return false;
        }
        return true;
    }

    /**
     * 把用户粘进来的东西整理成一条能用的 Cookie。
     *
     * 界面只留一个"粘"按钮就靠它 —— 不用再让用户先自己判断该走"智能提取"还是"原样粘贴"：
     * <ul>
     *   <li>单行、本来就像 Cookie（<code>a=1; b=2</code>）→ <b>原样收下，一个字都不动</b>；</li>
     *   <li>文本里出现 cookie / curl 这类标记（请求头块、cURL）→ 走 {@link #extract} 摘出来；</li>
     *   <li>摘不出来 → 原样收着，绝不猜、绝不截断。</li>
     * </ul>
     */
    public static String tidy(String raw) {
        if (raw == null) return "";
        String t = raw.trim();
        if (t.length() == 0) return "";
        if (t.indexOf('\n') < 0 && looksLikeCookie(t)) return t;
        String low = t.toLowerCase(Locale.US);
        if (low.indexOf("cookie") < 0 && low.indexOf("curl") < 0) return t;
        String got = extract(t);
        return got.length() > 0 ? got : t;
    }

    /** 给界面看的摘要：多少条、大概多长。 */
    public static String summary(String cookie) {
        if (cookie == null || cookie.trim().length() == 0) return "未设置";
        int n = 0;
        for (String p : cookie.split(";")) if (p.trim().length() > 0) n++;
        return "已设置  ·  " + n + " 条  ·  " + cookie.length() + " 字符";
    }
}
