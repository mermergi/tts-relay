package com.mermergi.ttsrelay;

import android.content.Context;
import android.content.SharedPreferences;

/** 全部可调项都落在这里，UI 直接读写。 */
public final class Prefs {
    public static final String UPSTREAM_DEF = "https://api.deepseek.com";
    public static final String TTS_BASE_DEF = "http://127.0.0.1:1221";
    public static final String ENGINE_DEF = "com.github.jing332.tts_server_android";
    /** 默认不指定音色：留空就交给 TTS Server 用它自己的默认音色，本 App 不预设任何具体音色。 */
    public static final String VOICE_DEF = "";
    public static final int PORT_DEF = 8788;
    public static final int MAXCHUNK_DEF = 90;

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("relay", Context.MODE_PRIVATE);
    }

    public static String upstream(Context c) { return sp(c).getString("upstream", UPSTREAM_DEF); }
    public static void setUpstream(Context c, String v) { sp(c).edit().putString("upstream", v).apply(); }

    public static int port(Context c) { return sp(c).getInt("port", PORT_DEF); }
    public static void setPort(Context c, int v) { sp(c).edit().putInt("port", v).apply(); }

    public static String ttsBase(Context c) { return sp(c).getString("tts_base", TTS_BASE_DEF); }
    public static void setTtsBase(Context c, String v) { sp(c).edit().putString("tts_base", v).apply(); }

    public static String engine(Context c) { return sp(c).getString("engine", ENGINE_DEF); }
    public static void setEngine(Context c, String v) { sp(c).edit().putString("engine", v).apply(); }

    public static String voice(Context c) { return sp(c).getString("voice", VOICE_DEF); }
    public static void setVoice(Context c, String v) { sp(c).edit().putString("voice", v).apply(); }

    public static String rate(Context c) { return sp(c).getString("rate", "1.0"); }
    public static void setRate(Context c, String v) { sp(c).edit().putString("rate", v).apply(); }

    public static String pitch(Context c) { return sp(c).getString("pitch", "1.0"); }
    public static void setPitch(Context c, String v) { sp(c).edit().putString("pitch", v).apply(); }

    /** 试听 / 换音色时念的那句话，可以自己改。 */
    public static final String TRY_DEF = "哥哥，这是小洁试音，能听见吗？";

    public static String tryText(Context c) {
        String v = sp(c).getString("try_text", TRY_DEF);
        return (v == null || v.trim().length() == 0) ? TRY_DEF : v;
    }

    public static void setTryText(Context c, String v) { sp(c).edit().putString("try_text", v).apply(); }

    public static int maxChunk(Context c) { return sp(c).getInt("maxchunk", MAXCHUNK_DEF); }
    public static void setMaxChunk(Context c, int v) { sp(c).edit().putInt("maxchunk", v).apply(); }

    /** 断句方案（Splitter.MODE_*）。 */
    public static int splitMode(Context c) {
        return Splitter.clampMode(sp(c).getInt("split_mode", Splitter.MODE_NATURAL));
    }
    public static void setSplitMode(Context c, int v) { sp(c).edit().putInt("split_mode", v).apply(); }

    public static boolean muted(Context c) { return sp(c).getBoolean("muted", false); }
    public static void setMuted(Context c, boolean v) { sp(c).edit().putBoolean("muted", v).apply(); }

    public static boolean skipSmall(Context c) { return sp(c).getBoolean("skip_small", true); }
    public static void setSkipSmall(Context c, boolean v) { sp(c).edit().putBoolean("skip_small", v).apply(); }

    /** TTS Server 崩了之后，替它把「转发器」拉起来 —— 它自己不会回来。 */
    public static boolean autoRevive(Context c) { return sp(c).getBoolean("auto_revive", true); }
    public static void setAutoRevive(Context c, boolean v) { sp(c).edit().putBoolean("auto_revive", v).apply(); }

    /** 允许局域网访问（给电脑上的酒馆/其它客户端用）。默认关：开了等于把你的 key 转发给整个局域网。 */
    public static boolean lanAllowed(Context c) { return sp(c).getBoolean("lan", false); }
    public static void setLanAllowed(Context c, boolean v) { sp(c).edit().putBoolean("lan", v).apply(); }

    /** 上次服务是否在跑（用户以为开着，重启后自动拉起来）。 */
    public static boolean wanted(Context c) { return sp(c).getBoolean("wanted", false); }
    public static void setWanted(Context c, boolean v) { sp(c).edit().putBoolean("wanted", v).apply(); }

    // ───────────────────────── 界面 ─────────────────────────

    /** 主题：0 跟随系统 / 1 深色 / 2 浅色。 */
    public static int themeMode(Context c) { return sp(c).getInt("theme", 0); }
    public static void setThemeMode(Context c, int v) { sp(c).edit().putInt("theme", v).apply(); }

    /** 播放音量 0~1，和系统媒体音量是乘法关系。 */
    public static float volume(Context c) { return sp(c).getFloat("volume", 1f); }
    public static void setVolume(Context c, float v) { sp(c).edit().putFloat("volume", v).apply(); }

    /** 开机自启（配合"上次是开着的"）。 */
    public static boolean autoStart(Context c) { return sp(c).getBoolean("auto_start", false); }
    public static void setAutoStart(Context c, boolean v) { sp(c).edit().putBoolean("auto_start", v).apply(); }

    // ───────────────────────── 跳过规则 ─────────────────────────

    /**
     * 「这是后台小活、别念」的关键词。只在 system/developer 提示词里找，
     * 而且只有客户端没设输出额度时才算数 —— 否则角色卡里一个"标题"就能把整条哑掉。
     */
    public static final String SKIP_KEYWORDS_DEF = "标题,title,摘要,summar,命名";

    public static String skipKeywords(Context c) {
        return sp(c).getString("skip_keywords", SKIP_KEYWORDS_DEF);
    }
    public static void setSkipKeywords(Context c, String v) {
        sp(c).edit().putString("skip_keywords", v == null ? "" : v).apply();
    }

    /** 输出额度小于这个值 = 客户端在后台起标题/写摘要。 */
    public static final int SMALL_TOKEN_DEF = 200;

    public static int smallTokenLimit(Context c) {
        int v = sp(c).getInt("small_tokens", SMALL_TOKEN_DEF);
        return v < 1 ? SMALL_TOKEN_DEF : v;
    }
    public static void setSmallTokenLimit(Context c, int v) {
        sp(c).edit().putInt("small_tokens", v).apply();
    }

    // ───────────────────────── 多语音 ─────────────────────────

    /** 多语音总开关：旁白/对话/内心/动作各用一个音色。 */
    public static boolean mvEnabled(Context c) { return sp(c).getBoolean("mv_on", false); }
    public static void setMvEnabled(Context c, boolean v) { sp(c).edit().putBoolean("mv_on", v).apply(); }

    /**
     * 某个角色的音色。三种取值：
     * <ul>
     *   <li>空串 —— 跟随全局音色</li>
     *   <li>{@link MultiVoice#SKIP} —— 这个角色不念</li>
     *   <li>具体音色名 —— 用这个</li>
     * </ul>
     */
    public static String mvVoice(Context c, String role) {
        return sp(c).getString("mv_" + role, "");
    }
    public static void setMvVoice(Context c, String role, String v) {
        sp(c).edit().putString("mv_" + role, v == null ? "" : v).apply();
    }

    /** 解析模式：听书 / GAL 角色标签 / RP 只念引号。 */
    public static int parseMode(Context c) {
        return MultiVoice.clampMode(sp(c).getInt("parse_mode", MultiVoice.MODE_BOOK));
    }
    public static void setParseMode(Context c, int v) { sp(c).edit().putInt("parse_mode", v).apply(); }

    // ───────────────────────── 角色音色表 ─────────────────────────

    /**
     * 一张表管所有嗓子：<b>角色名 = 音色</b>，一行一条。
     * 保留键：旁白 / 对话 / 内心 / 动作 / 默认 —— 旁白同时也是 GAL 模式里
     * 没打标签那半句的角色名，和插件一样。
     */
    public static String voiceTable(Context c) { return sp(c).getString("voice_table", ""); }
    public static void setVoiceTable(Context c, String v) {
        sp(c).edit().putString("voice_table", v == null ? "" : v).apply();
    }

    public static java.util.LinkedHashMap<String, String> voiceTableMap(Context c) {
        java.util.LinkedHashMap<String, String> m = new java.util.LinkedHashMap<String, String>();
        String raw = voiceTable(c);
        if (raw == null) return m;
        for (String line : raw.split("\n")) {
            String s = line.trim();
            if (s.length() == 0) continue;
            int i = s.indexOf('=');
            if (i <= 0) continue;
            String k = s.substring(0, i).trim();
            String v = s.substring(i + 1).trim();
            if (k.length() > 0) m.put(k, v);
        }
        return m;
    }

    public static void putVoice(Context c, String key, String val) {
        if (key == null) return;
        java.util.LinkedHashMap<String, String> m = voiceTableMap(c);
        if (val == null) m.remove(key); else m.put(key.trim(), val);
        saveTable(c, m);
    }

    public static void removeVoice(Context c, String key) {
        java.util.LinkedHashMap<String, String> m = voiceTableMap(c);
        m.remove(key);
        saveTable(c, m);
    }

    private static void saveTable(Context c, java.util.LinkedHashMap<String, String> m) {
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, String> e : m.entrySet()) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(e.getKey()).append('=').append(e.getValue() == null ? "" : e.getValue());
        }
        setVoiceTable(c, sb.toString());
    }

    // ───────────────────────── 语音引擎 ─────────────────────────

    public static String engines(Context c) { return sp(c).getString("engines", ""); }
    public static void setEngines(Context c, String v) {
        sp(c).edit().putString("engines", v == null ? "" : v).apply();
    }

    public static String activeEngine(Context c) { return sp(c).getString("engine_active", ""); }
    public static void setActiveEngine(Context c, String v) {
        sp(c).edit().putString("engine_active", v == null ? "" : v).apply();
    }
}
