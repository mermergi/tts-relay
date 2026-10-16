package com.mermergi.ttsrelay;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 纯代码搭界面时反复要用的零件：圆角卡、按钮、输入框、片子、瓷砖。
 * 全部按 Palette 上色 —— 这样"换主题"不用碰任何一个页面。
 */
public final class Ui {

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

    /** 按下有水波，松手是实心圆角 —— 按钮和可点卡片都用它。 */
    public RippleDrawable pressable(int fill, int rippleColor, float radius, int stroke) {
        GradientDrawable shape = round(fill, stroke, radius);
        GradientDrawable mask = round(0xFFFFFFFF, 0, radius);
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), shape, mask);
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

    /** 带右边距的等分格。 */
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

    /** 圆角卡片，底部留 12dp 间距。 */
    public LinearLayout card() {
        LinearLayout l = col();
        l.setBackground(round(p.card, p.border, 18));
        l.setPadding(dp(16), dp(15), dp(16), dp(16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        l.setLayoutParams(lp);
        return l;
    }

    // ───────────────────────── 字 ─────────────────────────

    public TextView sectionTitle(String s) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(p.textSecondary);
        t.setTextSize(12);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLetterSpacing(0.06f);
        t.setPadding(0, 0, 0, dp(9));
        return t;
    }

    public TextView label(String s) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(p.textMuted);
        t.setTextSize(11.5f);
        t.setPadding(0, dp(10), 0, dp(5));
        return t;
    }

    public TextView hint(String s) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(p.textMuted);
        t.setTextSize(11);
        t.setLineSpacing(dp(2), 1f);
        t.setPadding(0, dp(8), 0, 0);
        return t;
    }

    public TextView value(String s) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(p.textPrimary);
        t.setTextSize(13.5f);
        return t;
    }

    // ───────────────────────── 控件 ─────────────────────────

    public EditText field(String key, String def, int type) {
        EditText e = new EditText(c);
        e.setTag(key);
        e.setText(def);
        e.setInputType(type);
        e.setTextSize(14);
        e.setTextColor(p.textPrimary);
        e.setHintTextColor(p.textMuted);
        e.setSingleLine(true);
        e.setBackground(round(p.cardHi, p.border, 12));
        e.setPadding(dp(13), dp(11), dp(13), dp(11));
        return e;
    }

    /** 多行输入（断句预览用），不带 key —— 不参与设置的读写。 */
    public EditText area(String hint) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        e.setTextSize(14);
        e.setTextColor(p.textPrimary);
        e.setHintTextColor(p.textMuted);
        e.setSingleLine(false);
        e.setMinLines(3);
        e.setGravity(Gravity.TOP | Gravity.START);
        e.setBackground(round(p.cardHi, p.border, 12));
        e.setPadding(dp(13), dp(11), dp(13), dp(11));
        return e;
    }

    public Button btn(String s, boolean primary, View.OnClickListener l) {
        Button b = new Button(c);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setOnClickListener(l);
        b.setStateListAnimator(null);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(14), dp(11), dp(14), dp(11));
        if (primary) {
            b.setTextColor(p.onAccent);
            b.setTypeface(Typeface.DEFAULT_BOLD);
            b.setBackground(pressable(p.accent, p.rippleOnAccent, 13, 0));
        } else {
            b.setTextColor(p.accent);
            b.setBackground(pressable(p.cardHi, p.ripple, 13, p.border));
        }
        return b;
    }

    /** 高 48dp 的主按钮（首页那个大开关）。 */
    public Button bigBtn(String s, boolean primary, View.OnClickListener l) {
        Button b = btn(s, primary, l);
        b.setTextSize(16);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        b.setLayoutParams(lp);
        return b;
    }

    public TextView chip(String s, boolean active, View.OnClickListener l) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(12.5f);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(13), dp(7), dp(13), dp(7));
        if (active) {
            t.setTextColor(p.onAccent);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setBackground(pressable(p.accent, p.rippleOnAccent, 20, 0));
        } else {
            t.setTextColor(p.textSecondary);
            t.setBackground(pressable(p.cardHi, p.ripple, 20, p.border));
        }
        t.setOnClickListener(l);
        return t;
    }

    public TextView pill(String s, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(10.5f);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(9), dp(4), dp(9), dp(4));
        t.setBackground(round(p.cardHi, p.border, 20));
        return t;
    }

    public TextView switchRow(String title, String sub) {
        TextView t = new TextView(c);
        t.setText(sub == null || sub.length() == 0 ? title : title + "\n" + sub);
        t.setTextColor(p.textPrimary);
        t.setTextSize(13.5f);
        t.setLineSpacing(dp(3), 1f);
        t.setPadding(0, dp(10), 0, dp(2));
        return t;
    }

    /** 一格统计：大数字 + 小标题。valueOut[0] 拿回数字控件。 */
    public LinearLayout tile(String caption, int color, TextView[] valueOut) {
        LinearLayout l = col();
        l.setGravity(Gravity.CENTER);
        l.setBackground(round(p.cardHi, p.border, 14));
        l.setPadding(dp(8), dp(13), dp(8), dp(13));

        TextView v = new TextView(c);
        v.setText("0");
        v.setTextSize(19);
        v.setTextColor(color);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setGravity(Gravity.CENTER);
        v.setSingleLine(true);

        TextView t = new TextView(c);
        t.setText(caption);
        t.setTextSize(10.5f);
        t.setTextColor(p.textMuted);
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
        lp.bottomMargin = dp(4);
        v.setLayoutParams(lp);
        return v;
    }

    /** 上了主题色的开关 —— 系统默认那个绿的和这套配色打架。 */
    public android.widget.Switch switchBox(String text) {
        android.widget.Switch s = new android.widget.Switch(c);
        s.setText(text);
        s.setTextColor(p.textPrimary);
        s.setTextSize(13.5f);
        s.setLineSpacing(dp(3), 1f);
        s.setPadding(0, dp(12), 0, dp(4));
        int[][] st = {{android.R.attr.state_checked}, {}};
        s.setThumbTintList(new ColorStateList(st, new int[]{p.accent, p.textMuted}));
        s.setTrackTintList(new ColorStateList(st, new int[]{p.accentSoft, p.cardHi}));
        return s;
    }

    /** 给 SeekBar 上色，省得深浅两套主题下轨道看不见。 */
    public void tint(android.widget.SeekBar bar) {
        bar.setProgressTintList(ColorStateList.valueOf(p.accent));
        bar.setThumbTintList(ColorStateList.valueOf(p.accent));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(p.border));
    }
}
