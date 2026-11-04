package com.mermergi.ttsrelay.ui;

import android.content.Context;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import com.mermergi.ttsrelay.Palette;
import com.mermergi.ttsrelay.Ui;

import java.util.Locale;

/** 「标题 + 当前值 + 滑杆」一排，语速/音调/音量共用。 */
public final class SliderRow {
    public SeekBar bar;
    public TextView val;

    private static final float TONE_LO = 0.5f, TONE_HI = 2.0f;

    public static LinearLayout build(Context ctx, Ui ui, Palette p, String title, int max,
                                     SliderRow out, SeekBar.OnSeekBarChangeListener l) {
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

        SeekBar bar = new SeekBar(ctx);
        bar.setMax(max);
        ui.tint(bar);
        bar.setOnSeekBarChangeListener(l);
        box.addView(bar, Widgets.topGap(ui, 2));

        out.bar = bar;
        out.val = val;
        return box;
    }

    public static int toneProg(String s) {
        float v;
        try { v = Float.parseFloat(s); } catch (Throwable t) { v = 1f; }
        if (v < TONE_LO) v = TONE_LO;
        if (v > TONE_HI) v = TONE_HI;
        return Math.round((v - TONE_LO) / (TONE_HI - TONE_LO) * 100f);
    }

    public static String toneVal(int prog) {
        float v = TONE_LO + (TONE_HI - TONE_LO) * (prog / 100f);
        return String.format(Locale.US, "%.2f", v);
    }
}
