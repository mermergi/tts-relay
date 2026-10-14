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
 *
 * ── 断句方案 ──
 * 五套方案的差别只在三个轴上：
 *   ① 哪些标点算断点（硬断）              → endPat
 *   ② 攒到多长才肯发（短句合并）          → mergeMin
 *   ③ 超长了往哪儿退（软断点优先级）      → score()
 * 认不出标点的长串一律在"单句最长字数"处硬切，绝不无限攒。
 */
public final class Splitter {

    public static final int MODE_NATURAL = 0;   // 自然句
    public static final int MODE_QUICK   = 1;   // 短句 · 快出声
    public static final int MODE_LONG    = 2;   // 长句 · 少切几次
    public static final int MODE_LINE    = 3;   // 按行 / 条目
    public static final int MODE_FREE    = 4;   // 整段 · 超长才切
    public static final int MODE_COUNT   = 5;

    private static final String[] NAMES = {
            "自然句（默认）",
            "短句 · 快出声",
            "长句 · 少切几次",
            "按行 / 条目",
            "整段 · 超长才切",
    };

    private static final String[] DESCS = {
            "句号、问号、叹号、分号、换行才断；太长就退到逗号。最像人念书。",
            "逗号、顿号、冒号也算断点，而且第一块特意切得短 —— 追求「张嘴就出声」的响应速度。",
            "先把短句攒成一块再合成：TTS 请求更少、语气更连贯，代价是第一声要等一会儿。",
            "只在换行处断：列表、步骤、剧本、歌词，一行一句。",
            "几乎不主动切，只在超过「单句最长字数」时按标点退让切一刀。块最大最连贯，也最容易卡。",
    };

    public static String modeName(int m) { return NAMES[clampMode(m)]; }
    public static String modeDesc(int m) { return DESCS[clampMode(m)]; }
    public static int clampMode(int m) { return (m < 0 || m >= MODE_COUNT) ? MODE_NATURAL : m; }

    /** 硬断点：命中就发（除非还没攒够 mergeMin）。 */
    private static final Pattern END_NATURAL = Pattern.compile("[。！？!?；;…]+|\\n+");
    private static final Pattern END_QUICK   = Pattern.compile("[。！？!?；;…，,、：:]+|\\n+");
    private static final Pattern END_LONG    = Pattern.compile("[。！？!?…]+|\\n+");
    private static final Pattern END_LINE    = Pattern.compile("\\n+");

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

    private final int maxChunk;
    private final int minChunk;
    private final int mode;
    private final Pattern endPat;
    private final int mergeMin;     // 0 = 不攒；>0 = 攒到这个长度才肯发
    private final int firstMin;     // 第一块的门槛（"快出声"用）
    private boolean first = true;   // 还没念出过任何一块

    public Splitter(int maxChunk, int minChunk) {
        this(maxChunk, minChunk, MODE_NATURAL);
    }

    public Splitter(int maxChunk, int minChunk, int mode) {
        this.maxChunk = Math.max(20, maxChunk);
        this.minChunk = Math.max(1, minChunk);
        this.mode = clampMode(mode);
        switch (this.mode) {
            case MODE_QUICK:
                endPat = END_QUICK;
                mergeMin = 0;
                firstMin = 1;                       // 第一块：有标点就放，越早出声越好
                break;
            case MODE_LONG:
                endPat = END_LONG;
                mergeMin = Math.max(60, this.maxChunk * 7 / 10);
                firstMin = this.minChunk;
                break;
            case MODE_LINE:
                endPat = END_LINE;
                mergeMin = 0;
                firstMin = this.minChunk;
                break;
            case MODE_FREE:
                endPat = null;                      // 没有硬断点，只在超长时退让切
                mergeMin = Integer.MAX_VALUE;
                firstMin = this.minChunk;
                break;
            case MODE_NATURAL:
            default:
                endPat = END_NATURAL;
                mergeMin = 0;
                firstMin = this.minChunk;
                break;
        }
    }

    public int mode() { return mode; }

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

    private static final Pattern FENCE_BLOCK = Pattern.compile("```.*?```", Pattern.DOTALL);

    private List<String> extract() {
        List<String> out = new ArrayList<String>();
        while (true) {
            int end = -1;
            if (endPat != null) {
                int need = first ? firstMin : mergeMin;
                Matcher m = endPat.matcher(live);
                while (m.find()) {
                    if (need <= 0 || m.end() >= need) { end = m.end(); break; }
                    // 还没攒够 → 继续往后找下一个断点（长句合并就是这么来的）
                }
            }
            if (end > 0) {
                add(out, live.substring(0, end));
                live.delete(0, end);
                continue;
            }
            if (live.length() >= maxChunk) {
                int cut = bestCut(live, maxChunk);
                add(out, live.substring(0, cut));
                live.delete(0, cut);
                continue;
            }
            break;
        }
        return out;
    }

    /**
     * 硬切点挑选：在 [maxChunk-窗, maxChunk] 里找<b>优先级最高</b>的标点；
     * 同优先级取离 maxChunk 最近的。找不到就老实硬切。
     * 窗口随 maxChunk 放大（至少 20 字），免得长块被从句子中间劈开。
     */
    private int bestCut(CharSequence s, int cut) {
        int window = Math.max(20, maxChunk / 2);
        int floor = Math.max(1, cut - window);
        int best = cut, bestScore = 0;
        for (int i = cut; i > floor; i--) {
            int sc = score(s.charAt(i - 1));
            if (sc > bestScore) { bestScore = sc; best = i; }
        }
        return best;
    }

    /** 标点优先级：句末 > 分号 > 逗号 > 冒号/右括号 > 空白。0 = 不该在这儿切。 */
    private static int score(char c) {
        if (c == '。' || c == '！' || c == '？' || c == '!' || c == '?' || c == '…') return 5;
        if (c == '；' || c == ';') return 4;
        if (c == '，' || c == ',' || c == '、') return 3;
        if (c == '：' || c == ':' || c == '）' || c == ')' || c == '】' || c == '》'
                || c == '」' || c == '”' || c == '"') return 2;
        if (c == ' ' || c == '\t') return 1;
        return 0;
    }

    private void add(List<String> out, String piece) {
        String t = clean(piece);
        if (worthSay(t)) {
            out.add(t);
            first = false;
        }
    }

    /**
     * 洗完还剩"实词"才值得念 —— 比单纯比长度准：
     * "好。" 不会被吞掉（旧版长度门槛 4 会把它扔了），
     * 纯符号的 "**" "…" "—" 也进不了 TTS。
     */
    private static boolean worthSay(String t) {
        if (t == null || t.length() == 0) return false;
        for (int i = 0; i < t.length(); ) {
            int cp = t.codePointAt(i);
            if (Character.isLetterOrDigit(cp)) return true;
            i += Character.charCount(cp);
        }
        return false;
    }

    public List<String> flush() {
        List<String> out = new ArrayList<String>();
        add(out, live.toString());
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
