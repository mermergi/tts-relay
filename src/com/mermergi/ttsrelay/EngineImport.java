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
        if (looksLikeJsPlugin(t)) {
            List<EngineStore.Item> one = new ArrayList<EngineStore.Item>();
            one.add(jsEngine(t));
            return one;
        }

        // 去掉可能的 BOM 和 markdown 代码块围栏（从聊天里复制粘贴很常见）
        if (t.startsWith("\uFEFF")) t = t.substring(1);
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            if (nl > 0) t = t.substring(nl + 1);
            int end = t.lastIndexOf("```");
            if (end > 0) t = t.substring(0, end);
            t = t.trim();
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
     * 源码原样存进配置，跑的时候交给 TTS Server 自己的运行时（见 kt/ + PluginHost）—— 同一个引擎，
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

        // 元数据不是猜的：把插件跑起来读它 PluginJS 上的 name/id/author/vars —— 照 TTS Server 的 eval()
        String name = "";
        try {
            com.mermergi.ttsrelay.js.PluginHost h =
                    new com.mermergi.ttsrelay.js.PluginHost(App.ctx(), it.id, src, null);
            h.init();
            com.mermergi.ttsrelay.js.PluginHost.Meta m = h.meta();
            name = m.getName();
            if (name.length() == 0) name = m.getId();
            JSONObject labels = new JSONObject();
            String loginKey = "";
            for (com.mermergi.ttsrelay.js.PluginHost.VarDef v : m.getVars()) {
                if (!it.cfg.has(v.getKey())) it.put(v.getKey(), "");
                String lab = v.getLabel();
                if (v.getHint().length() > 0) lab = lab + "（" + v.getHint() + "）";
                try { labels.put(v.getKey(), lab); } catch (Throwable ignored) { }
                if (loginKey.length() == 0 && v.getLoginUrl().length() > 0) {
                    loginKey = v.getKey();
                    it.put("login_key", loginKey);
                    it.put("login_url", v.getLoginUrl());
                    it.put("binding", v.getBinding().length() > 0 ? v.getBinding() : "session");
                    it.put("ua", v.getUa().length() > 0 ? v.getUa() : "mobile");
                }
            }
            if (labels.length() > 0) it.put("var_labels", labels.toString());
            if (m.getAuthor().length() > 0) it.put("author", m.getAuthor());
            if (m.getIconUrl().length() > 0) it.put("icon", m.getIconUrl());
            h.destroy();
        } catch (Throwable t) {
            // 源码先留着，跑不起来的理由由引擎的 problem() 说
        }
        it.label = name.length() > 0 ? name : "JS 插件";
        return it;
    }

    /** 是不是 TTS Server 的 JS 插件（一堆 ttsrv.userVars / getAudioV2）。 */
    public static boolean looksLikeJsPlugin(String t) {
        return t.contains("ttsrv.userVars") || t.contains("getAudioV2")
                || (t.contains("EditorJS") && t.contains("PluginJS"));
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
