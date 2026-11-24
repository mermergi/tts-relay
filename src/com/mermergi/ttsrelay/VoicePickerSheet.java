package com.mermergi.ttsrelay;

import android.content.Context;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.util.ArrayList;
import java.util.List;

/**
 * 「选择音色」底部弹窗 —— 声音页（默认 / 角色 / 用户消息）和编辑插件 TTS 页共用
 * 同一个页面：搜索、性别筛选、左栏按来源分组、试听、点选。
 *
 * 点选时弹窗自动收起，再把选中的音色交给 listener。
 */
public final class VoicePickerSheet {

    private VoicePickerSheet() { }

    public static void show(Context act, Ui ui, Palette p, int theme, String title,
                            List<VoiceItem> all, List<EngineStore.SourceGroup> groups,
                            final VoiceListAdapter.Listener listener) {
        if (all == null || all.isEmpty()) return;

        LinearLayout box = ui.col();
        box.setBackground(ui.sheetBg());
        box.setPadding(ui.dp(16), 0, ui.dp(16), ui.dp(10));

        TextView tv = new TextView(act);
        tv.setText(title);
        tv.setTextColor(p.textPrimary);
        tv.setTextSize(17);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(tv);

        final EditText filter = new EditText(act);
        filter.setHint("搜：名字 / 女声 / 青年 / 温柔…");
        filter.setSingleLine(true);
        filter.setTextColor(p.textPrimary);
        filter.setHintTextColor(p.textMuted);
        filter.setTextSize(14);
        filter.setBackground(ui.round(p.cardHi, p.border, 12));
        filter.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10));
        box.addView(filter);

        final LinearLayout chips = ui.row();
        chips.setPadding(0, ui.dp(10), 0, ui.dp(2));
        box.addView(chips);

        final RecyclerView rv = new RecyclerView(act);
        rv.setLayoutManager(new LinearLayoutManager(act));
        rv.setClipToPadding(false);
        rv.setPadding(0, ui.dp(2), 0, ui.dp(6));

        // ── 左边那一栏：按来源引擎分类 ──
        final LinearLayout rail = ui.col();
        rail.setPadding(0, ui.dp(4), ui.dp(8), 0);
        ScrollView railScroll = new ScrollView(act);
        railScroll.addView(rail);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ui.dp(104),
                ViewGroup.LayoutParams.MATCH_PARENT);
        railScroll.setLayoutParams(rlp);

        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(0, ui.dp(430), 1f);
        vlp.topMargin = ui.dp(4);
        rv.setLayoutParams(vlp);

        LinearLayout mid = ui.row();
        mid.addView(railScroll);
        mid.addView(rv);
        box.addView(mid);

        final String[] kw = new String[]{""};
        final String[] gen = new String[]{""};
        final String[] src = new String[]{""};
        final List<VoiceItem> rows = new ArrayList<VoiceItem>();

        final BottomSheetDialog dlg = new BottomSheetDialog(act, theme);
        dlg.setContentView(box);

        VoiceListAdapter ad = new VoiceListAdapter(p, ui, new VoiceListAdapter.Listener() {
            public void onPick(VoiceItem vi) {
                if (vi == null || vi.header) return;
                dlg.dismiss();
                if (listener != null) listener.onPick(vi);
            }

            public void onAudition(VoiceItem vi) {
                if (vi == null || vi.header) return;
                if (listener != null) listener.onAudition(vi);
            }
        });
        rv.setAdapter(ad);

        final Runnable refill = new Runnable() {
            public void run() {
                fillVoiceList(rows, all, kw[0], gen[0], src[0]);
                ad.submitList(new ArrayList<VoiceItem>(rows));
            }
        };
        renderGenderChips(ui, chips, p, gen, refill);
        renderSourceRail(ui, rail, p, groups, all, src, refill, rv);
        refill.run();

        filter.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(Editable e) {
                kw[0] = e.toString().trim();
                refill.run();
            }
        });

        dlg.show();
        try {   // 起来就是全展开（不是先露出一个 peek）
            dlg.getBehavior().setSkipCollapsed(true);
            dlg.getBehavior().setState(
                    com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
        } catch (Throwable ignored) { }
    }

    private static void renderSourceRail(final Ui ui, final LinearLayout rail, final Palette p,
                                         final List<EngineStore.SourceGroup> groups,
                                         final List<VoiceItem> all, final String[] src,
                                         final Runnable refill, final RecyclerView rv) {
        rail.removeAllViews();
        if (groups == null) return;
        for (final EngineStore.SourceGroup g : groups) {
            if (g.voices.isEmpty()) continue;
            final boolean on = g.id.equals(src[0]);
            TextView c = ui.chip(shortSrc(g.label) + " " + g.voices.size(), on,
                    new android.view.View.OnClickListener() {
                        public void onClick(android.view.View v) {
                            src[0] = on ? "" : g.id;      // 再点一下 = 不过滤
                            renderSourceRail(ui, rail, p, groups, all, src, refill, rv);
                            refill.run();
                            rv.scrollToPosition(0);
                        }
                    });
            c.setTextSize(11.5f);
            c.setSingleLine(true);
            c.setEllipsize(android.text.TextUtils.TruncateAt.END);
            c.setPadding(ui.dp(8), ui.dp(7), ui.dp(8), ui.dp(7));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = ui.dp(6);
            rail.addView(c, lp);
        }
    }

    private static String shortSrc(String label) {
        if (label == null) return "?";
        String s = label.trim();
        int i = s.indexOf('（');
        if (i > 0) s = s.substring(0, i);
        s = s.replaceAll("\\s+", "");
        return s.length() > 6 ? s.substring(0, 6) : s;
    }

    private static void fillVoiceList(List<VoiceItem> out, List<VoiceItem> all,
                                      String kw, String gen, String srcId) {
        out.clear();
        for (VoiceItem v : all) {
            if (gen.length() > 0 && !gen.equals(v.gender)) continue;
            if (srcId.length() > 0 && !srcId.equals(v.srcId)) continue;
            if (kw.length() > 0 && !v.name.contains(kw) && !v.tagLine.contains(kw)
                    && !v.raw.contains(kw)) continue;
            out.add(v);
        }
    }

    private static void renderGenderChips(final Ui ui, final LinearLayout chips, final Palette p,
                                          final String[] gen, final Runnable refill) {
        chips.removeAllViews();
        final String[] opts = {"女声", "男声", "未标注"};
        for (final String o : opts) {
            final boolean on = o.equals(gen[0]);
            TextView c = ui.chip(o, on, new android.view.View.OnClickListener() {
                public void onClick(android.view.View v) {
                    gen[0] = on ? "" : o;        // 再点一下 = 不过滤
                    renderGenderChips(ui, chips, p, gen, refill);
                    refill.run();
                }
            });
            chips.addView(c, ui.weightGap(1f, 8));
        }
    }
}
