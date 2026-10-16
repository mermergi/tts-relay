package com.mermergi.ttsrelay;

import android.content.Context;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import com.mermergi.ttsrelay.js.PluginBridge;

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
    /** 我这侧只存数据，不认识的键别往插件里塞。 */
    private static final java.util.Set<String> NOT_VARS = new java.util.HashSet<String>(java.util.Arrays.asList(
            "src", "def_vars", "var_labels", "user_vars", "tts_data", "login_key", "login_url",
            "binding", "ua", "lang", "voice", "speed", "volume", "pitch"));

    private PluginBridge open(Context c) throws Exception {
        // 变量值：优先读 `user_vars`（别人那套引擎文件的模型），没有就退回"cfg 里非保留键都当变量"
        Map<String, String> vars = new LinkedHashMap<String, String>();
        String uv = it == null ? "" : it.s("user_vars", "");
        if (uv.trim().length() > 0) {
            try {
                org.json.JSONObject o = new org.json.JSONObject(uv);
                for (java.util.Iterator<String> k = o.keys(); k.hasNext(); ) {
                    String key = k.next();
                    vars.put(key, o.optString(key, ""));
                }
            } catch (Throwable ignored) { }
        }
        if (it != null) {
            for (java.util.Iterator<String> k = it.cfg.keys(); k.hasNext(); ) {
                String key = k.next();
                if (NOT_VARS.contains(key) || vars.containsKey(key)) continue;
                vars.put(key, it.s(key, ""));
            }
        }

        // 每音色的持久数据（插件往 ttsrv.tts.data 里塞的东西）
        Map<String, String> data = new LinkedHashMap<String, String>();
        String td = it == null ? "" : it.s("tts_data", "");
        if (td.trim().length() > 0) {
            try {
                org.json.JSONObject o = new org.json.JSONObject(td);
                for (java.util.Iterator<String> k = o.keys(); k.hasNext(); ) {
                    String key = k.next();
                    data.put(key, o.optString(key, ""));
                }
            } catch (Throwable ignored) { }
        }

        String id = it == null || it.id.length() == 0 ? "plugin" : it.id;
        PluginBridge h = new PluginBridge(c, id, it == null ? "" : it.s("src", ""), vars, data,
                locale(), TtsEngine.shortVoice(Prefs.voice(c)));
        // 构造里已经 eval() + onLoad() + onLoadUI() + onLoadData()（照它的生命周期）
        return h;
    }

    /** 把插件改过的 tts.data 存回引擎配置 —— 下次合成接着用（它本来就是持久存储）。 */
    private void saveData(Context c, PluginBridge h) {
        if (it == null || c == null) return;
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            for (Map.Entry<String, String> e : h.ttsData().entrySet()) {
                if (e.getValue() == null) continue;
                o.put(e.getKey(), e.getValue());
            }
            String json = o.toString();
            if (json.equals(it.s("tts_data", ""))) return;      // 没变不写盘
            it.put("tts_data", json);
            EngineStore.put(c, it);
        } catch (Throwable t) {
            LogBus.error("存插件的每音色数据失败：" + t.getMessage());
        }
    }

    @Override public String problem(Context c) {
        if (it == null || it.s("src", "").trim().length() == 0) return "还没导入插件源码";
        try {
            open(c);
            return null;
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

        PluginBridge h = open(c);
        try {
            // 照他们的 PluginTtsSource：这一路的参数要先塞进 ttsrv.tts，插件会读
            h.setSource(locale(), TtsEngine.shortVoice(s.voice), s.rate, s.volume, s.pitch);
            // 换音色时它会（他们的界面会调）—— 插件常在这儿初始化/记下选中的音色
            h.onVoiceChanged(locale(), TtsEngine.shortVoice(s.voice));
            // 档位换算（1.0 → 50）在它自己的 getAudio 里
            byte[] out = h.audioBytes(text, s.rate, s.volume, s.pitch);
            if (out == null || out.length == 0) throw new IllegalStateException("插件这次没给回音频");
            return out;
        } finally {
            saveData(c, h);
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
        PluginBridge h = null;
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
                saveData(c, h);
                try { h.destroy(); } catch (Throwable ignored) { }
            }
        }
        return out;
    }
}
