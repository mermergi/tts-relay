package com.mermergi.ttsrelay;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;

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
                try { save(c, t, e); } catch (Throwable ignored) { }
                if (def != null) def.uncaughtException(t, e);   // 原有的默认行为照走（让系统弹"已停止"）
            }
        });
    }

    // ══════════════════════════ 落盘 ══════════════════════════

    private static File dir(android.content.Context c) {
        return new File(c.getFilesDir(), "crash");
    }

    private static void save(android.content.Context c, Thread t, Throwable e) throws Exception {
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

    /** 主界面 onCreate 里调一句：有新崩溃就弹「分享 / 忽略」（theme 走调用方的对话框主题）。 */
    public static void offerReport(final Activity act, final int dialogTheme) {
        final File newest = newest(dir(act));
        if (newest == null) return;
        new AlertDialog.Builder(act, dialogTheme)
                .setTitle("上次崩溃了")
                .setMessage("崩溃日志已存本地（" + newest.getName() + "）。\n"
                        + "分享给作者有助于修问题；忽略则留着下次再看。")
                .setPositiveButton("分享", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { share(act, newest); }
                })
                .setNegativeButton("忽略", null)
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
