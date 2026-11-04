package com.mermergi.ttsrelay.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.mermergi.ttsrelay.EngineStore;
import com.mermergi.ttsrelay.Palette;
import com.mermergi.ttsrelay.Ui;

import java.util.Locale;

/** 页面骨架与通用行控件。方法一律静态，依赖（Context/Ui/Palette）显式传参。 */
public final class Widgets {
    private Widgets() { }

    /** 把一列包进 ScrollView（M3 布局基准：16dp 页边距、区块之间 8dp 节奏）。 */
    public static View scroll(Context ctx, Ui ui, LinearLayout col) {
        col.setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(32));
        android.widget.ScrollView sv = new android.widget.ScrollView(ctx);
        sv.setClipToPadding(false);
        sv.addView(col, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return sv;
    }

    public static LinearLayout.LayoutParams topGap(Ui ui, int dp) {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        l.topMargin = ui.dp(dp);
        return l;
    }

    /** 可点的「标签 ▸ 值」行，点击行为由调用者给返回的行设置。 */
    public static LinearLayout kvRow(Context ctx, Ui ui, Palette p, String label, TextView val) {
        LinearLayout row = ui.row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(ui.dp(14), ui.dp(12), ui.dp(12), ui.dp(12));
        row.setBackground(ui.pressable(p.cardHi, p.ripple, 12, p.border));
        row.setClickable(true);
        TextView l = new TextView(ctx);
        l.setText(label);
        l.setTextColor(p.textMuted);
        l.setTextSize(12.5f);
        row.addView(l);
        val.setTextSize(14);
        val.setTextColor(p.textPrimary);
        val.setPadding(ui.dp(12), 0, 0, 0);
        val.setSingleLine(true);
        val.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(val, ui.weight(1f));
        TextView arrow = new TextView(ctx);
        arrow.setText("▾");
        arrow.setTextColor(p.textSecondary);
        arrow.setTextSize(14);
        row.addView(arrow);
        return row;
    }

    /** 这个引擎自己的语速/音量/音高（0 = 跟随全局，照 TTS Server 的 model）。 */
    public static LinearLayout paramSlider(final Context ctx, Ui ui, Palette p,
                                           final String label, final String key,
                                           final EngineStore.Item it) {
        LinearLayout box = ui.col();
        box.setPadding(0, ui.dp(8), 0, 0);
        LinearLayout head = ui.row();
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView l = new TextView(ctx);
        l.setText(label);
        l.setTextColor(p.textSecondary);
        l.setTextSize(12.5f);
        head.addView(l, ui.weight(1f));
        final TextView val = new TextView(ctx);
        val.setTextColor(p.accent);
        val.setTextSize(12.5f);
        head.addView(val);
        box.addView(head);

        final SeekBar bar = new SeekBar(ctx);
        bar.setMax(300);
        float cur = 0f;
        try { cur = Float.parseFloat(it.s(key, "")); } catch (Throwable ignored) { }
        final int init = Math.round(cur * 100f);
        bar.setProgress(init);
        val.setText(init == 0 ? "跟随全局" : String.format(Locale.US, "%.2f", init / 100f));
        ui.tint(bar);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar b, int prog, boolean fromUser) {
                if (!fromUser) return;
                val.setText(prog == 0 ? "跟随全局" : String.format(Locale.US, "%.2f", prog / 100f));
            }
            public void onStartTrackingTouch(SeekBar b) { }
            public void onStopTrackingTouch(SeekBar b) {
                int prog = b.getProgress();
                it.put(key, prog == 0 ? "" : String.format(Locale.US, "%.2f", prog / 100f));
            }
        });
        box.addView(bar);
        return box;
    }

    /** 「标题 + 当前值 + 滑杆」一排，实现在 {@link SliderRow}。 */
    public static LinearLayout sliderRow(Context ctx, Ui ui, Palette p, String title, int max,
                                         SliderRow out, SeekBar.OnSeekBarChangeListener l) {
        return SliderRow.build(ctx, ui, p, title, max, out, l);
    }

    public static void toast(Context ctx, String s) {
        Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show();
    }
}
