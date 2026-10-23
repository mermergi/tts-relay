package com.mermergi.ttsrelay;

import java.util.ArrayList;
import java.util.List;

/**
 * 一条音色。原值里本来就带标签，比如 TTS Server 的
 * {@code 成熟姐姐 女|青年|成熟_1792090364247} —— 名字、性别、年龄、风格都在这儿。
 * 解析出来，界面才能按男女分组、把标签单独摆一行。
 */
final class VoiceItem {

    /** 列表里的分组标题（女声 / 男声 / 未标注），不是真音色。 */
    boolean header;

    String name = "";      // 显示名（不含标签）
    String raw = "";       // 存下去的原值
    String gender = "未标注";
    String age = "";
    String style = "";
    String tagLine = "";
    String srcId = "";     // 这个音色是谁家的（引擎 id）
    String srcLabel = "";  // 引擎显示名
    String usedBy = "";    // 已经绑了哪些角色（一个音色可以绑多个）
    String icon = "";      // 插件给的音色图标（没有就是空串）

    VoiceItem() { }

    VoiceItem(String display, String value) {
        name = display;
        raw = value;
        tagLine = display;
    }

    static VoiceItem header(String title) {
        VoiceItem it = new VoiceItem();
        it.header = true;
        it.name = title;
        it.raw = "";
        return it;
    }

    static VoiceItem parse(String value) {
        VoiceItem it = new VoiceItem();
        it.raw = value == null ? "" : value;
        it.icon = EngineStore.voiceIcon(it.raw);
        String v = it.raw;
        int sep = v.indexOf(EngineStore.SEP);          // 官方链路：名字‖style_id
        if (sep > 0) v = v.substring(0, sep);
        v = v.replaceAll("_\\d+$", "").trim();
        it.name = v;
        int sp = v.lastIndexOf(' ');
        if (sp > 0) {
            String tail = v.substring(sp + 1);
            if (tail.contains("|")) {
                it.name = v.substring(0, sp);
                String[] parts = tail.split("\\|");
                List<String> rest = new ArrayList<String>();
                for (String p : parts) {
                    String t = p.trim();
                    if (t.length() == 0) continue;
                    if ("女".equals(t) || "女性".equals(t) || "女声".equals(t)) {
                        it.gender = "女声";
                    } else if ("男".equals(t) || "男性".equals(t) || "男声".equals(t)) {
                        it.gender = "男声";
                    } else if ("少年".equals(t) || "青年".equals(t) || "中年".equals(t)
                            || "老年".equals(t) || "儿童".equals(t) || "少女".equals(t)) {
                        it.age = t;
                    } else {
                        rest.add(t);
                    }
                }
                StringBuilder sb = new StringBuilder();
                if (!"未标注".equals(it.gender)) sb.append(it.gender);
                if (it.age.length() > 0) { if (sb.length() > 0) sb.append(" · "); sb.append(it.age); }
                for (String t : rest) { if (sb.length() > 0) sb.append(" · "); sb.append(t); }
                it.tagLine = sb.toString();
                it.style = rest.isEmpty() ? "" : rest.get(0);
                return it;
            }
        }
        // ③ 标签块里没有、名字里也没写 → 从整条原值里认通用标记：
        //    官方链路那半边是 style_id，像 zh_male_rap / zh_female_zhubo，
        //    插件那边也可能给 female / male。认出一次，所有引擎都适用。
        if ("未标注".equals(it.gender)) {
            String g = genderOf(it.raw);
            if (g != null) {
                it.gender = g;
                it.tagLine = (it.tagLine.length() == 0) ? g : (g + " · " + it.tagLine);
            }
        }
        return it;
    }

    /**
     * 通用性别识别 —— 不按引擎一个个适配。
     *
     * <p>① 值里写明的标记（引擎给的，最准）：英文 {@code female / woman / girl / lady}、
     * {@code male / _man / man / boy}，中文 {@code 女 / 男}。"female" 里含 "male"，所以先判女。
     *
     * <p>② 没写标记的（纯人名、网络称呼），用 {@code assets/gender_table.txt} 打分：
     * 那张表是从开源中文人名语料库（Apache-2.0，105 万条「名字,性别」）统计出来的
     * 每个字 / 双字的男女性别倾向，见 {@code tools/build-gender-table.py}。
     */
    static String genderOf(String s) {
        if (s == null || s.length() == 0) return null;
        String low = s.toLowerCase(java.util.Locale.ROOT);
        if (low.contains("female") || low.contains("woman") || low.contains("girl")
                || low.contains("lady") || s.contains("女")) return "女声";
        if (low.contains("male") || low.contains("_man") || low.contains(" man")
                || low.contains("boy") || s.contains("男")) return "男声";
        String w = byWords(s);           // ② 开放词典：这个词的释义里有没有写男女
        return w != null ? w : byTable(s);   // ③ 都没有 → 字级统计兜底
    }

    /** 在 CC-CEDICT 词表里找名字里最长的那个词，它的性别就是名字的性别。 */
    private static String byWords(String name) {
        loadWords();
        if (WORDS == null || WORDS.isEmpty() || name == null) return null;
        // 只认两个字以上的词：单字太容易撞（神、子、华 这种中性字会把好名字带偏）
        for (int len = Math.min(5, name.length()); len >= 2; len--) {
            for (int i = 0; i + len <= name.length(); i++) {
                Integer g = WORDS.get(name.substring(i, i + len));
                if (g != null) return g > 0 ? "女声" : "男声";
            }
        }
        return null;
    }

    private static java.util.HashMap<String, Integer> WORDS;

    private static synchronized void loadWords() {
        if (WORDS != null) return;
        WORDS = new java.util.HashMap<String, Integer>();
        try {
            android.content.Context c = App.ctx();
            if (c == null) return;
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(
                    c.getAssets().open("gender_words.txt"), "UTF-8"));
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.length() == 0 || line.charAt(0) == '#') continue;
                    int tab = line.indexOf('\t');
                    if (tab <= 0) continue;
                    try {
                        WORDS.put(line.substring(0, tab),
                                Integer.valueOf(line.substring(tab + 1).trim()));
                    } catch (NumberFormatException ignored) { }
                }
            } finally {
                r.close();
            }
        } catch (Throwable t) {
            // 读不到就少一层，不影响别的
        }
    }

    /** 统计法兜底：拿字/双字表给名字打分，偏得够明显才下结论。 */
    private static String byTable(String name) {
        loadTable();
        if (TABLE == null || TABLE.isEmpty()) return null;
        float sum = 0f;
        int hits = 0;
        int i = 0;
        while (i < name.length()) {
            if (i + 2 <= name.length()) {
                Float f = TABLE.get(name.substring(i, i + 2));
                if (f != null) { sum += f; hits++; i += 2; continue; }
            }
            Float f = TABLE.get(name.substring(i, i + 1));
            if (f != null) { sum += f; hits++; }
            i++;
        }
        // 字级表只是兜底，容易把「歌」「儿」这种中性偏一边的字当证据，
        // 所以要两个以上字、而且平均分要偏得明显才敢下结论
        if (hits < 2) return null;
        float avg = sum / hits;
        if (avg >= 1.5f) return "女声";
        if (avg <= -1.5f) return "男声";
        return null;
    }

    private static java.util.HashMap<String, Float> TABLE;

    private static synchronized void loadTable() {
        if (TABLE != null) return;
        TABLE = new java.util.HashMap<String, Float>();
        try {
            android.content.Context c = App.ctx();
            if (c == null) return;
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(
                    c.getAssets().open("gender_table.txt"), "UTF-8"));
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.length() == 0 || line.charAt(0) == '#') continue;
                    int tab = line.indexOf('\t');
                    if (tab <= 0) continue;
                    try {
                        TABLE.put(line.substring(0, tab),
                                Float.valueOf(line.substring(tab + 1).trim()));
                    } catch (NumberFormatException ignored) { }
                }
            } finally {
                r.close();
            }
        } catch (Throwable t) {
            // 表读不到就只是少一层识别，不影响别的
        }
    }

    @Override public String toString() { return name; }
}
