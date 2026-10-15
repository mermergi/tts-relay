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
 * 三种模式的<b>标签写法</b>跟酒馆那套多角色语音插件（ST-indexTTS2-X-Player）
 * 保持一致，这样模型按那套格式吐出来的文本拿来就能用 —— 但解析代码是这儿自己写的
 * （那套插件是 AGPL，只借格式、不借代码）：
 *
 * <ul>
 *   <li><b>GAL</b>：只念带角色标签的台词。角色名取<b>第一个方括号</b>，
 *       后面几层方括号（情感、场景、情绪向量…）是元数据，认了不用：
 *       {@code [角色][情感][场景]「台词」} / {@code [角色][情感]「台词」} /
 *       {@code [角色]「台词」} / {@code [角色] 台词} 都认。
 *       没打标签的行算旁白；旁白是<b>保留角色名</b>，和角色共用一张音色表。</li>
 *   <li><b>RP</b>：只念引号内的内容。引号成对认（{@code 「」} {@code “”} {@code 『』}
 *       {@code ‘’} {@code ""}），不跨类型。</li>
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

    /** 一段文本：正文 + 角色（可能是空）+ 语气类别。 */
    public static final class Seg {
        public final String text;
        public final String role;
        public final String name;     // 角色名；空 = 按 role 查表
        public Seg(String t, String r, String n) { text = t; role = r; name = n == null ? "" : n; }
        @Override public String toString() {
            return "[" + role + (name.length() == 0 ? "" : ":" + name) + "] " + text;
        }
    }

    /** 这句该用哪个音色；null = 不念。 */
    public static String voiceFor(Context c, Seg seg) {
        if (!Prefs.mvEnabled(c)) return Prefs.voice(c);
        Map<String, String> t = Prefs.voiceTableMap(c);
        String v = null;
        if (seg.name.length() > 0) v = t.get(seg.name);
        if (v == null) v = t.get(roleKey(seg.role));
        if (v == null) v = t.get(K_DEFAULT);
        if (v == null || v.length() == 0) return Prefs.voice(c);   // 没配 → 跟随全局
        return SKIP.equals(v) ? null : v;
    }

    public static String roleKey(String role) {
        if (NARRATOR.equals(role)) return K_NARRATOR;
        if (DIALOGUE.equals(role)) return K_DIALOGUE;
        if (THOUGHT.equals(role)) return K_THOUGHT;
        if (ACTION.equals(role)) return K_ACTION;
        return K_DEFAULT;
    }

    // ───────────────────────── GAL 标签 ─────────────────────────

    private static final class Gal {
        String character = "";
        String dialogue = "";
    }

    /**
     * 认一行 GAL 台词：{@code [角色][情感][场景]「台词」}。
     *
     * 标签可以是一层、两层、三层（再多也行），<b>角色名永远是第一个方括号</b>里的东西；
     * 后面几层是情感/场景这类给音效用的元数据，这儿不关心。标签后面允许有一个冒号，
     * 台词可以拿引号包着，也可以就那么直接跟。
     *
     * 写法是那套插件定的（见类注释），但实现是这儿一格一格扫出来的，
     * 所以 {@code [角色][情感]} 这种压根没台词的行会老老实实判成"不是台词"，
     * 而不是把 {@code [情感]} 当成要说的话。
     */
    private static Gal parseGal(String line) {
        if (line == null) return null;
        String s = line.trim();
        if (s.length() == 0 || s.charAt(0) != '[') return null;

        int i = 0;
        String character = null;
        while (i < s.length() && s.charAt(i) == '[') {
            int close = s.indexOf(']', i + 1);
            if (close < 0) return null;                       // 方括号没闭上
            String tag = clean(s.substring(i + 1, close));
            if (character == null) {
                if (tag.length() == 0) return null;           // 第一个标签就是空的
                character = tag;
            }
            i = close + 1;
            while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t')) i++;
        }
        if (character == null) return null;

        if (i < s.length() && (s.charAt(i) == ':' || s.charAt(i) == '\uff1a')) i++;   // 半角/全角冒号都认
        while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t')) i++;

        String rest = s.substring(i).trim();
        if (rest.length() == 0) return null;
        String text = unwrapQuotes(rest);
        if (text.length() == 0) return null;

        Gal g = new Gal();
        g.character = character;
        g.dialogue = text;
        return g;
    }

    /** 整段正好被一对引号包着，就把引号脱掉；不是就原样还回去。 */
    private static String unwrapQuotes(String s) {
        if (s.length() < 2) return s;
        char close = closeOf(s.charAt(0));
        if (close != 0 && s.charAt(s.length() - 1) == close) {
            return s.substring(1, s.length() - 1).trim();
        }
        return s;
    }

    /** 名字里的空白压成一个空格。 */
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
    /** 这一轮说话的人 —— 群聊整条消息都是他的。 */
    private String turnName = "";
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
            if (isSpeakerName(n) && (isQuoteStart(c) || Character.isLetterOrDigit(c))) {
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
        putAll(text, r, n);
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

    private void putAll(String s, String r, String n) {
        if (s == null || s.isEmpty()) return;
        String nn = n == null ? "" : n;
        if (!r.equals(role) || !nn.equals(name)) {
            if (hasWord(run)) out.addAll(flushRun());
            else run.setLength(0);        // 还没说人话，直接改标签，不用冲断句器
            role = r;
            name = nn;
        }
        run.append(s);
        for (int i = 0; i < s.length(); i++) ctxPush(s.charAt(i));
        for (String x : sp.feed(s)) out.add(new Seg(x, role, name));
        prev = s.charAt(s.length() - 1);
    }

    private List<Seg> flushRun() {
        List<Seg> res = new ArrayList<Seg>();
        for (String s : sp.flush()) res.add(new Seg(s, role, name));
        run.setLength(0);
        return res;
    }

    private void ctxPush(char c) {
        ctxBuf.append(c);
        int over = ctxBuf.length() - 64;
        if (over > 0) ctxBuf.delete(0, over);
    }
}
