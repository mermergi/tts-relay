package com.mermergi.ttsrelay;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;

/**
 * 「编辑插件 TTS」整页 —— 内容和 TTS Server 那个页面同一套加载逻辑，但<b>一个字都不写死</b>：
 *
 * <ul>
 *   <li>语言：插件声明了 {@code getLocales()} 才有这一行，没有就不显示</li>
 *   <li>声音：插件声明了 {@code getVoices(locale)} 才有这一行，没有就不显示</li>
 *   <li>下面那块：插件在 {@code onLoadUI(context, container)} 里画什么就显示什么
 *       （碰巧画了滑杆/开关/输入框，就都在这儿）</li>
 * </ul>
 *
 * 换语言会重新问一次声音；换声音会调 {@code onVoiceChanged(locale, voice)}，
 * 插件自己那块界面跟着更新。这就是 TTS Server 的加载顺序：
 * {@code onLoadData() → onLoadUI() → getLocales() → getVoices(locale)}。
 *
 * <p>页面上只有「名称」是<b>我们的</b>—— 那是「音色 → 名称」这个功能自己的数据，
 * 不是给插件补的字段。
 */
public final class PluginTtsActivity extends Activity {

    public static final String EXTRA_ID = "engine_id";
    public static final String EXTRA_VOICE = "voice";
    public static final String EXTRA_NAMES = "names";

    private Palette p;
    private Ui ui;
    private EngineStore.Item it;

    private EditText nameBox;
    private TextView langVal;
    private TextView voiceVal;
    private LinearLayout langRow;
    private LinearLayout voiceRow;
    private LinearLayout pluginHost;

    private String origVoice = "";
    private String curVoice = "";
    private String curLocale = "";
    private com.mermergi.ttsrelay.js.PluginBridge bridge;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        App.install(this);
        p = Palette.of(this);
        setTheme(p.light ? R.style.AppTheme_Light : R.style.AppTheme);
        ui = new Ui(this, p);

        Intent in = getIntent();
        String id = in == null ? "" : in.getStringExtra(EXTRA_ID);
        it = EngineStore.get(this, id);
        if (it == null) { toast("引擎没找到"); finish(); return; }

        origVoice = in.getStringExtra(EXTRA_VOICE);
        if (origVoice == null) origVoice = "";
        curVoice = origVoice;
        curLocale = it.s("lang", "zh-CN");
        if (curLocale.length() == 0) curLocale = "zh-CN";

        buildUi(in == null ? "" : in.getStringExtra(EXTRA_NAMES));
        loadPluginAsync();
    }

    private void buildUi(String names) {
        LinearLayout root = ui.col();
        root.setBackgroundColor(p.bg);

        // ── 顶栏 ──
        LinearLayout top = ui.row();
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(6));
        top.addView(tiny("←", new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        }));
        TextView title = new TextView(this);
        title.setText("编辑插件 TTS");
        title.setTextColor(p.textPrimary);
        title.setTextSize(17);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams tp = ui.weight(1f);
        tp.leftMargin = ui.dp(10);
        top.addView(title, tp);
        top.addView(tiny("💾", new View.OnClickListener() {
            public void onClick(View v) { save(); }
        }));
        root.addView(top);

        ScrollView sc = new ScrollView(this);
        sc.setClipToPadding(false);
        LinearLayout col = ui.col();
        col.setPadding(ui.dp(14), 0, ui.dp(14), ui.dp(16));

        // 名称（我们的：音色 → 名称）
        col.addView(ui.label("名称"));
        nameBox = ui.field(null, names == null ? "" : names.replace("|", " | "),
                InputType.TYPE_CLASS_TEXT);
        nameBox.setHint("杨幂|刘亦菲");
        col.addView(nameBox);

        // 语言 / 声音：插件声明了才出现
        langVal = new TextView(this);
        langVal.setText(curLocale);
        langVal.setTextColor(p.textPrimary);
        langVal.setTextSize(14);
        langRow = kvRow("语言", langVal);
        langRow.setVisibility(View.GONE);
        col.addView(langRow, ui.topGap(12));

        voiceVal = new TextView(this);
        voiceVal.setText(TtsEngine.shortVoice(curVoice));
        voiceVal.setTextColor(p.textPrimary);
        voiceVal.setTextSize(14);
        voiceVal.setSingleLine(true);
        voiceVal.setEllipsize(android.text.TextUtils.TruncateAt.END);
        voiceRow = kvRow("声音", voiceVal);
        voiceRow.setVisibility(View.GONE);
        col.addView(voiceRow, ui.topGap(8));

        // 插件自己画的界面
        pluginHost = ui.col();
        col.addView(pluginHost, ui.topGap(8));

        sc.addView(col);
        root.addView(sc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        Window w = getWindow();
        if (w != null) {
            w.setStatusBarColor(p.bg);
            w.setNavigationBarColor(p.bg);
        }
    }

    /** onLoadData() → onLoadUI() → getLocales() → getVoices(locale)：照它的顺序。 */
    private void loadPluginAsync() {
        new Thread(new Runnable() {
            public void run() {
                bridge = JsTtsEngine.sharedBridge(PluginTtsActivity.this, it);
                if (bridge == null) return;
                LinkedHashMap<String, String> locs = null;
                List<String[]> vs = null;
                try {
                    locs = new LinkedHashMap<String, String>(bridge.locales());
                } catch (Throwable ignored) { }
                try {
                    vs = bridge.voices(curLocale);
                } catch (Throwable ignored) { }
                final LinkedHashMap<String, String> fLocs = locs;
                final List<String[]> fVs = vs;
                runOnUiThread(new Runnable() {
                    public void run() {
                        bindLocales(fLocs);
                        bindVoices(fVs);
                        attachPluginUi();
                    }
                });
            }
        }).start();
    }

    /** 插件声明了语言才显示这一行；点了换语言，声音列表要重新问。 */
    private void bindLocales(final LinkedHashMap<String, String> locs) {
        if (locs == null || locs.isEmpty()) { langRow.setVisibility(View.GONE); return; }
        langRow.setVisibility(View.VISIBLE);
        String now = locs.get(curLocale);
        langVal.setText(now == null ? curLocale : now);
        langRow.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                final String[] codes = locs.keySet().toArray(new String[0]);
                String[] names = locs.values().toArray(new String[0]);
                int cur = 0;
                for (int i = 0; i < codes.length; i++) if (codes[i].equals(curLocale)) cur = i;
                ChoiceSheet.show(PluginTtsActivity.this, ui, p,
                        p.light ? R.style.AppBottomSheet_Light : R.style.AppBottomSheet,
                        "语言", null, names, null, cur, new ChoiceSheet.OnPick() {
                            public void onPick(int i) {
                                curLocale = codes[i];
                                langVal.setText(locs.get(codes[i]));
                                reloadVoices();
                            }
                        });
            }
        });
    }

    /** 插件声明了声音才显示这一行。 */
    private void bindVoices(final List<String[]> vs) {
        if (vs == null || vs.isEmpty()) { voiceRow.setVisibility(View.GONE); return; }
        voiceRow.setVisibility(View.VISIBLE);
        if (curVoice == null || curVoice.length() == 0) {
            curVoice = vs.get(0)[1];
            voiceVal.setText(vs.get(0)[0]);
        }
        voiceRow.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String[] names = new String[vs.size()];
                int cur = 0;
                for (int i = 0; i < vs.size(); i++) {
                    names[i] = vs.get(i)[0];
                    if (vs.get(i)[1].equals(curVoice)) cur = i;
                }
                ChoiceSheet.show(PluginTtsActivity.this, ui, p,
                        p.light ? R.style.AppBottomSheet_Light : R.style.AppBottomSheet,
                        "声音", null, names, null, cur, new ChoiceSheet.OnPick() {
                            public void onPick(int i) {
                                curVoice = vs.get(i)[1];
                                voiceVal.setText(vs.get(i)[0]);
                                // 插件自己那块界面跟着换
                                if (bridge != null) {
                                    try { bridge.onVoiceChanged(curLocale, curVoice); } catch (Throwable ignored) { }
                                }
                            }
                        });
            }
        });
    }

    private void reloadVoices() {
        new Thread(new Runnable() {
            public void run() {
                if (bridge == null) return;
                final List<String[]> vs;
                try { vs = bridge.voices(curLocale); } catch (Throwable t) { return; }
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (vs != null && !vs.isEmpty()) {
                            curVoice = vs.get(0)[1];
                            voiceVal.setText(vs.get(0)[0]);
                            if (bridge != null) {
                                try { bridge.onVoiceChanged(curLocale, curVoice); } catch (Throwable ignored) { }
                            }
                        }
                        bindVoices(vs);
                    }
                });
            }
        }).start();
    }

    /** 插件在 onLoadUI 里画的容器，原样挂上来；没画就什么都不显示。 */
    private void attachPluginUi() {
        if (bridge == null || pluginHost == null) return;
        try {
            View v = bridge.getUiContainer();
            if (v == null) return;
            ViewGroup par = (ViewGroup) v.getParent();
            if (par != null) par.removeView(v);
            pluginHost.removeAllViews();
            pluginHost.addView(v, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } catch (Throwable ignored) { }
    }

    private void save() {
        String nm = nameBox.getText().toString().trim();
        java.util.List<String> list = MultiVoice.splitNames(nm);
        if (!list.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (String n : list) { if (sb.length() > 0) sb.append('|'); sb.append(n); }
            if (origVoice != null && origVoice.length() > 0 && !origVoice.equals(curVoice)) {
                Prefs.moveVoiceNames(this, origVoice, curVoice, sb.toString());
            } else {
                Prefs.putVoiceNames(this, curVoice, sb.toString());
            }
        }
        it.put("lang", curLocale);
        it.put("voice", curVoice);
        EngineStore.put(this, it);
        JsTtsEngine.clearVoices();
        toast("已保存");
        finish();
    }

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
        LinearLayout.LayoutParams lp = ui.weight(1f);
        lp.leftMargin = ui.dp(12);
        row.addView(val, lp);
        TextView arrow = new TextView(this);
        arrow.setText("▾");
        arrow.setTextColor(p.textSecondary);
        arrow.setTextSize(14);
        row.addView(arrow);
        return row;
    }

    private TextView tiny(String s, View.OnClickListener l) {
        TextView t = ui.chip(s, false, l);
        t.setSingleLine(true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8));
        return t;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
