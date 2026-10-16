package com.mermergi.ttsrelay;

import android.content.Context;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 引擎类型：<b>TTS Server 的 JS 插件</b>。
 *
 * 直接把插件源码存进配置，用 {@link JsHost}（Rhino）跑起来 —— 不是猜它想干什么，
 * 是真的按它的 {@code getAudio} 去要音频：
 * <ul>
 *   <li>插件返回一个 http 链接 → 我们自己去取（绝大多数插件都是这种）</li>
 *   <li>插件自己发完请求、返回字节流 → 直接用</li>
 *   <li>音色表问 {@code EditorJS.getVoices()}，采样率问 {@code getAudioSampleRate()}</li>
 * </ul>
 *
 * 所以插件里那些算法（倍速怎么映射、SSML 怎么拼、参数怎么拼 URL）都是<b>原样执行</b>的，
 * 不存在"搬不过来"。
 */
public final class JsTtsEngine implements TtsEngine {

    private final EngineStore.Item it;

    public JsTtsEngine(EngineStore.Item it) { this.it = it; }

    @Override public String kind() { return EngineStore.KIND_JS; }

    @Override public String label() {
        return it != null && it.label.length() > 0 ? it.label : "JS 插件";
    }

    /** 每次跑都重新加载插件 —— Rhino 的作用域不能给多个线程共用，反正加载很便宜。 */
    private JsHost host() {
        Map<String, String> vars = new LinkedHashMap<String, String>();
        if (it != null) {
            for (java.util.Iterator<String> k = it.cfg.keys(); k.hasNext(); ) {
                String key = k.next();
                if ("src".equals(key) || "var_labels".equals(key) || "login_key".equals(key)) continue;
                vars.put(key, it.s(key, ""));
            }
        }
        String src = it == null ? "" : it.s("src", "");
        return JsHost.load(src, vars);
    }

    private String locale() {
        String l = it == null ? "" : it.s("lang", "");
        return l.length() == 0 ? "zh-CN" : l;
    }

    @Override public String problem(Context c) {
        if (it == null || it.s("src", "").trim().length() == 0) return "还没导入插件源码";
        try {
            JsHost h = host();
            if (h.hasFunction("getAudio") || h.hasFunction("getAudioV2")) return null;
            return "插件里既没有 getAudio 也没有 getAudioV2";
        } catch (Throwable t) {
            return "插件跑不起来：" + t.getMessage();
        }
    }

    // ───────────────────────── 合成 ─────────────────────────

    @Override
    public byte[] synth(Context c, String text, Say say) throws Exception {
        String bad = problem(c);
        if (bad != null) throw new IllegalStateException(bad);
        Say s = say == null ? new Say() : say;

        JsHost h = host();
        // 插件那两个参数的档位是 0-100（50 正常），我们是 1.0 正常
        int rate = Math.round(s.rate * JsHost.NEUTRAL);
        int volume = Math.round(s.volume * JsHost.NEUTRAL);
        int pitch = Math.round(s.pitch * JsHost.NEUTRAL);

        // 按 TTS Server 的次序：先问 getAudio（简单插件直接给链接/字节流），
        // 没有再走 getAudioV2（插件自己开 WebSocket，把音频一段段写回来）。
        Object v;
        if (h.hasFunction("getAudio")) {
            Object r = h.getAudio(text, locale(), TtsEngine.shortVoice(s.voice), rate, volume, pitch);
            // 别在这儿 jsToJava：它会把插件返回的数组/视图揉成 Object[]，
            // 后面认字节那步就再也认不出来了（"NativeUint8Array" 那一崩就是这么来的）
            v = (r == null || r == org.mozilla.javascript.Undefined.instance) ? null : r;
        } else {
            v = null;
        }
        if (v == null && h.hasFunction("getAudioV2")) {
            JsHost.V2 got = h.getAudioV2(text, locale(), TtsEngine.shortVoice(s.voice),
                    rate, volume, pitch, it == null ? 120000 : it.i("timeout", 120000));
            Object r = got.result;
            if (r instanceof CharSequence) {
                v = r;
            } else if (r instanceof byte[] || r instanceof InputStream) {
                v = r;
            } else {
                v = got.audio;                 // 插件写回桥里的字节
            }
        }
        if (v == null) throw new IllegalStateException("插件没给出音频");
        if (v instanceof byte[] && ((byte[]) v).length == 0) {
            throw new IllegalStateException("插件没写回任何音频");
        }

        // 插件把音频甩回来的姿势很多：byte[] / Uint8Array / Buffer / ArrayBuffer / 数字数组 /
        // 包一层 {audio:...} / base64 / data: URL / 地址。统一在这儿认。
        Object a = JsApi.audioValue(v);
        if (a == null) {
            throw new IllegalStateException("插件返回的东西认不出来：" + describe(v));
        }
        if (a instanceof InputStream) return readAll((InputStream) a);
        if (a instanceof byte[]) {
            byte[] out = (byte[]) a;
            if (out.length == 0) throw new IllegalStateException("插件写回的音频是空的");
            return out;
        }
        String url = a.toString().trim();
        if (url.startsWith("http://") || url.startsWith("https://")) return fetch(url);
        throw new IllegalStateException("插件返回的不是地址：" + url);
    }

    /** 报错得能看出到底是啥，别再只说「认不出来」。 */
    private static String describe(Object v) {
        if (v == null) return "null";
        StringBuilder sb = new StringBuilder();
        try { sb.append(v.getClass().getSimpleName()); } catch (Throwable ignored) { }
        if (v instanceof org.mozilla.javascript.Scriptable) {
            try { sb.append('/').append(((org.mozilla.javascript.Scriptable) v).getClassName()); }
            catch (Throwable ignored) { }
        }
        try {
            Object len = org.mozilla.javascript.ScriptableObject.getProperty(
                    (org.mozilla.javascript.Scriptable) v, "length");
            if (len instanceof Number) sb.append(" length=").append(len);
        } catch (Throwable ignored) { }
        return sb.toString();
    }

    /** 插件只给了地址 → 我们替它把音频取回来。 */
    private byte[] fetch(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(Math.max(5000, it == null ? 120000 : it.i("timeout", 120000)));
            conn.setUseCaches(false);
            conn.setRequestProperty("User-Agent", "tts-relay/2.7");
            if (it != null) {
                for (String line : it.s("headers", "").split("\n")) {
                    String x = line.trim();
                    int i = x.indexOf(':');
                    if (i <= 0) continue;
                    try { conn.setRequestProperty(x.substring(0, i).trim(), x.substring(i + 1).trim()); }
                    catch (Throwable ignored) { }
                }
                String cred = it.s("cred", "").trim();
                if (cred.length() > 0) conn.setRequestProperty("Cookie", cred);
            }
            int code = conn.getResponseCode();
            if (code != 200) {
                throw new IllegalStateException("插件给的地址回了 HTTP " + code);
            }
            InputStream in = conn.getInputStream();
            if (in == null) throw new IllegalStateException("插件给的地址没有内容");
            return readAll(in);
        } finally {
            try { conn.disconnect(); } catch (Throwable ignored) { }
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

    // ───────────────────────── 音色 ─────────────────────────

    @Override
    public List<String> voices(Context c) {
        List<String> out = new ArrayList<String>();
        try {
            JsHost h = host();
            if (!h.hasEditor()) return out;
            for (String[] v : h.voices(locale())) {
                String name = v[0];
                String id = v[1];
                if (name.length() == 0 && id.length() == 0) continue;
                out.add(id.length() == 0 || id.equals(name)
                        ? name : name + EngineStore.SEP + id);
            }
        } catch (Throwable t) {
            LogBus.error("跑插件的音色表失败：" + t.getMessage());
        }
        return out;
    }
}
