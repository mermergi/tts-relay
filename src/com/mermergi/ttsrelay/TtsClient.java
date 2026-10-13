package com.mermergi.ttsrelay;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/** 跟 TTS Server 的「转发器」说话：要音频、要音色列表。 */
public final class TtsClient {

    private static String q(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    public static byte[] synth(Context c, String text) throws IOException {
        String url = Prefs.ttsBase(c).replaceAll("/+$", "")
                + "/api/tts?text=" + q(text)
                + "&engine=" + q(Prefs.engine(c))
                + "&rate=" + q(Prefs.rate(c))
                + "&pitch=" + q(Prefs.pitch(c));
        String voice = Prefs.voice(c);
        if (voice != null && voice.length() > 0) url += "&voice=" + q(voice);
        HttpURLConnection conn = open(url);
        try {
            int code = conn.getResponseCode();
            if (code != 200) {
                InputStream es = conn.getErrorStream();
                byte[] err = es == null ? new byte[0] : readAll(es, 4096);
                throw new IOException("TTS HTTP " + code + " " + preview(err));
            }
            byte[] data = readAll(conn.getInputStream(), 32 * 1024 * 1024);
            if (!looksLikeAudio(data)) {
                // 200 但给的不是音频 —— 这里最容易被误当成"没声音"
                throw new IOException("TTS 返回的不是音频（" + data.length + " 字节）：" + preview(data));
            }
            return data;
        } finally {
            conn.disconnect();
        }
    }

    /** WAV 头，或 MP3 头。 */
    public static boolean looksLikeAudio(byte[] b) {
        if (b == null || b.length < 44) return false;
        if (b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'A' && b[10] == 'V' && b[11] == 'E') return true;
        if (b[0] == 'I' && b[1] == 'D' && b[2] == '3') return true;               // MP3 带 ID3
        int b0 = b[0] & 0xFF, b1 = b[1] & 0xFF;
        return b0 == 0xFF && (b1 & 0xE0) == 0xE0;                                 // MP3 裸帧
    }

    public static String preview(byte[] b) {
        if (b == null || b.length == 0) return "(空)";
        int n = Math.min(b.length, 160);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            int ch = b[i] & 0xFF;
            sb.append(ch >= 32 && ch < 127 ? (char) ch : '.');
        }
        return sb.toString();
    }

    public static String voices(Context c) throws IOException {
        String url = Prefs.ttsBase(c).replaceAll("/+$", "")
                + "/api/voices?engine=" + q(Prefs.engine(c));
        HttpURLConnection conn = open(url);
        try {
            if (conn.getResponseCode() != 200) throw new IOException("voices HTTP " + conn.getResponseCode());
            byte[] b = readAll(conn.getInputStream(), 4 * 1024 * 1024);
            return new String(b, "UTF-8");
        } finally {
            conn.disconnect();
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(60000);
        conn.setRequestMethod("GET");
        conn.setUseCaches(false);
        return conn;
    }

    public static byte[] readAll(InputStream in, int cap) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(64 * 1024);
        byte[] buf = new byte[16384];
        int n, total = 0;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > cap) break;
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }
}
