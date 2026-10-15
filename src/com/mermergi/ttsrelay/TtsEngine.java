package com.mermergi.ttsrelay;

import android.content.Context;

import java.util.List;

/**
 * 一个语音引擎：给它文字和参数，它给回音频字节。
 *
 * 这就是从 TTS Server 那儿学来的那套抽象 —— 只不过现在跑在我们自己的进程里：
 * 系统 TTS 和 HTTP 模板引擎都实现同一个 TtsEngine，播放链只认这个接口。
 */
public interface TtsEngine {

    /** 引擎类型：system / http。 */
    String kind();

    /** 显示名。 */
    String label();

    /** 合成。返回 WAV / MP3 字节。 */
    byte[] synth(Context c, String text, Say say) throws Exception;

    /** 这个引擎能报出来的音色；拿不到就返回空表。 */
    List<String> voices(Context c);

    /** 配置有没有坑；null = 没毛病。 */
    String problem(Context c);

    /** 一次合成的参数。 */
    final class Say {
        public String voice = "";
        public float rate = 1f;
        public float pitch = 1f;
        public float volume = 1f;
        /** 语气提示，认的引擎才用，不认的忽略。 */
        public String tone = "";

        public Say voice(String v) { voice = v == null ? "" : v; return this; }
    }

    /** 音色名太长时界面要截断 —— 统一在这儿处理。 */
    static String shortVoice(String v) {
        if (v == null) return "";
        int i = v.indexOf('\u2016');            // 可选：名字‖取值
        if (i > 0) v = v.substring(0, i);
        return v.replaceAll("_\\d+$", "");
    }
}
