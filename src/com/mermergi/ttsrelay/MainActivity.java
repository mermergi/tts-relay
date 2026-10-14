package com.mermergi.ttsrelay;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends Activity {

    private static final int REQ_NOTIF = 41;

    private TextView statusView;
    private TextView voiceView;
    private TextView logView;
    private TextView addrView;
    private Button toggleBtn;
    private Switch muteSwitch;
    private Switch lanSwitch;
    private Button splitBtn;
    private final Handler tick = new Handler();
    private int logLen = 0;

    private final Runnable refresher = new Runnable() {
        public void run() {
            refresh();
            tick.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        buildUi();
        if (Build.VERSION.SDK_INT >= 33) {
            try { requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIF); }
            catch (Throwable ignored) { }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 上次是开着的就自动拉起来
        if (Prefs.wanted(this) && !RelayService.isUp()) {
            try { startForegroundService(new Intent(this, RelayService.class)); } catch (Throwable ignored) { }
        }
        tick.removeCallbacks(refresher);
        tick.post(refresher);
        syncInputs();
    }

    @Override
    protected void onPause() {
        super.onPause();
        tick.removeCallbacks(refresher);
        saveInputs();
    }

    // ══════════════════════════ UI ══════════════════════════
    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(0xFF9AA0B4);
        t.setTextSize(12);
        t.setPadding(0, dp(12), 0, dp(4));
        return t;
    }

    private EditText field(String key, String def, int inputType) {
        EditText e = new EditText(this);
        e.setTag(key);
        e.setText(def);
        e.setInputType(inputType);
        e.setTextSize(14);
        e.setSingleLine(true);
        return e;
    }

    private Button btn(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private View card() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackgroundColor(0xFF1B1B24);
        l.setPadding(dp(16), dp(14), dp(16), dp(16));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, 0, 0, dp(12));
        l.setLayoutParams(p);
        return l;
    }

    @SuppressWarnings("deprecation")
    private void buildUi() {
        ScrollView root = new ScrollView(this);
        root.setBackgroundColor(0xFF121218);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(14), dp(18), dp(14), dp(30));
        root.addView(col);

        TextView title = new TextView(this);
        title.setText("朗读中转");
        title.setTextColor(Color.WHITE);
        title.setTextSize(26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(title);

        TextView sub = new TextView(this);
        sub.setText("手机上的 AI 对话软件，每一条回复都念出来");
        sub.setTextColor(0xFF7C83A0);
        sub.setTextSize(13);
        sub.setPadding(0, dp(4), 0, dp(16));
        col.addView(sub);

        // ---- 状态卡 ----
        LinearLayout st = (LinearLayout) card();
        statusView = new TextView(this);
        statusView.setTextColor(0xFFD5DAE8);
        statusView.setTextSize(14);
        statusView.setLineSpacing(dp(3), 1f);
        st.addView(statusView);
        col.addView(st);

        // ---- 主开关 ----
        toggleBtn = btn("启动中转", new View.OnClickListener() {
            public void onClick(View v) { toggle(); }
        });
        toggleBtn.setTextSize(17);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        tp.setMargins(0, 0, 0, dp(12));
        toggleBtn.setLayoutParams(tp);
        col.addView(toggleBtn);

        // ---- 对话软件要填的地址 ----
        LinearLayout ac = (LinearLayout) card();
        ac.addView(label("对话软件里填的「API 主机」"));
        addrView = new TextView(this);
        addrView.setTextColor(0xFF7A9EFF);
        addrView.setTextSize(14);
        addrView.setTypeface(Typeface.MONOSPACE);
        addrView.setPadding(0, 0, 0, dp(8));
        ac.addView(addrView);
        LinearLayout ab = new LinearLayout(this);
        ab.setOrientation(LinearLayout.HORIZONTAL);
        ab.addView(btn("复制本机地址", new View.OnClickListener() {
            public void onClick(View v) { copy("http://127.0.0.1:" + curPort() + "/v1"); }
        }), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ab.addView(btn("复制局域网地址", new View.OnClickListener() {
            public void onClick(View v) {
                String ip = lanIp();
                if (ip == null) { toast("没找到局域网 IP"); return; }
                copy("http://" + ip + ":" + curPort() + "/v1");
            }
        }), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ac.addView(ab);
        lanSwitch = new Switch(this);
        lanSwitch.setText("允许局域网访问（给电脑上的酒馆 / 其它设备用）");
        lanSwitch.setTextColor(0xFFD5DAE8);
        lanSwitch.setTextSize(13);
        lanSwitch.setPadding(0, dp(10), 0, 0);
        lanSwitch.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                Prefs.setLanAllowed(MainActivity.this, checked);
                toast(checked ? "已开启（重启中转生效）——注意：局域网里谁都能用你的 key"
                        : "已关闭（重启中转生效）");
            }
        });
        ac.addView(lanSwitch);
        col.addView(ac);

        // ---- 设置卡 ----
        LinearLayout sc = (LinearLayout) card();
        sc.addView(label("上游 API 地址"));
        final EditText fUp = field("upstream", Prefs.UPSTREAM_DEF, InputType.TYPE_TEXT_VARIATION_URI);
        fUp.setTag("upstream");
        sc.addView(fUp);
        sc.addView(label("监听端口"));
        final EditText fPort = field("port", String.valueOf(Prefs.PORT_DEF), InputType.TYPE_CLASS_NUMBER);
        fPort.setTag("port");
        sc.addView(fPort);
        sc.addView(label("TTS Server 转发器地址"));
        final EditText fTts = field("tts", Prefs.TTS_BASE_DEF, InputType.TYPE_TEXT_VARIATION_URI);
        fTts.setTag("tts");
        sc.addView(fTts);
        sc.addView(label("语速 / 音调"));
        LinearLayout rp = new LinearLayout(this);
        rp.setOrientation(LinearLayout.HORIZONTAL);
        final EditText fRate = field("rate", "1.0", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        fRate.setTag("rate");
        final EditText fPitch = field("pitch", "1.0", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        fPitch.setTag("pitch");
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        rp.addView(fRate, half);
        rp.addView(fPitch, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        sc.addView(rp);
        sc.addView(label("单句最长字数（超了硬切）"));
        final EditText fChunk = field("maxchunk", String.valueOf(Prefs.MAXCHUNK_DEF), InputType.TYPE_CLASS_NUMBER);
        fChunk.setTag("maxchunk");
        sc.addView(fChunk);
        sc.addView(label("断句方案"));
        splitBtn = btn("", new View.OnClickListener() {
            public void onClick(View v) { pickSplitMode(); }
        });
        splitBtn.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        sc.addView(splitBtn);
        sc.addView(hintLine("点一下换方案。五种只在\"哪里断、超长往哪退、要不要攒\"上不同；换完立刻生效，不用重启。"));
        muteSwitch = new Switch(this);
        muteSwitch.setText("静音（不朗读，只当中转用）");
        muteSwitch.setTextColor(0xFFD5DAE8);
        muteSwitch.setPadding(0, dp(10), 0, 0);
        sc.addView(muteSwitch);
        sc.addView(btn("保存设置", new View.OnClickListener() {
            public void onClick(View v) {
                saveInputs();
                toast("已保存，重启中转生效");
            }
        }));
        col.addView(sc);

        // ---- 音色卡 ----
        LinearLayout vc = (LinearLayout) card();
        vc.addView(label("音色"));
        voiceView = new TextView(this);
        voiceView.setTextColor(0xFFD5DAE8);
        voiceView.setTextSize(13);
        voiceView.setPadding(0, 0, 0, dp(8));
        vc.addView(voiceView);
        vc.addView(label("试听文本（随便改，试听和换音色都念这句）"));
        final EditText fTry = field("trytext", Prefs.TRY_DEF, InputType.TYPE_CLASS_TEXT);
        vc.addView(fTry);
        LinearLayout vb = new LinearLayout(this);
        vb.setOrientation(LinearLayout.HORIZONTAL);
        vb.addView(btn("选择音色", new View.OnClickListener() {
            public void onClick(View v) { pickVoice(); }
        }), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        vb.addView(btn("试听", new View.OnClickListener() {
            public void onClick(View v) { testSpeak(); }
        }), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        vb.addView(btn("自检", new View.OnClickListener() {
            public void onClick(View v) { selfTest(); }
        }), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        vc.addView(vb);
        TextView hint = new TextView(this);
        hint.setText("自检 = 不连模型，直接走一遍断句/跳代码块/合成/播放，用来排除故障");
        hint.setTextColor(0xFF6E7386);
        hint.setTextSize(11);
        hint.setPadding(0, dp(6), 0, 0);
        vc.addView(hint);
        col.addView(vc);

        // ---- 日志卡 ----
        LinearLayout lc = (LinearLayout) card();
        LinearLayout lh = new LinearLayout(this);
        lh.setOrientation(LinearLayout.HORIZONTAL);
        lh.setGravity(Gravity.CENTER_VERTICAL);
        TextView lt = label("运行日志");
        lh.addView(lt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        lh.addView(btn("清空", new View.OnClickListener() {
            public void onClick(View v) {
                LogBus.clear();
                logView.setText("");
                logLen = 0;
            }
        }));
        lc.addView(lh);

        ScrollView logScroll = new ScrollView(this);
        logScroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(240)));
        logView = new TextView(this);
        logView.setTextColor(0xFF8FE3A2);
        logView.setTextSize(11);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        logScroll.addView(logView);
        lc.addView(logScroll);
        col.addView(lc);

        setContentView(root);
    }

    // ══════════════════════════ 行为 ══════════════════════════
    private void toggle() {
        boolean up = RelayService.isUp();
        Intent i = new Intent(this, RelayService.class);
        if (up) {
            i.setAction(RelayService.ACTION_STOP);
            try { startService(i); } catch (Throwable ignored) { }
            toast("已停止");
        } else {
            saveInputs();
            try {
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
                else startService(i);
            } catch (Throwable t) {
                toast("启动失败：" + t.getMessage());
            }
        }
        tick.postDelayed(new Runnable() { public void run() { refresh(); } }, 600);
    }

    private List<EditText> fields() {
        List<EditText> out = new ArrayList<EditText>();
        collect((ViewGroup) findViewById(android.R.id.content), out);
        return out;
    }

    private void collect(ViewGroup g, List<EditText> out) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View v = g.getChildAt(i);
            if (v instanceof EditText) out.add((EditText) v);
            else if (v instanceof ViewGroup) collect((ViewGroup) v, out);
        }
    }

    /** 带标记的输入框（避免依赖 findViewById 的 id 生成）。 */
    private final List<EditText> tagged = new ArrayList<EditText>();

    private void saveInputs() {
        List<EditText> fs = fields();
        for (EditText e : fs) {
            Object tag = e.getTag();
            if (!(tag instanceof String)) continue;
            String k = (String) tag;
            String v = e.getText().toString().trim();
            if (v.length() == 0) continue;
            if (k.equals("upstream")) Prefs.setUpstream(this, v);
            else if (k.equals("tts")) Prefs.setTtsBase(this, v);
            else if (k.equals("rate")) Prefs.setRate(this, v);
            else if (k.equals("pitch")) Prefs.setPitch(this, v);
            else if (k.equals("trytext")) Prefs.setTryText(this, v);
            else if (k.equals("maxchunk")) {
                try { Prefs.setMaxChunk(this, Integer.parseInt(v)); } catch (Throwable ignored) { }
            } else if (k.equals("port")) {
                try { Prefs.setPort(this, Integer.parseInt(v)); } catch (Throwable ignored) { }
            }
        }
        Prefs.setMuted(this, muteSwitch != null && muteSwitch.isChecked());
    }

    private void syncInputs() {
        for (EditText e : fields()) {
            Object tag = e.getTag();
            if (!(tag instanceof String)) continue;
            String k = (String) tag;
            if (k.equals("upstream")) e.setText(Prefs.upstream(this));
            else if (k.equals("tts")) e.setText(Prefs.ttsBase(this));
            else if (k.equals("rate")) e.setText(Prefs.rate(this));
            else if (k.equals("pitch")) e.setText(Prefs.pitch(this));
            else if (k.equals("trytext")) e.setText(Prefs.tryText(this));
            else if (k.equals("maxchunk")) e.setText(String.valueOf(Prefs.maxChunk(this)));
            else if (k.equals("port")) e.setText(String.valueOf(Prefs.port(this)));
        }
        if (muteSwitch != null) muteSwitch.setChecked(Prefs.muted(this));
    }

    private void refresh() {
        boolean up = RelayService.isUp();
        int port = Prefs.port(this);
        HttpServer srv = RelayService.serverOrNull();
        Speaker spk = RelayService.speakerOrNull();
        if (srv != null && srv.isRunning()) port = srv.port();

        StringBuilder sb = new StringBuilder();
        sb.append(up ? "● 运行中" : "○ 未运行").append('\n');
        sb.append("监听     127.0.0.1:").append(port).append('\n');
        sb.append("上游     ").append(Prefs.upstream(this)).append('\n');
        sb.append("已念     ").append(spk == null ? 0 : spk.spokenCount()).append(" 句（")
                .append(spk == null ? 0 : spk.charCount()).append(" 字）\n");
        sb.append("队列     ").append(spk == null ? 0 : spk.queued()).append('\n');
        sb.append("静音     ").append(Prefs.muted(this) ? "开" : "关").append('\n');
        sb.append("失败     ").append(spk == null ? 0 : spk.failedCount()).append(" 次");
        if (spk != null && spk.stuckCount() > 0) sb.append("  卡死救回 ").append(spk.stuckCount()).append(" 次");
        String err = LogBus.lastError();
        if (err.length() > 0) sb.append("\n最近错误 ").append(err.length() > 90 ? err.substring(0, 90) + "…" : err);
        statusView.setText(sb.toString());

        addrView.setText("http://127.0.0.1:" + port + "/v1"
                + (lanIp() != null ? "\nhttp://" + lanIp() + ":" + port + "/v1  "
                + (Prefs.lanAllowed(this) ? "(局域网·已开)" : "(局域网·未开)") : ""));
        if (lanSwitch != null && lanSwitch.isChecked() != Prefs.lanAllowed(this)) {
            lanSwitch.setChecked(Prefs.lanAllowed(this));
        }
        toggleBtn.setText(up ? "停止中转" : "启动中转");
        if (muteSwitch != null && muteSwitch.isChecked() != Prefs.muted(this)) {
            muteSwitch.setChecked(Prefs.muted(this));
        }
        String v = Prefs.voice(this);
        voiceView.setText(v.length() == 0 ? "当前：（未选）用 TTS Server 自己的默认音色"
                : "当前：" + v.replaceAll("_\\d+$", ""));
        if (splitBtn != null) {
            splitBtn.setText("当前：" + Splitter.modeName(Prefs.splitMode(this)) + "  ▸");
        }

        if (spk != null && up && (System.currentTimeMillis() % 5000) < 1000) {
            RelayService svc = RelayService.instance();
            if (svc != null) svc.updateNotification();
        }

        String all = LogBus.dump();
        if (all.length() != logLen) {
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
    }

    private void copy(String s) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("relay", s));
        toast("已复制：" + s);
    }

    private int curPort() {
        HttpServer srv = RelayService.serverOrNull();
        return (srv != null && srv.isRunning()) ? srv.port() : Prefs.port(this);
    }

    /** 找一个局域网 IPv4，给电脑上的客户端用。 */
    private String lanIp() {
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

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ══════════════════════════ 音色 ══════════════════════════
    private void testSpeak() {
        Speaker spk = RelayService.speakerOrNull();
        if (spk == null) { toast("先启动中转"); return; }
        saveInputs();
        // 试听也走真正的断句方案：听不出来差别就多写几句、把标点铺开
        String text = Prefs.tryText(this);
        Splitter sp = new Splitter(Prefs.maxChunk(this), 4, Prefs.splitMode(this));
        List<String> parts = new ArrayList<String>();
        for (int i = 0; i < text.length(); i++) parts.addAll(sp.feed(String.valueOf(text.charAt(i))));
        parts.addAll(sp.flush());
        for (String s : parts) spk.feed(s);
        LogBus.add("试听 · 断句方案「" + Splitter.modeName(Prefs.splitMode(this))
                + "」切成 " + parts.size() + " 块：");
        for (String x : parts) LogBus.add("   ▸ " + x);
        toast("已排队（" + parts.size() + " 块，方案：" + Splitter.modeName(Prefs.splitMode(this)) + "）");
    }

    /** 换断句方案：列出五种 + 一句话说明，选完即存、即生效。 */
    private void pickSplitMode() {
        final String[] items = new String[Splitter.MODE_COUNT];
        for (int i = 0; i < Splitter.MODE_COUNT; i++) {
            items[i] = Splitter.modeName(i) + "\n" + Splitter.modeDesc(i);
        }
        new AlertDialog.Builder(this)
                .setTitle("断句方案")
                .setSingleChoiceItems(items, Prefs.splitMode(this),
                        new android.content.DialogInterface.OnClickListener() {
                            public void onClick(android.content.DialogInterface d, int which) {
                                Prefs.setSplitMode(MainActivity.this, which);
                                refresh();
                                toast("已切到「" + Splitter.modeName(which) + "」，立刻生效");
                                d.dismiss();
                            }
                        })
                .setNegativeButton("取消", null)
                .show();
    }

    private TextView hintLine(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(0xFF6E7386);
        t.setTextSize(11);
        t.setPadding(0, dp(6), 0, dp(4));
        return t;
    }

    private void selfTest() {
        if (!RelayService.isUp()) { toast("先启动中转"); return; }
        final int p = curPort();
        new Thread(new Runnable() {
            public void run() {
                try {
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                            new java.net.URL("http://127.0.0.1:" + p + "/_selftest").openConnection();
                    c.setConnectTimeout(5000);
                    c.setReadTimeout(15000);
                    c.getResponseCode();
                    c.disconnect();
                    runOnUiThread(new Runnable() {
                        public void run() { toast("自检音频已排队，听声音"); }
                    });
                } catch (Throwable t) {
                    final String m = String.valueOf(t.getMessage());
                    runOnUiThread(new Runnable() {
                        public void run() { toast("自检失败：" + m); }
                    });
                }
            }
        }).start();
    }

    private void pickVoice() {
        toast("正在拉取音色…");
        new Thread(new Runnable() {
            public void run() {
                String json;
                try {
                    json = TtsClient.voices(MainActivity.this);
                } catch (Throwable t) {
                    final String m = t.getMessage();
                    runOnUiThread(new Runnable() {
                        public void run() { toast("拉取失败：" + m + "（TTS Server 的转发器开了吗？）"); }
                    });
                    return;
                }
                final List<String> names = new ArrayList<String>();
                try {
                    JSONArray arr = new JSONArray(json);
                    Set<String> seen = new LinkedHashSet<String>();
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.optJSONObject(i);
                        if (o == null) continue;
                        String n = o.optString("name", "");
                        if (n.length() > 0 && seen.add(n)) names.add(n);
                    }
                } catch (Throwable ignored) { }
                runOnUiThread(new Runnable() {
                    public void run() { showVoiceDialog(names); }
                });
            }
        }).start();
    }

    private void showVoiceDialog(final List<String> names) {
        if (names.isEmpty()) { toast("没拿到音色"); return; }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), dp(8));
        final EditText filter = new EditText(this);
        filter.setHint("输入关键词过滤，如：女声 / 青年 / 普通话");
        filter.setSingleLine(true);
        box.addView(filter);
        final ListView lv = new ListView(this);
        final ArrayAdapter<String> ad = new ArrayAdapter<String>(this,
                android.R.layout.simple_list_item_1, new ArrayList<String>(names));
        lv.setAdapter(ad);
        box.addView(lv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(360)));

        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("选择音色（共 " + names.size() + " 个）")
                .setView(box)
                .setNegativeButton("取消", null)
                .create();

        filter.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(android.text.Editable e) {
                String k = e.toString().trim();
                ad.clear();
                for (String n : names) if (k.length() == 0 || n.contains(k)) ad.add(n);
                ad.notifyDataSetChanged();
            }
        });

        lv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                String name = (String) p.getItemAtPosition(pos);
                Prefs.setVoice(MainActivity.this, name);
                Speaker spk = RelayService.speakerOrNull();
                if (spk != null) spk.feed(Prefs.tryText(MainActivity.this));
                refresh();
                dlg.dismiss();
            }
        });
        dlg.show();
    }
}
