package com.mermergi.ttsrelay;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 引擎的导入 / 导出。
 *
 * 认两种格式，别人才好分享、你才好收：
 * <ul>
 *   <li><b>自家格式</b>：{@code [{"id","kind","label","cfg":{...}}]}</li>
 *   <li><b>大众格式</b>：阅读（Legado）的朗读引擎 JSON ——
 *       {@code {"name","url":"...?text={{speakText}}&voice={{speakVoice}}","header":"..."}}，
 *       单条或数组都行。TTS Server 也是认这个格式的，圈里互相发的就是它。</li>
 * </ul>
 * 占位符会统一成我们自己的写法，导进来就能跑。
 */
public final class EngineImport {

    /** 一段文本 → 一批引擎。认不出来就抛异常，别默默吞掉。 */
    public static List<EngineStore.Item> parse(String text) throws Exception {
        String t = text == null ? "" : text.trim();
        if (t.length() == 0) throw new IllegalArgumentException("内容为空");
        t = stripFence(t);

        // ① TTS Server 的插件包：.json，数组或单个对象，字段是
        //    name / pluginId / author / version / iconUrl / code / defVars / userVars
        //    —— 它那边就是 Json.decodeFromString<List<Plugin>> 直接入库。
        //    源码在 code 里，所以**不能**把整个 json 当 .js 存进去。
        if (t.startsWith("[") || t.startsWith("{")) {
            List<EngineStore.Item> plugins = new ArrayList<EngineStore.Item>();
            try {
                if (t.startsWith("[")) {
                    JSONArray arr = new JSONArray(t);
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.optJSONObject(i);
                        if (isPluginPackage(o)) plugins.add(pluginEngine(o));
                    }
                } else {
                    JSONObject o = new JSONObject(t);
                    if (isPluginPackage(o)) plugins.add(pluginEngine(o));
                }
            } catch (Throwable ignored) { }
            if (!plugins.isEmpty()) return plugins;
        }

        // ② 裸的 .js 插件源码
        if (looksLikeJsPlugin(t)) {
            List<EngineStore.Item> one = new ArrayList<EngineStore.Item>();
            // 插件就是插件：一律交给 TTS Server 那套运行时真跑（见 kt/ + PluginBridge）。
            one.add(jsEngine(t));
            return one;
        }

        List<JSONObject> objs = new ArrayList<JSONObject>();
        if (t.startsWith("[")) {
            JSONArray arr = new JSONArray(t);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) objs.add(o);
            }
        } else {
            JSONObject o = new JSONObject(t);
            JSONArray inner = o.optJSONArray("engines");
            if (inner != null) {
                for (int i = 0; i < inner.length(); i++) {
                    JSONObject x = inner.optJSONObject(i);
                    if (x != null) objs.add(x);
                }
            } else if (o.optJSONObject("cfg") != null) {
                objs.add(o);
            } else {
                objs.add(o);
            }
        }

        List<EngineStore.Item> out = new ArrayList<EngineStore.Item>();
        for (JSONObject o : objs) {
            EngineStore.Item it = convert(o);
            if (it != null) out.add(it);
        }
        if (out.isEmpty()) throw new IllegalArgumentException("没找到能认的引擎");
        return out;
    }

    /**
     * TTS Server 的 JS 插件 → 我们的一种引擎。
     *
     * 源码原样存进配置，跑的时候交给 TTS Server 自己的运行时（见 kt/app/PluginBridge.kt）—— 同一个引擎，
     * 所以插件里的 {@code getAudio} / {@code getVoices} 是什么样，这儿就是什么样，没有"搬不过来"。
     * 顺手把它声明的变量键、变量名、登录方式读出来填好。
     */
    private static EngineStore.Item jsEngine(String src) {
        EngineStore.Item it = new EngineStore.Item();
        it.kind = EngineStore.KIND_JS;
        it.id = EngineStore.newId(EngineStore.KIND_JS);
        it.put("src", src);
        it.put("lang", "zh-CN");
        it.put("headers", "");
        it.put("voices", "");
        it.put("voices_url", "");
        it.put("voices_path", "");
        it.put("voices_name", "name");
        it.put("voices_id", "id");
        it.put("login_url", "");
        it.put("binding", "session");
        it.put("ua", "mobile");

        // 元数据不是猜的：把插件跑起来读它自己的 PluginJS —— 和编辑框走的是同一条路（EngineStore.syncJsMeta）
        EngineStore.syncJsMeta(App.ctx(), it);
        EngineStore.inheritLogin(App.ctx(), it);      // 别处抓过的 Cookie 直接继承过来
        if (it.label == null || it.label.length() == 0) it.label = "JS 插件";
        return it;
    }

    /** 去掉 BOM 和 markdown 代码块围栏（从聊天里复制粘贴很常见）。 */
    private static String stripFence(String t) {
        if (t.startsWith("\uFEFF")) t = t.substring(1);
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            if (nl > 0) t = t.substring(nl + 1);
            int end = t.lastIndexOf("```");
            if (end > 0) t = t.substring(0, end);
        }
        return t.trim();
    }

    /** 这份 json 是不是 TTS Server 的插件包（有 code，且有 pluginId 或 name）。 */
    private static boolean isPluginPackage(JSONObject o) {
        if (o == null) return false;
        if (o.optString("code", "").trim().length() == 0) return false;
        return o.optString("pluginId", "").trim().length() > 0
                || o.optString("name", "").trim().length() > 0;
    }

    /**
     * 插件包 → 我们的一条 JS 引擎。和 TTS Server 入库同一份数据：
     * {@code name / pluginId / author / version / iconUrl / code / defVars / userVars}。
     *
     * 源码照原样存（跑的时候还是它自己那套运行时），名字用插件声明的不去猜；
     * defVars 里的 {@code value} 是作者给的默认值，{@code loginUrl / binding / ua}
     * 是它声明的登录方式 —— 都填进去，用户导进来就能用。
     */
    private static EngineStore.Item pluginEngine(JSONObject o) {
        EngineStore.Item it = jsEngine(o.optString("code", ""));
        String name = o.optString("name", "").trim();
        if (name.length() > 0) it.label = name;
        it.put("author", o.optString("author", ""));
        it.put("plugin_id", o.optString("pluginId", ""));
        it.put("icon", o.optString("iconUrl", ""));
        it.put("plugin_version", String.valueOf(o.optInt("version", 0)));

        JSONObject dv = o.optJSONObject("defVars");
        if (dv != null) {
            for (java.util.Iterator<String> k = dv.keys(); k.hasNext(); ) {
                String key = k.next();
                JSONObject d = dv.optJSONObject(key);
                if (d == null) continue;
                String val = d.optString("value", "");
                if (val.length() > 0 && it.s(key, "").trim().length() == 0) it.put(key, val);
                String lu = d.optString("loginUrl", "");
                if (lu.length() > 0 && it.s("login_url", "").trim().length() == 0) {
                    it.put("login_url", lu);
                }
                String bd = d.optString("binding", "");
                if (bd.length() > 0 && it.s("binding", "").equals("session")) it.put("binding", bd);
                String ua = d.optString("ua", "");
                if (ua.length() > 0) it.put("ua", ua);
            }
        }

        // 用户在 TTS Server 里已经填过的值也一起带过来
        JSONObject uv = o.optJSONObject("userVars");
        if (uv != null) {
            for (java.util.Iterator<String> k = uv.keys(); k.hasNext(); ) {
                String key = k.next();
                String val = uv.optString(key, "");
                if (val.trim().length() > 0) it.put(key, val);
            }
        }
        return it;
    }

    private static String value(String v) { return v == null ? "" : v; }

    /** 是不是 TTS Server 的 JS 插件（一堆 ttsrv.userVars / getAudioV2）。 */
    public static boolean looksLikeJsPlugin(String t) {
        return t.contains("ttsrv.userVars") || t.contains("getAudioV2")
                || (t.contains("EditorJS") && t.contains("PluginJS"));
    }
    /** 老配置里那种"原生引擎"的 kind 已经没有了：遇到就按 .js 插件重新导一遍。 */
    private static EngineStore.Item parseJsPlugin(String t) {
        return jsEngine(t);
    }

    private static String varsBlock(String t) {
        int at = t.indexOf("vars");
        if (at < 0) return "";
        int open = t.indexOf('{', at);
        if (open < 0) return "";
        int depth = 0;
        for (int i = open; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return t.substring(open, i + 1);
            }
        }
        return "";
    }

    /** 从 vars 里取某个键的默认值（value: "..."）；没有 value 就留空。 */
    private static void copyVar(EngineStore.Item it, String vars, String jsKey, String cfgKey) {
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(?:^|[\\s,{\"'])" + java.util.regex.Pattern.quote(jsKey)
                            + "\\s*:\\s*\\{([^}]*)\\}")
                    .matcher(vars);
            if (!m.find()) return;
            String val = rx(m.group(1), "value\\s*:\\s*\"([^\"]*)\"");
            if (val.length() > 0) it.put(cfgKey, val);
        } catch (Throwable ignored) { }
    }

    /** 从某个变量的 { } 块里读一个字符串属性（loginUrl / binding / ua 这些）。 */
    private static void copyVarProp(EngineStore.Item it, String vars, String jsKey,
                                    String prop, String cfgKey) {
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(?:^|[\\s,{\"'])" + java.util.regex.Pattern.quote(jsKey)
                            + "\\s*:\\s*\\{([^}]*)\\}")
                    .matcher(vars);
            if (!m.find()) return;
            String v = rx(m.group(1),
                    java.util.regex.Pattern.quote(prop) + "\\s*:\\s*['\"]([^'\"]*)['\"]");
            if (v.length() > 0) it.put(cfgKey, v);
        } catch (Throwable ignored) { }
    }

    private static String rx(String t, String pattern) {
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(pattern).matcher(t);
            if (m.find()) {
                String g = m.group(1);
                return g == null ? "" : g.trim();
            }
        } catch (Throwable ignored) { }
        return "";
    }

    private static EngineStore.Item convert(JSONObject o) {
        // ① 自家格式：有 kind + cfg
        if (o.optString("kind", "").length() > 0 && o.optJSONObject("cfg") != null) {
            EngineStore.Item it = new EngineStore.Item();
            it.kind = o.optString("kind", EngineStore.KIND_HTTP);
            it.label = o.optString("label", "导入的引擎");
            // 自家格式必须认原 id —— 否则"导出→再导入"会平白多出一份，而不是覆盖恢复
            it.id = o.optString("id", "");
            if (it.id.length() == 0) it.id = EngineStore.newId(it.kind);
            it.cfg = o.optJSONObject("cfg");
            if (it.cfg == null) it.cfg = new JSONObject();
            return it;
        }

        // ② 大众格式：Legado / TTS Server 的 httpTTS —— 认 url
        String url = firstNonEmpty(o.optString("url", ""), o.optString("apiUrl", ""));
        if (url.length() == 0) return null;

        EngineStore.Item it = new EngineStore.Item();
        it.kind = EngineStore.KIND_HTTP;
        String name = firstNonEmpty(o.optString("name", ""), o.optString("label", ""));
        it.label = name.length() > 0 ? name : "导入的朗读引擎";
        it.id = EngineStore.newId(EngineStore.KIND_HTTP);

        it.put("url", normalize(url));
        it.put("method", guessMethod(o, url));
        String header = firstNonEmpty(o.optString("header", ""), o.optString("headers", ""));
        it.put("headers", normalizeHeader(header));
        it.put("body", o.optString("body", ""));
        it.put("content_type", o.optString("contentType",
                "application/x-www-form-urlencoded; charset=utf-8"));
        int timeout = o.optInt("timeout", 60000);
        it.put("timeout", timeout > 0 ? timeout : 60000);
        it.put("json_key", o.optString("json_key", ""));

        // 音色目录：原格式带了就带上
        it.put("voices_url", o.optString("voices_url", ""));
        it.put("voices_path", o.optString("voices_path", ""));
        it.put("voices_name", o.optString("voices_name", "name"));
        it.put("voices_id", o.optString("voices_id", "name"));
        it.put("voices", o.optString("voices", ""));
        return it;
    }

    private static String guessMethod(JSONObject o, String url) {
        String m = o.optString("method", "");
        if (m.length() > 0) return m.toUpperCase(java.util.Locale.US);
        String body = o.optString("body", "");
        // 有正文模板 = POST；纯 url 拼参数 = GET（阅读那套全是 GET）
        if (body.trim().length() > 0) return "POST";
        if (o.optString("contentType", "").length() > 0) return "POST";
        return "GET";
    }

    /**
     * 把各种占位符统一成我们自己的写法。
     *
     * 坑：不能简单地 replace("{text}", "{{text}}") —— 上一步刚换出来的 {{text}}
     * 会被这一步再包一层，变成 {{{text}}}。所以单括号那条要用前后瞻，别咬到双括号里的。
     */
    public static String normalize(String url) {
        if (url == null) return "";
        String s = url;
        s = rep(s, "speakText", "text");
        s = rep(s, "speakVoice", "voice");
        s = rep(s, "speakSpeed", "rate");
        s = rep(s, "speakPitch", "pitch");
        s = rep(s, "speakVolume", "volume");
        s = rep(s, "voiceName", "voice");
        s = rep(s, "speed", "rate");
        s = rep(s, "text", "text");
        s = rep(s, "voice", "voice");
        return s;
    }

    /** 先收双花括号，再收"不是双花括号一部分"的单花括号。 */
    private static String rep(String s, String word, String target) {
        s = s.replaceAll("\\{\\{" + word + "\\}\\}", "\\{\\{" + target + "\\}\\}");
        s = s.replaceAll("(?<!\\{)\\{" + word + "\\}(?!\\})", "\\{\\{" + target + "\\}\\}");
        return s;
    }

    private static String normalizeHeader(String h) {
        if (h == null) return "";
        return h.replace("\\n", "\n").replace("\r\n", "\n").trim();
    }

    private static String firstNonEmpty(String a, String b) {
        if (a != null && a.trim().length() > 0) return a.trim();
        if (b != null && b.trim().length() > 0) return b.trim();
        return "";
    }

    // ───────────────────────── 导出 ─────────────────────────

    /** 自家格式：换个手机导回去能原样恢复。 */
    public static String exportOurs(List<EngineStore.Item> list) {
        JSONArray arr = new JSONArray();
        for (EngineStore.Item it : list) {
            JSONObject o = new JSONObject();
            try {
                o.put("id", it.id);
                o.put("kind", it.kind);
                o.put("label", it.label);
                o.put("cfg", it.cfg);
            } catch (Throwable ignored) { }
            arr.put(o);
        }
        return arr.toString();
    }

    /** 大众格式：阅读（Legado）的朗读引擎 JSON，导出的东西别人也能用。 */
    public static String exportLegado(List<EngineStore.Item> list) {
        JSONArray arr = new JSONArray();
        for (EngineStore.Item it : list) {
            if (!EngineStore.KIND_HTTP.equals(it.kind)) continue;
            JSONObject o = new JSONObject();
            try {
                String url = it.s("url", "");
                if ("POST".equalsIgnoreCase(it.s("method", "GET"))) {
                    String body = it.s("body", "");
                    if (body.trim().length() > 0) {
                        // 阅读的 httpTTS 只有 GET 拼参数 + header，正文模板表达不了
                        url = url + (body.contains("{{text}}") || body.contains("text") ? body : "");
                    }
                }
                o.put("name", it.label);
                o.put("url", url
                        .replace("{{text}}", "{{speakText}}")
                        .replace("{{voice}}", "{{speakVoice}}")
                        .replace("{{rate}}", "{{speakSpeed}}"));
                o.put("header", it.s("headers", ""));
                o.put("id", System.currentTimeMillis());
            } catch (Throwable ignored) { }
            arr.put(o);
        }
        return arr.toString();
    }
}
