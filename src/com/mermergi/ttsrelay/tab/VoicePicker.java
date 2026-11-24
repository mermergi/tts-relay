package com.mermergi.ttsrelay.tab;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ScrollView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.mermergi.ttsrelay.ChoiceSheet;
import com.mermergi.ttsrelay.EngineStore;
import com.mermergi.ttsrelay.MultiVoice;
import com.mermergi.ttsrelay.Palette;
import com.mermergi.ttsrelay.VoicePickerSheet;
import com.mermergi.ttsrelay.R;
import com.mermergi.ttsrelay.Prefs;
import com.mermergi.ttsrelay.PluginTtsActivity;
import com.mermergi.ttsrelay.RelayService;
import com.mermergi.ttsrelay.Speaker;
import com.mermergi.ttsrelay.TtsEngine;
import com.mermergi.ttsrelay.VoiceListAdapter;
import com.mermergi.ttsrelay.Ui;
import com.mermergi.ttsrelay.VoiceItem;

import java.util.ArrayList;
import java.util.List;

/**
 * 分角色朗读的音色机制：选音色、音色→名称绑定表、试听、解析模式。
 * 朗读页里「分角色」「朗读用户消息」两张卡也是它画的。
 */
public final class VoicePicker {
    private final Ui ui;
    private final Palette p;
    private final Tab.Host host;

    /** 这次开音色库是给「用户消息」挑音色。 */
    private boolean pickUserVoice = false;
    /** 这次开音色库，是为了给新音色填名字（先选音色，再填名字）。 */
    private boolean pickForBind = false;
    private boolean pickForDefault = false;
    /**
     * 「音色 → 名称」那张表里，面板正在编辑的是哪一条：
     * sheetOrig = 这条原来记在哪个音色名下（新加一条时是 null），
     * sheetVoice = 面板上现在选中的音色。换音色时要把<b>这一条</b>搬过去，
     * 不能两个音色各留一条 —— 那才是"新开一条"。
     */
    private String sheetOrig = null;
    private String sheetVoice = null;
    /** 换音色途中面板被关掉了，刚填的名字先存在这儿带过去。 */
    private String bindCarry = null;

    /** 所在 Activity（host.activity() 的缓存别名，视图活着它就活着）。 */
    private Context act() { return host.activity(); }

    public VoicePicker(Ui ui, Palette p, Tab.Host host) {
        this.ui = ui;
        this.p = p;
        this.host = host;
    }

    // ══════════════════════════ 给 SpeakTab 的卡 ══════════════════════════

    /** 「分角色朗读」卡：mvSwitch/modeBtn/roleBox 归这里管。 */
    public LinearLayout buildMultiVoiceCard(Context ctx) {
        LinearLayout mc = ui.card();
        mc.addView(ui.sectionTitle("分角色朗读"));
        mvSwitch = ui.switchBox("启用多语音\n关着就是一个音色念到底");
        mvSwitch.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                if (syncing) return;
                Prefs.setMvEnabled(act(), checked);
                renderVoiceTable();
                host.toast(checked ? "已开启：下一句起按角色换音色" : "已关闭：回到单音色");
            }
        });
        mc.addView(mvSwitch);

        modeBtn = ui.btn("", false, new View.OnClickListener() {
            public void onClick(View v) { pickParseMode(); }
        });
        modeBtn.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        mc.addView(modeBtn, com.mermergi.ttsrelay.ui.Widgets.topGap(ui, 12));

        mc.addView(ui.label("音色 → 名称"));
        roleBox = ui.col();
        mc.addView(roleBox, com.mermergi.ttsrelay.ui.Widgets.topGap(ui, 8));
        mc.addView(ui.btn("＋ 加一个音色", true, new View.OnClickListener() {
            public void onClick(View v) {
                pickUserVoice = false;
                sheetOrig = null;          // 新的一条，跟表里已有的无关
                sheetVoice = null;
                bindCarry = null;
                pickForBind = true;
                fetchVoicesAnd(null);
            }
        }), com.mermergi.ttsrelay.ui.Widgets.topGap(ui, 8));
        renderVoiceTable();
        mc.addView(ui.btn("复制引导提示词", false, new View.OnClickListener() {
            public void onClick(View v) { host.copy(GAL_PROMPT); }
        }), com.mermergi.ttsrelay.ui.Widgets.topGap(ui, 8));
        return mc;
    }

    /** 「朗读用户消息」卡。 */
    public LinearLayout buildUserMsgCard(Context ctx) {
        LinearLayout uc = ui.card();
        uc.addView(ui.sectionTitle("朗读用户消息"));
        speakUserSwitch = ui.switchBox("念我自己发出去的那条");
        speakUserSwitch.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                if (syncing) return;
                Prefs.setSpeakUser(act(), checked);
                renderUserVoice();
            }
        });
        uc.addView(speakUserSwitch);

        LinearLayout ur = ui.row();
        ur.setGravity(Gravity.CENTER_VERTICAL);
        ur.setPadding(0, ui.dp(8), 0, 0);
        userVoiceView = new TextView(act());
        userVoiceView.setTextSize(12.5f);
        userVoiceView.setTextColor(p.textSecondary);
        ur.addView(userVoiceView, ui.weightGap(1f, 8));
        ur.addView(ui.iconChip(R.drawable.ic_notif_play, new View.OnClickListener() {
            public void onClick(View v) { auditionQuiet(userVoiceOf()); }
        }));
        ur.addView(ui.chip("选音色", false, new View.OnClickListener() {
            public void onClick(View v) {
                pickUserVoice = true;
                fetchVoicesAnd("用户消息");
            }
        }));
        uc.addView(ur);
        renderUserVoice();
        return uc;
    }

    // ══════════════════════════ 状态 ══════════════════════════

    private com.google.android.material.materialswitch.MaterialSwitch mvSwitch, speakUserSwitch;
    private TextView modeBtn, userVoiceView;
    private LinearLayout roleBox;
    private boolean syncing = false;

    public void syncInputs() {
        syncing = true;
        if (mvSwitch != null) mvSwitch.setChecked(Prefs.mvEnabled(act()));
        if (speakUserSwitch != null) speakUserSwitch.setChecked(Prefs.speakUser(act()));
        renderUserVoice();
        renderVoiceTable();
        syncing = false;
    }

    public void refresh() {
        if (modeBtn != null) {
            modeBtn.setText("解析模式：" + MultiVoice.MODE_NAMES[Prefs.parseMode(act())] + "  ▸");
        }
    }

    public void clearRefs() {
        mvSwitch = null; speakUserSwitch = null; modeBtn = null; userVoiceView = null; roleBox = null;
    }

    /** 引导提示词 —— 与 MultiVoice 当前的解析/匹配规则一字对齐。 */
    private static final String GAL_PROMPT =
            "# 分角色朗读输出规范\n"
            + "\n## 台词行格式（三种任选，方括号用半角 [ ]）\n"
            + "[角色名]「台词」\n"
            + "[角色名][情感]「台词」\n"
            + "[角色名][情感][场景]「台词」\n"
            + "\n## 铁律\n"
            + "1. 每句台词单开一行，行首第一个 [ ] 里写说话角色名。\n"
            + "2. 角色名必须与绑定表中的名称**逐字一致**，匹配是精确匹配；同一角色全文用同一个名字，禁止变体、绰号、代称。\n"
            + "3. **无标签文本一律按旁白处理**：旁白、动作、心理、环境、过渡描写全部不加任何标签，直接写普通段落。\n"
            + "4. 不继承上一行角色：每个说话的行都必须自带角色名标签；旁白段落之后恢复对话时也要重新加标签。\n"
            + "5. 台词用「」包裹（接受 “”）；台词里不要出现换行。\n"
            + "6. 可以在一行开头连写多个标签指定备选：[角色名][别名]「台词」——角色名不匹配时会依次尝试后面的标签。\n"
            + "\n## 情感标签（可选，最多一个）\n"
            + "温柔 开心 害羞 生气 烦躁 悲伤 心疼 害怕 紧张 厌恶 讽刺 惊讶 平静 无奈 调侃；都不合适就不写。\n"
            + "\n## 示例\n"
            + "[小明][开心]「今天的天气真好呢。」\n"
            + "他悠闲地在公园中漫步，看着来往的人群。\n"
            + "[小红][害羞]「那…要不要一起走？」\n"
            + "\n注意：示例第二行无标签，会作为旁白朗读——这正是要求的行为。";

    public void pickVoice() {
        pickUserVoice = false;
        pickForBind = false;
        pickForDefault = true;     // 和绑定表里的「默认音色」行完全同一条选择逻辑
        fetchVoicesAnd(null);
    }
    private void fetchVoicesAnd(final String role) {
        new Thread(new Runnable() {
            public void run() {
                final List<VoiceItem> names = new ArrayList<VoiceItem>();
                final List<EngineStore.SourceGroup> groups;
                try {
                    // 照它：音色是"所有启用中的引擎"合起来给的（选了音色就等于选了引擎）；
                    // 按来源引擎分组 —— 选音色时左边那一栏就是它
                    groups = EngineStore.voicesByEngine(act());
                    for (EngineStore.SourceGroup g : groups) {
                        for (String v : g.voices) {
                            VoiceItem vi = VoiceItem.parse(v);
                            vi.srcId = g.id;
                            vi.srcLabel = g.label;
                            // 一个音色可以绑多个角色：这里把已经绑的都列出来
                            List<String> roles = EngineStore.rolesUsingVoice(act(), v);
                            StringBuilder rb = new StringBuilder();
                            for (String r : roles) {
                                if (rb.length() > 0) rb.append("、");
                                rb.append(r);
                            }
                            vi.usedBy = rb.length() > 8 ? roles.size() + " 个角色" : rb.toString();
                            names.add(vi);
                        }
                    }
                } catch (Throwable t) {
                    final String m = String.valueOf(t.getMessage());
                    host.runOnUi(new Runnable() {
                        public void run() { host.toast("拉取失败：" + m); }
                    });
                    return;
                }
                host.runOnUi(new Runnable() {
                    public void run() { showVoiceDialog(names, groups, role); }
                });
            }
        }).start();
    }
    private void showVoiceDialog(final List<VoiceItem> all,
                                 final List<EngineStore.SourceGroup> groups, final String role) {
        if (all.isEmpty()) { host.toast("没拿到音色"); return; }
        String who = role == null ? "选择音色" : "给「" + role + "」选音色";
        // 与「编辑插件 TTS」页共用同一个选择音色页面
        VoicePickerSheet.show(act(), ui, p, host.sheetTheme(), who, all, groups,
                new VoiceListAdapter.Listener() {
                    public void onPick(VoiceItem vi) {
                        if (vi == null || vi.header) return;
                        if (pickForDefault) {
                            pickForDefault = false;
                            Prefs.setVoiceEngine(act(), vi.raw,
                                    EngineStore.ownerOfVoice(act(), vi.raw));
                            Prefs.setVoice(act(), vi.raw);
                            auditionQuiet(vi.raw);
                            renderVoiceTable();
                            return;
                        }
                        if (pickUserVoice) {
                            pickUserVoice = false;
                            pickForDefault = false;
                            Prefs.setUserVoice(act(), vi.raw);
                            renderUserVoice();
                            auditionQuiet(vi.raw);
                            return;
                        }
                        if (pickForBind) {
                            pickForBind = false;
                            pickForDefault = false;
                            String carry = bindCarry;
                            bindCarry = null;
                            if (sheetOrig == null) {
                                newBinding(vi.raw);     // ＋ 加一个音色：先选音色 → 紧接着填名字
                            } else {
                                sheetVoice = vi.raw;    // 换音色：还是这一条，只是换个嗓子
                                showBindSheet(carry);
                            }
                            return;
                        }
                        Prefs.setVoiceEngine(act(), vi.raw,
                                EngineStore.ownerOfVoice(act(), vi.raw));
                        Prefs.setVoice(act(), vi.raw);
                        audition(vi.raw);
                        refresh();
                    }

                    public void onAudition(VoiceItem vi) {
                        if (vi == null || vi.header) return;
                        auditionQuiet(vi.raw);
                    }
                });
    }
    private static List<String> splitNames(String s) {
        List<String> out = new ArrayList<String>();
        if (s == null) return out;
        for (String part : s.split("[\\n\\r、，,;；|]+")) {
            String n = part.trim();
            if (n.length() == 0) continue;
            boolean dup = false;
            for (String x : out) if (x.equalsIgnoreCase(n)) { dup = true; break; }
            if (!dup) out.add(n);
        }
        return out;
    }
    private static String joinNames(List<String> names) {
        StringBuilder sb = new StringBuilder();
        for (String n : names) { if (sb.length() > 0) sb.append('\n'); sb.append(n); }
        return sb.toString();
    }
    private void renderSourceRail(final LinearLayout rail, final List<EngineStore.SourceGroup> groups,
                                  final List<VoiceItem> all, final String[] src,
                                  final Runnable refill, final RecyclerView rv) {
        rail.removeAllViews();
        for (final EngineStore.SourceGroup g : groups) {
            if (g.voices.isEmpty()) continue;
            final boolean on = g.id.equals(src[0]);
            TextView c = ui.chip(shortSrc(g.label) + " " + g.voices.size(), on,
                    new View.OnClickListener() {
                        public void onClick(View v) {
                            src[0] = on ? "" : g.id;      // 再点一下 = 不过滤
                            renderSourceRail(rail, groups, all, src, refill, rv);
                            refill.run();
                            rv.scrollToPosition(0);
                        }
                    });
            c.setTextSize(11.5f);
            c.setSingleLine(true);
            c.setEllipsize(android.text.TextUtils.TruncateAt.END);
            c.setPadding(ui.dp(8), ui.dp(7), ui.dp(8), ui.dp(7));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = ui.dp(6);
            rail.addView(c, lp);
        }
    }
    private static String shortSrc(String label) {
        if (label == null) return "?";
        String s = label.trim();
        int i = s.indexOf('（');
        if (i > 0) s = s.substring(0, i);
        s = s.replaceAll("\\s+", "");
        return s.length() > 6 ? s.substring(0, 6) : s;
    }
    private void fillVoiceList(List<VoiceItem> out, List<VoiceItem> all,
                               String kw, String gen, String srcId) {
        out.clear();
        for (VoiceItem v : all) {
            if (gen.length() > 0 && !gen.equals(v.gender)) continue;
            if (srcId.length() > 0 && !srcId.equals(v.srcId)) continue;
            if (kw.length() > 0 && !v.name.contains(kw) && !v.tagLine.contains(kw)
                    && !v.raw.contains(kw)) continue;
            out.add(v);
        }
    }
    private void renderGenderChips(final LinearLayout chips, final String[] gen,
                                   final Runnable refill) {
        chips.removeAllViews();
        final String[] opts = {"女声", "男声", "未标注"};
        for (final String o : opts) {
            final boolean on = o.equals(gen[0]);
            TextView c = ui.chip(o, on, new View.OnClickListener() {
                public void onClick(View v) {
                    gen[0] = on ? "" : o;        // 再点一下 = 不过滤
                    renderGenderChips(chips, gen, refill);
                    refill.run();
                }
            });
            chips.addView(c, ui.weightGap(1f, 8));
        }
    }
    private String userVoiceOf() {
        String v = Prefs.userVoice(act());
        return (v == null || v.length() == 0) ? Prefs.voice(act()) : v;
    }
    private void renderUserVoice() {
        if (userVoiceView == null) return;
        String v = Prefs.userVoice(act());
        boolean own = v != null && v.length() > 0;
        userVoiceView.setText("音色：" + (own ? TtsEngine.shortVoice(v) : "跟随全局"));
        userVoiceView.setTextColor(own ? p.textSecondary : p.textMuted);
    }
    private void renderVoiceTable() {
        if (roleBox == null) return;
        roleBox.removeAllViews();
        java.util.LinkedHashMap<String, String> m = Prefs.voiceNamesMap(act());
        if (m.isEmpty()) {
            roleBox.addView(ui.hint("还没配"));
            return;
        }
        // ── 默认音色行：和角色行同一套选音色逻辑，只是写的是全局默认 ──
        LinearLayout drow = ui.row();
        drow.setGravity(Gravity.CENTER_VERTICAL);
        drow.setPadding(ui.dp(14), ui.dp(11), ui.dp(10), ui.dp(11));
        drow.setBackground(ui.pressable(p.cardHi, p.ripple, 14, p.border));
        drow.setClickable(true);
        drow.setOnClickListener(new View.OnClickListener() {
            public void onClick(View x) {
                pickUserVoice = false;
                pickForBind = false;
                pickForDefault = true;
                fetchVoicesAnd(null);
            }
        });
        LinearLayout dcol = ui.col();
        TextView dv = new TextView(act());
        String cur = Prefs.voiceTableMap(act()).get("默认");
        if (cur == null || cur.trim().length() == 0) cur = Prefs.voice(act());
        dv.setText(TtsEngine.shortVoice(cur));
        dv.setTextColor(p.accent);
        dv.setTextSize(15);
        dv.setTypeface(Typeface.DEFAULT_BOLD);
        dcol.addView(dv);
        drow.addView(dcol, ui.weightGap(1f, 6));

        ImageView dplay = ui.iconChip(R.drawable.ic_notif_play, null);
        dplay.setOnClickListener(new View.OnClickListener() {
            public void onClick(View x) { auditionQuiet(Prefs.voice(act())); }
        });
        LinearLayout.LayoutParams dplp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dplp.rightMargin = ui.dp(8);
        drow.addView(dplay, dplp);

        drow.addView(ui.chip("编辑", false, new View.OnClickListener() {
            public void onClick(View x) { openVoiceEditor(Prefs.voice(act())); }
        }));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = ui.dp(6);
        roleBox.addView(drow, dlp);

        for (final java.util.Map.Entry<String, String> e : m.entrySet()) {
            final String voice = e.getKey();
            LinearLayout row = ui.row();
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(ui.dp(14), ui.dp(11), ui.dp(10), ui.dp(11));
            row.setBackground(ui.pressable(p.cardHi, p.ripple, 14, p.border));
            row.setClickable(true);
            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View x) { bindingSheet(voice); }
            });
            LinearLayout col = ui.col();
            TextView vn = new TextView(act());
            vn.setText(TtsEngine.shortVoice(voice));
            vn.setTextColor(p.accent);
            vn.setTextSize(15);
            vn.setTypeface(Typeface.DEFAULT_BOLD);
            col.addView(vn);
            TextView nn = new TextView(act());
            nn.setText("名称：" + e.getValue().replace("|", " | "));
            nn.setTextColor(p.textSecondary);
            nn.setTextSize(12.5f);
            nn.setPadding(0, ui.dp(3), 0, 0);
            col.addView(nn);
            row.addView(col, ui.weightGap(1f, 6));

            ImageView play = ui.iconChip(R.drawable.ic_notif_play, null);
            play.setOnClickListener(new View.OnClickListener() {
                public void onClick(View x) { auditionQuiet(voice); }
            });
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            plp.rightMargin = ui.dp(8);
            row.addView(play, plp);

            row.addView(ui.chip("编辑", false, new View.OnClickListener() {
                public void onClick(View x) { openVoiceEditor(e.getKey()); }
            }));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = ui.dp(6);
            roleBox.addView(row, lp);
        }
    }
    public void openVoiceEditor(String voice) {
        String owner = EngineStore.ownerOfVoice(act(), voice);
        EngineStore.Item eng = owner == null ? null : EngineStore.get(act(), owner);
        boolean hasPlugin = eng != null
                && (EngineStore.KIND_JS.equals(eng.kind) || eng.s("src", "").trim().length() > 0);
        if (!hasPlugin) { bindingSheet(voice); return; }
        Intent i = new Intent(act(), PluginTtsActivity.class);
        i.putExtra(PluginTtsActivity.EXTRA_ID, eng.id);
        i.putExtra(PluginTtsActivity.EXTRA_VOICE, voice);
        i.putExtra(PluginTtsActivity.EXTRA_NAMES, Prefs.voiceNamesMap(act()).get(voice));
        try { act().startActivity(i); } catch (Throwable t) { host.toast("打不开：" + t.getMessage()); }
    }
    private void bindingSheet(String voice) {
        sheetOrig = voice;                    // 原来是记在这个音色名下的
        sheetVoice = voice;
        showBindSheet(null);
    }
    private void newBinding(String voice) {
        sheetOrig = null;
        sheetVoice = voice;
        showBindSheet(null);
    }
    private void showBindSheet(String carry) {
        final String voice = sheetVoice;
        String cur = (carry != null) ? carry : Prefs.voiceNamesMap(act()).get(voice);
        LinearLayout box = ui.col();
        box.setPadding(ui.dp(18), ui.dp(2), ui.dp(18), ui.dp(22));

        LinearLayout head = ui.row();
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(act());
        title.setText(TtsEngine.shortVoice(voice));
        title.setTextColor(p.textPrimary);
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(title, ui.weightGap(1f, 8));
        head.addView(ui.iconChip(R.drawable.ic_notif_play, new View.OnClickListener() {
            public void onClick(View x) { auditionQuiet(voice); }
        }));
        box.addView(head);

        final EditText names = new EditText(act());
        names.setHint("名称：杨幂|刘亦菲");
        names.setText(cur == null ? "" : cur.replace("|", " | "));
        names.setTextSize(15);
        names.setTextColor(p.textPrimary);
        names.setHintTextColor(p.textMuted);
        names.setBackground(ui.round(p.cardHi, p.border, 12));
        names.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12));
        box.addView(names, ui.topGap(10));

        LinearLayout rr = ui.row();
        rr.addView(ui.btn("保存", true, new View.OnClickListener() {
            public void onClick(View v) {
                java.util.List<String> list = MultiVoice.splitNames(names.getText().toString());
                if (list.isEmpty()) { host.toast("名字不能空着"); return; }
                StringBuilder sb = new StringBuilder();
                for (String n : list) { if (sb.length() > 0) sb.append('|'); sb.append(n); }
                if (sheetOrig != null && !sheetOrig.equals(voice)) {
                    // 换过音色：这一条整体搬过去，旧音色那条不留
                    Prefs.moveVoiceNames(act(), sheetOrig, voice, sb.toString());
                } else {
                    Prefs.putVoiceNames(act(), voice, sb.toString());
                }
                Prefs.setVoiceEngine(act(), voice,
                        EngineStore.ownerOfVoice(act(), voice));
                sheetOrig = voice;
                renderVoiceTable();
                host.dismissSheet();
                host.toast("「" + TtsEngine.shortVoice(voice) + "」→ " + sb);
            }
        }), ui.weightGap(1f, 8));
        rr.addView(ui.btn("换个音色", false, new View.OnClickListener() {
            public void onClick(View v) {
                bindCarry = names.getText().toString();   // 刚填的名字带着走
                host.dismissSheet();
                pickForBind = true;
                fetchVoicesAnd(null);
            }
        }), ui.weightGap(1f, 0));
        box.addView(rr, ui.topGap(12));

        box.addView(ui.btn("删掉这一条", false, new View.OnClickListener() {
            public void onClick(View v) {
                Prefs.putVoiceNames(act(),
                        sheetOrig != null ? sheetOrig : voice, null);
                renderVoiceTable();
                host.dismissSheet();
            }
        }), ui.topGap(8));

        host.sheet(box);
    }
    private LinearLayout spinnerRow(String label, String value, View.OnClickListener click) {
        LinearLayout row = ui.row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(ui.dp(14), ui.dp(10), ui.dp(12), ui.dp(10));
        row.setBackground(ui.pressable(p.cardHi, p.ripple, 12, p.border));
        row.setClickable(true);
        row.setOnClickListener(click);
        LinearLayout col = ui.col();
        TextView lab = new TextView(act());
        lab.setText(label);
        lab.setTextColor(p.textMuted);
        lab.setTextSize(11.5f);
        col.addView(lab);
        TextView val = new TextView(act());
        val.setText(value == null || value.length() == 0 ? "—" : value);
        val.setTextColor(p.textPrimary);
        val.setTextSize(15);
        val.setPadding(0, ui.dp(2), 0, 0);
        col.addView(val);
        row.addView(col, ui.weightGap(1f, 6));
        TextView arrow = new TextView(act());
        arrow.setText("▾");
        arrow.setTextColor(p.textSecondary);
        arrow.setTextSize(14);
        row.addView(arrow);
        return row;
    }
    private LinearLayout auditionField(final String voice) {
        LinearLayout row = ui.row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(ui.dp(14), ui.dp(6), ui.dp(6), ui.dp(6));
        row.setBackground(ui.round(p.cardHi, p.border, 12));

        EditText e = new EditText(act());
        e.setHint("试听文本");
        e.setText(Prefs.tryText(act()));
        e.setTextSize(14);
        e.setTextColor(p.textPrimary);
        e.setHintTextColor(p.textMuted);
        e.setSingleLine(true);
        e.setBackground(null);
        e.setPadding(0, ui.dp(6), 0, ui.dp(6));
        e.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(Editable s) {
                String t = s.toString().trim();
                if (t.length() > 0) Prefs.setTryText(act(), t);
            }
        });
        row.addView(e, ui.weightGap(1f, 6));

        ImageView ear = ui.iconChip(R.drawable.ic_notif_play, null);
        ear.setOnClickListener(new View.OnClickListener() {
            public void onClick(View x) { auditionQuiet(voice); }
        });
        row.addView(ear);
        return row;
    }
    private void auditionQuiet(final String voice) {
        final Speaker spk = RelayService.speakerOrNull();
        if (spk == null) { host.toast("朗读没在跑（先打开中转）"); return; }
        String use = voice;
        if (use == null || use.length() == 0) use = Prefs.voice(act());
        if (use == null || use.length() == 0) { host.toast("还没选音色"); return; }
        Prefs.setVoiceEngine(act(), use, EngineStore.ownerOfVoice(act(), use));
        spk.interrupt();
        spk.feed(Prefs.tryText(act()), use);
    }
    private void audition(final String voice) {
        final Speaker spk = RelayService.speakerOrNull();
        if (spk == null) { host.toast("朗读没在跑（先打开中转）"); return; }
        String use = voice;
        if (use == null || use.length() == 0) use = Prefs.voice(act());
        if (use == null || use.length() == 0) { host.toast("还没选音色"); return; }
        final String text = Prefs.tryText(act());
        Prefs.setVoiceEngine(act(), use, EngineStore.ownerOfVoice(act(), use));

        final AlertDialog dlg = new AlertDialog.Builder(act(), host.dialogTheme())
                .setTitle("试听")
                .setMessage(text + "\n\n— " + TtsEngine.shortVoice(use))
                .setNegativeButton("停止", null)
                .create();
        dlg.show();
        spk.interrupt();
        spk.feed(text, use);
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
            public void run() {
                try { dlg.dismiss(); } catch (Throwable ignored) { }
            }
        }, 2600);
    }
    private void pickParseMode() {
        ChoiceSheet.show(act(), ui, p, host.sheetTheme(), "解析模式", null,
                MultiVoice.MODE_NAMES, MultiVoice.MODE_DESCS, Prefs.parseMode(act()),
                new ChoiceSheet.OnPick() {
                    public void onPick(int which) {
                        Prefs.setParseMode(act(), which);
                        syncInputs();
                    }
                });
    }
}
