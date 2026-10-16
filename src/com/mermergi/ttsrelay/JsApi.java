package com.mermergi.ttsrelay;

import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;
import org.mozilla.javascript.typedarrays.NativeArrayBuffer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JS 插件能用的那一整套接口 —— <b>照着 TTS Server 的清单来，不是遇到一个补一个</b>。
 *
 * 对照的是它 {@code lib-script} 里这几个文件：
 * <ul>
 *   <li>{@code runtime/NativeResponse.kt} —— 响应的
 *       {@code status / statusText / headers / url / redirected / ok}
 *       和 {@code json() / text() / bytes()}</li>
 *   <li>{@code runtime/GlobalHttp.kt} —— 全局 {@code http.get / post / postMultipart}</li>
 *   <li>{@code runtime/GlobalFileSystem.kt} —— 全局 {@code fs.*}</li>
 *   <li>{@code simple/ext/JsNet.kt} —— {@code ttsrv.httpGet / httpGetString / httpGetBytes /
 *       httpPost / httpPostMultipart}（老式那套，插件里最常用）</li>
 *   <li>{@code simple/ext/JsExtensions.kt} —— {@code strToBytes / bytesToStr / getFile /
 *       readTxtFile / writeTxtFile / randomUUID / getAudioSampleRate …}</li>
 *   <li>{@code simple/ext/JsCrypto.kt} —— {@code hexEncodeToString / hexDecodeToString /
 *       timeFormat …}</li>
 * </ul>
 *
 * OkHttp 那套名字（{@code code() / message() / body().string() / body().byteStream()}）也一并给全，
 * 因为 TTS Server 是把原始 Response 当 Java 对象暴露给 JS 的，插件里两种写法都有。
 */
final class JsApi {

    private JsApi() { }

    /** 一次 http 回来的东西。 */
    static final class Resp {
        int code;
        String message = "";
        String url = "";
        byte[] bytes = new byte[0];
        final Map<String, String> headers = new LinkedHashMap<String, String>();
        boolean redirected;

        String text() {
            try { return new String(bytes, "UTF-8"); } catch (Throwable t) { return ""; }
        }
    }

    static Resp fetch(String method, String url, String body, Map<String, String> headers) {
        Resp r = new Resp();
        r.url = url;
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(120000);
            conn.setUseCaches(false);
            conn.setInstanceFollowRedirects(true);
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    try { conn.setRequestProperty(e.getKey(), e.getValue()); } catch (Throwable ignored) { }
                }
            }
            if (body != null && body.length() > 0) {
                conn.setDoOutput(true);
                conn.getOutputStream().write(body.getBytes("UTF-8"));
            }
            r.code = conn.getResponseCode();
            r.message = String.valueOf(conn.getResponseMessage());
            r.redirected = r.code >= 300 && r.code < 400;
            for (Map.Entry<String, java.util.List<String>> e : conn.getHeaderFields().entrySet()) {
                if (e.getKey() == null || e.getValue() == null || e.getValue().isEmpty()) continue;
                r.headers.put(e.getKey(), e.getValue().get(0));
            }
            InputStream in = r.code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            r.bytes = in == null ? new byte[0] : readAll(in);
        } catch (Throwable t) {
            r.code = 0;
            r.message = String.valueOf(t.getMessage());
            r.bytes = r.message.getBytes();
        } finally {
            if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) { }
        }
        return r;
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(64 * 1024);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return bos.toByteArray();
    }

    // ═════════════════════════ 响应对象 ═════════════════════════

    /** 把 {@link Resp} 包成 JS 对象：NativeResponse 的属性/方法 + OkHttp Response 的那几个。 */
    static Scriptable response(Context cx, Scriptable scope, final Resp r) {
        final ScriptableObject o = (ScriptableObject) cx.newObject(scope);
        ScriptableObject.putProperty(o, "status", Integer.valueOf(r.code));
        ScriptableObject.putProperty(o, "statusText", r.message);
        ScriptableObject.putProperty(o, "url", r.url);
        ScriptableObject.putProperty(o, "redirected", Boolean.valueOf(r.redirected));
        ScriptableObject.putProperty(o, "ok", Boolean.valueOf(r.code >= 200 && r.code < 300));

        ScriptableObject headers = (ScriptableObject) cx.newObject(scope);
        for (Map.Entry<String, String> e : r.headers.entrySet()) {
            ScriptableObject.putProperty(headers, e.getKey(), e.getValue());
        }
        ScriptableObject.putProperty(o, "headers", headers);

        ScriptableObject.putProperty(o, "code", fn(new Fn() {
            public Object run(Context c, Scriptable s, Object[] a) { return Integer.valueOf(r.code); }
        }));
        ScriptableObject.putProperty(o, "message", fn(new Fn() {
            public Object run(Context c, Scriptable s, Object[] a) { return r.message; }
        }));
        ScriptableObject.putProperty(o, "isSuccessful", fn(new Fn() {
            public Object run(Context c, Scriptable s, Object[] a) {
                return Boolean.valueOf(r.code >= 200 && r.code < 300);
            }
        }));
        ScriptableObject.putProperty(o, "isRedirect", fn(new Fn() {
            public Object run(Context c, Scriptable s, Object[] a) { return Boolean.valueOf(r.redirected); }
        }));
        ScriptableObject.putProperty(o, "text", fn(new Fn() {          // NativeResponse.text()
            public Object run(Context c, Scriptable s, Object[] a) { return r.text(); }
        }));
        ScriptableObject.putProperty(o, "json", fn(new Fn() {          // NativeResponse.json()
            public Object run(Context c, Scriptable s, Object[] a) {
                return parseJson(c, s, r.text());
            }
        }));
        ScriptableObject.putProperty(o, "bytes", fn(new Fn() {         // NativeResponse.bytes()
            public Object run(Context c, Scriptable s, Object[] a) { return arrayBuffer(r.bytes); }
        }));

        // OkHttp 那套：resp.body().string() / .byteStream() / .bytes() / .contentType()
        final ScriptableObject bodyObj = (ScriptableObject) cx.newObject(scope);
        ScriptableObject.putProperty(bodyObj, "string", fn(new Fn() {
            public Object run(Context c, Scriptable s, Object[] a) { return r.text(); }
        }));
        ScriptableObject.putProperty(bodyObj, "bytes", fn(new Fn() {
            public Object run(Context c, Scriptable s, Object[] a) { return r.bytes; }
        }));
        ScriptableObject.putProperty(bodyObj, "byteStream", fn(new Fn() {
            public Object run(Context c, Scriptable s, Object[] a) {
                return Context.javaToJS(new ByteArrayInputStream(r.bytes), s);
            }
        }));
        ScriptableObject.putProperty(bodyObj, "charStream", fn(new Fn() {
            public Object run(Context c, Scriptable s, Object[] a) {
                return Context.javaToJS(new java.io.StringReader(r.text()), s);
            }
        }));
        ScriptableObject.putProperty(bodyObj, "contentType", fn(new Fn() {
            public Object run(Context c, Scriptable s, Object[] a) {
                String ct = r.headers.get("Content-Type");
                return ct == null ? Undefined.instance : ct;
            }
        }));
        // OkHttp 里 body() 是方法：插件写的是 resp.body().string()
        final ScriptableObject bFinal = bodyObj;
        ScriptableObject.putProperty(o, "body", fn(new Fn() {
            public Object run(Context c, Scriptable s, Object[] a) { return bFinal; }
        }));

        return o;
    }

    private static Object parseJson(Context cx, Scriptable scope, String text) {
        try {
            Object json = ScriptableObject.getProperty(scope, "JSON");
            if (!(json instanceof Scriptable)) return Undefined.instance;
            Object parse = ((Scriptable) json).get("parse", (Scriptable) json);
            if (!(parse instanceof Function)) return Undefined.instance;
            return ((Function) parse).call(cx, scope, (Scriptable) json, new Object[]{text});
        } catch (Throwable t) {
            throw Context.throwAsScriptRuntimeEx(t);
        }
    }

    static NativeArrayBuffer arrayBuffer(byte[] data) {
        NativeArrayBuffer b = new NativeArrayBuffer(data == null ? 0 : data.length);
        if (data != null && data.length > 0) System.arraycopy(data, 0, b.getBuffer(), 0, data.length);
        return b;
    }

    // ═════════════════════════ 小工具 ═════════════════════════

    interface Fn { Object run(Context cx, Scriptable scope, Object[] args); }

    static BaseFunction fn(final Fn f) {
        return new BaseFunction() {
            @Override public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
                Object r = f.run(cx, scope, args == null ? new Object[0] : args);
                return r == null ? Undefined.instance : r;
            }
        };
    }

    static void put(Scriptable target, String name, Object value) {
        try { ScriptableObject.putProperty(target, name, value); } catch (Throwable ignored) { }
    }

    static String str(Object o) {
        return o == null || o == Undefined.instance ? "" : Context.toString(o);
    }

    static String str(Object[] a, int i) {
        return a == null || i >= a.length ? "" : str(a[i]);
    }

    static Map<String, String> headers(Object o) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        if (o instanceof Scriptable) {
            Scriptable s = (Scriptable) o;
            for (Object id : s.getIds()) {
                String k = String.valueOf(id);
                Object v = s.get(k, s);
                if (v == null || v == Scriptable.NOT_FOUND || v == Undefined.instance) continue;
                out.put(k, str(v));
            }
        }
        return out;
    }

    static byte[] bytesOf(Object o) {
        if (o == null || o == Undefined.instance) return new byte[0];
        if (o instanceof byte[]) return (byte[]) o;
        if (o instanceof NativeArrayBuffer) return ((NativeArrayBuffer) o).getBuffer();
        // Uint8Array / Buffer 这类 —— 漏了这条，Buffer 的东西会被当成空字节
        if (o instanceof org.mozilla.javascript.typedarrays.NativeTypedArrayView) {
            byte[] raw = rawOf((Scriptable) o);
            if (raw.length > 0) return raw;
        }
        if (o instanceof CharSequence) {
            try { return o.toString().getBytes("UTF-8"); } catch (Throwable t) { return new byte[0]; }
        }
        Object j = Context.jsToJava(o, Object.class);
        if (j instanceof byte[]) return (byte[]) j;
        if (j instanceof InputStream) {
            try { return readAll((InputStream) j); } catch (Throwable t) { return new byte[0]; }
        }
        return new byte[0];
    }

    // ═════════════════════════ ttsrv 上那一整套（JsNet + JsExtensions + JsCrypto）═════════════════════════

    /** 老式 http 那套：{@code ttsrv.httpGet / httpGetString / httpGetBytes / httpPost / httpPostMultipart}。 */
    static void installTtsrvNet(Context cx, Scriptable scope, ScriptableObject ttsrv, final String cookie) {
        put(ttsrv, "httpGet", fn(new Fn() {
            public Object run(Context c, Scriptable s, Object[] a) {
                return JsApi.response(c, s, JsApi.fetch("GET", str(a, 0), null, JsApi.headers(a.length > 1 ? a[1] : null)));
            }
        }));
        put(ttsrv, "httpGetString", fn(new Fn() {
            public Object run(Context c, Scriptable s, Object[] a) {
                return JsApi.fetch("GET", str(a, 0), null, JsApi.headers(a.length > 1 ? a[1] : null)).text();
            }
        }));
        put(ttsrv, "httpGetBytes", fn(new Fn() {
            public Object run(Context c, Scriptable s, Object[] a) {
                return arrayBuffer(JsApi.fetch("GET", str(a, 0), null, JsApi.headers(a.length > 1 ? a[1] : null)).bytes);
            }
        }));
        put(ttsrv, "httpPost", fn(new Fn() {
            public Object run(Context c, Scriptable s, Object[] a) {
                return JsApi.response(c, s, JsApi.fetch("POST", str(a, 0), str(a, 1), JsApi.headers(a.length > 2 ? a[2] : null)));
            }
        }));
        put(ttsrv, "httpPostMultipart", fn(new Fn() {
            public Object run(Context c, Scriptable s, Object[] a) {
                // 附件表单：本 App 不做，别让插件炸，明确告诉它没成功
                Resp r = new Resp();
                r.code = 501;
                r.message = "multipart 没实现";
                return JsApi.response(c, s, r);
            }
        }));
        put(ttsrv, "cancelNetwork", fn(new Fn() {
            public Object run(Context c, Scriptable s, Object[] a) { return Undefined.instance; }
        }));
    }

    /** JsExtensions 那一套：字符串/字节、文件、uuid、采样率、提示。 */
    static void installTtsrvExtras(Context cx, Scriptable scope, final ScriptableObject ttsrv,
                                   final Map<String, String> files) {
        put(ttsrv, "strToBytes", fn(new Fn() {
            public Object run(Context c, Scriptable s, Object[] a) {
                return arrayBuffer(bytesOf(a[0]));
            }
        }));
        put(ttsrv, "bytesToStr", fn(new Fn() {
            public Object run(Context c, Scriptable s, Object[] a) {
                return new String(bytesOf(a[0]));
            }
        }));
        put(ttsrv, "randomUUID", fn(new Fn() {
            public Object run(Context c, Scriptable s, Object[] a) {
                return java.util.UUID.randomUUID().toString();
            }
        }));
        put(ttsrv, "getAudioSampleRate", fn(new Fn() {
            public Object run(Context c, Scriptable s, Object[] a) {
                return Integer.valueOf(mp3Rate(bytesOf(a[0])));
            }
        }));
        put(ttsrv, "getFile", fn(new Fn() {
            public Object run(Context c, Scriptable s, Object[] a) { return str(a, 0); }
        }));
        put(ttsrv, "readFile", fn(new Fn() {
            public Object run(Context c, Scriptable s, Object[] a) { return arrayBuffer(fileGet(files, str(a, 0))); }
        }));
        put(ttsrv, "readTxtFile", fn(new Fn() {
            public Object run(Context c, Scriptable s, Object[] a) { return new String(fileGet(files, str(a, 0))); }
        }));
        put(ttsrv, "writeTxtFile", fn(new Fn() {
            public Object run(Context c, Scriptable s, Object[] a) {
                files.put(str(a, 0), str(a, 1));
                return Undefined.instance;
            }
        }));
        put(ttsrv, "deleteFile", fn(new Fn() {
            public Object run(Context c, Scriptable s, Object[] a) { return Boolean.valueOf(files.remove(str(a, 0)) != null); }
        }));
        put(ttsrv, "fileExist", fn(new Fn() {
            public Object run(Context c, Scriptable s, Object[] a) { return Boolean.valueOf(files.containsKey(str(a, 0))); }
        }));
        put(ttsrv, "charsetDetect", fn(new Fn() {
            public Object run(Context c, Scriptable s, Object[] a) { return "UTF-8"; }
        }));
        // 提示：插件偶尔弹一下。这一层故意不碰 Android（保持能离线跑），所以只丢掉。
        put(ttsrv, "toast", fn(new Fn() {
            public Object run(Context c, Scriptable s, Object[] a) { return Undefined.instance; }
        }));
        put(ttsrv, "longToast", fn(new Fn() {
            public Object run(Context c, Scriptable s, Object[] a) { return Undefined.instance; }
        }));
        put(ttsrv, "setMargins", fn(new Fn() {
            public Object run(Context c, Scriptable s, Object[] a) { return Undefined.instance; }
        }));
        put(ttsrv, "log", fn(new Fn() {
            public Object run(Context c, Scriptable s, Object[] a) { return Undefined.instance; }
        }));
        // JsCrypto：十六进制 + 时间格式化
        put(ttsrv, "hexEncodeToString", fn(new Fn() {
            public Object run(Context c, Scriptable s, Object[] a) { return hex(bytesOf(a[0])); }
        }));
        put(ttsrv, "hexDecodeToString", fn(new Fn() {
            public Object run(Context c, Scriptable s, Object[] a) { return new String(unhex(str(a, 0))); }
        }));
        put(ttsrv, "hexDecodeToByteArray", fn(new Fn() {
            public Object run(Context c, Scriptable s, Object[] a) { return arrayBuffer(unhex(str(a, 0))); }
        }));
        put(ttsrv, "timeFormat", fn(new Fn() {
            public Object run(Context c, Scriptable s, Object[] a) {
                return new java.text.SimpleDateFormat(a.length > 1 ? str(a, 1) : "yyyy-MM-dd HH:mm:ss")
                        .format(new java.util.Date(a.length > 0 && a[0] != null && a[0] != Undefined.instance
                                ? (long) Context.toNumber(a[0]) : System.currentTimeMillis()));
            }
        }));
        put(ttsrv, "timeFormatUTC", fn(new Fn() {
            public Object run(Context c, Scriptable s, Object[] a) {
                java.text.SimpleDateFormat f = new java.text.SimpleDateFormat(
                        a.length > 1 ? str(a, 1) : "yyyy-MM-dd'T'HH:mm:ss'Z'");
                f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                return f.format(new java.util.Date(a.length > 0 && a[0] != null && a[0] != Undefined.instance
                        ? (long) Context.toNumber(a[0]) : System.currentTimeMillis()));
            }
        }));
    }

    /** 全局 http：{@code http.get / post / postMultipart}。 */
    static void installGlobalHttp(Context cx, Scriptable scope, ScriptableObject http,
                                  final BaseFunction get, final BaseFunction post) {
        put(http, "get", get);
        put(http, "post", post);
        put(http, "postMultipart", fn(new Fn() {
            public Object run(Context c, Scriptable s, Object[] a) {
                Resp r = new Resp();
                r.code = 501;
                r.message = "multipart 没实现";
                return JsApi.response(c, s, r);
            }
        }));
    }

    /** 全局 fs：readText / readFile / writeFile / rm / rename / mkdir / copy / exists / isFile。 */
    static void installGlobalFs(Context cx, Scriptable scope, ScriptableObject fs, final Map<String, String> store) {
        put(fs, "readText", fn(new Fn() {
            public Object run(Context c, Scriptable s, Object[] a) { return new String(fileGet(store, str(a, 0))); }
        }));
        put(fs, "readFile", fn(new Fn() {
            public Object run(Context c, Scriptable s, Object[] a) { return arrayBuffer(fileGet(store, str(a, 0))); }
        }));
        put(fs, "writeFile", fn(new Fn() {
            public Object run(Context c, Scriptable s, Object[] a) {
                store.put(str(a, 0), str(a, 1));
                return Undefined.instance;
            }
        }));
        put(fs, "rm", fn(new Fn() {
            public Object run(Context c, Scriptable s, Object[] a) { store.remove(str(a, 0)); return Undefined.instance; }
        }));
        put(fs, "rename", fn(new Fn() {
            public Object run(Context c, Scriptable s, Object[] a) {
                String v = store.remove(str(a, 0));
                if (v != null) store.put(str(a, 1), v);
                return Undefined.instance;
            }
        }));
        put(fs, "mkdir", fn(new Fn() {
            public Object run(Context c, Scriptable s, Object[] a) { return Undefined.instance; }
        }));
        put(fs, "copy", fn(new Fn() {
            public Object run(Context c, Scriptable s, Object[] a) {
                String v = store.get(str(a, 0));
                if (v != null) store.put(str(a, 1), v);
                return Undefined.instance;
            }
        }));
        put(fs, "exists", fn(new Fn() {
            public Object run(Context c, Scriptable s, Object[] a) { return Boolean.valueOf(store.containsKey(str(a, 0))); }
        }));
        put(fs, "isFile", fn(new Fn() {
            public Object run(Context c, Scriptable s, Object[] a) { return Boolean.valueOf(store.containsKey(str(a, 0))); }
        }));
    }

    private static byte[] fileGet(Map<String, String> store, String key) {
        String v = store.get(key);
        return v == null ? new byte[0] : v.getBytes();
    }

    /** 从 mp3 头里读采样率；读不出来给 24000（插件那边的常见值）。 */
    private static int mp3Rate(byte[] b) {
        if (b != null && b.length > 24 && b[0] == 'I' && b[1] == 'D' && b[2] == '3') {
            int i = 6;
            while (i + 4 < b.length) {
                String id = new String(b, i, 4);
                int size = ((b[i + 4] & 0x7f) << 21) | ((b[i + 5] & 0x7f) << 14)
                        | ((b[i + 6] & 0x7f) << 7) | (b[i + 7] & 0x7f);
                if (!id.startsWith("T") && !id.startsWith("A")) return 0;
                if (id.startsWith("T")) break;
                i += 10 + size;
            }
        }
        return 24000;
    }

    // ═════════════════════════ Buffer / UUID / environment ═════════════════════════

    /**
     * 全局 {@code Buffer} —— TTS Server 自己给 Rhino 加的那个（它包里的 NativeBuffer.kt）。
     * 插件里最常见的就是 {@code Buffer.from(x, 'base64')} 和 {@code buf.toString('hex')}。
     * 底下拿 Rhino 自己的 Uint8Array 兜着，于是 length、下标、slice 这些也都有。
     */
    static void installBuffer(Context cx, Scriptable scope) {
        final ScriptableObject buf = (ScriptableObject) cx.newObject(scope);
        put(buf, "BYTES_PER_ELEMENT", Integer.valueOf(1));
        put(buf, "from", fn(new Fn() {
            public Object run(Context c, Scriptable s, Object[] a) {
                if (a.length == 0 || a[0] == null || a[0] == Undefined.instance) {
                    throw Context.reportRuntimeError("First argument must be a string or buffer");
                }
                byte[] data;
                if (a[0] instanceof NativeArrayBuffer) {
                    byte[] all = ((NativeArrayBuffer) a[0]).getBuffer();
                    int off = a.length > 2 ? (int) Context.toNumber(a[2]) : 0;
                    int len = a.length > 3 ? (int) Context.toNumber(a[3]) : all.length;
                    int end = Math.min(all.length, off + len);
                    data = new byte[Math.max(0, end - off)];
                    System.arraycopy(all, Math.max(0, off), data, 0, data.length);
                } else {
                    data = decode(str(a, 0), a.length > 1 ? str(a, 1) : "utf-8");
                }
                return wrapBytes(c, s, data);
            }
        }));
        put(buf, "alloc", fn(new Fn() {
            public Object run(Context c, Scriptable s, Object[] a) {
                int n = a.length > 0 ? (int) Context.toNumber(a[0]) : 0;
                byte[] data = new byte[Math.max(0, n)];
                if (a.length > 1 && a[1] != Undefined.instance) java.util.Arrays.fill(data, (byte) Context.toNumber(a[1]));
                return wrapBytes(c, s, data);
            }
        }));
        put(buf, "concat", fn(new Fn() {
            public Object run(Context c, Scriptable s, Object[] a) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                if (a.length > 0 && a[0] instanceof Scriptable) {
                    Scriptable arr = (Scriptable) a[0];
                    Object lenObj = arr.get("length", arr);
                    int n = lenObj instanceof Number ? ((Number) lenObj).intValue() : 0;
                    for (int i = 0; i < n; i++) {
                        byte[] one = bytesOf(arr.get(i, arr));
                        bos.write(one, 0, one.length);
                    }
                }
                return wrapBytes(c, s, bos.toByteArray());
            }
        }));
        put(buf, "isBuffer", fn(new Fn() {
            public Object run(Context c, Scriptable s, Object[] a) {
                return Boolean.valueOf(bytesLike(a.length > 0 ? a[0] : null));
            }
        }));
        put(scope, "Buffer", buf);
    }

    /** 把一个 byte[] 包成"能当 Buffer 用"的对象。 */
    static Scriptable wrapBytes(Context cx, Scriptable scope, byte[] data) {
        Scriptable u8 = null;
        try {
            u8 = (Scriptable) cx.newObject(scope, "Uint8Array", new Object[]{arrayBuffer(data)});
        } catch (Throwable ignored) { }
        if (u8 == null) u8 = cx.newObject(scope);
        final Scriptable self = u8;
        put(self, "length", Integer.valueOf(data.length));
        put(self, "BYTES_PER_ELEMENT", Integer.valueOf(1));
        put(self, "toString", fn(new Fn() {
            public Object run(Context c, Scriptable s, Object[] a) {
                String enc = a.length > 0 && a[0] != Undefined.instance ? str(a, 0).toLowerCase() : "utf-8";
                return encode(rawOf(self), enc);
            }
        }));
        put(self, "slice", fn(new Fn() {
            public Object run(Context c, Scriptable s, Object[] a) {
                byte[] all = rawOf(self);
                int from = a.length > 0 ? (int) Context.toNumber(a[0]) : 0;
                int to = a.length > 1 && a[1] != Undefined.instance ? (int) Context.toNumber(a[1]) : all.length;
                if (from < 0) from += all.length;
                if (to < 0) to += all.length;
                from = Math.max(0, Math.min(all.length, from));
                to = Math.max(from, Math.min(all.length, to));
                byte[] out = new byte[to - from];
                System.arraycopy(all, from, out, 0, out.length);
                return wrapBytes(c, s, out);
            }
        }));
        return self;
    }

    private static byte[] rawOf(Scriptable o) {
        if (o instanceof org.mozilla.javascript.typedarrays.NativeTypedArrayView) {
            NativeArrayBuffer b = ((org.mozilla.javascript.typedarrays.NativeTypedArrayView<?>) o).getBuffer();
            if (b != null) return b.getBuffer();
        }
        return new byte[0];
    }

    static boolean bytesLike(Object o) {
        return o instanceof NativeArrayBuffer
                || o instanceof org.mozilla.javascript.typedarrays.NativeTypedArrayView
                || o instanceof byte[];
    }

    /** 编码：utf-8 / base64 / hex / ascii / latin1 / 其它 charset。 */
    static byte[] decode(String input, String encoding) {
        String e = encoding == null ? "utf-8" : encoding.toLowerCase().trim();
        try {
            if (e.equals("base64") || e.equals("base64url")) {
                String t = input.replace('-', '+').replace('_', '/');
                while (t.length() % 4 != 0) t = t + "=";
                return java.util.Base64.getDecoder().decode(t);
            }
            if (e.equals("hex")) return unhex(input);
            if (e.equals("latin1") || e.equals("binary")) return input.getBytes("ISO-8859-1");
            if (e.equals("utf8") || e.equals("utf-8") || e.isEmpty()) return input.getBytes("UTF-8");
            return input.getBytes(e);
        } catch (Throwable t) {
            throw Context.reportRuntimeError("Unsupported encoding: " + encoding);
        }
    }

    static String encode(byte[] data, String encoding) {
        String e = encoding == null ? "utf-8" : encoding.toLowerCase().trim();
        try {
            if (e.equals("base64") || e.equals("base64url")) {
                String out = java.util.Base64.getEncoder().encodeToString(data);
                return e.equals("base64url") ? out.replace('+', '-').replace('/', '_') : out;
            }
            if (e.equals("hex")) return hex(data);
            if (e.equals("latin1") || e.equals("binary")) return new String(data, "ISO-8859-1");
            if (e.equals("utf8") || e.equals("utf-8") || e.isEmpty()) return new String(data, "UTF-8");
            return new String(data, e);
        } catch (Throwable t) {
            throw Context.reportRuntimeError("Unsupported encoding: " + encoding);
        }
    }

    /** 全局 {@code UUID}：v4 / stringify / parse / validate。 */
    static void installUUID(Context cx, Scriptable scope, final String engineId) {
        final ScriptableObject u = (ScriptableObject) cx.newObject(scope);
        put(u, "v4", fn(new Fn() {
            public Object run(Context c, Scriptable s, Object[] a) { return java.util.UUID.randomUUID().toString(); }
        }));
        put(u, "stringify", fn(new Fn() {
            public Object run(Context c, Scriptable s, Object[] a) {
                byte[] b = bytesOf(a.length > 0 ? a[0] : null);
                if (b.length == 16) {
                    return new java.util.UUID(bytesToLong(b, 0), bytesToLong(b, 8)).toString();
                }
                return java.util.UUID.randomUUID().toString();
            }
        }));
        put(u, "parse", fn(new Fn() {
            public Object run(Context c, Scriptable s, Object[] a) {
                java.util.UUID id;
                try { id = java.util.UUID.fromString(str(a, 0)); }
                catch (Throwable t) { throw Context.reportRuntimeError("Invalid UUID: " + str(a, 0)); }
                byte[] out = new byte[16];
                longOf(id.getMostSignificantBits(), out, 0);
                longOf(id.getLeastSignificantBits(), out, 8);
                return wrapBytes(c, s, out);
            }
        }));
        put(u, "validate", fn(new Fn() {
            public Object run(Context c, Scriptable s, Object[] a) {
                try { java.util.UUID.fromString(str(a, 0)); return Boolean.TRUE; }
                catch (Throwable t) { return Boolean.FALSE; }
            }
        }));
        put(scope, "UUID", u);
    }

    private static long bytesToLong(byte[] b, int at) {
        long v = 0;
        for (int i = 0; i < 8; i++) v = (v << 8) | (b[at + i] & 0xffL);
        return v;
    }

    private static void longOf(long v, byte[] out, int at) {
        for (int i = 7; i >= 0; i--) {
            out[at + i] = (byte) (v & 0xff);
            v >>= 8;
        }
    }

    /** 全局 {@code environment}：TTS Server 那边给插件看缓存目录/引擎 id。 */
    static void installEnvironment(Context cx, Scriptable scope, String engineId) {
        ScriptableObject env = (ScriptableObject) cx.newObject(scope);
        put(env, "id", engineId == null ? "" : engineId);
        put(env, "cacheDir", "");
        put(scope, "environment", env);
        put(scope, "println", fn(new Fn() {
            public Object run(Context c, Scriptable s, Object[] a) { return Undefined.instance; }
        }));
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(Character.forDigit((x >> 4) & 0xf, 16)).append(Character.forDigit(x & 0xf, 16));
        return sb.toString();
    }

    static byte[] unhex(String s) {
        String t = s == null ? "" : s.trim();
        if (t.length() % 2 != 0) throw new IllegalArgumentException("hex 长度不对");
        byte[] out = new byte[t.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(t.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
