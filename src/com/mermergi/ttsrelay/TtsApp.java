package com.mermergi.ttsrelay;

import android.app.Application;

import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.DynamicColorsOptions;

/**
 * 进程入口（AndroidManifest 里 android:name=".TtsApp"）。
 *
 * <p>这里只干一件额外的事：Android 12+ 打开 <b>Material You 动态色</b> ——
 * 系统从壁纸取色，给每个 Activity 套一层 ThemeOverlay，于是 styles.xml 里那套
 * 静态角色就被壁纸色覆盖。界面颜色全走 {@code Palette} 从主题读，
 * 所以不用改任何页面。</p>
 *
 * <p>只在这里注册一次：{@code applyToActivitiesIfAvailable} 是往 Application 挂
 * ActivityLifecycleCallbacks，重复调用会重复挂（不是幂等的）。</p>
 */
public final class TtsApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        App.install(this);
        try {
            DynamicColors.applyToActivitiesIfAvailable(this,
                    new DynamicColorsOptions.Builder().build());
        } catch (Throwable t) {
            // 老系统 / 没有动态色资源时保持静态色板，不能因为换肤失败起不来
            LogBus.error("动态色没装上：" + t);
        }
    }
}
