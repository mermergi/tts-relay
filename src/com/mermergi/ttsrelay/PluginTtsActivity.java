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
    /** 这个音色自己的语速 / 音量 / 音高（0 = 跟随全局）。 */
    private android.widget.SeekBar spdBar, volBar, pitBar;
    private TextView spdVal, volVal, pitVal;

    private String origVoice = "";
    private String curVoice = "";
    private String curLocale = "";
    private com.mermergi.ttsrelay.js.PluginBridge bridge;

    @Override protected void onCreate(Bundle b) {
        App.install(this);
        // 先定主题再 super.onCreate（主题必须在 inflate 之前生效）
        p = Palette.apply(this);
        super.onCreate(b);
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

        col.addView(ui.label("语速 / 音量 / 音高"), ui.topGap(14));
        float[] pv = EngineStore.paramsForVoice(it, curVoice);
        spdBar = paramBar(0.5f, 2.0f, pv[0], 1.0f);
        volBar = paramBar(0f, 2.0f, pv[1], 1.0f);
        pitBar = paramBar(0.5f, 2.0f, pv[2], 1.0f);
        spdVal = ui.hint("");
        volVal = ui.hint("");
        pitVal = ui.hint("");
        col.addView(sliderRow("语速", spdBar, spdVal, 0.5f, 2.0f), ui.topGap(8));
        col.addView(sliderRow("音量", volBar, volVal, 0f, 2.0f), ui.topGap(6));
        col.addView(sliderRow("音高", pitBar, pitVal, 0.5f, 2.0f), ui.topGap(6));

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

    /**
     * onLoadData() → onLoadUI() → getLocales() → getVoices(locale)：照它的顺序。
     *
     * <p>这里<b>不复用</b>合成用的那个宿主，而是按「这个音色」单开一个：插件的
     * {@code onLoadUI} 一建出来读的就是这个音色的 {@code ttsrv.tts.data}
     * （插件的「语气」下拉就靠这个）。页面关掉时把它销毁，改过的数据已经存进配置。
     */
    private void loadPluginAsync() {
        new Thread(new Runnable() {
            public void run() {
                final com.mermergi.ttsrelay.js.PluginBridge h;
                try {
                    h = JsTtsEngine.openFor(PluginTtsActivity.this, it, curLocale, curVoice);
                } catch (Throwable t) {
                    LogBus.error("打开插件失败：" + t.getMessage());
                    return;
                }
                LinkedHashMap<String, String> locs = null;
                List<String[]> vs = null;
                try {
                    locs = new LinkedHashMap<String, String>(h.locales());
                } catch (Throwable ignored) { }
                try {
                    vs = h.voices(curLocale);
                } catch (Throwable ignored) { }
                final LinkedHashMap<String, String> fLocs = locs;
                final List<String[]> fVs = vs;
                runOnUiThread(new Runnable() {
                    public void run() {
                        com.mermergi.ttsrelay.js.PluginBridge old = bridge;
                        bridge = h;
                        if (old != null && old != h) {
                            try { old.destroy(); } catch (Throwable ignored) { }
                        }
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
                                loadPluginAsync();
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
                                // 换音色 = 换一套"每音色数据"，重开宿主让插件界面显示这个音色的语气
                                loadPluginAsync();
                            }
                        });
            }
        });
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
        EngineStore.setParamsForVoice(it, curVoice,
                barVal(spdBar, 0.5f, 2.0f), barVal(volBar, 0f, 2.0f), barVal(pitBar, 0.5f, 2.0f));
        it.put("lang", curLocale);
        it.put("voice", curVoice);
        EngineStore.put(this, it);
        // 插件界面上改的东西（插件那个「语气」下拉存在 ttsrv.tts.data 里）也要存下来 ——
        // 而且是按音色存的：这个音色选了什么语气，下次还是它。
        try {
            LogBus.add("页面保存：voice=[" + curVoice + "] bridge=" + (bridge != null)
                    + " data=" + (bridge == null ? "-" : bridge.ttsData()));
        } catch (Throwable ignored) { }
        JsTtsEngine.persistData(this, it, curVoice, bridge);
        JsTtsEngine.clearVoices();
        toast("已保存");
        finish();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 直接按返回键离开也要把插件界面上的选择留下（保存按钮只是"我确信"一下）
        try { JsTtsEngine.persistData(this, it, curVoice, bridge); } catch (Throwable ignored) { }
    }

    /** 一条滑杆：progress 0..100，0 = 跟随；其余按 [lo, hi] 线性映射。 */
    private android.widget.SeekBar paramBar(float lo, float hi, float cur, float def) {
        android.widget.SeekBar b = new android.widget.SeekBar(this);
        int prog = 0;
        if (cur > 0f) prog = Math.round((cur - lo) / (hi - lo) * 100f);
        b.setMax(100);
        b.setProgress(Math.max(0, Math.min(100, prog)));
        return b;
    }

    private float barVal(android.widget.SeekBar b, float lo, float hi) {
        if (b == null) return 0f;
        int p = b.getProgress();
        if (p <= 0) return 0f;                       // 0 = 跟随全局
        return lo + (hi - lo) * (p / 100f);
    }

    private LinearLayout sliderRow(String name, final android.widget.SeekBar bar,
                                   final TextView out, final float lo, final float hi) {
        LinearLayout row = ui.row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView l = new TextView(this);
        l.setText(name);
        l.setTextColor(p.textSecondary);
        l.setTextSize(12.5f);
        row.addView(l, ui.weightGap(0.18f, 8));
        bar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(android.widget.SeekBar b, int prog, boolean fromUser) { show(); }
            public void onStartTrackingTouch(android.widget.SeekBar b) { }
            public void onStopTrackingTouch(android.widget.SeekBar b) { show(); }
            private void show() {
                int prog = bar.getProgress();
                if (prog <= 0) out.setText("跟随全局");
                else out.setText(String.format(Locale.US, "%.2f", lo + (hi - lo) * (prog / 100f)));
            }
        });
        row.addView(bar, ui.weightGap(1f, 8));
        row.addView(out, ui.weight(0.22f));
        int p0 = bar.getProgress();
        out.setText(p0 <= 0 ? "跟随全局" : String.format(Locale.US, "%.2f", lo + (hi - lo) * (p0 / 100f)));
        return row;
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
