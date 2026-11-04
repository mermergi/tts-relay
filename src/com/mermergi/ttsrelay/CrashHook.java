package com.mermergi.ttsrelay;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;

import com.mermergi.ttsrelay.RelayService;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

/**
 * 崩溃自捕获：没接任何 SDK（这类 app 上不了 Play Services），自己兜底。
 *
 * <ul>
 *   <li>装在 {@link App#install} 里 —— 五个进程入口（主界面 / 两个编辑页 / 服务 / 插件宿主）
 *       都会过它，谁先起来谁装上；</li>
 *   <li>崩了就把 版本号 + 线程 + 栈 + LogBus 最近的日志 写进
 *       {@code files/crash/crash-<时间>.txt}（内部存储，卸载即清，不碰权限）；</li>
 *   <li>下次主界面起来时 {@link #offerReport} 弹一句「上次崩了」，可以分享给作者，也可以划走。</li>
 * </ul>
 */
public final class CrashHook {

    private static final int KEEP = 5;                     // 最多留几份，防着把存储塞爆
    private static final SimpleDateFormat FMT =
            new SimpleDateFormat("MMdd-HHmmss", Locale.CHINA);

    private CrashHook() { }

    public static void install(final android.content.Context c) {
        final Thread.UncaughtExceptionHandler def = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    File f = save(c, t, e);
                    LogBus.add("已捕获崩溃 → " + f.getName() + "（下次启动会弹分享）");
                } catch (Throwable st) {
                    LogBus.error("崩溃落盘失败：" + st);
                }
                try { scheduleRestart(c); } catch (Throwable ignored) { }
                if (def != null) def.uncaughtException(t, e);   // 原有的默认行为照走（让系统弹"已停止"）
            }
        });
    }

    // ══════════════════════════ 自拉 ══════════════════════════

    /** 崩了不认命：3 秒后用闹钟把前台服务拉起来。 */
    private static void scheduleRestart(android.content.Context c) {
        android.app.PendingIntent pi = restartPendingIntent(c);
        if (pi == null) return;
        android.app.AlarmManager am = (android.app.AlarmManager)
                c.getSystemService(android.content.Context.ALARM_SERVICE);
        if (am == null) return;
        long at = android.os.SystemClock.elapsedRealtime() + 3000;
        try {
            // 精确闹钟在 Android 14 上才算 FGS 后台启动的豁免理由；没授权就退回 inexact 试运气
            if (android.os.Build.VERSION.SDK_INT >= 31 && am.canScheduleExactAlarms())
                am.setExactAndAllowWhileIdle(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
            else
                am.setAndAllowWhileIdle(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
        } catch (Throwable ignored) {
            try { am.set(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi); } catch (Throwable ignored2) { }
        }
    }

    /** 拉服务不拉 Activity —— 崩死后的进程属于后台，起活动会被 BAL 拦死，前台服务才有豁免。 */
    static android.app.PendingIntent restartPendingIntent(android.content.Context c) {
        try {
            android.content.Intent i = new android.content.Intent(c, RelayService.class);
            return android.app.PendingIntent.getForegroundService(c, 9901, i,
                    android.app.PendingIntent.FLAG_ONE_SHOT
                            | android.app.PendingIntent.FLAG_UPDATE_CURRENT
                            | android.app.PendingIntent.FLAG_IMMUTABLE);
        } catch (Throwable t) {
            return null;
        }
    }

    // ══════════════════════════ 落盘 ══════════════════════════

    private static File dir(android.content.Context c) {
        return new File(c.getFilesDir(), "crash");
    }

    private static File save(android.content.Context c, Thread t, Throwable e) throws Exception {
        File d = dir(c);
        d.mkdirs();
        File f = new File(d, "crash-" + FMT.format(new Date()) + ".txt");
        StringBuilder sb = new StringBuilder();
        sb.append("app: 朗读中转 ").append(versionName(c)).append('\n');
        sb.append("at:  ").append(FMT.format(new Date())).append('\n');
        sb.append("thread: ").append(t.getName()).append(" (id=").append(t.getId()).append(")\n\n");
        sb.append(LogBus.dump()).append("\n\n");
        sb.append(android.util.Log.getStackTraceString(e)).append('\n');
        FileWriter w = new FileWriter(f);
        w.write(sb.toString());
        w.close();

        // 只留最近 KEEP 份
        File[] all = d.listFiles();
        if (all != null && all.length > KEEP) {
            Arrays.sort(all);
            for (int i = 0; i < all.length - KEEP; i++) all[i].delete();
        }
        return f;
    }

    private static String versionName(android.content.Context c) {
        try {
            return c.getPackageManager()
                    .getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "?";
        }
    }

    // ══════════════════════════ 下次启动的弹窗 ══════════════════════════

    /**
     * 主界面 onCreate 里调一句：有新崩溃就弹「分享 / 忽略」（theme 走调用方的对话框主题）。
     * 忽略 / 分享过的那份记下名字（prefs），同一份不烦第二次；再出更新的崩才重新弹。
     */
    public static void offerReport(final Activity act, final int dialogTheme) {
        final File newest = newest(dir(act));
        if (newest == null) return;
        final android.content.SharedPreferences sp =
                act.getSharedPreferences("crash", android.content.Context.MODE_PRIVATE);
        if (newest.getName().equals(sp.getString("seen", null))) return;
        new AlertDialog.Builder(act, dialogTheme)
                .setTitle("上次崩溃了")
                .setMessage("崩溃日志已存本地（" + newest.getName() + "）。\n"
                        + "分享给作者有助于修问题；忽略则留着下次再看。")
                .setPositiveButton("分享", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        sp.edit().putString("seen", newest.getName()).apply();
                        share(act, newest);
                    }
                })
                .setNegativeButton("忽略", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        sp.edit().putString("seen", newest.getName()).apply();
                    }
                })
                .show();
    }

    private static File newest(File d) {
        File[] all = d == null ? null : d.listFiles();
        if (all == null || all.length == 0) return null;
        Arrays.sort(all);
        return all[all.length - 1];
    }

    private static void share(Activity act, File f) {
        try {
            // 拷到 cacheDir 再用 FileProvider 分享会引额外配置；这里直接读成文本走 ACTION_SEND
            InputStream in = new FileInputStream(f);
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) buf.write(b, 0, n);
            in.close();
            String body = buf.toString("UTF-8");
            if (body.length() > 9000) body = body.substring(0, 9000) + "\n…（已截断）";
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_SUBJECT, f.getName());
            i.putExtra(Intent.EXTRA_TEXT, body);
            act.startActivity(Intent.createChooser(i, "分享崩溃日志"));
        } catch (Throwable t) {
            LogBus.error("分享崩溃日志失败：" + t.getMessage());
        }
    }

    /** 测试用：本地造一份假崩溃（不真崩进程）。 */
    public static void fakeForTest(android.content.Context c) {
        try {
            save(c, Thread.currentThread(),
                    new IllegalStateException("这是一份测试崩溃（fakeForTest）"));
        } catch (Throwable ignored) { }
    }
}
