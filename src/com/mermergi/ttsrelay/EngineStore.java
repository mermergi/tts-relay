package com.mermergi.ttsrelay;

import com.mermergi.ttsrelay.js.AudioSink;
import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 引擎仓库。
 *
 * 设计照 TTS Server 那套：<b>引擎是个通用容器，不是一个写死的类型</b>。
 * <ul>
 *   <li>配置就是一张<b>键值表</b>，谁都能往里加自己的键；几种已知键只是"约定"，不是"白名单"。</li>
 *   <li>音色目录由引擎<b>自己声明怎么取</b>（voices_url + JSON 路径 + 字段名），
 *       而不是每种引擎写死一段拉取代码。</li>
 *   <li>音色<b>三路合并</b>：手工填的 + 从 voices_url 拉的 + 引擎自带的。</li>
 * </ul>
 */
public final class EngineStore {

    public static final String KIND_SYSTEM = "system";
    public static final String KIND_HTTP = "http";
    /** TTS Server 的 JS 插件 —— 源码存着，用 TTS Server 自己的运行时跑（见 kt/ + PluginHost）。 */
    public static final String KIND_JS = "js";

    public static final String ID_SYSTEM = "system";
    public static final String ID_HTTP = "ts-server";

    /** 显示名 与 取值 拼在一起的分隔符（音色名里不会出现）。 */
    public static final String SEP = "\u2016";

    /** 引擎类型的"约定键"：知道就自动填好，不知道也不拦着你自己加。 */
    public static final String[] KNOWN_KEYS = {
            "url", "method", "headers", "body", "content_type", "timeout", "json_key",
            "voices_url", "voices_path", "voices_name", "voices_id", "voices",
            "cookie", "appkey", "speaker", "format", "sample_rate",
            "src", "var_labels", "login_key",
            "pkg", "lang",
    };

    public static final class Item {
        public String id = "";
        public String kind = KIND_SYSTEM;
        public String label = "";
        public JSONObject cfg = new JSONObject();

        public String s(String key, String def) {
            String v = cfg.optString(key, def);
            return v == null ? def : v;
        }
        public int i(String key, int def) { return cfg.optInt(key, def); }
        public void put(String key, Object v) {
            try { cfg.put(key, v); } catch (Throwable ignored) { }
        }
        public Item copy() {
            Item it = new Item();
            it.id = id; it.kind = kind; it.label = label;
            try { it.cfg = new JSONObject(cfg.toString()); } catch (Throwable ignored) { }
            return it;
        }
    }

    private static volatile TtsEngine cache;
    private static volatile String cacheKey = "";

    // ───────────────────────── 读写 ─────────────────────────

    public static void ensureDefaults(Context c) {
        if (Prefs.engines(c).length() > 0) { migrate(c); return; }
        List<Item> list = new ArrayList<Item>();

        Item sys = new Item();
        sys.id = ID_SYSTEM; sys.kind = KIND_SYSTEM; sys.label = "手机系统语音";
        sys.put("pkg", "");
        sys.put("lang", "zh-CN");
        sys.put("voices", "");
        list.add(sys);

        Item srv = new Item();
        srv.id = ID_HTTP; srv.kind = KIND_HTTP; srv.label = "TTS Server（旧的）";
        srv.put("url", "http://127.0.0.1:1221/api/tts?text={{text}}"
                + "&engine=com.github.jing332.tts_server_android"
                + "&rate={{rate}}&pitch={{pitch}}&voice={{voice}}");
        srv.put("method", "GET");
        srv.put("headers", "");
        srv.put("body", "");
        srv.put("timeout", 60000);
        srv.put("json_key", "");
        srv.put("voices", "");
        srv.put("voices_url", "http://127.0.0.1:1221/api/voices?engine=com.github.jing332.tts_server_android");
        srv.put("voices_path", "");
        srv.put("voices_name", "name");
        srv.put("voices_id", "name");
        list.add(srv);

        save(c, list);
        Prefs.setActiveEngine(c, ID_SYSTEM);
    }

    /** 老配置补上后来才有的约定键（比如 voices_url），别让升级把音色拉取弄丢。 */
    private static void migrate(Context c) {
        List<Item> list = rawList(c);
        boolean changed = false;
        for (Item it : list) {
            if (KIND_HTTP.equals(it.kind)) {
                if (!it.cfg.has("voices_url")) {
                    it.put("voices_url", deriveVoicesUrl(it.s("url", "")));
                    changed = true;
                }
                if (!it.cfg.has("voices_path")) { it.put("voices_path", ""); changed = true; }
                if (!it.cfg.has("voices_name")) { it.put("voices_name", "name"); changed = true; }
                if (!it.cfg.has("voices_id")) { it.put("voices_id", "name"); changed = true; }
            }
            if (!it.cfg.has("voices")) { it.put("voices", ""); changed = true; }
        }
        if (changed) save(c, list);
    }

    /** 从 /api/tts?...&engine=X 推出 /api/voices?engine=X。 */
    private static String deriveVoicesUrl(String url) {
        if (url == null || !url.contains("/api/tts")) return "";
        String base = url.substring(0, url.indexOf("/api/tts"));
        String eng = "";
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("engine=([^&\\s]+)").matcher(url);
            if (m.find()) eng = m.group(1);
        } catch (Throwable ignored) { }
        return base + "/api/voices" + (eng.length() > 0 ? "?engine=" + eng : "");
    }

    private static List<Item> rawList(Context c) {
        List<Item> out = new ArrayList<Item>();
        try {
            JSONArray arr = new JSONArray(Prefs.engines(c));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Item it = new Item();
                it.id = o.optString("id", "");
                it.kind = o.optString("kind", KIND_SYSTEM);
                it.label = o.optString("label", it.id);
                it.cfg = o.optJSONObject("cfg");
                if (it.cfg == null) it.cfg = new JSONObject();
                if (it.id.length() > 0) out.add(it);
            }
        } catch (Throwable ignored) { }
        return out;
    }

    public static List<Item> all(Context c) {
        ensureDefaults(c);
        return rawList(c);
    }

    public static Item get(Context c, String id) {
        for (Item it : all(c)) if (it.id.equals(id)) return it;
        return null;
    }

    public static boolean has(Context c, String id) {
        return id != null && id.length() > 0 && get(c, id) != null;
    }

    /** 同名同类型的那一个（导入去重用：同一个文件导两遍，不该多出一份）。 */
    public static Item sameLabel(Context c, String kind, String label) {
        if (label == null || label.trim().length() == 0) return null;
        for (Item it : all(c)) {
            if (it.kind.equals(kind) && it.label.equals(label)) return it;
        }
        return null;
    }

    public static void save(Context c, List<Item> list) {
        JSONArray arr = new JSONArray();
        for (Item it : list) {
            JSONObject o = new JSONObject();
            try {
                o.put("id", it.id);
                o.put("kind", it.kind);
                o.put("label", it.label);
                o.put("cfg", it.cfg);
            } catch (Throwable ignored) { }
            arr.put(o);
        }
        Prefs.setEngines(c, arr.toString());
        invalidate();
    }

    public static void put(Context c, Item it) {
        List<Item> list = rawList(c);
        boolean hit = false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(it.id)) { list.set(i, it); hit = true; break; }
        }
        if (!hit) list.add(it);
        save(c, list);
    }

    public static void remove(Context c, String id) {
        List<Item> list = rawList(c);
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).id.equals(id)) list.remove(i);
        }
        save(c, list);
        if (id.equals(Prefs.activeEngine(c))) {
            Prefs.setActiveEngine(c, list.isEmpty() ? "" : list.get(0).id);
        }
    }

    public static String activeId(Context c) {
        ensureDefaults(c);
        String id = Prefs.activeEngine(c);
        if (get(c, id) == null) {
            List<Item> l = rawList(c);
            id = l.isEmpty() ? "" : l.get(0).id;
            Prefs.setActiveEngine(c, id);
        }
        return id;
    }

    public static void setActive(Context c, String id) {
        Prefs.setActiveEngine(c, id);
        invalidate();
    }

    public static void invalidate() { cache = null; cacheKey = ""; }

    // ───────────────────────── 当前引擎 ─────────────────────────

    public static TtsEngine activeEngine(Context c) {
        String id = activeId(c);
        Item it = get(c, id);
        if (it == null) it = get(c, ID_SYSTEM);
        if (it == null) {
            it = new Item();
            it.id = ID_SYSTEM;
            it.kind = KIND_SYSTEM;
            it.label = "手机系统语音";
        }
        String key = it.id + "|" + it.cfg.toString();
        TtsEngine e = cache;
        if (e != null && key.equals(cacheKey)) return e;
        e = build(it);
        cache = e;
        cacheKey = key;
        return e;
    }

    public static TtsEngine build(Item it) {
        if (it == null) it = new Item();
        if (KIND_HTTP.equals(it.kind)) return new HttpTtsEngine(it);
        if (KIND_JS.equals(it.kind)) return new JsTtsEngine(it);
        return new SystemTtsEngine(it);
    }

    // ───────────────────────── 音色目录 ─────────────────────────

    /**
     * 音色从哪来：<b>三路合并</b>，和 TTS Server 一个思路。
     * <ol>
     *   <li>手工填的 voices</li>
     *   <li>voices_url 拉的 JSON 目录（路径 / 字段名都是配置项）</li>
     *   <li>引擎自己知道的（系统 TTS 的语音列表、插件给的推荐列表）</li>
     * </ol>
     */
    public static List<String> fetchVoices(Context c, Item it) {
        LinkedHashSet<String> out = new LinkedHashSet<String>();
        if (it == null) return new ArrayList<String>();

        for (String line : it.s("voices", "").split("\n")) {
            String s = line.trim();
            if (s.length() > 0) out.add(s);
        }

        String vu = it.s("voices_url", "").trim();
        if (vu.length() > 0) {
            try {
                out.addAll(fetchFromUrl(it, vu));
            } catch (Throwable t) {
                LogBus.error("从 voices_url 拉音色失败：" + t.getMessage());
            }
        }

        try {
            out.addAll(build(it).voices(c));
        } catch (Throwable t) {
            LogBus.error("引擎自带音色拉取失败：" + t.getMessage());
        }
        return new ArrayList<String>(out);
    }

    /**
     * 从插件源码里读它自己声明的变量（TTS Server 引擎文件的 {@code vars}），写回：
     * {@code def_vars}（声明）/ {@code var_labels}（界面显示名）/ 缺的 cfg 键 / {@code login_*}。
     *
     * 打开 JS 引擎的编辑框、以及导入插件时都会走这儿 —— 所以老引擎不用重新导入，
     * 它声明的「API密钥」这类变量会自己冒出来。
     */
    public static boolean syncJsMeta(Context c, Item it) {
        if (it == null || !KIND_JS.equals(it.kind)) return false;
        String src = it.s("src", "");
        if (src.trim().length() == 0) return false;
        String before = it.s("def_vars", "") + "|" + it.s("author", "") + "|" + it.s("version", "");
        com.mermergi.ttsrelay.js.PluginBridge h = null;
        try {
            h = new com.mermergi.ttsrelay.js.PluginBridge(c,
                    it.id.length() == 0 ? "plugin" : it.id, src, null, null,
                    it.s("lang", "zh-CN"), "");
            JSONObject labels = new JSONObject();
            JSONObject defs = new JSONObject();
            String loginKey = "";
            for (java.util.Map.Entry<String, java.util.Map<String, String>> e : h.defVars().entrySet()) {
                String key = e.getKey();
                java.util.Map<String, String> def = e.getValue();
                if (!it.cfg.has(key)) it.put(key, "");

                String label = def == null ? "" : nn(def.get("name"));
                if (label.length() == 0) label = key;
                String hint = def == null ? "" : nn(def.get("hint"));
                if (hint.length() > 0) label = label + "（" + hint + "）";
                try { labels.put(key, label); } catch (Throwable ignored) { }

                JSONObject d = new JSONObject();
                if (def != null) {
                    for (java.util.Map.Entry<String, String> f : def.entrySet()) {
                        try { d.put(f.getKey(), nn(f.getValue())); } catch (Throwable ignored) { }
                    }
                }
                // 插件声明的默认值（vars 里的 value）—— TTS Server 会拿它预填。
                // 不填的话插件读 userVars 拿到 undefined，请求就会缺字段（插件会直接报 app key not found）。
                String dv = def == null ? "" : nn(def.get("value"));
                if (dv.length() > 0 && it.s(key, "").trim().length() == 0) it.put(key, dv);
                try { defs.put(key, d); } catch (Throwable ignored) { }

                String loginUrl = def == null ? "" : nn(def.get("loginUrl"));
                if (loginKey.length() == 0 && loginUrl.length() > 0) {
                    loginKey = key;
                    it.put("login_key", loginKey);
                    it.put("login_url", loginUrl);
                    String b = def == null ? "" : nn(def.get("binding"));
                    it.put("binding", b.length() > 0 ? b : "session");
                    it.put("ua", "mobile");
                }
            }
            // 插件没声明"要登录"的变量 → 之前那套 Cookie 登录键清干净，
            // 否则 JS 引擎编辑框里会冒出一个 Cookie 框，让人把 API 密钥填错地方
            if (loginKey.length() == 0) {
                it.cfg.remove("login_key");
                it.cfg.remove("login_url");
                it.cfg.remove("binding");
                it.cfg.remove("ua");
            }
            if (labels.length() > 0) it.put("var_labels", labels.toString());
            if (defs.length() > 0) it.put("def_vars", defs.toString());
            if (!it.cfg.has("user_vars")) it.put("user_vars", "{}");
            if (!it.cfg.has("tts_data")) it.put("tts_data", "{}");
            if (h.name().length() > 0 && (it.label == null || it.label.length() == 0
                    || "JS 插件".equals(it.label))) {
                it.label = h.name();
            }
            if (h.author().length() > 0) it.put("author", h.author());
            if (h.version() >= 0) it.put("version", h.version());
            if (h.iconUrl().length() > 0) it.put("icon", h.iconUrl());
        } catch (Throwable t) {
            LogBus.error("读插件声明的变量失败：" + t.getMessage());
        } finally {
            if (h != null) { try { h.destroy(); } catch (Throwable ignored) { } }
        }
        return !before.equals(it.s("def_vars", "") + "|" + it.s("author", "") + "|" + it.s("version", ""));
    }

    private static String nn(String v) { return v == null ? "" : v; }

    /**
     * 把「别处已经抓过的登录信息」搬到插件声明的变量上。
     *
     * 典型场景：以前用的是原生引擎（Cookie 存在 cfg 里），现在改用插件版 ——
     * 插件声明的变量也叫 cookie，没必要让用户再登一次。任何一个引擎里存着的
     * cookie / cred 都能当来源，谁先有值用谁。
     */
    public static void inheritLogin(Context c, Item js) {
        if (js == null || !KIND_JS.equals(js.kind)) return;
        java.util.Map<String, String> defs = new java.util.LinkedHashMap<String, String>();
        try {
            JSONObject d = new JSONObject(js.s("def_vars", ""));
            for (java.util.Iterator<String> k = d.keys(); k.hasNext(); ) {
                String key = k.next();
                JSONObject one = d.optJSONObject(key);
                String binding = one == null ? "" : one.optString("binding", "");
                String loginUrl = one == null ? "" : one.optString("loginUrl", "");
                boolean cookieish = key.toLowerCase().contains("cookie")
                        || binding.toLowerCase().contains("cookie");
                if (cookieish && (loginUrl.length() > 0 || binding.length() > 0 || true)) {
                    defs.put(key, binding);
                }
            }
        } catch (Throwable ignored) { }
        if (defs.isEmpty()) return;

        String src = "";
        for (Item other : all(c)) {
            if (other == js) continue;
            String cv = other.s("cookie", "");
            if (cv.length() == 0) cv = other.s("cred", "");
            if (cv.length() > 0) { src = cv; break; }
        }
        if (src.length() == 0) return;

        for (String key : defs.keySet()) {
            if (js.s(key, "").length() == 0) {
                js.put(key, src);
                js.put("login_key", key);
                LogBus.add("把已有的登录信息填进了插件的「" + key + "」变量（" + src.length() + " 字符）");
            }
        }
        syncUserVars(js);
    }

    /** 插件声明了变量、但用户一个都没填 —— 它界面上会因此提示「请设置变量」。 */
    public static boolean needsVars(Item it) {
        if (it == null || !KIND_JS.equals(it.kind)) return false;
        int n = 0;
        try { n = new JSONObject(it.s("def_vars", "")).length(); } catch (Throwable ignored) { }
        if (n == 0) return false;
        int filled = 0;
        try {
            JSONObject uv = new JSONObject(it.s("user_vars", ""));
            for (java.util.Iterator<String> k = uv.keys(); k.hasNext(); ) {
                if (uv.optString(k.next(), "").trim().length() > 0) filled++;
            }
        } catch (Throwable ignored) { }
        return filled == 0;
    }

    /** 插件自己的缓存目录（它 `ttsrv.getFile()` 落的地方）。 */
    public static java.io.File cacheDir(Context c, Item it) {
        java.io.File base = c == null ? null : c.getExternalCacheDir();
        if (base == null) return null;
        return new java.io.File(base, it == null || it.id.length() == 0 ? "plugin" : it.id);
    }

    public static boolean hasCache(Context c, Item it) {
        java.io.File d = cacheDir(c, it);
        return d != null && d.isDirectory() && d.list() != null && d.list().length > 0;
    }

    public static void clearCache(Context c, Item it) {
        java.io.File d = cacheDir(c, it);
        if (d == null || !d.isDirectory()) return;
        java.io.File[] fs = d.listFiles();
        if (fs == null) return;
        for (java.io.File f : fs) deleteTree(f);
    }

    private static void deleteTree(java.io.File f) {
        if (f.isDirectory()) {
            java.io.File[] fs = f.listFiles();
            if (fs != null) for (java.io.File x : fs) deleteTree(x);
        }
        f.delete();
    }

    /**
     * 这个音色自己的 {@code ttsrv.tts.data}（插件的「每套 TTS 配置数据」）。
     *
     * <p>照 TTS Server：data 本来就是<b>跟着 TTS 配置（音色）走</b>的，一个音色一份。
     * 我们这边一个插件引擎可以挂很多音色，所以按音色分开存 —— 插件的「语气」正是存在这里，
     * 以前只存了引擎那一份，等于所有音色共用同一个语气。
     */

    /**
     * 把 cfg 里的 JSON 串解析成对象。
     *
     * <p>坑：{@code new JSONObject("")} 会抛 JSONException（"End of input at character 0"）。
     * 以前直接 {@code new JSONObject(it.s("params_by_voice", ""))} 再 catch 吞掉，
     * 结果就是：<b>第一次</b>存某个音色的数据时 cfg 里还没有这个键 → 解析抛异常 → 整段写入被跳过，
     * 什么都存不进去（插件的「自定义语气指令」、每音色的语速/音量/音高全中这一枪）。
     * 空串/脏数据一律当空对象。</p>
     */
    static JSONObject obj(String raw) {
        if (raw == null || raw.trim().length() == 0) return new JSONObject();
        try {
            return new JSONObject(raw);
        } catch (Throwable t) {
            return new JSONObject();
        }
    }

    public static java.util.LinkedHashMap<String, String> ttsDataForVoice(Item it, String voice) {
        java.util.LinkedHashMap<String, String> m = new java.util.LinkedHashMap<String, String>();
        if (it == null) return m;
        String key = voice == null ? "" : voice.trim();
        if (key.length() > 0) {
            try {
                JSONObject all = obj(it.s("tts_data_by_voice", ""));
                JSONObject one = all.optJSONObject(key);
                if (one != null) {
                    for (java.util.Iterator<String> k = one.keys(); k.hasNext(); ) {
                        String kk = k.next();
                        m.put(kk, one.optString(kk, ""));
                    }
                    return m;
                }
            } catch (Throwable ignored) { }
        }
        try {
            JSONObject o = obj(it.s("tts_data", ""));
            for (java.util.Iterator<String> k = o.keys(); k.hasNext(); ) {
                String kk = k.next();
                m.put(kk, o.optString(kk, ""));
            }
        } catch (Throwable ignored) { }
        return m;
    }

    /**
     * 这个音色自己的语速 / 音量 / 音高（0 = 跟随全局）。
     * 照 TTS Server：这三个参数本来就挂在「TTS 配置（音色）」上，一个音色一份。
     */
    public static float[] paramsForVoice(Item it, String voice) {
        float[] out = new float[]{0f, 0f, 0f};
        if (it == null || voice == null || voice.trim().length() == 0) return out;
        try {
            JSONObject all = obj(it.s("params_by_voice", ""));
            JSONObject one = all.optJSONObject(voice.trim());
            if (one == null) return out;
            out[0] = (float) one.optDouble("speed", 0);
            out[1] = (float) one.optDouble("volume", 0);
            out[2] = (float) one.optDouble("pitch", 0);
        } catch (Throwable ignored) { }
        return out;
    }

    /** 存这个音色的三项参数（<=0 的项就不存，等于跟随上层）。 */
    public static void setParamsForVoice(Item it, String voice, float speed, float volume, float pitch) {
        if (it == null || voice == null || voice.trim().length() == 0) return;
        try {
            String key = voice.trim();
            JSONObject all = obj(it.s("params_by_voice", ""));
            JSONObject one = new JSONObject();
            if (speed > 0f) one.put("speed", (double) speed);
            if (volume > 0f) one.put("volume", (double) volume);
            if (pitch > 0f) one.put("pitch", (double) pitch);
            if (one.length() == 0) all.remove(key);
            else all.put(key, one);
            if (all.length() == 0) it.cfg.remove("params_by_voice");
            else it.put("params_by_voice", all.toString());
        } catch (Throwable ignored) { }
    }

    /** 存这个音色的 tts.data；和引擎级那份一样就不单独存（让它跟着引擎默认走）。 */
    public static void setTtsDataForVoice(Item it, String voice, java.util.Map<String, String> data) {
        if (it == null) return;
        String key = voice == null ? "" : voice.trim();
        try {
            JSONObject one = new JSONObject();
            if (data != null) {
                for (java.util.Map.Entry<String, String> e : data.entrySet()) {
                    if (e.getValue() != null) one.put(e.getKey(), e.getValue());
                }
            }
            if (key.length() == 0) {         // 不知道是哪个音色：就写引擎级那份
                if (one.length() == 0) it.cfg.remove("tts_data");
                else it.put("tts_data", one.toString());
                return;
            }
            JSONObject all = obj(it.s("tts_data_by_voice", ""));
            JSONObject base = obj(it.s("tts_data", ""));
            if (one.toString().equals(base.toString())) all.remove(key);
            else all.put(key, one);
            if (all.length() == 0) it.cfg.remove("tts_data_by_voice");
            else it.put("tts_data_by_voice", all.toString());
        } catch (Throwable ignored) { }
    }

    /**
     * 插件声明的变量 → {@code user_vars}：TTS Server 是"声明"和"用户填的值"分开存的，
     * 插件读的是 {@code ttsrv.userVars['api_key']}，塞在 cfg 的散键里容易对不上。
     */
    public static void syncUserVars(Item it) {
        if (it == null || !KIND_JS.equals(it.kind)) return;
        java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<String>();
        try {
            JSONObject def = new JSONObject(it.s("def_vars", ""));
            for (java.util.Iterator<String> k = def.keys(); k.hasNext(); ) keys.add(k.next());
        } catch (Throwable ignored) { }
        if (keys.isEmpty()) {
            try {
                JSONObject lb = new JSONObject(it.s("var_labels", ""));
                for (java.util.Iterator<String> k = lb.keys(); k.hasNext(); ) keys.add(k.next());
            } catch (Throwable ignored) { }
        }
        if (keys.isEmpty()) return;
        try {
            JSONObject o = new JSONObject();
            for (String k : keys) {
                String v = it.s(k, "");
                if (v.length() > 0) o.put(k, v);
            }
            it.put("user_vars", o.toString());
        } catch (Throwable ignored) { }
    }

    /**
     * voices_url 失败后的冷却（引擎 id + url → 失败时刻）。
     *
     * 为什么要有它：老引擎（比如指向已经关掉的 127.0.0.1:1221 那条）会**每次开界面、
     * 每次刷音色表都去撞一堵死墙**，连不上还要等 8 秒，日志里反复刷同一行报错。
     * 死地址就该记住"它是死的"，隔几分钟再试一次就够了。
     */
    private static final java.util.Map<String, Long> VOICES_FAIL =
            new java.util.concurrent.ConcurrentHashMap<String, Long>();
    private static final long VOICES_FAIL_COOL = 5 * 60 * 1000L;

    /** 这个音色源现在是不是在冷却里（界面可以据此显示"上次失败，5 分钟内不再试"）。 */
    public static boolean voicesUrlCooling(Item it) {
        if (it == null) return false;
        String k = it.id + "|" + it.s("voices_url", "").trim();
        Long t = VOICES_FAIL.get(k);
        return t != null && System.currentTimeMillis() - t < VOICES_FAIL_COOL;
    }

    /**
     * 拉音色。失败**不抛**（返回空表），并且带冷却：
     * 只在这一轮失败时打一行日志，之后 5 分钟内不再请求、也不再刷日志。
     */
    private static List<String> fetchFromUrl(Item it, String url) throws Exception {
        final String fk = it.id + "|" + url;
        Long bad = VOICES_FAIL.get(fk);
        if (bad != null && System.currentTimeMillis() - bad < VOICES_FAIL_COOL) {
            return new ArrayList<String>();      // 冷却中：不请求、不报错
        }
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(20000);
            conn.setRequestMethod("GET");
            conn.setUseCaches(false);
            conn.setRequestProperty("User-Agent", "tts-relay/2.7");
            for (String line : it.s("headers", "").split("\n")) {
                String s = line.trim();
                int i = s.indexOf(':');
                if (i <= 0) continue;
                try { conn.setRequestProperty(s.substring(0, i).trim(), s.substring(i + 1).trim()); }
                catch (Throwable ignored) { }
            }
            String ck = it.s("cookie", "").trim();
            if (ck.length() > 0) conn.setRequestProperty("Cookie", ck);
            if (conn.getResponseCode() != 200) {
                throw new IllegalStateException("HTTP " + conn.getResponseCode());
            }
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream(64 * 1024);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            String text = new String(bos.toByteArray(), "UTF-8");

            JSONArray arr = arrayAt(text, it.s("voices_path", ""));
            String nameKey = it.s("voices_name", "name");
            String idKey = it.s("voices_id", "name");
            List<String> out = new ArrayList<String>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    String s = arr.optString(i, "");
                    if (s.length() > 0) out.add(s);
                    continue;
                }
                String name = o.optString(nameKey, "");
                String id = idKey.length() == 0 ? name : o.optString(idKey, name);
                if (id.length() == 0) id = name;
                if (id.length() == 0) continue;
                out.add(id.equals(name) ? name : name + SEP + id);
            }
            VOICES_FAIL.remove(fk);
            return out;
        } catch (Throwable t) {
            long now = System.currentTimeMillis();
            boolean first = bad == null || now - bad >= VOICES_FAIL_COOL;
            VOICES_FAIL.put(fk, now);
            if (first) {
                LogBus.error("从 voices_url 拉音色失败：" + t.getMessage()
                        + "（" + (VOICES_FAIL_COOL / 60000) + " 分钟内不再试；"
                        + "要改成别的地址就在这条引擎的配置里改，或者删掉这条引擎）");
            }
            return new ArrayList<String>();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 空路径 = 响应本身就是数组；否则按 a.b.c 找进去。 */
    private static JSONArray arrayAt(String text, String path) throws Exception {
        String t = text == null ? "" : text.trim();
        if (path == null || path.trim().length() == 0) return new JSONArray(t);
        Object cur = new JSONObject(t);
        String[] parts = path.replace("$.", "").replace("$", "").split("\\.");
        for (String p : parts) {
            if (p.length() == 0) continue;
            if (!(cur instanceof JSONObject)) {
                throw new IllegalStateException("路径 " + path + " 走不通");
            }
            Object nx = ((JSONObject) cur).opt(p);
            if (nx == null) throw new IllegalStateException("JSON 里没有「" + p + "」");
            cur = nx;
        }
        if (cur instanceof JSONArray) return (JSONArray) cur;
        throw new IllegalStateException("路径 " + path + " 指到的不是数组");
    }

    // ───────────────────────── 合成 ─────────────────────────

    // ── 启用 / 音色反查 ──
    //
    // 引擎不再有"当前独占"这回事：导入/新建的引擎默认都是启用的，
    // 朗读时<b>看选了哪个音色</b>，反查是哪个引擎提供它（照 TTS Server 的模型：
    // 音源 + 音色决定用哪个引擎）。查不到才退回"当前引擎"。

    /** 停用的引擎：cfg 里 `off=1`。不写就是启用（导入的引擎默认启用）。 */
    public static boolean isEnabled(Item it) {
        return it == null || it.i("off", 0) == 0;
    }

    public static void setEnabled(Context c, Item it, boolean on) {
        if (it == null) return;
        it.put("off", on ? 0 : 1);
        put(c, it);
    }

    /** 音色表缓存（键带 src/url 的 hash，改了配置自动失效）——JS 引擎问一次很贵。 */
    private static final java.util.Map<String, List<String>> VOICE_CACHE =
            new java.util.concurrent.ConcurrentHashMap<String, List<String>>();

    private static String voiceKey(Item it) {
        return it.id + "|" + it.s("src", "").hashCode() + "|" + it.s("url", "").hashCode()
                + "|" + it.s("voices", "").hashCode();
    }

    /** 查（必要时算）一个引擎的音色表，带缓存。 */
    public static List<String> voicesCached(Context c, Item it) {
        String k = voiceKey(it);
        List<String> hit = VOICE_CACHE.get(k);
        if (hit != null) return hit;
        List<String> out;
        try {
            out = fetchVoices(c, it);
        } catch (Throwable t) {
            out = new java.util.ArrayList<String>();
        }
        VOICE_CACHE.put(k, out);
        return out;
    }

    public static void warmVoices(Context c) {
        new Thread(new Runnable() {
            public void run() {
                for (Item it : all(c)) {
                    if (!isEnabled(it)) continue;
                    try { voicesCached(c, it); } catch (Throwable ignored) { }
                }
            }
        }).start();
    }

    private static boolean sameVoice(String entry, String want) {
        if (entry == null || want == null) return false;
        String e = entry.trim(), w = want.trim();
        if (e.equalsIgnoreCase(w)) return true;
        int i = e.indexOf(SEP);
        String en = i > 0 ? e.substring(0, i).trim() : e;
        String ei = i > 0 ? e.substring(i + 1).trim() : e;
        int j = w.indexOf(SEP);
        String wn = j > 0 ? w.substring(0, j).trim() : w;
        String wi = j > 0 ? w.substring(j + 1).trim() : w;
        return en.equalsIgnoreCase(wn) || en.equalsIgnoreCase(wi)
                || ei.equalsIgnoreCase(wn) || ei.equalsIgnoreCase(wi);
    }

    /** 音色 → 引擎（内存版，列表/音色表一算出来就填）。 */
    private static final java.util.Map<String, String> VOICE_OWNER =
            new java.util.concurrent.ConcurrentHashMap<String, String>();

    /** 这个音色归哪个引擎（音色表算过就直接给；没算过就看持久表）。 */
    public static String ownerOfVoice(Context c, String voice) {
        if (voice == null || voice.length() == 0) return "";
        String owner = VOICE_OWNER.get(voice);
        if (owner == null || owner.length() == 0) owner = Prefs.voiceEngine(c, voice);
        return owner == null ? "" : owner;
    }

    /** 所有<b>启用中</b>引擎的音色，一起列出来；顺手记住每个音色归谁。 */
    public static List<String> fetchVoicesAll(Context c) {
        List<String> out = new java.util.ArrayList<String>();
        java.util.Set<String> seen = new java.util.LinkedHashSet<String>();
        for (Item it : all(c)) {
            if (!isEnabled(it)) continue;
            List<String> vs;
            try { vs = voicesCached(c, it); } catch (Throwable t) { continue; }
            for (String v : vs) {
                if (v == null || v.length() == 0) continue;
                VOICE_OWNER.put(v, it.id);
                if (seen.add(v)) out.add(v);
            }
        }
        return out;
    }

    /** 这个音色是谁提供的？先看"音色→引擎"那张表（照它：音色带着配置），再挨个引擎找，最后退回当前引擎。 */
    public static TtsEngine engineForVoice(Context c, String voice) {
        String want = voice == null ? "" : voice.trim();
        if (want.length() > 0) {
            String owner = VOICE_OWNER.get(want);
            if (owner == null || owner.length() == 0) owner = Prefs.voiceEngine(c, want);
            if (owner != null && owner.length() > 0) {
                Item it = get(c, owner);
                if (it != null && isEnabled(it)) {
                    VOICE_OWNER.put(want, owner);
                    return build(it);
                }
            }
        }
        if (want.length() > 0) {
            for (Item it : all(c)) {
                if (!isEnabled(it)) continue;
                List<String> vs;
                try { vs = voicesCached(c, it); } catch (Throwable t) { continue; }
                for (String v : vs) {
                    if (sameVoice(v, want)) return build(it);
                }
            }
        }
        return activeEngine(c);
    }

    /**
     * 这个音色是不是 JS 插件在念 —— 插件的"吊死"能用"还在不在吐字节"判断，
     * 别的引擎只能按总时限。
     */
    public static boolean isJsVoice(Context c, String voice) {
        try { return engineForVoice(c, voice) instanceof JsTtsEngine; }
        catch (Throwable t) { return false; }
    }

    /** 合成成功时记一笔（现在只用来日志里看看哪句慢）。 */
    public static void noteSynthOk(String voice, long ms) {
        if (voice == null || voice.length() == 0) return;
        if (ms <= 0 || ms > 300000L) return;
        SYNTH_MS.put(voice, ms);
    }

    /** 最近一次成功的合成耗时（按音色记）。 */
    private static final java.util.Map<String, Long> SYNTH_MS =
            new java.util.concurrent.ConcurrentHashMap<String, Long>();

    /** 插件宿主吊住了（合成硬超时了）：整个丢掉，下一句重新建，别再用那个抱死的运行时。 */
    public static void abandonPluginHosts() {
        try { JsTtsEngine.abandonHosts(); } catch (Throwable ignored) { }
    }

    /** 音色库按「来源引擎」分组 —— 选音色时左侧那一栏就是它（原神 / 火山…）。 */
    public static final class SourceGroup {
        public final String id;
        public final String label;
        public final List<String> voices = new ArrayList<String>();
        SourceGroup(String id, String label) { this.id = id; this.label = label; }
    }

    /**
     * 所有启用中的引擎，各自能报出哪些音色。
     * 顺手把「音色 → 引擎」记进 {@link #VOICE_OWNER}（选了音色就等于选了引擎）。
     */
    public static List<SourceGroup> voicesByEngine(Context c) {
        List<SourceGroup> out = new ArrayList<SourceGroup>();
        for (Item it : all(c)) {
            if (!isEnabled(it)) continue;
            List<String> vs;
            try { vs = voicesCached(c, it); } catch (Throwable t) { continue; }
            if (vs == null || vs.isEmpty()) continue;
            String label = it.label == null || it.label.trim().length() == 0 ? it.kind : it.label.trim();
            SourceGroup g = new SourceGroup(it.id, label);
            for (String v : vs) {
                if (v == null || v.trim().length() == 0) continue;
                VOICE_OWNER.put(v, it.id);
                g.voices.add(v);
            }
            if (!g.voices.isEmpty()) out.add(g);
        }
        return out;
    }

    /** 音色 → 插件给的图标地址（插件音色表里带 iconUrl 时记下来，界面上显示）。 */
    private static final java.util.Map<String, String> VOICE_ICON =
            new java.util.concurrent.ConcurrentHashMap<String, String>();

    public static void putVoiceIcon(String voice, String url) {
        if (voice == null || voice.length() == 0) return;
        if (url == null || url.trim().length() == 0) return;
        VOICE_ICON.put(voice, url.trim());
    }

    public static String voiceIcon(String voice) {
        return voice == null ? "" : VOICE_ICON.getOrDefault(voice, "");
    }

    /** 这个引擎叫什么（界面上按来源分组时显示）。 */
    public static String engineLabel(Context c, String id) {
        if (id == null || id.length() == 0) return "";
        Item it = get(c, id);
        if (it == null) return "";
        return it.label == null || it.label.trim().length() == 0 ? it.kind : it.label.trim();
    }

    /** 还有哪些角色在用这个音色 —— 一个音色可以绑多个角色，界面上要看得见。 */
    public static List<String> rolesUsingVoice(Context c, String voice) {
        List<String> out = new ArrayList<String>();
        if (voice == null || voice.trim().length() == 0) return out;
        String want = voice.trim();
        for (java.util.Map.Entry<String, String> e : Prefs.voiceTableMap(c).entrySet()) {
            String v = e.getValue();
            if (v == null) continue;
            if (v.trim().equalsIgnoreCase(want)) out.add(e.getKey());
        }
        return out;
    }

    /**
     * 边合成边给：JS 插件走真流式；别的引擎先下完再一块送（老路）。
     */
    public static void synthTo(Context c, String text, String voice, AudioSink sink) throws Exception {
        TtsEngine e = engineForVoice(c, voice);
        TtsEngine.Say say = new TtsEngine.Say();
        say.voice = voice == null ? "" : voice;
        applyAudioParams(c, say, voice);
        if (e instanceof JsTtsEngine) {
            ((JsTtsEngine) e).synthTo(c, text, say, sink);
            return;
        }
        byte[] all = e.synth(c, text, say);
        if (all != null && all.length > 0) sink.chunk(all, 0, all.length);
    }

    /** 播放链只调这一个：按音色挑引擎，再合成。 */
    public static byte[] synthNow(Context c, String text, String voice) throws Exception {
        TtsEngine e = engineForVoice(c, voice);
        TtsEngine.Say say = new TtsEngine.Say();
        say.voice = voice == null ? "" : voice;
        applyAudioParams(c, say, voice);
        return e.synth(c, text, say);
    }

    /**
     * 语速 / 音量 / 音高：先看这个音色所属引擎自己有没有单独设（插件页那三个滑杆），
     * 没设就跟随全局。照 TTS Server 的 model：{@code speed=0} 就是「跟随」。
     */
    public static void applyAudioParams(Context c, TtsEngine.Say say, String voice) {
        say.rate = num(Prefs.rate(c), 1f);
        say.pitch = num(Prefs.pitch(c), 1f);
        say.volume = Prefs.volume(c);
        try {
            String owner = ownerOfVoice(c, voice);
            Item it = owner == null ? null : get(c, owner);
            if (it == null) return;
            // 先看这个音色自己的三项（TTS Server 就是挂在音色上的）
            float[] pv = paramsForVoice(it, voice);
            if (pv[0] > 0f) say.rate = pv[0];
            if (pv[1] > 0f) say.volume = pv[1];
            if (pv[2] > 0f) say.pitch = pv[2];
            if (pv[0] > 0f || pv[1] > 0f || pv[2] > 0f) return;
            float sp = num(it.s("speed", ""), 0f);
            float vo = num(it.s("vol", ""), 0f);
            float pi = num(it.s("pitch_", ""), 0f);
            if (sp > 0f) say.rate = sp;
            if (vo > 0f) say.volume = vo;
            if (pi > 0f) say.pitch = pi;
        } catch (Throwable ignored) { }
    }

    private static float num(String s, float def) {
        try { return Float.parseFloat(s); } catch (Throwable t) { return def; }
    }

    public static String newId(String kind) {
        return kind + ":" + Long.toHexString(System.currentTimeMillis());
    }
}
