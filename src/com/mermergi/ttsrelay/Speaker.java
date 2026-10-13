package com.mermergi.ttsrelay;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.HandlerThread;

import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 两条线程的流水线：
 *   合成线程：文本 → TTS Server → 音频，塞进 audioQ（失败会重试一次）
 *   播放线程：从 audioQ 取音频，MediaPlayer 播完自动接下一句
 * 合成第 N+1 句时第 N 句正在播 —— 句与句之间不留空档。
 */
public final class Speaker {

    private static final class Clip {
        final String text;
        final byte[] data;
        final int gen;

        Clip(String t, byte[] d, int g) { text = t; data = d; gen = g; }
    }

    private final Context ctx;
    private final LinkedBlockingQueue<String> textQ = new LinkedBlockingQueue<String>();
    private final ArrayBlockingQueue<Clip> audioQ = new ArrayBlockingQueue<Clip>(4);

    private volatile int gen = 0;
    private volatile boolean running = false;

    private HandlerThread synthT;
    private HandlerThread playT;
    private Handler playH;
    private MediaPlayer mp;
    private int seq = 0;

    private final AtomicInteger spoken = new AtomicInteger();
    private final AtomicInteger chars = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();
    private volatile String last = "";
    private volatile long lastTtsHint = 0;

    /** 播放看门狗用：这一句什么时候开始播的、音频本身多长。 */
    private volatile long playStart = 0;
    private volatile int playDur = 0;
    private volatile int stuckCount = 0;

    public Speaker(Context c) { ctx = c.getApplicationContext(); }

    public int spokenCount() { return spoken.get(); }
    public int charCount() { return chars.get(); }
    public int failedCount() { return failed.get(); }
    public int stuckCount() { return stuckCount; }
    public String lastText() { return last; }
    public int queued() { return textQ.size() + audioQ.size(); }

    public void start() {
        if (running) return;
        running = true;
        cleanClips();
        synthT = new HandlerThread("tts-synth");
        synthT.start();
        playT = new HandlerThread("tts-play");
        playT.start();
        playH = new Handler(playT.getLooper());
        new Thread(new Runnable() {
            public void run() { synthLoop(); }
        }, "tts-synth-worker").start();
        playH.post(playNext);
        playH.postDelayed(stuckCheck, 1500);
        logVolume();
        LogBus.add("发声器已就绪（音色：" + Prefs.voice(ctx).replaceAll("_\\d+$", "") + "）");
    }

    private void logVolume() {
        try {
            android.media.AudioManager am =
                    (android.media.AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            int v = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
            int max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
            if (v == 0) {
                LogBus.error("媒体音量是 0 —— 这就是没声音的原因，按一下音量键调大");
            } else {
                LogBus.add("媒体音量 " + v + "/" + max);
            }
        } catch (Throwable ignored) { }
    }

    public void stop() {
        running = false;
        interrupt();
        if (synthT != null) synthT.quitSafely();
        if (playT != null) playT.quitSafely();
        synthT = null;
        playT = null;
        LogBus.add("发声器已停止");
    }

    /** 新的一轮来了 / 用户点了闭嘴：掐掉没念完的。 */
    public void interrupt() {
        gen++;
        textQ.clear();
        audioQ.clear();
        Handler h = playH;
        if (h != null) {
            h.post(new Runnable() {
                public void run() {
                    releasePlayer();
                    playStart = 0;
                    playDur = 0;
                    // 关键：掐完之后必须重新上膛。
                    // 否则 onCompletion 永远不会来，播放循环就断了 —— 表现就是"合成还在跑，但没声音"。
                    Handler hh = playH;
                    if (hh != null) {
                        hh.removeCallbacks(playNext);
                        hh.postDelayed(playNext, 50);
                    }
                }
            });
        }
    }

    public void feed(String text) {
        if (text == null || text.length() == 0) return;
        if (Prefs.muted(ctx)) return;
        textQ.offer(text);
    }

    private void cleanClips() {
        try {
            File[] fs = ctx.getCacheDir().listFiles();
            if (fs == null) return;
            for (File f : fs) {
                if (f.getName().startsWith("clip_")) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
        } catch (Throwable ignored) { }
    }

    // ───────────────────────── 合成 ─────────────────────────
    private void synthLoop() {
        while (running) {
            String text;
            try {
                text = textQ.poll(400, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                return;
            }
            if (text == null) continue;
            int my = gen;
            if (Prefs.muted(ctx)) continue;

            byte[] data = null;
            String err = null;
            for (int attempt = 0; attempt < 2; attempt++) {
                if (!running || my != gen) break;
                try {
                    data = TtsClient.synth(ctx, text);
                    break;
                } catch (Throwable t) {
                    err = t.getClass().getSimpleName() + ": " + t.getMessage();
                    if (attempt == 0) {
                        try {
                            Thread.sleep(400);
                        } catch (InterruptedException ie) {
                            return;
                        }
                    }
                }
            }
            if (data == null) {
                if (err != null && my == gen) {
                    failed.incrementAndGet();
                    LogBus.error("合成失败：" + err + "   ← 文本：" + head(text, 20));
                    ttsDownHint(err);
                }
                continue;
            }
            if (my != gen) continue;
            try {
                while (running && my == gen) {
                    if (audioQ.offer(new Clip(text, data, my), 200, TimeUnit.MILLISECONDS)) break;
                }
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void ttsDownHint(String err) {
        if (!(err.contains("Failed to connect") || err.contains("ECONNREFUSED")
                || err.contains("Connection refused") || err.contains("TTS HTTP"))) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastTtsHint > 20000) {
            lastTtsHint = now;
            LogBus.add("→ TTS Server 没在正常工作：打开 TTS Server，"
                    + "「转发器」页确认服务已启动、监听端口 " + portOf(Prefs.ttsBase(ctx)));
        }
    }

    private static String portOf(String base) {
        try {
            java.net.URL u = new java.net.URL(base);
            return String.valueOf(u.getPort() > 0 ? u.getPort() : 80);
        } catch (Throwable t) {
            return "?";
        }
    }

    // ───────────────────────── 播放 ─────────────────────────
    private final Runnable playNext = new Runnable() {
        public void run() {
            if (!running) return;
            // 上一句还没播完，谁也别想插队 —— 保证一句是一句，绝不腰斩
            if (mp != null) return;
            Clip clip = audioQ.poll();
            if (clip == null) {
                playH.postDelayed(this, 70);
                return;
            }
            if (clip.gen != gen) {
                playH.post(this);
                return;
            }
            try {
                playOne(clip);
            } catch (Throwable t) {
                failed.incrementAndGet();
                LogBus.error("播放失败：" + t.getClass().getSimpleName() + " " + t.getMessage());
                releasePlayer();
                playH.post(this);
            }
        }
    };

    private void playOne(final Clip clip) throws Exception {
        final File f = new File(ctx.getCacheDir(), "clip_" + System.nanoTime() + ".wav");
        releasePlayer();                       // 先放掉上一个，再落盘，避免互相踩
        FileOutputStream fos = new FileOutputStream(f);
        try {
            fos.write(clip.data);
        } finally {
            fos.close();
        }

        MediaPlayer p = new MediaPlayer();
        try {
            p.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            p.setDataSource(f.getAbsolutePath());
            p.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                public void onCompletion(MediaPlayer m) {
                    // 关键：绝不在回调里直接 release —— 换一轮循环再收尾，躲开原生层自锁
                    postAfter(f, null);
                }
            });
            p.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                public boolean onError(MediaPlayer m, int what, int extra) {
                    postAfter(f, "MediaPlayer 报错 what=" + what + " extra=" + extra);
                    return true;
                }
            });
            p.prepare();
            int dur = p.getDuration();
            if (dur <= 0) throw new IllegalStateException("音频时长为 " + dur + "ms（文件是坏的）");
            playDur = dur;
            playStart = System.currentTimeMillis();
            p.start();
        } catch (Throwable t) {
            try { p.reset(); } catch (Throwable ignored) { }
            try { p.release(); } catch (Throwable ignored) { }
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            throw new IllegalStateException("MediaPlayer 起播失败：" + t.getClass().getSimpleName()
                    + " " + t.getMessage() + "（音频 " + clip.data.length + " 字节）");
        }
        mp = p;
        spoken.incrementAndGet();
        chars.addAndGet(clip.text.length());
        last = clip.text;
        LogBus.add("🔊 " + head(clip.text, 42) + "  (" + playDur + "ms)");
    }

    /** 收尾统一走这里：先释放、再取下一条，全在播放线程上排队。 */
    private void postAfter(final File f, final String err) {
        Handler h = playH;
        if (h == null) return;
        h.post(new Runnable() {
            public void run() {
                if (err != null) LogBus.error(err);
                releasePlayer();
                //noinspection ResultOfMethodCallIgnored
                f.delete();
                playStart = 0;
                playDur = 0;
                playNext.run();
            }
        });
    }

    /** 看门狗：音频早该播完了却还挂着 —— 判定卡死，强行推进。 */
    private final Runnable stuckCheck = new Runnable() {
        public void run() {
            try {
                MediaPlayer p = mp;
                int dur = playDur;
                if (p != null && dur > 0 && playStart > 0) {
                    long el = System.currentTimeMillis() - playStart;
                    boolean overrun = el > dur + 3000L;
                    boolean silentPause = false;
                    try {
                        // 音频焦点被抢会静默暂停，onCompletion 永远不来 —— 最阴的一种卡死
                        silentPause = el > 2500L && !p.isPlaying();
                    } catch (Throwable ignored) { }
                    if (overrun || silentPause) {
                        stuckCount++;
                        LogBus.error("播放卡死（" + (silentPause ? "被静默暂停" : "超时")
                                + "，已挂 " + el + "ms，音频只有 " + dur + "ms），强制跳到下一句");
                        releasePlayer();
                        playStart = 0;
                        playDur = 0;
                        playNext.run();
                    }
                } else if (p == null && !audioQ.isEmpty()) {
                    // 队列里有音频却没人播 = 播放循环掉了，踹一脚
                    stuckCount++;
                    LogBus.error("播放循环停了（队列还剩 " + audioQ.size() + " 条），已重新拉起");
                    playNext.run();
                }
            } catch (Throwable ignored) { }
            Handler h = playH;
            if (h != null && running) h.postDelayed(this, 1000);
        }
    };

    private static String head(String s, int n) {
        return s.length() > n ? s.substring(0, n) + "…" : s;
    }

    private void releasePlayer() {
        MediaPlayer p = mp;
        mp = null;
        if (p == null) return;
        try { if (p.isPlaying()) p.stop(); } catch (Throwable ignored) { }
        try { p.reset(); } catch (Throwable ignored) { }
        try { p.release(); } catch (Throwable ignored) { }
    }
}
