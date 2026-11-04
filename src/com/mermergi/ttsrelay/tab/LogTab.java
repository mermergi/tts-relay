package com.mermergi.ttsrelay.tab;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.mermergi.ttsrelay.LogBus;
import com.mermergi.ttsrelay.Palette;
import com.mermergi.ttsrelay.Ui;

/** 日志页：每秒从 LogBus 拉全文渲染，滚动到底。 */
public final class LogTab implements Tab {
    private final Ui ui;
    private final Palette p;
    private final Tab.Host host;

    private TextView logView;
    private int logLen = -1;

    public LogTab(Ui ui, Palette p, Tab.Host host) {
        this.ui = ui;
        this.p = p;
        this.host = host;
    }

    @Override
    public View build(Context ctx) {
        LinearLayout root = ui.col();
        root.setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(12));

        LinearLayout head = ui.row();
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = ui.sectionTitle("运行日志");
        t.setPadding(0, 0, 0, 0);
        head.addView(t, ui.weight(1f));
        head.addView(ui.btn("复制全部", false, new View.OnClickListener() {
            public void onClick(View v) { host.copy(LogBus.dump()); }
        }));
        LinearLayout.LayoutParams hg = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hg.leftMargin = ui.dp(8);
        hg.rightMargin = ui.dp(8);
        head.addView(ui.btn("清空", false, new View.OnClickListener() {
            public void onClick(View v) {
                LogBus.clear();
                if (logView != null) logView.setText("");
                logLen = 0;
            }
        }), hg);
        root.addView(head);

        ScrollView sv = new ScrollView(ctx);
        sv.setBackground(ui.round(p.card, p.border, 16));
        sv.setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12));
        logView = new TextView(ctx);
        logView.setTextSize(11);
        logView.setTextColor(p.log);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        logView.setLineSpacing(ui.dp(2), 1f);
        sv.addView(logView);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        lp.topMargin = ui.dp(10);
        root.addView(sv, lp);
        return root;
    }

    @Override
    public void refresh() {
        if (logView == null) return;
        String all = LogBus.dump();
        if (all.length() == logLen) return;
        logLen = all.length();
        logView.setText(all);
        View parent = (View) logView.getParent();
        if (parent instanceof ScrollView) {
            final ScrollView sv = (ScrollView) parent;
            sv.post(new Runnable() {
                public void run() { sv.fullScroll(View.FOCUS_DOWN); }
            });
        }
    }

    @Override
    public void clearRefs() {
        logView = null;
        logLen = -1;
    }
}
