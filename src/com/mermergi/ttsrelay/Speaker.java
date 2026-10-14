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
    /**
     * TTS Server 连不上时的熔断：别一句一句猛敲。
     * 它崩了是"整台机器"的事，敲再快也只是刷一屏 ConnectException，
     * 还把句子一条条浪费掉 —— 这里改成退避重试，<b>失败的句子不丢</b>。
     */
    private volatile long nextTryAt = 0;
    private volatile int failStreak = 0;

    /**
     * 自适应节奏（毫秒）：给上游留的最小请求间隔。
     * 反代/网页版引擎被短时间连击容易触发风控，但健康时又不该白白加延迟 ——
     * 所以平时是 0；一旦出现"限流样"的失败就自动翻倍放慢，连续成功再自动收回。
     */
    private volatile long paceMs = 0;
    private volatile long lastSynthAt = 0;
    private int curTries = 0;

    public long pace() { return paceMs; }

    private void synthLoop() {
        String cur = null;
        while (running) {
            if (cur == null) {
                try {
                    cur = textQ.poll(400, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    return;
                }
                if (cur == null) continue;
                curTries = 0;
            }
            int my = gen;
            if (Prefs.muted(ctx)) { cur = null; continue; }
            if (my != gen) { cur = null; continue; }   // 被打断了，这条不要了

            long wait = nextTryAt - System.currentTimeMillis();
            if (wait > 0) {
                try {
                    Thread.sleep(Math.min(wait, 1000));
                } catch (InterruptedException ie) {
                    return;
                }
                continue;                              // cur 还留着，等熔断结束重试
            }

            long need = paceMs - (System.currentTimeMillis() - lastSynthAt);
            if (need > 0) {
                try {
                    Thread.sleep(Math.min(need, 3000));
                } catch (InterruptedException ie) {
                    return;
                }
                continue;                              // cur 留着，等节奏
            }

            // 只发一发。
            // 原来失败后 400ms 立刻补一发 —— 对"网页版反代"这类引擎是毒药：
            // 它自己内部已经在重试好几轮，我们再补刀只会更快触发风控。
            // 失败由外层统一处理：留着这一句 + 退避重试。
            byte[] data = null;
            String err = null;
            try {
                lastSynthAt = System.currentTimeMillis();
                data = TtsClient.synth(ctx, text_safe(cur));
            } catch (Throwable t) {
                err = t.getClass().getSimpleName() + ": " + t.getMessage();
            }
            if (data == null) {
                if (err != null && my == gen) {
                    boolean conn = isConnErr(err);
                    boolean busy = isTimeout(err);   // 超时：它多半还在忙上一条
                    failed.incrementAndGet();
                    LogBus.error("合成失败：" + err + "   ← 文本：" + head(cur, 20));
                    boolean throttle = isThrottle(err);
                    if (conn || busy) {
                        failStreak++;
                        long back = Math.min(30000L, (busy ? 10000L : 2000L)
                                << Math.min(failStreak - 1, busy ? 1 : 4));
                        nextTryAt = System.currentTimeMillis() + back;
                        LogBus.add("⏸ 退避 " + (back / 1000) + " 秒（"
                                + (busy ? "上一发还没返回，别再往上堆了" : "连续 " + failStreak + " 次连不上")
                                + "，这一句留着不丢）");
                        if (!busy) TtsReviver.tryRevive(ctx);
                    } else if (throttle && curTries < 3) {
                        // 引擎回了但不是音频（反代被风控/限流的典型样子）：放慢 + 留着重试
                        curTries++;
                        failStreak = 0;
                        paceMs = Math.min(3000L, paceMs == 0 ? 500L : paceMs * 2);
                        long back = Math.min(30000L, 5000L << Math.min(curTries - 1, 2));
                        nextTryAt = System.currentTimeMillis() + back;
                        LogBus.add("⏸ 退避 " + (back / 1000) + " 秒（引擎回的不是音频，"
                                + "像是被限流/风控了；节奏放到 " + paceMs + "ms，第 " + curTries
                                + " 次重试，这一句留着）");
                    } else {
                        failStreak = 0;
                        cur = null;                    // 救不回来了
                        curTries = 0;
                    }
                    ttsDownHint(err);
                } else {
                    cur = null;
                }
                continue;                                  // cur 在熔断分支里被保留
            }
            failStreak = 0;
            nextTryAt = 0;
            curTries = 0;
            if (paceMs > 0) {                          // 连续成功 → 把节奏收回去
                paceMs = paceMs / 2;
                if (paceMs < 200) paceMs = 0;
            }
            TtsReviver.noteOk();
            if (my != gen) { cur = null; continue; }
            try {
                while (running && my == gen) {
                    if (audioQ.offer(new Clip(cur, data, my), 200, TimeUnit.MILLISECONDS)) break;
                }
            } catch (InterruptedException e) {
                return;
            }
            cur = null;
        }
    }

    private static String text_safe(String t) { return t == null ? "" : t; }

    /** 回了、但回的不是音频 / 直接 4xx-5xx —— 反代被限流、风控、会话过期都长这样。 */
    private static boolean isThrottle(String err) {
        if (err == null) return false;
        return err.contains("不是音频") || err.contains("TTS HTTP 429")
                || err.contains("TTS HTTP 403") || err.contains("TTS HTTP 5")
                || err.contains("TTS HTTP 400");
    }

    /** 超时（而不是连不上）：说明请求已经送达、对方还在忙 —— 这时再发就是纯堆积。 */
    private static boolean isTimeout(String err) {
        if (err == null) return false;
        return err.contains("SocketTimeout") || err.contains("timeout")
                || err.contains("Timeout") || err.contains("timed out");
    }

    private static boolean isConnErr(String err) {
        if (err == null) return false;
        return err.contains("Failed to connect") || err.contains("ECONNREFUSED")
                || err.contains("Connection refused") || err.contains("ConnectException");
    }

    private void ttsDownHint(String err) {
        if (!(err.contains("Failed to connect") || err.contains("ECONNREFUSED")
                || err.contains("Connection refused") || err.contains("TTS HTTP"))) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastTtsHint > 20000) {
            lastTtsHint = now;
            String port = portOf(Prefs.ttsBase(ctx));
            if (TtsReviver.crashedJustNow()) {
                LogBus.add("→ TTS Server 刚刚崩了（上一句还念得好好的）。常见原因：某个音色合成失败，"
                        + "它内部抛 Bad audio format 0 把整个进程带走 —— 换个音色试试。"
                        + "已尝试自动拉起转发器，无效就手动点开 TTS Server 看端口 " + port);
            } else {
                LogBus.add("→ TTS Server 没在正常工作：打开 TTS Server，"
                        + "「转发器」页确认服务已启动、监听端口 " + port);
            }
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
