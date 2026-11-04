package com.mermergi.ttsrelay.tab;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.mermergi.ttsrelay.LogBus;
import com.mermergi.ttsrelay.Palette;
import com.mermergi.ttsrelay.Prefs;
import com.mermergi.ttsrelay.R;
import com.mermergi.ttsrelay.RelayService;
import com.mermergi.ttsrelay.Speaker;
import com.mermergi.ttsrelay.Ui;
import com.mermergi.ttsrelay.ui.Widgets;

/** 首页：状态大卡 + 统计 + 地址卡 + 快捷卡。 */
public final class HomeTab implements Tab {
    private final Ui ui;
    private final Palette p;
    private final Tab.Host host;
    /** 启中转前把设置页里没落盘的输入框存一下（旧 toggle 的行为）。 */
    private final Runnable onSaveInputs;
    private final Handler tick = new Handler(Looper.getMainLooper());

    private TextView heroDot, heroStatus, heroSub;
    private Button toggleBtn, pauseBtn, muteBtn;
    private final TextView[] stat = new TextView[4];
    private TextView addrView, lanAddrView;
    private com.google.android.material.materialswitch.MaterialSwitch lanSwitch;

    public HomeTab(Ui ui, Palette p, Tab.Host host, Runnable onSaveInputs) {
        this.ui = ui;
        this.p = p;
        this.host = host;
        this.onSaveInputs = onSaveInputs;
    }

    @Override
    public View build(Context ctx) {
        LinearLayout col = ui.col();

        // ── 状态大卡 ──
        LinearLayout hero = ui.col();
        // M3 filled tonal card：primaryContainer 底 + XL 圆角（Material You 的标志性大色块）
        hero.setBackground(ui.round(p.accentSoft, Ui.R_XL));
        hero.setPadding(ui.dp(20), ui.dp(20), ui.dp(20), ui.dp(18));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.bottomMargin = ui.dp(12);
        hero.setLayoutParams(hlp);

        LinearLayout stRow = ui.row();
        stRow.setGravity(Gravity.CENTER_VERTICAL);
        heroDot = new TextView(ctx);
        heroDot.setText("●");
        heroDot.setTextSize(15);
        stRow.addView(heroDot);
        heroStatus = new TextView(ctx);
        heroStatus.setTextAppearance(ctx, R.style.TextAppearance_Material3_HeadlineSmall);
        heroStatus.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        heroStatus.setTextColor(p.onAccentSoft);
        stRow.addView(heroStatus);
        LinearLayout.LayoutParams hsp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hsp.leftMargin = ui.dp(8);
        heroStatus.setLayoutParams(hsp);
        hero.addView(stRow);

        heroSub = new TextView(ctx);
        heroSub.setTextAppearance(ctx, R.style.TextAppearance_Material3_BodyMedium);
        heroSub.setTextColor(p.onAccentSoft);
        heroSub.setLineSpacing(ui.dp(4), 1f);
        heroSub.setPadding(ui.dp(2), ui.dp(8), 0, ui.dp(16));
        hero.addView(heroSub);

        toggleBtn = ui.bigBtn("启动中转", true, new View.OnClickListener() {
            public void onClick(View v) { toggle(); }
        });
        hero.addView(toggleBtn);

        LinearLayout quick = ui.row();
        quick.setLayoutParams(Widgets.topGap(ui, 8));
        pauseBtn = ui.btnOnContainer("暂停", new View.OnClickListener() {
            public void onClick(View v) { togglePause(); }
        });
        muteBtn = ui.btnOnContainer("静音", new View.OnClickListener() {
            public void onClick(View v) { toggleMute(); }
        });
        Button skipB = ui.btnOnContainer("下一句", new View.OnClickListener() {
            public void onClick(View v) { skipCurrent(); }
        });
        quick.addView(pauseBtn, ui.weightGap(1f, 8));
        quick.addView(muteBtn, ui.weightGap(1f, 8));
        quick.addView(skipB, ui.weight(1f));
        hero.addView(quick);
        col.addView(hero);

        // ── 统计瓷砖 ──
        LinearLayout stats = ui.row();
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.bottomMargin = ui.dp(12);
        stats.setLayoutParams(slp);
        String[] caps = {"已念句", "字数", "队列", "失败"};
        int[] colors = {p.accent, p.textPrimary, p.warn, p.err};
        for (int i = 0; i < 4; i++) {
            TextView[] out = new TextView[1];
            stats.addView(ui.tile(caps[i], colors[i], out),
                    i < 3 ? ui.weightGap(1f, 8) : ui.weight(1f));
            stat[i] = out[0];
        }
        col.addView(stats);

        // ── 地址卡 ──
        LinearLayout ac = ui.card();
        ac.addView(ui.sectionTitle("对话软件里填的 API 主机"));
        addrView = new TextView(ctx);
        addrView.setTextSize(14);
        addrView.setTypeface(Typeface.MONOSPACE);
        ac.addView(addrView);
        lanAddrView = new TextView(ctx);
        lanAddrView.setTextSize(12);
        lanAddrView.setTypeface(Typeface.MONOSPACE);
        lanAddrView.setTextColor(p.textSecondary);
        lanAddrView.setPadding(0, ui.dp(4), 0, 0);
        ac.addView(lanAddrView);

        LinearLayout ab = ui.row();
        ab.setLayoutParams(Widgets.topGap(ui, 12));
        ab.addView(ui.btn("复制本机地址", true, new View.OnClickListener() {
            public void onClick(View v) { host.copy("http://127.0.0.1:" + curPort(ctx) + "/v1"); }
        }), ui.weightGap(1f, 8));
        ab.addView(ui.btn("复制局域网地址", false, new View.OnClickListener() {
            public void onClick(View v) {
                String ip = lanIp();
                if (ip == null) { host.toast("没找到局域网 IP"); return; }
                host.copy("http://" + ip + ":" + curPort(ctx) + "/v1");
            }
        }), ui.weight(1f));
        ac.addView(ab);

        lanSwitch = ui.switchBox("允许局域网访问");
        lanSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                Prefs.setLanAllowed(ctx, checked);
                host.toast(checked ? "已开启 —— 注意：局域网里谁都能用你的 key" : "已关闭");
            }
        });
        ac.addView(lanSwitch);
        col.addView(ac);

        // ── 快捷卡 ──
        LinearLayout tc = ui.card();
        tc.addView(ui.sectionTitle("快捷"));
        LinearLayout tb = ui.row();
        tb.addView(ui.btn("TTS Server", false, new View.OnClickListener() {
            public void onClick(View v) { openTtsServer(ctx); }
        }), ui.weightGap(1f, 8));
        tb.addView(ui.btn("自检", false, new View.OnClickListener() {
            public void onClick(View v) { Widgets.selfTest(ctx, curPort(ctx)); }
        }), ui.weightGap(1f, 8));
        tb.addView(ui.btn("清空日志", false, new View.OnClickListener() {
            public void onClick(View v) {
                LogBus.clear();
                host.toast("日志已清空");
            }
        }), ui.weight(1f));
        tc.addView(tb);
        col.addView(tc);

        return Widgets.scroll(ctx, ui, col);
    }

    @Override
    public void clearRefs() {
        heroDot = null; heroStatus = null; heroSub = null;
        toggleBtn = null; pauseBtn = null; muteBtn = null;
        stat[0] = null; stat[1] = null; stat[2] = null; stat[3] = null;
        addrView = null; lanAddrView = null; lanSwitch = null;
    }

    @Override
    public void refresh() {
        Context ctx = host.activity();
        boolean up = RelayService.isUp();
        int port = Prefs.port(ctx);
        com.mermergi.ttsrelay.HttpServer srv = RelayService.serverOrNull();
        Speaker spk = RelayService.speakerOrNull();
        if (srv != null && srv.isRunning()) port = srv.port();

        boolean muted = Prefs.muted(ctx);
        boolean paused = spk != null && spk.isPaused();

        if (heroStatus != null) {
            heroStatus.setText(up ? "运行中" : "未运行");
            heroStatus.setTextColor(up ? p.ok : Palette.alpha(p.onAccentSoft, 0.72f));
            heroDot.setTextColor(up ? p.ok : Palette.alpha(p.onAccentSoft, 0.72f));
            String state = !up ? "点下面按钮启动"
                    : (muted ? "已静音 · 只中转不朗读" : (paused ? "已暂停朗读" : "正在朗读"));
            heroSub.setText("127.0.0.1:" + port + "  →  " + shortHost(Prefs.upstream(ctx))
                    + "\n" + state);
        }
        if (toggleBtn != null) toggleBtn.setText(up ? "停止中转" : "启动中转");
        if (pauseBtn != null) pauseBtn.setText(paused ? "继续" : "暂停");
        if (muteBtn != null) muteBtn.setText(muted ? "取消静音" : "静音");

        if (stat[0] != null) stat[0].setText(String.valueOf(spk == null ? 0 : spk.spokenCount()));
        if (stat[1] != null) stat[1].setText(String.valueOf(spk == null ? 0 : spk.charCount()));
        if (stat[2] != null) stat[2].setText(String.valueOf(spk == null ? 0 : spk.queued()));
        if (stat[3] != null) stat[3].setText(String.valueOf(spk == null ? 0 : spk.failedCount()));

        if (addrView != null) addrView.setText("http://127.0.0.1:" + port + "/v1");
        if (lanAddrView != null) {
            String ip = lanIp();
            lanAddrView.setText(ip == null
                    ? "（没找到局域网 IP）"
                    : "http://" + ip + ":" + port + "/v1"
                    + (Prefs.lanAllowed(ctx) ? "  · 已开" : "  · 未开"));
        }
        if (lanSwitch != null && lanSwitch.isChecked() != Prefs.lanAllowed(ctx)) {
            lanSwitch.setChecked(Prefs.lanAllowed(ctx));
        }
    }

    private static String shortHost(String u) {
        try { return new java.net.URL(u).getHost(); } catch (Throwable t) { return u; }
    }

    private static int curPort(Context ctx) {
        com.mermergi.ttsrelay.HttpServer srv = RelayService.serverOrNull();
        return (srv != null && srv.isRunning()) ? srv.port() : Prefs.port(ctx);
    }

    private static String lanIp() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> ifs =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (ifs != null && ifs.hasMoreElements()) {
                java.net.NetworkInterface ni = ifs.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                java.util.Enumeration<java.net.InetAddress> as = ni.getInetAddresses();
                while (as.hasMoreElements()) {
                    java.net.InetAddress a = as.nextElement();
                    if (a instanceof java.net.Inet4Address && a.isSiteLocalAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private void toggle() {
        boolean up = RelayService.isUp();
        Activity a = host.activity();
        Intent i = new Intent(a, RelayService.class);
        if (up) {
            i.setAction(RelayService.ACTION_STOP);
            try { a.startService(i); } catch (Throwable ignored) { }
            host.toast("已停止");
        } else {
            onSaveInputs.run();
            try {
                if (android.os.Build.VERSION.SDK_INT >= 26) a.startForegroundService(i);
                else a.startService(i);
            } catch (Throwable t) {
                host.toast("启动失败：" + t.getMessage());
            }
        }
        tick.postDelayed(new Runnable() { public void run() { host.requestRefresh(); } }, 600);
    }

    private void togglePause() {
        Speaker spk = RelayService.speakerOrNull();
        if (spk == null) { host.toast("先启动中转"); return; }
        if (spk.isPaused()) spk.resume(); else spk.pause();
        host.requestRefresh();
    }

    private void toggleMute() {
        Activity a = host.activity();
        boolean m = !Prefs.muted(a);
        Prefs.setMuted(a, m);
        Speaker spk = RelayService.speakerOrNull();
        if (m && spk != null) spk.interrupt();
        host.toast(m ? "已静音：只当中转用，不朗读" : "已取消静音");
        host.requestRefresh();
    }

    private void skipCurrent() {
        Speaker spk = RelayService.speakerOrNull();
        if (spk == null) { host.toast("先启动中转"); return; }
        spk.skipCurrent();
        host.toast("已切到下一句");
    }

    private void openTtsServer(Context ctx) {
        try {
            Intent i = ctx.getPackageManager().getLaunchIntentForPackage(Prefs.engine(ctx));
            if (i == null) { host.toast("没装：" + Prefs.engine(ctx)); return; }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Throwable t) {
            host.toast("打开失败：" + t.getMessage());
        }
    }


}
