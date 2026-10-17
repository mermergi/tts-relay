package com.mermergi.ttsrelay;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 分角色朗读。
 *
 * 解析规则<b>照搬</b>酒馆多角色语音插件（ST-indexTTS2-X-Player）的 GAL/RP 两套，
 * 没有自己发明格式 —— 它怎么认，这里就怎么认：
 *
 * <ul>
 *   <li><b>GAL</b>：只念带角色标签的台词，格式按优先级
 *       {@code [角色][表情][场景]「台词」→ [角色][表情]「台词」→ [角色]「台词」→ [角色] 台词}，
 *       没打标签的行算旁白；旁白是<b>保留角色名</b>，和角色共用一张音色表。</li>
 *   <li><b>RP</b>：只念引号内的内容，五种引号分开匹配不跨类型。</li>
 *   <li><b>听书</b>：全文都念，只是按旁白/对话/内心/动作换嗓子（原来的行为）。</li>
 * </ul>
 */
public final class MultiVoice {

    // ───────────────────────── 解析模式 ─────────────────────────

    public static final int MODE_BOOK = 0;     // 听书 · 全文
    public static final int MODE_GAL = 1;      // GAL · 角色标签
    public static final int MODE_RP = 2;       // RP · 只念引号
    public static final int MODE_COUNT = 3;

    public static final String[] MODE_NAMES = {"听书 · 全文都念", "GAL · 角色标签", "RP · 只念引号"};
    public static final String[] MODE_DESCS = {
            "全文都念，按旁白/对话/内心/动作换嗓子。不打标签也能用。",
            "只念带 [角色名] 标签的台词，旁白单独一个嗓子。要多角色就选这个。",
            "只念引号（「」 “” 『』 ‘’）里的话，其余一律不念。",
    };
    public static int clampMode(int m) { return (m < 0 || m >= MODE_COUNT) ? MODE_BOOK : m; }

    // ───────────────────────── 角色 ─────────────────────────

    public static final String NARRATOR = "narrator";
    public static final String DIALOGUE = "dialogue";
    public static final String THOUGHT = "thought";
    public static final String ACTION = "action";

    /** 音色表里的保留键 —— 和角色名混在同一张表里。 */
    public static final String K_NARRATOR = "旁白";
    public static final String K_DIALOGUE = "对话";
    public static final String K_THOUGHT = "内心";
    public static final String K_ACTION = "动作";
    public static final String K_DEFAULT = "默认";

    /** 音色表里这个值 = 不念。 */
    public static final String SKIP = "\u0001skip";

    /** 这五个是分类键，不是角色名。 */
    public static boolean isReservedKey(String k) {
        return K_NARRATOR.equals(k) || K_DIALOGUE.equals(k) || K_THOUGHT.equals(k)
                || K_ACTION.equals(k) || K_DEFAULT.equals(k);
    }

    /** 一段文本：正文 + 角色（可能是空）+ 语气类别。 */
    public static final class Seg {
        public final String text;
        public final String role;
        public final String name;     // 第一个标签（角色名）；空 = 按 role 查表
        /**
         * 这一行开头那几个 {@code [标签]}，按顺序。
         * 例：[小明][少年]「…」 → ["小明", "少年"]。
         * 查音色时**从头一个个试，第一个命中的算**，所以 [小明] 没配过就能落到 [少年] 上。
         */
        public final java.util.List<String> tags;

        public Seg(String t, String r, String n) { this(t, r, n, null); }

        public Seg(String t, String r, String n, java.util.List<String> tagList) {
            text = t;
            role = r;
            name = n == null ? "" : n;
            java.util.List<String> tags0 = new java.util.ArrayList<String>();
            if (tagList != null) {
                for (String x : tagList) {
                    String v = x == null ? "" : x.trim();
                    if (v.length() > 0 && !tags0.contains(v)) tags0.add(v);
                }
            } else if (name.length() > 0) {
                tags0.add(name);
            }
            tags = tags0;
        }

        @Override public String toString() {
            return "[" + role + (name.length() == 0 ? "" : ":" + name) + "] " + text;
        }
    }

    /** 查表：允许名字两边有空白、大小写不同 —— 卡片里的写法常和手打的差一点点。 */
    private static String lookup(Map<String, String> t, String key) {
        if (key == null) return null;
        String v = t.get(key);
        if (v == null) v = t.get(key.trim());
        if (v == null) {
            String k = key.trim();
            for (Map.Entry<String, String> e : t.entrySet()) {
                if (e.getKey().trim().equalsIgnoreCase(k)) return e.getValue();
            }
        }
        return v;
    }

    /** 这句该用哪个音色；null = 不念。 */
    public static String voiceFor(Context c, Seg seg) {
        if (!Prefs.mvEnabled(c)) return Prefs.voice(c);

        // ① 先看「音色 → 名称」那几条（先选音色，再填名字）：名字对上就用它的音色。
        String byName = nameVoice(c, seg);
        if (byName != null) return byName;

        // ② 再退回老那张「角色 → 音色」表（老配置照样能用）
        Map<String, String> t = Prefs.voiceTableMap(c);
        String v = null;
        if (seg.name.length() > 0) v = lookup(t, seg.name);
        if (v == null) v = lookup(t, roleKey(seg.role));
        if (v == null) v = lookup(t, K_DEFAULT);
        if (v == null || v.length() == 0) return Prefs.voice(c);   // 没配 → 跟随全局
        return SKIP.equals(v) ? null : v;
    }

    // ───────────────────────── 音色 → 名称（先选音色，再填名字） ─────────────────────────

    /** 名称怎么切：竖线 / 顿号 / 逗号都行。 */
    public static java.util.List<String> splitNames(String names) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        if (names == null) return out;
        for (String p : names.split("[|｜、,，]+")) {
            String n = p.trim();
            if (n.length() > 0 && !out.contains(n)) out.add(n);
        }
        return out;
    }

    /**
     * 这一段该用哪个音色 —— 拿「角色名 / 段类型」去和每条「音色 → 名称」里的名字对，
     * 从上往下第一条对上的算。null = 没有绑定管它。
     */
    public static String nameVoice(Context c, Seg seg) {
        java.util.LinkedHashMap<String, String> m = Prefs.voiceNamesMap(c);
        if (m.isEmpty()) return null;
        // ① 行首的标签**一个接一个试**：第一个命中的就定下来，不再看后面的。
        //    例：[小明][少年]「…」——小明配过就用小明的，没配过就落到少年那条上。
        if (seg.tags != null) {
            for (String tag : seg.tags) {
                String hit = voiceOfName(m, tag);
                if (hit != null) return hit;
            }
        }
        // ② 标签都不认，再看段类型（旁白 / 对话 / 内心 / 动作）
        String role = seg.role == null ? "" : seg.role.trim();
        String hit = voiceOfName(m, role);
        if (hit != null) return hit;
        return voiceOfName(m, roleKey(role));
    }

    /** 这个名字（或段类型）配了哪个音色；没有就 null。 */
    private static String voiceOfName(java.util.LinkedHashMap<String, String> m, String want) {
        if (want == null) return null;
        String w = want.trim();
        if (w.length() == 0) return null;
        for (java.util.Map.Entry<String, String> e : m.entrySet()) {
            for (String n : splitNames(e.getValue())) {
                if (n.equalsIgnoreCase(w)) return e.getKey();
            }
        }
        return null;
    }

    public static String roleKey(String role) {
        if (NARRATOR.equals(role)) return K_NARRATOR;
        if (DIALOGUE.equals(role)) return K_DIALOGUE;
        if (THOUGHT.equals(role)) return K_THOUGHT;
        if (ACTION.equals(role)) return K_ACTION;
        return K_DEFAULT;
    }

    // ───────────────────────── GAL 标签规则（原样搬来的） ─────────────────────────

    private static final String Q_OPEN = "[\u300c\"\u201c\u300e]";        // 「 " “ 『
    private static final String Q_CLOSE = "[\u300d\"\u201d\u300f]";       // 」 " ” 』

    /** 格式1：[角色][表情][场景]「台词」 */
    private static final Pattern GAL_3 = Pattern.compile(
            "^\\s*\\[([^\\]\\n]+)\\]\\s*\\[([^\\]\\n]*)\\]\\s*\\[([^\\]\\n]+)\\]\\s*:?\\s*("
                    + Q_OPEN + "(.*?)" + Q_CLOSE + "|.+)\\s*$");
    /** 格式2：[角色][表情]「台词」 */
    private static final Pattern GAL_2 = Pattern.compile(
            "^\\s*\\[([^\\]\\n]+)\\]\\s*(?:\\|\\s*)?\\[([^\\]]*)\\]\\s*:?\\s*("
                    + Q_OPEN + "(.*?)" + Q_CLOSE + "|.+)\\s*$");
    /** 格式3/4：[角色][情绪向量]「台词」 或 [角色] 台词 */
    private static final Pattern GAL_1 = Pattern.compile(
            "^\\s*\\[([^\\]]+)\\](?:\\[[\\d.,\\s-]*\\])?\\s*\\s*("
                    + Q_OPEN + "(.*?)" + Q_CLOSE + "|.+)\\s*$");
    /** 引号只认这五种，分开匹配不跨类型。 */
    private static final Pattern RP_QUOTE = Pattern.compile(
            "\u300c([^\u300d]+)\u300d|\"([^\"]+)\"|\u300e([^\u300f]+)\u300f"
                    + "|\u201c([^\u201d]+)\u201d|\u2018([^\u2019]+)\u2019");

    private static final class Gal {
        String character = "";
        String dialogue = "";
        java.util.List<String> tags = new java.util.ArrayList<String>();
    }

    /** 行首连着的那几个 [标签]：{@code [小明][少年]「…」} → ["小明","少年"]。 */
    private static final Pattern LEAD_TAGS = Pattern.compile("^\\s*(\\[[^\\]]*\\])+");

    private static java.util.List<String> leadingTags(String line) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        Matcher m = LEAD_TAGS.matcher(line == null ? "" : line);
        if (!m.find()) return out;
        String head = m.group();
        Matcher one = Pattern.compile("\\[([^\\]]*)\\]").matcher(head);
        while (one.find()) {
            String t = clean(one.group(1));
            if (t.length() > 0 && !out.contains(t)) out.add(t);
        }
        return out;
    }

    private static Gal parseGal(String line) {
        if (line == null) return null;
        String s = line.trim();
        if (s.length() == 0 || s.charAt(0) != '[') return null;

        Matcher m = GAL_3.matcher(s);
        if (m.matches()) return gal(m, 1, 5, 4);
        m = GAL_2.matcher(s);
        if (m.matches()) return gal(m, 1, 4, 3);
        m = GAL_1.matcher(s);
        if (m.matches()) return gal(m, 1, 3, 2);
        return null;
    }

    private static Gal gal(Matcher m, int gName, int gInner, int gRaw) {
        String name = clean(m.group(gName));
        String inner = m.group(gInner);
        String text = inner != null ? inner.trim() : String.valueOf(m.group(gRaw)).trim();
        if (name.length() == 0 || text.length() == 0) return null;
        Gal g = new Gal();
        g.character = name;
        g.dialogue = text;
        // 行首所有 [标签] 都收着：第一个当角色名，后面几个是"退路"
        // （[小明][少年]：小明没配音色就用少年那一条）
        java.util.List<String> tags = leadingTags(m.group(0));
        if (tags.isEmpty()) tags.add(name);
        g.tags = tags;
        return g;
    }

    private static String clean(String s) {
        if (s == null) return "";
        return s.replaceAll("\\s+", " ").trim();
    }

    private static boolean isNarratorName(String n) {
        return K_NARRATOR.equals(n) || "Narrator".equalsIgnoreCase(n) || " narrator".equalsIgnoreCase(n);
    }

    // ───────────────────────── 心里话 / 短引用 ─────────────────────────

    private static final String THOUGHT_WORDS =
            "心想|心里想|心中想|心道|心说|暗道|暗想|暗暗想|想道|内心|心里|心中"
                    + "|暗忖|思忖|思量|脑海|脑中|寻思";

    private static final Pattern THOUGHT_TAIL = Pattern.compile(
            "(?:" + THOUGHT_WORDS + ")[，,：:\\s　]*$");
    private static final Pattern THOUGHT_HEAD = Pattern.compile(
            "^[（(【〔\\[，,：:\\s　]*(?:" + THOUGHT_WORDS + ")");

    /** 短引号里的标点：有这些才算一句真台词。 */
    private static boolean realSpeech(String t) {
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '\u3002' || c == '\uff01' || c == '\uff1f' || c == '\u2026'
                    || c == '!' || c == '?' || c == '\uff0c' || c == ','
                    || c == '\uff1b' || c == ';' || c == '\u3001') {
                return true;
            }
        }
        return false;
    }

    private static char closeOf(char open) {
        switch (open) {
            case '\u201c': return '\u201d';
            case '\u2018': return '\u2019';
            case '\u300c': return '\u300d';
            case '\u300e': return '\u300f';
            case '"': return '"';
            case '\'': return '\'';
            default: return 0;
        }
    }

    private static char closeOfBracket(char open) {
        switch (open) {
            case '\uff08': return '\uff09';
            case '(': return ')';
            case '\u3010': return '\u3011';
            case '\u3014': return '\u3015';
            case '[': return ']';
            default: return 0;
        }
    }

    private static boolean wordChar(char c) { return Character.isLetterOrDigit(c); }

    private static boolean hasWord(CharSequence s) {
        for (int i = 0; i < s.length(); i++) if (wordChar(s.charAt(i))) return true;
        return false;
    }

    // ───────────────────────── 状态 ─────────────────────────

    private final int mode;
    private final boolean enabled;      // 总开关（多语音）。关掉 = 全部走全局音色
    private final Splitter sp;

    /** 听书模式用：当前这一段（引号/括号/星号）。 */
    private boolean spanOpen = false;
    private char spanClose = 0;
    private boolean spanIsQuote = false;
    private boolean spanStreaming = false;
    private String spanRole = NARRATOR;
    private String spanName = "";
    private final StringBuilder spanHold = new StringBuilder();
    private final StringBuilder spanBuf = new StringBuilder();

    private boolean pendingStar = false;
    private boolean inFence = false;
    private int ticks = 0;

    /** GAL 模式用：一行一行攒。 */
    private final StringBuilder lineBuf = new StringBuilder();

    /** RP 模式用：引号里那截。 */
    private boolean rpOpen = false;
    private char rpClose = 0;
    private String rpRole = DIALOGUE;
    private String rpName = "";
    private boolean rpStreaming = false;
    private final StringBuilder rpHold = new StringBuilder();

    private String role = NARRATOR;
    private String name = "";
    /** 当前这段行首的标签表（第一个是角色名，后面是退路）。 */
    private java.util.List<String> curTags = null;
    /** 这一轮说话的人 —— 群聊整条消息都是他的。 */
    private String turnName = "";

    /**
     * 音色表里用户<b>点名过</b>的角色名。这几个名字一出现就一定是说话人 —— 不用猜、
     * 不受"一行只有一个名字"还是"名字：内容"影响。用户既然手填了这个角色，就该认它。
     */
    private java.util.Set<String> knownNames = java.util.Collections.emptySet();

    public void setKnownNames(java.util.Collection<String> names) {
        java.util.Set<String> set = new java.util.LinkedHashSet<String>();
        if (names != null) {
            for (String n : names) {
                if (n == null) continue;
                String t = clean(n);
                if (t.length() > 0 && t.length() <= 24 && !isReservedKey(t)) set.add(t);
            }
        }
        knownNames = set;
    }

    private boolean isKnownName(String n) {
        return n != null && knownNames.contains(n.trim());
    }
    private int prefixState = 0;          // 0 收名字 / 1 吃到冒号 / 2 这行不判前缀
    private final StringBuilder prefixBuf = new StringBuilder();
    private final StringBuilder prefixWs = new StringBuilder();
    private char prefixColon = ':';
    private final StringBuilder run = new StringBuilder();
    private final StringBuilder ctxBuf = new StringBuilder();
    private char prev = 0;
    private List<Seg> out = new ArrayList<Seg>();

    private static final int SPAN_SHORT = 6;

    public MultiVoice(int maxChunk, int minChunk, int splitMode, boolean enabled) {
        this(maxChunk, minChunk, splitMode, enabled, MODE_BOOK);
    }

    public MultiVoice(int maxChunk, int minChunk, int splitMode, boolean enabled, int parseMode) {
        this.sp = new Splitter(maxChunk, minChunk, splitMode);
        this.enabled = enabled;
        this.mode = clampMode(parseMode);
    }

    public int mode() { return mode; }

    /**
     * 这一发是谁在说 —— 群聊的 system 提醒里直接写着（见 HttpServer.speakerFromNudge），
     * 由外面喂进来，省得从正文里猜。只种 {@code turnName}、不动 {@code prefixState}：
     * 模型要是照旧打了「名字: 」前缀，下面照样认得出来并且把前缀吃掉；不打，就按这个人算。
     */
    public void setTurnName(String n) {
        if (n == null) return;
        String s = clean(n);
        if (s.length() > 0) turnName = s;
    }

    // ───────────────────────── 入口 ─────────────────────────

    public List<Seg> feed(String delta) {
        out = new ArrayList<Seg>();
        if (delta == null || delta.isEmpty()) return out;
        if (!enabled) {
            // 单音色：整段直接喂，保持老路径的速度
            for (String x : sp.feed(delta)) out.add(new Seg(x, NARRATOR, ""));
            return out;
        }
        for (int i = 0; i < delta.length(); i++) route(delta.charAt(i));
        return out;
    }

    public List<Seg> flush() {
        out = new ArrayList<Seg>();
        // 这一回合结束了，可还有几个字卡在「这行是不是『角色名:』」的试探里 ——
        // 必须把它们还回去，否则没标点、没换行的短消息会被整条吞掉。
        // （用户消息最常撞这个：发一句「今晚一起吃饭吗」，一个字都没念。）
        if (prefixState == 0) giveUp();
        else if (prefixState == 1) giveUpAfterColon('\0');
        if (mode == MODE_GAL && lineBuf.length() > 0) {
            String line = lineBuf.toString();
            lineBuf.setLength(0);
            emitGalLine(line);
        }
        if (mode == MODE_BOOK && spanOpen && spanHold.length() > 0) {
            putAll(spanHold.toString(), spanRole, spanName);
            spanHold.setLength(0);
        }
        if (mode == MODE_RP && rpOpen && rpHold.length() > 0) {
            putAll(rpHold.toString(), rpRole, rpName);
            rpHold.setLength(0);
        }
        out.addAll(flushRun());
        closeSpan();
        rpOpen = false;
        rpHold.setLength(0);
        rpStreaming = false;
        role = NARRATOR;
        name = "";
        turnName = "";
        prefixState = 0;
        prefixBuf.setLength(0);
        prefixWs.setLength(0);
        ctxBuf.setLength(0);
        prev = 0;
        pendingStar = false;
        inFence = false;
        ticks = 0;
        return out;
    }

    // ═════════════════════════ 群聊的名字前缀 ═════════════════════════

    /**
     * 酒馆群聊会给每条消息加「角色名: 」前缀
     * （openai.js: {@code content = `${chat[j].name}: ${content}`}），模型会照着这个格式输出。
     * 认出它 → ① 念的时候把前缀去掉 ② 整条回复都算这个人说的。
     */
    private void route(char c) {
        if (c == '\n') {
            if (prefixState == 1) giveUpAfterColon('\0');
            else if (prefixState == 0) giveUp();
            dispatch('\n');
            prefixState = 0;
            prefixBuf.setLength(0);
            prefixWs.setLength(0);
            return;
        }
        if (prefixState != 2) {
            if (prefixState == 0) {
                if (prefixBuf.length() == 0 && isSpace(c)) { dispatch(c); return; }
                if (c == ':' || c == '\uff1a') {
                    if (prefixBuf.length() >= 1 && prefixBuf.length() <= 12) {
                        prefixColon = c;
                        prefixState = 1;
                        return;
                    }
                    giveUp(); dispatch(c); return;
                }
                if (isBreaker(c)) { giveUp(); dispatch(c); return; }
                prefixBuf.append(c);
                if (prefixBuf.length() > 12) giveUp();
                return;
            }
            // state 1：冒号吃到了，等第一个实字确认这是不是「说话人」
            if (isSpace(c)) { prefixWs.append(c); return; }
            String n = clean(prefixBuf.toString());
            // 群聊已经把说话人告诉过我们了：名字对得上就一定是前缀，
            // 哪怕后面跟的是「（动作）」这种括号开头，也得把它吃掉而不是念出来。
            boolean known = (turnName.length() > 0 && turnName.equals(n)) || isKnownName(n);
            if ((isSpeakerName(n) || known)
                    && (known || isQuoteStart(c) || Character.isLetterOrDigit(c))) {
                turnName = n;
                prefixBuf.setLength(0);
                prefixWs.setLength(0);
                prefixState = 2;
                dispatch(c);
                return;
            }
            giveUpAfterColon(c);
            return;
        }
        dispatch(c);
    }

    private void giveUp() {
        if (prefixState == 2) return;
        String s = prefixBuf.toString();
        prefixBuf.setLength(0);
        prefixState = 2;
        for (int i = 0; i < s.length(); i++) dispatch(s.charAt(i));
    }

    private void giveUpAfterColon(char extra) {
        String s = prefixBuf.toString();
        String ws = prefixWs.toString();
        prefixBuf.setLength(0);
        prefixWs.setLength(0);
        prefixState = 2;
        for (int i = 0; i < s.length(); i++) dispatch(s.charAt(i));
        dispatch(prefixColon);
        for (int i = 0; i < ws.length(); i++) dispatch(ws.charAt(i));
        if (extra != 0) dispatch(extra);
    }

    private static boolean isSpace(char c) { return c == ' ' || c == '\t' || c == '\u3000'; }

    /** 这些开头一出现，就说明这行不是「角色名: 」 */
    private static boolean isBreaker(char c) {
        return c == '[' || c == '\u3010' || c == '\u300c' || c == '\u201c' || c == '\u300e'
                || c == '\uff08' || c == '(' || c == '*' || c == '#' || c == '-' || c == '>'
                || c == '`' || c == '+' || c == '"' || c == '\'';
    }

    private static boolean isQuoteStart(char c) {
        return c == '\u300c' || c == '\u201c' || c == '\u300e' || c == '\uff08'
                || c == '(' || c == '*' || c == '"';
    }

    private static final java.util.Set<String> NOT_A_NAME = new java.util.HashSet<String>(
            java.util.Arrays.asList("时间", "注意", "备注", "提示", "说明", "格式", "标题", "状态",
                    "心情", "场景", "动作", "表情", "人物", "地点", "系统", "用户", "选项",
                    "设定", "规则", "内容", "名称", "简介", "台词", "环境", "当前", "例如",
                    "示例", "设定", "记录", "结果", "回答", "回复"));

    private static boolean isSpeakerName(String n) {
        if (n == null || n.length() == 0 || n.length() > 12) return false;
        if (NOT_A_NAME.contains(n)) return false;
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (Character.isWhitespace(c)) return false;
            if ("[]\u3010\u3011\u300c\u300d\u300e\u300f\uff08\uff09()\uff1a:*\"'".indexOf(c) >= 0) return false;
        }
        return true;
    }

    /** 把字符交给当前模式。 */
    private void dispatch(char c) {
        if (mode == MODE_GAL) stepGal(c);
        else if (mode == MODE_RP) stepRp(c);
        else stepBook(c);
    }

    // ═════════════════════════ GAL · 角色标签 ═════════════════════════

    private void stepGal(char c) {
        if (c == '\n') {
            String line = lineBuf.toString();
            lineBuf.setLength(0);
            emitGalLine(line);
            return;
        }
        lineBuf.append(c);
        ctxPush(c);
    }

    private void emitGalLine(String line) {
        if (line == null || line.trim().length() == 0) return;
        // 一行光写个名字（`小雪` / `小雪：`）—— 卡片里最常见的写法。
        // 这个名字在音色表里被点名过 → 它就是下面几句的说话人，这行本身不念。
        String bare = line.trim();
        if (bare.endsWith(":") || bare.endsWith("\uff1a")) bare = bare.substring(0, bare.length() - 1).trim();
        if (isKnownName(bare)) { turnName = bare; return; }
        Gal g = parseGal(line);
        String n;
        String text;
        String r;
        if (g != null) {
            n = g.character;
            text = g.dialogue;
            r = isNarratorName(n) ? NARRATOR : DIALOGUE;
            if (isNarratorName(n)) n = K_NARRATOR;
        } else if (turnName.length() > 0) {
            // 群聊：没打标签，但这轮说话的人已经从前缀认出来了
            n = turnName;
            text = line;
            r = DIALOGUE;
        } else {
            n = K_NARRATOR;          // 没打标签的行 → 旁白（和插件一致）
            text = line;
            r = NARRATOR;
        }
        if (text.trim().length() == 0) return;
        putAll(text, r, n, g == null ? null : g.tags);
        out.addAll(flushRun());      // 一行一句，行与行之间不许粘
    }

    // ═════════════════════════ RP · 只念引号 ═════════════════════════

    private void stepRp(char c) {
        if (rpOpen) {
            boolean closing = (c == rpClose);
            if (!closing) rpHold.append(c);
            if (rpStreaming) {
                if (rpHold.length() > 0) {
                    putAll(rpHold.toString(), rpRole, rpName);
                    rpHold.setLength(0);
                }
            } else if (rpHold.length() > SPAN_SHORT) {
                rpStreaming = true;
                putAll(rpHold.toString(), rpRole, rpName);
                rpHold.setLength(0);
            }
            if (closing) {
                if (!rpStreaming && rpHold.length() > 0) {
                    putAll(rpHold.toString(), rpRole, rpName);
                    rpHold.setLength(0);
                }
                out.addAll(flushRun());
                rpOpen = false;
                rpStreaming = false;
            }
            ctxPush(c);
            return;
        }
        char qc = closeOf(c);
        if (qc != 0 && !isApostrophe(c)) {
            rpOpen = true;
            rpClose = qc;
            rpRole = thoughtBefore() ? THOUGHT : DIALOGUE;
            rpName = nameBefore();
            rpHold.setLength(0);
            rpStreaming = false;
        }
        ctxPush(c);        // 引号外的字一律不念
    }

    // ═════════════════════════ 听书 · 全文 ═════════════════════════

    private void stepBook(char c) {
        if (c == '`') {
            ticks++;
            if (ticks == 3) { inFence = !inFence; ticks = 0; }
            put(c, NARRATOR);
            return;
        }
        ticks = 0;
        if (inFence) { put(c, NARRATOR); return; }

        if (pendingStar) {
            pendingStar = false;
            if (c == '*') { put(c, NARRATOR); return; }
            openSpan('*', thoughtBefore() ? THOUGHT : ACTION, false, "");
            spanChar(c);
            return;
        }
        if (spanOpen) { spanChar(c); return; }

        if (c == '*') { pendingStar = true; return; }

        char qc = closeOf(c);
        if (qc != 0 && !isApostrophe(c)) {
            openSpan(qc, thoughtBefore() ? THOUGHT : DIALOGUE, true, nameBefore());
            spanChar(c);
            return;
        }
        char bc = closeOfBracket(c);
        if (bc != 0) {
            openSpan(bc, thoughtBefore() ? THOUGHT : ACTION, false, "");
            spanChar(c);
            return;
        }
        put(c, NARRATOR);
    }

    private void openSpan(char close, String r, boolean isQuote, String n) {
        spanOpen = true;
        spanClose = close;
        spanRole = r;
        spanIsQuote = isQuote;
        spanName = n == null ? "" : n;
        spanStreaming = false;
        spanHold.setLength(0);
        spanBuf.setLength(0);
    }

    private void closeSpan() {
        spanOpen = false;
        spanClose = 0;
        spanIsQuote = false;
        spanStreaming = false;
        spanName = "";
        spanHold.setLength(0);
        spanBuf.setLength(0);
    }

    private void spanChar(char c) {
        spanHold.append(c);
        if (spanBuf.length() < 14) spanBuf.append(c);
        if (!THOUGHT.equals(spanRole) && THOUGHT_HEAD.matcher(spanBuf).find()) {
            spanRole = THOUGHT;
        }
        boolean closing = spanClose != 0 && c == spanClose;

        if (spanStreaming) {
            putAll(spanHold.toString(), spanRole, spanName);
            spanHold.setLength(0);
        } else if (spanHold.length() > SPAN_SHORT) {
            spanStreaming = true;
            putAll(spanHold.toString(), spanRole, spanName);
            spanHold.setLength(0);
        }

        if (closing) {
            if (!spanStreaming) {
                String t = spanHold.toString();
                spanHold.setLength(0);
                // 引号太短又不带标点 → 不算对话，按旁白念
                putAll(t, (spanIsQuote && !realSpeech(t)) ? NARRATOR : spanRole,
                        (spanIsQuote && !realSpeech(t)) ? "" : spanName);
            }
            out.addAll(flushRun());
            closeSpan();
        }
    }

    // ───────────────────────── 名字 / 心里话 的判定 ─────────────────────────

    /** 引号前面那一小截里有没有「张三说道」这种。 */
    private static final Pattern NAME_CUE = Pattern.compile(
            "(?:^|[，。！？；：、\\s\"\u201c\u201d\u300c\u300d\u300e\u300f])"
                    + "([\\u4e00-\\u9fa5A-Za-z0-9·]{2,4})"
                    + "(?:说道|问道|答道|喊道|叫道|笑道|喝道|哭道|叹道|低声道|沉声道|怒道|开口道"
                    + "|说|道|问|答|喊|叫)[：:,，\\s]*$");
    private static final Pattern NAME_TAG = Pattern.compile(
            "[\\[【]([^\\[\\]【】]{1,14})[\\]】][：:\\s]*$");
    private static final java.util.Set<String> PRONOUNS = new java.util.HashSet<String>(
            java.util.Arrays.asList("他", "她", "它", "我", "你", "您", "咱", "俺",
                    "对方", "两人", "二人", "众人", "大家", "有人", "那人", "这人", "他们", "她们"));

    private String nameBefore() {
        String tail = ctxBuf.toString();
        Matcher t = NAME_TAG.matcher(tail);
        if (t.find()) {
            String n = clean(t.group(1));
            if (n.length() > 0 && !PRONOUNS.contains(n)) return n;
        }
        Matcher m = NAME_CUE.matcher(tail);
        if (m.find()) {
            String n = clean(m.group(1)).replaceAll("[地着的了]$", "");
            if (n.length() > 0 && !PRONOUNS.contains(n)) return n;
        }
        return turnName == null ? "" : turnName;
    }

    private boolean thoughtBefore() { return THOUGHT_TAIL.matcher(ctxBuf).find(); }

    private boolean isApostrophe(char c) { return c == '\'' && wordChar(prev); }

    // ───────────────────────── 落字 ─────────────────────────

    private void put(char c, String r) { putAll(String.valueOf(c), r, ""); }

    private void putAll(String s, String r, String n) { putAll(s, r, n, null); }

    private void putAll(String s, String r, String n, java.util.List<String> tags) {
        if (s == null || s.isEmpty()) return;
        String nn = n == null ? "" : n;
        if (!r.equals(role) || !nn.equals(name)) {
            if (hasWord(run)) out.addAll(flushRun());
            else run.setLength(0);        // 还没说人话，直接改标签，不用冲断句器
            role = r;
            name = nn;
            curTags = tags;
        } else if (tags != null && (curTags == null || !curTags.equals(tags))) {
            curTags = tags;               // 名字一样但退路标签换了（少见）：跟着换
        }
        run.append(s);
        for (int i = 0; i < s.length(); i++) ctxPush(s.charAt(i));
        for (String x : sp.feed(s)) out.add(new Seg(x, role, name, curTags));
        prev = s.charAt(s.length() - 1);
    }

    private List<Seg> flushRun() {
        List<Seg> res = new ArrayList<Seg>();
        for (String s : sp.flush()) res.add(new Seg(s, role, name, curTags));
        run.setLength(0);
        return res;
    }

    private void ctxPush(char c) {
        ctxBuf.append(c);
        int over = ctxBuf.length() - 64;
        if (over > 0) ctxBuf.delete(0, over);
    }
}
