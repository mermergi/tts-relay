package com.mermergi.ttsrelay;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import android.view.View;
import android.widget.ImageView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 插件音色图标的小加载器：插件在 {@code getVoices()} 里会给 {@code iconUrl}，
 * 这里按 URL 缓存、后台拉、回来后对一下 tag（列表复用，别把图标贴到别的行上）。
 *
 * <p>没必要为几张 36dp 的小图拉 Coil/Glide —— 那套是给整屏图片列表用的。
 */
final class Icons {

    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(64);
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2);

    private Icons() { }

    static void load(final String url, final ImageView iv) {
        if (iv == null) return;
        if (url == null || url.trim().length() == 0) {
            iv.setTag(null);
            iv.setVisibility(View.GONE);
            return;
        }
        iv.setVisibility(View.VISIBLE);
        iv.setTag(url);
        Bitmap hit = CACHE.get(url);
        if (hit != null) { iv.setImageBitmap(hit); return; }
        iv.setImageDrawable(null);
        POOL.execute(new Runnable() {
            public void run() {
                Bitmap bmp = null;
                InputStream in = null;
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(12000);
                    c.setRequestProperty("User-Agent", "tts-relay");
                    in = c.getInputStream();
                    bmp = BitmapFactory.decodeStream(in);
                } catch (Throwable ignored) {
                } finally {
                    try { if (in != null) in.close(); } catch (Throwable ignored) { }
                    try { if (c != null) c.disconnect(); } catch (Throwable ignored) { }
                }
                if (bmp == null) return;
                CACHE.put(url, bmp);
                final Bitmap fb = bmp;
                iv.post(new Runnable() {
                    public void run() {
                        if (url.equals(iv.getTag())) iv.setImageBitmap(fb);
                    }
                });
            }
        });
    }
}
