package com.mermergi.ttsrelay;

import android.content.Context;
import android.content.res.Configuration;

/**
 * 两套色板：深色 / 浅色。「跟随系统」时按 uiMode 现场选。
 * 界面是纯代码搭的，颜色一律从这里取 —— 换主题就是换这一个对象。
 */
public final class Palette {

    public final boolean light;

    public final int bg;          // 页面底
    public final int card;        // 卡片
    public final int cardHi;      // 卡片里的次级块（输入框、瓷砖）
    public final int border;      // 描边

    public final int textPrimary;
    public final int textSecondary;
    public final int textMuted;

    public final int accent;      // 主色
    public final int accentSoft;  // 主色的淡底（渐变起点）
    public final int onAccent;    // 主色按钮上的字
    public final int ripple;      // 浅色块上的水波
    public final int rippleOnAccent;

    public final int ok;
    public final int warn;
    public final int err;
    public final int log;         // 日志字色

    private Palette(boolean light,
                    int bg, int card, int cardHi, int border,
                    int textPrimary, int textSecondary, int textMuted,
                    int accent, int accentSoft, int onAccent, int ripple, int rippleOnAccent,
                    int ok, int warn, int err, int log) {
        this.light = light;
        this.bg = bg; this.card = card; this.cardHi = cardHi; this.border = border;
        this.textPrimary = textPrimary; this.textSecondary = textSecondary; this.textMuted = textMuted;
        this.accent = accent; this.accentSoft = accentSoft; this.onAccent = onAccent;
        this.ripple = ripple; this.rippleOnAccent = rippleOnAccent;
        this.ok = ok; this.warn = warn; this.err = err; this.log = log;
    }

    private static final Palette DARK = new Palette(false,
            0xFF0D0E13, 0xFF161822, 0xFF1E2130, 0xFF272B3A,
            0xFFE9ECF6, 0xFF9AA0B4, 0xFF6E7386,
            0xFF6C8CFF, 0xFF232C4A, 0xFFFFFFFF, 0x1FFFFFFF, 0x33FFFFFF,
            0xFF4ADE80, 0xFFFBBF24, 0xFFF87171, 0xFF8FE3A2);

    private static final Palette LIGHT = new Palette(true,
            0xFFF2F3F8, 0xFFFFFFFF, 0xFFF6F7FB, 0xFFE3E6F0,
            0xFF14161C, 0xFF5A6072, 0xFF8A90A2,
            0xFF4F6BFF, 0xFFE8ECFF, 0xFFFFFFFF, 0x12000000, 0x33FFFFFF,
            0xFF16A34A, 0xFFD97706, 0xFFDC2626, 0xFF1B6B38);

    public static final String[] THEME_NAMES = {"跟随系统", "深色", "浅色"};

    public static Palette of(Context c) {
        return resolve(Prefs.themeMode(c), c);
    }

    /** mode：0 跟随系统 / 1 深色 / 2 浅色。 */
    public static Palette resolve(int mode, Context c) {
        boolean light;
        if (mode == 1) light = false;
        else if (mode == 2) light = true;
        else {
            light = (c.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_YES;
        }
        return light ? LIGHT : DARK;
    }
}
