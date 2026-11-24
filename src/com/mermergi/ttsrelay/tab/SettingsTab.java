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
    private TextView addrView, lanAddrView;
    private android.widget.EditText tryField;
    private android.widget.CompoundButton lanSwitch;
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
        LinearLayout uc = ui.foldCard(ctx, "上游模型接口");
        LinearLayout foldUc = ui.foldBody;
        foldUc.addView(ui.label("常用上游"));
        HorizontalScrollView hs = new HorizontalScrollView(ctx);
        hs.setHorizontalScrollBarEnabled(false);
        presetRow = ui.row();
        hs.addView(presetRow);
        buildPresetRow(ctx);
        foldUc.addView(hs);

        foldUc.addView(ui.label("上游 API 地址"));
        upstreamField = ui.field("upstream", Prefs.UPSTREAM_DEF, InputType.TYPE_TEXT_VARIATION_URI);
        foldUc.addView(upstreamField);
        foldUc.addView(ui.label("监听端口"));
        portField = ui.field("port", String.valueOf(Prefs.PORT_DEF), InputType.TYPE_CLASS_NUMBER);
        foldUc.addView(portField);
        col.addView(uc);

        // ── TTS ──
        LinearLayout tc = ui.foldCard(ctx, "TTS Server");
        LinearLayout foldTc = ui.foldBody;
        foldTc.addView(ui.label("转发器地址"));
        ttsField = ui.field("tts", Prefs.TTS_BASE_DEF, InputType.TYPE_TEXT_VARIATION_URI);
        foldTc.addView(ttsField);
        foldTc.addView(ui.label("单句最长字数（超了硬切）"));
        maxChunkField = ui.field("maxchunk", String.valueOf(Prefs.MAXCHUNK_DEF),
                InputType.TYPE_CLASS_NUMBER);
        foldTc.addView(maxChunkField);
        col.addView(tc);

        // ═══ 连接信息（原首页地址卡）═══
        LinearLayout conn = ui.foldCard(ctx, "对话软件里填的 API 主机");
        LinearLayout foldConn = ui.foldBody;
        addrView = new android.widget.TextView(ctx);
        addrView.setTextSize(14);
        addrView.setTypeface(android.graphics.Typeface.MONOSPACE);
        foldConn.addView(addrView);
        lanAddrView = new android.widget.TextView(ctx);
        lanAddrView.setTextSize(12);
        lanAddrView.setTypeface(android.graphics.Typeface.MONOSPACE);
        lanAddrView.setTextColor(p.textSecondary);
        lanAddrView.setPadding(0, ui.dp(4), 0, 0);
        foldConn.addView(lanAddrView);
        LinearLayout cab = ui.row();
        cab.addView(ui.btn("复制本机地址", true, new View.OnClickListener() {
            public void onClick(View v) { host.copy("http://127.0.0.1:" + curPort(ctx) + "/v1"); }
        }), ui.weightGap(1f, 8));
        cab.addView(ui.btn("复制局域网地址", false, new View.OnClickListener() {
            public void onClick(View v) {
                String ip = lanIp();
                if (ip == null) { host.toast("没找到局域网 IP"); return; }
                host.copy("http://" + ip + ":" + curPort(ctx) + "/v1");
            }
        }), ui.weight(1f));
        foldConn.addView(cab, Widgets.topGap(ui, 12));
        lanSwitch = ui.switchBox("允许局域网访问");
        lanSwitch.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                Prefs.setLanAllowed(ctx, checked);
                host.toast(checked ? "已开启 —— 注意：局域网里谁都能用你的 key" : "已关闭");
            }
        });
        foldConn.addView(lanSwitch);
        col.addView(conn);

        // ── 跳过规则 ──
        LinearLayout sc = ui.foldCard(ctx, "后台小活判定");
        LinearLayout foldSc = ui.foldBody;
        skipSwitch = ui.switchBox("检测后台小活\n起标题、写摘要这类请求不朗读");
        skipSwitch.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                Prefs.setSkipSmall(ctx, checked);
            }
        });
        foldSc.addView(skipSwitch);
        foldSc.addView(ui.label("额度阈值：输出上限小于它就当小活"));
        smallTokenField = ui.field("smalltoken", String.valueOf(Prefs.SMALL_TOKEN_DEF),
                InputType.TYPE_CLASS_NUMBER);
        foldSc.addView(smallTokenField);
        foldSc.addView(ui.label("关键词（英文逗号分隔，只在 system 提示词里找）"));
        skipKeyField = ui.field("skipkey", Prefs.SKIP_KEYWORDS_DEF, InputType.TYPE_CLASS_TEXT);
        foldSc.addView(skipKeyField);
        col.addView(sc);

        // ── 自动化 ──
        LinearLayout ac = ui.foldCard(ctx, "自动化与外观");
        LinearLayout foldAc = ui.foldBody;
        autoStartSwitch = ui.switchBox("开机自启\n仅在上次是运行状态时拉起");
        autoStartSwitch.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                Prefs.setAutoStart(ctx, checked);
            }
        });
        foldAc.addView(autoStartSwitch);
        themeBtn = ui.btn("", false, new View.OnClickListener() {
            public void onClick(View v) { pickTheme(); }
        });
        foldAc.addView(themeBtn, Widgets.topGap(ui, 14));
        col.addView(ac);

        col.addView(ui.bigBtn("保存设置", true, new View.OnClickListener() {
            public void onClick(View v) {
                saveInputs();
                syncInputs();
                host.toast("已保存（端口 / 上游 / TTS 地址要重启中转生效）");
            }
        }));

        // ═══ 保活 ═══
        LinearLayout kc = ui.foldCard(ctx, "保活");
        LinearLayout foldKc = ui.foldBody;
        foldKc.addView(ui.label(keepAliveText()));
        foldKc.addView(ui.btn("申请电池优化豁免", false, new View.OnClickListener() {
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
        LinearLayout bc = ui.foldCard(ctx, "备份与恢复");
        LinearLayout foldBc = ui.foldBody;
        foldBc.addView(ui.label("全部设置 + 引擎 + 音色表打进一个 JSON。恢复会覆盖现有配置。"));
        LinearLayout brow = ui.row();
        brow.addView(ui.btn("导出备份", false, new View.OnClickListener() {
            public void onClick(View v) { exportBackup(); }
        }), ui.weightGap(1f, 8));
        brow.addView(ui.btn("导入备份", false, new View.OnClickListener() {
            public void onClick(View v) { importBackup(); }
        }), ui.weight(1f));
        foldBc.addView(brow, Widgets.topGap(ui, 8));
        col.addView(bc);

        // ═══ 排障 ═══
        LinearLayout dbg = ui.foldCard(ctx, "排障");
        LinearLayout foldDbg = ui.foldBody;
        LinearLayout dbrow = ui.row();
        dbrow.addView(ui.btn("运行日志", false, new View.OnClickListener() {
            public void onClick(View v) {
                ctx.startActivity(new android.content.Intent(ctx, com.mermergi.ttsrelay.LogActivity.class));
            }
        }), ui.weightGap(1f, 8));
        dbrow.addView(ui.btn("清空日志", false, new View.OnClickListener() {
            public void onClick(View v) {
                com.mermergi.ttsrelay.LogBus.clear();
                host.toast("日志已清空");
            }
        }), ui.weight(1f));
        foldDbg.addView(dbrow, Widgets.topGap(ui, 8));
        foldDbg.addView(ui.label("试听文本（试听和换音色都念这句）"));
        tryField = ui.field("trytext", Prefs.TRY_DEF, android.text.InputType.TYPE_CLASS_TEXT);
        foldDbg.addView(tryField);
        LinearLayout trrow = ui.row();
        trrow.addView(ui.btn("试听", false, new View.OnClickListener() {
            public void onClick(View v) { testSpeak(ctx); }
        }), ui.weightGap(1f, 8));
        trrow.addView(ui.btn("引擎自检", false, new View.OnClickListener() {
            public void onClick(View v) {
                com.mermergi.ttsrelay.ui.Widgets.selfTest(ctx, curPort(ctx));
            }
        }), ui.weight(1f));
        foldDbg.addView(trrow, Widgets.topGap(ui, 8));
        col.addView(dbg);

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

    /** 试听：按当前断句方案切块排队（原声音页逻辑，搬进设置）。 */
    private void testSpeak(Context ctx) {
        com.mermergi.ttsrelay.Speaker spk = com.mermergi.ttsrelay.RelayService.speakerOrNull();
        if (spk == null) { host.toast("先启动中转"); return; }
        if (tryField != null) {
            String v = tryField.getText().toString().trim();
            if (v.length() > 0) Prefs.setTryText(ctx, v);
        }
        String text = Prefs.tryText(ctx);
        com.mermergi.ttsrelay.Splitter sp = new com.mermergi.ttsrelay.Splitter(
                Prefs.maxChunk(ctx), 4, Prefs.splitMode(ctx));
        java.util.List<String> parts = new java.util.ArrayList<String>();
        for (int i = 0; i < text.length(); i++) {
            parts.addAll(sp.feed(String.valueOf(text.charAt(i))));
        }
        parts.addAll(sp.flush());
        for (String ps : parts) spk.feed(ps);
        com.mermergi.ttsrelay.LogBus.add("试听 · 断句方案「"
                + com.mermergi.ttsrelay.Splitter.modeName(Prefs.splitMode(ctx))
                + "」切成 " + parts.size() + " 块：");
        for (String x : parts) com.mermergi.ttsrelay.LogBus.add("   ▸ " + x);
        host.toast("已排队（" + parts.size() + " 块，方案："
                + com.mermergi.ttsrelay.Splitter.modeName(Prefs.splitMode(ctx)) + "）");
    }

    private static int curPort(Context ctx) {
        com.mermergi.ttsrelay.HttpServer srv = com.mermergi.ttsrelay.RelayService.serverOrNull();
        return (srv != null && srv.isRunning()) ? srv.port() : Prefs.port(ctx);
    }

    private static String lanIp() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> ifs =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (ifs != null && ifs.hasMoreElements()) {
                java.net.NetworkInterface ni = ifs.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                java.util.Enumeration<java.net.InetAddress> as = ni.getInetAddresses();
                while (as.hasMoreElements()) {
                    java.net.InetAddress a = as.nextElement();
                    if (a instanceof java.net.Inet4Address && a.isSiteLocalAddress())
                        return a.getHostAddress();
                }
            }
        } catch (Throwable ignored) { }
        return null;
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
        if (tryField != null) tryField.setText(Prefs.tryText(ctx));
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
        Context ctx = host.activity();
        if (themeBtn != null) {
            themeBtn.setText("主题：" + Palette.THEME_NAMES[Prefs.themeMode(ctx)] + "  ▸");
        }
        int port = curPort(ctx);
        if (addrView != null) addrView.setText("http://127.0.0.1:" + port + "/v1");
        if (lanAddrView != null) {
            String ip = lanIp();
            lanAddrView.setText(ip == null
                    ? "（没找到局域网 IP）"
                    : "http://" + ip + ":" + port + "/v1"
                    + (Prefs.lanAllowed(ctx) ? "  · 已开" : "  · 未开"));
        }
        if (lanSwitch != null && lanSwitch.isChecked() != Prefs.lanAllowed(ctx)) {
            lanSwitch.setChecked(Prefs.lanAllowed(ctx));
        }
    }

    @Override
    public void clearRefs() {
        upstreamField = null; portField = null; ttsField = null;
        maxChunkField = null; skipKeyField = null; smallTokenField = null;
        skipSwitch = null; autoStartSwitch = null; themeBtn = null;
        addrView = null; lanAddrView = null; lanSwitch = null; tryField = null;
        presetChips.clear(); presetRow = null;
    }
}
