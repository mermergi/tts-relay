package com.mermergi.ttsrelay;

import android.content.Context;
import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * 万能的 HTTP 引擎：地址是模板，占位符一填就完事。
 * 只要对方给回音频字节，Edge/Azure/OpenAI/你自己的服务，全都能接。
 *
 * <pre>
 *   {{text}} {{voice}} {{rate}} {{pitch}} {{volume}} {{format}}
 * </pre>
 */
public final class HttpTtsEngine implements TtsEngine {

    private final EngineStore.Item it;

    public HttpTtsEngine(EngineStore.Item item) { this.it = item; }

    @Override public String kind() { return EngineStore.KIND_HTTP; }

    @Override public String label() {
        return it != null && it.label.length() > 0 ? it.label : "HTTP 引擎";
    }

    @Override public String problem(Context c) {
        String url = it == null ? "" : it.s("url", "");
        if (url.trim().length() == 0) return "还没填接口地址";
        if (!url.startsWith("http://") && !url.startsWith("https://")) return "地址要以 http(s):// 开头";
        return null;
    }

    // ───────────────────────── 合成 ─────────────────────────

    @Override
    public byte[] synth(Context c, String text, Say say) throws Exception {
        String problem = problem(c);
        if (problem != null) throw new IllegalStateException(problem);

        String method = it.s("method", "GET").toUpperCase(java.util.Locale.US);
        String urlStr = fill(it.s("url", ""), text, say, true);
        String body = it.s("body", "");
        boolean hasBody = body.trim().length() > 0 && "POST".equals(method);

        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(Math.min(15000, Math.max(2000, it.i("timeout", 30000))));
        conn.setReadTimeout(Math.max(3000, it.i("timeout", 60000)));
        conn.setRequestMethod(method);
        conn.setUseCaches(false);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "tts-relay/2.7");

        String headers = it.s("headers", "");
        for (String line : headers.split("\n")) {
            String s = line.trim();
            int i = s.indexOf(':');
            if (i <= 0) continue;
            try { conn.setRequestProperty(s.substring(0, i).trim(), s.substring(i + 1).trim()); }
            catch (Throwable ignored) { }
        }

        if (hasBody) {
            byte[] payload = fill(body, text, say, false).getBytes("UTF-8");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type",
                    it.s("content_type", "application/json; charset=utf-8"));
            conn.setFixedLengthStreamingMode(payload.length);
            OutputStream os = conn.getOutputStream();
            try { os.write(payload); } finally { try { os.close(); } catch (Throwable ignored) { } }
        }

        int code = conn.getResponseCode();
        if (code != 200) {
            InputStream es = conn.getErrorStream();
            byte[] err = es == null ? new byte[0] : readAll(es, 4096);
            conn.disconnect();
            throw new IllegalStateException("HTTP " + code + " " + preview(err));
        }
        InputStream in = conn.getInputStream();
        byte[] data = readAll(in, 32 * 1024 * 1024);
        conn.disconnect();

        String jsonKey = it.s("json_key", "");
        if (jsonKey.length() > 0) {
            data = unwrapJson(data, jsonKey);
        }
        if (!TtsClient.looksLikeAudio(data)) {
            throw new IllegalStateException("返回的不是音频（" + data.length + " 字节）：" + preview(data));
        }
        return data;
    }

    /** JSON 里包着音频：要么是 base64，要么是个下载地址。 */
    private byte[] unwrapJson(byte[] raw, String key) throws Exception {
        String s = new String(raw, "UTF-8");
        JSONObject o = new JSONObject(s);
        String v = o.optString(key, "");
        if (v.length() == 0) throw new IllegalStateException("JSON 里没有字段「" + key + "」");
        if (v.startsWith("http://") || v.startsWith("https://")) {
            HttpURLConnection c2 = (HttpURLConnection) new URL(v).openConnection();
            c2.setConnectTimeout(10000);
            c2.setReadTimeout(60000);
            c2.setRequestProperty("User-Agent", "tts-relay/2.7");
            try {
                if (c2.getResponseCode() != 200) throw new IllegalStateException("下载音频 HTTP " + c2.getResponseCode());
                return readAll(c2.getInputStream(), 32 * 1024 * 1024);
            } finally {
                c2.disconnect();
            }
        }
        try {
            return Base64.decode(v, Base64.DEFAULT);
        } catch (Throwable t) {
            throw new IllegalStateException("字段「" + key + "」既不是地址也不是 base64");
        }
    }

    // ───────────────────────── 模板 ─────────────────────────

    private String fill(String tpl, String text, Say say, boolean encode) {
        if (tpl == null) return "";
        Say s = say == null ? new Say() : say;
        String out = EngineImport.normalize(tpl);   // 兼容阅读等别的写法
        out = out.replace("{{text}}", encode ? enc(text) : esc(text));
        out = out.replace("{{voice}}", encode ? enc(s.voice) : esc(s.voice));
        out = out.replace("{{rate}}", enc(fmt(s.rate)));
        out = out.replace("{{pitch}}", enc(fmt(s.pitch)));
        out = out.replace("{{volume}}", enc(fmt(s.volume)));
        out = out.replace("{{tone}}", encode ? enc(s.tone) : esc(s.tone));
        out = out.replace("{{format}}", "mp3");
        out = fillNum(out, s);
        out = fillCfg(out, encode);
        return out;
    }

    /** 数字占位符的缩放写法：{{rate*50}}、{{rate*100-100}}、{{pitch*50-50}}。
     *  TTS Server 插件那边的 rate/pitch/volume 是 0-100（50 为正常），
     *  我们是 1.0 为正常，导入插件时靠这个换算。 */
    private static final java.util.regex.Pattern NUM_PH = java.util.regex.Pattern.compile(
            "\\{\\{(rate|pitch|volume)(?:\\*(-?[0-9.]+))?(?:([+-])([0-9.]+))?\\}\\}");

    private static String fillNum(String out, Say s) {
        java.util.regex.Matcher m = NUM_PH.matcher(out);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String name = m.group(1);
            float v = "rate".equals(name) ? s.rate : ("pitch".equals(name) ? s.pitch : s.volume);
            if (m.group(2) != null) {
                try { v = v * Float.parseFloat(m.group(2)); } catch (Throwable ignored) { }
            }
            if (m.group(3) != null) {
                try {
                    float b = Float.parseFloat(m.group(4));
                    v = "-".equals(m.group(3)) ? v - b : v + b;
                } catch (Throwable ignored) { }
            }
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(trimNum(v)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String trimNum(float f) {
        if (Math.abs(f - Math.round(f)) < 0.005f) return String.valueOf(Math.round(f));
        return String.format(java.util.Locale.US, "%.2f", f);
    }

    /** 剩下的 {{键}} 从引擎配置里取 —— 插件导进来的变量（server、region…）靠这个活起来。 */
    private static final java.util.regex.Pattern CFG_PH = java.util.regex.Pattern.compile(
            "\\{\\{([A-Za-z_][A-Za-z0-9_.-]*)\\}\\}");

    private String fillCfg(String out, boolean encode) {
        if (it == null) return out;
        java.util.regex.Matcher m = CFG_PH.matcher(out);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String k = m.group(1);
            String v = it.s(k, "");
            if (v.length() == 0) continue;              // 没这个键就原样留着，别把 URL 掏空
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(encode ? enc(v) : esc(v)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String fmt(float f) {
        return String.format(java.util.Locale.US, "%.2f", f);
    }

    private static String enc(String s) {
        try { return URLEncoder.encode(s == null ? "" : s, "UTF-8"); }
        catch (Exception e) { return ""; }
    }

    /** 塞进 JSON/正文时把引号换行这些挡一下。 */
    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    // ───────────────────────── 音色 ─────────────────────────

    @Override
    public List<String> voices(Context c) {
        List<String> out = new ArrayList<String>();
        String raw = it == null ? "" : it.s("voices", "");
        for (String line : raw.split("\n")) {
            String s = line.trim();
            if (s.length() > 0 && !out.contains(s)) out.add(s);
        }
        return out;
    }

    // ───────────────────────── 杂 ─────────────────────────

    private static byte[] readAll(InputStream in, int cap) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(64 * 1024);
        byte[] buf = new byte[16384];
        int n;
        long total = 0;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > cap) break;
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    private static String preview(byte[] b) {
        return TtsClient.preview(b);
    }
}
