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
