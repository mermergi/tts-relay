package com.mermergi.ttsrelay;

import android.content.Context;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import java.io.File;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 用手机自带的系统语音合成 —— 本地、离线、不要网络、不会把别的 App 带崩。
 * 音色取决于你装了什么语音引擎（小米/讯飞/Google…）。
 */
public final class SystemTtsEngine implements TtsEngine {

    private final EngineStore.Item it;

    /** 系统 TTS 初始化很贵，整个进程留一份。 */
    private static volatile TextToSpeech TTS;
    private static volatile boolean ready = false;
    private static volatile String readyPkg = "";
    private static final Object LOCK = new Object();

    public SystemTtsEngine(EngineStore.Item item) { this.it = item; }

    @Override public String kind() { return EngineStore.KIND_SYSTEM; }

    @Override public String label() {
        return it != null && it.label.length() > 0 ? it.label : "手机系统语音";
    }

    @Override public String problem(Context c) {
        // 注意：这里**绝对不能**去 init —— 它会等系统语音初始化（最长 8 秒），
        // 而界面刷新是在主线程上问这个问题的，一 init 就 ANR。
        // 真起不来的时候，synth() 会带着原因抛出来。
        return null;
    }

    // ───────────────────────── 初始化 ─────────────────────────

    private String pkg() { return it == null ? "" : it.s("pkg", ""); }

    private boolean init(Context c) {
        String want = pkg();
        synchronized (LOCK) {
            if (TTS != null && ready && want.equals(readyPkg)) return true;
            if (TTS != null) {
                try { TTS.shutdown(); } catch (Throwable ignored) { }
                TTS = null;
                ready = false;
            }
            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicReference<Boolean> ok = new AtomicReference<Boolean>(false);
            try {
                TTS = want.length() > 0
                        ? new TextToSpeech(c.getApplicationContext(),
                        new TextToSpeech.OnInitListener() {
                            public void onInit(int status) {
                                ok.set(status == TextToSpeech.SUCCESS);
                                latch.countDown();
                            }
                        }, want)
                        : new TextToSpeech(c.getApplicationContext(),
                        new TextToSpeech.OnInitListener() {
                            public void onInit(int status) {
                                ok.set(status == TextToSpeech.SUCCESS);
                                latch.countDown();
                            }
                        });
            } catch (Throwable t) {
                LogBus.error("系统语音初始化失败：" + t.getMessage());
                TTS = null;
                return false;
            }
            try {
                if (!latch.await(8, TimeUnit.SECONDS)) {
                    LogBus.error("系统语音初始化超时");
                    return false;
                }
            } catch (InterruptedException e) {
                return false;
            }
            ready = Boolean.TRUE.equals(ok.get());
            readyPkg = want;
            if (ready) applyLang(c);
            return ready;
        }
    }

    private void applyLang(Context c) {
        TextToSpeech t = TTS;
        if (t == null) return;
        String lang = it == null ? "zh-CN" : it.s("lang", "zh-CN");
        try {
            Locale l = Locale.forLanguageTag(lang);
            int r = t.setLanguage(l);
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                t.setLanguage(Locale.CHINA);
            }
        } catch (Throwable ignored) { }
    }

    // ───────────────────────── 合成 ─────────────────────────

    @Override
    public byte[] synth(Context c, String text, Say say) throws Exception {
        if (!init(c)) throw new IllegalStateException("系统语音引擎不可用");
        TextToSpeech t = TTS;
        if (t == null) throw new IllegalStateException("系统语音引擎不可用");

        synchronized (LOCK) {
            applyLang(c);
            float rate = say == null ? 1f : say.rate;
            float pitch = say == null ? 1f : say.pitch;
            // 系统引擎只吃 0.1~3
            t.setSpeechRate(clamp(rate, 0.1f, 3f));
            t.setPitch(clamp(pitch, 0.1f, 3f));
            applyVoice(t, say == null ? "" : say.voice);

            File out = new File(c.getCacheDir(), "sys_" + System.nanoTime() + ".wav");
            final CountDownLatch done = new CountDownLatch(1);
            final AtomicReference<String> err = new AtomicReference<String>("");
            String utt = "relay-" + System.nanoTime();

            t.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                public void onStart(String id) { }
                public void onDone(String id) { done.countDown(); }
                @Deprecated public void onError(String id) {
                    err.set("系统语音报错");
                    done.countDown();
                }
                public void onError(String id, int code) {
                    err.set("系统语音报错 code=" + code);
                    done.countDown();
                }
            });

            Bundle params = new Bundle();
            float vol = say == null ? 1f : say.volume;
            if (vol > 0f && vol < 1f) {
                params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, clamp(vol, 0f, 1f));
            }
            int r = t.synthesizeToFile(text, params, out, utt);
            if (r != TextToSpeech.SUCCESS) {
                //noinspection ResultOfMethodCallIgnored
                out.delete();
                throw new IllegalStateException("synthesizeToFile 直接被拒（" + r + "）");
            }
            boolean fin;
            try {
                fin = done.await(60, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                fin = false;
            }
            if (!fin) {
                //noinspection ResultOfMethodCallIgnored
                out.delete();
                throw new IllegalStateException("系统语音合成超时");
            }
            if (err.get().length() > 0) {
                //noinspection ResultOfMethodCallIgnored
                out.delete();
                throw new IllegalStateException(err.get());
            }
            byte[] data = readAll(out);
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            if (data.length < 44) throw new IllegalStateException("系统语音没吐出音频（"
                    + data.length + " 字节）");
            return data;
        }
    }

    private void applyVoice(TextToSpeech t, String name) {
        if (name == null || name.length() == 0) return;
        try {
            Set<Voice> vs = t.getVoices();
            if (vs == null) return;
            for (Voice v : vs) {
                if (v.getName().equals(name)) { t.setVoice(v); return; }
            }
        } catch (Throwable ignored) { }
    }

    private static float clamp(float v, float lo, float hi) {
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }

    private static byte[] readAll(File f) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(256 * 1024);
        FileInputStream in = new FileInputStream(f);
        try {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        } finally {
            try { in.close(); } catch (Throwable ignored) { }
        }
        return bos.toByteArray();
    }

    // ───────────────────────── 音色 ─────────────────────────

    @Override
    public List<String> voices(Context c) {
        List<String> out = new ArrayList<String>();
        if (!init(c)) return out;
        TextToSpeech t = TTS;
        if (t == null) return out;
        try {
            Set<Voice> vs = t.getVoices();
            if (vs == null) return out;
            for (Voice v : vs) {
                Locale l = v.getLocale();
                String tag = l == null ? "" : l.toLanguageTag();
                if (!tag.startsWith("zh")) continue;            // 只留中文，列表才不像垃圾堆
                String n = v.getName();
                if (n == null || n.length() == 0) continue;
                if (!out.contains(n)) out.add(n);
            }
            if (out.isEmpty()) {
                for (Voice v : vs) {
                    String n = v.getName();
                    if (n != null && n.length() > 0 && !out.contains(n)) out.add(n);
                }
            }
        } catch (Throwable ignored) { }
        return out;
    }
}
