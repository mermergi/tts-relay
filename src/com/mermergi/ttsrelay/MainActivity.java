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
    /** 「常用上游」那一行的容器 —— 加了自定义接口要整行重画。 */

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
        View page;
        Tab t = tabs[i];
        if (t != null) page = t.build(this);
        else page = pageEngine();
        content.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        paintTabs();
        dispatchSync();
        refresh();
    }



    /** 切页时先松手，免得还抓着上一页已经拆掉的控件。 */
    private void clearRefs() {
        engineBox = null; engineNow = null;
        Tab t = (tab >= 0) ? tabs[tab] : null;
        if (t != null) t.clearRefs();
    }

    // ══════════════════════════ 朗读 ══════════════════════════






    @Override public int dialogTheme() {
        return p.light ? R.style.AppDialog_Light : R.style.AppDialog;
    }


    // ══════════════════════════ 引擎 ══════════════════════════

    private View pageEngine() {
        LinearLayout col = ui.col();

        LinearLayout c1 = ui.card();
        c1.addView(ui.sectionTitle("当前语音引擎"));
        engineNow = ui.value("");
        engineNow.setLineSpacing(ui.dp(3), 1f);
        c1.addView(engineNow);
        col.addView(c1);

        LinearLayout c2 = ui.card();
        c2.addView(ui.sectionTitle("引擎列表（点一行改它）"));
        engineBox = ui.col();
        c2.addView(engineBox);
        c2.addView(ui.btn("＋ 添加引擎", false, new View.OnClickListener() {
            public void onClick(View v) { addEngine(); }
        }), Widgets.topGap(ui, 8));
        col.addView(c2);

        LinearLayout c3 = ui.card();
        c3.addView(ui.sectionTitle("测试"));
        c3.addView(ui.btn("用当前引擎念一句", true, new View.OnClickListener() {
            public void onClick(View v) { testEngine(); }
        }));
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
        c4.addView(r1, Widgets.topGap(ui, 8));
        LinearLayout r2 = ui.row();
        r2.addView(ui.btn("导出到文件", false, new View.OnClickListener() {
            public void onClick(View v) { exportToFile(); }
        }), ui.weightGap(1f, 8));
        r2.addView(ui.btn("复制给别人", false, new View.OnClickListener() {
            public void onClick(View v) { exportMenu(); }
        }), ui.weight(1f));
        c4.addView(r2, Widgets.topGap(ui, 8));
        col.addView(c4);

        return Widgets.scroll(this, ui, col);
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
                            voicePicker.openVoiceEditor(TtsEngine.shortVoice(it.s("voice", "")));
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
        final LinearLayout langRow = Widgets.kvRow(this, ui, p, "语言", langVal);
        final LinearLayout voiceRow = Widgets.kvRow(this, ui, p, "声音", voiceVal);
        box.addView(langRow, ui.topGap(10));
        box.addView(voiceRow, ui.topGap(8));

        // ── 插件自己画的界面挂这儿 ──
        final LinearLayout host = ui.col();
        box.addView(host, ui.topGap(10));

        // ── 这个引擎自己的语速 / 音量 / 音高 ──
        box.addView(Widgets.paramSlider(this, ui, p, "语速", "speed", it), ui.topGap(6));
        box.addView(Widgets.paramSlider(this, ui, p, "音量", "vol", it));
        box.addView(Widgets.paramSlider(this, ui, p, "音高", "pitch_", it));

        // 这个抽屉自己开的插件宿主（按当前音色），保存/关掉时要把它的 ttsrv.tts.data 存回去
        final com.mermergi.ttsrelay.js.PluginBridge[] hostRef =
                new com.mermergi.ttsrelay.js.PluginBridge[1];
        box.addView(ui.btn("保存", true, new View.OnClickListener() {
            public void onClick(View v) {
                String nm = nameBox.getText().toString().trim();
                it.label = nm.length() == 0 ? "插件 TTS" : nm;
                it.put("lang", lang[0]);
                it.put("voice", curVoice[0]);
                EngineStore.put(MainActivity.this, it);
                // 插件界面上改的东西（豆包那个「自定义语气指令」存在 ttsrv.tts.data 里）
                JsTtsEngine.persistData(MainActivity.this, it, curVoice[0], hostRef[0]);
                JsTtsEngine.clearVoices();
                renderEngines();
                dismissSheet();
            }
        }), ui.topGap(16));

        roleSheet = sheet(box);
        // 手势划走 / 返回键关掉也一样存（跟单音色编辑页 onPause 的行为对齐）
        try {
            roleSheet.setOnDismissListener(new DialogInterface.OnDismissListener() {
                public void onDismiss(DialogInterface d) {
                    JsTtsEngine.persistData(MainActivity.this, it, curVoice[0], hostRef[0]);
                }
            });
        } catch (Throwable ignored) { }

        // 起插件慢（要 eval 整个脚本），后台跑完再填
        new Thread(new Runnable() {
            public void run() {
                final com.mermergi.ttsrelay.js.PluginBridge h;
                try {
                    h = JsTtsEngine.openFor(MainActivity.this, it, lang[0], curVoice[0]);
                } catch (Throwable t) {
                    LogBus.error("打开插件界面失败：" + t.getMessage());
                    return;
                }
                hostRef[0] = h;
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
                                try { JsTtsEngine.persistData(MainActivity.this, it, curVoice[0], h); }
                                catch (Throwable ignored) { }
                                curVoice[0] = vs.get(i)[1];
                                voiceVal.setText(vs.get(i)[0]);
                                try { h.onVoiceChanged(lang[0], curVoice[0]); } catch (Throwable ignored) { }
                            }
                        });
            }
        });
    }

    /** 一行「名字 ▸ 值」（插件页里的下拉）。 */


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

        final List<Form.Fld> flds = Form.buildForm(this, ui, box, spec.toArray(new String[0][]));

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
                }), Widgets.topGap(ui, 10));
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
            ck.addView(ckRow, Widgets.topGap(ui, 8));
            box.addView(ck);
        }

        LinearLayout act = ui.row();
        act.addView(ui.btn("＋ 加一个键", false, new View.OnClickListener() {
            public void onClick(View v) { addCfgKey(it); }
        }), ui.weightGap(1f, 8));
        act.addView(ui.btn("看看音色", false, new View.OnClickListener() {
            public void onClick(View v) { showEngineVoices(it); }
        }), ui.weight(1f));
        box.addView(act, Widgets.topGap(ui, 8));

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
        }), Widgets.topGap(ui, 8));

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

    private void applyEditor(EngineStore.Item it, EditText nameBox, List<Form.Fld> flds,
                            List<String> extraKeys) {
        String nm = nameBox.getText().toString().trim();
        if (nm.length() > 0) it.label = nm;
        for (Form.Fld f : flds) {
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
