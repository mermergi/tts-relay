package com.mermergi.ttsrelay;

import com.mermergi.ttsrelay.js.AudioSink;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 两条线程的流水线：
 *   合成线程：文本 → 引擎 → 音频，塞进 audioQ（失败会重试）
 *   播放线程：从 audioQ 取音频，MediaPlayer 播完自动接下一句
 * 合成第 N+1 句时第 N 句正在播 —— 句与句之间不留空档。
 *
 * <p><b>第三条线程：看门狗（tts-watch）。</b>2026-10 真机事故：念三句之后
 * 彻底没声，日志一个字没有。原因是播放线程卡在 MediaPlayer 的原生调用里
 * （prepare/release 这类），而**看门狗原来跑在播放线程自己身上** ——
 * 线程一卡，看门狗跟着一起死，谁也发现不了。
 *
 * <p>现在：看门狗是**独立线程**，只认"心跳"。哪一段不跳了就写日志 + 换新线程，
 * 卡住的那条连同它抱着的原生对象一起丢掉（换不回就当弃线程）。
 * 合成那段另外再加一层硬超时（{@link HardCall}），插件吊死也只是丢一句。
 */
public final class Speaker {

    private static final class Clip {
        final String text;
        final byte[] data;
        final int gen;
        final String voice;
        /** 非空 = 边下边播：这个地址是一条还在往里写的流（LiveAudio）。 */
        final String url;
        final LiveAudio live;

        Clip(String t, byte[] d, int g, String v) { this(t, d, g, v, null, null); }

        Clip(String t, byte[] d, int g, String v, String u, LiveAudio la) {
            text = t; data = d; gen = g; voice = v; url = u; live = la;
        }
    }

    /** 合成任务：一句话 + 它该用的音色（多语音时每句可能不同）。 */
    private static final class Task {
        final String text;
        final String voice;

        Task(String t, String v) { text = t; voice = v; }
    }

    private final Context ctx;
    private final LinkedBlockingQueue<Task> textQ = new LinkedBlockingQueue<Task>();
    private final ArrayBlockingQueue<Clip> audioQ = new ArrayBlockingQueue<Clip>(4);

    private volatile int gen = 0;
    private volatile boolean running = false;
    /** 暂停只停"播" —— 合成照跑，继续时立刻有声，不用等下一句合出来。 */
    private volatile boolean paused = false;
    private volatile float volume = 1f;

    // ── 播放那一段 ──
    private HandlerThread playT;
    private Handler playH;
    private MediaPlayer mp;
    private int seq = 0;
    /** 播放线程的心跳：每跑一次 playNext 就更新。卡住 = 心跳停了。 */
    private volatile long playBeat = 0;
    private volatile String playStage = "未启动";
    private volatile long playStart = 0;
    private volatile int playDur = 0;
    private volatile int stuckCount = 0;

    // ── 合成那一段 ──
    private volatile Thread synthThread;
    /** 合成线程的"代"：换线程时 +1，老线程回来一看代不对就自己退场。 */
    private volatile int synthEpoch = 0;
    private volatile boolean synthBusy = false;
    private volatile long synthBeat = 0;
    private volatile String synthStage = "未启动";
    /** 当前这一句最多等多久（硬超时用），看门狗按它判断合成是不是真吊住了。 */
    private volatile long synthWait = 30000;

    // ── 看门狗 ──
    private volatile Thread watchThread;
    private volatile boolean watching = false;

    private final AtomicInteger spoken = new AtomicInteger();
    private final AtomicInteger chars = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();
    private volatile String last = "";
    private volatile long lastTtsHint = 0;

    public Speaker(Context c) { ctx = c.getApplicationContext(); }

    public int spokenCount() { return spoken.get(); }
    public int charCount() { return chars.get(); }
    public int failedCount() { return failed.get(); }
    public int stuckCount() { return stuckCount; }
    public String lastText() { return last; }
    public int queued() { return textQ.size() + audioQ.size(); }
    public boolean isPaused() { return paused; }
    public float volume() { return volume; }

    /** 音量 0~1：立刻作用于正在播的那句，并记住给后面每一句。 */
    public void setVolume(float v) {
        volume = Math.max(0f, Math.min(1f, v));
        final Handler h = playH;
        if (h != null) {
            h.post(new Runnable() {
                public void run() {
                    MediaPlayer p = mp;
                    if (p == null) return;
                    try { p.setVolume(volume, volume); } catch (Throwable ignored) { }
                }
            });
        }
    }

    /** 暂停播放（合成继续排队，队列满了合成线程自然等）。 */
    public void pause() {
        if (!running || paused) return;
        paused = true;
        Handler h = playH;
        if (h != null) {
            h.post(new Runnable() {
                public void run() {
                    beat("已暂停");
                    MediaPlayer p = mp;
                    if (p == null) return;
                    try { if (p.isPlaying()) p.pause(); } catch (Throwable ignored) { }
                }
            });
        }
        LogBus.add("⏸ 朗读已暂停");
    }

    public void resume() {
        if (!running || !paused) return;
        paused = false;
        Handler h = playH;
        if (h != null) {
            h.post(new Runnable() {
                public void run() {
                    beat("继续");
                    MediaPlayer p = mp;
                    if (p == null) return;
                    try { p.start(); } catch (Throwable ignored) { }
                }
            });
        }
        LogBus.add("▶ 朗读已继续");
    }

    /**
     * 跳过当前这句，但**不清队列** —— 这是和 {@link #interrupt()} 的区别：
     * interrupt 是"新的一轮来了，全清"；skip 是"这句不想听，后面的照听"。
     */
    public void skipCurrent() {
        final Handler h = playH;
        if (h == null) return;
        h.post(new Runnable() {
            public void run() {
                releasePlayer();
                playStart = 0;
                playDur = 0;
                Handler hh = playH;
                if (hh != null) {
                    hh.removeCallbacks(playNext);
                    hh.postDelayed(playNext, 30);
                }
            }
        });
        LogBus.add("⏭ 已跳过当前句");
    }

    public void start() {
        if (running) return;
        running = true;
        paused = false;
        volume = Prefs.volume(ctx);
        cleanClips();
        gen++;                       // 新会话：上一代的残留音频一律作废
        playT = new HandlerThread("tts-play");
        playT.start();
        playH = new Handler(playT.getLooper());
        playBeat = SystemClock.uptimeMillis();
        playStage = "起播";
        tickOn = false;
        playH.post(playNext);
        startSynthWorker();
        watching = true;
        Thread w = new Thread(new Runnable() {
            public void run() { watchLoop(); }
        }, "tts-watch");
        w.setDaemon(true);
        watchThread = w;
        w.start();
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
        watching = false;
        Thread w = watchThread;
        watchThread = null;
        if (w != null) w.interrupt();
        interrupt();
        if (playT != null) playT.quitSafely();
        playT = null;
        Thread s = synthThread;
        if (s != null) s.interrupt();
        synthThread = null;
        LogBus.add("发声器已停止");
    }

    /** 新的一轮来了 / 用户点了闭嘴：掐掉没念完的。 */
    public void interrupt() {
        gen++;
        int dropped = textQ.size() + audioQ.size();
        textQ.clear();
        for (Clip c : audioQ) if (c.live != null) c.live.fail("被打断");
        audioQ.clear();
        // 打断会把"上一段还没念的"直接丢掉 —— 这是设计。但要看得出丢了几句，
        // 否则"队列里还剩三句却不念了"就分不清是打断丢的、还是工作线程卡住了。
        if (dropped > 0) LogBus.add("⏭ 被打断，丢掉上一段没念的 " + dropped + " 句（队列清空）");
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
                        beat("打断后续播");
                        hh.postDelayed(playNext, 50);
                    }
                }
            });
        }
    }

    public void feed(String text) { feed(text, null); }

    /** @param voice 这一句单独用的音色（多语音）；null / 空 = 用全局音色。 */
    public void feed(String text, String voice) {
        if (text == null || text.length() == 0) return;
        if (Prefs.muted(ctx)) return;
        textQ.offer(new Task(text, voice));
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

    // ───────────────────────── 心跳 / 看门狗 ─────────────────────────

    /** 播放线程：我还活着，现在在干这个。 */
    private void beat(String stage) {
        playStage = stage;
        playBeat = SystemClock.uptimeMillis();
    }

    /** 合成线程：我还活着，现在在干这个。 */
    private void synthBeat(String stage) {
        synthStage = stage;
        synthBeat = SystemClock.uptimeMillis();
    }

    /**
     * 独立看门狗：**不跑在播放线程上** —— 这正是这次事故的关键。
     * 谁的心跳停了就写清楚"卡在哪一步"，然后把那一段换成新线程。
     */
    private void watchLoop() {
        while (watching && running) {
            try {
                Thread.sleep(700);
            } catch (InterruptedException e) {
                return;
            }
            if (!watching || !running) return;
            long now = SystemClock.uptimeMillis();

            boolean lead = mp != null || !audioQ.isEmpty();
            long synthGrace = (lead ? Math.max(synthWait * 3, 120000L) : synthWait) + 15000L;
            if (synthBusy && now - synthBeat > synthGrace) {
                LogBus.error("🩺 合成线程 " + ((now - synthBeat) / 1000) + " 秒没动"
                        + "（卡在" + synthStage + "，这句最多该等 " + (synthWait / 1000) + " 秒），"
                        + "换新合成线程，这一句丢掉");
                startSynthWorker();
            }

            if (now - playBeat > 6000L) {
                LogBus.error("🩺 播放线程 " + ((now - playBeat) / 1000) + " 秒没动"
                        + "（卡在" + playStage + " —— 十有八九是 MediaPlayer 的原生调用没返回），"
                        + "已换一条新播放线程接着念");
                rebuildPlayThread();
            }
        }
    }

    /** 播放线程卡住时换一条 —— 卡住的那条（和它抱着的 MediaPlayer）一起丢掉。 */
    private void rebuildPlayThread() {
        HandlerThread old = playT;
        HandlerThread nt = new HandlerThread("tts-play-re");
        nt.start();
        Handler nh = new Handler(nt.getLooper());
        playT = nt;
        playH = nh;
        // 卡住的那个播放器对象不能再碰：碰它的调用会继续卡在原生层里。
        mp = null;
        playStart = 0;
        playDur = 0;
        tickOn = false;          // 旧那条看护链跟着旧 looper 一起没了
        stuckCount++;
        playBeat = SystemClock.uptimeMillis();
        playStage = "换新线程";
        if (old != null) {
            try { old.quitSafely(); } catch (Throwable ignored) { }
        }
        nh.post(playNext);
    }

    private void startSynthWorker() {
        final int ep = ++synthEpoch;
        synthBusy = false;
        synthBeat("等句子");
        Thread t = new Thread(new Runnable() {
            public void run() { synthLoop(ep); }
        }, "tts-synth-worker");
        t.setDaemon(true);
        synthThread = t;
        t.start();
    }

    // ───────────────────────── 合成 ─────────────────────────
    /**
     * 引擎连不上时的熔断：别一句一句猛敲。
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
    /** 这一句已经失败几次 —— 用来判断它是不是"毒句"。 */
    private int curFails = 0;
    /** 这一句因为"引擎吊住"被硬超时过几次（第一次是换宿主重试，第二次才算真失败）。 */
    private int curTimeouts = 0;

    /** 插件合成"还在不在动" + "现在急不急"看这个。 */
    private final HardCall.Clock pluginClock = new HardCall.Clock() {
        public long lastActivity() { return com.mermergi.ttsrelay.js.PluginBridge.getLastActivity(); }

        /** 还有声音在播（且不是马上播完）/ 队列里还排着 → 这一句是提前合的，慢慢等。 */
        public boolean hasLead() {
            MediaPlayer p = mp;
            if (p != null) {
                if (playStart <= 0) return true;                  // 正在 prepare，也算有声
                long left = playDur - (SystemClock.uptimeMillis() - playStart);
                if (left > 4000L) return true;                    // 还够听一会儿
            }
            return !audioQ.isEmpty();
        }
    };

    public long pace() { return paceMs; }

    private void synthLoop(final int ep) {
        Task cur = null;
        while (running && ep == synthEpoch) {
            if (cur == null) {
                synthBeat("等句子");
                try {
                    cur = textQ.poll(400, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    return;
                }
                if (cur == null) continue;
                curTries = 0;
                curFails = 0;
                curTimeouts = 0;
            }
            int my = gen;
            if (Prefs.muted(ctx)) { cur = null; continue; }
            if (my != gen) { cur = null; continue; }   // 被打断了，这条不要了

            long wait = nextTryAt - System.currentTimeMillis();
            if (wait > 0) {
                synthBeat("熔断退避 " + (wait / 1000) + " 秒");
                try {
                    Thread.sleep(Math.min(wait, 1000));
                } catch (InterruptedException ie) {
                    return;
                }
                continue;                              // cur 还留着，等熔断结束重试
            }

            long need = paceMs - (System.currentTimeMillis() - lastSynthAt);
            if (need > 0) {
                synthBeat("等节奏 " + need + "ms");
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
            boolean timedOut = false;
            LiveAudio live = null;
            boolean streaming = EngineStore.isJsVoice(ctx, cur.voice);   // 插件走边下边播
            boolean streamed = false;                                    // 这一句已经交给播放线程了
            final Task job = cur;
            // 句子越长，TTS 那边本来就越久（64 字能吐出 18 秒音频）。总时限按字数放宽，
            // 但真正的判据是"有没有动静"：插件还在吐字节就一直等，见 HardCall.runIdle。
            synthWait = Math.max(15000L, Math.min(90000L, 6000L + 200L * cur.text.length()));
            synthBusy = true;
            synthBeat("引擎合成 " + head(cur.text, 12) + "（最多等 " + (synthWait / 1000) + " 秒）");
            long t0 = System.currentTimeMillis();
            try {
                lastSynthAt = System.currentTimeMillis();
                final HardCall.Job<byte[]> job2 = new HardCall.Job<byte[]>() {
                    public byte[] run() throws Throwable {
                        return EngineStore.synthNow(ctx, text_safe(job.text), job.voice);
                    }
                };
                if (streaming) {
                    // 边下边播：先把这条"还在下载的流"塞进播放队列，播放线程立刻起播；
                    // 合成线程接着把插件吐的字节往里泵，整句下完这边才算完。
                    com.mermergi.ttsrelay.js.PluginBridge.touch();   // 起跑线
                    final LiveAudio la = LiveAudio.create();
                    live = la;
                    Clip liveClip = new Clip(cur.text, null, my, cur.voice, la.url(), la);
                    // 队列满了要**等**，不能塞不进就把这句丢了 —— 合成比播放快得多，
                    // 队列（4 格）一会儿就满，那样每轮第 5 句往后全没了（真机踩过）。
                    // 等的过程中要是这一轮被丢掉了，就把这条流撤掉、这句不要了。
                    boolean qued = false;
                    try {
                        while (running && my == gen && ep == synthEpoch) {
                            if (audioQ.offer(liveClip, 200, TimeUnit.MILLISECONDS)) { qued = true; break; }
                        }
                    } catch (InterruptedException ie) {
                        LiveAudio.drop(la);
                        return;
                    }
                    if (!qued) {
                        LiveAudio.drop(la);
                        live = null;
                        cur = null;
                        continue;                 // 这一轮已经作废，不是失败
                    }
                    beat("起播流");
                    Handler hp = playH;
                    if (hp != null) hp.post(playNext);       // 队列里刚有东西：叫醒播放线程
                    HardCall.runIdle("合成", 8000L, 90000L, synthWait, Math.max(synthWait * 3, 120000L),
                            pluginClock, new HardCall.Job<byte[]>() {
                        public byte[] run() throws Throwable {
                            EngineStore.synthTo(ctx, text_safe(job.text), job.voice, new AudioSink() {
                                public void chunk(byte[] b, int o, int l) { la.chunk(b, o, l); }
                            });
                            return null;
                        }
                    });
                    la.finish();                             // 下完了：让播放端读到流尾
                    streamed = true;
                } else {
                    data = HardCall.run("合成", synthWait, job2);
                }
            } catch (HardCall.TimedOut te) {
                timedOut = true;
                err = te.getMessage();
                if (live != null) {
                    err = err + "（这句已经收到 " + live.available() + " 字节）";
                }
            } catch (Throwable t) {
                err = t.getClass().getSimpleName() + ": " + t.getMessage();
            } finally {
                synthBusy = false;
                if (live != null && !streamed) live.fail(err == null ? "合成失败" : err);
            }
            // 边下边播：这一句已经交给播放线程了，成不成看那边播得怎么样
            if (streamed) {
                long cost = System.currentTimeMillis() - t0;
                EngineStore.noteSynthOk(cur.voice, cost);
                if (cost > 8000L) {
                    LogBus.add("🐢 这句下载花了 " + (cost / 1000) + " 秒（文本 "
                            + cur.text.length() + " 字 · 边下边播）");
                }
                cur = null;
                continue;
            }
            if (data != null && data.length > 0) {
                long cost = System.currentTimeMillis() - t0;
                EngineStore.noteSynthOk(cur.voice, cost);
                if (cost > 8000L) {
                    LogBus.add("🐢 这句合成花了 " + (cost / 1000) + " 秒（文本 "
                            + cur.text.length() + " 字 · 音频 " + (data.length / 1024) + "KB）");
                }
            }
            if (data == null) {
                if (timedOut) {
                    // 这不是"慢"，是"吊住了"（宿主里的 WebSocket 会话废了）。
                    // 丢掉那个宿主，立刻用新宿主重试同一句 —— 既不用等第二次超时，
                    // 也不至于平白少念一句。
                    EngineStore.abandonPluginHosts();
                    if (curTimeouts == 0) {
                        curTimeouts++;
                        LogBus.error("⏱ " + err + " —— 丢掉它、换新宿主重试这一句：「"
                                + head(cur.text, 16) + "」");
                        continue;                          // cur 留着
                    }
                    failed.incrementAndGet();
                    LogBus.error("⏱ " + err + " —— 换过宿主还是不行，跳过这句：「"
                            + head(cur.text, 16) + "」");
                    cur = null;
                    curTimeouts = 0;
                    curFails = 0;
                    curTries = 0;
                    failStreak = 0;
                    continue;
                }
                if (err != null && my == gen) {
                    boolean conn = isConnErr(err);
                    boolean busy = isTimeout(err);   // 超时：它多半还在忙上一条
                    failed.incrementAndGet();
                    LogBus.error("合成失败：" + err + "   ← 文本：" + head(cur.text, 20));
                    boolean throttle = isThrottle(err);
                    curFails++;
                    // 引擎崩了之后，同一句反复重发就是反复把它打崩
                    // （TTS Server 的 SystemTtsService 一遇合成失败就抛 Bad audio format 0 把进程带走）。
                    // 所以连不上超过 3 次，这句直接丢掉，别再喂它。
                    if (conn && curFails >= 3) {
                        LogBus.error("这句试了 " + curFails + " 次都把引擎打没了，跳过它："
                                + head(cur.text, 24)
                                + "（是引擎自己的 bug，不是这句的错）");
                        cur = null;
                        curFails = 0;
                        curTries = 0;
                        failStreak = 0;
                        ttsDownHint(err);
                        continue;
                    }
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
            curFails = 0;
            if (paceMs > 0) {                          // 连续成功 → 把节奏收回去
                paceMs = paceMs / 2;
                if (paceMs < 200) paceMs = 0;
            }
            TtsReviver.noteOk();
            if (my != gen) { cur = null; continue; }
            synthBeat("塞进播放队列");
            try {
                while (running && my == gen && ep == synthEpoch) {
                    if (audioQ.offer(new Clip(cur.text, data, my, cur.voice), 200, TimeUnit.MILLISECONDS)) break;
                }
            } catch (InterruptedException e) {
                return;
            }
            if (ep != synthEpoch) return;              // 这条工作线程已经被换掉了
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
                LogBus.add("→ 引擎刚刚崩了（上一句还念得好好的）。常见原因：某个音色合成失败，"
                        + "它内部抛 Bad audio format 0 把整个进程带走 —— 换个音色试试。"
                        + "已尝试自动拉起转发器，无效就手动点开看端口 " + port);
            } else {
                LogBus.add("→ 引擎没在正常工作：确认转发器已启动、监听端口 " + port);
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
    /**
     * 推进：有音频就播下一句，没有就等。
     * 只在三种情况下被叫醒：上句播完（postAfter）、被打断/跳过、以及自己的空转轮询。
     */
    private final Runnable playNext = new Runnable() {
        public void run() {
            if (!running) return;
            beat("轮询队列");
            // 上一句还没播完，谁也别想插队 —— 保证一句是一句，绝不腰斩
            if (mp != null) {
                scheduleTick();
                return;
            }
            // 暂停：整条队列停在这儿，别偷偷把后面的念了
            if (paused) {
                playH.postDelayed(this, 200);
                return;
            }
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
                if (clip.url != null) playLive(clip); else playOne(clip);
                scheduleTick();
            } catch (Throwable t) {
                failed.incrementAndGet();
                LogBus.error("播放失败：" + t.getClass().getSimpleName() + " " + t.getMessage());
                releasePlayer();
                playH.post(this);
            }
        }
    };

    /** 看护当前这一句：每秒回来看一眼它是不是播完了/被静默暂停了。只留一条链。 */
    private volatile boolean tickOn = false;

    private final Runnable playTick = new Runnable() {
        public void run() {
            tickOn = false;
            if (!running) return;
            beat("看护当前句");
            if (paused) {
                scheduleTick();
                return;
            }
            MediaPlayer p = mp;
            if (p == null) return;                 // 已经不播了：交给 playNext 那条链
            long el = SystemClock.uptimeMillis() - playStart;
            boolean over = playStart > 0 && playDur > 0 && el > playDur + 3000L;
            boolean silent = false;
            if (playStart > 0 && el > 2500L) {
                try {
                    // 音频焦点被抢会静默暂停，onCompletion 永远不来 —— 最阴的一种卡死
                    silent = !p.isPlaying();
                } catch (Throwable ignored) { }
            }
            if (over || silent) {
                stuckCount++;
                LogBus.error("播放卡死（" + (silent ? "被静默暂停" : "超时")
                        + "，已挂 " + el + "ms，音频只有 " + playDur + "ms），强制跳到下一句");
                releasePlayer();
                playStart = 0;
                playDur = 0;
                playNext.run();
                return;
            }
            scheduleTick();
        }
    };

    private void scheduleTick() {
        Handler h = playH;
        if (h == null || tickOn) return;
        tickOn = true;
        h.postDelayed(playTick, 1000);
    }

    /**
     * 播一条还在下载的流：MediaPlayer 直接读 {@code http://127.0.0.1:8788/_live/xxx}
     * （progressive 流，读到哪放到哪）。用 prepareAsync 是为了别把播放线程堵在
     * "等第一批字节"上 —— 那样看门狗会以为线程卡死。
     */
    private void playLive(final Clip clip) throws Exception {
        releasePlayer();
        final MediaPlayer p = new MediaPlayer();
        beat("打开流");
        try {
            p.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            p.setVolume(volume, volume);
            p.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                public void onPrepared(MediaPlayer m) {
                    try {
                        playDur = m.getDuration();
                    } catch (Throwable ignored) { }
                    playStart = SystemClock.uptimeMillis();
                    beat("流播放中");
                    try { m.start(); } catch (Throwable ignored) { }
                }
            });
            p.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                public void onCompletion(MediaPlayer m) { postAfter(null, null); }
            });
            p.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                public boolean onError(MediaPlayer m, int what, int extra) {
                    // 是我们自己把流掐断的（合成中途出错/被打断），播放器当然会报错，
                    // 那不是毛病，别刷一屏吓人。真正的问题上面已经记过了。
                    if (clip.live != null && clip.live.wasCut()) {
                        postAfter(null, null);
                        return true;
                    }
                    postAfter(null, "流播放出错 what=" + what + " extra=" + extra);
                    return true;
                }
            });
            p.setDataSource(clip.url);
            p.prepareAsync();
        } catch (Throwable t) {
            try { p.reset(); } catch (Throwable ignored) { }
            try { p.release(); } catch (Throwable ignored) { }
            throw new IllegalStateException("流起播失败：" + t.getClass().getSimpleName()
                    + " " + t.getMessage());
        }
        mp = p;                       // 立刻记上：playNext 靠它守着"一句是一句"
        spoken.incrementAndGet();
        chars.addAndGet(clip.text.length());
        last = clip.text;
        LogBus.add("🔊 " + (clip.voice == null || clip.voice.length() == 0
                ? "" : "【" + clip.voice.replaceAll("_\\d+$", "") + "】")
                + head(clip.text, 42) + "  (边下边播 · 文本 " + clip.text.length() + " 字)");
        playStart = 0;
        playDur = 0;
    }

    private void playOne(final Clip clip) throws Exception {
        final File f = new File(ctx.getCacheDir(), "clip_" + System.nanoTime() + ".wav");
        releasePlayer();                       // 先放掉上一个，再落盘，避免互相踩
        beat("写音频文件");
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
            beat("setDataSource");
            p.setDataSource(f.getAbsolutePath());
            p.setVolume(volume, volume);
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
            beat("prepare（原生，可能卡这儿）");
            p.prepare();
            int dur = p.getDuration();
            if (dur <= 0) throw new IllegalStateException("音频时长为 " + dur + "ms（文件是坏的）");
            playDur = dur;
            beat("start");
            p.start();
        } catch (Throwable t) {
            try { p.reset(); } catch (Throwable ignored) { }
            try { p.release(); } catch (Throwable ignored) { }
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            throw new IllegalStateException("MediaPlayer 起播失败：" + t.getClass().getSimpleName()
                    + " " + t.getMessage() + "（音频 " + clip.data.length + " 字节）");
        }
        playStart = SystemClock.uptimeMillis();
        playStage = "播放中 " + playDur + "ms";
        mp = p;
        spoken.incrementAndGet();
        chars.addAndGet(clip.text.length());
        last = clip.text;
        // 多语音时把音色也打出来 —— 一眼就能看出这句是谁在念
        // 音频字节/码率也打出来 —— 有些引擎会把超长句子截断，听感是"没念完就自己停了"，
        // 这时候一眼能看出是音频本身短，而不是播放被打断
        LogBus.add("🔊 " + (clip.voice == null || clip.voice.length() == 0
                ? "" : "【" + clip.voice.replaceAll("_\\d+$", "") + "】")
                + head(clip.text, 42) + "  (" + playDur + "ms"
                + " · 文本 " + clip.text.length() + " 字 · 音频 " + (clip.data == null ? 0 : clip.data.length) + " 字节"
                + (clip.data == null || playDur <= 0 ? "" : " · " + (clip.data.length * 8L / playDur) + " kbps") + ")");
    }

    /** 收尾统一走这里：先释放、再取下一条，全在播放线程上排队。 */
    private void postAfter(final File f, final String err) {
        Handler h = playH;
        if (h == null) return;
        h.post(new Runnable() {
            public void run() {
                if (err != null) LogBus.error(err);
                releasePlayer();
                if (f != null) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
                playStart = 0;
                playDur = 0;
                beat("上一句播完");
                playNext.run();
            }
        });
    }

    private static String head(String s, int n) {
        return s.length() > n ? s.substring(0, n) + "…" : s;
    }

    private void releasePlayer() {
        MediaPlayer p = mp;
        mp = null;
        if (p == null) return;
        beat("release（原生，可能卡这儿）");
        try { if (p.isPlaying()) p.stop(); } catch (Throwable ignored) { }
        try { p.reset(); } catch (Throwable ignored) { }
        try { p.release(); } catch (Throwable ignored) { }
    }
}
