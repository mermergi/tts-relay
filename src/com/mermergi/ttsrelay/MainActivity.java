package com.mermergi.ttsrelay;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.mermergi.ttsrelay.tab.PlayerTab;
import com.mermergi.ttsrelay.tab.SettingsTab;
import com.mermergi.ttsrelay.tab.SpeakTab;
import com.mermergi.ttsrelay.tab.VoicePicker;
import com.mermergi.ttsrelay.tab.EngineTab;
import com.mermergi.ttsrelay.tab.Tab;
import com.mermergi.ttsrelay.ui.Widgets;

/**
 * 四页签：朗读（播放器 · 快捷）/ 声音（音色 · 语调 · 断句 · 分角色）/ 引擎 / 设置（含运行日志入口）。
 * 界面全是代码搭的，颜色一律走 Palette —— 换主题只换一个对象，页面一行都不用动。
 */
public class MainActivity extends Activity implements Tab.Host {

    private static final int REQ_NOTIF = 41;
    private static final String[] TAB_NAME = {"朗读", "声音", "引擎", "设置"};
    /** 转屏 / 换主题 recreate 之后停在原来那一页。 */
    private static int LAST_TAB = 0;

    private Palette p;
    private Ui ui;
    /** 已迁移到 tab/ 协调器的页；null = 还是 MainActivity 里的老代码。 */
    private final Tab[] tabs = new Tab[5];

    // ── 骨架 ──
    private FrameLayout content;
    private final TextView[] tabName = new TextView[5];
    private int tab = -1;

    // ── 日志 ──

    private final Handler tick = new Handler();
    /** 5 秒一拍的兜底轮询：真正的实时性由下面两个事件源负责。 */
    private final Runnable refresher = new Runnable() {
        public void run() {
            refresh();
            tick.postDelayed(this, 5000);
        }
    };
    /** 朗读状态变了（开始念/念完/暂停/队列变）→ 刷当前页。 */
    private final Speaker.Listener spkLsn = new Speaker.Listener() {
        public void onSpeakerChanged() { runOnUiThread(refreshUi); }
    };
    /** 有新日志 → 只在日志页时刷（别的页不需要跟着日志走）。 */
    private final Runnable refreshUi = new Runnable() { public void run() { refresh(); } };

    // ══════════════════════════ 生命周期 ══════════════════════════

    @Override
    protected void onCreate(Bundle b) {
        App.install(this);
        // 先定主题、再从主题里取 M3 角色（Palette.apply 内部先 setTheme）
        p = Palette.apply(this);
        super.onCreate(b);
        ui = new Ui(this, p);
        tabs[0] = new PlayerTab(ui, p, this, new Runnable() { public void run() { dispatchSave(); } });
        voicePicker = new VoicePicker(ui, p, this);
        tabs[1] = new SpeakTab(ui, p, this, voicePicker);
        engTab = new EngineTab(ui, p, this);
        tabs[2] = engTab;
        tabs[3] = new SettingsTab(ui, p, this);
        engTab.voicePicker = voicePicker;
        applyWindow();
        buildShell();
        if (Build.VERSION.SDK_INT >= 33) {
            try { requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIF); }
            catch (Throwable ignored) { }
        }
        CrashHook.offerReport(this, dialogTheme());   // 上次崩过就弹「分享 / 忽略」
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 口信通道：adb/快捷方式发 ACTION_START 过来就当「要开中转」。
        // 服务没导出，shell 起不了它；但 Activity 是导出的，同 uid 代发合法。
        if (RelayService.ACTION_START.equals(getIntent().getAction())
                && !RelayService.isUp()) {
            Prefs.setWanted(this, true);
        }
        // 上次是开着的就自动拉起来
        if (Prefs.wanted(this) && !RelayService.isUp()) {
            try { startForegroundService(new Intent(this, RelayService.class)); } catch (Throwable ignored) { }
        }
        tick.removeCallbacks(refresher);
        tick.post(refresher);
        Speaker.addListener(spkLsn);
        dispatchSync();
    }

    @Override
    protected void onPause() {
        super.onPause();
        tick.removeCallbacks(refresher);
        Speaker.removeListener(spkLsn);
        dispatchSave();
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
                ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(64)));

        setContentView(root);
        switchTab(LAST_TAB);
    }

    /**
     * M3 底部导航：选中项是一颗 secondaryContainer 药丸（Material You 的标志性样子），
     * 未选是 onSurfaceVariant 文字。没有描边分隔线 —— 层级靠底色 tone。
     */
    private View buildTabBar() {
        LinearLayout bar = ui.row();
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(p.card);
        bar.setPadding(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6));
        for (int i = 0; i < TAB_NAME.length; i++) {
            final int idx = i;
            TextView pill = new TextView(this);
            pill.setText(TAB_NAME[i]);
            pill.setTextAppearance(this, R.style.TextAppearance_Material3_LabelLarge);
            pill.setGravity(Gravity.CENTER);
            pill.setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10));
            pill.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            pill.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { switchTab(idx); }
            });
            tabName[i] = pill;
            bar.addView(pill);
        }
        return bar;
    }

    private void paintTabs() {
        for (int i = 0; i < TAB_NAME.length; i++) {
            boolean on = (i == tab);
            TextView t = tabName[i];
            if (t == null) continue;
            t.setTextColor(on ? p.chipFg : p.textSecondary);
            t.setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            t.setBackground(on ? ui.round(p.chipBg, 22) : ui.round(0x00000000, 22));
        }
    }

    private void switchTab(int i) {
        if (i < 0 || i > 4) i = 0;
        if (i == tab) { paintTabs(); return; }
        dispatchSave();
        clearRefs();
        tab = i;
        LAST_TAB = i;
        content.removeAllViews();
        View page = null;
        Tab t = tabs[i];
        if (t != null) page = t.build(this);
        if (page != null) content.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        paintTabs();
        dispatchSync();
        refresh();
    }

    /** 切页时先松手，免得还抓着上一页已经拆掉的控件。 */
    /** 切页时先松手，免得还抓着上一页已经拆掉的控件。 */
    private void clearRefs() {
        Tab t = (tab >= 0) ? tabs[tab] : null;
        if (t != null) t.clearRefs();
    }

    @Override public int dialogTheme() {
        return p.light ? R.style.AppDialog_Light : R.style.AppDialog;
    }

    // ══════════════════════════ 引擎结果回传 ══════════════════════════

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        engTab.onActivityResult(req, res, data);
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

        Tab t2 = (tab >= 0) ? tabs[tab] : null;
        if (t2 != null) t2.refresh();
    }

    // ══════════════════════════ 读写分发 ══════════════════════════

    /** 壳分发：每个 Tab 管自己的字段落盘 / 回填。 */
    private void dispatchSave() {
        Tab t = (tab >= 0) ? tabs[tab] : null;
        if (t != null) t.saveInputs();
    }

    private void dispatchSync() {
        Tab t = (tab >= 0) ? tabs[tab] : null;
        if (t != null) t.syncInputs();
    }

    private Dialog roleSheet;
    /** 引擎页「编辑音色」要开朗读页那套编辑器，借 VoicePicker 的。 */
    private VoicePicker voicePicker;
    private EngineTab engTab;
    /**
     * 底部抽屉的壳 —— 用的是 Material 的 BottomSheetDialog（它的 ModalBottomSheet 同一套）：
     * 遮罩、从下滑走、拖动、圆角、导航栏避让全是库里的，不用自己摆假把手。
     */
    @Override public Dialog sheet(View content) {
        com.google.android.material.bottomsheet.BottomSheetDialog d =
                new com.google.android.material.bottomsheet.BottomSheetDialog(this, sheetTheme());

        LinearLayout wrap = ui.col();
        wrap.setBackground(ui.sheetBg());                // M3 抽屉底板（容器自身透明，底得自己画）
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
    @Override public int sheetTheme() {
        return p.light ? R.style.AppBottomSheet_Light : R.style.AppBottomSheet;
    }
    @Override public void dismissSheet() {
        Dialog d = roleSheet;
        roleSheet = null;
        if (d != null) {
            try { d.dismiss(); } catch (Throwable ignored) { }
        }
    }

    // ══════════════════════════ 杂项 ══════════════════════════

    @Override public void copy(String s) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("relay", s));
        toast("已复制：" + s);
    }

    @Override public void toast(String s) {
        Widgets.toast(this, s);
    }

    @Override public void runOnUi(Runnable r) { runOnUiThread(r); }
    @Override public void requestRefresh() { refresh(); }
    @Override public Activity activity() { return this; }
}
