package com.mermergi.ttsrelay.tab;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.mermergi.ttsrelay.ChoiceSheet;
import com.mermergi.ttsrelay.Palette;
import com.mermergi.ttsrelay.RelayService;
import com.mermergi.ttsrelay.Prefs;
import com.mermergi.ttsrelay.R;
import com.mermergi.ttsrelay.Ui;
import com.mermergi.ttsrelay.ui.Widgets;

import java.util.ArrayList;
import java.util.List;

/** 设置页：上游 / TTS Server / 后台小活判定 / 自动化与外观。 */
public final class SettingsTab implements Tab {
    private final Ui ui;
    private final Palette p;
    private final Tab.Host host;

    private EditText upstreamField, portField, ttsField, maxChunkField, skipKeyField, smallTokenField;
    private com.google.android.material.materialswitch.MaterialSwitch skipSwitch, autoStartSwitch;
    private TextView themeBtn;
    private LinearLayout presetRow;
    private final List<TextView> presetChips = new ArrayList<TextView>();

    /** 常用上游，点一下就填进地址框。 */
    private static final String[][] PRESETS = {
            {"DeepSeek", "https://api.deepseek.com"},
            {"OpenAI", "https://api.openai.com"},
            {"硅基流动", "https://api.siliconflow.cn"},
            {"本地 Ollama", "http://127.0.0.1:11434"},
    };

    public SettingsTab(Ui ui, Palette p, Tab.Host host) {
        this.ui = ui;
        this.p = p;
        this.host = host;
    }

    @Override
    public View build(Context ctx) {
        LinearLayout col = ui.col();

        // ── 上游 ──
        LinearLayout uc = ui.card();
        uc.addView(ui.sectionTitle("上游模型接口"));
        uc.addView(ui.label("常用上游"));
        HorizontalScrollView hs = new HorizontalScrollView(ctx);
        hs.setHorizontalScrollBarEnabled(false);
        presetRow = ui.row();
        hs.addView(presetRow);
        buildPresetRow(ctx);
        uc.addView(hs);

        uc.addView(ui.label("上游 API 地址"));
        upstreamField = ui.field("upstream", Prefs.UPSTREAM_DEF, InputType.TYPE_TEXT_VARIATION_URI);
        uc.addView(upstreamField);
        uc.addView(ui.label("监听端口"));
        portField = ui.field("port", String.valueOf(Prefs.PORT_DEF), InputType.TYPE_CLASS_NUMBER);
        uc.addView(portField);
        col.addView(uc);

        // ── TTS ──
        LinearLayout tc = ui.card();
        tc.addView(ui.sectionTitle("TTS Server"));
        tc.addView(ui.label("转发器地址"));
        ttsField = ui.field("tts", Prefs.TTS_BASE_DEF, InputType.TYPE_TEXT_VARIATION_URI);
        tc.addView(ttsField);
        tc.addView(ui.label("单句最长字数（超了硬切）"));
        maxChunkField = ui.field("maxchunk", String.valueOf(Prefs.MAXCHUNK_DEF),
                InputType.TYPE_CLASS_NUMBER);
        tc.addView(maxChunkField);
        col.addView(tc);

        // ── 跳过规则 ──
        LinearLayout sc = ui.card();
        sc.addView(ui.sectionTitle("后台小活判定"));
        skipSwitch = ui.switchBox("检测后台小活\n起标题、写摘要这类请求不朗读");
        skipSwitch.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                Prefs.setSkipSmall(ctx, checked);
            }
        });
        sc.addView(skipSwitch);
        sc.addView(ui.label("额度阈值：输出上限小于它就当小活"));
        smallTokenField = ui.field("smalltoken", String.valueOf(Prefs.SMALL_TOKEN_DEF),
                InputType.TYPE_CLASS_NUMBER);
        sc.addView(smallTokenField);
        sc.addView(ui.label("关键词（英文逗号分隔，只在 system 提示词里找）"));
        skipKeyField = ui.field("skipkey", Prefs.SKIP_KEYWORDS_DEF, InputType.TYPE_CLASS_TEXT);
        sc.addView(skipKeyField);
        col.addView(sc);

        // ── 自动化 ──
        LinearLayout ac = ui.card();
        ac.addView(ui.sectionTitle("自动化与外观"));
        autoStartSwitch = ui.switchBox("开机自启\n仅在上次是运行状态时拉起");
        autoStartSwitch.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                Prefs.setAutoStart(ctx, checked);
            }
        });
        ac.addView(autoStartSwitch);
        themeBtn = ui.btn("", false, new View.OnClickListener() {
            public void onClick(View v) { pickTheme(); }
        });
        ac.addView(themeBtn, Widgets.topGap(ui, 14));
        col.addView(ac);

        col.addView(ui.bigBtn("保存设置", true, new View.OnClickListener() {
            public void onClick(View v) {
                saveInputs();
                syncInputs();
                host.toast("已保存（端口 / 上游 / TTS 地址要重启中转生效）");
            }
        }));

        // ═══ 保活 ═══
        LinearLayout kc = new LinearLayout(ctx);
        kc.setOrientation(LinearLayout.VERTICAL);
        kc.addView(ui.sectionTitle("保活"));
        kc.addView(ui.label(keepAliveText()));
        kc.addView(ui.btn("申请电池优化豁免", false, new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    ctx.startActivity(new android.content.Intent(
                            android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            android.net.Uri.parse("package:" + ctx.getPackageName())));
                } catch (Throwable t) {
                    host.toast("跳不过去，去系统设置里手动关掉本应用的电池优化");
                }
            }
        }));
        col.addView(kc, Widgets.topGap(ui, 14));

        // ═══ 备份 / 恢复 ═══
        LinearLayout bc = new LinearLayout(ctx);
        bc.setOrientation(LinearLayout.VERTICAL);
        bc.addView(ui.sectionTitle("备份与恢复"));
        bc.addView(ui.label("全部设置 + 引擎 + 音色表打进一个 JSON。恢复会覆盖现有配置。"));
        LinearLayout brow = new LinearLayout(ctx);
        brow.setOrientation(LinearLayout.HORIZONTAL);
        brow.addView(ui.btn("导出备份", false, new View.OnClickListener() {
            public void onClick(View v) { exportBackup(); }
        }));
        TextView gap = new TextView(ctx);
        gap.setMinWidth(ui.dp(10));
        brow.addView(gap);
        brow.addView(ui.btn("导入备份", false, new View.OnClickListener() {
            public void onClick(View v) { importBackup(); }
        }));
        bc.addView(brow, Widgets.topGap(ui, 8));
        col.addView(bc);

        return Widgets.scroll(ctx, ui, col);
    }

    // ══════════════════════════ 常用上游 ══════════════════════════

    /** 重画「常用上游」那一行：内置的 + 自己加的 + 末尾那个「＋ 自定义」。 */
    private void buildPresetRow(final Context ctx) {
        if (presetRow == null) return;
        presetRow.removeAllViews();
        presetChips.clear();
        for (int i = 0; i < PRESETS.length; i++) {
            addPresetChip(ctx, PRESETS[i][0], PRESETS[i][1], false);
        }
        for (String[] e : Prefs.upstreamCustom(ctx)) {
            addPresetChip(ctx, e[0], e[1], true);
        }
        TextView add = ui.chip("＋ 自定义", false, new View.OnClickListener() {
            public void onClick(View v) { customUpstreamSheet(ctx); }
        });
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.rightMargin = ui.dp(8);
        presetRow.addView(add, cp);
        renderPresets();
    }

    private void addPresetChip(final Context ctx, final String name, final String url,
                               final boolean custom) {
        TextView chip = ui.chip(name, false, new View.OnClickListener() {
            public void onClick(View v) {
                if (upstreamField == null) return;
                upstreamField.setText(url);
                saveInputs();
                renderPresets();
            }
        });
        chip.setTag(url);
        if (custom) {                       // 自己加的：长按删掉
            chip.setOnLongClickListener(new View.OnLongClickListener() {
                public boolean onLongClick(View v) {
                    Prefs.removeUpstreamCustom(ctx, url);
                    buildPresetRow(ctx);
                    host.toast("删掉了「" + name + "」");
                    return true;
                }
            });
        }
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.rightMargin = ui.dp(8);
        presetRow.addView(chip, cp);
        presetChips.add(chip);
    }

    /** 自定义上游：起个名字、填地址存下来，就当常用上游用（长按可删）。 */
    private void customUpstreamSheet(final Context ctx) {
        LinearLayout box = ui.col();
        box.setPadding(ui.dp(18), ui.dp(2), ui.dp(18), ui.dp(18));

        TextView title = new TextView(ctx);
        title.setText("自定义接口");
        title.setTextColor(p.textPrimary);
        title.setTextSize(17);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        box.addView(title, ui.topGap(14));

        final EditText name = ui.field(null, "", InputType.TYPE_CLASS_TEXT);
        name.setHint("名字，比如「公司网关」");
        final EditText url = ui.field(null, "", InputType.TYPE_TEXT_VARIATION_URI);
        url.setHint("https://…");
        box.addView(ui.label("名字"), ui.topGap(12));
        box.addView(name);
        box.addView(ui.label("地址"), ui.topGap(10));
        box.addView(url);

        box.addView(ui.btn("保存", true, new View.OnClickListener() {
            public void onClick(View v) {
                String n = name.getText().toString().trim();
                String u = url.getText().toString().trim();
                if (n.length() == 0 || u.length() == 0) { host.toast("名字和地址都要填"); return; }
                if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://" + u;
                Prefs.putUpstreamCustom(ctx, n, u);
                buildPresetRow(ctx);
                if (upstreamField != null) {
                    upstreamField.setText(u);
                    saveInputs();
                    renderPresets();
                }
                host.dismissSheet();
            }
        }), ui.topGap(14));

        List<String[]> have = Prefs.upstreamCustom(ctx);
        if (!have.isEmpty()) {
            box.addView(ui.label("已保存的（点「删」去掉）"), ui.topGap(16));
            for (final String[] e : have) {
                LinearLayout row = ui.row();
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(0, ui.dp(8), 0, 0);
                LinearLayout col = ui.col();
                TextView n = new TextView(ctx);
                n.setText(e[0]);
                n.setTextColor(p.textPrimary);
                n.setTextSize(14);
                TextView u = new TextView(ctx);
                u.setText(e[1]);
                u.setTextColor(p.textMuted);
                u.setTextSize(11.5f);
                col.addView(n);
                col.addView(u);
                row.addView(col, ui.weightGap(1f, 8));
                row.addView(ui.chip("删", false, new View.OnClickListener() {
                    public void onClick(View v) {
                        Prefs.removeUpstreamCustom(ctx, e[1]);
                        host.dismissSheet();
                        customUpstreamSheet(ctx);
                    }
                }));
                box.addView(row);
            }
        }

        host.sheet(box);
    }

    private void renderPresets() {
        if (presetRow == null || upstreamField == null) return;
        String cur = upstreamField.getText().toString().trim();
        for (int i = 0; i < presetRow.getChildCount(); i++) {
            View v = presetRow.getChildAt(i);
            if (!(v instanceof TextView) || v.getTag() == null) continue;
            TextView t = (TextView) v;
            boolean on = String.valueOf(v.getTag()).equals(cur);
            if (on) {
                t.setTextColor(p.onAccent);
                t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                t.setBackground(ui.pressable(p.accent, p.rippleOnAccent, 20, 0));
            } else {
                t.setTextColor(p.textSecondary);
                t.setTypeface(android.graphics.Typeface.DEFAULT);
                t.setBackground(ui.pressable(p.cardHi, p.ripple, 20, p.border));
            }
        }
    }

    private String keepAliveText() {
        android.os.PowerManager pm = (android.os.PowerManager)
                host.activity().getSystemService(Context.POWER_SERVICE);
        boolean ok = pm != null && pm.isIgnoringBatteryOptimizations(host.activity().getPackageName());
        return ok ? "电池优化：已豁免（系统不敢随便杀）"
                  : "电池优化：未豁免（系统闲了会掐掉后台朗读）";
    }

    // ══════════════════════════ 备份 / 恢复 ══════════════════════════

    /** 导出：JSON 丢给系统分享（存文件 / 发给自己 / 云盘都行）。 */
    private void exportBackup() {
        try {
            String json = Prefs.backupJson(host.activity());
            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_SEND);
            i.setType("application/json");
            i.putExtra(android.content.Intent.EXTRA_SUBJECT, "ttsrelay 备份");
            i.putExtra(android.content.Intent.EXTRA_TEXT, json);
            host.activity().startActivity(android.content.Intent.createChooser(i, "导出备份"));
        } catch (Throwable t) {
            host.toast("导出失败：" + t.getMessage());
        }
    }

    /** 导入：粘贴 JSON（从备份文件/消息里复制来），确认后覆盖写回。 */
    private void importBackup() {
        android.content.ClipboardManager cm = (android.content.ClipboardManager)
                host.activity().getSystemService(Context.CLIPBOARD_SERVICE);
        final String clip = cm == null || cm.getPrimaryClip() == null
                ? "" : String.valueOf(cm.getPrimaryClip().getItemAt(0).getText());
        if (!clip.contains("ttsrelay-backup")) {
            host.toast("剪贴板里没有备份 JSON —— 先复制备份文件内容再点导入");
            return;
        }
        new AlertDialog.Builder(host.activity(), host.dialogTheme())
                .setTitle("导入备份")
                .setMessage("将覆盖全部现有配置（含引擎和音色表），确定？")
                .setPositiveButton("覆盖", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        try {
                            int n = Prefs.restoreJson(host.activity(), clip);
                            RelayService svc = RelayService.instance();
                            if (svc != null) svc.configReloaded();
                            host.toast("已恢复 " + n + " 项");
                            syncInputs();
                        } catch (Throwable t) {
                            host.toast("导入失败：" + t.getMessage());
                        }
                    }
                })
                .setNegativeButton("算了", null)
                .show();
    }

    // ══════════════════════════ 主题 ══════════════════════════

    private void pickTheme() {
        ChoiceSheet.show(host.activity(), ui, p, host.sheetTheme(), "主题", null,
                Palette.THEME_NAMES, null, Prefs.themeMode(host.activity()), new ChoiceSheet.OnPick() {
                    public void onPick(int which) {
                        Prefs.setThemeMode(host.activity(), which);
                        host.activity().recreate();
                    }
                });
    }

    // ══════════════════════════ 读写 ══════════════════════════

    @Override
    public void saveInputs() {
        Context ctx = host.activity();
        if (skipKeyField != null) {
            Prefs.setSkipKeywords(ctx, skipKeyField.getText().toString().trim());
        }
        if (upstreamField != null) {
            String v = upstreamField.getText().toString().trim();
            if (v.length() > 0) Prefs.setUpstream(ctx, v);
        }
        if (ttsField != null) {
            String v = ttsField.getText().toString().trim();
            if (v.length() > 0) Prefs.setTtsBase(ctx, v);
        }
        if (portField != null) {
            String v = portField.getText().toString().trim();
            if (v.length() > 0) {
                try { Prefs.setPort(ctx, Integer.parseInt(v)); } catch (Throwable ignored) { }
            }
        }
        if (maxChunkField != null) {
            String v = maxChunkField.getText().toString().trim();
            if (v.length() > 0) {
                try { Prefs.setMaxChunk(ctx, Integer.parseInt(v)); } catch (Throwable ignored) { }
            }
        }
        if (smallTokenField != null) {
            String v = smallTokenField.getText().toString().trim();
            if (v.length() > 0) {
                try { Prefs.setSmallTokenLimit(ctx, Integer.parseInt(v)); } catch (Throwable ignored) { }
            }
        }
    }

    @Override
    public void syncInputs() {
        Context ctx = host.activity();
        if (upstreamField != null) upstreamField.setText(Prefs.upstream(ctx));
        if (ttsField != null) ttsField.setText(Prefs.ttsBase(ctx));
        if (portField != null) portField.setText(String.valueOf(Prefs.port(ctx)));
        if (maxChunkField != null) maxChunkField.setText(String.valueOf(Prefs.maxChunk(ctx)));
        if (skipKeyField != null) skipKeyField.setText(Prefs.skipKeywords(ctx));
        if (smallTokenField != null) smallTokenField.setText(String.valueOf(Prefs.smallTokenLimit(ctx)));
        if (skipSwitch != null) skipSwitch.setChecked(Prefs.skipSmall(ctx));
        if (autoStartSwitch != null) autoStartSwitch.setChecked(Prefs.autoStart(ctx));
        if (themeBtn != null) {
            themeBtn.setText("主题：" + Palette.THEME_NAMES[Prefs.themeMode(ctx)] + "  ▸");
        }
        renderPresets();
    }

    @Override
    public void refresh() {
        if (themeBtn != null) {
            themeBtn.setText("主题：" + Palette.THEME_NAMES[Prefs.themeMode(host.activity())] + "  ▸");
        }
    }

    @Override
    public void clearRefs() {
        upstreamField = null; portField = null; ttsField = null;
        maxChunkField = null; skipKeyField = null; smallTokenField = null;
        skipSwitch = null; autoStartSwitch = null; themeBtn = null;
        presetChips.clear(); presetRow = null;
    }
}
