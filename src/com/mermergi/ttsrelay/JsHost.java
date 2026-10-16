package com.mermergi.ttsrelay;

import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 跑 TTS Server 的 JS 插件。
 *
 * <b>这不是自己发明的接口</b> —— 是把 TTS Server 那套跑插件的环境照搬过来：
 * 同一个引擎（Mozilla Rhino，解释执行，不在 Android 上生成字节码），同一组全局对象
 * （{@code ttsrv}、{@code http}、{@code fs}、{@code logger}、{@code console}），
 * 插件里怎么写就怎么跑，于是它的 {@code getAudio} / {@code getVoices} 原样能用。
 *
 * 用到的返回约定（和 TTS Server 一致）：
 * <ul>
 *   <li>{@code PluginJS.getAudio(text, locale, voice, rate, volume, pitch)}
 *       返回 http 链接 → 我们自己取；返回 InputStream / byte[] → 插件已经取好了</li>
 *   <li>{@code EditorJS.getVoices(locale)} 返回 {@code {取值: {name: 显示名}}} 或数组</li>
 *   <li>{@code EditorJS.getAudioSampleRate(locale, voice)} 返回采样率</li>
 * </ul>
 *
 * 纯 Java、不碰 Android，所以能拿真插件离线跑一遍再进手机。
 */
public final class JsHost {

    /** rate/volume/pitch 的档位：TTS Server 那边是 0-100，50 为正常。 */
    public static final int NEUTRAL = 50;

    private final Scriptable scope;
    private final Scriptable pluginJs;
    private final Scriptable editorJs;
    private final String source;
    private final Map<String, String> files = new LinkedHashMap<String, String>();

    private JsHost(Scriptable scope, Scriptable pluginJs, Scriptable editorJs, String source) {
        this.scope = scope;
        this.pluginJs = pluginJs;
        this.editorJs = editorJs;
        this.source = source;
    }

    public String source() { return source; }
    public boolean hasEditor() { return editorJs != null; }

    /** 插件里有没有声明这种取音频的函数。 */
    public boolean hasFunction(String name) {
        if (pluginJs == null) return false;
        return pluginJs.get(name, pluginJs) instanceof Function;
    }

    /**
     * 把插件跑起来。
     *
     * @param src  插件源码
     * @param vars 用户填的变量（对应插件里的 {@code ttsrv.userVars['x']}）；空值会被当成没设置，
     *             让插件自己的 {@code || 默认值} 生效 —— 和 TTS Server 的行为一致
     */
    public static JsHost load(String src, Map<String, String> vars) {
        Context cx = Context.enter();
        try {
            cx.setOptimizationLevel(-1);          // Android 上只能解释执行
            cx.setLanguageVersion(Context.VERSION_ES6);
            Scriptable scope = cx.initStandardObjects();
            installHost(cx, scope, vars);
            cx.evaluateString(scope, src == null ? "" : src, "plugin.js", 1, null);
            Object p = scope.get("PluginJS", scope);
            Object e = scope.get("EditorJS", scope);
            return new JsHost(scope,
                    p instanceof Scriptable ? (Scriptable) p : null,
                    e instanceof Scriptable ? (Scriptable) e : null,
                    src);
        } finally {
            Context.exit();
        }
    }

    // ───────────────────────── 全局对象 ─────────────────────────

    private static void installHost(Context cx, Scriptable scope, final Map<String, String> vars) {
        final ScriptableObject ttsrv = (ScriptableObject) cx.newObject(scope);

        // ttsrv.userVars：插件读变量的地方
        final ScriptableObject uv = (ScriptableObject) cx.newObject(scope);
        if (vars != null) {
            for (Map.Entry<String, String> en : vars.entrySet()) {
                String v = en.getValue();
                if (v == null || v.length() == 0) continue;   // 空 = 没设置，让插件的默认值生效
                ScriptableObject.putProperty(uv, en.getKey(), v);
            }
        }
        ScriptableObject.putProperty(ttsrv, "userVars", uv);

        // ttsrv.tts.data：插件里点一下 UI 存的值，我们这儿没有 UI，给个空袋子
        ScriptableObject tts = (ScriptableObject) cx.newObject(scope);
        ScriptableObject.putProperty(tts, "data", cx.newObject(scope));
        ScriptableObject.putProperty(ttsrv, "data", cx.newObject(scope));
        ScriptableObject.putProperty(ttsrv, "tts", tts);

        final JsHost[] self = new JsHost[1];

        // http 客户端
        Function post = new BaseFunction() {
            @Override public Object call(Context c, Scriptable sc, Scriptable thisObj, Object[] a) {
                String url = str(a, 0);
                String body = str(a, 1);
                Map<String, String> h = headers(a.length > 2 ? a[2] : null);
                return response(c, sc, http("POST", url, body, h));
            }
        };
        Function get = new BaseFunction() {
            @Override public Object call(Context c, Scriptable sc, Scriptable thisObj, Object[] a) {
                return response(c, sc, http("GET", str(a, 0), null,
                        headers(a.length > 1 ? a[1] : null)));
            }
        };
        Function getString = new BaseFunction() {
            @Override public Object call(Context c, Scriptable sc, Scriptable thisObj, Object[] a) {
                Resp r = http("GET", str(a, 0), null, headers(a.length > 1 ? a[1] : null));
                return r.text;
            }
        };
        Function postMultipart = new BaseFunction() {
            @Override public Object call(Context c, Scriptable sc, Scriptable thisObj, Object[] a) {
                // 附件上传本 App 用不上，但别让插件炸：告诉它没成功
                return response(c, sc, new Resp(501, "", "multipart not supported"));
            }
        };
        ScriptableObject.putProperty(ttsrv, "httpPost", post);
        ScriptableObject.putProperty(ttsrv, "httpGetString", getString);
        ScriptableObject.putProperty(ttsrv, "httpPostMultipart", postMultipart);

        ScriptableObject http = (ScriptableObject) cx.newObject(scope);
        ScriptableObject.putProperty(http, "post", post);
        ScriptableObject.putProperty(http, "get", get);
        ScriptableObject.putProperty(scope, "http", http);

        // fs：插件常拿它缓存音色列表，内存里给一份就够
        final Map<String, String> files = new LinkedHashMap<String, String>();
        ScriptableObject fs = (ScriptableObject) cx.newObject(scope);
        ScriptableObject.putProperty(fs, "exists", new BaseFunction() {
            @Override public Object call(Context c, Scriptable sc, Scriptable t, Object[] a) {
                return files.containsKey(str(a, 0));
            }
        });
        ScriptableObject.putProperty(fs, "readText", new BaseFunction() {
            @Override public Object call(Context c, Scriptable sc, Scriptable t, Object[] a) {
                String v = files.get(str(a, 0));
                return v == null ? "" : v;
            }
        });
        ScriptableObject.putProperty(fs, "writeFile", new BaseFunction() {
            @Override public Object call(Context c, Scriptable sc, Scriptable t, Object[] a) {
                files.put(str(a, 0), str(a, 1));
                return Undefined.instance;
            }
        });
        ScriptableObject.putProperty(scope, "fs", fs);

        // 日志：不往外吐（插件可能打敏感东西），只留个不炸的实现
        Function noop = new BaseFunction() {
            @Override public Object call(Context c, Scriptable sc, Scriptable t, Object[] a) {
                return Undefined.instance;
            }
        };
        ScriptableObject loggerObj = (ScriptableObject) cx.newObject(scope);
        for (String n : new String[]{"i", "d", "w", "e", "v"}) {
            ScriptableObject.putProperty(loggerObj, n, noop);
        }
        ScriptableObject.putProperty(scope, "logger", loggerObj);

        ScriptableObject console = (ScriptableObject) cx.newObject(scope);
        for (String n : new String[]{"log", "error", "warn", "info", "debug"}) {
            ScriptableObject.putProperty(console, n, noop);
        }
        ScriptableObject.putProperty(scope, "console", console);

        ScriptableObject.putProperty(scope, "ttsrv", ttsrv);
    }

    private static final class Resp {
        final int code;
        final String text;
        final byte[] bytes;
        Resp(int code, String text, byte[] bytes) { this.code = code; this.text = text; this.bytes = bytes; }
        Resp(int code, String text, String err) { this(code, text, err.getBytes()); }
    }

    private static Resp http(String method, String url, String body, Map<String, String> headers) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(120000);
            conn.setUseCaches(false);
            for (Map.Entry<String, String> e : headers.entrySet()) {
                try { conn.setRequestProperty(e.getKey(), e.getValue()); } catch (Throwable ignored) { }
            }
            if (body != null) {
                conn.setDoOutput(true);
                conn.getOutputStream().write(body.getBytes("UTF-8"));
            }
            int code = conn.getResponseCode();
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            byte[] data = in == null ? new byte[0] : readAll(in);
            return new Resp(code, new String(data, "UTF-8"), data);
        } catch (Throwable t) {
            return new Resp(0, String.valueOf(t.getMessage()), String.valueOf(t.getMessage()));
        } finally {
            if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) { }
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(64 * 1024);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return bos.toByteArray();
    }

    /** 给插件用的响应对象：{@code code() / text() / body().string() / body().byteStream()}。 */
    private static Scriptable response(Context cx, Scriptable scope, final Resp r) {
        ScriptableObject out = (ScriptableObject) cx.newObject(scope);
        ScriptableObject.putProperty(out, "code", new BaseFunction() {
            @Override public Object call(Context c, Scriptable s, Scriptable t, Object[] a) {
                return Integer.valueOf(r.code);
            }
        });
        ScriptableObject.putProperty(out, "text", new BaseFunction() {
            @Override public Object call(Context c, Scriptable s, Scriptable t, Object[] a) {
                return r.text;
            }
        });
        ScriptableObject body = (ScriptableObject) cx.newObject(scope);
        ScriptableObject.putProperty(body, "string", new BaseFunction() {
            @Override public Object call(Context c, Scriptable s, Scriptable t, Object[] a) {
                return r.text;
            }
        });
        ScriptableObject.putProperty(body, "byteStream", new BaseFunction() {
            @Override public Object call(Context c, Scriptable s, Scriptable t, Object[] a) {
                return Context.javaToJS(new java.io.ByteArrayInputStream(r.bytes), s);
            }
        });
        ScriptableObject.putProperty(out, "body", body);
        ScriptableObject.putProperty(out, "headers", cx.newObject(scope));
        return out;
    }

    private static Map<String, String> headers(Object o) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        if (o instanceof Scriptable) {
            Scriptable s = (Scriptable) o;
            for (Object id : s.getIds()) {
                String k = String.valueOf(id);
                Object v = s.get(k, s);
                if (v == null || v == Scriptable.NOT_FOUND) continue;
                out.put(k, Context.toString(v));
            }
        }
        return out;
    }

    private static String str(Object[] a, int i) {
        if (a == null || i >= a.length || a[i] == null || a[i] == Undefined.instance) return "";
        return Context.toString(a[i]);
    }

    // ───────────────────────── 调用插件 ─────────────────────────

    /** {@code PluginJS.name}。 */
    public String name() { return prop(pluginJs, "name"); }

    public String id() { return prop(pluginJs, "id"); }

    public String author() { return prop(pluginJs, "author"); }

    public String description() { return prop(pluginJs, "description"); }

    /** {@code PluginJS.vars} 里声明的键（顺序按插件里的写法），以及它们的 label / 登录信息。 */
    public List<String> varKeys() {
        List<String> out = new ArrayList<String>();
        Object v = pluginJs == null ? null : pluginJs.get("vars", pluginJs);
        if (v instanceof Scriptable) {
            for (Object id : ((Scriptable) v).getIds()) out.add(String.valueOf(id));
        }
        return out;
    }

    public String varLabel(String key) {
        Object v = pluginJs == null ? null : pluginJs.get("vars", pluginJs);
        if (v instanceof Scriptable) {
            Object one = ((Scriptable) v).get(key, (Scriptable) v);
            if (one instanceof Scriptable) {
                String label = prop((Scriptable) one, "label");
                String hint = prop((Scriptable) one, "hint");
                if (label.length() == 0) label = key;
                return hint.length() > 0 ? label + "（" + hint + "）" : label;
            }
        }
        return key;
    }

    /** 变量里声明的登录三件套（有就拿来用）。 */
    public String loginUrl() { return varProp("loginUrl"); }

    public String binding() { return varProp("binding"); }

    public String ua() { return varProp("ua"); }

    /** 哪个变量声明了登录页 —— 网页登录抓回来的值要写进它（TTS Server 就是这么干的）。 */
    public String loginKey() {
        Object v = pluginJs == null ? null : pluginJs.get("vars", pluginJs);
        if (!(v instanceof Scriptable)) return "";
        Scriptable vars = (Scriptable) v;
        for (Object id : vars.getIds()) {
            String key = String.valueOf(id);
            Object one = vars.get(key, vars);
            if (one instanceof Scriptable && prop((Scriptable) one, "loginUrl").length() > 0) return key;
        }
        return "";
    }

    private String varProp(String name) {
        Object v = pluginJs == null ? null : pluginJs.get("vars", pluginJs);
        if (!(v instanceof Scriptable)) return "";
        Scriptable vars = (Scriptable) v;
        for (Object id : vars.getIds()) {
            Object one = vars.get(String.valueOf(id), vars);
            if (one instanceof Scriptable) {
                String x = prop((Scriptable) one, name);
                if (x.length() > 0) return x;
            }
        }
        return "";
    }

    private static String prop(Scriptable s, String key) {
        if (s == null) return "";
        Object v = s.get(key, s);
        if (v == null || v == Scriptable.NOT_FOUND || v == Undefined.instance) return "";
        return Context.toString(v);
    }

    /**
     * 调 {@code PluginJS.getAudio(...)}。
     *
     * @return 插件给的三种东西之一：http 链接（String）、已经取好的字节（byte[] / InputStream）、
     *         或者 null（它没给 / 抛了）
     */
    public Object getAudio(String text, String locale, String voice,
                           int rate100, int volume100, int pitch100) throws Exception {
        return call("getAudio", new Object[]{text, locale, voice,
                Integer.valueOf(rate100), Integer.valueOf(volume100), Integer.valueOf(pitch100)});
    }

    /** 采样率；插件没声明就返回 0。 */
    public int sampleRate(String locale, String voice) {
        try {
            Object r = callOn(editorJs, "getAudioSampleRate", new Object[]{locale, voice});
            if (r == null || r == Undefined.instance) return 0;
            return (int) Context.toNumber(r);
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * 音色表：{@code EditorJS.getVoices(locale)} → {@code [{name, id}]}。
     * 插件一般返回 {@code {取值: {name: 显示名}}} 这种对象，也有的直接给数组。
     */
    public List<String[]> voices(String locale) {
        List<String[]> out = new ArrayList<String[]>();
        Object r;
        try {
            r = callOn(editorJs, "getVoices", new Object[]{locale});
        } catch (Throwable t) {
            return out;
        }
        if (r == null || r == Undefined.instance) return out;
        if (r instanceof Scriptable && !(r instanceof Function)) {
            Scriptable s = (Scriptable) r;
            if (s.getPrototype() == null && s.getIds().length > 0
                    && s.get("length", s) != Scriptable.NOT_FOUND) {
                // 数组
                int n = (int) Context.toNumber(s.get("length", s));
                for (int i = 0; i < n; i++) {
                    Object one = s.get(i, s);
                    if (one instanceof Scriptable) out.add(pair((Scriptable) one, String.valueOf(i)));
                }
                return out;
            }
            for (Object id : s.getIds()) {
                String key = String.valueOf(id);
                Object one = s.get(key, s);
                if (one instanceof Scriptable) out.add(pair((Scriptable) one, key));
                else if (one != null && one != Scriptable.NOT_FOUND) out.add(new String[]{Context.toString(one), key});
            }
        }
        return out;
    }

    private static String[] pair(Scriptable one, String key) {
        String name = prop(one, "name");
        if (name.length() == 0) name = key;
        return new String[]{name, key};
    }

    private Object call(String fn, Object[] args) throws Exception {
        return callOn(pluginJs, fn, args);
    }

    private Object callOn(Scriptable holder, String fn, Object[] args) throws Exception {
        if (holder == null) throw new IllegalStateException("插件里没有这个东西");
        Object f = holder.get(fn, holder);
        if (!(f instanceof Function)) throw new IllegalStateException("插件里没有 " + fn + "()");
        Context cx = Context.enter();
        try {
            cx.setOptimizationLevel(-1);
            Object r = ((Function) f).call(cx, scope, holder, args);
            return r;
        } finally {
            Context.exit();
        }
    }
}
