package com.mermergi.ttsrelay;

import android.content.Context;
import android.content.SharedPreferences;

/** 全部可调项都落在这里，UI 直接读写。 */
public final class Prefs {
    public static final String UPSTREAM_DEF = "https://api.deepseek.com";
    public static final String TTS_BASE_DEF = "http://127.0.0.1:1221";
    public static final String ENGINE_DEF = "com.github.jing332.tts_server_android";
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

    public static boolean muted(Context c) { return sp(c).getBoolean("muted", false); }
    public static void setMuted(Context c, boolean v) { sp(c).edit().putBoolean("muted", v).apply(); }

    public static boolean skipSmall(Context c) { return sp(c).getBoolean("skip_small", true); }
    public static void setSkipSmall(Context c, boolean v) { sp(c).edit().putBoolean("skip_small", v).apply(); }

    /** 允许局域网访问（给电脑上的酒馆/其它客户端用）。默认关：开了等于把你的 key 转发给整个局域网。 */
    public static boolean lanAllowed(Context c) { return sp(c).getBoolean("lan", false); }
    public static void setLanAllowed(Context c, boolean v) { sp(c).edit().putBoolean("lan", v).apply(); }

    /** 上次服务是否在跑（用户以为开着，重启后自动拉起来）。 */
    public static boolean wanted(Context c) { return sp(c).getBoolean("wanted", false); }
    public static void setWanted(Context c, boolean v) { sp(c).edit().putBoolean("wanted", v).apply(); }
}
