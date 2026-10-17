package com.mermergi.ttsrelay;

import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;

/**
 * 音色库列表 —— 换成 RecyclerView 之后，复用、动画、平滑滚动都是现成的，
 * 不用再拿 ListView 塞假 item 当分组标题。
 *
 * 两种视图类型：分组标题（女声 / 男声 / 未标注）、音色行（名字 + 标签 + 🎧）。
 * 行用 MaterialCardView：水波、点击反馈、圆角、描边都是它自带的。
 */
final class VoiceListAdapter extends ListAdapter<VoiceItem, RecyclerView.ViewHolder> {

    private static final int TYPE_HEAD = 0;
    private static final int TYPE_ROW = 1;

    interface Listener {
        /** 点了这一行（选它，或者给它填名字）。 */
        void onPick(VoiceItem it);

        /** 点了行尾的 🎧 —— 只出声，不算选中。 */
        void onAudition(VoiceItem it);
    }

    private static final DiffUtil.ItemCallback<VoiceItem> DIFF =
            new DiffUtil.ItemCallback<VoiceItem>() {
                @Override public boolean areItemsTheSame(@NonNull VoiceItem a, @NonNull VoiceItem b) {
                    return a.header == b.header && a.raw.equals(b.raw) && a.name.equals(b.name);
                }

                @Override public boolean areContentsTheSame(@NonNull VoiceItem a, @NonNull VoiceItem b) {
                    return a.name.equals(b.name) && a.tagLine.equals(b.tagLine)
                            && a.usedBy.equals(b.usedBy) && a.srcLabel.equals(b.srcLabel);
                }
            };

    private final Palette p;
    private final Ui ui;
    private final Listener l;

    VoiceListAdapter(Palette p, Ui ui, Listener l) {
        super(DIFF);
        this.p = p;
        this.ui = ui;
        this.l = l;
    }

    @Override public int getItemViewType(int pos) {
        return getItem(pos).header ? TYPE_HEAD : TYPE_ROW;
    }

    @NonNull @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
        if (type == TYPE_HEAD) {
            TextView h = new TextView(ui.c);
            h.setTextColor(p.accent);
            h.setTextSize(12);
            h.setTypeface(Typeface.DEFAULT_BOLD);
            h.setPadding(ui.dp(8), ui.dp(14), ui.dp(8), ui.dp(6));
            return new RecyclerView.ViewHolder(h) { };
        }
        return new Row(makeRow());
    }

    @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder vh, int pos) {
        final VoiceItem it = getItem(pos);
        if (vh instanceof Row) ((Row) vh).bind(it);
        else ((TextView) vh.itemView).setText(it.name);
    }

    /** 一行的壳：卡 + 「名字 / 标签」两行 + 行尾 🎧。 */
    private View makeRow() {
        MaterialCardView card = new MaterialCardView(ui.c);
        card.setCardBackgroundColor(p.cardHi);
        card.setStrokeColor(p.border);
        card.setStrokeWidth(Math.max(1, ui.dp(1)));
        card.setRadius(ui.dp(14));
        card.setCardElevation(0f);
        card.setClickable(true);
        card.setFocusable(true);
        card.setRippleColor(ColorStateList.valueOf(p.ripple));
        RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = ui.dp(4);
        lp.bottomMargin = ui.dp(4);
        card.setLayoutParams(lp);

        LinearLayout row = ui.row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(ui.dp(14), ui.dp(11), ui.dp(8), ui.dp(11));
        card.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout col = ui.col();
        TextView name = new TextView(ui.c);
        name.setTextColor(p.textPrimary);
        name.setTextSize(15);
        col.addView(name);
        TextView sub = new TextView(ui.c);
        sub.setTextSize(11.5f);
        sub.setPadding(0, ui.dp(3), 0, 0);
        col.addView(sub);
        row.addView(col, ui.weightGap(1f, 6));

        TextView ear = ui.chip("🎧", false, null);
        row.addView(ear);
        return card;
    }

    private final class Row extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView sub;
        final TextView ear;

        Row(View v) {
            super(v);
            LinearLayout row = (LinearLayout) ((MaterialCardView) v).getChildAt(0);
            LinearLayout col = (LinearLayout) row.getChildAt(0);
            name = (TextView) col.getChildAt(0);
            sub = (TextView) col.getChildAt(1);
            ear = (TextView) row.getChildAt(1);
        }

        void bind(final VoiceItem it) {
            name.setText(it.name);
            String s = it.tagLine;
            if (it.usedBy.length() > 0) {
                s = (s.length() > 0 ? s + "  ·  " : "") + "已用于 " + it.usedBy;
            } else if (it.srcLabel.length() > 0) {
                s = (s.length() > 0 ? s + "  ·  " : "") + it.srcLabel;
            }
            sub.setText(s);
            sub.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
            sub.setTextColor(it.usedBy.length() > 0 ? p.accent : p.textMuted);

            itemView.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { l.onPick(it); }
            });
            ear.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { l.onAudition(it); }
            });
        }
    }
}
