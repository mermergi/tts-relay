package com.mermergi.ttsrelay;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 硬超时：把活丢给一条一次性线程，到点就 {@code join(timeout)} 走人。
 *
 * <p>为什么不用协程的 {@code withTimeout}：它只能取消**协程的挂起点**。
 * 插件那边走的是 `PipedInputStream`，我们读它的时候是阻塞在
 * {@code InputStream.readBytes()} 上 —— 那里没有挂起点，插件要是一直不
 * 回调（WebSocket 悄悄断了、服务端不回包），协程超时**永远不会触发**，
 * 线程就永远吊在那儿。真机上就是这么"念了三句就不动了，日志一个字没有"。
 *
 * <p>所以这里用最硬的一招：超时之后那条线程**连同它抱着的锁一起丢掉**
 * （弃线程），调用方继续往下走。丢掉之前先 interrupt 一下，能醒就醒。
 */
public final class HardCall {

    /** 超时。这条线程已经被丢弃，调用方别再指望它。 */
    public static final class TimedOut extends Exception {
        public final long ms;
        TimedOut(long ms, String msg) { super(msg); this.ms = ms; }
    }

    private static final AtomicInteger ABANDONED = new AtomicInteger();

    /** 累计丢掉了多少条吊死的线程（看门狗/日志用）。 */
    public static int abandoned() { return ABANDONED.get(); }

    public interface Job<T> {
        T run() throws Throwable;
    }

    /** 干活的进度：还在动就返回"刚刚有过动静"的时间戳。 */
    public interface Clock {
        long lastActivity();
    }

    /**
     * 一边等一边看进度：只要 {@code clock} 还在往前走（比如插件还在吐音频字节），
     * 就一直等下去 —— <b>慢不等于吊死</b>。只有连续 {@code idleMs} 毫无动静，
     * 或者总共超过 {@code maxMs}，才丢线程。
     *
     * <p>为什么要有这个：以前只按总时长卡，一句 18 秒的长音频在 5 秒时限下必被杀，
     * 然后丢宿主、重建、下一发又慢 —— 朗读开头就是这么断断续续的。
     */
    public static <T> T runIdle(final String what, long idleMs, long maxMs,
                                final Clock clock, final Job<T> job) throws Exception {
        final Object[] out = new Object[1];
        final Throwable[] err = new Throwable[1];
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    out[0] = job.run();
                } catch (Throwable e) {
                    err[0] = e;
                }
            }
        }, "hardcall-" + what);
        t.setDaemon(true);
        long start = System.currentTimeMillis();
        t.start();
        while (true) {
            t.join(250L);
            if (!t.isAlive()) break;
            long now = System.currentTimeMillis();
            long last = clock == null ? now : clock.lastActivity();
            if (last > 0 && now - last > Math.max(1000L, idleMs)) {
                return abandon(t, what, (now - last) / 1000 + " 秒没有任何动静（还活着的话它还在吐数据）");
            }
            if (now - start > Math.max(2000L, maxMs)) {
                return abandon(t, what, (now - start) / 1000 + " 秒还没读完（总时限）");
            }
        }
        if (err[0] != null) {
            if (err[0] instanceof Exception) throw (Exception) err[0];
            if (err[0] instanceof Error) throw (Error) err[0];
            throw new IllegalStateException(err[0]);
        }
        //noinspection unchecked
        return (T) out[0];
    }

    private static <T> T abandon(Thread t, String what, String why) throws TimedOut {
        int n = ABANDONED.incrementAndGet();
        t.interrupt();
        throw new TimedOut(0, what + " 停住了：" + why + "（那条线程已丢弃，累计 " + n + " 条）");
    }

    /**
     * 跑 {@code job}，最多等 {@code timeoutMs}（毫秒）。
     *
     * @param what 日志里显示的名字，比如"合成"
     * @throws TimedOut 到点没返回 —— 线程已丢弃
     */
    public static <T> T run(final String what, long timeoutMs, final Job<T> job) throws Exception {
        final Object[] out = new Object[1];
        final Throwable[] err = new Throwable[1];
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    out[0] = job.run();
                } catch (Throwable e) {
                    err[0] = e;
                }
            }
        }, "hardcall-" + what);
        t.setDaemon(true);
        t.start();
        t.join(Math.max(1000L, timeoutMs));
        if (t.isAlive()) {
            int n = ABANDONED.incrementAndGet();
            t.interrupt();   // 能醒就醒（sleep/IO 可中断的那种）；醒不了就让它挂在后台
            throw new TimedOut(timeoutMs, what + " 超过 " + (timeoutMs / 1000) + " 秒没返回"
                    + "（那条线程已丢弃，累计 " + n + " 条）");
        }
        if (err[0] != null) {
            if (err[0] instanceof Exception) throw (Exception) err[0];
            if (err[0] instanceof Error) throw (Error) err[0];
            throw new IllegalStateException(err[0]);
        }
        //noinspection unchecked
        return (T) out[0];
    }
}
