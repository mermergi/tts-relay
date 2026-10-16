package com.mermergi.ttsrelay;

import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;
import org.mozilla.javascript.typedarrays.NativeArrayBuffer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 给 JS 插件用的 {@code Websocket} —— TTS Server 那个 {@code NativeWebSocket} 的对应物。
 *
 * 插件里的写法（和 TTS Server 一模一样）：
 * <pre>
 *   let ws = new Websocket(url, {Cookie: cookie, ...})
 *   ws.on('open', ...)   ws.on('binary', buf =&gt; callback.write(buf))
 *   ws.on('text', msg =&gt; ...)   ws.on('close', (code, reason) =&gt; ...)   ws.on('error', (err, resp) =&gt; ...)
 *   ws.send('{"event":"text",...}')      ws.cancel()
 *   ws.readyState === Websocket.OPEN
 * </pre>
 *
 * 底下用的是 {@link MiniWs}（这个 App 自己那个 RFC6455 客户端）。
 * 二进制交给 JS 时包成 {@link NativeArrayBuffer}，和 TTS Server 一致。
 */
public final class JsWebSocket {

    public static final int CONNECTING = 0;
    public static final int OPEN = 1;
    public static final int CLOSING = 2;
    public static final int CLOSED = 3;

    private final String url;
    private final LinkedHashMap<String, String> headers;
    private final int timeoutMs;
    private final Map<String, List<Function>> handlers = new LinkedHashMap<String, List<Function>>();
    private final Scriptable scope;
    private Scriptable obj;
    private MiniWs ws;
    private volatile int readyState = CONNECTING;
    private volatile boolean started = false;

    JsWebSocket(String url, LinkedHashMap<String, String> headers, int timeoutMs, Scriptable scope) {
        this.url = url;
        this.headers = headers;
        this.timeoutMs = timeoutMs;
        this.scope = scope;
    }

    /**
     * 造一个 JS 能用的 Websocket 对象。
     *
     * <b>注意：不在这儿连。</b> 插件是 {@code new Websocket(...)} 之后马上 {@code ws.on(...)} 注册回调的，
     * 这边要是一构造就开连，'open' 很可能比回调先到、被丢掉。所以先记着，
     * 等插件那一趟 JS 调用返回了，由 {@link JsHost#startPending()} 统一开闸。
     */
    public static JsWebSocket make(Context cx, Scriptable scope,
                                   String url, LinkedHashMap<String, String> headers, int timeoutMs) {
        JsWebSocket w = new JsWebSocket(url, headers, timeoutMs, scope);
        w.obj = w.build(cx, scope);
        return w;
    }

    Scriptable object() { return obj; }

    private Scriptable build(Context cx, final Scriptable scope) {
        final Scriptable o = cx.newObject(scope);
        ScriptableObject.putProperty(o, "readyState", Integer.valueOf(CONNECTING));

        ScriptableObject.putProperty(o, "on", new BaseFunction() {
            @Override public Object call(Context c, Scriptable s, Scriptable t, Object[] a) {
                String evt = a.length > 0 ? Context.toString(a[0]) : "";
                if (a.length > 1 && a[1] instanceof Function) {
                    List<Function> l = handlers.get(evt);
                    if (l == null) { l = new ArrayList<Function>(); handlers.put(evt, l); }
                    l.add((Function) a[1]);
                }
                return Undefined.instance;
            }
        });

        ScriptableObject.putProperty(o, "send", new BaseFunction() {
            @Override public Object call(Context c, Scriptable s, Scriptable t, Object[] a) {
                if (a.length == 0 || ws == null || readyState != OPEN) return Boolean.FALSE;
                try {
                    if (a[0] instanceof CharSequence) {
                        ws.sendText(a[0].toString());
                    } else {
                        byte[] b = bytesOf(a[0]);
                        if (b == null) return Boolean.FALSE;
                        ws.sendBinary(b);
                    }
                    return Boolean.TRUE;
                } catch (Throwable e) {
                    return Boolean.FALSE;
                }
            }
        });

        ScriptableObject.putProperty(o, "cancel", new BaseFunction() {
            @Override public Object call(Context c, Scriptable s, Scriptable t, Object[] a) {
                readyState = CLOSED;
                try { put("readyState", o, Integer.valueOf(CLOSED)); } catch (Throwable ignored) { }
                MiniWs w = ws;
                if (w != null) w.closeQuietly();
                return Undefined.instance;
            }
        });

        ScriptableObject.putProperty(o, "close", new BaseFunction() {
            @Override public Object call(Context c, Scriptable s, Scriptable t, Object[] a) {
                readyState = CLOSING;
                try { put("readyState", o, Integer.valueOf(CLOSING)); } catch (Throwable ignored) { }
                MiniWs w = ws;
                if (w != null) w.close();
                return Undefined.instance;
            }
        });

        return o;
    }

    /** 插件那一趟调用返回之后才会被叫到。 */
    void start() {
        if (started) return;
        started = true;
        Thread th = new Thread(new Runnable() {
            public void run() { loop(); }
        }, "js-ws");
        th.setDaemon(true);
        th.start();
    }

    private void loop() {
        try {
            ws = MiniWs.connect(url, headers, timeoutMs);
            readyState = OPEN;
            put("readyState", obj, Integer.valueOf(OPEN));
            fire("open");
            while (readyState == OPEN) {
                byte[] msg = ws.readBinary();
                if (msg == null) break;                       // 对端发了关闭帧
                if (ws.lastOpcode == 0x2) {
                    fire("binary", toArrayBuffer(msg));
                } else {
                    fire("text", new String(msg, "UTF-8"));
                }
            }
            readyState = CLOSED;
            put("readyState", obj, Integer.valueOf(CLOSED));
            fire("close", Integer.valueOf(1000), "");
        } catch (Throwable e) {
            readyState = CLOSED;
            put("readyState", obj, Integer.valueOf(CLOSED));
            String m = String.valueOf(e.getMessage());
            fire("error", m, errResp(e));
            fire("close", Integer.valueOf(1006), m);
        }
    }

    private NativeArrayBuffer toArrayBuffer(byte[] data) {
        NativeArrayBuffer buf = new NativeArrayBuffer(data.length);
        System.arraycopy(data, 0, buf.getBuffer(), 0, data.length);
        return buf;
    }

    /** 插件里 {@code resp.text()} 能拿到错因。 */
    private Scriptable errResp(final Throwable e) {
        Context cx = Context.enter();
        try {
            Scriptable o = cx.newObject(scope);
            ScriptableObject.putProperty(o, "text", new BaseFunction() {
                @Override public Object call(Context c, Scriptable s, Scriptable t, Object[] a) {
                    return String.valueOf(e.getMessage());
                }
            });
            ScriptableObject.putProperty(o, "code", new BaseFunction() {
                @Override public Object call(Context c, Scriptable s, Scriptable t, Object[] a) {
                    return Integer.valueOf(0);
                }
            });
            return o;
        } finally {
            Context.exit();
        }
    }

    private void put(String name, Scriptable target, Object value) {
        try { ScriptableObject.putProperty(target, name, value); } catch (Throwable ignored) { }
    }

    /**
     * 事件派发。WebSocket 的读取线程和"调用插件"的线程不是同一个，
     * 所以这里自己 enter 一个 Rhino Context；两边的 JS 不会同时跑（调用那边在等桥的字节）。
     */
    private void fire(String evt, Object... args) {
        List<Function> fs = handlers.get(evt);
        if (fs == null || fs.isEmpty()) return;
        Context cx = Context.enter();
        try {
            cx.setOptimizationLevel(-1);
            for (Function f : fs) {
                try {
                    f.call(cx, scope, obj, args);
                } catch (Throwable t) {
                    // 插件自己的回调炸了不该把整条链路带走
                }
            }
        } finally {
            Context.exit();
        }
    }

    /** JS 给的二进制：ArrayBuffer / 任意 typed array（Uint8Array、Buffer…）/ byte[] / 字节字符串都认。 */
    static byte[] bytesOf(Object o) {
        if (o == null || o == Undefined.instance) return null;
        if (o instanceof byte[]) return (byte[]) o;
        if (o instanceof NativeArrayBuffer) return ((NativeArrayBuffer) o).getBuffer();
        // 交给 JsApi 那一份：按 byteOffset/byteLength 切，所有 typed array 一起认
        if (o instanceof org.mozilla.javascript.typedarrays.NativeArrayBufferView) {
            byte[] b = JsApi.bytesOf(o);
            if (b != null && b.length > 0) return b;
        }
        if (o instanceof CharSequence) {
            try { return o.toString().getBytes("UTF-8"); } catch (Throwable t) { return null; }
        }
        Object j = Context.jsToJava(o, Object.class);
        return j instanceof byte[] ? (byte[]) j : null;
    }
}
