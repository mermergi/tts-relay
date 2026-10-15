package com.mermergi.ttsrelay;

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

    public static final String ID_SYSTEM = "system";
    public static final String ID_HTTP = "ts-server";

    /** 显示名 与 取值 拼在一起的分隔符（音色名里不会出现）。 */
    public static final String SEP = "\u2016";

    /** 引擎类型的"约定键"：知道就自动填好，不知道也不拦着你自己加。 */
    public static final String[] KNOWN_KEYS = {
            "url", "method", "headers", "body", "content_type", "timeout", "json_key",
            "voices_url", "voices_path", "voices_name", "voices_id", "voices",
            "cred", "speaker", "format", "sample_rate",
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

        save(c, list);
        Prefs.setActiveEngine(c, ID_SYSTEM);
    }

    /** 老配置补上后来才有的约定键（比如 voices_url），别让升级把音色拉取弄丢。 */
    private static void migrate(Context c) {
        List<Item> list = rawList(c);
        boolean changed = false;
        for (java.util.Iterator<Item> li = list.iterator(); li.hasNext(); ) {
            Item it = li.next();
            if (!KIND_HTTP.equals(it.kind) && !KIND_SYSTEM.equals(it.kind)) {
                li.remove();          // 早先版本留下、现在已经不认的引擎类型，别在列表里挂着
                changed = true;
                continue;
            }
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
        return new SystemTtsEngine(it);
    }

    // ───────────────────────── 音色目录 ─────────────────────────

    /**
     * 音色从哪来：<b>三路合并</b>，和 TTS Server 一个思路。
     * <ol>
     *   <li>手工填的 voices</li>
     *   <li>voices_url 拉的 JSON 目录（路径 / 字段名都是配置项）</li>
     *   <li>引擎自己知道的（比如系统 TTS 这台机器上装了哪些语音）</li>
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

    private static List<String> fetchFromUrl(Item it, String url) throws Exception {
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
            String cred = it.s("cred", "").trim();
            if (cred.length() > 0) conn.setRequestProperty("Cookie", cred);
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
            return out;
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

    /** 播放链只调这一个：拿当前引擎合成。 */
    public static byte[] synthNow(Context c, String text, String voice) throws Exception {
        TtsEngine e = activeEngine(c);
        TtsEngine.Say say = new TtsEngine.Say();
        say.voice = voice == null ? "" : voice;
        say.rate = num(Prefs.rate(c), 1f);
        say.pitch = num(Prefs.pitch(c), 1f);
        say.volume = Prefs.volume(c);
        return e.synth(c, text, say);
    }

    private static float num(String s, float def) {
        try { return Float.parseFloat(s); } catch (Throwable t) { return def; }
    }

    public static String newId(String kind) {
        return kind + ":" + Long.toHexString(System.currentTimeMillis());
    }
}
