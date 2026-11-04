package com.mermergi.ttsrelay.tab;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
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

/** 引擎页：引擎列表、增删导入导出、一键测试。编辑器在 EngineEditor。 */
public final class EngineTab implements Tab {
    private final Ui ui;
    private final Palette p;
    private final Tab.Host host;
    private final EngineEditor ed;

    private LinearLayout engineBox;
    private TextView engineNow;
    private String pendingExport = null;
    private boolean metaSyncing = false;
    private static final int REQ_IMPORT = 51;
    private static final int REQ_EXPORT = 52;
    private final java.util.Map<String, TextView> rowName = new java.util.HashMap<String, TextView>();
    private final java.util.Map<String, TextView> rowSub = new java.util.HashMap<String, TextView>();
    private final java.util.Map<String, TextView> rowNeed = new java.util.HashMap<String, TextView>();
    /** 引擎菜单里「编辑音色」那一项要开朗读页那套编辑器，壳把 VoicePicker 塞进来。 */
    public com.mermergi.ttsrelay.tab.VoicePicker voicePicker;

    private Activity act() { return host.activity(); }

    public EngineTab(Ui ui, Palette p, Tab.Host host) {
        this.ui = ui;
        this.p = p;
        this.host = host;
        this.ed = new EngineEditor(ui, p, host, this);
    }

    public EngineEditor editor() { return ed; }

    @Override
    public void refresh() { }

    @Override
    public void syncInputs() {
        renderEngines();          // 只在切页 / 回前台时画，别每秒都画
    }

    @Override
    public void clearRefs() {
        engineBox = null; engineNow = null;
        rowName.clear(); rowSub.clear(); rowNeed.clear();
    }

    /** 壳的 onActivityResult 全转到这里：SAF 归自己，编辑器那两个号转给 ed。 */
    public void onActivityResult(int req, int res, Intent data) {
        if (res != Activity.RESULT_OK) return;
        if (req == EngineEditor.REQ_PICK_JS || req == EngineEditor.REQ_LOGIN) {
            ed.onActivityResult(req, res, data);
            return;
        }
        if (data == null || data.getData() == null) return;
        android.net.Uri uri = data.getData();
        try {
        if (req == REQ_IMPORT) {
                java.io.InputStream in = act().getContentResolver().openInputStream(uri);
                if (in == null) { host.toast("读不到这个文件"); return; }
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                in.close();
                doImport(new String(bos.toByteArray(), "UTF-8"));
            } else if (req == REQ_EXPORT) {
                java.io.OutputStream os = act().getContentResolver().openOutputStream(uri);
                if (os == null) { host.toast("写不进这个位置"); return; }
                os.write(pendingExport.getBytes("UTF-8"));
                os.close();
                host.toast("已导出");
            }
        } catch (Throwable t) {
            host.toast("文件操作失败：" + t.getMessage());
        }
    }

    @Override
    public View build(Context ctx) {
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

        return Widgets.scroll(act(), ui, col);
    }
    private void addEngine() {
        ChoiceSheet.show(act(), ui, p, host.sheetTheme(), "添加引擎", null,
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
                        EngineStore.put(act(), it);
                        renderEngines();
                        ed.editEngine(it);
                    }
                });
    }
    public void testEngine() {
        final Speaker spk = RelayService.speakerOrNull();
        if (spk == null) { host.toast("先启动中转"); return; }
        final TtsEngine e = EngineStore.activeEngine(act());
        host.toast("正在问引擎…");
        // 同样不能在 UI 线程上问：JS 引擎会真跑一遍插件
        new Thread(new Runnable() {
            public void run() {
                final String prob = e == null ? "引擎起不来" : e.problem(act());
                host.runOnUi(new Runnable() {
                    public void run() {
                        if (prob != null) { host.toast("⚠ " + prob); return; }
                        spk.feed("这是当前语音引擎的测试，能听见就说明通了。", null);
                        host.toast("已排队（" + (e == null ? "?" : e.label()) + "）");
                    }
                });
            }
        }).start();
    }
    private void importFromFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "text/plain", "*/*"});
        try { act().startActivityForResult(i, REQ_IMPORT); }
        catch (Throwable t) { host.toast("打不开文件选择器：" + t.getMessage()); }
    }
    private void importFromClipboard() {
        try {
            ClipboardManager cm = (ClipboardManager) act().getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || cm.getPrimaryClip() == null
                    || cm.getPrimaryClip().getItemCount() == 0) {
                host.toast("剪贴板是空的");
                return;
            }
            CharSequence cs = cm.getPrimaryClip().getItemAt(0).coerceToText(act());
            doImport(cs == null ? "" : cs.toString());
        } catch (Throwable t) {
            host.toast("读剪贴板失败：" + t.getMessage());
        }
    }
    private void doImport(String text) {
        try {
            final List<EngineStore.Item> list = EngineImport.parse(text);
            StringBuilder sb = new StringBuilder();
            for (EngineStore.Item it : list) {
                // 同一个文件导两遍别再堆一份出来：认原 id，认不出就按「同名同类型」认
                if (it.id.length() == 0 || !EngineStore.has(act(), it.id)) {
                    EngineStore.Item same = EngineStore.sameLabel(act(), it.kind, it.label);
                    if (same != null) {
                        it.id = same.id;
                        String old = same.s("cookie", "");
                        if (old.length() > 0 && it.s("cookie", "").length() == 0) it.put("cookie", old);
                    }
                }
                EngineStore.put(act(), it);
                sb.append("· ").append(it.label)
                        .append("  (").append(kindName(it.kind)).append("\n");
            }
            renderEngines();
            new AlertDialog.Builder(act(), host.dialogTheme())
                    .setTitle("导入成功：加了 " + list.size() + " 个引擎")
                    .setMessage(sb.toString().trim()
                            + "\n\n在「引擎列表」里点它 → 设为当前引擎，就能用了。")
                    .setPositiveButton("知道了", null)
                    .show();
        } catch (Throwable t) {
            new AlertDialog.Builder(act(), host.dialogTheme())
                    .setTitle("导入失败")
                    .setMessage(String.valueOf(t.getMessage())
                            + "\n\n支持：本 App 导出的 JSON、阅读(Legado)的朗读引擎 JSON。")
                    .setPositiveButton("知道了", null)
                    .show();
        }
    }
    private void exportMenu() {
        ChoiceSheet.show(act(), ui, p, host.sheetTheme(), "导出 / 分享", null,
                new String[]{"复制本 App 格式", "复制成阅读(Legado)格式", "导出到文件…"},
                new String[]{"换机能原样恢复（含插件脚本）",
                        "别人 / TTS Server 都能直接导入",
                        "存成 .json，自己挑位置"},
                -1, new ChoiceSheet.OnPick() {
                    public void onPick(int which) {
                        if (which == 0) {
                            host.copy(EngineImport.exportOurs(EngineStore.all(act())));
                        } else if (which == 1) {
                            host.copy(EngineImport.exportLegado(EngineStore.all(act())));
                        } else {
                            exportToFile();
                        }
                    }
                });
    }
    private void exportToFile() {
        pendingExport = EngineImport.exportOurs(EngineStore.all(act()));
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/json");
        i.putExtra(Intent.EXTRA_TITLE, "tts-relay-engines.json");
        try { act().startActivityForResult(i, REQ_EXPORT); }
        catch (Throwable t) { host.toast("打不开文件保存器：" + t.getMessage()); }
    }
    public static String kindName(String kind) {
        if (EngineStore.KIND_JS.equals(kind)) return "JS 插件";
        if (EngineStore.KIND_HTTP.equals(kind)) return "HTTP 接口";
        return "系统语音";
    }
    private void syncJsMetaInBackground() {
        if (metaSyncing) return;
        final java.util.List<EngineStore.Item> todo = new java.util.ArrayList<EngineStore.Item>();
        for (EngineStore.Item it : EngineStore.all(act())) {
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
                    try { if (EngineStore.syncJsMeta(act(), it)) changed = true; }
                    catch (Throwable ignored) { }
                }
                final boolean c = changed;
                host.runOnUi(new Runnable() {
                    public void run() {
                        metaSyncing = false;
                        if (c) refreshEngineRows();
                    }
                });
            }
        }).start();
    }
    public void renderEngines() {
        if (engineBox == null) return;
        engineBox.removeAllViews();
        rowName.clear(); rowSub.clear(); rowNeed.clear();
        String active = EngineStore.activeId(act());
        for (final EngineStore.Item it : EngineStore.all(act())) {
            // 一行 = 头像 + 名字/副标题/状态 + 铅笔 + ⋮ —— 照 TTS Server 的插件列表那样
            LinearLayout row = ui.row();
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(ui.dp(10), ui.dp(8), ui.dp(6), ui.dp(8));
            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    EngineStore.setActive(act(), it.id);
                    refreshEngineRows();
                    host.toast("当前引擎：" + it.label);
                }
            });

            // ☑ 启用/禁用 —— 照它插件列表那个勾选框；停用的整行压暗
            final android.widget.CheckBox cb = new android.widget.CheckBox(act());
            cb.setChecked(EngineStore.isEnabled(it));
            cb.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                    EngineStore.setEnabled(act(), it, on);
                    renderEngines();
                }
            });
            row.addView(cb, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            if (!EngineStore.isEnabled(it)) row.setAlpha(0.45f);

            TextView av = new TextView(act());
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
                public void onClick(View v) { ed.openPluginEditor(it, true); }
            });
            col.addView(need);
            rowNeed.put(it.id, need);

            row.addView(col, ui.weight(1f));

            row.addView(ui.btn("✎", false, new View.OnClickListener() {
                public void onClick(View v) { ed.editEngine(it); }
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
        EngineStore.warmVoices(act());
    }
    private void refreshEngineRows() {
        String active = EngineStore.activeId(act());
        for (EngineStore.Item it : EngineStore.all(act())) {
            boolean on = it.id.equals(active);
            TextView t = rowName.get(it.id);
            if (t != null) t.setTextColor(on ? p.accent : p.textPrimary);

            TextView sub = rowSub.get(it.id);
            if (sub != null) sub.setText(subtitle(it));

            TextView need = rowNeed.get(it.id);
            if (need != null) need.setVisibility(EngineStore.needsVars(it) ? View.VISIBLE : View.GONE);
        }

        if (engineNow != null) {
            EngineStore.Item it = EngineStore.get(act(), active);
            engineNow.setText((it == null ? "（没有）" : it.label)
                    + (it == null ? "" : "  ·  " + kindName(it.kind))
                    + "\n点「试听」验证它现在通不通");
            engineNow.setTextColor(p.textSecondary);
        }
    }
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
    private static String avatarChar(EngineStore.Item it) {
        if (EngineStore.KIND_SYSTEM.equals(it.kind)) return "本";
        if (EngineStore.KIND_JS.equals(it.kind)) {
            String s = it.label == null ? "" : it.label.trim();
            return s.length() == 0 ? "JS" : s.substring(0, 1);
        }
        return "H";
    }
    private int avatarColor(EngineStore.Item it) {
        int[] cs = {p.accent, p.ok, p.warn, 0xFF7E57C2, 0xFF26A69A, 0xFFEF6C00, 0xFF5C6BC0};
        int h = it.id == null ? 0 : it.id.hashCode();
        return cs[Math.abs(h) % cs.length];
    }
    private void exportEngineConfig(EngineStore.Item it) {
        try {
            java.util.List<EngineStore.Item> one = new java.util.ArrayList<EngineStore.Item>();
            one.add(it.copy());
            host.copy(EngineImport.exportOurs(one));
            host.toast("配置已复制到剪贴板");
        } catch (Throwable t) {
            host.toast("导出失败：" + t.getMessage());
        }
    }
    private void engineMenu(final EngineStore.Item it) {
        ChoiceSheet.show(act(), ui, p, host.sheetTheme(), it.label, null,
                new String[]{"设为当前引擎", "编辑配置", "设置变量", "插件界面", "导出配置",
                        "清除缓存", "看看音色列表", "测试这个引擎", "删除"},
                null, -1, new ChoiceSheet.OnPick() {
                    public void onPick(int which) {
                        if (which == 0) {
                            EngineStore.setActive(act(), it.id);
                            renderEngines();
                        } else if (which == 1) {
                            ed.editEngine(it);
                        } else if (which == 2) {
                            ed.openPluginEditor(it, true);
                        } else if (which == 3) {
                            voicePicker.openVoiceEditor(TtsEngine.shortVoice(it.s("voice", "")));
                        } else if (which == 4) {
                            exportEngineConfig(it);
                        } else if (which == 5) {
                            if (EngineStore.hasCache(act(), it)) {
                                EngineStore.clearCache(act(), it);
                                host.toast("缓存清了");
                            } else {
                                host.toast("这个插件没缓存");
                            }
                        } else if (which == 6) {
                            ed.showEngineVoices(it);
                        } else if (which == 7) {
                            EngineStore.setActive(act(), it.id);
                            renderEngines();
                            testEngine();
                        } else if (which == 8) {
                            EngineStore.remove(act(), it.id);
                            renderEngines();
                        }
                    }
                });
    }
}
