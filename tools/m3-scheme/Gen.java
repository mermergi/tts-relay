import com.google.android.material.color.utilities.*;

public class Gen {
    static void p(String n, int argb) {
        System.out.printf("        <item name=\"%s\">#%08X</item>%n", n, argb);
    }
    public static void main(String[] args) {
        int seed = 0xFF4F6BFF;
        MaterialDynamicColors m = new MaterialDynamicColors();
        for (boolean dark : new boolean[]{false, true}) {
            SchemeTonalSpot s = new SchemeTonalSpot(Hct.fromInt(seed), dark, 0.0);
            System.out.println(dark ? "DARK" : "LIGHT");
            p("colorPrimary", m.primary().getArgb(s));
            p("colorOnPrimary", m.onPrimary().getArgb(s));
            p("colorPrimaryContainer", m.primaryContainer().getArgb(s));
            p("colorOnPrimaryContainer", m.onPrimaryContainer().getArgb(s));
            p("colorSecondary", m.secondary().getArgb(s));
            p("colorOnSecondary", m.onSecondary().getArgb(s));
            p("colorSecondaryContainer", m.secondaryContainer().getArgb(s));
            p("colorOnSecondaryContainer", m.onSecondaryContainer().getArgb(s));
            p("colorTertiary", m.tertiary().getArgb(s));
            p("colorOnTertiary", m.onTertiary().getArgb(s));
            p("colorTertiaryContainer", m.tertiaryContainer().getArgb(s));
            p("colorOnTertiaryContainer", m.onTertiaryContainer().getArgb(s));
            p("colorError", m.error().getArgb(s));
            p("colorOnError", m.onError().getArgb(s));
            p("colorErrorContainer", m.errorContainer().getArgb(s));
            p("colorOnErrorContainer", m.onErrorContainer().getArgb(s));
            p("colorSurface", m.surface().getArgb(s));
            p("colorOnSurface", m.onSurface().getArgb(s));
            p("colorSurfaceVariant", m.surfaceVariant().getArgb(s));
            p("colorOnSurfaceVariant", m.onSurfaceVariant().getArgb(s));
            p("colorSurfaceDim", m.surfaceDim().getArgb(s));
            p("colorSurfaceBright", m.surfaceBright().getArgb(s));
            p("colorSurfaceContainerLowest", m.surfaceContainerLowest().getArgb(s));
            p("colorSurfaceContainerLow", m.surfaceContainerLow().getArgb(s));
            p("colorSurfaceContainer", m.surfaceContainer().getArgb(s));
            p("colorSurfaceContainerHigh", m.surfaceContainerHigh().getArgb(s));
            p("colorSurfaceContainerHighest", m.surfaceContainerHighest().getArgb(s));
            p("colorOutline", m.outline().getArgb(s));
            p("colorOutlineVariant", m.outlineVariant().getArgb(s));
            p("colorSurfaceInverse", m.inverseSurface().getArgb(s));
            p("colorOnSurfaceInverse", m.inverseOnSurface().getArgb(s));
            p("colorPrimaryInverse", m.inversePrimary().getArgb(s));
            System.out.println();
        }
    }
}
