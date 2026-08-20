package android.util;

/** Minimal, deterministic display metrics for headless extension rendering. */
public class DisplayMetrics {
    public static final int DENSITY_DEFAULT = 160;
    public static final int DENSITY_LOW = 120;
    public static final int DENSITY_MEDIUM = 160;
    public static final int DENSITY_TV = 213;
    public static final int DENSITY_HIGH = 240;
    public static final int DENSITY_XHIGH = 320;
    public static final int DENSITY_XXHIGH = 480;
    public static final int DENSITY_XXXHIGH = 640;

    public int widthPixels;
    public int heightPixels;
    public float density;
    public int densityDpi;
    public float scaledDensity;
    public float xdpi;
    public float ydpi;

    public DisplayMetrics() {
        setToDefaults();
    }

    public void setToDefaults() {
        widthPixels = integerProperty("tachiaz.display.width", 1170);
        heightPixels = integerProperty("tachiaz.display.height", 2532);
        density = floatProperty("tachiaz.display.density", 3.0f);
        densityDpi = Math.round(DENSITY_DEFAULT * density);
        scaledDensity = density;
        xdpi = densityDpi;
        ydpi = densityDpi;
    }

    public void setTo(DisplayMetrics other) {
        widthPixels = other.widthPixels;
        heightPixels = other.heightPixels;
        density = other.density;
        densityDpi = other.densityDpi;
        scaledDensity = other.scaledDensity;
        xdpi = other.xdpi;
        ydpi = other.ydpi;
    }

    private static int integerProperty(String name, int fallback) {
        try {
            return Integer.parseInt(System.getProperty(name, String.valueOf(fallback)));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static float floatProperty(String name, float fallback) {
        try {
            return Float.parseFloat(System.getProperty(name, String.valueOf(fallback)));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
