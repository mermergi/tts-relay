package com.mermergi.ttsrelay;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.radiobutton.MaterialRadioButton;

/**
 * 「选一项」/「选一个操作」的面板 —— 底部抽屉，一行一张卡。
 *
 * 以前这类全用系统 AlertDialog + setSingleChoiceItems：名字和说明挤在同一个
 * TextView 里、字号一样、靠换行硬分，看着就是一坨。现在一行 = 名字（15sp 粗）
 * + 说明（12sp 灰）+ 右边单选点，选中的那张卡描边和底色换成主题色。
 */
final class ChoiceSheet {

    interface OnPick {
        void onPick(int index);
    }

    /**
     * @param checked 当前选中的下标；<b>-1 = 这是一张操作清单</b>，不画单选点
     */
    static void show(Context ctx, Ui ui, Palette p, int themeRes,
                     String title, String subtitle,
                     String[] names, String[] descs, int checked, final OnPick onPick) {
        final BottomSheetDialog dlg = new BottomSheetDialog(ctx, themeRes);

        LinearLayout box = ui.col();
        box.setPadding(ui.dp(18), ui.dp(2), ui.dp(18), ui.dp(16));

        TextView t = new TextView(ctx);
        t.setText(title);
        t.setTextColor(p.textPrimary);
        t.setTextSize(17);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(t);

        if (subtitle != null && subtitle.length() > 0) {
            TextView s = new TextView(ctx);
            s.setText(subtitle);
            s.setTextColor(p.textMuted);
            s.setTextSize(12);
            s.setPadding(0, ui.dp(3), 0, 0);
            box.addView(s);
        }

        LinearLayout list = ui.col();
        list.setPadding(0, ui.dp(12), 0, 0);
        boolean radio = checked >= 0;

        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            boolean on = radio && i == checked;

            MaterialCardView card = new MaterialCardView(ctx);
            card.setCardBackgroundColor(on ? p.accentSoft : p.cardHi);
            card.setStrokeColor(on ? p.accent : p.border);
            card.setStrokeWidth(Math.max(1, ui.dp(on ? 2 : 1)));
            card.setRadius(ui.dp(14));
            card.setCardElevation(0f);
            card.setClickable(true);
            card.setFocusable(true);
            card.setRippleColor(ColorStateList.valueOf(p.ripple));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.bottomMargin = ui.dp(8);
            card.setLayoutParams(clp);

            LinearLayout row = ui.row();
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(ui.dp(14), ui.dp(12), radio ? ui.dp(8) : ui.dp(14), ui.dp(12));
            card.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            LinearLayout col = ui.col();
            TextView name = new TextView(ctx);
            name.setText(names[i]);
            name.setTextSize(15);
            name.setTextColor(on ? p.accent : p.textPrimary);
            if (on) name.setTypeface(Typeface.DEFAULT_BOLD);
            col.addView(name);

            String d = (descs != null && i < descs.length) ? descs[i] : null;
            if (d != null && d.length() > 0) {
                TextView dd = new TextView(ctx);
                dd.setText(d);
                dd.setTextSize(12.5f);
                dd.setTextColor(p.textMuted);
                dd.setLineSpacing(ui.dp(2), 1f);
                dd.setPadding(0, ui.dp(4), 0, 0);
                col.addView(dd);
            }
            row.addView(col, ui.weightGap(1f, 8));

            if (radio) {
                MaterialRadioButton rb = new MaterialRadioButton(ctx);
                rb.setChecked(on);
                rb.setClickable(false);
                rb.setFocusable(false);
                rb.setButtonTintList(ColorStateList.valueOf(on ? p.accent : p.textMuted));
                row.addView(rb);
            }

            card.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    dlg.dismiss();
                    if (onPick != null) onPick.onPick(idx);
                }
            });
            list.addView(card);
        }

        ScrollView sc = new ScrollView(ctx);
        sc.setClipToPadding(false);
        sc.addView(list);
        box.addView(sc);

        dlg.setContentView(box);
        dlg.show();
        try { dlg.getBehavior().setSkipCollapsed(true); } catch (Throwable ignored) { }
    }
}
