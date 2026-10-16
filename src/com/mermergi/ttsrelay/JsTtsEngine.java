package com.mermergi.ttsrelay;

import android.content.Context;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import com.mermergi.ttsrelay.js.PluginHost;

/**
 * 引擎类型：<b>TTS Server 的 JS 插件</b>。
 *
 * 宿主不是我手写的等价物了 —— Rhino 运行时，还有 {@code Buffer} / {@code Response} /
 * {@code Websocket} / {@code http} / {@code fs} / {@code ttsrv} 那一整套，用的是
 * TTS Server（jing332/tts-server-android，MIT）的 {@code lib-script} 源码原样编出来的
 * （见 {@code kt/ttsrv-script/}）；调用协议（{@code getAudio}、
 * {@code getAudioV2(request, callback)}、{@code EditorJS.getVoices}）也是照它
 * {@code TtsPluginEngineV2} 那份搬的（见 {@code kt/app/PluginHost.kt}）。
 *
 * 所以插件里的算法是真的在它自己的运行时里跑。
 */
public final class JsTtsEngine implements TtsEngine {

    private final EngineStore.Item it;

    public JsTtsEngine(EngineStore.Item it) { this.it = it; }

    @Override public String kind() { return EngineStore.KIND_JS; }

    @Override public String label() {
        return it != null && it.label.length() > 0 ? it.label : "JS 插件";
    }

    /**
     * 起一个插件宿主。<b>每次合成都重开</b>：Rhino 的作用域不能多线程共用，
     * 而加载本身很便宜（几十毫秒）。
     */
    private PluginHost open(Context c) throws Exception {
        Map<String, String> vars = new LinkedHashMap<String, String>();
        if (it != null) {
            for (java.util.Iterator<String> k = it.cfg.keys(); k.hasNext(); ) {
                String key = k.next();
                if ("src".equals(key) || "var_labels".equals(key) || "login_key".equals(key)
                        || "login_url".equals(key) || "binding".equals(key) || "ua".equals(key)) continue;
                vars.put(key, it.s(key, ""));
            }
        }
        String id = it == null || it.id.length() == 0 ? "plugin" : it.id;
        PluginHost h = new PluginHost(c, id, it == null ? "" : it.s("src", ""), vars);
        h.init();
        h.onLoad();       // 照抄它：execute 完调 onLoad
        return h;
    }

    @Override public String problem(Context c) {
        if (it == null || it.s("src", "").trim().length() == 0) return "还没导入插件源码";
        try {
            PluginHost h = open(c);
            if (h.hasAudioFn()) return null;
            return "插件里既没有 getAudio 也没有 getAudioV2";
        } catch (Throwable t) {
            String m = t.getMessage();
            return "插件跑不起来：" + (m == null ? t.getClass().getSimpleName() : m);
        }
    }

    // ───────────────────────── 合成 ─────────────────────────

    @Override
    public byte[] synth(Context c, String text, Say say) throws Exception {
        String bad = problem(c);
        if (bad != null) throw new IllegalStateException(bad);
        Say s = say == null ? new Say() : say;

        PluginHost h = open(c);
        try {
            // 插件那两个参数的档位是 0-100（50 正常），我们是 1.0 正常 —— 换算在 PluginHost 里
            byte[] out = h.audioBytes(text, locale(), TtsEngine.shortVoice(s.voice),
                    s.rate, s.volume, s.pitch);
            if (out == null || out.length == 0) throw new IllegalStateException("插件这次没给回音频");
            return out;
        } finally {
            try { h.destroy(); } catch (Throwable ignored) { }
        }
    }

    private String locale() {
        String l = it == null ? "" : it.s("lang", "");
        return l.length() == 0 ? "zh-CN" : l;
    }

    // ───────────────────────── 音色 ─────────────────────────

    /**
     * 音色表：照 TTS Server 界面的逻辑走 —— 先问 {@code EditorJS.getLocales()} 有哪几种语言，
     * 再一个个问 {@code getVoices(locale)}；问不出语言就只问配置里那一个。
     * 我们比它简单的地方：不按语言分组，平铺一张表。
     */
    @Override
    public List<String> voices(Context c) {
        List<String> out = new ArrayList<String>();
        if (it == null || it.s("src", "").trim().length() == 0) return out;
        PluginHost h = null;
        try {
            h = open(c);
            LinkedHashSet<String> locales = new LinkedHashSet<String>();
            try {
                locales.addAll(h.locales().keySet());
            } catch (Throwable ignored) { }
            if (locales.isEmpty()) locales.add(locale());

            LinkedHashSet<String> seen = new LinkedHashSet<String>();
            for (String loc : locales) {
                List<String[]> vs;
                try {
                    vs = h.voices(loc);
                } catch (Throwable t) {
                    LogBus.error("插件音色表拉取失败（" + loc + "）：" + t.getMessage());
                    continue;
                }
                for (String[] v : vs) {
                    String name = v[0] == null ? "" : v[0];
                    String id = v[1] == null ? "" : v[1];
                    if (name.length() == 0 && id.length() == 0) continue;
                    String one = id.length() == 0 || id.equals(name)
                            ? name : name + EngineStore.SEP + id;
                    if (seen.add(one)) out.add(one);
                }
            }
        } catch (Throwable t) {
            LogBus.error("跑插件的音色表失败：" + t.getMessage());
        } finally {
            if (h != null) {
                try { h.destroy(); } catch (Throwable ignored) { }
            }
        }
        return out;
    }
}
