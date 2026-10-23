package com.mermergi.ttsrelay;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.materialswitch.MaterialSwitch;

/**
 * 纯代码搭界面时反复要用的零件：圆角卡、按钮、输入框、片子、瓷砖。
 *
 * <p>形状、字号、按压态都按 Material 3 的规范来：形状走 M3 形状刻度（4/8/12/16/28），
 * 字号走 M3 的语义字号（TitleSmall / BodyMedium / LabelLarge…），按压态是
 * state layer（12% 内容色），颜色一律从 {@link Palette} 取 —— 所以换主题、换动态色
 * 都不用碰任何一个页面。</p>
 */
public final class Ui {

    /** M3 形状刻度：ExtraSmall / Small / Medium / Large / ExtraLarge。 */
    public static final float R_XS = 4f;
    public static final float R_S = 8f;
    public static final float R_M = 12f;
    public static final float R_L = 16f;
    public static final float R_XL = 28f;

    private static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    public final Context c;
    public final Palette p;

    public Ui(Context c, Palette p) { this.c = c; this.p = p; }

    public int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics());
    }

    // ───────────────────────── 底 ─────────────────────────

    public GradientDrawable round(int fill, float radius) {
        return round(fill, 0, radius);
    }

    public GradientDrawable round(int fill, int stroke, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(fill);
        g.setCornerRadius(dp(radius));
        if (stroke != 0) g.setStroke(Math.max(1, dp(1)), stroke);
        return g;
    }

    public GradientDrawable gradient(int from, int to, float radius) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{from, to});
        g.setCornerRadius(dp(radius));
        return g;
    }

    /** 按下有 state layer，松手是实心圆角 —— 按钮和可点卡片都用它。 */
    public RippleDrawable pressable(int fill, int rippleColor, float radius, int stroke) {
        GradientDrawable shape = round(fill, stroke, radius);
        GradientDrawable mask = round(0xFFFFFFFF, 0, radius);
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), shape, mask);
    }

    /** 底部抽屉那层的底：只有上面两个角是圆的（M3 XL 圆角 28dp）。 */
    public GradientDrawable sheetBg() {
        GradientDrawable g = new GradientDrawable();
        g.setColor(p.card);
        float r = dp(R_XL);
        g.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        return g;
    }

    /** M3 filled text field 的底：上面两个角 4dp，底部一条指示线（聚焦变主色）。 */
    public Drawable fieldBg(boolean focused) {
        GradientDrawable fill = new GradientDrawable();
        fill.setColor(p.cardHi);
        float r = dp(R_XS);
        fill.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});

        GradientDrawable line = new GradientDrawable();
        line.setColor(focused ? p.accent : p.outline);

        LayerDrawable ld = new LayerDrawable(new Drawable[]{fill, line});
        ld.setLayerHeight(1, Math.max(1, dp(focused ? 2 : 1)));
        ld.setLayerGravity(1, Gravity.BOTTOM | Gravity.FILL_HORIZONTAL);
        return ld;
    }

    // ───────────────────────── 布局 ─────────────────────────

    public LinearLayout col() {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public LinearLayout row() {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    public View space(int h) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(h)));
        return v;
    }

    public LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    /** 等分的一格（横向排布时用）。 */
    public LinearLayout.LayoutParams weight(float w) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w);
    }

    /** 满宽 + 上边距（弹窗里一坨一坨按钮之间用）。 */
    public LinearLayout.LayoutParams topGap(int topDp) {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        l.topMargin = dp(topDp);
        return l;
    }

    public LinearLayout.LayoutParams weightGap(float w, int gapDp) {
        LinearLayout.LayoutParams l = weight(w);
        l.rightMargin = dp(gapDp);
        return l;
    }

    /** M3 filled card：surface container 底、12dp 圆角、无描边（层级靠 tone，不靠阴影）。 */
    public LinearLayout card() {
        LinearLayout l = col();
        l.setBackground(round(p.card, R_M));
        l.setPadding(dp(16), dp(16), dp(16), dp(16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        l.setLayoutParams(lp);
        return l;
    }

    // ───────────────────────── 字 ─────────────────────────

    public TextView text(String s, int style, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextAppearance(c, style);
        t.setTextColor(color);
        return t;
    }

    /** 区块标题：M3 TitleSmall。 */
    public TextView sectionTitle(String s) {
        TextView t = text(s, R.style.TextAppearance_Material3_TitleSmall, p.textPrimary);
        t.setTypeface(MEDIUM);
        t.setPadding(0, 0, 0, dp(10));
        return t;
    }

    /** 字段小标签：M3 LabelMedium。 */
    public TextView label(String s) {
        TextView t = text(s, R.style.TextAppearance_Material3_LabelMedium, p.textSecondary);
        t.setPadding(0, dp(12), 0, dp(6));
        return t;
    }

    /** 说明文字：M3 BodySmall。 */
    public TextView hint(String s) {
        TextView t = text(s, R.style.TextAppearance_Material3_BodySmall, p.textMuted);
        t.setLineSpacing(dp(3), 1f);
        t.setPadding(0, dp(8), 0, 0);
        return t;
    }

    /** 一行值：M3 BodyMedium。 */
    public TextView value(String s) {
        TextView t = text(s, R.style.TextAppearance_Material3_BodyMedium, p.textPrimary);
        return t;
    }

    // ───────────────────────── 控件 ─────────────────────────

    public EditText field(String key, String def, int type) {
        EditText e = new EditText(c);
        e.setTag(key);
        e.setText(def);
        e.setInputType(type);
        e.setTextAppearance(c, R.style.TextAppearance_Material3_BodyLarge);
        e.setTextColor(p.textPrimary);
        e.setHintTextColor(p.textMuted);
        e.setSingleLine(true);
        e.setBackground(fieldBg(false));
        e.setPadding(dp(16), dp(13), dp(16), dp(13));
        e.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean has) {
                v.setBackground(fieldBg(has));
            }
        });
        return e;
    }

    /** 多行输入（断句预览用），不带 key —— 不参与设置的读写。 */
    public EditText area(String hint) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        e.setTextAppearance(c, R.style.TextAppearance_Material3_BodyMedium);
        e.setTextColor(p.textPrimary);
        e.setHintTextColor(p.textMuted);
        e.setSingleLine(false);
        e.setMinLines(3);
        e.setGravity(Gravity.TOP | Gravity.START);
        e.setBackground(fieldBg(false));
        e.setPadding(dp(16), dp(13), dp(16), dp(13));
        e.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean has) {
                v.setBackground(fieldBg(has));
            }
        });
        return e;
    }

    /**
     * 按钮：primary = M3 filled（主操作），否则 = M3 filled tonal（次操作）。
     * 高度 40dp、全圆角、LabelLarge。
     */
    public Button btn(String s, boolean primary, View.OnClickListener l) {
        Button b = baseBtn(s, l);
        if (primary) {
            b.setTextColor(p.onAccent);
            b.setBackground(pressable(p.accent, p.rippleOnAccent, 20, 0));
        } else {
            b.setTextColor(p.chipFg);
            b.setBackground(pressable(p.chipBg, p.ripple, 20, 0));
        }
        return b;
    }

    /** 放在 primaryContainer 大色块上的次按钮：描边 + onPrimaryContainer 字。 */
    public Button btnOnContainer(String s, View.OnClickListener l) {
        Button b = baseBtn(s, l);
        b.setTextColor(p.onAccentSoft);
        b.setBackground(pressable(0x00000000, Palette.alpha(p.onAccentSoft, 0.12f), 20,
                Palette.alpha(p.onAccentSoft, 0.38f)));
        return b;
    }

    /** M3 outlined 按钮（弹窗里的"取消"这种）。 */
    public Button btnOutlined(String s, View.OnClickListener l) {
        Button b = baseBtn(s, l);
        b.setTextColor(p.accent);
        b.setBackground(pressable(0x00000000, p.ripple, 20, p.outline));
        return b;
    }

    private Button baseBtn(String s, View.OnClickListener l) {
        Button b = new Button(c);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextAppearance(c, R.style.TextAppearance_Material3_LabelLarge);
        b.setTypeface(MEDIUM);
        b.setOnClickListener(l);
        b.setStateListAnimator(null);
        b.setGravity(Gravity.CENTER);
        b.setIncludeFontPadding(false);
        b.setMinHeight(dp(40));
        b.setMinimumHeight(dp(40));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(20), dp(10), dp(20), dp(10));
        return b;
    }

    /** 高 48dp 的主按钮（首页那个大开关）。 */
    public Button bigBtn(String s, boolean primary, View.OnClickListener l) {
        Button b = btn(s, primary, l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        b.setLayoutParams(lp);
        return b;
    }

    /**
     * M3 filter chip：未选 = 描边 + onSurfaceVariant；选中 = secondaryContainer + 勾。
     */
    public TextView chip(String s, boolean active, View.OnClickListener l) {
        TextView t = new TextView(c);
        t.setText(active ? "✓ " + s : s);
        t.setTextAppearance(c, R.style.TextAppearance_Material3_LabelLarge);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(14), dp(7), dp(14), dp(7));
        if (active) {
            t.setTextColor(p.chipFg);
            t.setTypeface(MEDIUM);
            t.setBackground(pressable(p.chipBg, p.ripple, R_S, 0));
        } else {
            t.setTextColor(p.textSecondary);
            t.setTypeface(Typeface.DEFAULT);
            t.setBackground(pressable(0x00000000, p.ripple, R_S, p.outline));
        }
        t.setOnClickListener(l);
        return t;
    }

    public TextView pill(String s, int color) {
        TextView t = text(s, R.style.TextAppearance_Material3_LabelSmall, color);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(9), dp(4), dp(9), dp(4));
        t.setBackground(round(p.cardHi, R_S));
        return t;
    }

    public TextView switchRow(String title, String sub) {
        TextView t = text(sub == null || sub.length() == 0 ? title : title + "\n" + sub,
                R.style.TextAppearance_Material3_BodyMedium, p.textPrimary);
        t.setLineSpacing(dp(3), 1f);
        t.setPadding(0, dp(12), 0, dp(2));
        return t;
    }

    /** 一格统计：大数字 + 小标题。 */
    public LinearLayout tile(String caption, int color, TextView[] valueOut) {
        LinearLayout l = col();
        l.setGravity(Gravity.CENTER);
        l.setBackground(round(p.cardHi, R_M));
        l.setPadding(dp(8), dp(14), dp(8), dp(14));

        TextView v = text("0", R.style.TextAppearance_Material3_TitleLarge, color);
        v.setTypeface(MEDIUM);
        v.setGravity(Gravity.CENTER);
        v.setSingleLine(true);

        TextView t = text(caption, R.style.TextAppearance_Material3_LabelMedium, p.textMuted);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(4), 0, 0);

        l.addView(v);
        l.addView(t);
        if (valueOut != null && valueOut.length > 0) valueOut[0] = v;
        return l;
    }

    public View divider() {
        View v = new View(c);
        v.setBackgroundColor(p.border);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        lp.topMargin = dp(14);
        lp.bottomMargin = dp(6);
        v.setLayoutParams(lp);
        return v;
    }

    /** M3 开关：直接用 MaterialSwitch，颜色由主题角色给，不用再手搓 tint。 */
    public MaterialSwitch switchBox(String text) {
        MaterialSwitch s = new MaterialSwitch(c);
        s.setText(text);
        s.setTextAppearance(c, R.style.TextAppearance_Material3_BodyMedium);
        s.setTextColor(p.textPrimary);
        s.setLineSpacing(dp(3), 1f);
        s.setPadding(0, dp(12), 0, dp(8));
        return s;
    }

    /** 给 SeekBar 上色，省得深浅两套主题下轨道看不见。 */
    public void tint(android.widget.SeekBar bar) {
        bar.setProgressTintList(ColorStateList.valueOf(p.accent));
        bar.setThumbTintList(ColorStateList.valueOf(p.accent));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(p.border));
    }
}
