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

    /**
     * 插件宿主缓存 —— <b>照 TTS Server 的做法</b>：它是把 provider 缓存起来的
     * （`TtsManager` + `AbstractCachedManager`，key 用 `PluginTtsSource.getKey()` = 插件 id）。
     *
     * 不缓存的话每次合成都重建运行时、**重连一次 WebSocket**：又慢（26KB 插件每次重新 eval），
     * 又容易撞上服务端的频率限制（表现就是"好一段、失败一段"）。
     */
    private static final java.util.Map<String, PluginBridge> HOSTS =
            new java.util.concurrent.ConcurrentHashMap<String, PluginBridge>();

    private String hostKey(String locale, String voice) {
        return cacheKey() + "|" + locale + "|" + voice;
    }

    /** 配置改了（存过引擎）就把缓存清掉，别拿旧源码跑。 */
    public static void clearHosts() {
        for (PluginBridge b : HOSTS.values()) {
            try { b.destroy(); } catch (Throwable ignored) { }
        }
        HOSTS.clear();
        VOICES_CACHE.clear();
    }

    /** 拿一个（可能已经建好的）插件宿主。 */
    private PluginBridge hostFor(Context c, String locale, String voice) throws Exception {
        String k = hostKey(locale, voice);
        PluginBridge h = HOSTS.get(k);
        if (h != null) return h;
        h = open(c, locale, voice);
        HOSTS.put(k, h);
        return h;
    }

    /** 插件音色表缓存：键带上 src 的 hash，改了插件自动失效。 */
    private static final java.util.Map<String, List<String>> VOICES_CACHE =
            new java.util.concurrent.ConcurrentHashMap<String, List<String>>();
    /** 每个引擎最终用哪个音色（插件自己的取值）。 */
    private static final java.util.Map<String, String> VOICE_PICK =
            new java.util.concurrent.ConcurrentHashMap<String, String>();

    private String cacheKey() {
        return (it == null ? "?" : it.id) + "|" + (it == null ? 0 : it.s("src", "").hashCode());
    }

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

    private PluginBridge open(Context c) throws Exception { return open(c, locale(), ""); }

    private PluginBridge open(Context c, String locale, String voice) throws Exception {
        // 变量值：优先读 `user_vars`（别人那套引擎文件的模型），没有就退回"cfg 里非保留键都当变量"
        Map<String, String> vars = new LinkedHashMap<String, String>();
        String uv = it == null ? "" : it.s("user_vars", "");
        if (uv.trim().length() > 0) {
            try {
                org.json.JSONObject o = new org.json.JSONObject(uv);
                for (java.util.Iterator<String> k = o.keys(); k.hasNext(); ) {
                    String key = k.next();
                    String val = o.optString(key, "");
                    // 空值 = 没设置（照 TTS Server：空值会把键删掉，让插件的 || 默认值生效）
                    if (val.trim().length() > 0) vars.put(key, val);
                }
            } catch (Throwable ignored) { }
        }
        if (it != null) {
            for (java.util.Iterator<String> k = it.cfg.keys(); k.hasNext(); ) {
                String key = k.next();
                if (NOT_VARS.contains(key) || vars.containsKey(key)) continue;
                String val = it.s(key, "");
                // 关键：空字符串别当"用户填了空"传给插件 —— 那会让它的默认值失效
                // （豆包插件就是这么被坑的：appkey 传了空串 → 服务端说 app key not found）
                if (val.trim().length() > 0) vars.put(key, val);
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
                locale, voice.length() > 0 ? voice : TtsEngine.shortVoice(Prefs.voice(c)));
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

        PluginBridge h = hostFor(c, locale(), TtsEngine.shortVoice(s.voice));
        try {
            // 照他们的 PluginTtsSource：这一路的参数要先塞进 ttsrv.tts，插件会读
            // 插件要的是它自己音色表里的取值（豆包那个 ICL 音色它不认识）
            String v = pickVoice(c, s.voice);
            h.setSource(locale(), v, s.rate, s.volume, s.pitch);
            // 换音色时它会（他们的界面会调）—— 插件常在这儿初始化/记下选中的音色
            h.onVoiceChanged(locale(), v);
            // 档位换算（1.0 → 50）在它自己的 getAudio 里
            try {
                byte[] out = h.audioBytes(text, s.rate, s.volume, s.pitch);
                if (out == null || out.length == 0) throw new IllegalStateException("插件这次没给回音频");
                return out;
            } catch (Throwable first) {
                // 换音色后的第一次很容易空手而归：插件要先异步拉一遍音色表
                // （它日志里那句「音色列表已加载: 445 个」），第一次请求就撞在这中间。
                // 第二次一定好，所以重试一次；宿主留着（缓存过）重试很快。
                LogBus.error("插件第一次没给音频（" + first.getMessage() + "），重试一次");
                try { Thread.sleep(300); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                h.setSource(locale(), v, s.rate, s.volume, s.pitch);
                h.onVoiceChanged(locale(), v);
                byte[] out2 = h.audioBytes(text, s.rate, s.volume, s.pitch);
                if (out2 == null || out2.length == 0) throw first;
                return out2;
            }
        } finally {
            saveData(c, h);
            try { h.destroy(); } catch (Throwable ignored) { }
        }
    }

    /** 音色表里那一行（`名字‖取值`）的取值部分。 */
    private static String idOf(String entry) {
        if (entry == null) return "";
        int i = entry.indexOf(EngineStore.SEP);
        return (i > 0 ? entry.substring(i + 1) : entry).trim();
    }

    private static String nameOf(String entry) {
        if (entry == null) return "";
        int i = entry.indexOf(EngineStore.SEP);
        return (i > 0 ? entry.substring(0, i) : entry).trim();
    }

    /**
     * 插件引擎该用哪个音色 —— 照它那边的语义（音色是插件自己的取值，不是全局那个）：
     * ① 调用方给的对得上就用它；② 引擎配置里存的；③ 插件音色表的第一个（并记住）。
     */
    private String pickVoice(Context c, String want) {
        String wantId = idOf(want);
        String wantName = nameOf(want);
        List<String> vs = voices(c);
        if (vs.isEmpty()) return wantId;

        for (String v : vs) {
            if (eq(idOf(v), wantId) || eq(nameOf(v), wantId) || eq(nameOf(v), wantName)
                    || eq(idOf(v), wantName)) {
                remember(c, idOf(v));
                return idOf(v);
            }
        }
        String saved = it == null ? "" : it.s("voice", "");
        if (saved.length() > 0) {
            for (String v : vs) {
                if (eq(idOf(v), saved) || eq(nameOf(v), saved)) {
                    remember(c, idOf(v));
                    return idOf(v);
                }
            }
        }
        String first = idOf(vs.get(0));
        remember(c, first);
        return first;
    }

    private static boolean eq(String a, String b) {
        return a != null && b != null && a.trim().equalsIgnoreCase(b.trim());
    }

    private void remember(Context c, String v) {
        if (it == null || v.length() == 0) return;
        String key = cacheKey();
        if (v.equals(VOICE_PICK.get(key))) return;
        VOICE_PICK.put(key, v);
        if (!v.equals(it.s("voice", ""))) {
            it.put("voice", v);
            try { EngineStore.put(c, it); } catch (Throwable ignored) { }
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
        List<String> cached = VOICES_CACHE.get(cacheKey());
        if (cached != null) return cached;
        List<String> out = fetchVoices(c);
        if (!out.isEmpty()) VOICES_CACHE.put(cacheKey(), out);
        return out;
    }

    private List<String> fetchVoices(Context c) {
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
