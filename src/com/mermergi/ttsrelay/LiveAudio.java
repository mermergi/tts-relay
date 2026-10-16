package com.mermergi.ttsrelay;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一条"还在下载的音频"—— 插件边合成，播放器边放，不等整句下完。
 *
 * <p>玩法：插件吐一块字节就 {@link #chunk} 进来；本机 HTTP 上开一个
 * {@code /_live/<id>} 的流式端点（chunked，不写 Content-Length），
 * MediaPlayer 直接播这个 URL —— 它按 progressive 流读，读多少放多少。
 *
 * <p>为什么绕一圈走 HTTP：Android 的 MediaPlayer 不能播"正在被追加写"的文件，
 * 但它会老老实实读 HTTP 流（网络电台就是这么播的）。自己去写 MediaCodec + AudioTrack
 * 解码是另一条路，没必要。
 */
public final class LiveAudio {

    private static final Map<String, LiveAudio> LIVE = new ConcurrentHashMap<String, LiveAudio>();
    private static final AtomicInteger SEQ = new AtomicInteger();

    /** 推给 HTTP 客户端用（HttpServer 里那个 writeChunk）。 */
    public interface Sink {
        void chunk(byte[] data, int off, int len) throws IOException;
    }

    private final String id;
    /** 音频字节（句子级别，几十~几百 KB，先全留着，简单可靠）。 */
    private byte[] buf = new byte[64 * 1024];
    private int written = 0;
    private boolean finished = false;
    private String error = null;
    /** 有没有 HTTP 客户端把它读干过（读干过就可以从表里摘掉）。 */
    private volatile boolean drained = false;

    private LiveAudio(String id) { this.id = id; }

    public static LiveAudio create() {
        LiveAudio a = new LiveAudio("a" + SEQ.incrementAndGet());
        LIVE.put(a.id, a);
        return a;
    }

    public static LiveAudio get(String id) { return id == null ? null : LIVE.get(id); }

    public static void drop(LiveAudio a) { if (a != null) LIVE.remove(a.id); }

    /** 现在还有哪些流在表里（调试：/_status 里会带上）。 */
    public static String ids() { return LIVE.keySet().toString(); }

    public String id() { return id; }

    /** 播放器要播的地址（本机回环 + 中转端口）。 */
    public String url() {
        return "http://127.0.0.1:" + HttpServer.PORT + "/_live/" + id;
    }

    /** 插件又吐了一块。 */
    public void chunk(byte[] b, int off, int len) {
        if (b == null || len <= 0) return;
        synchronized (this) {
            if (written + len > buf.length) {
                int cap = Math.max(buf.length * 2, written + len);
                byte[] nb = new byte[cap];
                System.arraycopy(buf, 0, nb, 0, written);
                buf = nb;
            }
            System.arraycopy(b, off, buf, written, len);
            written += len;
            notifyAll();
        }
    }

    public void finish() {
        synchronized (this) {
            finished = true;
            notifyAll();
        }
    }

    /** 出错了：让播放端赶紧收尾，别干等。 */
    public void fail(String msg) {
        synchronized (this) {
            error = msg == null ? "合成失败" : msg;
            finished = true;
            notifyAll();
        }
    }

    /** 是我们自己掐断的（合成中途出错 / 被打断）：播放器那边的 EOF 报错就别当回事。 */
    public synchronized boolean wasCut() { return error != null; }

    public synchronized String error() { return error; }
    public synchronized boolean isFinished() { return finished; }
    public synchronized int available() { return written; }
    public boolean isDrained() { return drained; }

    /** 等第一批字节（用来判断 mp3 还是 wav），最多等 {@code ms}。 */
    public String contentType(long ms) {
        long end = System.currentTimeMillis() + ms;
        synchronized (this) {
            while (written < 4 && !finished && System.currentTimeMillis() < end) {
                try { wait(200); } catch (InterruptedException ignored) { }
            }
            if (written >= 4) {
                String h = "" + (char) buf[0] + (char) buf[1] + (char) buf[2] + (char) buf[3];
                if ("RIFF".equals(h)) return "audio/wav";
                if ("OggS".equals(h)) return "audio/ogg";
                if (h.startsWith("ID3") || (buf[0] & 0xFF) == 0xFF) return "audio/mpeg";
            }
            return "audio/mpeg";     // 认不出来就按 mp3 报：MediaPlayer 会自己嗅
        }
    }

    /**
     * 把字节一块块推给 HTTP 客户端。<b>没有</b> Content-Length，客户端读多少算多少；
     * 客户端读得慢（播放缓冲满了）就自然阻塞在这儿，这正是我们想要的背压。
     */
    public void pump(Sink sink) throws IOException {
        int sent = 0;
        while (true) {
            byte[] snapshot;
            int off, len;
            synchronized (this) {
                while (written <= sent && !finished) {
                    try { wait(300); } catch (InterruptedException ignored) { return; }
                }
                if (written <= sent) {          // finished 且没有新数据
                    drained = true;
                    return;
                }
                snapshot = buf;
                off = sent;
                len = written - sent;
                sent = written;
            }
            sink.chunk(snapshot, off, len);
        }
    }
}
