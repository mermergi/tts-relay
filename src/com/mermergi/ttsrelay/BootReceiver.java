package com.mermergi.ttsrelay;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * 开机自启。
 * 只满足两个条件才动手：用户开了「开机自启」，而且**上次关机的状态是运行中**。
 * 否则你明明关了它，重启却又自己站起来，那叫流氓软件。
 */
public final class BootReceiver extends BroadcastReceiver {

    public void onReceive(Context c, Intent i) {
        LogBus.init(c);
        String a = i == null ? "" : String.valueOf(i.getAction());
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(a)) {
            return;
        }
        if (!Prefs.autoStart(c)) return;
        if (!Prefs.wanted(c)) {
            LogBus.add("开机了，但上次是关闭状态 —— 不自启");
            return;
        }
        try {
            Intent s = new Intent(c, RelayService.class);
            s.setAction(RelayService.ACTION_START);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(s);
            else c.startService(s);
            LogBus.add("开机自启：中转已拉起");
        } catch (Throwable t) {
            LogBus.add("开机自启失败：" + t.getMessage());
        }
    }
}
