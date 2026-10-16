package com.mermergi.ttsrelay;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * 一个刚刚够用的 WebSocket 客户端（RFC6455）。
 *
 * 为什么自己写：这个 App 是 aapt2 + javac + d8 手工编的，没有依赖管理，
 * 塞不进 okhttp，而 Android 框架里也没有 WS 客户端。要连的是 wss，
 * 所以握手、掩码、分片、ping/pong 都得自己来 —— 一共就这几百行。
 *
 * 现在两处在用：JS 插件那套 {@code Websocket} 宿主，以及自带的引擎。
 */
public final class MiniWs {

    private final Socket sock;
    private final InputStream in;
    private final OutputStream out;
    private final SecureRandom rnd = new SecureRandom();
    private volatile boolean closed = false;
    /** 最近一帧的操作码：1=文本 2=二进制。调用方靠它分辨「这是 JSON 还是音频」。 */
    public volatile int lastOpcode = 0;

    private MiniWs(Socket s) throws Exception {
        this.sock = s;
        this.in = s.getInputStream();
        this.out = s.getOutputStream();
    }

    /** 连上并完成握手。headers 里别带 Host/Upgrade/Connection/Key/Version，那些自己加。 */
    public static MiniWs connect(String url, LinkedHashMap<String, String> headers, int timeoutMs)
            throws Exception {
        URI u = new URI(url);
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(java.util.Locale.US);
        boolean tls = "wss".equals(scheme);
        String host = u.getHost();
        int port = u.getPort() > 0 ? u.getPort() : (tls ? 443 : 80);
        String path = u.getRawPath() == null || u.getRawPath().length() == 0 ? "/" : u.getRawPath();
        if (u.getRawQuery() != null && u.getRawQuery().length() > 0) path += "?" + u.getRawQuery();
        if (host == null) throw new IllegalArgumentException("地址不合法：" + url);

        Socket s;
        if (tls) {
            SSLSocketFactory f = (SSLSocketFactory) SSLSocketFactory.getDefault();
            SSLSocket ss = (SSLSocket) f.createSocket();
            ss.connect(new InetSocketAddress(host, port), timeoutMs);
            ss.setSoTimeout(timeoutMs);
            // SNI：不带它，很多站点会直接掐握手
            try {
                javax.net.ssl.SSLParameters sp = ss.getSSLParameters();
                sp.setServerNames(java.util.Collections.singletonList(
                        new javax.net.ssl.SNIHostName(host)));
                ss.setSSLParameters(sp);
            } catch (Throwable ignored) { }
            ss.startHandshake();
            s = ss;
        } else {
            s = new Socket();
            s.connect(new InetSocketAddress(host, port), timeoutMs);
        }
        s.setSoTimeout(timeoutMs);
        s.setTcpNoDelay(true);

        MiniWs ws = new MiniWs(s);

        byte[] nonce = new byte[16];
        new SecureRandom().nextBytes(nonce);
        String key = java.util.Base64.getEncoder().encodeToString(nonce);   // API 26 起就有

        StringBuilder req = new StringBuilder();
        req.append("GET ").append(path).append(" HTTP/1.1\r\n");
        req.append("Host: ").append(host).append("\r\n");
        req.append("Upgrade: websocket\r\n");
        req.append("Connection: Upgrade\r\n");
        req.append("Sec-WebSocket-Key: ").append(key).append("\r\n");
        req.append("Sec-WebSocket-Version: 13\r\n");
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                req.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
            }
        }
        req.append("\r\n");
        ws.out.write(req.toString().getBytes("ISO-8859-1"));
        ws.out.flush();

        // 读响应头，直读到空行
        ByteArrayOutputStream hb = new ByteArrayOutputStream(1024);
        int last4 = 0;
        int guard = 0;
        while (guard++ < 65536) {
            int c = ws.in.read();
            if (c < 0) throw new java.io.IOException("握手时连接就断了");
            hb.write(c);
            last4 = ((last4 << 8) | c) & 0xFFFFFFFF;
            if ((last4 & 0xFFFF) == 0x0D0A && hb.size() >= 4) {
                byte[] h = hb.toByteArray();
                int n = h.length;
                if (n >= 4 && h[n - 4] == 13 && h[n - 3] == 10 && h[n - 2] == 13 && h[n - 1] == 10) break;
            }
        }
        String head = new String(hb.toByteArray(), "ISO-8859-1");
        String firstLine = head.split("\r\n", 2)[0];
        if (!firstLine.contains("101")) {
            // 被拒的时候对端通常还会给一段正文（错误码/原因），带出来好排查
            String snippet = "";
            try {
                byte[] rest = new byte[512];
                int total = 0;
                while (total < rest.length) {
                    int r = ws.in.read(rest, total, rest.length - total);
                    if (r < 0) break;
                    total += r;
                }
                snippet = new String(rest, 0, total, "UTF-8").trim();
            } catch (Throwable ignored) { }
            ws.closeQuietly();
            throw new java.io.IOException("WebSocket 握手被拒：" + firstLine
                    + (snippet.length() > 0
                    ? "  响应：" + snippet.replaceAll("\\s+", " ").substring(0, Math.min(200, snippet.length()))
                    : ""));
        }
        return ws;
    }

    // ───────────────────────── 发 ─────────────────────────

    public void sendBinary(byte[] data) throws Exception {
        send(0x2, data);
    }

    /** 文本帧（有些服务就吃 JSON 字符串，不用二进制）。 */
    public void sendText(String text) throws Exception {
        send(0x1, (text == null ? "" : text).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private void send(int opcode, byte[] data) throws Exception {
        if (closed) throw new java.io.IOException("连接已关");
        if (data == null) data = new byte[0];
        ByteArrayOutputStream o = new ByteArrayOutputStream(data.length + 14);
        o.write(0x80 | (opcode & 0x0f));
        int len = data.length;
        if (len < 126) {
            o.write(0x80 | len);                      // 客户端必须掩码
        } else if (len < 65536) {
            o.write(0x80 | 126);
            o.write((len >>> 8) & 0xff);
            o.write(len & 0xff);
        } else {
            o.write(0x80 | 127);
            for (int i = 7; i >= 0; i--) o.write((int) (((long) len >>> (8 * i)) & 0xff));
        }
        byte[] mask = new byte[4];
        rnd.nextBytes(mask);
        o.write(mask, 0, 4);
        for (int i = 0; i < len; i++) o.write(data[i] ^ mask[i & 3]);
        synchronized (this) {
            out.write(o.toByteArray());
            out.flush();
        }
    }

    private void sendPong(byte[] payload) {
        try { send(0xA, payload); } catch (Throwable ignored) { }
    }

    // ───────────────────────── 收 ─────────────────────────

    /** 收一条完整消息（自动处理分片和 ping/pong）。连接关了返回 null。 */
    public byte[] readBinary() throws Exception {
        ByteArrayOutputStream acc = new ByteArrayOutputStream(64 * 1024);
        while (!closed) {
            byte[] b0;
            try {
                b0 = readN(2);
            } catch (java.net.SocketTimeoutException e) {
                throw e;
            }
            int fin = b0[0] & 0x80;
            int op = b0[0] & 0x0f;
            boolean masked = (b0[1] & 0x80) != 0;
            long len = b0[1] & 0x7f;
            if (len == 126) {
                byte[] e = readN(2);
                len = ((e[0] & 0xffL) << 8) | (e[1] & 0xffL);
            } else if (len == 127) {
                byte[] e = readN(8);
                len = 0;
                for (int i = 0; i < 8; i++) len = (len << 8) | (e[i] & 0xffL);
            }
            byte[] key = masked ? readN(4) : null;
            byte[] payload = len > 0 ? readN((int) len) : new byte[0];
            if (masked && key != null) {
                for (int i = 0; i < payload.length; i++) payload[i] ^= key[i & 3];
            }

            if (op == 0x8) {                      // close
                closeQuietly();
                return null;
            }
            if (op == 0x9) { sendPong(payload); continue; }
            if (op == 0xA) continue;              // pong
            if (op == 0x1 || op == 0x2 || op == 0x0) {
                acc.write(payload, 0, payload.length);
                if (fin != 0) {
                    if (op != 0x0) lastOpcode = op;      // 续帧保留首帧的类型
                    return acc.toByteArray();
                }
                continue;
            }
        }
        return null;
    }

    private byte[] readN(int n) throws Exception {
        byte[] b = new byte[n];
        int got = 0;
        while (got < n) {
            int r = in.read(b, got, n - got);
            if (r < 0) throw new java.io.IOException("连接被对端关掉");
            got += r;
        }
        return b;
    }

    public boolean isClosed() { return closed; }

    public void closeQuietly() {
        closed = true;
        try { sock.close(); } catch (Throwable ignored) { }
    }

    public void close() {
        try { send(0x8, new byte[0]); } catch (Throwable ignored) { }
        closeQuietly();
    }
}
