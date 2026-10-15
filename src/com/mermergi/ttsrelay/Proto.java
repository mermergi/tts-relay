package com.mermergi.ttsrelay;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;

/**
 * 一点点 protobuf —— 只够用豆包那套帧：
 * <pre>
 *   发：{2:appkey, 3:"VoiceGenie", 5:方法名, 6:JSON载荷, 8:task_id}
 *   收：{4:事件名, 1:task_id, 5:code, 6:msg, 8:音频}
 * </pre>
 * 全是 length-delimited，除了少数 varint 的 code。
 */
public final class Proto {

    /** 一个字段。二进制字段别去读 str（多半是乱码）。 */
    public static final class F {
        public String str = "";
        public byte[] bin;
        public long num;
        public boolean isBinary;
    }

    public static byte[] fstr(int field, String s) {
        byte[] b = (s == null ? "" : s).getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream o = new ByteArrayOutputStream(b.length + 8);
        write(o, varint(((long) field << 3) | 2));
        write(o, varint(b.length));
        o.write(b, 0, b.length);
        return o.toByteArray();
    }

    public static byte[] varint(long v) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(10);
        while (true) {
            int b = (int) (v & 0x7f);
            v >>>= 7;
            if (v != 0) o.write(b | 0x80);
            else { o.write(b); break; }
        }
        return o.toByteArray();
    }

    /** 拼一帧。 */
    public static byte[] frame(String appkey, String ns, String method, String payload, String task) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(256);
        write(o, fstr(2, appkey));
        write(o, fstr(3, ns));
        write(o, fstr(5, method));
        if (payload != null) write(o, fstr(6, payload));
        if (task != null && task.length() > 0) write(o, fstr(8, task));
        return o.toByteArray();
    }

    public static LinkedHashMap<Integer, F> parse(byte[] b) {
        LinkedHashMap<Integer, F> out = new LinkedHashMap<Integer, F>();
        if (b == null) return out;
        int[] p = new int[]{0};
        while (p[0] < b.length) {
            long key = readVarint(b, p);
            if (p[0] < 0) break;
            int field = (int) (key >>> 3);
            int wt = (int) (key & 7);
            F rec = new F();
            if (wt == 0) {
                rec.num = readVarint(b, p);
                if (p[0] < 0) break;
            } else if (wt == 2) {
                long len = readVarint(b, p);
                if (p[0] < 0) break;
                int n = (int) len;
                if (n < 0 || p[0] + n > b.length) break;
                rec.bin = new byte[n];
                System.arraycopy(b, p[0], rec.bin, 0, n);
                rec.str = new String(rec.bin, StandardCharsets.UTF_8);
                p[0] += n;
            } else if (wt == 5) {
                p[0] += 4;
                continue;
            } else if (wt == 1) {
                p[0] += 8;
                continue;
            } else {
                break;
            }
            out.put(field, rec);
        }
        return out;
    }

    private static long readVarint(byte[] b, int[] p) {
        long v = 0;
        int sh = 0;
        while (p[0] < b.length) {
            int c = b[p[0]++] & 0xFF;
            v |= ((long) (c & 0x7f)) << sh;
            if ((c & 0x80) == 0) return v;
            sh += 7;
            if (sh > 63) break;
        }
        p[0] = -1;
        return 0;
    }

    private static void write(ByteArrayOutputStream o, byte[] b) {
        o.write(b, 0, b.length);
    }

    /** 人类可读的字段摘要，出问题看日志用。 */
    public static String dump(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<Integer, F> e : parse(b).entrySet()) {
            F f = e.getValue();
            if (sb.length() > 0) sb.append(' ');
            if (f.bin == null) sb.append(e.getKey()).append('=').append(f.num);
            else if (looksText(f.bin)) sb.append(e.getKey()).append("=\"").append(head(f.str, 40)).append('"');
            else sb.append(e.getKey()).append("=<").append(f.bin.length).append("B>");
        }
        return sb.toString();
    }

    private static boolean looksText(byte[] b) {
        int n = Math.min(b.length, 64);
        for (int i = 0; i < n; i++) {
            int c = b[i] & 0xFF;
            if (c < 0x09 || (c > 0x0d && c < 0x20)) return false;
        }
        return true;
    }

    private static String head(String s, int n) {
        return s.length() > n ? s.substring(0, n) + "…" : s;
    }
}
