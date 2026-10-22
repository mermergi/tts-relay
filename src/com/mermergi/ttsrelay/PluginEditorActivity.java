package com.mermergi.ttsrelay;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * TTS Server 的 JS 插件编辑器 —— 照它那个样子做：
 * <ul>
 *   <li>整屏代码区（行号 + 横向滚动 + 等宽字），不是塞在一个小框里</li>
 *   <li>缩进工具条：TAB = &gt; { } ( ) , " ' ;</li>
 *   <li>顶栏：返回 / 名字 / ▶ 试听 / ↶ ↷ / 保存</li>
 *   <li>⋮ 菜单：<b>设置变量</b>（插件自己声明的 vars，显示名用它给的 name）、选择语言、粘贴/复制/清空</li>
 * </ul>
 * 一句话：编辑框里只有插件自己的东西，HTTP 引擎那堆字段一个都不出现。
 */
public final class PluginEditorActivity extends Activity {

    public static final String EXTRA_ID = "engine_id";
    /** 从「请设置变量」那行进来的：打开就弹变量面板。 */
    public static final String EXTRA_OPEN_VARS = "open_vars";
    private static final int REQ_LOGIN = 71;
    private static final String TEST_TEXT = "这是当前语音引擎的测试，能听见就说明通了。";

    private Palette p;
    private Ui ui;
    private EngineStore.Item it;

    private EditText nameBox;
    /** 代码区用开源库 sora-editor（TTS Server 用的就是它，见 tools/lib/SOURCES.md）：
     *  行号、语法高亮、撤销重做、搜索、缩进全是它给的，不是我们手搓的。 */
    private io.github.rosemoe.sora.widget.CodeEditor code;
    private TextView status;

    /** 穷人版撤销：每次改动前存一版。 */
    private final List<String> history = new ArrayList<String>();
    private int histPos = -1;
    private boolean loading = false;

    @Override protected void onCreate(Bundle b) {
        App.install(this);
        p = Palette.apply(this);
        super.onCreate(b);
        ui = new Ui(this, p);

        String id = getIntent() == null ? "" : getIntent().getStringExtra(EXTRA_ID);
        it = EngineStore.get(this, id);
        if (it == null) { toast("引擎没找到"); finish(); return; }
        // 这个页只干一件事：编辑插件源码。没源码的引擎（系统语音没有代码）不进这儿。
        if (it.s("src", "").trim().length() == 0
                && !EngineStore.KIND_JS.equals(it.kind)) {
            toast("这个引擎没有插件源码");
            finish();
            return;
        }
        // 进来就先把插件声明的变量读回来（老引擎不用重新导入）
        EngineStore.syncJsMeta(this, it);
        buildUi();
        load();
        if (getIntent() != null && getIntent().getBooleanExtra(EXTRA_OPEN_VARS, false)) {
            code.post(new Runnable() {
                public void run() { varsDialog(); }
            });
        }
    }

    // ───────────────────────── 界面 ─────────────────────────

    private void buildUi() {
        LinearLayout root = ui.col();
        root.setBackgroundColor(p.bg);
        root.setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(6));

        // ── 顶栏 ──
        LinearLayout top = ui.row();
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(tinyKey("←", new View.OnClickListener() {
            public void onClick(View v) { save(); finish(); }
        }));
        nameBox = ui.field("label", "", InputType.TYPE_CLASS_TEXT);
        nameBox.setHint("引擎名字");
        nameBox.setSingleLine(true);
        LinearLayout.LayoutParams np = ui.weight(1f);
        np.leftMargin = ui.dp(4);
        np.rightMargin = ui.dp(4);
        top.addView(nameBox, np);
        top.addView(tinyKey("▶", new View.OnClickListener() {
            public void onClick(View v) { listen(); }
        }));
        top.addView(tinyKey("↶", new View.OnClickListener() {
            public void onClick(View v) { undo(); }
        }));
        top.addView(tinyKey("↷", new View.OnClickListener() {
            public void onClick(View v) { redo(); }
        }));
        top.addView(tinyKey("💾", new View.OnClickListener() {
            public void onClick(View v) { save(); toast("已保存"); }
        }));
        top.addView(tinyKey("⋮", new View.OnClickListener() {
            public void onClick(View v) { menu(v); }
        }));
        root.addView(top);

        status = ui.hint("");
        status.setPadding(0, ui.dp(4), 0, 0);
        root.addView(status);

        // 插件自己画的界面（语气输入框之类）直接内嵌在这儿，和 TTS Server 一样，不用开弹窗
        TextView inlineToggle = ui.chip("插件界面 ▾", false, new View.OnClickListener() {
            public void onClick(View v) { toggleInline(); }
        });
        inlineToggle.setSingleLine(true);
        root.addView(inlineToggle);
        pluginInline = ui.col();
        pluginInline.setVisibility(View.GONE);
        root.addView(pluginInline);

        // ── 代码区：sora-editor（开源，整屏）──
        code = new io.github.rosemoe.sora.widget.CodeEditor(this);
        code.setTypefaceText(Typeface.MONOSPACE);
        code.setTypefaceLineNumber(Typeface.MONOSPACE);
        code.setTextSize(13f);
        code.setTabWidth(4);
        code.setWordwrap(false);
        code.setHighlightCurrentLine(true);
        code.setLigatureEnabled(false);
        // 主题 + JS 语法：照它 CodeEditorHelper.initEditor() 来的（assets/textmate 那几份也是它的）
        try {
            com.mermergi.ttsrelay.js.EditorSetup.init(getAssets(), code);
        } catch (Throwable t) {
            LogBus.error("编辑器主题/语法没装上：" + t.getMessage());
            android.util.Log.w("ttsrelay", "编辑器 TextMate 没装上", t);
        }
        // 兜底：不管 TextMate 成没成，关键颜色都按我们的深色主题再压一遍，
        // 免得主题没加载时字和底色一个色 → 看着像白板
        try {
            io.github.rosemoe.sora.widget.schemes.EditorColorScheme sc = code.getColorScheme();
            if (sc == null) {
                sc = new io.github.rosemoe.sora.widget.schemes.EditorColorScheme();
                code.setColorScheme(sc);
            }
            sc.setColor(io.github.rosemoe.sora.widget.schemes.EditorColorScheme.WHOLE_BACKGROUND, p.card);
            sc.setColor(io.github.rosemoe.sora.widget.schemes.EditorColorScheme.TEXT_NORMAL, p.textPrimary);
            sc.setColor(io.github.rosemoe.sora.widget.schemes.EditorColorScheme.LINE_NUMBER, p.textMuted);
            sc.setColor(io.github.rosemoe.sora.widget.schemes.EditorColorScheme.LINE_NUMBER_BACKGROUND, p.card);
            sc.setColor(io.github.rosemoe.sora.widget.schemes.EditorColorScheme.CURRENT_LINE, p.cardHi);
            code.setColorScheme(sc);
            android.util.Log.i("ttsrelay", "编辑器自检：配色=" + sc.getClass().getSimpleName()
                    + " 底色=" + Integer.toHexString(sc.getColor(
                            io.github.rosemoe.sora.widget.schemes.EditorColorScheme.WHOLE_BACKGROUND))
                    + " 字色=" + Integer.toHexString(sc.getColor(
                            io.github.rosemoe.sora.widget.schemes.EditorColorScheme.TEXT_NORMAL)));
        } catch (Throwable t) {
            android.util.Log.w("ttsrelay", "编辑器配色压不上", t);
        }
        // 注意：ui.weight() 是给"横向"行用的（width=0 + weight）。
        // root 是竖向的，用它会得到宽度 0 → 编辑器整块看不见（就是"代码区一片空"的真凶）。
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        wp.topMargin = ui.dp(6);
        code.setBackground(ui.round(p.card, p.border, 12));
        root.addView(code, wp);

        code.getText().addContentListener(new io.github.rosemoe.sora.text.ContentListener() {
            public void beforeReplace(io.github.rosemoe.sora.text.Content content) { }
            public void afterInsert(io.github.rosemoe.sora.text.Content content, int startLine,
                                    int startColumn, int endLine, int endColumn, CharSequence text) {
                syncGutter();
            }
            public void afterDelete(io.github.rosemoe.sora.text.Content content, int startLine,
                                    int startColumn, int endLine, int endColumn, CharSequence text) {
                syncGutter();
            }
        });

        // ── 缩进工具条 ──
        LinearLayout keys = ui.row();
        keys.setPadding(0, ui.dp(6), 0, 0);
        String[] ins = {"TAB", "=", ">", "{", "}", "(", ")", ",", "\"", "'", ";"};
        for (final String k : ins) {
            TextView chip = ui.chip(k, false, new View.OnClickListener() {
                public void onClick(View v) {
                    insert("TAB".equals(k) ? "    " : k);
                }
            });
            chip.setSingleLine(true);
            chip.setGravity(Gravity.CENTER);
            chip.setTextSize(12f);
            LinearLayout.LayoutParams kp = ui.weight(1f);
            kp.leftMargin = ui.dp(2);
            keys.addView(chip, kp);
        }
        root.addView(keys);

        setContentView(root);
    }

    @Override protected void onDestroy() {
        try { if (inlineBridge != null) inlineBridge.destroy(); } catch (Throwable ignored) { }
        try { if (uiBridge != null) uiBridge.destroy(); } catch (Throwable ignored) { }
        super.onDestroy();
    }

    /** 顶栏那些小方键（普通 Button 在这个高度太占地）。 */
    private TextView tinyKey(String s, View.OnClickListener l) {
        TextView t = ui.chip(s, false, l);
        t.setSingleLine(true);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(15f);
        t.setLayoutParams(new LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)));
        return t;
    }

    /** 状态行（行号由 sora-editor 自己画）。 */
    private void syncGutter() {
        io.github.rosemoe.sora.text.Content c = code.getText();
        status.setText(label() + " · " + c.getLineCount() + " 行 · " + c.length() + " 字符"
                + " · 变量 " + varCount() + " 个");
    }

    private String label() {
        String s = nameBox == null ? "" : nameBox.getText().toString().trim();
        return s.length() == 0 ? "插件" : s;
    }

    private int varCount() {
        int n = 0;
        try {
            JSONObject d = new JSONObject(it.s("def_vars", ""));
            n = d.length();
        } catch (Throwable ignored) { }
        if (n == 0) {
            try { n = new JSONObject(it.s("var_labels", "")).length(); } catch (Throwable ignored) { }
        }
        return n;
    }

    // ───────────────────────── 编辑动作 ─────────────────────────

    private void load() {
        loading = true;
        nameBox.setText(it.label);
        code.setText(it.s("src", ""));
        loading = false;
        android.util.Log.i("ttsrelay", "编辑器自检：语言=" + code.getEditorLanguage()
                + " 字数=" + code.getText().length()
                + " 行数=" + code.getText().getLineCount()
                + " 字号px=" + code.getTextSizePx());
        history.clear();
        history.add(code.getText().toString());
        histPos = 0;
        syncGutter();
    }

    private void snapshot() {
        if (loading) return;
        String now = code.getText().toString();
        if (histPos >= 0 && histPos < history.size() && history.get(histPos).equals(now)) return;
        while (history.size() > histPos + 1) history.remove(history.size() - 1);
        history.add(now);
        if (history.size() > 60) history.remove(0);
        histPos = history.size() - 1;
    }

    /** 撤销重做用 sora 自己的历史（它记的比我这版细）。 */
    private void undo() {
        try { code.undo(); syncGutter(); } catch (Throwable t) { toast("没法撤销了"); }
    }

    private void redo() {
        try { code.redo(); syncGutter(); } catch (Throwable t) { toast("没法重做了"); }
    }

    /** 工具条按键：在光标处插一段（sora 的 commitText 就是干这个的）。 */
    private void insert(String s) {
        snapshot();
        code.commitText(s);
        syncGutter();
    }

    /** ▶ 试听：先存，再让当前引擎（切成它就对了）念一句。 */
    private void listen() {
        save();
        Speaker spk = RelayService.speakerOrNull();
        if (spk == null) { toast("先启动中转（首页那个开关）"); return; }
        EngineStore.setActive(this, it.id);
        String prob = EngineStore.build(it).problem(this);
        if (prob != null) { toast("⚠ " + prob); return; }
        spk.feed(TEST_TEXT, null);
        toast("已排队（" + it.label + "）");
    }

    private void save() {
        if (it == null) return;
        String nm = nameBox.getText().toString().trim();
        if (nm.length() > 0) it.label = nm;
        String before = it.s("src", "");
        it.put("src", code.getText().toString());
        EngineStore.syncUserVars(it);
        EngineStore.put(this, it);
        // 源码/变量动过 → 插件宿主缓存作废（合成时存 tts_data 不会走到这儿）
        if (!before.equals(it.s("src", ""))) {
            try { com.mermergi.ttsrelay.JsTtsEngine.clearHosts(); } catch (Throwable ignored) { }
        }
    }

    // ───────────────────────── ⋮ 菜单 ─────────────────────────

    private void menu(View anchor) {
        PopupMenu m = new PopupMenu(this, anchor);
        m.getMenu().add("插件界面（插件自己画的控件）");
        m.getMenu().add("设置变量…");
        m.getMenu().add("选择语言…");
        m.getMenu().add("看插件信息");
        m.getMenu().add("粘贴源码");
        m.getMenu().add("复制源码");
        m.getMenu().add("清空源码");
        m.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            public boolean onMenuItemClick(android.view.MenuItem item) {
                String t = String.valueOf(item.getTitle());
                if (t.startsWith("插件界面")) { save(); pluginUiDialog(); }
                else if ("设置变量…".equals(t)) { save(); varsDialog(); }
                else if ("选择语言…".equals(t)) { save(); localeDialog(); }
                else if ("看插件信息".equals(t)) { infoDialog(); }
                else if ("粘贴源码".equals(t)) { paste(); }
                else if ("复制源码".equals(t)) { copy(); }
                else if ("清空源码".equals(t)) { clear(); }
                return true;
            }
        });
        m.show();
    }

    /** 那个插件自己带的界面实例（弹窗关掉前别让它被回收）。 */
    private com.mermergi.ttsrelay.js.PluginBridge uiBridge;
    /** 内嵌在页面里的插件界面（和 TTS Server 一样，不用再开弹窗）。 */
    private LinearLayout pluginInline;
    private com.mermergi.ttsrelay.js.PluginBridge inlineBridge;
    private boolean inlineOpen = false;

    /** 展开/收起内嵌的插件界面。 */
    private void toggleInline() {
        inlineOpen = !inlineOpen;
        if (!inlineOpen) {
            pluginInline.setVisibility(View.GONE);
            if (inlineBridge != null) {
                try { inlineBridge.destroy(); } catch (Throwable ignored) { }
                inlineBridge = null;
            }
            return;
        }
        save();
        new Thread(new Runnable() {
            public void run() {
                final com.mermergi.ttsrelay.js.PluginBridge h;
                try {
                    // 走引擎那份配置：变量、每音色数据都带上，界面里的输入框才有值
                    h = com.mermergi.ttsrelay.JsTtsEngine.openFor(PluginEditorActivity.this, it,
                            it.s("lang", "zh-CN"), it.s("voice", ""));
                } catch (Throwable t) {
                    runOnUiThread(new Runnable() { public void run() { toast("插件界面起不来：" + t.getMessage()); } });
                    return;
                }
                runOnUiThread(new Runnable() {
                    public void run() {
                        LinearLayout host = h.getUiContainer();
                        if (host == null || host.getChildCount() == 0) {
                            toast("这个插件没有自己的界面");
                            inlineOpen = false;
                            try { h.destroy(); } catch (Throwable ignored) { }
                            return;
                        }
                        ViewGroup par = (ViewGroup) host.getParent();
                        if (par != null) par.removeView(host);
                        if (inlineBridge != null) {
                            try { inlineBridge.destroy(); } catch (Throwable ignored) { }
                        }
                        inlineBridge = h;
                        pluginInline.removeAllViews();
                        pluginInline.addView(host, new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                        pluginInline.setVisibility(View.VISIBLE);
                    }
                });
            }
        }).start();
    }

    /** 变量面板 —— 登录回来要关掉重开，否则框里还是登录前的空值，一点保存就把新值盖回去。 */
    private AlertDialog varsDlg;

    /**
     * 把插件自己的界面显示出来 —— 照它 `PluginTtsUI` 里的 previewUi：
     * 建一个容器，调插件的 `onLoadUI(context, container)`，它往里塞自己的控件。
     * 火山那个插件的 BV 输入框就活在这儿（选 custom 音色才用得到）。
     */
    private void pluginUiDialog() {
        try {
            uiBridge = new com.mermergi.ttsrelay.js.PluginBridge(this, it.id,
                    code.getText().toString(), null, null, it.s("lang", "zh-CN"), "");
        } catch (Throwable t) {
            toast("插件界面起不来：" + t.getMessage());
            return;
        }
        LinearLayout host = uiBridge.getUiContainer();
        if (host == null || host.getChildCount() == 0) {
            toast("这个插件没有自己的界面（onLoadUI 是空的）");
            return;
        }
        ScrollView sv = new ScrollView(this);
        host.setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8));
        sv.addView(host);
        new AlertDialog.Builder(this, dialogTheme())
                .setTitle("插件界面")
                .setView(sv)
                .setPositiveButton("好", null)
                .show();
    }

    /** 设置变量 —— 就是 TTS Server 里「更多选项 → 设置变量」，显示名用它引擎文件里的 name。 */
    private void varsDialog() {
        EngineStore.syncJsMeta(this, it);
        final JSONObject defs = defs();
        final JSONObject labels = labels();
        if (defs.length() == 0 && labels.length() == 0) {
            toast("这个插件没声明变量");
            return;
        }
        LinearLayout box = ui.col();
        box.setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(6));
        ScrollView sv = new ScrollView(this);
        sv.addView(box);

        final List<String> keys = new ArrayList<String>();
        final List<EditText> boxes = new ArrayList<EditText>();
        final List<String> logins = new ArrayList<String>();
        Iterator<String> ks = defs.length() > 0 ? defs.keys() : labels.keys();
        while (ks.hasNext()) {
            final String k = ks.next();
            JSONObject d = defs.optJSONObject(k);
            String show = d == null ? "" : d.optString("name", "");
            if (show.length() == 0) show = labels.optString(k, k);
            String hint = d == null ? "" : d.optString("hint", "");
            final String loginUrl = d == null ? "" : d.optString("loginUrl", "");

            box.addView(ui.label(show + (hint.length() > 0 ? "（" + hint + "）" : "")));
            EditText e = ui.area("");
            e.setMinLines(1);
            e.setSingleLine(true);
            e.setText(it.s(k, ""));
            // 输入框右边一个 → ：填完这一项立刻存这一项（照它那个界面的样子）
            LinearLayout line = ui.row();
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.addView(e, ui.weight(1f));
            line.addView(ui.btn("→", true, new View.OnClickListener() {
                public void onClick(View v) {
                    int idx = keys.indexOf(k);
                    if (idx >= 0) {
                        it.put(k, boxes.get(idx).getText().toString().trim());
                        save();
                        toast("已保存「" + k + "」");
                    }
                }
            }));
            box.addView(line);
            // ua 绑定：插件要一个 User-Agent，给个一键填手机 UA 的按钮
            String binding = d == null ? "" : d.optString("binding", "");
            if ("ua".equals(binding) || "ua".equals(k)) {
                box.addView(ui.chip("填手机 UA", false, new View.OnClickListener() {
                    public void onClick(View v) {
                        int idx = keys.indexOf(k);
                        if (idx >= 0) boxes.get(idx).setText(MOBILE_UA);
                    }
                }));
            }
            keys.add(k);
            boxes.add(e);
            logins.add(loginUrl);
            if (loginUrl.length() > 0) {
                box.addView(ui.btn("网页登录，自动填这一项", false, new View.OnClickListener() {
                    public void onClick(View v) {
                        save();
                        pendingLoginKey = k;
                        Intent i = new Intent(PluginEditorActivity.this, LoginActivity.class);
                        i.putExtra(LoginActivity.EXTRA_URL, loginUrl);
                        i.putExtra(LoginActivity.EXTRA_BINDING,
                                defs.optJSONObject(k) == null ? "user_input"
                                        : defs.optJSONObject(k).optString("binding", "user_input"));
                        i.putExtra(LoginActivity.EXTRA_TITLE, "登录完点右上角那个按钮");
                        try { startActivityForResult(i, REQ_LOGIN); }
                        catch (Throwable t) { toast("打不开登录页：" + t.getMessage()); }
                    }
                }));
            }
        }

        varsDlg = new AlertDialog.Builder(this, dialogTheme())
                .setTitle("设置变量")
                .setView(sv)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        for (int i = 0; i < keys.size(); i++) {
                            it.put(keys.get(i), boxes.get(i).getText().toString().trim());
                        }
                        save();
                        syncGutter();
                        toast("变量已保存");
                    }
                })
                .setNeutralButton("直接打开登录页", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { openFirstLogin(logins); }
                })
                .setNegativeButton("取消", null)
                .create();
        varsDlg.show();
    }

    private String pendingLoginKey = "";

    /** ua 绑定用的手机 UA。 */
    static final String MOBILE_UA = "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    private void openFirstLogin(List<String> logins) {
        for (int i = 0; i < logins.size(); i++) {
            if (logins.get(i).length() > 0) {
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(logins.get(i)))); }
                catch (Throwable t) { toast("打不开：" + t.getMessage()); }
                return;
            }
        }
        toast("这个插件没有登录页");
    }

    /** 选择语言：插件的 getLocales() 说了算（它们界面就是这么给的）。 */
    private void localeDialog() {
        final String src = code.getText().toString();
        toast("正在问插件的语言列表…");
        new Thread(new Runnable() {
            public void run() {
                final List<String> out = new ArrayList<String>();
                com.mermergi.ttsrelay.js.PluginBridge h = null;
                try {
                    h = new com.mermergi.ttsrelay.js.PluginBridge(PluginEditorActivity.this, it.id,
                            src, null, null, it.s("lang", "zh-CN"), "");
                    out.addAll(h.locales().keySet());
                } catch (Throwable t) {
                    LogBus.error("问语言列表失败：" + t.getMessage());
                } finally {
                    if (h != null) { try { h.destroy(); } catch (Throwable ignored) { } }
                }
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (out.isEmpty()) { toast("插件没报语言列表，直接用默认 zh-CN"); return; }
                        final String[] arr = out.toArray(new String[0]);
                        new AlertDialog.Builder(PluginEditorActivity.this, dialogTheme())
                                .setTitle("语言")
                                .setItems(arr, new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface d, int w) {
                                        it.put("lang", arr[w]);
                                        save();
                                        toast("语言：" + arr[w]);
                                    }
                                })
                                .setNegativeButton("取消", null)
                                .show();
                    }
                });
            }
        }).start();
    }

    private void infoDialog() {
        EngineStore.syncJsMeta(this, it);
        StringBuilder sb = new StringBuilder();
        sb.append("名字：").append(it.label).append('\n');
        sb.append("作者：").append(it.s("author", "（插件没写）")).append('\n');
        sb.append("语言：").append(it.s("lang", "zh-CN")).append('\n');
        sb.append("声明变量：").append(varCount()).append(" 个\n");
        try {
            JSONObject d = defs();
            Iterator<String> k = d.keys();
            while (k.hasNext()) {
                String key = k.next();
                sb.append("　").append(key).append(" → ")
                        .append(d.optJSONObject(key) == null ? "" : d.optJSONObject(key).optString("name"))
                        .append('\n');
            }
        } catch (Throwable ignored) { }
        new AlertDialog.Builder(this, dialogTheme())
                .setTitle("插件信息")
                .setMessage(sb.toString())
                .setPositiveButton("好", null)
                .show();
    }

    private JSONObject defs() {
        try { return new JSONObject(it.s("def_vars", "")); } catch (Throwable t) { return new JSONObject(); }
    }

    private JSONObject labels() {
        try { return new JSONObject(it.s("var_labels", "")); } catch (Throwable t) { return new JSONObject(); }
    }

    // ───────────────────────── 杂项 ─────────────────────────

    private void paste() {
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    getSystemService(CLIPBOARD_SERVICE);
            if (cm == null || cm.getPrimaryClip() == null
                    || cm.getPrimaryClip().getItemCount() == 0) { toast("剪贴板是空的"); return; }
            String s = String.valueOf(cm.getPrimaryClip().getItemAt(0).coerceToText(this));
            snapshot();
            code.setText(s);
            syncGutter();
            toast("粘好了，记得保存");
        } catch (Throwable t) {
            toast("粘贴失败：" + t.getMessage());
        }
    }

    private void copy() {
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(android.content.ClipData.newPlainText("plugin",
                        code.getText().toString()));
                toast("源码已复制");
            }
        } catch (Throwable t) {
            toast("复制失败：" + t.getMessage());
        }
    }

    private void clear() {
        new AlertDialog.Builder(this, dialogTheme())
                .setTitle("清空源码？")
                .setMessage("插件源码会被清掉，引擎就跑不起来了。")
                .setPositiveButton("清空", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        snapshot();
                        code.setText("");
                        syncGutter();
                    }
                })
                .setNegativeButton("算了", null)
                .show();
    }

    private int dialogTheme() {
        return p.light ? android.R.style.Theme_Material_Light_Dialog_Alert
                : android.R.style.Theme_Material_Dialog_Alert;
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_LOGIN && res == RESULT_OK && data != null) {
            String v = data.getStringExtra(LoginActivity.RESULT_VALUE);
            if (v != null && v.trim().length() > 0 && pendingLoginKey.length() > 0) {
                it.put(pendingLoginKey, v.trim());
                it.put("login_key", pendingLoginKey);
                EngineStore.syncUserVars(it);
                EngineStore.put(this, it);
                toast("已填入「" + pendingLoginKey + "」（" + v.trim().length() + " 字符）");
                // 关键：登录前开的那个面板里还是旧值，关掉重开 —— 不然一点保存就把它盖回去
                if (varsDlg != null) {
                    try { varsDlg.dismiss(); } catch (Throwable ignored) { }
                    varsDlg = null;
                }
                code.post(new Runnable() {
                    public void run() { varsDialog(); }
                });
            } else if (pendingLoginKey.length() > 0) {
                toast("没抓到东西（登录页可能还没登录完）");
            }
        }
    }

    @Override protected void onPause() {
        super.onPause();
        save();          // 返回键 / 切后台都存，别让用户白编辑
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
