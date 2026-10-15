package com.mermergi.ttsrelay;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 豆包 VoiceGenie —— 原生跑在我们进程里，不再经过 TTS Server。
 *
 * 协议是从插件里原样搬过来的（那条插件当初就是逆出来的）：
 * <pre>
 *   wss://frontier-audio-web-ws.doubao.com/api/v2/sami/voicegenie
 *   帧 = protobuf {2:appkey, 3:"VoiceGenie", 5:方法名, 6:JSON, 8:task_id}
 *   ① StartTask            -> TaskStarted(task_id)
 *   ② StartSession(config) -> SessionStarted
 *   ③ BidirectionalTTS({text}) + EndTTS
 *   ④ TTSResponse(音频, field 8) … TTSEnded
 *   ⑤ FinishSession        -> SessionFinished   ← 这句不能省，否则下一句 40100003
 * </pre>
 * 音频格式默认 mp3：wav 体积大 9 倍，ogg_opus 的采样率会骗人（Opus 解码恒定 48k）。
 */
public final class DoubaoEngine implements TtsEngine {

    private static final String HOST = "wss://frontier-audio-web-ws.doubao.com/api/v2/sami/voicegenie";
    private static final String NS = "VoiceGenie";
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36";

    private static final Object LOCK = new Object();
    private static MiniWs ws;
    private static String taskId;
    private static String wsAppkey = "";

    /** 音色名和 style_id 用一个不会出现在名字里的分隔符拼起来 —— 显示给人看，取值给服务端。 */
    private static final String SEP = EngineStore.SEP;
    private static List<String> voiceCache;

    private final EngineStore.Item it;

    public DoubaoEngine(EngineStore.Item item) { this.it = item; }

    @Override public String kind() { return EngineStore.KIND_DOUBAO; }

    @Override public String label() {
        return it != null && it.label.length() > 0 ? it.label : "豆包 VoiceGenie";
    }

    private String cookie() { return it == null ? "" : it.s("cookie", "").trim(); }
    private String appkey() {
        String a = it == null ? "" : it.s("appkey", "").trim();
        return a.length() == 0 ? "GOqQpfo1fO7slHv8" : a;
    }
    private String fmt() {
        String f = it == null ? "mp3" : it.s("format", "mp3");
        if (!"mp3".equals(f) && !"wav".equals(f) && !"ogg_opus".equals(f)) f = "mp3";
        return f;
    }
    private int sampleRate() {
        int r = it == null ? 24000 : it.i("sample_rate", 24000);
        return r > 0 ? r : 24000;
    }

    @Override public String problem(Context c) {
        if (cookie().length() == 0) return "还没填 Cookie（豆包网页版登录后的那个）";
        return null;
    }

    // ───────────────────────── 合成 ─────────────────────────

    @Override
    public byte[] synth(Context c, String text, Say say) throws Exception {
        String p = problem(c);
        if (p != null) throw new IllegalStateException(p);
        if (text == null || text.trim().length() == 0) throw new IllegalArgumentException("空文本");

        synchronized (LOCK) {
            Exception last = null;
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    if (isSamantha()) return synthSamantha(text, say);
                    return synthOnce(text, say);
                } catch (Exception e) {
                    last = e;
                    drop();
                    LogBus.add("豆包：第 " + (attempt + 1) + " 次失败，重连再试（" + e.getMessage() + "）");
                }
            }
            throw last == null ? new IllegalStateException("豆包合成失败") : last;
        }
    }

    private byte[] synthOnce(String text, Say say) throws Exception {
        ensureTask();
        sendSession(say);
        waitEvent("SessionStarted", 12000);

        LinkedHashMap<String, String> f = new LinkedHashMap<String, String>();
        send("BidirectionalTTS", new JSONObject().put("text", text).toString());
        send("EndTTS", null);
        send("Ping", "{}");

        ByteArrayOutputStream audio = new ByteArrayOutputStream(512 * 1024);
        long deadline = System.currentTimeMillis() + 120000;
        while (System.currentTimeMillis() < deadline) {
            byte[] frame = ws.readBinary();
            if (frame == null) throw new java.io.IOException("连接断了");
            if (frame.length == 0) continue;
            LinkedHashMap<Integer, Proto.F> fs = Proto.parse(frame);
            String ev = fs.containsKey(4) ? fs.get(4).str : "";
            long code = fs.containsKey(5) ? fs.get(5).num : 0;
            String msg = fs.containsKey(6) ? fs.get(6).str : "";

            Proto.F chunk = fs.get(8);
            if (chunk != null && chunk.bin != null && chunk.bin.length > 0) {
                // 音频恒在 field 8；task_id 是"发出去"的 8，收回来的 8 就是声音
                audio.write(chunk.bin, 0, chunk.bin.length);
            }

            if ("TTSEnded".equals(ev)) {
                try { send("FinishSession", null); } catch (Throwable ignored) { }
                byte[] data = audio.toByteArray();
                if (data.length == 0) {
                    throw new java.io.IOException("TTSEnded 但没收到音频"
                            + (msg.length() > 0 ? "：" + msg : " code=" + code));
                }
                return data;
            }
            if ("TaskFailed".equals(ev) || "SessionFailed".equals(ev)
                    || "TTSSynthesisFailed".equals(ev)) {
                if (code == 40100003) {
                    // 会话没放掉：重连重来一次
                    drop();
                    throw new java.io.IOException("会话没释放（40100003），重连重试");
                }
                throw new java.io.IOException(ev + " code=" + code
                        + (msg.length() > 0 ? "：" + msg : ""));
            }
            // Pong / TTSResponse / SessionStarted / TaskStarted … 接着收
        }
        throw new java.io.IOException("等音频超时");
    }

    private void ensureTask() throws Exception {
        if (ws != null && !ws.isClosed() && taskId != null) return;
        drop();
        LinkedHashMap<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("Cookie", cookie());
        headers.put("User-Agent", UA);
        headers.put("Origin", "https://www.doubao.com");
        String url = HOST + "?api_app_key=" + java.net.URLEncoder.encode(appkey(), "UTF-8")
                + "&namespace=" + NS;
        ws = MiniWs.connect(url, headers, 25000);
        wsAppkey = appkey();
        send("StartTask", "{}");
        waitEvent("TaskStarted", 12000);
    }

    /** 等到某个事件（顺带把音频丢掉）。 */
    private void waitEvent(String want, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            byte[] frame = ws.readBinary();
            if (frame == null) throw new java.io.IOException("连接断了");
            if (frame.length == 0) continue;
            LinkedHashMap<Integer, Proto.F> fs = Proto.parse(frame);
            String ev = fs.containsKey(4) ? fs.get(4).str : "";
            long code = fs.containsKey(5) ? fs.get(5).num : 0;
            String msg = fs.containsKey(6) ? fs.get(6).str : "";
            if ("TaskStarted".equals(ev)) {
                Proto.F id = fs.get(1);
                if (id != null && id.str != null && id.str.length() > 0) taskId = id.str;
                if (want.equals(ev)) return;
                continue;
            }
            if (want.equals(ev)) return;
            if ("TaskFailed".equals(ev) || "SessionFailed".equals(ev)
                    || "TTSSynthesisFailed".equals(ev)) {
                throw new java.io.IOException(ev + " code=" + code
                        + (msg.length() > 0 ? "：" + msg : ""));
            }
        }
        throw new java.io.IOException("等 " + want + " 超时");
    }

    private void send(String method, String payload) throws Exception {
        if (ws == null || ws.isClosed()) throw new java.io.IOException("连接不在");
        byte[] frame = Proto.frame(wsAppkey, NS, method, payload, taskId);
        ws.sendBinary(frame);
    }

    private void sendSession(Say say) throws Exception {
        float rate = say == null ? 1f : say.rate;
        float pitch = say == null ? 1f : say.pitch;
        if (rate < 0.5f) rate = 0.5f;
        if (rate > 2f) rate = 2f;
        float pitchOff = pitch - 1f;                 // 我们的 1.0 = 不偏
        if (pitchOff < -1f) pitchOff = -1f;
        if (pitchOff > 1f) pitchOff = 1f;

        String speaker = say == null ? "" : say.voice;
        speaker = resolveSpeaker(speaker);
        if (speaker.length() == 0) speaker = it == null ? "" : it.s("speaker", "");
        if (speaker.length() == 0) {
            if (voiceCache == null) voiceCache = voices(null);
            if (!voiceCache.isEmpty()) {
                String v = voiceCache.get(0);
                speaker = v.contains(SEP) ? v.substring(v.lastIndexOf(SEP) + 1) : v;
            }
        }
        if (speaker.length() == 0) throw new IllegalStateException("没指定音色");

        JSONObject cfg = new JSONObject();
        cfg.put("request_type", 5);                   // 5 = TEXT_TTS
        JSONObject tts = new JSONObject();
        tts.put("speaker", speaker);
        JSONObject ac = new JSONObject();
        ac.put("bit_rate", 32000);
        ac.put("format", fmt());
        ac.put("sample_rate", sampleRate());
        tts.put("audio_config", ac);
        JSONObject pp = new JSONObject();
        pp.put("pitch", Math.round(pitchOff * 100) / 100.0);
        pp.put("speech_rate", Math.round(rate * 100) / 100.0);
        JSONObject extra = new JSONObject();
        extra.put("post_process", pp);
        tts.put("extra", extra);
        // 语气指令必须挂在 tts 这一层，挂 tts.extra 下服务端直接忽略
        if (say != null && say.tone != null && say.tone.trim().length() > 0) {
            tts.put("context_texts", new JSONArray().put(say.tone.trim()));
        }
        cfg.put("tts", tts);
        send("StartSession", cfg.toString());
    }

    /**
     * 把音色名换成 style_id。
     *
     * 两种都认：新的 {@code 名字 标签‖style_id}，和旧的 TTS Server 那套
     * {@code 名字 标签_时间戳} —— 后者按显示名反查（两边的显示名本来就同源，
     * 都来自豆包的 name + tag_list），这样换了引擎也不用把音色表重配一遍。
     */
    private String resolveSpeaker(String v) {
        if (v == null || v.trim().length() == 0) return "";
        String s = v.trim();
        int i = s.indexOf(SEP);
        if (i >= 0) return s.substring(i + 1);
        if (!s.startsWith("zh_") && !s.startsWith("en_") && s.contains("_")) {
            // 旧格式，按显示名反查
            String key = s.replaceAll("_\\d+$", "").trim();
            if (voiceCache == null) voiceCache = voices(null);
            for (String item : voiceCache) {
                int k = item.indexOf(SEP);
                if (k < 0) continue;
                String disp = item.substring(0, k).replaceAll("_\\d+$", "").trim();
                if (disp.equals(key)) return item.substring(k + 1);
            }
            for (String item : voiceCache) {
                int k = item.indexOf(SEP);
                if (k < 0) continue;
                String disp = item.substring(0, k).replaceAll("_\\d+$", "").trim();
                if (disp.startsWith(key) || key.startsWith(disp)) return item.substring(k + 1);
            }
        }
        return s;
    }

    /** 走哪条链路：voicegenie（新，protobuf）/ samantha（老，纯 JSON）。 */
    private boolean isSamantha() {
        String l = it == null ? "voicegenie" : it.s("link", "voicegenie");
        l = l.trim().toLowerCase(java.util.Locale.US);
        return "samantha".equals(l) || "legacy".equals(l) || "old".equals(l) || "老".equals(l);
    }

    private String prefix() {
        return it == null ? "" : it.s("prefix", "");
    }

    /** 老链路（ws-samantha）：连接串上直接带 speaker/语速/音调，帧是纯 JSON，音频走二进制帧。 */
    private static final String SAMANTHA_HOST = "wss://ws-samantha.doubao.com/samantha/audio/tts";

    private byte[] synthSamantha(String text, Say say) throws Exception {
        String speaker = resolveSpeaker(say == null ? "" : say.voice);
        if (speaker.length() == 0) speaker = it == null ? "" : it.s("speaker", "");
        if (speaker.length() == 0) {
            if (voiceCache == null) voiceCache = voices(null);
            if (!voiceCache.isEmpty()) {
                String v = voiceCache.get(0);
                speaker = v.contains(SEP) ? v.substring(v.lastIndexOf(SEP) + 1) : v;
            }
        }
        if (speaker.length() == 0) throw new IllegalStateException("没指定音色");

        // 这条链路是 -100..100 的刻度（插件里：request.rate*2-100）
        float rate = say == null ? 1f : say.rate;
        float pitch = say == null ? 1f : say.pitch;
        int sr = Math.round((rate - 1f) * 100f);
        sr = Math.max(-100, Math.min(100, sr));
        int pt = Math.round((pitch - 1f) * 50f);
        pt = Math.max(-50, Math.min(50, pt));

        // 这条链路只认 aac：给别的格式它会退回普通 HTTP 200，握手就不是 101 了
        String f = it == null ? "aac" : it.s("samantha_format", "aac");
        if (!"aac".equals(f) && !"mp3".equals(f)) f = "aac";

        StringBuilder q = new StringBuilder();
        q.append("?format=").append(f)
         .append("&speaker=").append(enc(speaker))
         .append("&speech_rate=").append(sr)
         .append("&pitch=").append(pt)
         .append("&mode=0&language=zh&browser_language=zh-CN&device_platform=web")
         .append("&aid=586861&real_aid=586861&pkg_type=release_version")
         .append("&device_id=").append(deviceId())
         .append("&tea_uuid=").append(deviceId())
         .append("&web_id=").append(deviceId())
         .append("&is_new_user=0&region=CN&sys_region=CN&use-olympus-account=1&samantha_web=1")
         .append("&version=1.20.1&version_code=20800&pc_version=1.20.1");

        LinkedHashMap<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("Cookie", cookie());
        headers.put("Origin", "chrome-extension://capohkkfagimodmlpnahjoijgoocdjhd");
        headers.put("User-Agent", UA);

        MiniWs w = MiniWs.connect(SAMANTHA_HOST + q, headers, 25000);
        try {
            String payload = prefix() + text;
            logger("发文本 " + payload.length() + " 字");
            w.sendText("{\"event\":\"text\",\"podcast_extra\":{\"role\":\"\"},\"text\":\""
                    + jsonEsc(payload) + "\"}");
            w.sendText("{\"event\":\"finish\"}");

            ByteArrayOutputStream audio = new ByteArrayOutputStream(512 * 1024);
            long deadline = System.currentTimeMillis() + 120000;
            while (System.currentTimeMillis() < deadline) {
                byte[] fr;
                try {
                    fr = w.readBinary();
                } catch (java.net.SocketTimeoutException te) {
                    break;
                }
                if (fr == null) break;                  // 对端关了
                if (fr.length == 0) continue;
                if (w.lastOpcode == 0x1) {              // 文本帧：服务端的回话，不是音频
                    logger("<- " + new String(fr, "UTF-8").trim());
                    continue;
                }
                audio.write(fr, 0, fr.length);
            }
            byte[] data = audio.toByteArray();
            if (data.length == 0) throw new java.io.IOException("老链路没收到音频");
            return data;
        } finally {
            w.closeQuietly();
        }
    }

    private static String deviceId() {
        StringBuilder sb = new StringBuilder();
        java.util.Random r = new java.util.Random();
        for (int i = 0; i < 2; i++) sb.append(100000000L + (long) (r.nextDouble() * 900000000L));
        return sb.toString();
    }

    private static String enc(String s) {
        try { return java.net.URLEncoder.encode(s == null ? "" : s, "UTF-8"); }
        catch (Throwable t) { return ""; }
    }

    private static String jsonEsc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private static void logger(String m) {
        try { LogBus.add("豆包(老链路)：" + m); } catch (Throwable ignored) { }
    }

    private static void drop() {
        MiniWs w = ws;
        ws = null;
        taskId = null;
        if (w != null) w.closeQuietly();
    }

    /** 进程退出前收个尾。 */
    public static void shutdown() {
        synchronized (LOCK) { drop(); }
    }

    // ───────────────────────── 音色 ─────────────────────────

    @Override
    public List<String> voices(Context c) {
        if (voiceCache != null) return voiceCache;
        List<String> out = new ArrayList<String>();
        String ck = cookie();
        if (ck.length() == 0) return out;
        try {
            String did = Long.toHexString(System.currentTimeMillis()) + Long.toHexString(new java.util.Random().nextLong());
            String url = "https://www.doubao.com/alice/user_voice/recommend?language=zh"
                    + "&browser_language=zh-CN&mode=0&device_platform=web&aid=497858&real_aid=497858"
                    + "&pkg_type=release_version&device_id=" + did + "&tea_uuid=" + did
                    + "&web_id=" + did + "&is_new_user=0&region=CN&sys_region=CN"
                    + "&use-olympus-account=1&samantha_web=1&version_code=20800&pc_version=3.9.0";
            String[][] tabs = {{"1", ""}, {"10", "female"}, {"10", "male"},
                    {"10", "characters"}, {"10", "accent"}};
            for (String[] t : tabs) {
                String body = "{\"page_index\":1,\"page_size\":200,\"recommend_type\":" + t[0]
                        + ",\"tab_key\": \"" + t[1] + "\"}";
                String txt = postJson(url, body, ck);
                if (txt == null) continue;
                JSONObject o = new JSONObject(txt);
                if (o.optInt("code", -1) != 0) continue;
                JSONArray arr = o.optJSONObject("data") == null ? null
                        : o.optJSONObject("data").optJSONArray("ugc_voice_list");
                if (arr == null) continue;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject v = arr.optJSONObject(i);
                    if (v == null) continue;
                    String name = v.optString("name", "");
                    String sid = v.optString("style_id", "");
                    if (sid.length() == 0) continue;
                    // 标签在 tag_list[].tag_value 里 —— 插件就是把它 join("|") 拼到名字后面的：
                    //   mm[v.style_id] = { name: v.name + " " + tag }
                    // 丢了这一步，音色库里的「女|青年|温柔」就全没了，也就没法按男女分组。
                    StringBuilder tags = new StringBuilder();
                    JSONArray tl = v.optJSONArray("tag_list");
                    if (tl != null) {
                        for (int k = 0; k < tl.length(); k++) {
                            JSONObject tg = tl.optJSONObject(k);
                            String tv = tg == null ? "" : tg.optString("tag_value", "");
                            if (tv.length() == 0) continue;
                            if (tags.length() > 0) tags.append('|');
                            tags.append(tv);
                        }
                    }
                    String disp = (name.length() == 0 ? sid : name)
                            + (tags.length() > 0 ? " " + tags : "");
                    String item = disp + SEP + sid;
                    if (!out.contains(item)) out.add(item);
                }
            }
        } catch (Throwable t) {
            LogBus.error("豆包音色列表拉取失败：" + t.getMessage());
        }
        if (!out.isEmpty()) voiceCache = out;
        return out;
    }

    /** 清缓存（改了 cookie 之后）。 */
    public static void clearVoiceCache() { voiceCache = null; }

    private static String postJson(String url, String body, String cookie) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Cookie", cookie);
            conn.setRequestProperty("User-Agent", UA);
            byte[] b = body.getBytes("UTF-8");
            conn.setFixedLengthStreamingMode(b.length);
            conn.getOutputStream().write(b);
            conn.getOutputStream().close();
            if (conn.getResponseCode() != 200) return null;
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream(64 * 1024);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
