package com.mermergi.ttsrelay;

import com.mermergi.ttsrelay.js.AudioSink;
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

    /**
     * 被丢弃的宿主（有线程还吊在它里面）。僵尸线程回来之后要能认出"我已经被丢了"，
     * 别再接着重试、别再往日志里喷 —— 不然日志会被它搅乱（真机上见过：
     * 超时跳过后，被丢弃的那条线程又打出一行"插件第 1 次没给音频"）。
     */
    private static final java.util.Set<PluginBridge> DEAD =
            java.util.Collections.newSetFromMap(
                    new java.util.concurrent.ConcurrentHashMap<PluginBridge, Boolean>());

    /**
     * 宿主键 —— <b>只按插件</b>，不带 locale/voice：这才是它那边的粒度
     * （`TtsManager` 按 `PluginTtsSource.getKey()` 缓存，一个插件一个 provider）。
     *
     * 以前我按 locale|voice 各缓存一个宿主 → 分角色朗读时**每个音色一条 WebSocket、
     * 一份 Rhino 运行时**：长连接插件三条里就有一条会吊死（真机实测第 4 句卡 30 秒）。
     * 音色不用建宿主区分 —— 每次合成前都会 `setSource` + `onVoiceChanged`（照它们的做法）。
     */
    private String hostKey(String locale, String voice) {
        return cacheKey();
    }

    /** 配置改了（存过引擎）就把缓存清掉，别拿旧源码跑。 */
    public static void clearHosts() {
        for (PluginBridge b : HOSTS.values()) {
            try { b.destroy(); } catch (Throwable ignored) { }
        }
        HOSTS.clear();
        VOICES_CACHE.clear();
        DEAD.clear();
    }

    /**
     * 把宿主整个丢掉，但**不去 destroy** —— 里面可能还有合成线程吊在
     * `PipedInputStream.read()` 上，`destroy()` 会顺着它的生命周期再卡一次。
     * 直接弃掉：那条线程连同它的 Rhino 运行时一起留在后台，下次合成重建。
     */
    public static void abandonHosts() {
        final java.util.List<PluginBridge> dead =
                new java.util.ArrayList<PluginBridge>(HOSTS.values());
        DEAD.addAll(dead);
        HOSTS.clear();
        // 注意：**必须让它自己收摊（关掉 WebSocket）**，不能只从表里划掉。
        // 只划掉的话那条 WS 还连着 —— 僵尸会话 + 新开的会话两条并存，
        // 服务端会互相踢，下一句又开始"没有任何动静"，于是又丢一次宿主……级联。
        // 收摊可能卡（插件正吊着），所以丢给一条一次性线程去做，绝不挡路。
        for (final PluginBridge b : dead) {
            Thread t = new Thread(new Runnable() {
                public void run() {
                    try { b.destroy(); } catch (Throwable ignored) { }
                }
            }, "plugin-stop");
            t.setDaemon(true);
            t.start();
        }
        if (!dead.isEmpty()) {
            LogBus.add("插件宿主丢弃 " + dead.size() + " 个，并让它关掉旧连接（免得两条会话互相踢）");
        }
    }

    /** 这个宿主是不是已经被丢掉了？（僵尸线程用来自查） */
    static boolean isDead(PluginBridge h) { return h != null && DEAD.contains(h); }

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

    /** 插件配置里的合成超时（毫秒）—— 播放链拿它当硬超时。 */
    public int timeoutMs(int def) {
        return it == null ? def : it.i("timeout", def);
    }

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

    /**
     * 界面用：<b>插件自己画出来的界面</b>（它在 {@code onLoadUI(context, container)} 里
     * 往容器里塞的那些控件 —— JSpinner / JTextInput / JSeekBar，TTS Server 的插件都是这么写的）。
     *
     * <p>关键点：<b>不针对任何插件写死</b>。容器里的东西是插件自己建的，我们只是把它显示出来；
     * 换一个插件，界面自动就是那个插件的。
     *
     * <p>复用同一个缓存宿主（同一个 key），所以界面上改的东西和合成用的是同一份状态。
     *
     * @return 插件画的容器；插件没有界面 / 起不来就 null
     */
    public static android.view.View pluginUi(Context c, EngineStore.Item item) {
        PluginBridge h = sharedBridge(c, item);
        return h == null ? null : h.getUiContainer();
    }

    /**
     * 这个引擎<b>共用的那个宿主</b>（合成和界面同一个）：界面上换语言/声音之后直接对
     * 同一个宿主 {@code onVoiceChanged}，插件自己那坨控件就跟着变。
     */
    public static PluginBridge sharedBridge(Context c, EngineStore.Item item) {
        if (c == null || item == null) return null;
        String key = item.id + "|" + item.s("src", "").hashCode();
        try {
            PluginBridge h = HOSTS.get(key);
            if (h == null) {
                h = openFor(c, item, item.s("lang", "zh-CN"), item.s("voice", ""));
                HOSTS.put(key, h);
            }
            return h;
        } catch (Throwable t) {
            LogBus.error("打开插件宿主失败：" + t.getMessage());
            return null;
        }
    }

    /** 界面用：问插件有哪几种语言（和 TTS Server 那个编辑页一样，列表是问插件要的）。 */
    public static java.util.LinkedHashMap<String, String> localesOf(Context c, EngineStore.Item item) {
        PluginBridge h = null;
        try {
            h = openFor(c, item, item.s("lang", "zh-CN"), "");
            return new java.util.LinkedHashMap<String, String>(h.locales());
        } catch (Throwable t) {
            LogBus.error("问插件语言列表失败：" + t.getMessage());
            return new java.util.LinkedHashMap<String, String>();
        } finally {
            if (h != null) try { h.destroy(); } catch (Throwable ignored) { }
        }
    }

    /** 界面用：问插件这个语言下有哪些声音。 */
    public static java.util.List<String[]> voicesOf(Context c, EngineStore.Item item, String locale) {
        PluginBridge h = null;
        try {
            h = openFor(c, item, locale == null || locale.length() == 0 ? "zh-CN" : locale, "");
            return h.voices(locale == null || locale.length() == 0 ? "zh-CN" : locale);
        } catch (Throwable t) {
            LogBus.error("问插件声音列表失败：" + t.getMessage());
            return new java.util.ArrayList<String[]>();
        } finally {
            if (h != null) try { h.destroy(); } catch (Throwable ignored) { }
        }
    }

    /** 音色表缓存清掉（换了语言 / 声音之后要用一次）。 */
    public static void clearVoices() { VOICES_CACHE.clear(); }

    private PluginBridge open(Context c, String locale, String voice) throws Exception {
        return openFor(c, it, locale, voice);
    }

    /** 按某份引擎配置开一个插件宿主（界面上问语言/声音也走这里，保证参数一模一样）。 */
    public static PluginBridge openFor(Context c, EngineStore.Item it, String locale, String voice) throws Exception {
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
                // （插件就是这么被坑的：appkey 传了空串 → 服务端说 app key not found）
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

        // 关键：宿主是复用的，**必须串起来**。
        // 它那边（TtsManager + TtsPluginEngineV2 的 stream mutex）就是这么干的：
        // 同一个插件运行时同时被两个合成碰，就会互相把会话搅烂 → No data written。
        synchronized (h) {
            String v = pickVoice(c, s.voice);
            // 音色/参数没变就别再折腾插件一遍 —— 分角色朗读是"一个音色念几句又换下一个"，
            // 每次 setSource + onVoiceChanged 都会让插件重新初始化一回（慢的就是这一下）。
            String sig = locale() + "|" + v + "|" + s.rate + "|" + s.volume + "|" + s.pitch;
            if (!sig.equals(h.getAppliedSig())) {
                h.setSource(locale(), v, s.rate, s.volume, s.pitch);
                h.onVoiceChanged(locale(), v);
                h.setAppliedSig(sig);
            }

            Throwable first = null;
            for (int attempt = 0; attempt < 3; attempt++) {
                // 已经因为硬超时被丢掉了：这条线程是"僵尸"，别再重试、别再刷日志。
                if (isDead(h)) {
                    first = new IllegalStateException("宿主已被丢弃（这一句超时跳过了）");
                    break;
                }
                try {
                    byte[] out = h.audioBytes(text, s.rate, s.volume, s.pitch,
                            it == null ? 30000L : (long) Math.min(30000, it.i("timeout", 30000)));
                    if (out != null && out.length > 0) return out;
                    first = new IllegalStateException("插件这次没给回音频");
                } catch (Throwable t) {
                    first = t;
                }
                if (isDead(h)) break;      // 这次尝试期间被丢弃了 → 不吭声地收工
                // 第一次常撞在"插件正异步拉音色表/上一句刚结束"上；后面两次是给突发留的余地
                LogBus.error("插件第 " + (attempt + 1) + " 次没给音频（" + first.getMessage() + "），等一下再试");
                try {
                    Thread.sleep(400L + 600L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                h.setSource(locale(), v, s.rate, s.volume, s.pitch);
                h.onVoiceChanged(locale(), v);
            }
            if (!isDead(h)) saveData(c, h);
            throw new IllegalStateException(first == null ? "插件没给音频" : first.getMessage());
        }
    }

    /**
     * 流式合成：插件吐一块就交给 {@code sink}（播放端同时已经开播），
     * 不再等整句都下完。重试用在"一个字节都还没送出去"的情况 ——
     * 已经送过一半再重来，播放器听到的就是接了两遍的音频。
     */
    public void synthTo(Context c, String text, Say say, AudioSink sink) throws Exception {
        String bad = problem(c);
        if (bad != null) throw new IllegalStateException(bad);
        Say s = say == null ? new Say() : say;
        final PluginBridge h = hostFor(c, locale(), TtsEngine.shortVoice(s.voice));

        synchronized (h) {
            String v = pickVoice(c, s.voice);
            String sig = locale() + "|" + v + "|" + s.rate + "|" + s.volume + "|" + s.pitch;
            if (!sig.equals(h.getAppliedSig())) {
                h.setSource(locale(), v, s.rate, s.volume, s.pitch);
                h.onVoiceChanged(locale(), v);
                h.setAppliedSig(sig);
            }
            final boolean[] got = new boolean[]{false};
            AudioSink wrapped = new AudioSink() {
                public void chunk(byte[] data, int off, int len) throws Exception {
                    got[0] = true;
                    sink.chunk(data, off, len);
                }
            };
            Throwable first = null;
            for (int attempt = 0; attempt < 2; attempt++) {
                if (isDead(h)) throw new IllegalStateException("宿主已被丢弃（这一句超时跳过了）");
                try {
                    int n = h.audioTo(text, s.rate, s.volume, s.pitch,
                            it == null ? 30000L : (long) Math.min(30000, it.i("timeout", 30000)), wrapped);
                    if (n > 0) { saveData(c, h); return; }
                    first = new IllegalStateException("插件这次没给回音频");
                } catch (Throwable t) {
                    first = t;
                }
                if (got[0] || isDead(h)) break;      // 已经送过字节 / 已被丢弃 → 不重来
                LogBus.error("插件第 " + (attempt + 1) + " 次没给音频（" + first.getMessage() + "），等一下再试");
                try { Thread.sleep(400L); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                h.setSource(locale(), v, s.rate, s.volume, s.pitch);
                h.onVoiceChanged(locale(), v);
            }
            if (!isDead(h)) saveData(c, h);
            throw new IllegalStateException(first == null ? "插件没给音频" : first.getMessage());
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

    private static boolean eq(String a, String b) {
        return a != null && b != null && a.trim().equalsIgnoreCase(b.trim());
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
