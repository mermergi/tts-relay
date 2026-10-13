package com.mermergi.ttsrelay;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 本地 HTTP/1.1 服务：
 *   /_*       管理接口（状态 / 音色 / 试听 / 静音 …）
 *   其它一切   原样转发给上游，同时把 SSE 里的正文扒出来交给 Speaker
 */
public final class HttpServer {

    /**
     * "这是后台小活、别念" 的关键词。
     * 只扫 <b>system / developer</b> 提示词，绝不扫整包 messages ——
     * 联网搜索的结果长这样 {"title":…,"link":…,"snippet":…}，
     * 扫整包必然命中 "title"，于是搜索结果之后那条正牌回复被整段静音。
     */
    private static final String[] SKIP_MARKERS = {"标题", "title", "摘要", "summar", "命名"};

    /** 一次"念不念"的判定结果 + 理由，出问题看日志就知道是谁把嘴捂上的。 */
    private static final class Decision {
        final boolean speak;
        /** 要不要打断正在念的上一段。false = 接在它后面排队（同一个回合的工具续写）。 */
        final boolean cut;
        final String why;
        Decision(boolean s, boolean c, String w) { speak = s; cut = c; why = w; }
    }

    private final Context ctx;
    private final Speaker speaker;
    private volatile ServerSocket server;
    private volatile boolean running;
    private Thread acceptThread;
    private int port = Prefs.PORT_DEF;

    public HttpServer(Context c, Speaker s) {
        ctx = c.getApplicationContext();
        speaker = s;
    }

    public int port() { return port; }
    public boolean isRunning() { return running; }

    public void start(int p) throws IOException {
        if (running) return;
        port = p;
        boolean lan = Prefs.lanAllowed(ctx);
        String bindIp = lan ? "0.0.0.0" : "127.0.0.1";
        server = new ServerSocket(p, 32, InetAddress.getByName(bindIp));
        server.setReuseAddress(true);
        running = true;
        acceptThread = new Thread(new Runnable() {
            public void run() { acceptLoop(); }
        }, "relay-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        LogBus.add("中转已启动  " + bindIp + ":" + p
                + (lan ? "（局域网可访问）" : "（仅本机）"));
    }

    public void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (Throwable ignored) { }
        server = null;
        LogBus.add("中转已停止");
    }

    private void acceptLoop() {
        while (running) {
            Socket s;
            try {
                s = server.accept();
            } catch (Throwable t) {
                if (running) LogBus.add("accept 异常：" + t.getMessage());
                break;
            }
            final Socket sock = s;
            Thread t = new Thread(new Runnable() {
                public void run() { serve(sock); }
            }, "relay-conn");
            t.setDaemon(true);
            t.start();
        }
    }

    // ══════════════════════════ 连接处理 ══════════════════════════
    private void serve(Socket sock) {
        OutputStream rawOut = null;
        try {
            sock.setSoTimeout(30000);
            sock.setTcpNoDelay(true);
            InputStream in = sock.getInputStream();
            rawOut = new BufferedOutputStream(sock.getOutputStream(), 16384);

            String reqLine = readLine(in);
            if (reqLine == null || reqLine.length() == 0) return;
            String[] parts = reqLine.split(" ");
            if (parts.length < 2) return;
            String method = parts[0].toUpperCase(Locale.US);
            String target = parts[1];

            Map<String, String> headers = new LinkedHashMap<String, String>();
            String line;
            while ((line = readLine(in)) != null && line.length() > 0) {
                int c = line.indexOf(':');
                if (c > 0) headers.put(line.substring(0, c).trim().toLowerCase(Locale.US),
                        line.substring(c + 1).trim());
            }

            byte[] body = readBody(in, headers);

            String path = target, query = null;
            int qm = target.indexOf('?');
            if (qm >= 0) {
                path = target.substring(0, qm);
                query = target.substring(qm + 1);
            }
            if (path.startsWith("http://") || path.startsWith("https://")) {
                try {
                    URL u = new URL(target);
                    path = u.getPath();
                    query = u.getQuery();
                } catch (Throwable ignored) { }
            }

            if (path.startsWith("/_")) {
                admin(rawOut, method, path, query, body);
            } else if ("OPTIONS".equals(method)) {
                // 浏览器/网页版客户端（酒馆这类）会先发预检，必须答好，否则跨域直接被拦
                Map<String, String> h = new LinkedHashMap<String, String>();
                h.put("Content-Type", "text/plain");
                writeHead(rawOut, 204, h, 0, false);
                rawOut.flush();
            } else {
                proxy(sock, rawOut, method, path, query, headers, body);
            }
        } catch (Throwable t) {
            LogBus.add("连接处理异常：" + t.getMessage());
        } finally {
            try { if (rawOut != null) rawOut.flush(); } catch (Throwable ignored) { }
            try { sock.close(); } catch (Throwable ignored) { }
        }
    }

    // ══════════════════════════ 管理接口 ══════════════════════════
    private void admin(OutputStream out, String method, String path, String query, byte[] body)
            throws IOException {
        Map<String, String> qs = parseQuery(query);
        if ("/_check".equals(path)) {
            // 把一段请求体喂进来，只做"念不念"的判定、不转发 —— 用来复现静音问题
            Decision d = decide(body == null ? new byte[0] : body);
            JSONObject o = bodyInfo(body == null ? new byte[0] : body);
            try {
                o.put("speak", d.speak);
                o.put("cut", d.cut);
                o.put("why", d.why);
            } catch (Throwable ignored) { }
            json(out, 200, o.toString());
            return;
        }
        if ("/_log".equals(path)) {
            byte[] b = LogBus.dump().getBytes(StandardCharsets.UTF_8);
            Map<String, String> h = new LinkedHashMap<String, String>();
            h.put("Content-Type", "text/plain; charset=utf-8");
            h.put("Cache-Control", "no-store");
            writeHead(out, 200, h, b.length, false);
            out.write(b);
            out.flush();
            return;
        }
        if ("/_status".equals(path)) {
            JSONObject o = new JSONObject();
            try {
                o.put("ok", true);
                o.put("port", port);
                o.put("running", running);
                o.put("muted", Prefs.muted(ctx));
                o.put("spoken", speaker.spokenCount());
                o.put("failed", speaker.failedCount());
                o.put("stuck", speaker.stuckCount());
                o.put("error", LogBus.lastError());
                o.put("chars", speaker.charCount());
                o.put("queue", speaker.queued());
                o.put("last", speaker.lastText());
                o.put("upstream", Prefs.upstream(ctx));
                o.put("engine", Prefs.engine(ctx));
                o.put("voice", Prefs.voice(ctx));
                o.put("tts", Prefs.ttsBase(ctx));
            } catch (Throwable ignored) { }
            json(out, 200, o.toString());
            return;
        }
        if ("/_voices".equals(path)) {
            try {
                json(out, 200, TtsClient.voices(ctx));
            } catch (Throwable t) {
                json(out, 502, "{\"error\":\"" + esc(t.getMessage()) + "\"}");
            }
            return;
        }
        if ("/_voice".equals(path)) {
            String v = qs.get("v");
            if (v != null && v.length() > 0) Prefs.setVoice(ctx, v);
            json(out, 200, "{\"ok\":true,\"voice\":\"" + esc(Prefs.voice(ctx)) + "\"}");
            return;
        }
        if ("/_say".equals(path)) {
            String t = qs.get("text");
            if (t == null || t.length() == 0) t = "测试一下，能听见吗？";
            speaker.feed(t);
            json(out, 200, "{\"ok\":true}");
            return;
        }
        if ("/_selftest".equals(path)) {
            Splitter sp = new Splitter(Prefs.maxChunk(ctx), 4);
            String fake = "哥哥，这是自检。"
                    + "\n```python\nprint('这段代码不该被念出来')\n```\n"
                    + "如果你听到了这句话，说明断句、跳过代码块、合成、播放整条链路都通了。";
            for (int i = 0; i < fake.length(); i++) {
                for (String s : sp.feed(String.valueOf(fake.charAt(i)))) speaker.feed(s);
            }
            for (String s : sp.flush()) speaker.feed(s);
            json(out, 200, "{\"ok\":true,\"msg\":\"自检音频已排队\"}");
            return;
        }
        if ("/_stop".equals(path)) {
            speaker.interrupt();
            json(out, 200, "{\"ok\":true}");
            return;
        }
        if ("/_mute".equals(path)) {
            Prefs.setMuted(ctx, true);
            speaker.interrupt();
            json(out, 200, "{\"ok\":true,\"muted\":true}");
            return;
        }
        if ("/_unmute".equals(path)) {
            Prefs.setMuted(ctx, false);
            json(out, 200, "{\"ok\":true,\"muted\":false}");
            return;
        }
        json(out, 404, "{\"ok\":false,\"error\":\"unknown admin path\"}");
    }

    private void json(OutputStream out, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        Map<String, String> h = new LinkedHashMap<String, String>();
        h.put("Content-Type", "application/json; charset=utf-8");
        h.put("Cache-Control", "no-store");
        writeHead(out, code, h, b.length, false);
        out.write(b);
        out.flush();
    }

    // ══════════════════════════ 转发 ══════════════════════════
    private void proxy(Socket sock, OutputStream out, String method, String path, String query,
                       Map<String, String> headers, byte[] body) throws IOException {

        boolean chat = path.endsWith("/chat/completions") && "POST".equals(method);
        Decision dec = chat ? decide(body) : new Decision(false, true, "非对话请求");
        boolean speak = dec.speak;
        logRequest(method, path, body, dec, chat);

        // 不在这里就打断！等这一轮真的吐出第一句可念的正文时再打断。
        // 否则"会话标题生成""摘要"这类不发声的请求会把正在念的回复整段清空 → 直接没声音。
        final boolean[] turnStarted = new boolean[]{false};

        String up = Prefs.upstream(ctx).replaceAll("/+$", "");
        String url = up + path + (query != null && query.length() > 0 ? "?" + query : "");

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(300000);
            conn.setUseCaches(false);
            conn.setInstanceFollowRedirects(false);

            for (Map.Entry<String, String> e : headers.entrySet()) {
                String k = e.getKey();
                if (k.equals("host") || k.equals("content-length") || k.equals("connection")
                        || k.equals("keep-alive") || k.equals("transfer-encoding")
                        || k.equals("upgrade") || k.equals("accept-encoding")
                        || k.equals("proxy-connection") || k.equals("te") || k.equals("trailer")) {
                    continue;
                }
                try { conn.setRequestProperty(e.getKey(), e.getValue()); } catch (Throwable ignored) { }
            }
            conn.setRequestProperty("Accept-Encoding", "identity");

            if (body != null && body.length > 0) {
                conn.setDoOutput(true);
                conn.setFixedLengthStreamingMode(body.length);
                OutputStream os = conn.getOutputStream();
                try { os.write(body); } finally { os.close(); }
            }

            int code = conn.getResponseCode();
            String ct = conn.getHeaderField("Content-Type");

            Map<String, String> outHeaders = new LinkedHashMap<String, String>();
            for (Map.Entry<String, java.util.List<String>> e : conn.getHeaderFields().entrySet()) {
                String k = e.getKey();
                if (k == null) continue;
                String lk = k.toLowerCase(Locale.US);
                if (lk.equals("content-length") || lk.equals("connection")
                        || lk.equals("transfer-encoding") || lk.equals("keep-alive")
                        || lk.equals("content-encoding") || lk.startsWith("access-control-")) {
                    continue;
                }
                if (!e.getValue().isEmpty()) outHeaders.put(k, e.getValue().get(0));
            }
            if (ct != null) outHeaders.put("Content-Type", ct);

            if (ct != null && ct.contains("text/event-stream")) {
                streamSse(out, conn, code, outHeaders, speak, turnStarted, dec.cut);
                return;
            }

            InputStream ris = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            byte[] data = ris == null ? new byte[0] : TtsClient.readAll(ris, 64 * 1024 * 1024);
            writeHead(out, code, outHeaders, data.length, false);
            out.write(data);
            out.flush();

            if (speak && code == 200 && ct != null && ct.contains("application/json")) {
                try {
                    JSONObject o = new JSONObject(new String(data, StandardCharsets.UTF_8));
                    JSONArray ch = o.optJSONArray("choices");
                    if (ch != null && ch.length() > 0) {
                        JSONObject msg = ch.optJSONObject(0).optJSONObject("message");
                        if (msg != null) {
                            Object c = msg.opt("content");
                            if (c instanceof String) {
                                Splitter sp = new Splitter(Prefs.maxChunk(ctx), 4);
                                int[] sent = new int[]{0};
                                emit(sp.feed((String) c), turnStarted, sent, dec.cut);
                                emit(sp.flush(), turnStarted, sent, dec.cut);
                                if (sent[0] > 0) LogBus.add("↳ 本轮念出 " + sent[0] + " 句");
                            }
                        }
                    }
                } catch (Throwable ignored) { }
            }
        } catch (Throwable t) {
            LogBus.add("转发失败：" + t.getMessage());
            try {
                json(out, 502, "{\"error\":{\"message\":\"relay: " + esc(t.getMessage()) + "\"}}");
            } catch (Throwable ignored) { }
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 边转发边解析，客户端拿到的字节和上游一模一样。 */
    private void streamSse(OutputStream out, HttpURLConnection conn, int code,
                           Map<String, String> headers, boolean speak, boolean[] started,
                           boolean cut) throws IOException {
        writeHead(out, code, headers, -1, true);

        InputStream in = conn.getInputStream();
        Splitter sp = new Splitter(Prefs.maxChunk(ctx), 4);
        CharsetDecoder dec = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        StringBuilder lineBuf = new StringBuilder();

        byte[] buf = new byte[8192];
        int n;
        int[] sent = new int[]{0};
        while ((n = in.read(buf)) > 0) {
            writeChunk(out, buf, 0, n);            // 原样透传
            if (!speak) continue;
            ByteBuffer bb = ByteBuffer.wrap(buf, 0, n);
            CharBuffer cb = CharBuffer.allocate(n * 2 + 8);
            dec.decode(bb, cb, false);
            cb.flip();
            lineBuf.append(cb);
            int nl;
            while ((nl = lineBuf.indexOf("\n")) >= 0) {
                String l = lineBuf.substring(0, nl);
                lineBuf.delete(0, nl + 1);
                sseLine(l, sp, started, sent, cut);
            }
        }
        if (speak) {
            if (lineBuf.length() > 0) sseLine(lineBuf.toString(), sp, started, sent, cut);
            emit(sp.flush(), started, sent, cut);
            // 这条请求到底吐了几句正文？—— 和请求日志对起来就知道是哪一发在发声
            LogBus.add("↳ 本轮念出 " + sent[0] + " 句");
        }
        endChunks(out);
    }

    /** 真的凑出可念的句子了，才去打断上一段 —— 这是"没声音"那个坑的正解。 */
    private void emit(java.util.List<String> sentences, boolean[] started, int[] sent,
                      boolean cut) {
        if (sentences == null || sentences.isEmpty()) return;
        if (!started[0]) {
            started[0] = true;
            // 同一个回合的工具续写：接在后面念，不腰斩正在播的那一句
            if (cut) speaker.interrupt();
        }
        for (String s : sentences) {
            speaker.feed(s);
            sent[0]++;
        }
    }

    private void sseLine(String line, Splitter sp, boolean[] started, int[] sent, boolean cut) {
        line = line.trim();
        if (!line.startsWith("data:")) return;
        String payload = line.substring(5).trim();
        if (payload.length() == 0 || "[DONE]".equals(payload)) {
            emit(sp.flush(), started, sent, cut);
            return;
        }
        try {
            JSONObject o = new JSONObject(payload);
            JSONArray ch = o.optJSONArray("choices");
            if (ch == null) return;
            for (int i = 0; i < ch.length(); i++) {
                JSONObject c = ch.optJSONObject(i);
                if (c == null) continue;
                JSONObject d = c.optJSONObject("delta");
                if (d != null) {
                    Object v = d.opt("content");
                    if (v instanceof String) emit(sp.feed((String) v), started, sent, cut);
                }
                JSONObject m = c.optJSONObject("message");
                if (m != null) {
                    Object v = m.opt("content");
                    if (v instanceof String) emit(sp.feed((String) v), started, sent, cut);
                }
            }
        } catch (Throwable ignored) { }
    }

    /** 每个转发请求都记一笔 —— 出问题时看一眼日志就知道 Chatbox 到底发了几发、发的什么。 */
    private void logRequest(String method, String path, byte[] body, Decision d, boolean chat) {
        if (!chat) {
            if (!"GET".equals(method)) LogBus.add("← " + method + " " + path);
            return;
        }
        JSONObject info = bodyInfo(body);
        LogBus.add("← " + method + " " + path
                + " model=" + info.optString("model", "?")
                + " msgs=" + info.optInt("msgs", 0)
                + " 末条=" + info.optString("last", "-") + "(" + info.optInt("lastLen", 0) + "字)"
                + " max_tokens=" + info.optInt("max_tokens", -1)
                + " stream=" + info.optBoolean("stream", false)
                + "  →  " + (d.speak ? (d.cut ? "念" : "念·接在后面") : "跳过")
                + "（" + d.why + "）");
    }

    /** 把请求体里能看懂的字段抠出来，日志和 /_check 共用。 */
    private static JSONObject bodyInfo(byte[] body) {
        JSONObject o = new JSONObject();
        try {
            JSONObject src = new JSONObject(new String(body, StandardCharsets.UTF_8));
            JSONArray m = src.optJSONArray("messages");
            int cnt = m == null ? 0 : m.length();
            String lastRole = "-";
            int lastLen = 0;
            if (m != null && cnt > 0) {
                JSONObject last = m.optJSONObject(cnt - 1);
                if (last != null) {
                    lastRole = last.optString("role", "-");
                    Object c = last.opt("content");
                    lastLen = c == null ? 0 : String.valueOf(c).length();
                }
            }
            o.put("model", src.optString("model", "?"));
            o.put("msgs", cnt);
            o.put("last", lastRole);
            o.put("lastLen", lastLen);
            o.put("max_tokens", src.optInt("max_tokens", -1));
            o.put("stream", src.optBoolean("stream", false));
        } catch (Throwable ignored) { }
        return o;
    }

    /**
     * 念不念？ —— 唯一的判定入口，理由会写进日志。
     *
     * 规矩只有三条：
     *   ① 额度特别小的请求 = 客户端在后台起标题/写摘要，不念；
     *   ② 末条是 tool 结果 / 带 tool_calls 的 assistant = 工具轮的续写，
     *      联网搜索回灌后的那一条正是最终答复 —— 必须念（老版本这里被误杀），
     *      而且要**接在上一段后面排队**、不许打断，因为它是同一个回合的后半截；
     *   ③ "起标题"关键词只在 system 提示词里找，绝不碰用户和工具正文。
     */
    private Decision decide(byte[] body) {
        if (Prefs.muted(ctx)) return new Decision(false, true, "已静音");
        if (!Prefs.skipSmall(ctx)) return new Decision(true, true, "跳过规则已关");
        try {
            JSONObject o = new JSONObject(new String(body, StandardCharsets.UTF_8));

            int mt = o.optInt("max_tokens", -1);
            if (mt > 0 && mt < 200) return new Decision(false, true, "max_tokens=" + mt + "，后台小活");
            int mct = o.optInt("max_completion_tokens", -1);
            if (mct > 0 && mct < 200) {
                return new Decision(false, true, "max_completion_tokens=" + mct + "，后台小活");
            }

            JSONArray m = o.optJSONArray("messages");
            StringBuilder sys = new StringBuilder();
            String lastRole = "";
            boolean lastToolCalls = false;
            if (m != null) {
                for (int i = 0; i < m.length(); i++) {
                    JSONObject msg = m.optJSONObject(i);
                    if (msg == null) continue;
                    String role = msg.optString("role", "");
                    if ("system".equals(role) || "developer".equals(role)) {
                        sys.append(String.valueOf(msg.opt("content"))).append('\n');
                    }
                    if (i == m.length() - 1) {
                        lastRole = role;
                        lastToolCalls = msg.opt("tool_calls") instanceof JSONArray;
                    }
                }
            }
            // 工具轮的续写：这一发是"搜索结果回来之后"的正文
            boolean continuation = "tool".equals(lastRole) || "function".equals(lastRole)
                    || lastToolCalls;
            if (continuation) {
                return new Decision(true, false, "工具轮续写：接在上一段后面念，不打断");
            }

            String s = sys.toString().toLowerCase(Locale.US);
            for (String mk : SKIP_MARKERS) {
                if (s.contains(mk)) return new Decision(false, true, "system 提示词含「" + mk + "」，后台小活");
            }
        } catch (Throwable ignored) { }
        return new Decision(true, true, "正常对话：这一段会打断上一段");
    }

    // ══════════════════════════ HTTP 读写 ══════════════════════════
    private static void writeHead(OutputStream out, int code, Map<String, String> headers,
                                  int contentLength, boolean chunked) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(code).append(' ').append(reason(code)).append("\r\n");
        for (Map.Entry<String, String> e : headers.entrySet()) {
            String k = e.getKey();
            String lk = k.toLowerCase(Locale.US);
            if (lk.equals("content-length") || lk.equals("transfer-encoding")
                    || lk.equals("connection")) continue;
            sb.append(k).append(": ").append(e.getValue()).append("\r\n");
        }
        if (chunked) sb.append("Transfer-Encoding: chunked\r\n");
        else sb.append("Content-Length: ").append(contentLength).append("\r\n");
        sb.append("Connection: close\r\n");
        // 放行跨域：网页版客户端（酒馆 / NextChat 等）直连本机时全靠这几行
        sb.append("Access-Control-Allow-Origin: *\r\n");
        sb.append("Access-Control-Allow-Headers: *\r\n");
        sb.append("Access-Control-Allow-Methods: GET, POST, PUT, PATCH, DELETE, OPTIONS\r\n");
        sb.append("Access-Control-Max-Age: 86400\r\n\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    private static void writeChunk(OutputStream out, byte[] b, int off, int len) throws IOException {
        if (len <= 0) return;
        out.write((Integer.toHexString(len) + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
        out.write(b, off, len);
        out.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    private static void endChunks(OutputStream out) throws IOException {
        out.write("0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    private static String reason(int code) {
        switch (code) {
            case 200: return "OK";
            case 201: return "Created";
            case 204: return "No Content";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 429: return "Too Many Requests";
            case 500: return "Internal Server Error";
            case 502: return "Bad Gateway";
            case 503: return "Service Unavailable";
            case 504: return "Gateway Timeout";
            default: return "Status";
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(128);
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') bos.write(c);
            if (bos.size() > 16384) break;
        }
        if (c == -1 && bos.size() == 0) return null;
        return new String(bos.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    private static byte[] readBody(InputStream in, Map<String, String> headers) throws IOException {
        String te = headers.get("transfer-encoding");
        if (te != null && te.toLowerCase(Locale.US).contains("chunked")) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(8192);
            while (true) {
                String sz = readLine(in);
                if (sz == null) break;
                int semi = sz.indexOf(';');
                if (semi >= 0) sz = sz.substring(0, semi);
                sz = sz.trim();
                if (sz.length() == 0) continue;
                int n;
                try { n = Integer.parseInt(sz, 16); } catch (Throwable t) { break; }
                if (n == 0) { readLine(in); break; }
                byte[] b = new byte[n];
                int got = 0;
                while (got < n) {
                    int r = in.read(b, got, n - got);
                    if (r < 0) break;
                    got += r;
                }
                bos.write(b, 0, got);
                readLine(in);
            }
            return bos.toByteArray();
        }
        String cl = headers.get("content-length");
        if (cl == null) return new byte[0];
        int n;
        try { n = Integer.parseInt(cl.trim()); } catch (Throwable t) { return new byte[0]; }
        if (n <= 0) return new byte[0];
        byte[] b = new byte[n];
        int got = 0;
        while (got < n) {
            int r = in.read(b, got, n - got);
            if (r < 0) break;
            got += r;
        }
        return b;
    }

    private static Map<String, String> parseQuery(String q) {
        Map<String, String> m = new HashMap<String, String>();
        if (q == null) return m;
        for (String kv : q.split("&")) {
            int i = kv.indexOf('=');
            try {
                if (i < 0) m.put(URLDecoder.decode(kv, "UTF-8"), "");
                else m.put(URLDecoder.decode(kv.substring(0, i), "UTF-8"),
                        URLDecoder.decode(kv.substring(i + 1), "UTF-8"));
            } catch (Throwable ignored) { }
        }
        return m;
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }
}
