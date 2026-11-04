package com.mermergi.ttsrelay.tab;

import android.content.Context;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import com.mermergi.ttsrelay.ChoiceSheet;
import com.mermergi.ttsrelay.LogBus;
import com.mermergi.ttsrelay.MultiVoice;
import com.mermergi.ttsrelay.Palette;
import com.mermergi.ttsrelay.Prefs;
import com.mermergi.ttsrelay.RelayService;
import com.mermergi.ttsrelay.Speaker;
import com.mermergi.ttsrelay.Splitter;
import com.mermergi.ttsrelay.TtsEngine;
import com.mermergi.ttsrelay.Ui;
import com.mermergi.ttsrelay.ui.SliderRow;
import com.mermergi.ttsrelay.ui.Widgets;

import java.util.ArrayList;
import java.util.List;

/** 朗读页：音色/试听、语调滑杆、分角色（委托 VoicePicker）、断句与预览。 */
public final class SpeakTab implements Tab {
    private final Ui ui;
    private final Palette p;
    private final Tab.Host host;
    private final VoicePicker vp;

    private TextView voiceView, rateVal, pitchVal, volVal, previewOut;
    private SeekBar rateBar, pitchBar, volBar;
    private EditText tryField, previewIn;
    private Button splitBtn;
    private boolean syncing = false;

    private Context act() { return host.activity(); }

    public SpeakTab(Ui ui, Palette p, Tab.Host host, VoicePicker vp) {
        this.ui = ui;
        this.p = p;
        this.host = host;
        this.vp = vp;
    }

    private static int curPort(Context ctx) {
        com.mermergi.ttsrelay.HttpServer srv = RelayService.serverOrNull();
        return (srv != null && srv.isRunning()) ? srv.port() : Prefs.port(ctx);
    }

    @Override
    public View build(Context ctx) {
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
            public void onClick(View v) { vp.pickVoice(); }
        }), ui.weightGap(1f, 8));
        vb.addView(ui.btn("试听", false, new View.OnClickListener() {
            public void onClick(View v) { testSpeak(); }
        }), ui.weightGap(1f, 8));
        vb.addView(ui.btn("自检", false, new View.OnClickListener() {
            public void onClick(View v) { Widgets.selfTest(act(), curPort(act())); }
        }), ui.weight(1f));
        vc.addView(vb);

        vc.addView(ui.label("试听文本（试听和换音色都念这句）"));
        tryField = ui.field("trytext", Prefs.TRY_DEF, InputType.TYPE_CLASS_TEXT);
        vc.addView(tryField);
        col.addView(vc);

        // ── 语调 ──
        LinearLayout tc = ui.card();
        tc.addView(ui.sectionTitle("语速 · 音调 · 音量"));
        final SliderRow s1 = new SliderRow(), s2 = new SliderRow(), s3 = new SliderRow();

        tc.addView(Widgets.sliderRow(act(), ui, p, "语速", 100, s1, new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar b, int prog, boolean fromUser) {
                if (!fromUser) return;
                String v = SliderRow.toneVal(prog);
                s1.val.setText(v);
                Prefs.setRate(act(), v);
            }
            public void onStartTrackingTouch(SeekBar b) { }
            public void onStopTrackingTouch(SeekBar b) {
                LogBus.add("语速 → " + Prefs.rate(act()) + "（下一句生效）");
            }
        }));
        tc.addView(Widgets.sliderRow(act(), ui, p, "音调", 100, s2, new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar b, int prog, boolean fromUser) {
                if (!fromUser) return;
                String v = SliderRow.toneVal(prog);
                s2.val.setText(v);
                Prefs.setPitch(act(), v);
            }
            public void onStartTrackingTouch(SeekBar b) { }
            public void onStopTrackingTouch(SeekBar b) {
                LogBus.add("音调 → " + Prefs.pitch(act()) + "（下一句生效）");
            }
        }));
        tc.addView(Widgets.sliderRow(act(), ui, p, "音量", 100, s3, new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar b, int prog, boolean fromUser) {
                if (!fromUser) return;
                float v = prog / 100f;
                s3.val.setText(prog + "%");
                Prefs.setVolume(act(), v);
                Speaker spk = RelayService.speakerOrNull();
                if (spk != null) spk.setVolume(v);
            }
            public void onStartTrackingTouch(SeekBar b) { }
            public void onStopTrackingTouch(SeekBar b) { }
        }));
        rateBar = s1.bar; rateVal = s1.val;
        pitchBar = s2.bar; pitchVal = s2.val;
        volBar = s3.bar; volVal = s3.val;
        col.addView(tc);

        col.addView(vp.buildMultiVoiceCard(ctx));
        col.addView(vp.buildUserMsgCard(ctx));

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
        previewOut = new TextView(act());
        previewOut.setTextSize(12);
        previewOut.setTextColor(p.textSecondary);
        previewOut.setLineSpacing(ui.dp(3), 1f);
        previewOut.setPadding(0, ui.dp(12), 0, 0);
        pc.addView(previewOut);
        col.addView(pc);

        return Widgets.scroll(act(), ui, col);
    }
    private void renderPreview() {
        if (previewIn == null || previewOut == null) return;
        String t = previewIn.getText().toString();
        if (t.trim().length() == 0) {
            previewOut.setText("上面粘一段带标点的文字，这里会实时显示会被切成几句。");
            return;
        }
        Splitter sp = new Splitter(Prefs.maxChunk(act()), 4, Prefs.splitMode(act()));
        List<String> parts = new ArrayList<String>();
        parts.addAll(sp.feed(t));
        parts.addAll(sp.flush());
        StringBuilder sb = new StringBuilder();
        sb.append("共 ").append(parts.size()).append(" 句 · 方案「")
                .append(Splitter.modeName(Prefs.splitMode(act())))
                .append("」· 单句上限 ").append(Prefs.maxChunk(act())).append(" 字\n");
        for (int i = 0; i < parts.size(); i++) {
            sb.append(i + 1).append(". ").append(parts.get(i)).append('\n');
        }
        previewOut.setText(sb.toString());
    }
    private void testSpeak() {
        Speaker spk = RelayService.speakerOrNull();
        if (spk == null) { host.toast("先启动中转"); return; }
        saveInputs();
        String text = Prefs.tryText(act());
        Splitter sp = new Splitter(Prefs.maxChunk(act()), 4, Prefs.splitMode(act()));
        List<String> parts = new ArrayList<String>();
        for (int i = 0; i < text.length(); i++) {
            parts.addAll(sp.feed(String.valueOf(text.charAt(i))));
        }
        parts.addAll(sp.flush());
        for (String s : parts) spk.feed(s);
        LogBus.add("试听 · 断句方案「" + Splitter.modeName(Prefs.splitMode(act()))
                + "」切成 " + parts.size() + " 块：");
        for (String x : parts) LogBus.add("   ▸ " + x);
        host.toast("已排队（" + parts.size() + " 块，方案：" + Splitter.modeName(Prefs.splitMode(act())) + "）");
    }
    private void pickSplitMode() {
        String[] names = new String[Splitter.MODE_COUNT];
        String[] descs = new String[Splitter.MODE_COUNT];
        for (int i = 0; i < Splitter.MODE_COUNT; i++) {
            names[i] = Splitter.modeName(i);
            descs[i] = Splitter.modeDesc(i);
        }
        ChoiceSheet.show(act(), ui, p, host.sheetTheme(), "断句方案", null,
                names, descs, Prefs.splitMode(act()), new ChoiceSheet.OnPick() {
                    public void onPick(int which) {
                        Prefs.setSplitMode(act(), which);
                        syncInputs();
                        host.requestRefresh();
                    }
                });
    }
    // ══════════════════════════ 壳契约 ══════════════════════════

    @Override
    public void refresh() {
        if (voiceView != null) {
            String v = Prefs.voice(act());
            voiceView.setText(v.length() == 0
                    ? "当前：（未选）用 TTS Server 自己的默认音色"
                    : "当前：" + v.replaceAll("_\\d+$", ""));
        }
        if (splitBtn != null) {
            splitBtn.setText("断句方案：" + Splitter.modeName(Prefs.splitMode(act())) + "  ▸");
        }
        vp.refresh();
    }

    @Override
    public void saveInputs() {
        if (tryField != null) {
            String v = tryField.getText().toString().trim();
            if (v.length() > 0) Prefs.setTryText(act(), v);
        }
    }

    @Override
    public void syncInputs() {
        syncing = true;
        if (tryField != null) tryField.setText(Prefs.tryText(act()));
        if (rateBar != null) {
            rateBar.setProgress(SliderRow.toneProg(Prefs.rate(act())));
            rateVal.setText(Prefs.rate(act()));
        }
        if (pitchBar != null) {
            pitchBar.setProgress(SliderRow.toneProg(Prefs.pitch(act())));
            pitchVal.setText(Prefs.pitch(act()));
        }
        if (volBar != null) {
            int v = Math.round(Prefs.volume(act()) * 100f);
            volBar.setProgress(v);
            volVal.setText(v + "%");
        }
        renderPreview();
        syncing = false;
        vp.syncInputs();
    }

    @Override
    public void clearRefs() {
        voiceView = null; tryField = null;
        rateVal = null; pitchVal = null; volVal = null;
        rateBar = null; pitchBar = null; volBar = null;
        splitBtn = null; previewIn = null; previewOut = null;
    }
}
