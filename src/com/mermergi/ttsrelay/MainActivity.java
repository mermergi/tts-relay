package com.mermergi.ttsrelay;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 四页签：首页（状态 · 控制）/ 朗读（音色 · 语调 · 断句）/ 设置 / 日志。
 * 界面全是代码搭的，颜色一律走 Palette —— 换主题只换一个对象，页面一行都不用动。
 */
public class MainActivity extends Activity {

    private static final int REQ_NOTIF = 41;
    private static final String[] TAB_NAME = {"首页", "朗读", "设置", "引擎", "日志"};
    /** 转屏 / 换主题 recreate 之后停在原来那一页。 */
    private static int LAST_TAB = 0;

    private Palette p;
    private Ui ui;

    // ── 骨架 ──
    private FrameLayout content;
    private final TextView[] tabInd = new TextView[5];
    private final TextView[] tabName = new TextView[5];
    private int tab = -1;

    // ── 首页 ──
    private TextView heroDot, heroStatus, heroSub;
    private Button toggleBtn, pauseBtn, muteBtn;
    private final TextView[] stat = new TextView[4];
    private TextView addrView, lanAddrView;
    private Switch lanSwitch;

    // ── 朗读 ──
    private TextView voiceView, rateVal, pitchVal, volVal, previewOut;
    private SeekBar rateBar, pitchBar, volBar;
    private EditText tryField, previewIn;
    private Button splitBtn;

    // ── 设置 ──
    private EditText upstreamField, portField, ttsField, maxChunkField, skipKeyField, smallTokenField;
    private Switch skipSwitch, autoStartSwitch;
    private Switch mvSwitch, speakUserSwitch;
    private TextView userVoiceView;
    /** 这次开音色库是给「用户消息」挑音色。 */
    private boolean pickUserVoice = false;
    private Button modeBtn;
    private LinearLayout roleBox;
    /** 这次开音色库，是为了给新音色填名字（先选音色，再填名字）。 */
    private boolean pickForBind = false;
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
    /** 正在把设置值写回控件（这期间控件的变化不算用户操作）。 */
    private boolean syncing = false;

    // ── 引擎页 ──
    private LinearLayout engineBox;
    private TextView engineNow;
    private String pendingExport = null;
    private static final int REQ_IMPORT = 51;
    private static final int REQ_EXPORT = 52;
    private static final int REQ_LOGIN = 53;
    private String pendingLoginEngineId = null;
    /** 当前开着的「编辑引擎」框。只允许有一个 —— 叠加起来的话，
     *  关掉上面那个就会露出下面那个还挂着旧状态的框，看着就像"没保存"。 */
    private AlertDialog engineDlg = null;
    private Button themeBtn;
    private final List<TextView> presetChips = new ArrayList<TextView>();
    /** 「常用上游」那一行的容器 —— 加了自定义接口要整行重画。 */
    private LinearLayout presetRow;

    // ── 日志 ──
    private TextView logView;
    private int logLen = -1;

    private final Handler tick = new Handler();
    private final Runnable refresher = new Runnable() {
        public void run() {
            refresh();
            tick.postDelayed(this, 1000);
        }
    };

    /** 常用上游，点一下就填进地址框。 */
    private static final String[][] PRESETS = {
            {"DeepSeek", "https://api.deepseek.com"},
            {"OpenAI", "https://api.openai.com"},
            {"硅基流动", "https://api.siliconflow.cn"},
            {"本地 Ollama", "http://127.0.0.1:11434"},
    };

    // ══════════════════════════ 生命周期 ══════════════════════════

    @Override
    protected void onCreate(Bundle b) {
        App.install(this);
        p = Palette.of(this);
        // 先把主题定下来：Material 的控件（底部抽屉 / 输入框 / 按钮）在 inflate 时读的就是它
        setTheme(p.light ? R.style.AppTheme_Light : R.style.AppTheme);
        super.onCreate(b);
        ui = new Ui(this, p);
        applyWindow();
        buildShell();
        if (Build.VERSION.SDK_INT >= 33) {
            try { requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIF); }
            catch (Throwable ignored) { }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 上次是开着的就自动拉起来
        if (Prefs.wanted(this) && !RelayService.isUp()) {
            try { startForegroundService(new Intent(this, RelayService.class)); } catch (Throwable ignored) { }
        }
        tick.removeCallbacks(refresher);
        tick.post(refresher);
        syncInputs();
    }

    @Override
    protected void onPause() {
        super.onPause();
        tick.removeCallbacks(refresher);
        saveInputs();
    }

    @Override
    public void onConfigurationChanged(Configuration nc) {
        super.onConfigurationChanged(nc);
        // 跟随系统主题时，系统切深浅色也要跟着换一套
        recreate();
    }

    // ══════════════════════════ 外壳 ══════════════════════════

    private void applyWindow() {
        Window w = getWindow();
        w.setBackgroundDrawable(new ColorDrawable(p.bg));
        w.setStatusBarColor(p.bg);
        w.setNavigationBarColor(p.bg);
        View d = w.getDecorView();
        int f = d.getSystemUiVisibility();
        if (p.light) {
            f |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 26) f |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        } else {
            f &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            f &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        d.setSystemUiVisibility(f);
    }

    private void buildShell() {
        LinearLayout root = ui.col();
        root.setBackgroundColor(p.bg);

        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(buildTabBar(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(60)));

        setContentView(root);
        switchTab(LAST_TAB);
    }

    private View buildTabBar() {
        LinearLayout wrap = ui.col();
        wrap.setBackgroundColor(p.card);
        View line = new View(this);
        line.setBackgroundColor(p.border);
        wrap.addView(line, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, ui.dp(1))));

        LinearLayout bar = ui.row();
        for (int i = 0; i < 5; i++) {
            final int idx = i;
            LinearLayout item = ui.col();
            item.setGravity(Gravity.CENTER);
            item.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.MATCH_PARENT, 1f));

            TextView ind = new TextView(this);
            ind.setLayoutParams(new LinearLayout.LayoutParams(ui.dp(22), ui.dp(3)));
            ind.setBackground(ui.round(p.accent, 2));

            TextView nm = new TextView(this);
            nm.setText(TAB_NAME[i]);
            nm.setTextSize(12.5f);
            nm.setGravity(Gravity.CENTER);
            nm.setPadding(0, ui.dp(5), 0, 0);

            item.addView(ind);
            item.addView(nm);
            item.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { switchTab(idx); }
            });
            tabInd[i] = ind;
            tabName[i] = nm;
            bar.addView(item);
        }
        wrap.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return wrap;
    }

    private void paintTabs() {
        for (int i = 0; i < 5; i++) {
            boolean on = (i == tab);
            tabInd[i].setBackground(on ? ui.round(p.accent, 2) : ui.round(0x00000000, 2));
            tabName[i].setTextColor(on ? p.accent : p.textMuted);
            tabName[i].setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        }
    }

    private void switchTab(int i) {
        if (i < 0 || i > 4) i = 0;
        if (i == tab) { paintTabs(); return; }
        saveInputs();
        clearRefs();
        tab = i;
        LAST_TAB = i;
        content.removeAllViews();
        View page;
        if (i == 0) page = pageHome();
        else if (i == 1) page = pageSpeak();
        else if (i == 2) page = pageSettings();
        else if (i == 3) page = pageEngine();
        else page = pageLog();
        content.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        paintTabs();
        syncInputs();
        refresh();
    }

    private View scroll(LinearLayout col) {
        col.setPadding(ui.dp(14), ui.dp(16), ui.dp(14), ui.dp(28));
        ScrollView sv = new ScrollView(this);
        sv.setClipToPadding(false);
        sv.addView(col, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return sv;
    }

    private LinearLayout.LayoutParams topGap(int dp) {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        l.topMargin = ui.dp(dp);
        return l;
    }

    /** 切页时先松手，免得还抓着上一页已经拆掉的控件。 */
    private void clearRefs() {
        heroDot = null; heroStatus = null; heroSub = null;
        toggleBtn = null; pauseBtn = null; muteBtn = null;
        stat[0] = null; stat[1] = null; stat[2] = null; stat[3] = null;
        addrView = null; lanAddrView = null; lanSwitch = null;
        voiceView = null; tryField = null;
        rateVal = null; pitchVal = null; volVal = null;
        rateBar = null; pitchBar = null; volBar = null;
        splitBtn = null; previewIn = null; previewOut = null;
        upstreamField = null; portField = null; ttsField = null; maxChunkField = null;
        skipKeyField = null; smallTokenField = null;
        skipSwitch = null; autoStartSwitch = null; themeBtn = null;
        mvSwitch = null; modeBtn = null; roleBox = null;
        speakUserSwitch = null; userVoiceView = null;
        engineBox = null; engineNow = null;
        presetChips.clear(); presetRow = null;
        logView = null;
        logLen = -1;
    }

    // ══════════════════════════ 首页 ══════════════════════════

    private View pageHome() {
        LinearLayout col = ui.col();

        // ── 状态大卡 ──
        LinearLayout hero = ui.col();
        hero.setBackground(ui.gradient(p.accentSoft, p.card, 20));
        hero.setPadding(ui.dp(18), ui.dp(18), ui.dp(18), ui.dp(16));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.bottomMargin = ui.dp(12);
        hero.setLayoutParams(hlp);

        LinearLayout stRow = ui.row();
        stRow.setGravity(Gravity.CENTER_VERTICAL);
        heroDot = new TextView(this);
        heroDot.setText("●");
        heroDot.setTextSize(15);
        stRow.addView(heroDot);
        heroStatus = new TextView(this);
        heroStatus.setTextSize(21);
        heroStatus.setTypeface(Typeface.DEFAULT_BOLD);
        stRow.addView(heroStatus);
        LinearLayout.LayoutParams hsp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hsp.leftMargin = ui.dp(8);
        heroStatus.setLayoutParams(hsp);
        hero.addView(stRow);

        heroSub = new TextView(this);
        heroSub.setTextSize(12);
        heroSub.setTextColor(p.textSecondary);
        heroSub.setLineSpacing(ui.dp(3), 1f);
        heroSub.setPadding(ui.dp(2), ui.dp(6), 0, ui.dp(14));
        hero.addView(heroSub);

        toggleBtn = ui.bigBtn("启动中转", true, new View.OnClickListener() {
            public void onClick(View v) { toggle(); }
        });
        hero.addView(toggleBtn);

        LinearLayout quick = ui.row();
        quick.setLayoutParams(topGap(8));
        pauseBtn = ui.btn("暂停", false, new View.OnClickListener() {
            public void onClick(View v) { togglePause(); }
        });
        muteBtn = ui.btn("静音", false, new View.OnClickListener() {
            public void onClick(View v) { toggleMute(); }
        });
        Button skipB = ui.btn("跳过本句", false, new View.OnClickListener() {
            public void onClick(View v) { skipCurrent(); }
        });
        quick.addView(pauseBtn, ui.weightGap(1f, 8));
        quick.addView(muteBtn, ui.weightGap(1f, 8));
        quick.addView(skipB, ui.weight(1f));
        hero.addView(quick);
        col.addView(hero);

        // ── 统计瓷砖 ──
        LinearLayout stats = ui.row();
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.bottomMargin = ui.dp(12);
        stats.setLayoutParams(slp);
        String[] caps = {"已念句", "字数", "队列", "失败"};
        int[] colors = {p.accent, p.textPrimary, p.warn, p.err};
        for (int i = 0; i < 4; i++) {
            TextView[] out = new TextView[1];
            stats.addView(ui.tile(caps[i], colors[i], out),
                    i < 3 ? ui.weightGap(1f, 8) : ui.weight(1f));
            stat[i] = out[0];
        }
        col.addView(stats);

        // ── 地址卡 ──
        LinearLayout ac = ui.card();
        ac.addView(ui.sectionTitle("对话软件里填的 API 主机"));
        addrView = new TextView(this);
        addrView.setTextSize(14);
        addrView.setTypeface(Typeface.MONOSPACE);
        ac.addView(addrView);
        lanAddrView = new TextView(this);
        lanAddrView.setTextSize(12);
        lanAddrView.setTypeface(Typeface.MONOSPACE);
        lanAddrView.setTextColor(p.textSecondary);
        lanAddrView.setPadding(0, ui.dp(4), 0, 0);
        ac.addView(lanAddrView);

        LinearLayout ab = ui.row();
        ab.setLayoutParams(topGap(12));
        ab.addView(ui.btn("复制本机地址", true, new View.OnClickListener() {
            public void onClick(View v) { copy("http://127.0.0.1:" + curPort() + "/v1"); }
        }), ui.weightGap(1f, 8));
        ab.addView(ui.btn("复制局域网地址", false, new View.OnClickListener() {
            public void onClick(View v) {
                String ip = lanIp();
                if (ip == null) { toast("没找到局域网 IP"); return; }
                copy("http://" + ip + ":" + curPort() + "/v1");
            }
        }), ui.weight(1f));
        ac.addView(ab);

        lanSwitch = ui.switchBox("允许局域网访问");
        lanSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                Prefs.setLanAllowed(MainActivity.this, checked);
                toast(checked ? "已开启 —— 注意：局域网里谁都能用你的 key" : "已关闭");
            }
        });
        ac.addView(lanSwitch);
        col.addView(ac);

        // ── 快捷卡 ──
        LinearLayout tc = ui.card();
        tc.addView(ui.sectionTitle("快捷"));
        LinearLayout tb = ui.row();
        tb.addView(ui.btn("TTS Server", false, new View.OnClickListener() {
            public void onClick(View v) { openTtsServer(); }
        }), ui.weightGap(1f, 8));
        tb.addView(ui.btn("自检", false, new View.OnClickListener() {
            public void onClick(View v) { selfTest(); }
        }), ui.weightGap(1f, 8));
        tb.addView(ui.btn("清空日志", false, new View.OnClickListener() {
            public void onClick(View v) {
                LogBus.clear();
                if (logView != null) { logView.setText(""); logLen = 0; }
                toast("日志已清空");
            }
        }), ui.weight(1f));
        tc.addView(tb);
        col.addView(tc);

        return scroll(col);
    }

    // ══════════════════════════ 朗读 ══════════════════════════

    private View pageSpeak() {
        LinearLayout col = ui.col();

        // ── 音色 ──
        LinearLayout vc = ui.card();
        vc.addView(ui.sectionTitle("音色"));
        voiceView = ui.value("");
        voiceView.setTextColor(p.textSecondary);
        voiceView.setPadding(0, 0, 0, ui.dp(12));
        vc.addView(voiceView);

        LinearLayout vb = ui.row();
        vb.addView(ui.btn("选择音色", true, new View.OnClickListener() {
            public void onClick(View v) { pickVoice(); }
        }), ui.weightGap(1f, 8));
        vb.addView(ui.btn("试听", false, new View.OnClickListener() {
            public void onClick(View v) { testSpeak(); }
        }), ui.weightGap(1f, 8));
        vb.addView(ui.btn("自检", false, new View.OnClickListener() {
            public void onClick(View v) { selfTest(); }
        }), ui.weight(1f));
        vc.addView(vb);

        vc.addView(ui.label("试听文本（试听和换音色都念这句）"));
        tryField = ui.field("trytext", Prefs.TRY_DEF, InputType.TYPE_CLASS_TEXT);
        vc.addView(tryField);
        vc.addView(ui.hint("自检 = 不连模型，直接走一遍断句 / 跳代码块 / 合成 / 播放，用来排除故障。"));
        col.addView(vc);

        // ── 语调 ──
        LinearLayout tc = ui.card();
        tc.addView(ui.sectionTitle("语速 · 音调 · 音量"));
        final Sl s1 = new Sl(), s2 = new Sl(), s3 = new Sl();

        tc.addView(sliderRow("语速", 100, s1, new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar b, int prog, boolean fromUser) {
                if (!fromUser) return;
                String v = toneVal(prog);
                s1.val.setText(v);
                Prefs.setRate(MainActivity.this, v);
            }
            public void onStartTrackingTouch(SeekBar b) { }
            public void onStopTrackingTouch(SeekBar b) {
                LogBus.add("语速 → " + Prefs.rate(MainActivity.this) + "（下一句生效）");
            }
        }));
        tc.addView(sliderRow("音调", 100, s2, new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar b, int prog, boolean fromUser) {
                if (!fromUser) return;
                String v = toneVal(prog);
                s2.val.setText(v);
                Prefs.setPitch(MainActivity.this, v);
            }
            public void onStartTrackingTouch(SeekBar b) { }
            public void onStopTrackingTouch(SeekBar b) {
                LogBus.add("音调 → " + Prefs.pitch(MainActivity.this) + "（下一句生效）");
            }
        }));
        tc.addView(sliderRow("音量", 100, s3, new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar b, int prog, boolean fromUser) {
                if (!fromUser) return;
                float v = prog / 100f;
                s3.val.setText(prog + "%");
                Prefs.setVolume(MainActivity.this, v);
                Speaker spk = RelayService.speakerOrNull();
                if (spk != null) spk.setVolume(v);
            }
            public void onStartTrackingTouch(SeekBar b) { }
            public void onStopTrackingTouch(SeekBar b) { }
        }));
        rateBar = s1.bar; rateVal = s1.val;
        pitchBar = s2.bar; pitchVal = s2.val;
        volBar = s3.bar; volVal = s3.val;
        tc.addView(ui.hint("音量是叠在系统媒体音量上的，立刻生效；语速和音调改动从下一句开始。"));
        col.addView(tc);

        // ── 分角色朗读 ──
        LinearLayout mc = ui.card();
        mc.addView(ui.sectionTitle("分角色朗读"));
        mvSwitch = ui.switchBox("启用多语音\n关着就是一个音色念到底");
        mvSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                if (syncing) return;
                Prefs.setMvEnabled(MainActivity.this, checked);
                renderVoiceTable();
                toast(checked ? "已开启：下一句起按角色换音色" : "已关闭：回到单音色");
            }
        });
        mc.addView(mvSwitch);

        modeBtn = ui.btn("", false, new View.OnClickListener() {
            public void onClick(View v) { pickParseMode(); }
        });
        modeBtn.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        mc.addView(modeBtn, topGap(12));

        mc.addView(ui.label("音色 → 名称"));
        roleBox = ui.col();
        mc.addView(roleBox, topGap(8));
        mc.addView(ui.btn("＋ 加一个音色", true, new View.OnClickListener() {
            public void onClick(View v) {
                pickUserVoice = false;
                sheetOrig = null;          // 新的一条，跟表里已有的无关
                sheetVoice = null;
                bindCarry = null;
                pickForBind = true;
                fetchVoicesAnd(null);
            }
        }), topGap(8));
        renderVoiceTable();
        mc.addView(ui.btn("复制引导提示词（丢给酒馆）", false, new View.OnClickListener() {
            public void onClick(View v) { copy(GAL_PROMPT); }
        }), topGap(8));
        col.addView(mc);

        // ── 朗读用户消息 ──
        LinearLayout uc = ui.card();
        uc.addView(ui.sectionTitle("朗读用户消息"));
        speakUserSwitch = ui.switchBox("念我自己发出去的那条");
        speakUserSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                if (syncing) return;
                Prefs.setSpeakUser(MainActivity.this, checked);
                renderUserVoice();
            }
        });
        uc.addView(speakUserSwitch);

        LinearLayout ur = ui.row();
        ur.setGravity(Gravity.CENTER_VERTICAL);
        ur.setPadding(0, ui.dp(8), 0, 0);
        userVoiceView = new TextView(this);
        userVoiceView.setTextSize(12.5f);
        userVoiceView.setTextColor(p.textSecondary);
        ur.addView(userVoiceView, ui.weightGap(1f, 8));
        ur.addView(ui.chip("🎧", false, new View.OnClickListener() {
            public void onClick(View v) { auditionQuiet(userVoiceOf()); }
        }));
        ur.addView(ui.chip("选音色", false, new View.OnClickListener() {
            public void onClick(View v) {
                pickUserVoice = true;
                fetchVoicesAnd("用户消息");
            }
        }));
        uc.addView(ur);
        col.addView(uc);
        renderUserVoice();

        // ── 断句 ──
        LinearLayout pc = ui.card();
        pc.addView(ui.sectionTitle("断句"));
        splitBtn = ui.btn("", false, new View.OnClickListener() {
            public void onClick(View v) { pickSplitMode(); }
        });
        splitBtn.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        pc.addView(splitBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        pc.addView(ui.label("实时预览：粘一段带标点的文字进去"));
        previewIn = ui.area("把要念的段落贴进来，下面立刻显示会被切成几句…");
        previewIn.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(Editable e) { renderPreview(); }
        });
        pc.addView(previewIn);
        previewOut = new TextView(this);
        previewOut.setTextSize(12);
        previewOut.setTextColor(p.textSecondary);
        previewOut.setLineSpacing(ui.dp(3), 1f);
        previewOut.setPadding(0, ui.dp(12), 0, 0);
        pc.addView(previewOut);
        col.addView(pc);

        return scroll(col);
    }

    private static final class Sl {
        SeekBar bar;
        TextView val;
    }

    private LinearLayout sliderRow(String title, int max, final Sl out,
                                   SeekBar.OnSeekBarChangeListener l) {
        LinearLayout box = ui.col();
        LinearLayout head = ui.row();
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = ui.label(title);
        t.setPadding(0, ui.dp(6), 0, 0);
        head.addView(t, ui.weight(1f));
        TextView val = ui.value("");
        val.setTextSize(12.5f);
        val.setTextColor(p.accent);
        val.setGravity(Gravity.END);
        head.addView(val);
        box.addView(head);

        SeekBar bar = new SeekBar(this);
        bar.setMax(max);
        ui.tint(bar);
        bar.setOnSeekBarChangeListener(l);
        box.addView(bar, topGap(2));

        out.bar = bar;
        out.val = val;
        return box;
    }

    private static final float TONE_LO = 0.5f, TONE_HI = 2.0f;

    private static int toneProg(String s) {
        float v;
        try { v = Float.parseFloat(s); } catch (Throwable t) { v = 1f; }
        if (v < TONE_LO) v = TONE_LO;
        if (v > TONE_HI) v = TONE_HI;
        return Math.round((v - TONE_LO) / (TONE_HI - TONE_LO) * 100f);
    }

    private static String toneVal(int prog) {
        float v = TONE_LO + (TONE_HI - TONE_LO) * (prog / 100f);
        return String.format(Locale.US, "%.2f", v);
    }

    // ══════════════════════════ 设置 ══════════════════════════

    private View pageSettings() {
        LinearLayout col = ui.col();

        // ── 上游 ──
        LinearLayout uc = ui.card();
        uc.addView(ui.sectionTitle("上游模型接口"));
        uc.addView(ui.label("常用上游"));
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        presetRow = ui.row();
        hs.addView(presetRow);
        buildPresetRow();
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
        tc.addView(ui.hint("断句方案在「朗读」页里换，换完立刻生效，不用重启。"));
        col.addView(tc);

        // ── 跳过规则 ──
        LinearLayout sc = ui.card();
        sc.addView(ui.sectionTitle("跳过规则"));
        skipSwitch = ui.switchBox("检测后台小活\n起标题、写摘要这类请求不朗读");
        skipSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                Prefs.setSkipSmall(MainActivity.this, checked);
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
        sc.addView(ui.hint("关键词只在客户端「没设输出额度」时才生效 —— 否则角色卡里一个"
                + "「标题」就能把整条正经回复哑掉。"));
        col.addView(sc);

        // ── 自动化 ──
        LinearLayout ac = ui.card();
        ac.addView(ui.sectionTitle("自动化与外观"));
        autoStartSwitch = ui.switchBox("开机自启\n仅在上次是运行状态时拉起");
        autoStartSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                Prefs.setAutoStart(MainActivity.this, checked);
            }
        });
        ac.addView(autoStartSwitch);
        themeBtn = ui.btn("", false, new View.OnClickListener() {
            public void onClick(View v) { pickTheme(); }
        });
        ac.addView(themeBtn, topGap(14));
        ac.addView(ui.hint("通知栏常驻卡片上还有 暂停 / 静音 / 停止 三个快捷按钮。"));
        col.addView(ac);

        col.addView(ui.bigBtn("保存设置", true, new View.OnClickListener() {
            public void onClick(View v) {
                saveInputs();
                syncInputs();
                toast("已保存（端口 / 上游 / TTS 地址要重启中转生效）");
            }
        }));
        col.addView(ui.hint("改动会随离开页面自动保存，上面这个按钮只是让你确信一下。"));

        return scroll(col);
    }

    /** 重画「常用上游」那一行：内置的 + 自己加的 + 末尾那个「＋ 自定义」。 */
    private void buildPresetRow() {
        if (presetRow == null) return;
        presetRow.removeAllViews();
        presetChips.clear();
        for (int i = 0; i < PRESETS.length; i++) {
            addPresetChip(PRESETS[i][0], PRESETS[i][1], false);
        }
        for (String[] e : Prefs.upstreamCustom(this)) {
            addPresetChip(e[0], e[1], true);
        }
        TextView add = ui.chip("＋ 自定义", false, new View.OnClickListener() {
            public void onClick(View v) { customUpstreamSheet(); }
        });
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.rightMargin = ui.dp(8);
        presetRow.addView(add, cp);
        renderPresets();
    }

    private void addPresetChip(final String name, final String url, final boolean custom) {
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
                    Prefs.removeUpstreamCustom(MainActivity.this, url);
                    buildPresetRow();
                    toast("删掉了「" + name + "」");
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
    private void customUpstreamSheet() {
        LinearLayout box = ui.col();
        box.setPadding(ui.dp(18), ui.dp(2), ui.dp(18), ui.dp(18));

        TextView title = new TextView(this);
        title.setText("自定义接口");
        title.setTextColor(p.textPrimary);
        title.setTextSize(17);
        title.setTypeface(Typeface.DEFAULT_BOLD);
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
                if (n.length() == 0 || u.length() == 0) { toast("名字和地址都要填"); return; }
                if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://" + u;
                Prefs.putUpstreamCustom(MainActivity.this, n, u);
                buildPresetRow();
                if (upstreamField != null) {
                    upstreamField.setText(u);
                    saveInputs();
                    renderPresets();
                }
                dismissSheet();
            }
        }), ui.topGap(14));

        List<String[]> have = Prefs.upstreamCustom(this);
        if (!have.isEmpty()) {
            box.addView(ui.label("已保存的（点「删」去掉）"), ui.topGap(16));
            for (final String[] e : have) {
                LinearLayout row = ui.row();
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(0, ui.dp(8), 0, 0);
                LinearLayout col = ui.col();
                TextView n = new TextView(this);
                n.setText(e[0]);
                n.setTextColor(p.textPrimary);
                n.setTextSize(14);
                TextView u = new TextView(this);
                u.setText(e[1]);
                u.setTextColor(p.textMuted);
                u.setTextSize(11.5f);
                col.addView(n);
                col.addView(u);
                row.addView(col, ui.weightGap(1f, 8));
                row.addView(ui.chip("删", false, new View.OnClickListener() {
                    public void onClick(View v) {
                        Prefs.removeUpstreamCustom(MainActivity.this, e[1]);
                        dismissSheet();
                        customUpstreamSheet();
                    }
                }));
                box.addView(row);
            }
        }

        roleSheet = sheet(box);
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
                t.setTypeface(Typeface.DEFAULT_BOLD);
                t.setBackground(ui.pressable(p.accent, p.rippleOnAccent, 20, 0));
            } else {
                t.setTextColor(p.textSecondary);
                t.setTypeface(Typeface.DEFAULT);
                t.setBackground(ui.pressable(p.cardHi, p.ripple, 20, p.border));
            }
        }
    }

    private int dialogTheme() {
        return p.light ? R.style.AppDialog_Light : R.style.AppDialog;
    }

    private void pickTheme() {
        ChoiceSheet.show(this, ui, p, sheetTheme(), "主题", null,
                Palette.THEME_NAMES, null, Prefs.themeMode(this), new ChoiceSheet.OnPick() {
                    public void onPick(int which) {
                        Prefs.setThemeMode(MainActivity.this, which);
                        recreate();
                    }
                });
    }

    // ══════════════════════════ 引擎 ══════════════════════════

    private View pageEngine() {
        LinearLayout col = ui.col();

        LinearLayout c1 = ui.card();
        c1.addView(ui.sectionTitle("当前语音引擎"));
        engineNow = ui.value("");
        engineNow.setLineSpacing(ui.dp(3), 1f);
        c1.addView(engineNow);
        c1.addView(ui.hint("播放链直接调这个引擎 —— 不再经过 TTS Server，也就不会再被它的崩溃带走。"));
        col.addView(c1);

        LinearLayout c2 = ui.card();
        c2.addView(ui.sectionTitle("引擎列表（点一行改它）"));
        engineBox = ui.col();
        c2.addView(engineBox);
        c2.addView(ui.btn("＋ 添加引擎", false, new View.OnClickListener() {
            public void onClick(View v) { addEngine(); }
        }), topGap(8));
        col.addView(c2);

        LinearLayout c3 = ui.card();
        c3.addView(ui.sectionTitle("测试"));
        c3.addView(ui.btn("用当前引擎念一句", true, new View.OnClickListener() {
            public void onClick(View v) { testEngine(); }
        }));
        c3.addView(ui.hint("失败原因会写进「日志」页，也会打在合成失败那行里。"));
        col.addView(c3);

        LinearLayout c4 = ui.card();
        c4.addView(ui.sectionTitle("导入 / 导出"));
        LinearLayout r1 = ui.row();
        r1.addView(ui.btn("从文件导入", false, new View.OnClickListener() {
            public void onClick(View v) { importFromFile(); }
        }), ui.weightGap(1f, 8));
        r1.addView(ui.btn("从剪贴板导入", false, new View.OnClickListener() {
            public void onClick(View v) { importFromClipboard(); }
        }), ui.weight(1f));
        c4.addView(r1, topGap(8));
        LinearLayout r2 = ui.row();
        r2.addView(ui.btn("导出到文件", false, new View.OnClickListener() {
            public void onClick(View v) { exportToFile(); }
        }), ui.weightGap(1f, 8));
        r2.addView(ui.btn("复制给别人", false, new View.OnClickListener() {
            public void onClick(View v) { exportMenu(); }
        }), ui.weight(1f));
        c4.addView(r2, topGap(8));
        c4.addView(ui.hint("认两种格式：本 App 自己导出的（能原样恢复），"
                + "和阅读（Legado）的朗读引擎 JSON —— 就是圈里互相分享的那种，"
                + "TTS Server 也认。别人的配置直接粘进来就能用。"));
        col.addView(c4);

        return scroll(col);
    }

    // ── 导入 / 导出 ──

    private void importFromFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "text/plain", "*/*"});
        try { startActivityForResult(i, REQ_IMPORT); }
        catch (Throwable t) { toast("打不开文件选择器：" + t.getMessage()); }
    }

    private void importFromClipboard() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || cm.getPrimaryClip() == null
                    || cm.getPrimaryClip().getItemCount() == 0) {
                toast("剪贴板是空的");
                return;
            }
            CharSequence cs = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
            doImport(cs == null ? "" : cs.toString());
        } catch (Throwable t) {
            toast("读剪贴板失败：" + t.getMessage());
        }
    }

    private void doImport(String text) {
        try {
            final List<EngineStore.Item> list = EngineImport.parse(text);
            StringBuilder sb = new StringBuilder();
            for (EngineStore.Item it : list) {
                // 同一个文件导两遍别再堆一份出来：认原 id，认不出就按「同名同类型」认
                if (it.id.length() == 0 || !EngineStore.has(MainActivity.this, it.id)) {
                    EngineStore.Item same = EngineStore.sameLabel(MainActivity.this, it.kind, it.label);
                    if (same != null) {
                        it.id = same.id;
                        String old = same.s("cookie", "");
                        if (old.length() > 0 && it.s("cookie", "").length() == 0) it.put("cookie", old);
                    }
                }
                EngineStore.put(MainActivity.this, it);
                sb.append("· ").append(it.label)
                        .append("  (").append(kindName(it.kind)).append("\n");
            }
            renderEngines();
            new AlertDialog.Builder(this, dialogTheme())
                    .setTitle("导入成功：加了 " + list.size() + " 个引擎")
                    .setMessage(sb.toString().trim()
                            + "\n\n在「引擎列表」里点它 → 设为当前引擎，就能用了。")
                    .setPositiveButton("知道了", null)
                    .show();
        } catch (Throwable t) {
            new AlertDialog.Builder(this, dialogTheme())
                    .setTitle("导入失败")
                    .setMessage(String.valueOf(t.getMessage())
                            + "\n\n支持：本 App 导出的 JSON、阅读(Legado)的朗读引擎 JSON。")
                    .setPositiveButton("知道了", null)
                    .show();
        }
    }

    private void exportMenu() {
        ChoiceSheet.show(this, ui, p, sheetTheme(), "导出 / 分享", null,
                new String[]{"复制本 App 格式", "复制成阅读(Legado)格式", "导出到文件…"},
                new String[]{"换机能原样恢复（含插件脚本）",
                        "别人 / TTS Server 都能直接导入",
                        "存成 .json，自己挑位置"},
                -1, new ChoiceSheet.OnPick() {
                    public void onPick(int which) {
                        if (which == 0) {
                            copy(EngineImport.exportOurs(EngineStore.all(MainActivity.this)));
                        } else if (which == 1) {
                            copy(EngineImport.exportLegado(EngineStore.all(MainActivity.this)));
                        } else {
                            exportToFile();
                        }
                    }
                });
    }

    private void exportToFile() {
        pendingExport = EngineImport.exportOurs(EngineStore.all(this));
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/json");
        i.putExtra(Intent.EXTRA_TITLE, "tts-relay-engines.json");
        try { startActivityForResult(i, REQ_EXPORT); }
        catch (Throwable t) { toast("打不开文件保存器：" + t.getMessage()); }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK) return;

        // 网页登录取回来的是一段字符串，没有文件 Uri ——
        // 千万别拿文件那套 "data.getData()==null 就 return" 的条件把它挡掉（之前就是这么丢的）
        if (req == REQ_PICK_JS) {
            if (res == RESULT_OK && data != null && data.getData() != null && pendingJsPick != null) {
                try {
                    String src = readText(data.getData());
                    if (src == null || src.trim().length() == 0) { toast("文件是空的"); return; }
                    EngineStore.Item e2 = pendingJsPick;
                    e2.kind = EngineStore.KIND_JS;
                    e2.put("src", src);
                    EngineStore.syncJsMeta(this, e2);      // 顺手把它的名字/变量读出来
                    EngineStore.inheritLogin(this, e2);    // 旧引擎里抓过的 Cookie 直接继承
                    com.mermergi.ttsrelay.JsTtsEngine.clearHosts();
                    EngineStore.put(this, e2);
                    renderEngines();
                    toast("已变成 JS 插件：" + e2.label);
                    openPluginEditor(e2, false);
                } catch (Throwable t) {
                    toast("读文件失败：" + t.getMessage());
                } finally {
                    pendingJsPick = null;
                }
            }
            return;
        }
        if (req == REQ_LOGIN) {
            String v = data == null ? null : data.getStringExtra(LoginActivity.RESULT_VALUE);
            if (v == null || v.trim().length() == 0) { toast("没取到内容，先登录再点「取回」"); return; }
            EngineStore.Item it = EngineStore.get(this, pendingLoginEngineId);
            if (it == null) { toast("找不到那个引擎了"); return; }
            String val = v.trim();
            it.put(loginTarget(it), val);
            EngineStore.put(this, it);
            renderEngines();
            LogBus.add("网页登录取回成功，已写入「" + it.label + "」：" + CookieGrab.summary(val));
            toast("已写入「" + it.label + "」\n" + CookieGrab.summary(val));
            // 关键：刚才那个编辑框是登录前开的，里面还挂着旧状态（"未设置"）。
            // 不重开的话，用户看到的就是"没写进去"。
            try { editEngine(it); } catch (Throwable ignored) { }
            return;
        }

        if (data == null || data.getData() == null) return;
        android.net.Uri uri = data.getData();
        try {
            if (req == REQ_IMPORT) {
                java.io.InputStream in = getContentResolver().openInputStream(uri);
                if (in == null) { toast("读不到这个文件"); return; }
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                in.close();
                doImport(new String(bos.toByteArray(), "UTF-8"));
            } else if (req == REQ_EXPORT) {
                java.io.OutputStream os = getContentResolver().openOutputStream(uri);
                if (os == null) { toast("写不进这个位置"); return; }
                os.write(pendingExport.getBytes("UTF-8"));
                os.close();
                toast("已导出");
            }
        } catch (Throwable t) {
            toast("文件操作失败：" + t.getMessage());
        }
    }

    private static String kindName(String kind) {
        if (EngineStore.KIND_JS.equals(kind)) return "JS 插件";
        if (EngineStore.KIND_HTTP.equals(kind)) return "HTTP 接口";
        return "系统语音";
    }

    private boolean metaSyncing = false;

    private void syncJsMetaInBackground() {
        if (metaSyncing) return;
        final java.util.List<EngineStore.Item> todo = new java.util.ArrayList<EngineStore.Item>();
        for (EngineStore.Item it : EngineStore.all(this)) {
            if (!EngineStore.KIND_JS.equals(it.kind)) continue;
            if (it.s("def_vars", "").trim().length() > 0) continue;      // 已经读过了
            if (it.s("src", "").trim().length() == 0) continue;          // 没源码，没啥可读
            todo.add(it);
        }
        if (todo.isEmpty()) return;
        metaSyncing = true;
        new Thread(new Runnable() {
            public void run() {
                boolean changed = false;
                for (EngineStore.Item it : todo) {
                    try { if (EngineStore.syncJsMeta(MainActivity.this, it)) changed = true; }
                    catch (Throwable ignored) { }
                }
                final boolean c = changed;
                runOnUiThread(new Runnable() {
                    public void run() {
                        metaSyncing = false;
                        if (c) refreshEngineRows();
                    }
                });
            }
        }).start();
    }

    // 行的零件都留着引用：后台结果回来时<b>原地改字</b>，不重建视图
    // （重建会把整页高度变来变去，看着就是"乱跳"）
    private final java.util.Map<String, TextView> rowName = new java.util.HashMap<String, TextView>();
    private final java.util.Map<String, TextView> rowSub = new java.util.HashMap<String, TextView>();
    private final java.util.Map<String, TextView> rowNeed = new java.util.HashMap<String, TextView>();

    private void renderEngines() {
        if (engineBox == null) return;
        engineBox.removeAllViews();
        rowName.clear(); rowSub.clear(); rowNeed.clear();
        String active = EngineStore.activeId(this);
        for (final EngineStore.Item it : EngineStore.all(this)) {
            // 一行 = 头像 + 名字/副标题/状态 + 铅笔 + ⋮ —— 照 TTS Server 的插件列表那样
            LinearLayout row = ui.row();
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(ui.dp(10), ui.dp(8), ui.dp(6), ui.dp(8));
            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    EngineStore.setActive(MainActivity.this, it.id);
                    refreshEngineRows();
                    toast("当前引擎：" + it.label);
                }
            });

            // ☑ 启用/禁用 —— 照它插件列表那个勾选框；停用的整行压暗
            final android.widget.CheckBox cb = new android.widget.CheckBox(this);
            cb.setChecked(EngineStore.isEnabled(it));
            cb.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                    EngineStore.setEnabled(MainActivity.this, it, on);
                    renderEngines();
                }
            });
            row.addView(cb, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            if (!EngineStore.isEnabled(it)) row.setAlpha(0.45f);

            TextView av = new TextView(this);
            av.setText(avatarChar(it));
            av.setGravity(Gravity.CENTER);
            av.setTextSize(15);
            av.setTextColor(p.onAccent);
            av.setBackground(ui.round(avatarColor(it), ui.dp(19)));
            row.addView(av, new LinearLayout.LayoutParams(ui.dp(38), ui.dp(38)));

            LinearLayout col = ui.col();
            col.setPadding(ui.dp(10), 0, ui.dp(6), 0);

            TextView t = ui.value(it.label);
            col.addView(t);
            rowName.put(it.id, t);

            TextView sub = ui.hint("");
            col.addView(sub);
            rowSub.put(it.id, sub);

            // 照它那行：插件声明了变量却没填 → 主色提示，点它直接去设置变量
            TextView need = ui.value("请设置变量（API密钥等）▸");
            need.setTextColor(p.accent);
            need.setVisibility(View.GONE);
            need.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { openPluginEditor(it, true); }
            });
            col.addView(need);
            rowNeed.put(it.id, need);

            row.addView(col, ui.weight(1f));

            row.addView(ui.btn("✎", false, new View.OnClickListener() {
                public void onClick(View v) { editEngine(it); }
            }));
            row.addView(ui.btn("⋮", false, new View.OnClickListener() {
                public void onClick(View v) { engineMenu(it); }
            }));

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = ui.dp(8);
            engineBox.addView(row, lp);
        }
        refreshEngineRows();
        syncJsMetaInBackground();
        EngineStore.warmVoices(this);
    }

    /**
     * 只改字、只改可见性 —— 不新增/删除视图，也就不会跳。
     *
     * <b>这里绝不执行插件</b>：它（TTS Server）的插件列表也是这样，只显示已存的数据
     * （名字、作者-版本、要不要设置变量），插件要到真用的时候（试听/合成/编辑）才跑。
     */
    private void refreshEngineRows() {
        String active = EngineStore.activeId(this);
        for (EngineStore.Item it : EngineStore.all(this)) {
            boolean on = it.id.equals(active);
            TextView t = rowName.get(it.id);
            if (t != null) t.setTextColor(on ? p.accent : p.textPrimary);

            TextView sub = rowSub.get(it.id);
            if (sub != null) sub.setText(subtitle(it));

            TextView need = rowNeed.get(it.id);
            if (need != null) need.setVisibility(EngineStore.needsVars(it) ? View.VISIBLE : View.GONE);
        }

        if (engineNow != null) {
            EngineStore.Item it = EngineStore.get(this, active);
            engineNow.setText((it == null ? "（没有）" : it.label)
                    + (it == null ? "" : "  ·  " + kindName(it.kind))
                    + "\n点「试听」验证它现在通不通");
            engineNow.setTextColor(p.textSecondary);
        }
    }

    /** 副标题：插件引擎写「作者 - v版本」，和它那边一样。 */
    private String subtitle(EngineStore.Item it) {
        String author = it.s("author", "");
        int v = it.i("version", -1);
        StringBuilder sb = new StringBuilder();
        if (author.length() > 0) sb.append(author);
        if (v >= 0) sb.append(sb.length() > 0 ? " - v" : "v").append(v);
        if (sb.length() == 0) sb.append(kindName(it.kind));
        else sb.append("  ·  ").append(kindName(it.kind));
        return sb.toString();
    }

    /** 头像里那个字：插件用名字首字，系统引擎用「本」，HTTP 引擎用「H」。 */
    private static String avatarChar(EngineStore.Item it) {
        if (EngineStore.KIND_SYSTEM.equals(it.kind)) return "本";
        if (EngineStore.KIND_JS.equals(it.kind)) {
            String s = it.label == null ? "" : it.label.trim();
            return s.length() == 0 ? "JS" : s.substring(0, 1);
        }
        return "H";
    }

    /** 头像底色：按 id 散开，别都一个色。 */
    private int avatarColor(EngineStore.Item it) {
        int[] cs = {p.accent, p.ok, p.warn, 0xFF7E57C2, 0xFF26A69A, 0xFFEF6C00, 0xFF5C6BC0};
        int h = it.id == null ? 0 : it.id.hashCode();
        return cs[Math.abs(h) % cs.length];
    }

    /** 照它的「导出配置」：把这一个引擎的配置导成 JSON（插件源码 + 变量都在里头）。 */
    private void exportEngineConfig(EngineStore.Item it) {
        try {
            java.util.List<EngineStore.Item> one = new java.util.ArrayList<EngineStore.Item>();
            one.add(it.copy());
            copy(EngineImport.exportOurs(one));
            toast("配置已复制到剪贴板");
        } catch (Throwable t) {
            toast("导出失败：" + t.getMessage());
        }
    }

    private static final int REQ_PICK_JS = 61;
    private EngineStore.Item pendingJsPick;

    /** 挑一份插件 .js（TTS Server 导出来的那种）→ 这个引擎就变成 JS 插件。 */
    private void pickPluginFile(EngineStore.Item it) {
        pendingJsPick = it;
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("*/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        try { startActivityForResult(Intent.createChooser(i, "选插件 .js"), REQ_PICK_JS); }
        catch (Throwable t) { toast("打不开文件选择器：" + t.getMessage()); }
    }

    private String readText(android.net.Uri uri) throws Exception {
        java.io.InputStream in = getContentResolver().openInputStream(uri);
        if (in == null) return null;
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return new String(bos.toByteArray(), "UTF-8");
    }

    private void openPluginEditor(final EngineStore.Item it, final boolean openVars) {
        // 没有插件源码的引擎（系统语音、HTTP 模板）：编辑器只管插件源码，
        // 所以这儿给一条「粘源码转成 JS 插件」的路 —— 照 TTS Server：引擎就是插件。
        if (it != null && it.s("src", "").trim().length() == 0
                && !EngineStore.KIND_JS.equals(it.kind)) {
            new AlertDialog.Builder(this, dialogTheme())
                    .setTitle(it.label)
                    .setMessage("这个引擎没有插件源码 —— 插件编辑器只管源码，所以没得编。\n\n"
                            + "TTS Server 里引擎就是一份 .js：选你导出的那个文件（一般在"
                            + "「内部存储 / 语音引擎 / ttsrv-plugin-*.js」），它立刻变成 JS 插件。")
                    .setPositiveButton("选插件 .js 文件", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) { pickPluginFile(it); }
                    })
                    .setNeutralButton("直接粘贴源码", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) {
                            it.kind = EngineStore.KIND_JS;
                            it.put("src", "");
                            EngineStore.put(MainActivity.this, it);
                            renderEngines();
                            openPluginEditor(it, false);
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        Intent pi = new Intent(this, PluginEditorActivity.class);
        pi.putExtra(PluginEditorActivity.EXTRA_ID, it.id);
        pi.putExtra(PluginEditorActivity.EXTRA_OPEN_VARS, openVars);
        try { startActivity(pi); } catch (Throwable t) { toast("打不开插件编辑器：" + t.getMessage()); }
    }

    private void engineMenu(final EngineStore.Item it) {
        ChoiceSheet.show(this, ui, p, sheetTheme(), it.label, null,
                new String[]{"设为当前引擎", "编辑配置", "设置变量", "插件界面", "导出配置",
                        "清除缓存", "看看音色列表", "测试这个引擎", "删除"},
                null, -1, new ChoiceSheet.OnPick() {
                    public void onPick(int which) {
                        if (which == 0) {
                            EngineStore.setActive(MainActivity.this, it.id);
                            renderEngines();
                        } else if (which == 1) {
                            editEngine(it);
                        } else if (which == 2) {
                            openPluginEditor(it, true);
                        } else if (which == 3) {
                            openVoiceEditor(TtsEngine.shortVoice(it.s("voice", "")));
                        } else if (which == 4) {
                            exportEngineConfig(it);
                        } else if (which == 5) {
                            if (EngineStore.hasCache(MainActivity.this, it)) {
                                EngineStore.clearCache(MainActivity.this, it);
                                toast("缓存清了");
                            } else {
                                toast("这个插件没缓存");
                            }
                        } else if (which == 6) {
                            showEngineVoices(it);
                        } else if (which == 7) {
                            EngineStore.setActive(MainActivity.this, it.id);
                            renderEngines();
                            testEngine();
                        } else if (which == 8) {
                            EngineStore.remove(MainActivity.this, it.id);
                            renderEngines();
                        }
                    }
                });
    }

    /**
     * 「插件 TTS」：照 TTS Server 那个编辑页的<b>加载逻辑</b>搭的，页面不认识任何插件：
     *
     * <ol>
     *   <li>显示名称 / 试听文本 —— 通用字段</li>
     *   <li>语言 ← {@code engine.getLocales()}（问插件的）</li>
     *   <li>声音 ← {@code engine.getVoices(locale)}（换语言就重问）</li>
     *   <li><b>插件自己那块界面</b> ← {@code engine.onLoadData()} + {@code engine.onLoadUI(context, 容器)}，
     *       插件往容器里塞什么就显示什么（JSpinner / JTextInput / JSeekBar）</li>
     *   <li>语速 / 音量 / 音高 —— 这个引擎自己的，0 = 跟随全局</li>
     * </ol>
     *
     * 换声音之后要调 {@code onVoiceChanged(locale, voice)}，插件会把它自己那坨控件跟着更新。
     */
    private void pluginSheet(final EngineStore.Item it) {
        if (!EngineStore.KIND_JS.equals(it.kind) && it.s("src", "").trim().length() == 0) {
            toast("这个引擎没有插件界面");
            return;
        }
        final LinearLayout box = ui.col();
        box.setBackground(ui.sheetBg());
        box.setPadding(ui.dp(16), 0, ui.dp(16), ui.dp(16));

        ImageView handle = new ImageView(this);
        handle.setImageResource(R.drawable.mtrl_bottomsheet_drag_handle);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(ui.dp(32), ui.dp(4));
        hlp.gravity = Gravity.CENTER_HORIZONTAL;
        hlp.topMargin = ui.dp(10);
        box.addView(handle, hlp);

        final EditText nameBox = ui.field(null, it.label == null ? "" : it.label, InputType.TYPE_CLASS_TEXT);
        nameBox.setHint("显示名称");
        box.addView(nameBox, ui.topGap(12));

        final EditText tryBox = ui.field(null, Prefs.tryText(this), InputType.TYPE_CLASS_TEXT);
        tryBox.setHint("试听文本");
        final String[] curVoice = new String[]{it.s("voice", "")};
        LinearLayout tr = ui.row();
        tr.setGravity(Gravity.CENTER_VERTICAL);
        tr.addView(tryBox, ui.weightGap(1f, 8));
        tr.addView(ui.chip("🎧", false, new View.OnClickListener() {
            public void onClick(View v) {
                String t = tryBox.getText().toString().trim();
                if (t.length() == 0) return;
                Prefs.setTryText(MainActivity.this, t);
                Speaker spk = RelayService.speakerOrNull();
                if (spk == null) { toast("朗读没在跑"); return; }
                spk.interrupt();
                spk.feed(t, TtsEngine.shortVoice(curVoice[0]));
            }
        }));
        box.addView(tr, ui.topGap(10));

        // ── 语言 / 声音：选项都是问插件要的 ──
        final TextView langVal = new TextView(this);
        final TextView voiceVal = new TextView(this);
        final String[] lang = new String[]{it.s("lang", "zh-CN")};
        langVal.setText(lang[0]);
        voiceVal.setText(TtsEngine.shortVoice(curVoice[0]));
        final LinearLayout langRow = kvRow("语言", langVal);
        final LinearLayout voiceRow = kvRow("声音", voiceVal);
        box.addView(langRow, ui.topGap(10));
        box.addView(voiceRow, ui.topGap(8));

        // ── 插件自己画的界面挂这儿 ──
        final LinearLayout host = ui.col();
        box.addView(host, ui.topGap(10));

        // ── 这个引擎自己的语速 / 音量 / 音高 ──
        box.addView(paramSlider("语速", "speed", it), ui.topGap(6));
        box.addView(paramSlider("音量", "vol", it));
        box.addView(paramSlider("音高", "pitch_", it));

        box.addView(ui.btn("保存", true, new View.OnClickListener() {
            public void onClick(View v) {
                String nm = nameBox.getText().toString().trim();
                it.label = nm.length() == 0 ? "插件 TTS" : nm;
                it.put("lang", lang[0]);
                it.put("voice", curVoice[0]);
                EngineStore.put(MainActivity.this, it);
                JsTtsEngine.clearVoices();
                renderEngines();
                dismissSheet();
            }
        }), ui.topGap(16));

        roleSheet = sheet(box);

        // 起插件慢（要 eval 整个脚本），后台跑完再填
        new Thread(new Runnable() {
            public void run() {
                final com.mermergi.ttsrelay.js.PluginBridge h =
                        JsTtsEngine.sharedBridge(MainActivity.this, it);
                if (h == null) return;
                final java.util.LinkedHashMap<String, String> locs;
                final java.util.List<String[]> vs;
                try {
                    locs = new java.util.LinkedHashMap<String, String>(h.locales());
                    vs = h.voices(lang[0]);
                } catch (Throwable t) {
                    return;
                }
                runOnUiThread(new Runnable() {
                    public void run() {
                        bindLangRow(langRow, locs, lang, langVal, voiceRow, voiceVal, curVoice, h, it);
                        bindVoiceRow(voiceRow, vs, curVoice, voiceVal, lang, h, it);
                        try {
                            android.view.View v = h.getUiContainer();
                            if (v != null) {
                                android.view.ViewGroup par = (android.view.ViewGroup) v.getParent();
                                if (par != null) par.removeView(v);
                                host.removeAllViews();
                                host.addView(v, new LinearLayout.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.WRAP_CONTENT));
                            }
                        } catch (Throwable ignored) { }
                    }
                });
            }
        }).start();
    }

    /** 语言下拉：选项来自插件 getLocales()；换语言要重新问一遍声音列表。 */
    private void bindLangRow(final LinearLayout row, final java.util.LinkedHashMap<String, String> locs,
                             final String[] lang, final TextView langVal,
                             final LinearLayout voiceRow, final TextView voiceVal,
                             final String[] curVoice, final com.mermergi.ttsrelay.js.PluginBridge h,
                             final EngineStore.Item it) {
        if (locs.isEmpty()) return;
        row.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                final String[] codes = locs.keySet().toArray(new String[0]);
                String[] names = locs.values().toArray(new String[0]);
                int cur = 0;
                for (int i = 0; i < codes.length; i++) if (codes[i].equals(lang[0])) cur = i;
                ChoiceSheet.show(MainActivity.this, ui, p, sheetTheme(), "语言", null,
                        names, null, cur, new ChoiceSheet.OnPick() {
                            public void onPick(int i) {
                                lang[0] = codes[i];
                                langVal.setText(locs.get(codes[i]));
                                new Thread(new Runnable() {
                                    public void run() {
                                        final java.util.List<String[]> nv;
                                        try { nv = h.voices(lang[0]); } catch (Throwable t) { return; }
                                        runOnUiThread(new Runnable() {
                                            public void run() {
                                                bindVoiceRow(voiceRow, nv, curVoice, voiceVal, lang, h, it);
                                                if (!nv.isEmpty()) {
                                                    curVoice[0] = nv.get(0)[1];
                                                    voiceVal.setText(nv.get(0)[0]);
                                                }
                                            }
                                        });
                                    }
                                }).start();
                            }
                        });
            }
        });
    }

    /** 声音下拉：选项来自插件 getVoices(locale)；选完通知插件换声音。 */
    private void bindVoiceRow(final LinearLayout row, final java.util.List<String[]> vs,
                              final String[] curVoice, final TextView voiceVal,
                              final String[] lang, final com.mermergi.ttsrelay.js.PluginBridge h,
                              final EngineStore.Item it) {
        if (vs == null || vs.isEmpty()) return;
        row.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String[] names = new String[vs.size()];
                int cur = 0;
                for (int i = 0; i < vs.size(); i++) {
                    names[i] = vs.get(i)[0];
                    if (vs.get(i)[1].equals(curVoice[0])) cur = i;
                }
                ChoiceSheet.show(MainActivity.this, ui, p, sheetTheme(), "声音", null,
                        names, null, cur, new ChoiceSheet.OnPick() {
                            public void onPick(int i) {
                                curVoice[0] = vs.get(i)[1];
                                voiceVal.setText(vs.get(i)[0]);
                                try { h.onVoiceChanged(lang[0], curVoice[0]); } catch (Throwable ignored) { }
                            }
                        });
            }
        });
    }

    /** 一行「名字 ▸ 值」（插件页里的下拉）。 */
    private LinearLayout kvRow(String label, TextView val) {
        LinearLayout row = ui.row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(ui.dp(14), ui.dp(12), ui.dp(12), ui.dp(12));
        row.setBackground(ui.pressable(p.cardHi, p.ripple, 12, p.border));
        row.setClickable(true);
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(p.textMuted);
        l.setTextSize(12.5f);
        row.addView(l);
        val.setTextSize(14);
        val.setTextColor(p.textPrimary);
        val.setPadding(ui.dp(12), 0, 0, 0);
        val.setSingleLine(true);
        val.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(val, ui.weight(1f));
        TextView arrow = new TextView(this);
        arrow.setText("▾");
        arrow.setTextColor(p.textSecondary);
        arrow.setTextSize(14);
        row.addView(arrow);
        return row;
    }

    /** 这个引擎自己的语速/音量/音高（0 = 跟随全局，照 TTS Server 的 model）。 */
    private LinearLayout paramSlider(final String label, final String key, final EngineStore.Item it) {
        LinearLayout box = ui.col();
        box.setPadding(0, ui.dp(8), 0, 0);
        LinearLayout head = ui.row();
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(p.textSecondary);
        l.setTextSize(12.5f);
        head.addView(l, ui.weight(1f));
        final TextView val = new TextView(this);
        val.setTextColor(p.accent);
        val.setTextSize(12.5f);
        head.addView(val);
        box.addView(head);

        final SeekBar bar = new SeekBar(this);
        bar.setMax(300);
        float cur = 0f;
        try { cur = Float.parseFloat(it.s(key, "")); } catch (Throwable ignored) { }
        final int init = Math.round(cur * 100f);
        bar.setProgress(init);
        val.setText(init == 0 ? "跟随全局" : String.format(Locale.US, "%.2f", init / 100f));
        ui.tint(bar);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar b, int prog, boolean fromUser) {
                if (!fromUser) return;
                val.setText(prog == 0 ? "跟随全局" : String.format(Locale.US, "%.2f", prog / 100f));
            }
            public void onStartTrackingTouch(SeekBar b) { }
            public void onStopTrackingTouch(SeekBar b) {
                int prog = b.getProgress();
                it.put(key, prog == 0 ? "" : String.format(Locale.US, "%.2f", prog / 100f));
            }
        });
        box.addView(bar);
        return box;
    }

    private void addEngine() {
        ChoiceSheet.show(this, ui, p, sheetTheme(), "添加引擎", null,
                new String[]{"系统语音", "HTTP 接口", "TTS Server 的 JS 插件"},
                new String[]{"本地离线，系统装了什么用什么",
                        "任意云服务，自己拼 URL",
                        "TTS Server 那套插件脚本"},
                -1, new ChoiceSheet.OnPick() {
                    public void onPick(int which) {
                        EngineStore.Item it = new EngineStore.Item();
                        if (which == 0) {
                            it.kind = EngineStore.KIND_SYSTEM;
                            it.label = "系统语音";
                            it.put("pkg", "");
                            it.put("lang", "zh-CN");
                        } else if (which == 1) {
                            it.kind = EngineStore.KIND_HTTP;
                            it.label = "HTTP 引擎";
                            it.put("url", "https://example.com/tts?text={{text}}&voice={{voice}}");
                            it.put("method", "GET");
                            it.put("headers", "");
                            it.put("body", "");
                            it.put("timeout", 60000);
                            it.put("json_key", "");
                            it.put("voices", "");
                        } else if (which == 2) {
                            it.kind = EngineStore.KIND_JS;
                            it.label = "JS 插件";
                            it.put("src", "");
                            it.put("lang", "zh-CN");
                            it.put("headers", "");
                            it.put("voices", "");
                        }
                        it.id = EngineStore.newId(it.kind);
                        EngineStore.put(MainActivity.this, it);
                        renderEngines();
                        editEngine(it);
                    }
                });
    }

    // ── 配置表单 ──

    private static final class Fld {
        String key;
        EditText box;
    }

    /** spec 每行：{配置键, 显示名, 当前值, "single"|"multi"} */
    private List<Fld> buildForm(LinearLayout box, String[][] spec) {
        List<Fld> out = new ArrayList<Fld>();
        for (String[] row : spec) {
            boolean multi = row.length > 3 && "multi".equals(row[3]);
            box.addView(ui.label(row[1]));
            EditText e;
            if (multi) {
                e = ui.area("");
                e.setText(row[2] == null ? "" : row[2]);
                e.setMinLines(3);
                e.setMaxLines(5);          // 长内容（headers 之类）别把整屏撑爆
            } else {
                e = ui.field(null, row[2] == null ? "" : row[2], InputType.TYPE_CLASS_TEXT);
            }
            e.setTag(null);
            box.addView(e);
            Fld f = new Fld();
            f.key = row[0];
            f.box = e;
            out.add(f);
        }
        return out;
    }

    private void editEngine(final EngineStore.Item it) {
        // 所有引擎都走整屏编辑器（JS 那条是代码页，其余是参数页）—— 不再用老表单
        if (it != null) {
            openPluginEditor(it, false);
            return;
        }
        // 先关掉上一个，别叠罗汉 —— 关掉新框露出旧框，看起来就跟没保存一样
        if (engineDlg != null) {
            try { engineDlg.dismiss(); } catch (Throwable ignored) { }
            engineDlg = null;
        }
        LinearLayout box = ui.col();
        box.setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(4));
        box.addView(ui.label("引擎名字"));
        final EditText nameBox = ui.field(null, it.label, InputType.TYPE_CLASS_TEXT);
        nameBox.setTag(null);
        box.addView(nameBox);

        final List<String[]> spec = new ArrayList<String[]>();
        if (EngineStore.KIND_HTTP.equals(it.kind)) {
            spec.add(new String[]{"url", "接口地址模板（{{text}} {{voice}} {{rate}} {{pitch}} {{volume}}）",
                    it.s("url", ""), "multi"});
            spec.add(new String[]{"method", "请求方法 GET / POST", it.s("method", "GET"), "single"});
            spec.add(new String[]{"headers", "请求头（每行 Key: Value）", it.s("headers", ""), "multi"});
            spec.add(new String[]{"body", "POST 正文模板", it.s("body", ""), "multi"});
            spec.add(new String[]{"json_key", "从 JSON 取音频的字段（留空=响应本身就是音频）",
                    it.s("json_key", ""), "single"});
            spec.add(new String[]{"timeout", "超时毫秒", String.valueOf(it.i("timeout", 60000)), "single"});
        } else if (EngineStore.KIND_JS.equals(it.kind)) {
            // 打开编辑框就先从插件源码里把它的变量声明读回来 ——
            // 老引擎（import 那会儿还没读懂 vars 的）也不用重新导入，它的「API密钥」会自己冒出来
            EngineStore.syncJsMeta(this, it);

            spec.add(new String[]{"src", "插件源码（TTS Server 那个 .js，整份贴进来）",
                    it.s("src", ""), "multi"});
            spec.add(new String[]{"lang", "语言（插件 getLocales() 里的取值，比如 zh-CN）",
                    it.s("lang", "zh-CN"), "single"});

            // 插件自己声明的变量 —— 显示名用它引擎文件里的 name，提示用 hint
            java.util.Set<String> shown = new java.util.HashSet<String>();
            try {
                org.json.JSONObject lb = new org.json.JSONObject(it.s("var_labels", ""));
                for (java.util.Iterator<String> ki = lb.keys(); ki.hasNext(); ) {
                    String k = ki.next();
                    if (shown.add(k)) spec.add(new String[]{k, lb.optString(k, k), it.s(k, ""), "single"});
                }
            } catch (Throwable ignored) { }
            for (java.util.Iterator<String> ki = it.cfg.keys(); ki.hasNext(); ) {
                String k = ki.next();
                if (JS_HIDE.contains(k)) continue;
                if (shown.add(k)) spec.add(new String[]{k, "插件变量：" + k, it.s(k, ""), "single"});
            }

        } else {
            spec.add(new String[]{"pkg", "系统引擎包名（留空=手机默认那个）", it.s("pkg", ""), "single"});
            spec.add(new String[]{"lang", "语言", it.s("lang", "zh-CN"), "single"});
        }

        // 音色目录（手工 + 远程 JSON）—— 这是给 HTTP 模板引擎用的；插件引擎的音色来自插件自己
        if (!EngineStore.KIND_JS.equals(it.kind)) {
        spec.add(new String[]{"voices_url", "音色目录地址（GET 一个 JSON；留空=不拉）",
                it.s("voices_url", ""), "multi"});
        spec.add(new String[]{"voices_path", "JSON 路径，如 data.list（留空=响应本身就是数组）",
                it.s("voices_path", ""), "single"});
        spec.add(new String[]{"voices_name", "显示名取哪个字段", it.s("voices_name", "name"), "single"});
        spec.add(new String[]{"voices_id", "取值取哪个字段（留空=同显示名）", it.s("voices_id", ""), "single"});
        spec.add(new String[]{"voices", "手工音色列表（每行一个）", it.s("voices", ""), "multi"});
        }

        java.util.Set<String> used = new java.util.HashSet<String>();
        for (String[] r : spec) used.add(r[0]);
        java.util.List<String> extra = new ArrayList<String>();
        for (java.util.Iterator<String> ki = it.cfg.keys(); ki.hasNext(); ) {
            String k = ki.next();
            if (used.contains(k) || "cookie".equals(k)) continue;
            if (EngineStore.KIND_JS.equals(it.kind) && JS_HIDE.contains(k)) continue;
            extra.add(k);
            spec.add(new String[]{k, "自定义参数：" + k, it.s(k, ""), "single"});
        }
        final List<String> extraKeys = extra;

        final List<Fld> flds = buildForm(box, spec.toArray(new String[0][]));

        // Cookie 单独一块：它动辄上千字符，塞进表单里没法看，所以只显示状态
        final String loginKey = loginTarget(it);
        // 引擎只有"真的声明了 loginUrl"才给登录框
        if (loginKey != null && loginKey.length() > 0 && it.cfg.has(loginKey)) {
            LinearLayout ck = ui.card();
            ck.addView(ui.sectionTitle("Cookie"));
            final TextView st = ui.value("");
            // 状态跟着数据走：取回、改完、清完都当场刷，不用关框重进
            final Runnable refresh = new Runnable() {
                public void run() {
                    String cur = it.s(loginKey, "");
                    st.setText(cur.length() == 0
                            ? "未设置 —— 点「网页登录」取一个，或「查看/修改」自己粘一个"
                            : CookieGrab.summary(cur));
                    st.setTextColor(cur.length() == 0 ? p.warn : p.ok);
                }
            };
            refresh.run();
            ck.addView(st);

            if (it.s("login_url", "").length() > 0) {
                ck.addView(ui.btn("网页登录，自动抓 Cookie", true, new View.OnClickListener() {
                    public void onClick(View v) {
                        applyEditor(it, nameBox, flds, extraKeys);
                        pendingLoginEngineId = it.id;
                        Intent i = new Intent(MainActivity.this, LoginActivity.class);
                        i.putExtra(LoginActivity.EXTRA_URL,
                                it.s("login_url", "about:blank"));
                        i.putExtra(LoginActivity.EXTRA_BINDING, it.s("binding", "cookies"));
                        i.putExtra(LoginActivity.EXTRA_UA, it.s("ua", "mobile"));
                        i.putExtra(LoginActivity.EXTRA_TITLE,
                                "登录完点右上角「取回」（绑定：" + it.s("binding", "cookies") + "）");
                        try { startActivityForResult(i, REQ_LOGIN); }
                        catch (Throwable t) { toast("打不开登录页：" + t.getMessage()); }
                    }
                }), topGap(10));
            }
            LinearLayout ckRow = ui.row();
            ckRow.addView(ui.btn("查看/修改", false, new View.OnClickListener() {
                public void onClick(View v) { editCookie(it, refresh); }
            }), ui.weightGap(1f, 8));
            ckRow.addView(ui.btn("清空", false, new View.OnClickListener() {
                public void onClick(View v) {
                    it.put(loginKey, "");
                    EngineStore.put(MainActivity.this, it);
                            refresh.run();
                    renderEngines();
                    toast("已清空");
                }
            }), ui.weight(1f));
            ck.addView(ckRow, topGap(8));
            box.addView(ck);
        }

        LinearLayout act = ui.row();
        act.addView(ui.btn("＋ 加一个键", false, new View.OnClickListener() {
            public void onClick(View v) { addCfgKey(it); }
        }), ui.weightGap(1f, 8));
        act.addView(ui.btn("看看音色", false, new View.OnClickListener() {
            public void onClick(View v) { showEngineVoices(it); }
        }), ui.weight(1f));
        box.addView(act, topGap(8));
        box.addView(ui.hint("引擎配置就是一张键值表：上面是「约定键」，想加自己的参数点「＋ 加一个键」。"));

        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        engineDlg = new AlertDialog.Builder(this, dialogTheme())
                .setTitle("编辑：" + kindName(it.kind))
                .setView(sv)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        applyEditor(it, nameBox, flds, extraKeys);
                        toast("已保存");
                    }
                })
                .setNeutralButton("测一下", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        EngineStore.setActive(MainActivity.this, it.id);
                        renderEngines();
                        testEngine();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 读剪贴板，读不到就空串。 */
    private String readClip() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || cm.getPrimaryClip() == null
                    || cm.getPrimaryClip().getItemCount() == 0) return "";
            CharSequence cs = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
            return cs == null ? "" : cs.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 登录值单独编辑：它太长，放表单里会把整屏撑爆，所以丢进自己的对话框。
     *
     * 只有一个「粘贴」按钮 —— 粘的是裸串就原样收，是 cURL / 请求头就自己摘出来，
     * 所以没必要让用户先自己选"智能提取"还是"原样粘贴"。保存时再整理一次兜底。
     */
    /**
     * 登录抓回来的值写进哪个配置键：JS 插件写它声明的那个变量（和 TTS Server 一样），
     * 登录拿到的凭证写进哪儿。
     */
    private static String loginTarget(EngineStore.Item it) {
        String k = it.s("login_key", "");
        if (k.length() > 0) return k;
        return "cred";
    }

    private void editCookie(final EngineStore.Item it, final Runnable onChange) {
        final String key = loginTarget(it);
        LinearLayout box = ui.col();
        box.setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(4));
        box.addView(ui.label("Cookie（多长都行，框里能滚）"));
        final EditText e = ui.area("");
        e.setText(it.s(key, ""));
        e.setMinLines(5);
        e.setMaxLines(9);
        box.addView(e);
        box.addView(ui.btn("从剪贴板粘贴", true, new View.OnClickListener() {
            public void onClick(View v) {
                String raw = readClip();
                if (raw.trim().length() == 0) { toast("剪贴板是空的"); return; }
                String got = CookieGrab.tidy(raw);
                if (got.length() == 0) { toast("剪贴板是空的"); return; }
                e.setText(got);
                e.setSelection(e.getText().length());
                toast((got.equals(raw.trim()) ? "已粘贴（原样）\n" : "已粘贴（摘出 Cookie）\n")
                        + CookieGrab.summary(got));
            }
        }), topGap(8));
        box.addView(ui.hint("粘什么进来都行：整段 cURL、一段请求头、或者裸的「名字=值; 名字=值」。"
                + "长的会自动把 Cookie 摘出来，摘不出就原样收着。"));
        box.addView(ui.hint("框里看到的就是最终结果；想清掉就清空再保存。"));

        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        new AlertDialog.Builder(this, dialogTheme())
                .setTitle("Cookie")
                .setView(sv)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        String v = CookieGrab.tidy(e.getText().toString());
                        it.put(key, v);
                        EngineStore.put(MainActivity.this, it);
                                    renderEngines();
                        if (onChange != null) onChange.run();   // 上面那块状态当场刷新
                        LogBus.add("Cookie 已保存（" + it.label + "）：" + CookieGrab.summary(v));
                        toast(v.length() == 0 ? "已清空" : "已保存（" + v.length() + " 字符）");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 把编辑框里的东西写回引擎。保存和"抓完 Cookie"共用这一份。 */
    /** JS 插件引擎用不上的那些键（HTTP 模板引擎的字段 + 我自己的内部键）—— 编辑框里别显示。 */
    private static final java.util.Set<String> JS_HIDE = new java.util.HashSet<String>(
            java.util.Arrays.asList("headers", "cred", "voices", "voices_url", "voices_path",
                    "voices_name", "voices_id", "var_labels", "def_vars", "user_vars", "tts_data",
                    "login_key", "login_url", "binding", "ua", "author", "icon"));

    private void applyEditor(EngineStore.Item it, EditText nameBox, List<Fld> flds,
                            List<String> extraKeys) {
        String nm = nameBox.getText().toString().trim();
        if (nm.length() > 0) it.label = nm;
        for (Fld f : flds) {
            String v = f.box.getText().toString();
            if ("timeout".equals(f.key) || "sample_rate".equals(f.key)) {
                try { it.put(f.key, Integer.parseInt(v.trim())); }
                catch (Throwable ignored) { }
            } else if (v.trim().length() == 0 && extraKeys.contains(f.key)) {
                it.cfg.remove(f.key);
            } else {
                it.put(f.key, v);
            }
        }
        if (EngineStore.KIND_JS.equals(it.kind)) EngineStore.syncUserVars(it);
        EngineStore.put(this, it);
        try { com.mermergi.ttsrelay.JsTtsEngine.clearHosts(); } catch (Throwable ignored) { }
        renderEngines();
    }

    /**
     * 插件声明的变量（它引擎文件里 {@code vars} 那些键）单独存一份 {@code user_vars} ——
     * TTS Server 就是"声明"和"用户填的值"分开存的：放进 cfg 的散键里，插件读
     * {@code ttsrv.userVars['api_key']} 时容易对不上。
     */
    private void addCfgKey(final EngineStore.Item it) {
        LinearLayout box = ui.col();
        box.setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(4));
        final EditText e = ui.field(null, "", InputType.TYPE_CLASS_TEXT);
        e.setTag(null);
        e.setHint("键名，比如 extra_param");
        box.addView(e);
        new AlertDialog.Builder(this, dialogTheme())
                .setTitle("加一个配置键")
                .setView(box)
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        String k = e.getText().toString().trim();
                        if (k.length() == 0) return;
                        it.put(k, "");
                        EngineStore.put(MainActivity.this, it);
                        renderEngines();
                        editEngine(it);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 把引擎能报出来的音色列出来，方便复制到音色表里。 */
    private void showEngineVoices(final EngineStore.Item it) {
        toast("正在拉音色…");
        final Context ctx = this;
        new Thread(new Runnable() {
            public void run() {
                final List<String> vs;
                try {
                    vs = EngineStore.fetchVoices(ctx, it);
                } catch (Throwable t) {
                    final String m = String.valueOf(t.getMessage());
                    runOnUiThread(new Runnable() {
                        public void run() { toast("拉取失败：" + m); }
                    });
                    return;
                }
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (vs.isEmpty()) { toast("这个引擎没报出音色，手动填就行"); return; }
                        final StringBuilder sb = new StringBuilder();
                        for (String v : vs) sb.append(TtsEngine.shortVoice(v)).append('\n');
                        new AlertDialog.Builder(MainActivity.this, dialogTheme())
                                .setTitle(it.label + "（" + vs.size() + " 个）")
                                .setMessage(sb.toString())
                                .setPositiveButton("复制全部", new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface d, int w) {
                                        copy(sb.toString().trim());
                                    }
                                })
                                .setNegativeButton("关闭", null)
                                .show();
                    }
                });
            }
        }).start();
    }

    private void testEngine() {
        final Speaker spk = RelayService.speakerOrNull();
        if (spk == null) { toast("先启动中转"); return; }
        final TtsEngine e = EngineStore.activeEngine(this);
        toast("正在问引擎…");
        // 同样不能在 UI 线程上问：JS 引擎会真跑一遍插件
        new Thread(new Runnable() {
            public void run() {
                final String prob = e == null ? "引擎起不来" : e.problem(MainActivity.this);
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (prob != null) { toast("⚠ " + prob); return; }
                        spk.feed("这是当前语音引擎的测试，能听见就说明通了。", null);
                        toast("已排队（" + (e == null ? "?" : e.label()) + "）");
                    }
                });
            }
        }).start();
    }

    // ══════════════════════════ 日志 ══════════════════════════

    private View pageLog() {
        LinearLayout root = ui.col();
        root.setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(12));

        LinearLayout head = ui.row();
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = ui.sectionTitle("运行日志");
        t.setPadding(0, 0, 0, 0);
        head.addView(t, ui.weight(1f));
        head.addView(ui.btn("复制全部", false, new View.OnClickListener() {
            public void onClick(View v) { copy(LogBus.dump()); }
        }));
        LinearLayout.LayoutParams hg = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hg.leftMargin = ui.dp(8);
        hg.rightMargin = ui.dp(8);
        head.addView(ui.btn("清空", false, new View.OnClickListener() {
            public void onClick(View v) {
                LogBus.clear();
                if (logView != null) logView.setText("");
                logLen = 0;
            }
        }), hg);
        root.addView(head);

        ScrollView sv = new ScrollView(this);
        sv.setBackground(ui.round(p.card, p.border, 16));
        sv.setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12));
        logView = new TextView(this);
        logView.setTextSize(11);
        logView.setTextColor(p.log);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        logView.setLineSpacing(ui.dp(2), 1f);
        sv.addView(logView);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        lp.topMargin = ui.dp(10);
        root.addView(sv, lp);
        return root;
    }

    // ══════════════════════════ 刷新 ══════════════════════════

    private void refresh() {
        boolean up = RelayService.isUp();
        int port = Prefs.port(this);
        HttpServer srv = RelayService.serverOrNull();
        Speaker spk = RelayService.speakerOrNull();
        if (srv != null && srv.isRunning()) port = srv.port();

        boolean muted = Prefs.muted(this);
        boolean paused = spk != null && spk.isPaused();

        if (heroStatus != null) {
            heroStatus.setText(up ? "运行中" : "未运行");
            heroStatus.setTextColor(up ? p.ok : p.textMuted);
            heroDot.setTextColor(up ? p.ok : p.textMuted);
            String state = !up ? "点下面按钮启动"
                    : (muted ? "已静音 · 只中转不朗读" : (paused ? "已暂停朗读" : "正在朗读"));
            heroSub.setText("127.0.0.1:" + port + "  →  " + shortHost(Prefs.upstream(this))
                    + "\n" + state);
        }
        if (toggleBtn != null) toggleBtn.setText(up ? "停止中转" : "启动中转");
        if (pauseBtn != null) pauseBtn.setText(paused ? "继续" : "暂停");
        if (muteBtn != null) muteBtn.setText(muted ? "取消静音" : "静音");

        if (stat[0] != null) stat[0].setText(String.valueOf(spk == null ? 0 : spk.spokenCount()));
        if (stat[1] != null) stat[1].setText(String.valueOf(spk == null ? 0 : spk.charCount()));
        if (stat[2] != null) stat[2].setText(String.valueOf(spk == null ? 0 : spk.queued()));
        if (stat[3] != null) stat[3].setText(String.valueOf(spk == null ? 0 : spk.failedCount()));

        if (addrView != null) addrView.setText("http://127.0.0.1:" + port + "/v1");
        if (lanAddrView != null) {
            String ip = lanIp();
            lanAddrView.setText(ip == null
                    ? "（没找到局域网 IP）"
                    : "http://" + ip + ":" + port + "/v1"
                    + (Prefs.lanAllowed(this) ? "  · 已开" : "  · 未开"));
        }
        if (voiceView != null) {
            String v = Prefs.voice(this);
            voiceView.setText(v.length() == 0
                    ? "当前：（未选）用 TTS Server 自己的默认音色"
                    : "当前：" + v.replaceAll("_\\d+$", ""));
        }
        if (splitBtn != null) {
            splitBtn.setText("断句方案：" + Splitter.modeName(Prefs.splitMode(this)) + "  ▸");
        }
        if (themeBtn != null) {
            themeBtn.setText("主题：" + Palette.THEME_NAMES[Prefs.themeMode(this)] + "  ▸");
        }
        if (modeBtn != null) {
            modeBtn.setText("解析模式：" + MultiVoice.MODE_NAMES[Prefs.parseMode(this)] + "  ▸");
        }
        if (lanSwitch != null && lanSwitch.isChecked() != Prefs.lanAllowed(this)) {
            lanSwitch.setChecked(Prefs.lanAllowed(this));
        }

        if (logView != null) {
            String all = LogBus.dump();
            if (all.length() != logLen) {
                logLen = all.length();
                logView.setText(all);
                View parent = (View) logView.getParent();
                if (parent instanceof ScrollView) {
                    final ScrollView sv = (ScrollView) parent;
                    sv.post(new Runnable() {
                        public void run() { sv.fullScroll(View.FOCUS_DOWN); }
                    });
                }
            }
        }

        if (up && (System.currentTimeMillis() % 5000) < 1000) {
            RelayService svc = RelayService.instance();
            if (svc != null) svc.updateNotification();
        }
    }

    private static String shortHost(String u) {
        try { return new java.net.URL(u).getHost(); } catch (Throwable t) { return u; }
    }

    // ══════════════════════════ 行为 ══════════════════════════

    private void toggle() {
        boolean up = RelayService.isUp();
        Intent i = new Intent(this, RelayService.class);
        if (up) {
            i.setAction(RelayService.ACTION_STOP);
            try { startService(i); } catch (Throwable ignored) { }
            toast("已停止");
        } else {
            saveInputs();
            try {
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
                else startService(i);
            } catch (Throwable t) {
                toast("启动失败：" + t.getMessage());
            }
        }
        tick.postDelayed(new Runnable() { public void run() { refresh(); } }, 600);
    }

    private void togglePause() {
        Speaker spk = RelayService.speakerOrNull();
        if (spk == null) { toast("先启动中转"); return; }
        if (spk.isPaused()) spk.resume(); else spk.pause();
        refresh();
    }

    private void toggleMute() {
        boolean m = !Prefs.muted(this);
        Prefs.setMuted(this, m);
        Speaker spk = RelayService.speakerOrNull();
        if (m && spk != null) spk.interrupt();
        toast(m ? "已静音：只当中转用，不朗读" : "已取消静音");
        refresh();
    }

    private void skipCurrent() {
        Speaker spk = RelayService.speakerOrNull();
        if (spk == null) { toast("先启动中转"); return; }
        spk.skipCurrent();
        toast("已跳过当前句");
    }

    private void openTtsServer() {
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage(Prefs.engine(this));
            if (i == null) { toast("没装：" + Prefs.engine(this)); return; }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            toast("打开失败：" + t.getMessage());
        }
    }

    // ══════════════════════════ 读写设置 ══════════════════════════

    private List<EditText> fields() {
        List<EditText> out = new ArrayList<EditText>();
        collect((ViewGroup) findViewById(android.R.id.content), out);
        return out;
    }

    private void collect(ViewGroup g, List<EditText> out) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View v = g.getChildAt(i);
            if (v instanceof EditText) out.add((EditText) v);
            else if (v instanceof ViewGroup) collect((ViewGroup) v, out);
        }
    }

    private void saveInputs() {
        for (EditText e : fields()) {
            Object tag = e.getTag();
            if (!(tag instanceof String)) continue;
            String k = (String) tag;
            String v = e.getText().toString().trim();
            if (k.equals("skipkey")) { Prefs.setSkipKeywords(this, v); continue; }
            if (k.equals("trytext")) { if (v.length() > 0) Prefs.setTryText(this, v); continue; }
            if (v.length() == 0) continue;
            if (k.equals("upstream")) Prefs.setUpstream(this, v);
            else if (k.equals("tts")) Prefs.setTtsBase(this, v);
            else if (k.equals("port")) {
                try { Prefs.setPort(this, Integer.parseInt(v)); } catch (Throwable ignored) { }
            } else if (k.equals("maxchunk")) {
                try { Prefs.setMaxChunk(this, Integer.parseInt(v)); } catch (Throwable ignored) { }
            } else if (k.equals("smalltoken")) {
                try { Prefs.setSmallTokenLimit(this, Integer.parseInt(v)); } catch (Throwable ignored) { }
            }
        }
    }

    private void syncInputs() {
        syncing = true;                      // 这一轮是"程序往控件里填值"，别当用户操作
        for (EditText e : fields()) {
            Object tag = e.getTag();
            if (!(tag instanceof String)) continue;
            String k = (String) tag;
            if (k.equals("upstream")) e.setText(Prefs.upstream(this));
            else if (k.equals("tts")) e.setText(Prefs.ttsBase(this));
            else if (k.equals("port")) e.setText(String.valueOf(Prefs.port(this)));
            else if (k.equals("maxchunk")) e.setText(String.valueOf(Prefs.maxChunk(this)));
            else if (k.equals("trytext")) e.setText(Prefs.tryText(this));
            else if (k.equals("skipkey")) e.setText(Prefs.skipKeywords(this));
            else if (k.equals("smalltoken")) e.setText(String.valueOf(Prefs.smallTokenLimit(this)));
        }
        if (rateBar != null) {
            rateBar.setProgress(toneProg(Prefs.rate(this)));
            rateVal.setText(Prefs.rate(this));
        }
        if (pitchBar != null) {
            pitchBar.setProgress(toneProg(Prefs.pitch(this)));
            pitchVal.setText(Prefs.pitch(this));
        }
        if (volBar != null) {
            int v = Math.round(Prefs.volume(this) * 100f);
            volBar.setProgress(v);
            volVal.setText(v + "%");
        }
        if (lanSwitch != null) lanSwitch.setChecked(Prefs.lanAllowed(this));
        if (skipSwitch != null) skipSwitch.setChecked(Prefs.skipSmall(this));
        if (mvSwitch != null) mvSwitch.setChecked(Prefs.mvEnabled(this));
        if (speakUserSwitch != null) speakUserSwitch.setChecked(Prefs.speakUser(this));
        renderUserVoice();
        renderVoiceTable();
        renderEngines();          // 只在切页 / 回前台时画，别每秒都画
        if (autoStartSwitch != null) autoStartSwitch.setChecked(Prefs.autoStart(this));
        if (themeBtn != null) themeBtn.setText("主题：" + Palette.THEME_NAMES[Prefs.themeMode(this)] + "  ▸");
        renderPresets();
        renderPreview();
        syncing = false;
    }

    // ══════════════════════════ 断句预览 ══════════════════════════

    private void renderPreview() {
        if (previewIn == null || previewOut == null) return;
        String t = previewIn.getText().toString();
        if (t.trim().length() == 0) {
            previewOut.setText("上面粘一段带标点的文字，这里会实时显示会被切成几句。");
            return;
        }
        Splitter sp = new Splitter(Prefs.maxChunk(this), 4, Prefs.splitMode(this));
        List<String> parts = new ArrayList<String>();
        parts.addAll(sp.feed(t));
        parts.addAll(sp.flush());
        StringBuilder sb = new StringBuilder();
        sb.append("共 ").append(parts.size()).append(" 句 · 方案「")
                .append(Splitter.modeName(Prefs.splitMode(this)))
                .append("」· 单句上限 ").append(Prefs.maxChunk(this)).append(" 字\n");
        for (int i = 0; i < parts.size(); i++) {
            sb.append(i + 1).append(". ").append(parts.get(i)).append('\n');
        }
        previewOut.setText(sb.toString());
    }

    // ══════════════════════════ 音色 / 试听 / 自检 ══════════════════════════

    private void testSpeak() {
        Speaker spk = RelayService.speakerOrNull();
        if (spk == null) { toast("先启动中转"); return; }
        saveInputs();
        String text = Prefs.tryText(this);
        Splitter sp = new Splitter(Prefs.maxChunk(this), 4, Prefs.splitMode(this));
        List<String> parts = new ArrayList<String>();
        for (int i = 0; i < text.length(); i++) {
            parts.addAll(sp.feed(String.valueOf(text.charAt(i))));
        }
        parts.addAll(sp.flush());
        for (String s : parts) spk.feed(s);
        LogBus.add("试听 · 断句方案「" + Splitter.modeName(Prefs.splitMode(this))
                + "」切成 " + parts.size() + " 块：");
        for (String x : parts) LogBus.add("   ▸ " + x);
        toast("已排队（" + parts.size() + " 块，方案：" + Splitter.modeName(Prefs.splitMode(this)) + "）");
    }

    private void selfTest() {
        if (!RelayService.isUp()) { toast("先启动中转"); return; }
        final int p0 = curPort();
        new Thread(new Runnable() {
            public void run() {
                try {
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                            new java.net.URL("http://127.0.0.1:" + p0 + "/_selftest").openConnection();
                    c.setConnectTimeout(5000);
                    c.setReadTimeout(15000);
                    c.getResponseCode();
                    c.disconnect();
                    runOnUiThread(new Runnable() {
                        public void run() { toast("自检音频已排队，听声音"); }
                    });
                } catch (Throwable t) {
                    final String m = String.valueOf(t.getMessage());
                    runOnUiThread(new Runnable() {
                        public void run() { toast("自检失败：" + m); }
                    });
                }
            }
        }).start();
    }

    private void pickSplitMode() {
        String[] names = new String[Splitter.MODE_COUNT];
        String[] descs = new String[Splitter.MODE_COUNT];
        for (int i = 0; i < Splitter.MODE_COUNT; i++) {
            names[i] = Splitter.modeName(i);
            descs[i] = Splitter.modeDesc(i);
        }
        ChoiceSheet.show(this, ui, p, sheetTheme(), "断句方案", null,
                names, descs, Prefs.splitMode(this), new ChoiceSheet.OnPick() {
                    public void onPick(int which) {
                        Prefs.setSplitMode(MainActivity.this, which);
                        syncInputs();
                        refresh();
                    }
                });
    }

    private void pickVoice() { pickUserVoice = false; fetchVoicesAnd(null); }

    /** 拉音色列表；role 为 null = 选全局音色，否则选某个角色的音色。 */
    private void fetchVoicesAnd(final String role) {
        new Thread(new Runnable() {
            public void run() {
                final List<VoiceItem> names = new ArrayList<VoiceItem>();
                final List<EngineStore.SourceGroup> groups;
                try {
                    // 照它：音色是"所有启用中的引擎"合起来给的（选了音色就等于选了引擎）；
                    // 按来源引擎分组 —— 选音色时左边那一栏就是它
                    groups = EngineStore.voicesByEngine(MainActivity.this);
                    for (EngineStore.SourceGroup g : groups) {
                        for (String v : g.voices) {
                            VoiceItem vi = VoiceItem.parse(v);
                            vi.srcId = g.id;
                            vi.srcLabel = g.label;
                            // 一个音色可以绑多个角色：这里把已经绑的都列出来
                            List<String> roles = EngineStore.rolesUsingVoice(MainActivity.this, v);
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
                    runOnUiThread(new Runnable() {
                        public void run() { toast("拉取失败：" + m); }
                    });
                    return;
                }
                runOnUiThread(new Runnable() {
                    public void run() { showVoiceDialog(names, groups, role); }
                });
            }
        }).start();
    }

    /**
     * 音色库 —— 它的 ModalBottomSheet + 左边的来源栏 + 可搜的 RecyclerView。
     * 点一行 = 选它（或给它填名字）；行尾 🎧 = 只试听。
     */
    private void showVoiceDialog(final List<VoiceItem> all, final List<EngineStore.SourceGroup> groups,
                                 final String role) {
        if (all.isEmpty()) { toast("没拿到音色"); return; }
        String who = role == null ? "选择音色" : "给「" + role + "」选音色";

        LinearLayout box = ui.col();
        box.setBackground(ui.sheetBg());
        box.setPadding(ui.dp(16), 0, ui.dp(16), ui.dp(10));

        TextView title = new TextView(this);
        title.setText(who);
        title.setTextColor(p.textPrimary);
        title.setTextSize(17);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(title);

        final EditText filter = new EditText(this);
        filter.setHint("搜：名字 / 女声 / 青年 / 温柔…");
        filter.setSingleLine(true);
        filter.setTextColor(p.textPrimary);
        filter.setHintTextColor(p.textMuted);
        filter.setTextSize(14);
        filter.setBackground(ui.round(p.cardHi, p.border, 12));
        filter.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10));
        box.addView(filter);

        final LinearLayout chips = ui.row();
        chips.setPadding(0, ui.dp(10), 0, ui.dp(2));
        box.addView(chips);

        final RecyclerView rv = new RecyclerView(this);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setClipToPadding(false);
        rv.setPadding(0, ui.dp(2), 0, ui.dp(6));

        // ── 左边那一栏：按来源引擎分类 ──
        final LinearLayout rail = ui.col();
        rail.setPadding(0, ui.dp(4), ui.dp(8), 0);
        ScrollView railScroll = new ScrollView(this);
        railScroll.addView(rail);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ui.dp(104),
                ViewGroup.LayoutParams.MATCH_PARENT);
        railScroll.setLayoutParams(rlp);

        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(0, ui.dp(430), 1f);
        vlp.topMargin = ui.dp(4);
        rv.setLayoutParams(vlp);

        LinearLayout mid = ui.row();
        mid.addView(railScroll);
        mid.addView(rv);
        box.addView(mid);

        final String[] kw = new String[]{""};
        final String[] gen = new String[]{""};
        final String[] src = new String[]{""};
        final List<VoiceItem> rows = new ArrayList<VoiceItem>();

        final com.google.android.material.bottomsheet.BottomSheetDialog dlg =
                new com.google.android.material.bottomsheet.BottomSheetDialog(this, sheetTheme());
        dlg.setContentView(box);

        VoiceListAdapter ad = new VoiceListAdapter(p, ui, new VoiceListAdapter.Listener() {
            public void onPick(VoiceItem vi) {
                if (vi == null || vi.header) return;
                if (pickUserVoice) {
                    pickUserVoice = false;
                    Prefs.setUserVoice(MainActivity.this, vi.raw);
                    renderUserVoice();
                    dlg.dismiss();
                    auditionQuiet(vi.raw);
                    return;
                }
                if (pickForBind) {
                    pickForBind = false;
                    dlg.dismiss();
                    String carry = bindCarry;
                    bindCarry = null;
                    if (sheetOrig == null) {
                        newBinding(vi.raw);         // ＋ 加一个音色：先选音色 → 紧接着填名字
                    } else {
                        sheetVoice = vi.raw;        // 换音色：还是这一条，只是换个嗓子
                        showBindSheet(carry);
                    }
                    return;
                }
                Prefs.setVoiceEngine(MainActivity.this, vi.raw,
                        EngineStore.ownerOfVoice(MainActivity.this, vi.raw));
                Prefs.setVoice(MainActivity.this, vi.raw);
                dlg.dismiss();
                audition(vi.raw);
                refresh();
            }

            public void onAudition(VoiceItem vi) {
                if (vi == null || vi.header) return;
                auditionQuiet(vi.raw);
            }
        });
        rv.setAdapter(ad);

        final Runnable refill = new Runnable() {
            public void run() {
                fillVoiceList(rows, all, kw[0], gen[0], src[0]);
                ad.submitList(new ArrayList<VoiceItem>(rows));
            }
        };
        renderGenderChips(chips, gen, refill);
        renderSourceRail(rail, groups, all, src, refill, rv);
        refill.run();

        filter.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(Editable e) {
                kw[0] = e.toString().trim();
                refill.run();
            }
        });

        dlg.show();
        try {   // 起来就是全展开（不是先露出一个 peek）
            dlg.getBehavior().setSkipCollapsed(true);
            dlg.getBehavior().setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
        } catch (Throwable ignored) { }
    }

    /** 角色名怎么切：换行 / 顿号 / 逗号 / 分号 / 竖线，都能当分隔符（空格不算，名字里可能有空格）。 */
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



    /** 左侧音色来源栏：「全部」+ 每条引擎一行（带数量）。点一下只留那一家的音色。 */
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

    /** 引擎名太长，左边那一栏塞不下就掐头留尾。 */
    private static String shortSrc(String label) {
        if (label == null) return "?";
        String s = label.trim();
        int i = s.indexOf('（');
        if (i > 0) s = s.substring(0, i);
        s = s.replaceAll("\\s+", "");
        return s.length() > 6 ? s.substring(0, 6) : s;
    }

    /** 按性别分组填表：女声 / 男声 / 未标注，各带一条分组标题；可再按来源引擎过滤。 */
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


    /** 用户消息用哪个音色：自己配了用自己的，没配就跟全局。 */
    private String userVoiceOf() {
        String v = Prefs.userVoice(this);
        return (v == null || v.length() == 0) ? Prefs.voice(this) : v;
    }

    private void renderUserVoice() {
        if (userVoiceView == null) return;
        String v = Prefs.userVoice(this);
        boolean own = v != null && v.length() > 0;
        userVoiceView.setText("音色：" + (own ? TtsEngine.shortVoice(v) : "跟随全局"));
        userVoiceView.setTextColor(own ? p.textSecondary : p.textMuted);
    }

    /** 画「音色 → 名称」这张表：一行一个音色，下面写着它的名字。 */
    private void renderVoiceTable() {
        if (roleBox == null) return;
        roleBox.removeAllViews();
        java.util.LinkedHashMap<String, String> m = Prefs.voiceNamesMap(this);
        if (m.isEmpty()) {
            roleBox.addView(ui.hint("还没配"));
            return;
        }
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
            TextView vn = new TextView(this);
            vn.setText(TtsEngine.shortVoice(voice));
            vn.setTextColor(p.accent);
            vn.setTextSize(15);
            vn.setTypeface(Typeface.DEFAULT_BOLD);
            col.addView(vn);
            TextView nn = new TextView(this);
            nn.setText("名称：" + e.getValue().replace("|", " | "));
            nn.setTextColor(p.textSecondary);
            nn.setTextSize(12.5f);
            nn.setPadding(0, ui.dp(3), 0, 0);
            col.addView(nn);
            row.addView(col, ui.weightGap(1f, 6));

            TextView play = ui.chip("🎧", false, null);
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

    /**
     * 点「编辑」：这条音色属于哪个插件引擎，就开那个插件的整页（TTS Server 那种「编辑插件 TTS」）。
     * 系统语音 / HTTP 这些没有插件的引擎，还是用原来的小抽屉。
     */
    private void openVoiceEditor(String voice) {
        String owner = EngineStore.ownerOfVoice(this, voice);
        EngineStore.Item eng = owner == null ? null : EngineStore.get(this, owner);
        boolean hasPlugin = eng != null
                && (EngineStore.KIND_JS.equals(eng.kind) || eng.s("src", "").trim().length() > 0);
        if (!hasPlugin) { bindingSheet(voice); return; }
        Intent i = new Intent(this, PluginTtsActivity.class);
        i.putExtra(PluginTtsActivity.EXTRA_ID, eng.id);
        i.putExtra(PluginTtsActivity.EXTRA_VOICE, voice);
        i.putExtra(PluginTtsActivity.EXTRA_NAMES, Prefs.voiceNamesMap(this).get(voice));
        try { startActivity(i); } catch (Throwable t) { toast("打不开：" + t.getMessage()); }
    }

    /** 编辑表里已有的这一条：姓名一栏 + 试听（它的 QuickEditBottomSheet + AuditionTextField）。 */
    private void bindingSheet(String voice) {
        sheetOrig = voice;                    // 原来是记在这个音色名下的
        sheetVoice = voice;
        showBindSheet(null);
    }

    /** 新加一条：名字先空着，音色是刚从库里选的。 */
    private void newBinding(String voice) {
        sheetOrig = null;
        sheetVoice = voice;
        showBindSheet(null);
    }

    /**
     * 画「音色 → 名称」这一条的编辑面板。
     *
     * @param carry 换音色途中被关掉时手里已经填好的名字；null = 用这个音色已存的
     */
    private void showBindSheet(String carry) {
        final String voice = sheetVoice;
        String cur = (carry != null) ? carry : Prefs.voiceNamesMap(this).get(voice);
        LinearLayout box = ui.col();
        box.setPadding(ui.dp(18), ui.dp(2), ui.dp(18), ui.dp(22));

        LinearLayout head = ui.row();
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText(TtsEngine.shortVoice(voice));
        title.setTextColor(p.textPrimary);
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(title, ui.weightGap(1f, 8));
        head.addView(ui.chip("🎧", false, new View.OnClickListener() {
            public void onClick(View x) { auditionQuiet(voice); }
        }));
        box.addView(head);

        final EditText names = new EditText(this);
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
                if (list.isEmpty()) { toast("名字不能空着"); return; }
                StringBuilder sb = new StringBuilder();
                for (String n : list) { if (sb.length() > 0) sb.append('|'); sb.append(n); }
                if (sheetOrig != null && !sheetOrig.equals(voice)) {
                    // 换过音色：这一条整体搬过去，旧音色那条不留
                    Prefs.moveVoiceNames(MainActivity.this, sheetOrig, voice, sb.toString());
                } else {
                    Prefs.putVoiceNames(MainActivity.this, voice, sb.toString());
                }
                Prefs.setVoiceEngine(MainActivity.this, voice,
                        EngineStore.ownerOfVoice(MainActivity.this, voice));
                sheetOrig = voice;
                renderVoiceTable();
                dismissSheet();
                toast("「" + TtsEngine.shortVoice(voice) + "」→ " + sb);
            }
        }), ui.weightGap(1f, 8));
        rr.addView(ui.btn("换个音色", false, new View.OnClickListener() {
            public void onClick(View v) {
                bindCarry = names.getText().toString();   // 刚填的名字带着走
                dismissSheet();
                pickForBind = true;
                fetchVoicesAnd(null);
            }
        }), ui.weightGap(1f, 0));
        box.addView(rr, ui.topGap(12));

        box.addView(ui.btn("删掉这一条", false, new View.OnClickListener() {
            public void onClick(View v) {
                Prefs.putVoiceNames(MainActivity.this,
                        sheetOrig != null ? sheetOrig : voice, null);
                renderVoiceTable();
                dismissSheet();
            }
        }), ui.topGap(8));

        roleSheet = sheet(box);
    }

    /** 底部升起来的面板（它那边是 ModalBottomSheet；我们没引 material，就自己摆一个 Dialog）。 */
    /**
     * 底部抽屉的壳 —— 用的是 Material 的 BottomSheetDialog（它的 ModalBottomSheet 同一套）：
     * 遮罩、从下滑走、拖动、圆角、导航栏避让全是库里的，不用自己摆假把手。
     */
    private Dialog sheet(View content) {
        com.google.android.material.bottomsheet.BottomSheetDialog d =
                new com.google.android.material.bottomsheet.BottomSheetDialog(this, sheetTheme());

        LinearLayout wrap = ui.col();
        ImageView handle = new ImageView(this);          // 库里那根真的 drag handle
        handle.setImageResource(R.drawable.mtrl_bottomsheet_drag_handle);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(ui.dp(32), ui.dp(4));
        hlp.gravity = Gravity.CENTER_HORIZONTAL;
        hlp.topMargin = ui.dp(10);
        hlp.bottomMargin = ui.dp(2);
        wrap.addView(handle, hlp);

        ScrollView sc = new ScrollView(this);
        sc.setClipToPadding(false);
        sc.addView(content);
        wrap.addView(sc);

        d.setContentView(wrap);
        d.show();
        try { d.getBehavior().setSkipCollapsed(true); } catch (Throwable ignored) { }
        return d;
    }

    /** 底部抽屉那层的主题（深色 / 浅色各一套，都在 styles.xml 里）。 */
    private int sheetTheme() {
        return p.light ? R.style.AppBottomSheet_Light : R.style.AppBottomSheet;
    }

    /** 它的 AppSpinner：上面小字标签，下面当前值，右边一个 ▾，整行可点。 */
    private LinearLayout spinnerRow(String label, String value, View.OnClickListener click) {
        LinearLayout row = ui.row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(ui.dp(14), ui.dp(10), ui.dp(12), ui.dp(10));
        row.setBackground(ui.pressable(p.cardHi, p.ripple, 12, p.border));
        row.setClickable(true);
        row.setOnClickListener(click);
        LinearLayout col = ui.col();
        TextView lab = new TextView(this);
        lab.setText(label);
        lab.setTextColor(p.textMuted);
        lab.setTextSize(11.5f);
        col.addView(lab);
        TextView val = new TextView(this);
        val.setText(value == null || value.length() == 0 ? "—" : value);
        val.setTextColor(p.textPrimary);
        val.setTextSize(15);
        val.setPadding(0, ui.dp(2), 0, 0);
        col.addView(val);
        row.addView(col, ui.weightGap(1f, 6));
        TextView arrow = new TextView(this);
        arrow.setText("▾");
        arrow.setTextColor(p.textSecondary);
        arrow.setTextSize(14);
        row.addView(arrow);
        return row;
    }

    /** 它的 AuditionTextField：一个写着「试听文本」的框 + 右边耳机按钮。 */
    private LinearLayout auditionField(final String voice) {
        LinearLayout row = ui.row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(ui.dp(14), ui.dp(6), ui.dp(6), ui.dp(6));
        row.setBackground(ui.round(p.cardHi, p.border, 12));

        EditText e = new EditText(this);
        e.setHint("试听文本");
        e.setText(Prefs.tryText(this));
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
                if (t.length() > 0) Prefs.setTryText(MainActivity.this, t);
            }
        });
        row.addView(e, ui.weightGap(1f, 6));

        TextView ear = ui.chip("🎧", false, null);
        ear.setOnClickListener(new View.OnClickListener() {
            public void onClick(View x) { auditionQuiet(voice); }
        });
        row.addView(ear);
        return row;
    }

    /**
     * 直接出声、不弹窗 —— 列表里那个 🎧 用它：挑音色的时候弹窗会挡住列表。
     * 再点一次就是换这一句重念（先掐掉上一句）。
     */
    private void auditionQuiet(final String voice) {
        final Speaker spk = RelayService.speakerOrNull();
        if (spk == null) { toast("朗读没在跑（先打开中转）"); return; }
        String use = voice;
        if (use == null || use.length() == 0) use = Prefs.voice(this);
        if (use == null || use.length() == 0) { toast("还没选音色"); return; }
        Prefs.setVoiceEngine(this, use, EngineStore.ownerOfVoice(this, use));
        spk.interrupt();
        spk.feed(Prefs.tryText(this), use);
    }

    /** 试听（它的 AuditionDialog）：弹个小窗，出声后自己关掉。 */
    private void audition(final String voice) {
        final Speaker spk = RelayService.speakerOrNull();
        if (spk == null) { toast("朗读没在跑（先打开中转）"); return; }
        String use = voice;
        if (use == null || use.length() == 0) use = Prefs.voice(this);
        if (use == null || use.length() == 0) { toast("还没选音色"); return; }
        final String text = Prefs.tryText(this);
        Prefs.setVoiceEngine(this, use, EngineStore.ownerOfVoice(this, use));

        final AlertDialog dlg = new AlertDialog.Builder(this, dialogTheme())
                .setTitle("试听")
                .setMessage(text + "\n\n— " + TtsEngine.shortVoice(use))
                .setNegativeButton("停止", null)
                .create();
        dlg.show();
        spk.interrupt();
        spk.feed(text, use);
        new android.os.Handler(getMainLooper()).postDelayed(new Runnable() {
            public void run() {
                try { dlg.dismiss(); } catch (Throwable ignored) { }
            }
        }, 2600);
    }



    private Dialog roleSheet;

    private void dismissSheet() {
        Dialog d = roleSheet;
        roleSheet = null;
        if (d != null) {
            try { d.dismiss(); } catch (Throwable ignored) { }
        }
    }




    private void pickParseMode() {
        ChoiceSheet.show(this, ui, p, sheetTheme(), "解析模式", null,
                MultiVoice.MODE_NAMES, MultiVoice.MODE_DESCS, Prefs.parseMode(this),
                new ChoiceSheet.OnPick() {
                    public void onPick(int which) {
                        Prefs.setParseMode(MainActivity.this, which);
                        syncInputs();
                    }
                });
    }

    /** 丢给酒馆的格式规范 —— 照搬插件那段，模型按 [角色名] 输出才能分角色。 */
    private static final String GAL_PROMPT =
            "# 格式输出规范\n"
            + "**描写任何角色（主要角色、NPC、路人、旁白）说话时，必须严格遵守格式，对话单开一行**\n\n"
            + "## 格式：\n[角色名][情感][场景]「对话内容」\n\n"
            + "### 角色名：\n当前说话的人物名称。\n\n"
            + "### 情感：\n每个角色只选一个最贴合的：温柔、开心、害羞、生气、烦躁、悲伤、心疼、"
            + "害怕、紧张、厌恶、讽刺、惊讶、平静、无奈、调侃；没有合适的就写 [通常]。\n\n"
            + "### 对话内容：\n用「」或 “” 包裹角色说出的台词。\n\n"
            + "### 场景：\n可留空。\n\n"
            + "## 格式示例：\n[小明][开心][春日公园]「今天的天气真好呢。」\n"
            + "他悠闲地在公园中漫步，看着来往的人群。\n"
            + "\n（旁白、动作、心理描写不要加标签，直接写就行，会被当成旁白念。）";

    // ══════════════════════════ 杂项 ══════════════════════════

    private void copy(String s) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("relay", s));
        toast("已复制：" + s);
    }

    private int curPort() {
        HttpServer srv = RelayService.serverOrNull();
        return (srv != null && srv.isRunning()) ? srv.port() : Prefs.port(this);
    }

    private String lanIp() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> ifs =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (ifs != null && ifs.hasMoreElements()) {
                java.net.NetworkInterface ni = ifs.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                java.util.Enumeration<java.net.InetAddress> as = ni.getInetAddresses();
                while (as.hasMoreElements()) {
                    java.net.InetAddress a = as.nextElement();
                    if (a instanceof java.net.Inet4Address && a.isSiteLocalAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
