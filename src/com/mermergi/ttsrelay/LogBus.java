package com.mermergi.ttsrelay;

import android.content.Context;

import java.io.File;
import java.io.FileWriter;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/** 内存日志环 + 落盘 + 订阅。重启后还能看到上一次的错误。 */
public final class LogBus {
    private static final int CAP = 600;
    private static final long MAX_BYTES = 256 * 1024;
    private static final ArrayDeque<String> LINES = new ArrayDeque<>();
    private static final List<Sink> SINKS = new CopyOnWriteArrayList<>();
    private static final SimpleDateFormat FMT = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US);

    private static volatile File FILE;
    private static volatile String lastError = "";

    public interface Sink {
        void onLog(String line);
    }

    /** 服务启动时调一次：接上日志文件，并把上次的尾巴读回来。 */
    public static void init(Context c) {
        try {
            File f = new File(c.getApplicationContext().getFilesDir(), "relay.log");
            FILE = f;
            if (!f.exists()) return;
            long len = f.length();
            int want = (int) Math.min(len, 32 * 1024);
            if (want <= 0) return;
            byte[] buf = new byte[want];
            RandomAccessFile raf = new RandomAccessFile(f, "r");
            try {
                raf.seek(len - want);
                raf.readFully(buf);
            } finally {
                raf.close();
            }
            String[] lines = new String(buf, "UTF-8").split("\n");
            int start = (want < len) ? 1 : 0;   // 掐掉可能被截断的第一行
            for (int i = start; i < lines.length; i++) {
                String l = lines[i];
                if (l.length() == 0) continue;
                LINES.addLast(l);
                while (LINES.size() > CAP) LINES.removeFirst();
            }
        } catch (Throwable ignored) { }
    }

    public static synchronized void add(String msg) {
        String line = "[" + FMT.format(new Date()) + "] " + msg;
        LINES.addLast(line);
        while (LINES.size() > CAP) LINES.removeFirst();
        File f = FILE;
        if (f != null) {
            try {
                if (f.length() > MAX_BYTES) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
                FileWriter w = new FileWriter(f, true);
                try {
                    w.write(line + "\n");
                } finally {
                    w.close();
                }
            } catch (Throwable ignored) { }
        }
        for (Sink s : SINKS) {
            try { s.onLog(line); } catch (Throwable ignored) { }
        }
    }

    public static void error(String msg) {
        lastError = msg;
        add("❌ " + msg);
    }

    public static String lastError() { return lastError; }

    public static void addSink(Sink s) { SINKS.add(s); }

    public static synchronized String dump() {
        StringBuilder sb = new StringBuilder();
        for (String l : LINES) sb.append(l).append('\n');
        return sb.toString();
    }

    public static synchronized void clear() {
        LINES.clear();
        File f = FILE;
        if (f != null) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }
}
