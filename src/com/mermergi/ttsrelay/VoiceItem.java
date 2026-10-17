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
        String v = it.raw;
        int sep = v.indexOf(EngineStore.SEP);          // 豆包：名字‖style_id
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
        it.tagLine = "";
        return it;
    }

    @Override public String toString() { return name; }
}
