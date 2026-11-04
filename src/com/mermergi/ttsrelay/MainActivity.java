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
import com.mermergi.ttsrelay.tab.HomeTab;
import com.mermergi.ttsrelay.tab.LogTab;
import com.mermergi.ttsrelay.tab.SettingsTab;
import com.mermergi.ttsrelay.tab.SpeakTab;
import com.mermergi.ttsrelay.tab.VoicePicker;
import com.mermergi.ttsrelay.tab.EngineTab;
import com.mermergi.ttsrelay.tab.Tab;
import com.mermergi.ttsrelay.ui.Form;
import com.mermergi.ttsrelay.ui.SliderRow;
import com.mermergi.ttsrelay.ui.Widgets;

/**
 * 四页签：首页（状态 · 控制）/ 朗读（音色 · 语调 · 断句）/ 设置 / 日志。
 * 界面全是代码搭的，颜色一律走 Palette —— 换主题只换一个对象，页面一行都不用动。
 */
public class MainActivity extends Activity implements Tab.Host {

    private static final int REQ_NOTIF = 41;
    private static final String[] TAB_NAME = {"首页", "朗读", "设置", "引擎", "日志"};
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

    // ── 首页 ──

    // ── 设置 ──
    private TextView userVoiceView;
    /** 这次开音色库是给「用户消息」挑音色。 */
    /** 这次开音色库，是为了给新音色填名字（先选音色，再填名字）。 */
    /**
     * 「音色 → 名称」那张表里，面板正在编辑的是哪一条：
     * sheetOrig = 这条原来记在哪个音色名下（新加一条时是 null），
     * sheetVoice = 面板上现在选中的音色。换音色时要把<b>这一条</b>搬过去，
     * 不能两个音色各留一条 —— 那才是"新开一条"。
     */
    /** 换音色途中面板被关掉了，刚填的名字先存在这儿带过去。 */
    /** 正在把设置值写回控件（这期间控件的变化不算用户操作）。 */

    // ── 引擎页 ──

    // ── 日志 ──

    private final Handler tick = new Handler();
    private final Runnable refresher = new Runnable() {
        public void run() {
            refresh();
            tick.postDelayed(this, 1000);
        }
    };


    // ══════════════════════════ 生命周期 ══════════════════════════

    @Override
    protected void onCreate(Bundle b) {
        App.install(this);
        // 先定主题、再从主题里取 M3 角色（Palette.apply 内部先 setTheme）
        p = Palette.apply(this);
        super.onCreate(b);
        ui = new Ui(this, p);
        tabs[0] = new HomeTab(ui, p, this, new Runnable() { public void run() { dispatchSave(); } });
        tabs[4] = new LogTab(ui, p, this);
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
        dispatchSync();
    }

    @Override
    protected void onPause() {
        super.onPause();
        tick.removeCallbacks(refresher);
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
        for (int i = 0; i < 5; i++) {
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
        for (int i = 0; i < 5; i++) {
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
        content.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        paintTabs();
        dispatchSync();
        refresh();
    }



    /** 切页时先松手，免得还抓着上一页已经拆掉的控件。 */
    private void clearRefs() {
        Tab t = (tab >= 0) ? tabs[tab] : null;
        if (t != null) t.clearRefs();
    }

    // ══════════════════════════ 朗读 ══════════════════════════






    @Override public int dialogTheme() {
        return p.light ? R.style.AppDialog_Light : R.style.AppDialog;
    }





    // ── 导入 / 导出 ──











    private boolean metaSyncing = false;



    // 行的零件都留着引用：后台结果回来时<b>原地改字</b>，不重建视图
    // （重建会把整页高度变来变去，看着就是"乱跳"）
    private final java.util.Map<String, TextView> rowName = new java.util.HashMap<String, TextView>();
    private final java.util.Map<String, TextView> rowSub = new java.util.HashMap<String, TextView>();
    private final java.util.Map<String, TextView> rowNeed = new java.util.HashMap<String, TextView>();



    /**
     * 只改字、只改可见性 —— 不新增/删除视图，也就不会跳。
     *
     * <b>这里绝不执行插件</b>：它（TTS Server）的插件列表也是这样，只显示已存的数据
     * （名字、作者-版本、要不要设置变量），插件要到真用的时候（试听/合成/编辑）才跑。
     */


    /** 副标题：插件引擎写「作者 - v版本」，和它那边一样。 */


    /** 头像里那个字：插件用名字首字，系统引擎用「本」，HTTP 引擎用「H」。 */


    /** 头像底色：按 id 散开，别都一个色。 */


    /** 照它的「导出配置」：把这一个引擎的配置导成 JSON（插件源码 + 变量都在里头）。 */


    private static final int REQ_PICK_JS = 61;
    private EngineStore.Item pendingJsPick;

    /** 挑一份插件 .js（TTS Server 导出来的那种）→ 这个引擎就变成 JS 插件。 */








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


    /** 语言下拉：选项来自插件 getLocales()；换语言要重新问一遍声音列表。 */


    /** 声音下拉：选项来自插件 getVoices(locale)；选完通知插件换声音。 */


    /** 一行「名字 ▸ 值」（插件页里的下拉）。 */




    // ── 配置表单 ──





    /** 读剪贴板，读不到就空串。 */


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







    /**
     * 插件声明的变量（它引擎文件里 {@code vars} 那些键）单独存一份 {@code user_vars} ——
     * TTS Server 就是"声明"和"用户填的值"分开存的：放进 cfg 的散键里，插件读
     * {@code ttsrv.userVars['api_key']} 时容易对不上。
     */


    /** 把引擎能报出来的音色列出来，方便复制到音色表里。 */




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


        if (up && (System.currentTimeMillis() % 5000) < 1000) {
            RelayService svc = RelayService.instance();
            if (svc != null) svc.updateNotification();
        }

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
