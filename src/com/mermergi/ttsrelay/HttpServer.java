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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
    /** 干跑断句用的示例文本：短句、长句、列表、代码块都塞一点。 */
    private static final String SPLIT_DEMO =
            "先说结论。这个问题分三步：\n"
            + "1. 先把 API 地址指过来；\n"
            + "2. 再选一个音色；\n"
            + "3. 剩下的事交给它自己。\n"
            + "```\nprint('这行不该被念')\n```\n"
            + "对了，遇到那种一口气能说六十个字不带标点的长句子的时候，就只能靠字数硬切了，切在哪儿全看退让规则。完了。";

    /** 关键词与阈值现在都能在设置页改，这里只留一份兜底默认。 */
    private static String[] skipMarkers(Context c) {
        String raw = Prefs.skipKeywords(c);
        if (raw == null) return new String[0];
        String[] parts = raw.split(",");
        java.util.List<String> out = new java.util.ArrayList<String>();
        for (String s : parts) {
            String k = s.trim().toLowerCase(Locale.US);
            if (k.length() > 0) out.add(k);
        }
        return out.toArray(new String[0]);
    }

    /** 一次"念不念"的判定结果 + 理由，出问题看日志就知道是谁把嘴捂上的。 */
    static final class Decision {
        final boolean speak;
        /** 要不要打断正在念的上一段。false = 接在它后面排队（同一个回合的后续）。 */
        final boolean cut;
        final String why;
        /** 这一发是谁在说。群聊的提醒里直接写着名字，比从正文里猜靠谱。空 = 照旧从正文认。 */
        final String speaker;
        Decision(boolean s, boolean c, String w) { this(s, c, w, ""); }
        Decision(boolean s, boolean c, String w, String who) {
            speak = s;
            cut = c;
            why = w;
            speaker = who == null ? "" : who;
        }
    }

    private final Context ctx;
    private final Speaker speaker;
    /** 当前监听端口 —— LiveAudio 拼流地址要用（MediaPlayer 只能按 URL 播）。 */
    public static volatile int PORT = com.mermergi.ttsrelay.Prefs.PORT_DEF;

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
        PORT = p;
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
                o.put("speaker", d.speaker);
            } catch (Throwable ignored) { }
            json(out, 200, o.toString());
            return;
        }
        if ("/_editortest".equals(path)) {
            // 本地自检：在主线程真建一个 sora 编辑器，跑和插件编辑器同一条初始化，
            // 把「语言/配色/字号/字数/TextMate 有没有起来」吐成 JSON —— 不用人肉点界面
            final org.json.JSONObject o = new org.json.JSONObject();
            final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                public void run() {
                    try {
                        io.github.rosemoe.sora.widget.CodeEditor ed =
                                new io.github.rosemoe.sora.widget.CodeEditor(ctx);
                        o.put("ctor", "ok");
                        try {
                            com.mermergi.ttsrelay.js.EditorSetup.init(ctx.getAssets(), ed);
                            o.put("textmate", "ok");
                        } catch (Throwable t) {
                            o.put("textmate", t.getClass().getSimpleName() + ": " + t.getMessage());
                        }
                        ed.setText("let a = 1;\nfunction f(){ return 2; }\n// 测试\n");
                        o.put("chars", ed.getText().length());
                        o.put("lines", ed.getText().getLineCount());
                        o.put("lang", String.valueOf(ed.getEditorLanguage()));
                        o.put("sizePx", ed.getTextSizePx());
                        io.github.rosemoe.sora.widget.schemes.EditorColorScheme sc = ed.getColorScheme();
                        o.put("scheme", sc == null ? "null" : sc.getClass().getSimpleName());
                        if (sc != null) {
                            o.put("bg", Integer.toHexString(sc.getColor(
                                    io.github.rosemoe.sora.widget.schemes.EditorColorScheme.WHOLE_BACKGROUND)));
                            o.put("fg", Integer.toHexString(sc.getColor(
                                    io.github.rosemoe.sora.widget.schemes.EditorColorScheme.TEXT_NORMAL)));
                        }
                    } catch (Throwable t) {
                        try { o.put("error", t.toString()); } catch (Throwable ignored) { }
                    }
                    latch.countDown();
                }
            });
            try {
                latch.await(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
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
                o.put("live", LiveAudio.ids());
                o.put("error", LogBus.lastError());
                o.put("chars", speaker.charCount());
                o.put("queue", speaker.queued());
                o.put("last", speaker.lastText());
                o.put("upstream", Prefs.upstream(ctx));
                o.put("engine", Prefs.engine(ctx));
                o.put("voice", Prefs.voice(ctx));
                o.put("tts", Prefs.ttsBase(ctx));
                o.put("splitMode", Prefs.splitMode(ctx));
                o.put("splitModeName", Splitter.modeName(Prefs.splitMode(ctx)));
                EngineStore.Item ae = EngineStore.get(ctx, EngineStore.activeId(ctx));
                o.put("ttsEngine", ae == null ? "?" : ae.label);
                o.put("ttsEngineId", EngineStore.activeId(ctx));
                o.put("ttsCrashedRecently", TtsReviver.crashedJustNow());
                o.put("autoRevive", Prefs.autoRevive(ctx));
                o.put("ttsInflight", TtsClient.inflight());
                o.put("ttsInflightMax", TtsClient.inflightMax());
                o.put("ttsPaceMs", speaker.pace());
            } catch (Throwable ignored) { }
            json(out, 200, o.toString());
            return;
        }
        if ("/_import".equals(path)) {
            // 本地调试/命令行导入。?text=<urlencoded JSON>，或者 POST 正文直接丢 JSON
            try {
                String text = qs.get("text");
                if (text == null || text.trim().length() == 0) {
                    text = new String(body == null ? new byte[0] : body, java.nio.charset.StandardCharsets.UTF_8);
                }
                java.util.List<EngineStore.Item> list = EngineImport.parse(text);
                JSONArray arr = new JSONArray();
                for (EngineStore.Item it : list) {
                    EngineStore.put(ctx, it);
                    arr.put(it.label + "  [" + it.kind + "]");
                }
                json(out, 200, "{\"imported\":" + list.size() + ",\"engines\":" + arr + "}");
            } catch (Throwable t) {
                json(out, 400, "{\"error\":\"" + esc(String.valueOf(t.getMessage())) + "\"}");
            }
            return;
        }
        if ("/_export".equals(path)) {
            String fmt = qs.get("fmt");
            String txt = "legado".equals(fmt)
                    ? EngineImport.exportLegado(EngineStore.all(ctx))
                    : EngineImport.exportOurs(EngineStore.all(ctx));
            try {
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\n"
                        + "Content-Length: " + txt.getBytes("UTF-8").length + "\r\n"
                        + "Access-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n")
                        .getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
                out.write(txt.getBytes("UTF-8"));
                out.flush();
            } catch (Throwable ignored) { }
            return;
        }
        if ("/_engine".equals(path)) {
            // 本地调试：切引擎 / 改某个引擎的配置项。?id=<引擎 id> 切过去；?k=cookie&v=xxx 改配置
            String del = qs.get("del");
            if (del != null && del.trim().length() > 0) {
                EngineStore.remove(ctx, del.trim());
                json(out, 200, "{\"deleted\":\"" + esc(del.trim()) + "\"}");
                return;
            }
            String id = qs.get("id");
            if (id != null && id.trim().length() > 0) EngineStore.setActive(ctx, id.trim());
            String k = qs.get("k");
            String v = qs.get("v");
            if (k != null && v != null) {
                EngineStore.Item it = EngineStore.get(ctx, EngineStore.activeId(ctx));
                if (it != null) {
                    if ("timeout".equals(k) || "sample_rate".equals(k)) {
                        try { it.put(k, Integer.parseInt(v.trim())); } catch (Throwable ignored) { }
                    } else {
                        it.put(k, v);
                    }
                    EngineStore.put(ctx, it);
                    JsTtsEngine.clearHosts();      // 配置改了：插件宿主里的旧变量别再用
                }
            }
            EngineStore.Item cur = EngineStore.get(ctx, EngineStore.activeId(ctx));
            json(out, 200, "{\"active\":\"" + EngineStore.activeId(ctx) + "\",\"label\":\""
                    + esc(cur == null ? "?" : cur.label) + "\"}");
            return;
        }
        if ("/_engines".equals(path)) {
            JSONObject o = new JSONObject();
            try {
                o.put("active", EngineStore.activeId(ctx));
                JSONArray arr = new JSONArray();
                for (EngineStore.Item e : EngineStore.all(ctx)) {
                    JSONObject x = new JSONObject();
                    x.put("id", e.id);
                    x.put("label", e.label);
                    x.put("kind", e.kind);
                    String prob = EngineStore.build(e).problem(ctx);
                    x.put("problem", prob == null ? JSONObject.NULL : prob);
                    arr.put(x);
                }
                o.put("engines", arr);
            } catch (Throwable ignored) { }
            json(out, 200, o.toString());
            return;
        }
        if ("/_voices".equals(path)) {
            // 当前引擎的音色目录（手工 + voices_url + 引擎自带，三路合并）；?id=xxx 看指定引擎
            try {
                String id = qs.get("id");
                // 不带 id = 列出「所有启用中的引擎」的音色（照它：音色集合才是选引擎的入口）
                List<String> all = (id == null || id.length() == 0)
                        ? EngineStore.fetchVoicesAll(ctx)
                        : EngineStore.fetchVoices(ctx, EngineStore.get(ctx, id));
                EngineStore.Item it = EngineStore.get(ctx, EngineStore.activeId(ctx));
                JSONArray arr = new JSONArray();
                boolean grouped = "1".equals(qs.get("grouped"));
                for (String v : all) {
                    if (!grouped) { arr.put(v); continue; }
                    // grouped=1：把标签拆出来，验证「按男女分组」用的就是这份数据
                    JSONObject o = new JSONObject();
                    String raw = v;
                    int sp = raw.indexOf(EngineStore.SEP);
                    String idPart = sp > 0 ? raw.substring(sp + 1) : "";
                    String disp = (sp > 0 ? raw.substring(0, sp) : raw).replaceAll("_\\d+$", "").trim();
                    String name = disp, tags = "";
                    int ls = disp.lastIndexOf(' ');
                    if (ls > 0 && disp.substring(ls + 1).contains("|")) {
                        name = disp.substring(0, ls);
                        tags = disp.substring(ls + 1);
                    }
                    String gender = "未标注";
                    if ("女".equals(tags.split("\\|")[0]) || tags.contains("女|") || tags.startsWith("女")) gender = "女声";
                    else if (tags.startsWith("男")) gender = "男声";
                    o.put("name", name);
                    o.put("tags", tags);
                    o.put("gender", gender);
                    o.put("id", idPart);
                    arr.put(o);
                }
                json(out, 200, arr.toString());
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
        if ("/_split".equals(path)) {
            // 干跑断句：同一段文字，不同方案切出来什么鬼样子，一看就明白。
            // 按 3 字一段喂进去，模拟真实 SSE 的碎块 —— 增量逻辑也一并验了。
            String t = qs.get("text");
            if (t == null || t.length() == 0) t = SPLIT_DEMO;
            int mode = Prefs.splitMode(ctx);
            String mv = qs.get("mode");
            if (mv != null && mv.length() > 0) {
                try { mode = Integer.parseInt(mv.trim()); } catch (Throwable ignored) { }
            }
            mode = Splitter.clampMode(mode);
            int chunk = Prefs.maxChunk(ctx);
            String cv = qs.get("chunk");
            if (cv != null && cv.length() > 0) {
                try { chunk = Integer.parseInt(cv.trim()); } catch (Throwable ignored) { }
            }
            // roles=1 → 按角色切，顺带把多语音规则验一遍
            boolean wantRoles = "1".equals(qs.get("roles")) || "true".equals(qs.get("roles"));
            Splitter sp = new Splitter(chunk, 4, mode);
            MultiVoice mvo = new MultiVoice(chunk, 4, mode, true, Prefs.parseMode(ctx));
            // as=名字 → 假装群聊提醒说"这一发是他"，用来验"没打标签的行归谁"
            String as = qs.get("as");
            if (as != null && as.trim().length() > 0) mvo.setTurnName(as.trim());
            JSONArray parts = new JSONArray();
            try {
                for (int i = 0; i < t.length(); i += 3) {
                    String piece = t.substring(i, Math.min(t.length(), i + 3));
                    if (wantRoles) {
                        for (MultiVoice.Seg g : mvo.feed(piece)) parts.put(tagVoice(ctx, g) + "\t" + g.text);
                    } else {
                        for (String s : sp.feed(piece)) parts.put(s);
                    }
                }
                if (wantRoles) {
                    for (MultiVoice.Seg g : mvo.flush()) parts.put(tagVoice(ctx, g) + "\t" + g.text);
                } else {
                    for (String s : sp.flush()) parts.put(s);
                }
                JSONObject o = new JSONObject();
                o.put("mode", mode);
                o.put("modeName", Splitter.modeName(mode));
                o.put("modeDesc", Splitter.modeDesc(mode));
                o.put("maxChunk", Math.max(20, chunk));
                o.put("roles", wantRoles);
                o.put("parts", parts);
                o.put("count", parts.length());
                json(out, 200, o.toString());
            } catch (Throwable t2) {
                json(out, 500, "{\"error\":\"" + esc(t2.getMessage()) + "\"}");
            }
            return;
        }
        if ("/_say".equals(path)) {
            String t = qs.get("text");
            if (t == null || t.length() == 0) t = "测试一下，能听见吗？";
            boolean roles = "1".equals(qs.get("roles")) || "true".equals(qs.get("roles"));
            if (roles) {
                // 走完整的多语音链路：分段 → 查音色表 → 合成
                MultiVoice mvo = newMultiVoice(qs.get("as") == null ? "" : qs.get("as").trim());
                boolean[] started = new boolean[]{false};
                int[] sent = new int[]{0};
                emit(mvo.feed(t), started, sent, true);
                emit(mvo.flush(), started, sent, true);
                json(out, 200, "{\"ok\":true,\"parts\":" + sent[0] + "}");
            } else {
                speaker.feed(t);
                json(out, 200, "{\"ok\":true}");
            }
            return;
        }
        if (path.startsWith("/_live/")) {
            // 边下边播：插件还在吐字节，这里就把已经拿到的部分推给 MediaPlayer。
            // chunked + 不写 Content-Length = progressive 流，读到哪放到哪。
            final LiveAudio live = LiveAudio.get(path.substring("/_live/".length()).trim());
            if (live == null) {
                json(out, 404, "{\"error\":\"没有这条流（已经播完了？）\"}");
                return;
            }
            try {
                Map<String, String> h = new LinkedHashMap<String, String>();
                h.put("Content-Type", live.contentType(8000));   // 等第一批字节，好认出 mp3/wav
                h.put("Cache-Control", "no-store");
                h.put("icy-name", "tts-relay");
                writeHeadStream(out, 200, h);
                live.pump(new LiveAudio.Sink() {
                    public void chunk(byte[] d, int o, int l) throws IOException {
                        out.write(d, o, l);
                        out.flush();
                    }
                });
                out.flush();
            } catch (Throwable t) {
                // 播放器提前断开是常事（切下一句），别当错误刷日志
            } finally {
                LiveAudio.drop(live);
            }
            return;
        }
        if ("/_names".equals(path)) {
            // 本地调试：读写「音色 → 名称」（?text=... 或 POST 正文；一行一条：音色<TAB>名字|名字）
            try {
                String text = qs.get("text");
                if (text == null) text = new String(body == null ? new byte[0] : body, java.nio.charset.StandardCharsets.UTF_8);
                if (text != null && text.trim().length() > 0) Prefs.setVoiceNames(ctx, text);
                JSONObject o = new JSONObject();
                JSONArray arr = new JSONArray();
                for (java.util.Map.Entry<String, String> e : Prefs.voiceNamesMap(ctx).entrySet()) {
                    arr.put(TtsEngine.shortVoice(e.getKey()) + "  ⇐  " + e.getValue());
                }
                o.put("bindings", arr);
                json(out, 200, o.toString());
            } catch (Throwable t) {
                json(out, 400, "{\"error\":\"" + esc(String.valueOf(t.getMessage())) + "\"}");
            }
            return;
        }
        if ("/_mv".equals(path)) {
            // 本地调试：读写角色音色表。音色传 "-" = 不念，传空 = 跟随全局
            String on = qs.get("on");
            if (on != null) Prefs.setMvEnabled(ctx, "1".equals(on) || "true".equals(on));
            String md = qs.get("mode");
            if (md != null) {
                try { Prefs.setParseMode(ctx, MultiVoice.clampMode(Integer.parseInt(md.trim()))); }
                catch (Throwable ignored) { }
            }
            String key = qs.get("k");
            String val = qs.get("v");
            if (key != null && key.trim().length() > 0) {
                if (val == null) Prefs.removeVoice(ctx, key.trim());
                else Prefs.putVoice(ctx, key.trim(), "-".equals(val) ? MultiVoice.SKIP : val);
            }
            String del = qs.get("del");
            if (del != null) Prefs.removeVoice(ctx, del.trim());

            JSONObject o = new JSONObject();
            try {
                o.put("enabled", Prefs.mvEnabled(ctx));
                o.put("mode", Prefs.parseMode(ctx));
                o.put("modeName", MultiVoice.MODE_NAMES[Prefs.parseMode(ctx)]);
                JSONObject tab = new JSONObject();
                for (java.util.Map.Entry<String, String> e : Prefs.voiceTableMap(ctx).entrySet()) {
                    String v = e.getValue();
                    tab.put(e.getKey(), MultiVoice.SKIP.equals(v) ? "-skip-"
                            : (v.length() == 0 ? "-global-" : v));
                }
                o.put("table", tab);
                o.put("global", Prefs.voice(ctx).length() == 0 ? "-default-" : Prefs.voice(ctx));
            } catch (Throwable ignored) { }
            json(out, 200, o.toString());
            return;
        }
        if ("/_selftest".equals(path)) {
            Splitter sp = new Splitter(Prefs.maxChunk(ctx), 4, Prefs.splitMode(ctx));
            String fake = "哥哥，这是自检。"
                    + "\n```python\nprint('这段代码不该被念出来')\n```\n"
                    + "如果你听到了这句话，说明断句、跳过代码块、合成、播放整条链路都通了。";
            List<String> parts = new ArrayList<String>();
            for (int i = 0; i < fake.length(); i++) {
                parts.addAll(sp.feed(String.valueOf(fake.charAt(i))));
            }
            parts.addAll(sp.flush());
            for (String s : parts) speaker.feed(s);
            LogBus.add("自检 · 断句方案「" + Splitter.modeName(Prefs.splitMode(ctx))
                    + "」切成 " + parts.size() + " 块：");
            for (String s : parts) LogBus.add("   ▸ " + s);
            json(out, 200, "{\"ok\":true,\"msg\":\"自检音频已排队\",\"modeName\":\""
                    + esc(Splitter.modeName(Prefs.splitMode(ctx))) + "\",\"count\":" + parts.size() + "}");
            return;
        }
        if ("/_revive".equals(path)) {
            String r = TtsReviver.revive(ctx, "1".equals(qs.get("force")));
            json(out, 200, "{\"ok\":true,\"result\":\"" + esc(r) + "\"}");
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
                streamSse(out, conn, code, outHeaders, speak, turnStarted, dec.cut, dec.speaker);
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
                                MultiVoice mv = newMultiVoice(dec.speaker);
                                int[] sent = new int[]{0};
                                emit(mv.feed((String) c), turnStarted, sent, dec.cut);
                                emit(mv.flush(), turnStarted, sent, dec.cut);
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
                           boolean cut, String who) throws IOException {
        writeHead(out, code, headers, -1, true);

        InputStream in = conn.getInputStream();
        MultiVoice mv = newMultiVoice(who);
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
                sseLine(l, mv, started, sent, cut);
            }
        }
        if (speak) {
            if (lineBuf.length() > 0) sseLine(lineBuf.toString(), mv, started, sent, cut);
            emit(mv.flush(), started, sent, cut);
            // 这条请求到底吐了几句正文？—— 和请求日志对起来就知道是哪一发在发声
            LogBus.add("↳ 本轮念出 " + sent[0] + " 句");
        }
        endChunks(out);
    }

    /** 断句器：多语音开着就按角色切，关着就跟以前一模一样。 */
    private MultiVoice newMultiVoice() { return newMultiVoice(""); }

    /** who 非空 = 请求里已经写明这一发是谁在说（群聊提醒），不用再从正文猜。 */
    private MultiVoice newMultiVoice(String who) {
        MultiVoice mv = new MultiVoice(Prefs.maxChunk(ctx), 4, Prefs.splitMode(ctx),
                Prefs.mvEnabled(ctx), Prefs.parseMode(ctx));
        mv.setTurnName(who);
        // 音色表里点名过的角色名交给解析器 —— 这样"小雪"就算写成单独一行、
        // 或者「小雪：（动作）」这种，它都认得出是这个人，而不会全丢给旁白。
        java.util.List<String> names = new java.util.ArrayList<String>();
        for (java.util.Map.Entry<String, String> e : Prefs.voiceTableMap(ctx).entrySet()) {
            if (MultiVoice.isReservedKey(e.getKey())) continue;
            if (MultiVoice.SKIP.equals(e.getValue())) continue;
            names.add(e.getKey());
        }
        mv.setKnownNames(names);
        return mv;
    }

    /**
     * 真的凑出可念的句子了，才去打断上一段 —— 这是"没声音"那个坑的正解。
     * 多语音时逐段查音色表：设成「不念」的角色直接丢掉（连打断都不算）。
     */
    private void emit(java.util.List<MultiVoice.Seg> segs, boolean[] started, int[] sent,
                      boolean cut) {
        if (segs == null || segs.isEmpty()) return;
        for (MultiVoice.Seg seg : segs) {
            String voice = MultiVoice.voiceFor(ctx, seg);
            if (voice == null) continue;              // 这个角色设成了「不念」
            if (!started[0]) {
                started[0] = true;
                // 同一个回合的工具续写：接在后面念，不腰斩正在播的那一句
                if (cut) speaker.interrupt();
            }
            speaker.feed(seg.text, voice);
            sent[0]++;
        }
    }

    private void sseLine(String line, MultiVoice mv, boolean[] started, int[] sent, boolean cut) {
        line = line.trim();
        if (!line.startsWith("data:")) return;
        String payload = line.substring(5).trim();
        if (payload.length() == 0 || "[DONE]".equals(payload)) {
            emit(mv.flush(), started, sent, cut);
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
                    if (v instanceof String) emit(mv.feed((String) v), started, sent, cut);
                }
                JSONObject m = c.optJSONObject("message");
                if (m != null) {
                    Object v = m.opt("content");
                    if (v instanceof String) emit(mv.feed((String) v), started, sent, cut);
                }
            }
        } catch (Throwable ignored) { }
    }

    private static String tag(MultiVoice.Seg g) {
        return g.name.length() == 0 ? g.role : g.role + ":" + g.name;
    }

    /** 干跑时把"这句会用谁的音色"也带上 —— 规则改完不用真念就能验。 */
    private static String tagVoice(Context c, MultiVoice.Seg g) {
        String v = MultiVoice.voiceFor(c, g);
        return tag(g) + (v == null ? " → （不念）" : " → " + TtsEngine.shortVoice(v));
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
     *   ③ "起标题"关键词只在 system 提示词里找、且仅当客户端没设输出额度时生效，
     *      绝不碰用户和工具正文 —— 设了额度的正经回复不会被「标题」这类词误杀。
     */
    private Decision decide(byte[] body) {
        if (Prefs.muted(ctx)) return new Decision(false, true, "已静音");
        try {
            JSONObject o = new JSONObject(new String(body, StandardCharsets.UTF_8));
            // 「跳过后台小活」只管"念不念"，不该顺手把"打断不打断"也锁死 ——
            // 之前它一关就直接 cut=true，群聊里每个人一开口就把上一个人腰斩。
            return decideShape(shapeOf(o), Prefs.skipSmall(ctx),
                    Prefs.smallTokenLimit(ctx), skipMarkers(ctx));
        } catch (Throwable ignored) { }
        return new Decision(true, true, "正常对话：这一段会打断上一段");
    }

    /** 判定只看这几个字段。抽出来是为了能脱开 Android 的 org.json 离线验。 */
    static final class Shape {
        int mt = -1;
        int mct = -1;
        String lastRole = "";
        String prevRole = "";
        String lastText = "";
        String sysText = "";
        boolean lastToolCalls;
    }

    static Shape shapeOf(JSONObject o) {
        Shape sh = new Shape();
        sh.mt = o.optInt("max_tokens", -1);
        sh.mct = o.optInt("max_completion_tokens", -1);
        StringBuilder sys = new StringBuilder();
        JSONArray m = o.optJSONArray("messages");
        if (m != null) {
            for (int i = 0; i < m.length(); i++) {
                JSONObject msg = m.optJSONObject(i);
                if (msg == null) continue;
                String role = msg.optString("role", "");
                if ("system".equals(role) || "developer".equals(role)) {
                    sys.append(String.valueOf(msg.opt("content"))).append('\n');
                }
                if (i == m.length() - 1) {
                    sh.lastRole = role;
                    sh.lastText = String.valueOf(msg.opt("content"));
                    sh.lastToolCalls = msg.opt("tool_calls") instanceof JSONArray;
                } else if (i == m.length() - 2) {
                    sh.prevRole = role;
                }
            }
        }
        sh.sysText = sys.toString();
        return sh;
    }

    /**
     * 判定本体：纯函数，不碰 Context，所以能离线拿真请求形状一条条验。
     *
     *   ① 额度特别小的请求 = 客户端在后台起标题/写摘要，不念（{@code applySkip} 关掉时不管这条）；
     *   ② 末条是 tool 结果 / 带 tool_calls 的 assistant = 工具轮的续写，必须念、排队不打断；
     *   ③ 酒馆群聊「自动回复」是一人一发：每发前面插一条 system 提醒（nudge）让下一个人说话。
     *      末条是这条提醒 = 同一轮换个人接着说 → 排队不打断，名字直接从提醒里拿；
     *      但提醒前面是用户刚发的话，那就是新的一轮 → 该打断；
     *   ④ 末条是 assistant = 酒馆的「继续」/ 没插提醒的续写 → 排队不打断；
     *   ⑤ "起标题"关键词只扫 system 提示词、且仅当客户端没设输出额度时生效。
     */
    static Decision decideShape(Shape sh, boolean applySkip, int small, String[] markers) {
        if (applySkip) {
            if (sh.mt > 0 && sh.mt < small) {
                return new Decision(false, true, "max_tokens=" + sh.mt + "，后台小活");
            }
            if (sh.mct > 0 && sh.mct < small) {
                return new Decision(false, true, "max_completion_tokens=" + sh.mct + "，后台小活");
            }
        }

        if ("tool".equals(sh.lastRole) || "function".equals(sh.lastRole) || sh.lastToolCalls) {
            return new Decision(true, false, "工具轮续写：接在上一段后面念，不打断");
        }

        boolean sameTurn = "assistant".equals(sh.prevRole) || "tool".equals(sh.prevRole)
                || "function".equals(sh.prevRole);
        if ("system".equals(sh.lastRole) || "developer".equals(sh.lastRole)) {
            String who = speakerFromNudge(sh.lastText);
            if (who.length() > 0) {
                return new Decision(true, !sameTurn,
                        "群聊：" + (sameTurn ? "下一个人接着说" : "你刚发了新话，开头这一发")
                                + "，这一发是「" + who + "」"
                                + (sameTurn ? " → 接在后面念，不打断" : " → 打断上一段"),
                        who);
            }
            if (sameTurn) {
                return new Decision(true, false,
                        "末条是 system 提醒、前一条是回复：同一轮的后续，接在后面念");
            }
        }

        if ("assistant".equals(sh.lastRole)) {
            return new Decision(true, false, "末条是上一条回复：接着往后写，接在后面念，不打断");
        }

        if (applySkip && sh.mt <= 0 && sh.mct <= 0) {
            String sy = sh.sysText.toLowerCase(Locale.US);
            for (String mk : markers) {
                if (sy.contains(mk)) return new Decision(false, true, "system 提示词含「" + mk + "」，后台小活");
            }
        }
        return new Decision(true, true, applySkip ? "正常对话：这一段会打断上一段"
                : "正常对话：这一段会打断上一段（跳过规则已关，只判打断）");
    }

    /**
     * 酒馆群聊的提醒长这样：{@code [Write the next reply only as 小明.]}
     * （模板里的 {{char}} 会换成"这一发该谁说话"）。它等于直接告诉我们说话人是谁。
     * 文案用户能改，认不出就返回空串，那会儿再退回正文里的「名字: 」前缀识别。
     */
    static String speakerFromNudge(String content) {
        if (content == null) return "";
        String low = content.toLowerCase(Locale.US);
        int at = low.indexOf("reply only as");
        if (at < 0) return "";
        String tail = content.substring(at + "reply only as".length()).trim();
        while (tail.length() > 0) {
            char c = tail.charAt(tail.length() - 1);
            if (c == ']' || c == '.' || c == '\u3002' || c == ' ' || c == '\t'
                    || c == '\r' || c == '\n') {
                tail = tail.substring(0, tail.length() - 1).trim();
            } else {
                break;
            }
        }
        if (tail.length() == 0 || tail.length() > 40) return "";
        return tail;
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

    /**
     * 流式响应的头部：<b>不写</b> Content-Length，也<b>不用</b> chunked ——
     * 就用"连接关掉 = 数据完了"这种最老的写法。MediaPlayer 对 chunked 会报
     * MEDIA_ERROR_UNKNOWN，网络电台那一套（ICY）都是这个形式，它才认。
     */
    private static void writeHeadStream(OutputStream out, int code, Map<String, String> headers)
            throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(code).append(' ').append(reason(code)).append("\r\n");
        for (Map.Entry<String, String> e : headers.entrySet()) {
            String lk = e.getKey().toLowerCase(Locale.US);
            if (lk.equals("content-length") || lk.equals("transfer-encoding")
                    || lk.equals("connection")) continue;
            sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
        }
        sb.append("Connection: close\r\n\r\n");
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
