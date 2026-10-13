package com.mermergi.ttsrelay;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把流式增量片段切成一句一句，顺便：
 *   - 丢掉 ``` 代码块（跨 chunk 的半个围栏也认）
 *   - 刮掉 Markdown 边角料
 *   - 丢掉 emoji
 */
public final class Splitter {
    private static final Pattern FENCE_BLOCK = Pattern.compile("```.*?```", Pattern.DOTALL);
    private static final Pattern SENT_END = Pattern.compile("[。！？!?；;…]+|\\n+");
    private static final Pattern IMG = Pattern.compile("!\\[[^\\]]*\\]\\([^)]*\\)");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]*)\\]\\([^)]*\\)");
    private static final Pattern URL = Pattern.compile("https?://\\S+");
    private static final Pattern HEAD = Pattern.compile("(?m)^\\s{0,3}#{1,6}\\s*");
    private static final Pattern BULLET = Pattern.compile("(?m)^\\s*[-*+•]\\s+");
    private static final Pattern ORD = Pattern.compile("(?m)^\\s*\\d+[.)、]\\s*");
    private static final Pattern QUOTE = Pattern.compile("(?m)^\\s*>\\s*");
    private static final Pattern HR = Pattern.compile("(?m)^\\s*[-=*_]{3,}\\s*$");
    private static final Pattern MDSYM = Pattern.compile("[*_~`|]+");
    private static final Pattern WS = Pattern.compile("\\s+");

    private final StringBuilder hold = new StringBuilder();
    private final StringBuilder live = new StringBuilder();
    private int maxChunk = 90;
    private int minChunk = 4;

    public Splitter(int maxChunk, int minChunk) {
        this.maxChunk = Math.max(20, maxChunk);
        this.minChunk = Math.max(1, minChunk);
    }

    public List<String> feed(String delta) {
        if (delta == null || delta.isEmpty()) return new ArrayList<String>();
        hold.append(delta);
        String h = hold.toString();
        String nw = FENCE_BLOCK.matcher(h).replaceAll("");
        int idx = nw.indexOf("```");
        if (idx >= 0) {
            hold.setLength(0);
            hold.append(nw.substring(idx));
            nw = nw.substring(0, idx);
        } else {
            int c = 0;
            while (c < 2 && nw.length() - 1 - c >= 0 && nw.charAt(nw.length() - 1 - c) == '`') c++;
            if (c > 0) {
                hold.setLength(0);
                hold.append(nw.substring(nw.length() - c));
                nw = nw.substring(0, nw.length() - c);
            } else {
                hold.setLength(0);
            }
        }
        live.append(nw);
        return extract();
    }

    private List<String> extract() {
        List<String> out = new ArrayList<String>();
        while (true) {
            Matcher m = SENT_END.matcher(live);
            if (m.find()) {
                String piece = live.substring(0, m.end());
                live.delete(0, m.end());
                String t = clean(piece);
                if (t.length() >= minChunk) out.add(t);
                continue;
            }
            if (live.length() >= maxChunk) {
                int cut = maxChunk;
                int floor = Math.max(minChunk, cut - 30);
                for (int i = cut; i > floor; i--) {
                    char ch = live.charAt(i - 1);
                    if (ch == '，' || ch == ',' || ch == '、' || ch == '）' || ch == ')' || ch == ' ') {
                        cut = i;
                        break;
                    }
                }
                String piece = live.substring(0, cut);
                live.delete(0, cut);
                String t = clean(piece);
                if (t.length() >= minChunk) out.add(t);
                continue;
            }
            break;
        }
        return out;
    }

    public List<String> flush() {
        List<String> out = new ArrayList<String>();
        String t = clean(live.toString());
        if (t.length() >= minChunk) out.add(t);
        live.setLength(0);
        hold.setLength(0);
        return out;
    }

    public static String clean(String s) {
        if (s == null) return "";
        s = IMG.matcher(s).replaceAll("");
        s = LINK.matcher(s).replaceAll("$1");
        s = URL.matcher(s).replaceAll("");
        s = HEAD.matcher(s).replaceAll("");
        s = BULLET.matcher(s).replaceAll("");
        s = ORD.matcher(s).replaceAll("");
        s = QUOTE.matcher(s).replaceAll("");
        s = HR.matcher(s).replaceAll("");
        s = MDSYM.matcher(s).replaceAll("");
        s = stripEmoji(s);
        s = WS.matcher(s).replaceAll(" ").trim();
        return s;
    }

    private static String stripEmoji(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            int n = Character.charCount(cp);
            boolean drop =
                    (cp >= 0x1F300 && cp <= 0x1FAFF)
                            || (cp >= 0x1F000 && cp <= 0x1F2FF)
                            || (cp >= 0x2600 && cp <= 0x27BF)
                            || (cp >= 0x2B00 && cp <= 0x2BFF)
                            || cp == 0xFE0F || cp == 0x200D || cp == 0x20E3;
            if (!drop) sb.appendCodePoint(cp);
            i += n;
        }
        return sb.toString();
    }
}
