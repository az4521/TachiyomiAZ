package app.tachiaz.runtime;

/**
 * Proves that iOS-native compatibility types come from the mobile boot shim
 * while the remaining AndroidCompat API stays on the application classpath.
 */
public final class MobileShimPrecedenceTest {
    private MobileShimPrecedenceTest() {
    }

    public static void main(String[] args) throws Exception {
        Class<?> systemClock = Class.forName("android.os.SystemClock");
        if (systemClock.getClassLoader() != null) {
            throw new AssertionError(
                "SystemClock must be loaded from the mobile boot shim"
            );
        }

        Class<?> uri = Class.forName("android.net.Uri");
        uri.getMethod("getQueryParameter", String.class);
        if (uri.getClassLoader() == null) {
            throw new AssertionError(
                "Uri must come from the application compatibility classpath"
            );
        }

        Class<?> bitmap = requireMobileCompatShim("android.graphics.Bitmap");
        Class<?> rectangle = requireMobileCompatShim("android.graphics.Rect");
        Class<?> paint = requireMobileCompatShim("android.graphics.Paint");
        Class<?> canvas = requireMobileCompatShim("android.graphics.Canvas");
        requireMobileCompatShim("android.graphics.pdf.PdfRenderer");
        requireMobileCompatShim("android.content.res.Resources");
        requireMobileCompatShim("android.icu.text.BreakIterator");
        requireMobileCompatShim("android.icu.text.Collator");
        requireMobileCompatShim("android.icu.text.Normalizer2");
        requireMobileCompatShim("android.icu.text.RuleBasedCollator");
        requireMobileCompatShim("android.icu.text.StringSearch");
        requireMobileCompatShim("android.os.ParcelFileDescriptor");
        requireMobileCompatShim("android.util.DisplayMetrics");
        requireMobileCompatShim("android.util.JsonReader");
        requireMobileCompatShim("android.webkit.SslErrorHandler");
        canvas.getMethod(
            "drawBitmap",
            bitmap,
            rectangle,
            rectangle,
            paint
        );

        Class<?> userAgentInterceptor = Class.forName(
            "eu.kanade.tachiyomi.network.interceptor.UserAgentInterceptor"
        );
        userAgentInterceptor.getMethod("effectiveUserAgent", String.class);

        System.out.println("mobile shim precedence test passed");
    }

    private static Class<?> requireBootShim(String name) throws Exception {
        Class<?> type = Class.forName(name);
        if (type.getClassLoader() != null) {
            throw new AssertionError(name + " must come from the mobile boot shim");
        }
        return type;
    }

    private static Class<?> requireMobileCompatShim(String name)
        throws Exception {
        Class<?> type = Class.forName(name);
        if (type.getClassLoader() == null) {
            throw new AssertionError(name + " must not be on the boot classpath");
        }
        String source = String.valueOf(
            type.getProtectionDomain().getCodeSource().getLocation()
        );
        if (!source.contains("000-tachiaz-mobile-compat-shims.jar")) {
            throw new AssertionError(
                name + " loaded from the wrong compatibility JAR: " + source
            );
        }
        return type;
    }
}
