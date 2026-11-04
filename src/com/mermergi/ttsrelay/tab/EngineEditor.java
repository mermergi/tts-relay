package com.mermergi.ttsrelay.tab;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.text.InputType;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.mermergi.ttsrelay.ChoiceSheet;
import com.mermergi.ttsrelay.CookieGrab;
import com.mermergi.ttsrelay.EngineImport;
import com.mermergi.ttsrelay.EngineStore;
import com.mermergi.ttsrelay.HardCall;
import com.mermergi.ttsrelay.JsTtsEngine;
import com.mermergi.ttsrelay.LogBus;
import com.mermergi.ttsrelay.LoginActivity;
import com.mermergi.ttsrelay.Palette;
import com.mermergi.ttsrelay.Prefs;
import com.mermergi.ttsrelay.PluginEditorActivity;
import com.mermergi.ttsrelay.PluginTtsActivity;
import com.mermergi.ttsrelay.R;
import com.mermergi.ttsrelay.RelayService;
import com.mermergi.ttsrelay.Speaker;
import com.mermergi.ttsrelay.TtsClient;
import com.mermergi.ttsrelay.TtsEngine;
import com.mermergi.ttsrelay.Ui;
import com.mermergi.ttsrelay.VoiceListAdapter;
import com.mermergi.ttsrelay.VoiceItem;
import com.mermergi.ttsrelay.ui.Form;
import com.mermergi.ttsrelay.ui.Widgets;
import java.util.ArrayList;
import java.util.List;

/** 引擎编辑机制：整页编辑框、插件面板、Cookie 抓取、变量/音色键值管理。 */
public final class EngineEditor {
    private final Ui ui;
    private final Palette p;
    private final Tab.Host host;
    private final EngineTab engTab;

    public static final int REQ_PICK_JS = 61;
    public static final int REQ_LOGIN = 53;
    private EngineStore.Item pendingJsPick;
    private String pendingLoginEngineId = null;
    /** 当前开着的「编辑引擎」框。只允许有一个 —— 叠加起来的话，
     *  关掉上面那个就会露出下面那个还挂着旧状态的框，看着就像"没保存"。 */
    private AlertDialog engineDlg = null;
    private static final java.util.Set<String> JS_HIDE = new java.util.HashSet<String>(
            java.util.Arrays.asList("headers", "cred", "voices", "voices_url", "voices_path",
                    "voices_name", "voices_id", "var_labels", "def_vars", "user_vars", "tts_data",
                    "login_key", "login_url", "binding", "ua", "author", "icon"));

    private Activity act() { return host.activity(); }

    public EngineEditor(Ui ui, Palette p, Tab.Host host, EngineTab engTab) {
        this.ui = ui;
        this.p = p;
        this.host = host;
        this.engTab = engTab;
    }

    public void onActivityResult(int req, int res, Intent data) {
        if (req == REQ_PICK_JS) {
if (res == Activity.RESULT_OK && data != null && data.getData() != null && pendingJsPick != null) {
                try {
                    String src = readText(data.getData());
                    if (src == null || src.trim().length() == 0) { host.toast("文件是空的"); return; }
                    EngineStore.Item e2 = pendingJsPick;
                    e2.kind = EngineStore.KIND_JS;
                    e2.put("src", src);
                    EngineStore.syncJsMeta(act(), e2);      // 顺手把它的名字/变量读出来
                    EngineStore.inheritLogin(act(), e2);    // 旧引擎里抓过的 Cookie 直接继承
                    com.mermergi.ttsrelay.JsTtsEngine.clearHosts();
                    EngineStore.put(act(), e2);
                    engTab.renderEngines();
                    host.toast("已变成 JS 插件：" + e2.label);
                    openPluginEditor(e2, false);
                } catch (Throwable t) {
                    host.toast("读文件失败：" + t.getMessage());
                } finally {
                    pendingJsPick = null;
                }
            }
            return;
        }

        if (req == REQ_LOGIN) {
            String v = data == null ? null : data.getStringExtra(LoginActivity.RESULT_VALUE);
            if (v == null || v.trim().length() == 0) { host.toast("没取到内容，先登录再点「取回」"); return; }
            EngineStore.Item it = EngineStore.get(act(), pendingLoginEngineId);
            if (it == null) { host.toast("找不到那个引擎了"); return; }
            String val = v.trim();
            it.put(loginTarget(it), val);
            EngineStore.put(act(), it);
            engTab.renderEngines();
            LogBus.add("网页登录取回成功，已写入「" + it.label + "」：" + CookieGrab.summary(val));
            host.toast("已写入「" + it.label + "」\n" + CookieGrab.summary(val));
            // 关键：刚才那个编辑框是登录前开的，里面还挂着旧状态（"未设置"）。
            // 不重开的话，用户看到的就是"没写进去"。
            try { editEngine(it); } catch (Throwable ignored) { }
            return;
        }
    }

    public void editEngine(final EngineStore.Item it) {
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
            EngineStore.syncJsMeta(act(), it);

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

        final List<Form.Fld> flds = Form.buildForm(act(), ui, box, spec.toArray(new String[0][]));

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
                        Intent i = new Intent(act(), LoginActivity.class);
                        i.putExtra(LoginActivity.EXTRA_URL,
                                it.s("login_url", "about:blank"));
                        i.putExtra(LoginActivity.EXTRA_BINDING, it.s("binding", "cookies"));
                        i.putExtra(LoginActivity.EXTRA_UA, it.s("ua", "mobile"));
                        i.putExtra(LoginActivity.EXTRA_TITLE,
                                "登录完点右上角「取回」（绑定：" + it.s("binding", "cookies") + "）");
                        try { act().startActivityForResult(i, REQ_LOGIN); }
                        catch (Throwable t) { host.toast("打不开登录页：" + t.getMessage()); }
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
                    EngineStore.put(act(), it);
                            refresh.run();
                    engTab.renderEngines();
                    host.toast("已清空");
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

        ScrollView sv = new ScrollView(act());
        sv.addView(box);
        engineDlg = new AlertDialog.Builder(act(), host.dialogTheme())
                .setTitle("编辑：" + engTab.kindName(it.kind))
                .setView(sv)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        applyEditor(it, nameBox, flds, extraKeys);
                        host.toast("已保存");
                    }
                })
                .setNeutralButton("测一下", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        EngineStore.setActive(act(), it.id);
                        engTab.renderEngines();
                        engTab.testEngine();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }
    private void pluginSheet(final EngineStore.Item it) {
        if (!EngineStore.KIND_JS.equals(it.kind) && it.s("src", "").trim().length() == 0) {
            host.toast("这个引擎没有插件界面");
            return;
        }
        final LinearLayout box = ui.col();
        box.setBackground(ui.sheetBg());
        box.setPadding(ui.dp(16), 0, ui.dp(16), ui.dp(16));

        ImageView handle = new ImageView(act());
        handle.setImageResource(R.drawable.mtrl_bottomsheet_drag_handle);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(ui.dp(32), ui.dp(4));
        hlp.gravity = Gravity.CENTER_HORIZONTAL;
        hlp.topMargin = ui.dp(10);
        box.addView(handle, hlp);

        final EditText nameBox = ui.field(null, it.label == null ? "" : it.label, InputType.TYPE_CLASS_TEXT);
        nameBox.setHint("显示名称");
        box.addView(nameBox, ui.topGap(12));

        final EditText tryBox = ui.field(null, Prefs.tryText(act()), InputType.TYPE_CLASS_TEXT);
        tryBox.setHint("试听文本");
        final String[] curVoice = new String[]{it.s("voice", "")};
        LinearLayout tr = ui.row();
        tr.setGravity(Gravity.CENTER_VERTICAL);
        tr.addView(tryBox, ui.weightGap(1f, 8));
        tr.addView(ui.iconChip(R.drawable.ic_notif_play, new View.OnClickListener() {
            public void onClick(View v) {
                String t = tryBox.getText().toString().trim();
                if (t.length() == 0) return;
                Prefs.setTryText(act(), t);
                Speaker spk = RelayService.speakerOrNull();
                if (spk == null) { host.toast("朗读没在跑"); return; }
                spk.interrupt();
                spk.feed(t, TtsEngine.shortVoice(curVoice[0]));
            }
        }));
        box.addView(tr, ui.topGap(10));

        // ── 语言 / 声音：选项都是问插件要的 ──
        final TextView langVal = new TextView(act());
        final TextView voiceVal = new TextView(act());
        final String[] lang = new String[]{it.s("lang", "zh-CN")};
        langVal.setText(lang[0]);
        voiceVal.setText(TtsEngine.shortVoice(curVoice[0]));
        final LinearLayout langRow = Widgets.kvRow(act(), ui, p, "语言", langVal);
        final LinearLayout voiceRow = Widgets.kvRow(act(), ui, p, "声音", voiceVal);
        box.addView(langRow, ui.topGap(10));
        box.addView(voiceRow, ui.topGap(8));

        // ── 插件自己画的界面挂这儿 ──
        final LinearLayout sheetHost = ui.col();
        box.addView(sheetHost, ui.topGap(10));

        // ── 这个引擎自己的语速 / 音量 / 音高 ──
        box.addView(Widgets.paramSlider(act(), ui, p, "语速", "speed", it), ui.topGap(6));
        box.addView(Widgets.paramSlider(act(), ui, p, "音量", "vol", it));
        box.addView(Widgets.paramSlider(act(), ui, p, "音高", "pitch_", it));

        // 这个抽屉自己开的插件宿主（按当前音色），保存/关掉时要把它的 ttsrv.tts.data 存回去
        final com.mermergi.ttsrelay.js.PluginBridge[] hostRef =
                new com.mermergi.ttsrelay.js.PluginBridge[1];
        box.addView(ui.btn("保存", true, new View.OnClickListener() {
            public void onClick(View v) {
                String nm = nameBox.getText().toString().trim();
                it.label = nm.length() == 0 ? "插件 TTS" : nm;
                it.put("lang", lang[0]);
                it.put("voice", curVoice[0]);
                EngineStore.put(act(), it);
                // 插件界面上改的东西（豆包那个「自定义语气指令」存在 ttsrv.tts.data 里）
                JsTtsEngine.persistData(act(), it, curVoice[0], hostRef[0]);
                JsTtsEngine.clearVoices();
                engTab.renderEngines();
                host.dismissSheet();
            }
        }), ui.topGap(16));

        Dialog dlg = host.sheet(box);
        // 手势划走 / 返回键关掉也一样存（跟单音色编辑页 onPause 的行为对齐）
        try {
            dlg.setOnDismissListener(new DialogInterface.OnDismissListener() {
                public void onDismiss(DialogInterface d) {
                    JsTtsEngine.persistData(act(), it, curVoice[0], hostRef[0]);
                }
            });
        } catch (Throwable ignored) { }

        // 起插件慢（要 eval 整个脚本），后台跑完再填
        new Thread(new Runnable() {
            public void run() {
                final com.mermergi.ttsrelay.js.PluginBridge h;
                try {
                    h = JsTtsEngine.openFor(act(), it, lang[0], curVoice[0]);
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
                host.runOnUi(new Runnable() {
                    public void run() {
                        bindLangRow(langRow, locs, lang, langVal, voiceRow, voiceVal, curVoice, h, it);
                        bindVoiceRow(voiceRow, vs, curVoice, voiceVal, lang, h, it);
                        try {
                            android.view.View v = h.getUiContainer();
                            if (v != null) {
                                android.view.ViewGroup par = (android.view.ViewGroup) v.getParent();
                                if (par != null) par.removeView(v);
                                sheetHost.removeAllViews();
                                sheetHost.addView(v, new LinearLayout.LayoutParams(
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
                ChoiceSheet.show(act(), ui, p, host.sheetTheme(), "语言", null,
                        names, null, cur, new ChoiceSheet.OnPick() {
                            public void onPick(int i) {
                                lang[0] = codes[i];
                                langVal.setText(locs.get(codes[i]));
                                new Thread(new Runnable() {
                                    public void run() {
                                        final java.util.List<String[]> nv;
                                        try { nv = h.voices(lang[0]); } catch (Throwable t) { return; }
                                        host.runOnUi(new Runnable() {
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
                ChoiceSheet.show(act(), ui, p, host.sheetTheme(), "声音", null,
                        names, null, cur, new ChoiceSheet.OnPick() {
                            public void onPick(int i) {
                                try { JsTtsEngine.persistData(act(), it, curVoice[0], h); }
                                catch (Throwable ignored) { }
                                curVoice[0] = vs.get(i)[1];
                                voiceVal.setText(vs.get(i)[0]);
                                try { h.onVoiceChanged(lang[0], curVoice[0]); } catch (Throwable ignored) { }
                            }
                        });
            }
        });
    }
    /** 读剪贴板，读不到就空串。 */
    private String readClip() {
        try {
            ClipboardManager cm = (ClipboardManager) act().getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || cm.getPrimaryClip() == null
                    || cm.getPrimaryClip().getItemCount() == 0) return "";
            CharSequence cs = cm.getPrimaryClip().getItemAt(0).coerceToText(act());
            return cs == null ? "" : cs.toString();
        } catch (Throwable t) {
            return "";
        }
    }
    /** 登录抓回来的值写进哪个配置键：JS 插件写它声明的那个变量（和 TTS Server 一样）。 */
    private static String loginTarget(EngineStore.Item it) {
        String k = it.s("login_key", "");
        if (k.length() > 0) return k;
        return "cred";
    }
    /**
     * 登录值单独编辑：它太长，放表单里会把整屏撑爆，所以丢进自己的对话框。
     *
     * 只有一个「粘贴」按钮 —— 粘的是裸串就原样收，是 cURL / 请求头就自己摘出来，
     * 所以没必要让用户先自己选"智能提取"还是"原样粘贴"。保存时再整理一次兜底。
     */
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
                if (raw.trim().length() == 0) { host.toast("剪贴板是空的"); return; }
                String got = CookieGrab.tidy(raw);
                if (got.length() == 0) { host.toast("剪贴板是空的"); return; }
                e.setText(got);
                e.setSelection(e.getText().length());
                host.toast((got.equals(raw.trim()) ? "已粘贴（原样）\n" : "已粘贴（摘出 Cookie）\n")
                        + CookieGrab.summary(got));
            }
        }), Widgets.topGap(ui, 8));

        ScrollView sv = new ScrollView(act());
        sv.addView(box);
        new AlertDialog.Builder(act(), host.dialogTheme())
                .setTitle("Cookie")
                .setView(sv)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        String v = CookieGrab.tidy(e.getText().toString());
                        it.put(key, v);
                        EngineStore.put(act(), it);
                                    engTab.renderEngines();
                        if (onChange != null) onChange.run();   // 上面那块状态当场刷新
                        LogBus.add("Cookie 已保存（" + it.label + "）：" + CookieGrab.summary(v));
                        host.toast(v.length() == 0 ? "已清空" : "已保存（" + v.length() + " 字符）");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }
    /**
     * 插件声明的变量（它引擎文件里 {@code vars} 那些键）单独存一份 {@code user_vars} ——
     * TTS Server 就是"声明"和"用户填的值"分开存的：放进 cfg 的散键里，插件读
     * {@code ttsrv.userVars['api_key']} 时容易对不上。
     */
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
        EngineStore.put(act(), it);
        try { com.mermergi.ttsrelay.JsTtsEngine.clearHosts(); } catch (Throwable ignored) { }
        engTab.renderEngines();
    }
    private void addCfgKey(final EngineStore.Item it) {
        LinearLayout box = ui.col();
        box.setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(4));
        final EditText e = ui.field(null, "", InputType.TYPE_CLASS_TEXT);
        e.setTag(null);
        e.setHint("键名，比如 extra_param");
        box.addView(e);
        new AlertDialog.Builder(act(), host.dialogTheme())
                .setTitle("加一个配置键")
                .setView(box)
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        String k = e.getText().toString().trim();
                        if (k.length() == 0) return;
                        it.put(k, "");
                        EngineStore.put(act(), it);
                        engTab.renderEngines();
                        editEngine(it);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }
    /** 把引擎能报出来的音色列出来，方便复制到音色表里。 */
    public void showEngineVoices(final EngineStore.Item it) {
        host.toast("正在拉音色…");
        final Context ctx = act();
        new Thread(new Runnable() {
            public void run() {
                final List<String> vs;
                try {
                    vs = EngineStore.fetchVoices(ctx, it);
                } catch (Throwable t) {
                    final String m = String.valueOf(t.getMessage());
                    host.runOnUi(new Runnable() {
                        public void run() { host.toast("拉取失败：" + m); }
                    });
                    return;
                }
                host.runOnUi(new Runnable() {
                    public void run() {
                        if (vs.isEmpty()) { host.toast("这个引擎没报出音色，手动填就行"); return; }
                        final StringBuilder sb = new StringBuilder();
                        for (String v : vs) sb.append(TtsEngine.shortVoice(v)).append('\n');
                        new AlertDialog.Builder(act(), host.dialogTheme())
                                .setTitle(it.label + "（" + vs.size() + " 个）")
                                .setMessage(sb.toString())
                                .setPositiveButton("复制全部", new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface d, int w) {
                                        host.copy(sb.toString().trim());
                                    }
                                })
                                .setNegativeButton("关闭", null)
                                .show();
                    }
                });
            }
        }).start();
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
    public void openPluginEditor(final EngineStore.Item it, final boolean openVars) {
        // 没有插件源码的引擎（系统语音、HTTP 模板）：编辑器只管插件源码，
        // 所以这儿给一条「粘源码转成 JS 插件」的路 —— 照 TTS Server：引擎就是插件。
        if (it != null && it.s("src", "").trim().length() == 0
                && !EngineStore.KIND_JS.equals(it.kind)) {
            new AlertDialog.Builder(act(), host.dialogTheme())
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
                            EngineStore.put(act(), it);
                            engTab.renderEngines();
                            openPluginEditor(it, false);
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        Intent pi = new Intent(act(), PluginEditorActivity.class);
        pi.putExtra(PluginEditorActivity.EXTRA_ID, it.id);
        pi.putExtra(PluginEditorActivity.EXTRA_OPEN_VARS, openVars);
        try { act().startActivity(pi); } catch (Throwable t) { host.toast("打不开插件编辑器：" + t.getMessage()); }
    }
    /** 挑一份插件 .js（TTS Server 导出来的那种）→ 这个引擎就变成 JS 插件。 */
    private void pickPluginFile(EngineStore.Item it) {
        pendingJsPick = it;
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("*/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        try { act().startActivityForResult(Intent.createChooser(i, "选插件 .js"), REQ_PICK_JS); }
        catch (Throwable t) { host.toast("打不开文件选择器：" + t.getMessage()); }
    }
    private String readText(android.net.Uri uri) throws Exception {
        java.io.InputStream in = act().getContentResolver().openInputStream(uri);
        if (in == null) return null;
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return new String(bos.toByteArray(), "UTF-8");
    }
}
