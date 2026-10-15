package com.mermergi.ttsrelay;

import java.util.Locale;

/**
 * 从任意文本里摘出一段「名字=值; 名字=值」形状的凭据。
 *
 * 为什么要有它：有些朗读接口要先登录才给音色和音频，而用户手上其实已经有现成的凭据了
 * —— 浏览器开发者工具里一复制就有。常见的三种长相都认：
 *
 * <pre>
 *   ① 裸串：      a=1; b=2; c=3
 *   ② 请求头块：   Authorization: Bearer xxx
 *                  User-Agent: ...
 *   ③ cURL：      curl 'https://x' -H 'Authorization: Bearer xxx' -H 'User-Agent: ...'
 * </pre>
 *
 * 纯静态、不碰 Android，方便单独验证。
 */
public final class SessionPick {

    /**
     * 去哪几种请求头后面找凭据。一条请求里通常只有其中一个带值，
     * 顺序就是优先级；"token" 放最后，好让 x-auth-token / x-api-key 这类先被认出。
     */
    private static final String[] MARKERS = {"cookie", "authorization", "x-api-key", "token"};

    /** 认不出返回空串。 */
    public static String pick(String raw) {
        if (raw == null) return "";
        // cURL 里的反斜杠续行先接回去
        String t = raw.replace("\\\r\n", " ").replace("\\\n", " ").replace("\r", "");
        String lower = t.toLowerCase(Locale.US);

        for (String marker : MARKERS) {
            int at = lower.indexOf(marker);
            while (at >= 0) {
                int after = at + marker.length();
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
                    // 请求头的值不一定是 "名字=值" 的形状（Bearer xxx / sk-xxx 都不是），
                    // 所以这儿只要求非空；是不是凭据由上面那个标记名字负责。
                    if (v.length() > 0 && v.length() <= 20000) return v;
                }
                at = lower.indexOf(marker, after);
            }
        }

        String only = t.trim();
        // 多行的整块文本，如果第一行就是凭据串也认
        int nl = only.indexOf('\n');
        if (nl > 0) {
            String first = only.substring(0, nl).trim();
            if (looksLikePair(first)) return first;
        }
        return looksLikePair(only) ? only : "";
    }

    /** 整段文本本身是不是一条凭据：长得像 "名字=值" 才算数，免得把随便一段文字当凭据。 */
    public static boolean looksLikePair(String v) {
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
     * 把用户粘进来的东西整理成一条能直接用的凭据。
     *
     * 界面只留一个「粘贴」按钮就靠它 —— 不用再让用户先自己判断该走"智能提取"还是"原样粘贴"：
     * <ul>
     *   <li>单行、本来就像凭据（<code>a=1; b=2</code>）→ <b>原样收下，一个字都不动</b>；</li>
     *   <li>文本里出现 curl / 某个请求头标记 → 走 {@link #pick} 摘出来；</li>
     *   <li>摘不出来 → 原样收着，绝不猜、绝不截断。</li>
     * </ul>
     */
    public static String tidy(String raw) {
        if (raw == null) return "";
        String t = raw.trim();
        if (t.length() == 0) return "";
        if (t.indexOf('\n') < 0 && looksLikePair(t)) return t;
        String low = t.toLowerCase(Locale.US);
        boolean structured = low.indexOf("curl") >= 0;
        if (!structured) {
            for (String m : MARKERS) if (low.indexOf(m) >= 0) { structured = true; break; }
        }
        if (!structured) return t;
        String got = pick(t);
        return got.length() > 0 ? got : t;
    }

    /** 给界面看的摘要：多少条、大概多长。 */
    public static String summary(String cred) {
        if (cred == null || cred.trim().length() == 0) return "未保存";
        int n = 0;
        for (String p : cred.split(";")) if (p.trim().length() > 0) n++;
        return "已保存  ·  " + n + " 条  ·  " + cred.length() + " 字符";
    }
}
