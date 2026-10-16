package com.mermergi.ttsrelay;

import android.content.Context;

/**
 * 进程级的一次性初始化。
 *
 * JS 插件那套（TTS Server 的 lib-script）有两处要「全局上下文」：
 * <ul>
 *   <li>{@code splitties.init.appCtx} —— 它自己用 splitties，那个包是个 ContentProvider 装的全局 Context；
 *       我们没引 splitties，用 {@code kt/ttsrv-script-stub/} 里那个 6 行替身，启动时塞进去</li>
 *   <li>Drake Net 的 {@code NetConfig.initialize} —— 插件的 http 走它（它平时靠 androidx.startup 的
 *       InitializationProvider 自动初始化，我们手搓的 APK 没那套，就直接调它的初始化）</li>
 * </ul>
 */
public final class App {

    private static volatile Context appCtx;

    private App() { }

    public static void install(Context c) {
        if (c == null) return;
        Context a = c.getApplicationContext();
        if (a == null) a = c;
        if (appCtx == null) appCtx = a;

        try {
            splitties.init.AppCtxKt.installAppCtx(a);
        } catch (Throwable t) {
            LogBus.error("装 appCtx 失败：" + t.getMessage());
        }
        try {
            com.drake.net.NetConfig.INSTANCE.initialize("", a, new okhttp3.OkHttpClient.Builder());
        } catch (Throwable t) {
            LogBus.error("装 Drake Net 失败：" + t.getMessage());
        }
    }

    public static Context ctx() {
        Context c = appCtx;
        if (c == null) throw new IllegalStateException("App.install() 还没调用过");
        return c;
    }
}
