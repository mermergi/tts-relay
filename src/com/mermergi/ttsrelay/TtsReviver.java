package com.mermergi.ttsrelay;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * TTS Server 崩了之后<b>它自己不会把「转发器」重新拉起来</b> ——
 * 表现就是：进程还在（pidof 能查到），但 1221 端口死了，只能手动点开它的界面。
 *
 * 这里替它拉一把：直接 startForegroundService 它的转发器服务。
 * 该服务在 TTS Server 自己的 manifest 里是 <code>exported=true</code>，
 * 所以外部应用拉起它是合法动作，不需要 root、不需要特殊权限。
 *
 * 拉不动（被系统的后台启动限制拦了）就老实写日志，让用户手动开一下。
 */
public final class TtsReviver {

    public static final String PKG = "com.github.jing332.tts_server_android";
    public static final String FORWARDER =
            PKG + ".service.forwarder.system.SysTtsForwarderService";

    private static volatile long lastTry = 0;
    private static volatile long lastOk = 0;

    /** 记一笔"刚刚合成成功过" —— 用来判断它是"从没开"还是"刚崩"。 */
    public static void noteOk() { lastOk = System.currentTimeMillis(); }

    public static long lastOkAt() { return lastOk; }

    public static String tryRevive(Context ctx) { return revive(ctx, false); }

    /** force=true 时无视节流开关（给 /_revive 和界面按钮用）。 */
    public static String revive(Context ctx, boolean force) {
        if (!force && !Prefs.autoRevive(ctx)) return "自动重启已关";
        long now = System.currentTimeMillis();
        if (!force && now - lastTry < 60000) return "60 秒内只试一次，刚试过";
        lastTry = now;
        try {
            Intent i = new Intent();
            i.setComponent(new ComponentName(PKG, FORWARDER));
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
            else ctx.startService(i);
            LogBus.add("→ 正在拉起 TTS Server 的转发器（它崩后不会自己回来）");
            return "已发出重启请求";
        } catch (Throwable t) {
            String m = t.getClass().getSimpleName() + ": " + t.getMessage();
            LogBus.error("→ 自动拉起 TTS Server 失败：" + m + "（被系统拦住了就手动点开它一下）");
            return "失败：" + m;
        }
    }

    /**
     * 是不是"刚刚还活着、现在连不上" —— 大概率是它崩了。
     * 崩的原因里最常见的一个：某个音色/预设合成失败，
     * 它的 SystemTtsService 在没 start() 的情况下调 done()，
     * 安卓框架抛 IllegalArgumentException: Bad audio format 0，整个进程带走。
     */
    public static boolean crashedJustNow() {
        long ok = lastOk;
        return ok > 0 && System.currentTimeMillis() - ok < 120000;
    }
}
