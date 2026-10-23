package com.mermergi.ttsrelay;

import android.app.Activity;

import android.content.Context;
import android.content.res.Configuration;
import android.util.TypedValue;

/**
 * M3 语义角色 → 界面里那十来个名字。
 *
 * <p>界面是纯代码搭的，所有颜色都从这里取 —— 所以「换主题」= 换这一个对象。
 * 以前这里是两套写死的色板；现在值从主题里取（styles.xml 定义了整套 M3 角色），
 * Android 12+ 由 {@code DynamicColors} 用壁纸色覆盖主题角色，
 * 于是这里取到的自然就是 Material You 的动态色 —— 代码里不用再管。</p>
 *
 * <p>顺序很重要：{@link #apply(Activity)} 先 setTheme 再取色，必须赶在
 * {@code super.onCreate()} 之前调。</p>
 */
public final class Palette {

    public final boolean light;

    public final int bg;            // colorSurface            页面底
    public final int card;          // colorSurfaceContainer    卡片
    public final int cardHi;        // colorSurfaceContainerHighest 卡片里的输入框 / 瓷砖
    public final int border;        // colorOutlineVariant      描边、分隔线
    public final int outline;       // colorOutline             控件描边（比 border 重）

    public final int textPrimary;   // colorOnSurface
    public final int textSecondary; // colorOnSurfaceVariant
    public final int textMuted;     // onSurfaceVariant 再往 surface 混一档

    public final int accent;        // colorPrimary           主色
    public final int accentSoft;    // colorPrimaryContainer  主色的"容器"（大块强调底）
    public final int onAccent;      // colorOnPrimary
    public final int onAccentSoft;  // colorOnPrimaryContainer
    public final int chipBg;        // colorSecondaryContainer  选中态（chip / 底栏药丸）
    public final int chipFg;        // colorOnSecondaryContainer
    public final int ripple;        // colorOnSurface @12%（M3 state layer）
    public final int rippleOnAccent;// colorOnPrimary @12%

    public final int ok;            // M3 没有 success 角色，状态绿自己留一套
    public final int warn;
    public final int err;           // colorError
    public final int log;

    private Palette(boolean light,
                    int bg, int card, int cardHi, int border, int outline,
                    int textPrimary, int textSecondary, int textMuted,
                    int accent, int accentSoft, int onAccent, int onAccentSoft,
                    int chipBg, int chipFg, int ripple, int rippleOnAccent,
                    int ok, int warn, int err, int log) {
        this.light = light;
        this.bg = bg; this.card = card; this.cardHi = cardHi;
        this.border = border; this.outline = outline;
        this.textPrimary = textPrimary; this.textSecondary = textSecondary; this.textMuted = textMuted;
        this.accent = accent; this.accentSoft = accentSoft;
        this.onAccent = onAccent; this.onAccentSoft = onAccentSoft;
        this.chipBg = chipBg; this.chipFg = chipFg;
        this.ripple = ripple; this.rippleOnAccent = rippleOnAccent;
        this.ok = ok; this.warn = warn; this.err = err; this.log = log;
    }

    public static final String[] THEME_NAMES = {"跟随系统", "深色", "浅色"};

    // ══════════════ 取一套调色板 ══════════════

    /** 只看 Prefs + 系统 uiMode，不读主题 —— setTheme 之前就要用。 */
    public static boolean isLight(Context c) {
        int mode = Prefs.themeMode(c);
        if (mode == 1) return false;
        if (mode == 2) return true;
        return (c.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_YES;
    }

    /**
     * 定主题 + 从主题里把 M3 角色读出来。必须在 {@code super.onCreate()} 之前调用，
     * 否则控件 inflate 时读到的还是清单里那个默认主题。
     */
    public static Palette apply(Activity a) {
        boolean light = isLight(a);
        a.setTheme(light ? R.style.AppTheme_Light : R.style.AppTheme);
        return new Palette(a, light);
    }

    /** 主题已经按 Prefs 设过了，直接读。 */
    public static Palette of(Context c) {
        return new Palette(c, isLight(c));
    }

    // ══════════════ 从主题属性里取色 ══════════════

    private Palette(Context c, boolean light) {
        this.light = light;
        boolean d = !light;

        // 主题里没有这些属性时兜底（正常不会走到：styles.xml 两个主题都定义齐了）
        int fBg = light ? 0xFFFBF8FF : 0xFF121318;
        int fCard = light ? 0xFFEFEDF4 : 0xFF1F1F25;
        int fCardHi = light ? 0xFFE4E1E9 : 0xFF34343A;
        int fBorder = light ? 0xFFC6C5D0 : 0xFF46464F;
        int fOutline = light ? 0xFF767680 : 0xFF90909A;
        int fText = light ? 0xFF1B1B21 : 0xFFE4E1E9;
        int fText2 = light ? 0xFF46464F : 0xFFC6C5D0;
        int fAccent = light ? 0xFF515B92 : 0xFFBAC3FF;
        int fOnAccent = light ? 0xFFFFFFFF : 0xFF222C61;
        int fAccentSoft = light ? 0xFFDEE0FF : 0xFF394379;
        int fOnAccentSoft = light ? 0xFF0B154B : 0xFFDEE0FF;
        int fChipBg = light ? 0xFFDFE1F9 : 0xFF434659;
        int fChipFg = light ? 0xFF181A2C : 0xFFDFE1F9;
        int fErr = light ? 0xFFBA1A1A : 0xFFFFB4AB;

        bg = role(c, MAT.colorSurface, fBg);
        card = role(c, MAT.colorSurfaceContainer, fCard);
        cardHi = role(c, MAT.colorSurfaceContainerHighest, fCardHi);
        border = role(c, MAT.colorOutlineVariant, fBorder);
        outline = role(c, MAT.colorOutline, fOutline);

        textPrimary = role(c, MAT.colorOnSurface, fText);
        textSecondary = role(c, MAT.colorOnSurfaceVariant, fText2);
        textMuted = mix(textSecondary, bg, 0.32f);

        accent = role(c, MAT.colorPrimary, fAccent);
        accentSoft = role(c, MAT.colorPrimaryContainer, fAccentSoft);
        onAccent = role(c, MAT.colorOnPrimary, fOnAccent);
        onAccentSoft = role(c, MAT.colorOnPrimaryContainer, fOnAccentSoft);
        chipBg = role(c, MAT.colorSecondaryContainer, fChipBg);
        chipFg = role(c, MAT.colorOnSecondaryContainer, fChipFg);

        // M3 的 state layer：按下不是"变亮"，而是往内容色上叠一层 12% 的色
        ripple = alpha(textPrimary, 0.12f);
        rippleOnAccent = alpha(onAccent, 0.16f);

        // Material 3 没有 success / warning 角色；这两个不动（状态绿、提醒黄）
        ok = d ? 0xFF8DDDB0 : 0xFF1E6B45;
        warn = d ? 0xFFF5C66B : 0xFF8A5300;
        err = role(c, MAT.colorError, fErr);
        log = d ? 0xFF9FD8B4 : 0xFF1F6B44;
    }

    private static int role(Context c, int attr, int fallback) {
        TypedValue tv = new TypedValue();
        if (c.getTheme().resolveAttribute(attr, tv, true)
                && tv.type >= TypedValue.TYPE_FIRST_COLOR_INT
                && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) {
            return tv.data;
        }
        return fallback;
    }

    /** 线性混色：t=0 全 a，t=1 全 b。 */
    static int mix(int a, int b, float t) {
        float s = 1f - t;
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bgc = (b >> 8) & 0xFF, bb = b & 0xFF;
        return 0xFF000000
                | ((int) (ar * s + br * t) << 16)
                | ((int) (ag * s + bgc * t) << 8)
                | (int) (ab * s + bb * t);
    }

    static int alpha(int color, float a) {
        return ((int) (a * 255f) << 24) | (color & 0x00FFFFFF);
    }

    /** Material 的 R 不是这个包的 R，单独起个短名字，省得每行写全限定名。 */
    private static final class MAT {
        static final int colorSurface = com.google.android.material.R.attr.colorSurface;
        static final int colorSurfaceContainer = com.google.android.material.R.attr.colorSurfaceContainer;
        static final int colorSurfaceContainerHighest =
                com.google.android.material.R.attr.colorSurfaceContainerHighest;
        static final int colorOutline = com.google.android.material.R.attr.colorOutline;
        static final int colorOutlineVariant = com.google.android.material.R.attr.colorOutlineVariant;
        static final int colorOnSurface = com.google.android.material.R.attr.colorOnSurface;
        static final int colorOnSurfaceVariant = com.google.android.material.R.attr.colorOnSurfaceVariant;
        static final int colorPrimary = com.google.android.material.R.attr.colorPrimary;
        static final int colorOnPrimary = com.google.android.material.R.attr.colorOnPrimary;
        static final int colorPrimaryContainer = com.google.android.material.R.attr.colorPrimaryContainer;
        static final int colorOnPrimaryContainer = com.google.android.material.R.attr.colorOnPrimaryContainer;
        static final int colorSecondaryContainer = com.google.android.material.R.attr.colorSecondaryContainer;
        static final int colorOnSecondaryContainer = com.google.android.material.R.attr.colorOnSecondaryContainer;
        static final int colorError = com.google.android.material.R.attr.colorError;
    }
}
