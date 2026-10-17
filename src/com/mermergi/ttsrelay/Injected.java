package com.mermergi.ttsrelay;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 剥掉客户端/上游塞进正文里的「注入块」。
 *
 * <p>问题现场（用户在酒馆发的 179 字，念出来 4 句）：
 * <pre>
 * 那就分享一下你们的小穴是什么户型吧。减少单轮发言内容，增加每人发言轮次
 * &lt;system-reminder&gt;
 * Current date and time: 2026-10-18 01:14 (UTC+8)
 * &lt;/system-reminder&gt;
 * </pre>
 * 后面三句根本不是用户说的话，是客户端拼进去的上下文；尖括号还被念出来，
 * 插件那边直接合成失败（No data written）。
 *
 * <p>所以念之前先过一遍这里：成对的注入块整段删掉，落单的开/闭标签也删掉。
 * 只认「一看就是注入」的标签名，正文里的普通尖括号（比如表情 <3、代码片段）不动。
 */
final class Injected {

    /** 这些标签名包起来的内容一律不念。 */
    private static final String[] TAGS = {
            "system-reminder", "system", "instructions", "instruction",
            "context", "memory", "background", "lore",
    };

    private static final Pattern BLOCK;
    private static final Pattern STRAY;

    static {
        StringBuilder b = new StringBuilder();
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < TAGS.length; i++) {
            String t = TAGS[i];
            // <tag ...> ... </tag>
            b.append("<").append(t).append("\\b[^>]*>.*?</").append(t).append("\\s*>");
            // 落单的 <tag ...> 或 </tag>
            s.append("</?").append(t).append("\\b[^>]*>");
            if (i < TAGS.length - 1) {
                b.append('|');
                s.append('|');
            }
        }
        BLOCK = Pattern.compile(b.toString(), Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        STRAY = Pattern.compile(s.toString(), Pattern.CASE_INSENSITIVE);
    }

    private Injected() { }

    /** 把注入块剥掉；顺手把空行压掉，返回能直接念的文字。 */
    static String strip(String text) {
        if (text == null || text.length() == 0) return text;
        String out = BLOCK.matcher(text).replaceAll("\n");
        out = STRAY.matcher(out).replaceAll("");
        // 压掉连续空行（剥完一般会留几个）
        out = out.replaceAll("[ \\t]+\\n", "\n").replaceAll("\\n{3,}", "\n\n");
        return out.trim();
    }

    /** 有没有东西被剥掉（只用来打日志）。 */
    static boolean has(String text) {
        if (text == null) return false;
        Matcher m = BLOCK.matcher(text);
        return m.find();
    }
}
