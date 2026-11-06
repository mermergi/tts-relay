package com.mermergi.ttsrelay;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.mermergi.ttsrelay.tab.LogTab;
import com.mermergi.ttsrelay.tab.Tab;

/**
 * 运行日志二级页（从设置进）。原日志 tab 降级而来 —— LogTab 的视图逻辑原样复用，
 * 这里只提供壳：Tab.Host 适配 + 定时刷新。
 */
public class LogActivity extends Activity implements Tab.Host {

    private LogTab logTab;
    private Ui ui;
    private Palette p;
    private final Handler tick = new Handler(Looper.getMainLooper());
    private final Runnable refresher = new Runnable() {
        public void run() {
            if (logTab != null) logTab.refresh();
            tick.postDelayed(this, 800);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        p = Palette.apply(this);
        super.onCreate(b);
        ui = new Ui(this, p);
        logTab = new LogTab(ui, p, this);
        setContentView(logTab.build(this));
    }

    @Override
    protected void onResume() {
        super.onResume();
        tick.post(refresher);
    }

    @Override
    protected void onPause() {
        super.onPause();
        tick.removeCallbacks(refresher);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (logTab != null) logTab.clearRefs();
    }

    // ── Tab.Host 适配（日志页只用得到 toast/copy/activity）──
    public void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
    public void copy(String s) {
        android.content.ClipboardManager cm = (android.content.ClipboardManager)
                getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) { cm.setPrimaryClip(android.content.ClipData.newPlainText("t", s)); toast("已复制"); }
    }
    public int dialogTheme() { return p.light ? R.style.AppDialog_Light : R.style.AppDialog; }
    public void runOnUi(Runnable r) { runOnUiThread(r); }
    public void requestRefresh() { }
    public Activity activity() { return this; }
    public android.app.Dialog sheet(android.view.View content) { return null; }
    public void dismissSheet() { }
    public int sheetTheme() { return dialogTheme(); }
}
