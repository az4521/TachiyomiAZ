package app.tachiaz.runtime;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import android.webkit.JavascriptInterface;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import kotlinx.coroutines.flow.MutableStateFlow;
import kotlin.jvm.functions.Function0;
import suwayomi.tachidesk.server.ServerConfig;
import suwayomi.tachidesk.server.ServerConfigKt;
import eu.kanade.tachiyomi.network.interceptor.UserAgentInterceptor;

/** Verifies the Android API descriptors used by Tachiyomi extension libraries. */
public final class AndroidCompatibilitySurfaceTest {
    private AndroidCompatibilitySurfaceTest() {
    }

    public static void main(String[] arguments) throws Exception {
        Class<?> bitmap = Class.forName("android.graphics.Bitmap");
        Class<?> bitmapConfig = Class.forName("android.graphics.Bitmap$Config");
        Class<?> bitmapFormat = Class.forName(
            "android.graphics.Bitmap$CompressFormat"
        );
        Class<?> bitmapFactory = Class.forName("android.graphics.BitmapFactory");
        Class<?> bitmapOptions = Class.forName(
            "android.graphics.BitmapFactory$Options"
        );
        Class<?> canvas = Class.forName("android.graphics.Canvas");
        Class<?> paint = Class.forName("android.graphics.Paint");
        Class<?> rect = Class.forName("android.graphics.Rect");
        Class<?> textPaint = Class.forName("android.text.TextPaint");
        Class<?> alignment = Class.forName("android.text.Layout$Alignment");
        Class<?> staticLayout = Class.forName("android.text.StaticLayout");
        Class<?> parcelFileDescriptor = Class.forName(
            "android.os.ParcelFileDescriptor"
        );
        Class<?> pdfRenderer = Class.forName("android.graphics.pdf.PdfRenderer");
        Class<?> pdfPage = Class.forName("android.graphics.pdf.PdfRenderer$Page");
        Class<?> matrix = Class.forName("android.graphics.Matrix");

        bitmap.getMethod("createBitmap", int.class, int.class, bitmapConfig);
        bitmap.getMethod(
            "createBitmap",
            bitmap,
            int.class,
            int.class,
            int.class,
            int.class
        );
        bitmap.getMethod(
            "compress",
            bitmapFormat,
            int.class,
            OutputStream.class
        );
        bitmap.getMethod(
            "setPixels",
            int[].class,
            int.class,
            int.class,
            int.class,
            int.class,
            int.class,
            int.class
        );
        bitmapFactory.getMethod(
            "decodeByteArray",
            byte[].class,
            int.class,
            int.class,
            bitmapOptions
        );
        bitmapFactory.getMethod("decodeStream", InputStream.class);
        canvas.getConstructor(bitmap);
        canvas.getMethod("drawBitmap", bitmap, rect, rect, paint);
        canvas.getMethod("drawText", String.class, float.class, float.class, paint);
        canvas.getMethod("translate", float.class, float.class);
        canvas.getMethod("scale", float.class, float.class);
        canvas.getMethod("scale", float.class, float.class, float.class, float.class);
        canvas.getMethod("rotate", float.class);
        canvas.getMethod("rotate", float.class, float.class, float.class);
        canvas.getMethod("skew", float.class, float.class);
        canvas.getMethod("getWidth");
        canvas.getMethod("getHeight");
        canvas.getMethod("save");
        canvas.getMethod("restore");
        canvas.getMethod("restoreToCount", int.class);
        paint.getMethod("measureText", String.class);
        parcelFileDescriptor.getMethod("open", java.io.File.class, int.class);
        pdfRenderer.getConstructor(parcelFileDescriptor);
        pdfRenderer.getMethod("getPageCount");
        pdfRenderer.getMethod("openPage", int.class);
        pdfPage.getMethod("getWidth");
        pdfPage.getMethod("getHeight");
        pdfPage.getMethod("render", bitmap, rect, matrix, int.class);
        Class<?> sslHandler = Class.forName("android.webkit.SslErrorHandler");
        sslHandler.getConstructor();
        sslHandler.getMethod("proceed");
        sslHandler.getMethod("cancel");
        Class<?> webSettings = Class.forName("android.webkit.WebSettings");
        webSettings.getMethod(
            "getDefaultUserAgent",
            Class.forName("android.content.Context")
        );
        Object rectangle = rect
            .getConstructor(int.class, int.class, int.class, int.class)
            .newInstance(10, 20, 50, 80);
        if (
            (Integer) rect.getMethod("width").invoke(rectangle) != 40 ||
            (Integer) rect.getMethod("height").invoke(rectangle) != 60
        ) {
            throw new AssertionError("Android Rect geometry is incorrect");
        }
        java.lang.reflect.Constructor<?> bitmapConstructor = bitmap
            .getDeclaredConstructor(
                long.class,
                int.class,
                int.class,
                bitmapConfig
            );
        bitmapConstructor.setAccessible(true);
        @SuppressWarnings({"unchecked", "rawtypes"})
        Object argb8888 = Enum.valueOf(
            (Class<? extends Enum>) bitmapConfig,
            "ARGB_8888"
        );
        Object fakeBitmap = bitmapConstructor.newInstance(1L, 100, 50, argb8888);
        Object canvasInstance = canvas.getConstructor(bitmap).newInstance(fakeBitmap);
        if ((Integer) canvas.getMethod("getSaveCount").invoke(canvasInstance) != 1) {
            throw new AssertionError("Canvas must begin with one save frame");
        }
        int saved = (Integer) canvas.getMethod("save").invoke(canvasInstance);
        canvas.getMethod("translate", float.class, float.class)
            .invoke(canvasInstance, 10f, 20f);
        Object clip = rect.getConstructor().newInstance();
        canvas.getMethod("getClipBounds", rect).invoke(canvasInstance, clip);
        if (
            rect.getField("left").getInt(clip) != -10 ||
            rect.getField("top").getInt(clip) != -20 ||
            rect.getField("right").getInt(clip) != 90 ||
            rect.getField("bottom").getInt(clip) != 30
        ) {
            throw new AssertionError("Canvas translation was not reflected in its clip");
        }
        canvas.getMethod("restoreToCount", int.class).invoke(canvasInstance, saved);
        if ((Integer) canvas.getMethod("getSaveCount").invoke(canvasInstance) != 1) {
            throw new AssertionError("Canvas save/restore did not balance");
        }
        staticLayout.getConstructor(
            CharSequence.class,
            textPaint,
            int.class,
            alignment,
            float.class,
            float.class,
            boolean.class
        );
        Class.forName("android.text.Html").getMethod(
            "fromHtml",
            String.class,
            int.class
        );

        Class<?> decoder = Class.forName("tachiyomi.decoder.ImageDecoder");
        Class<?> decoderCompanion = Class.forName(
            "tachiyomi.decoder.ImageDecoder$Companion"
        );
        decoder.getMethod("decode", rect, int.class);
        decoder.getMethod(
            "decode",
            rect,
            boolean.class,
            int.class,
            boolean.class,
            byte[].class
        );
        decoder.getMethod("decode", rect, boolean.class, int.class);
        decoderCompanion.getMethod(
            "newInstance",
            InputStream.class,
            boolean.class,
            byte[].class
        );
        decoderCompanion.getMethod(
            "newInstance",
            InputStream.class,
            boolean.class
        );

        Class<?> quickJs = Class.forName("app.cash.quickjs.QuickJs");
        quickJs.getMethod("evaluate", String.class);
        quickJs.getMethod("get", String.class, Class.class);
        Class.forName("app.cash.quickjs.QuickJsException")
            .getConstructor(String.class);
        Class<?> cookieManagerType = Class.forName(
            "android.webkit.CookieManager"
        );
        cookieManagerType.getMethod("getInstance");
        Class<?> nativeBridgeType = Class.forName(
            "app.tachiaz.compat.NativeBridge"
        );
        nativeBridgeType.getMethod(
            "bitmapCopyPixels",
            long.class,
            long.class,
            int[].class
        );
        nativeBridgeType.getMethod(
            "webkitCommand",
            String.class,
            long.class,
            String.class,
            String.class
        );
        Object missingEventResult = nativeBridgeType.getMethod(
            "dispatchWebKitEvent",
            long.class,
            String.class,
            String.class,
            String.class
        ).invoke(null, 9_999L, "pageFinished", "https://example.invalid", "");
        if (!"".equals(missingEventResult)) {
            throw new AssertionError("Unknown WebView callbacks must be ignored safely");
        }
        if (!JavascriptTarget.class.getMethod("post", String.class)
            .isAnnotationPresent(JavascriptInterface.class)) {
            throw new AssertionError("JavaScript interface annotations are not visible at runtime");
        }
        Class<?> webViewFactoryType = Class.forName(
            "app.tachiaz.compat.IOSWebViewProviderFactory"
        );
        Class<?> providerType = Class.forName(
            "app.tachiaz.compat.IOSWebViewProviderFactory$Provider"
        );
        java.lang.reflect.Method asyncMethods = providerType.getDeclaredMethod(
            "asynchronousJavascriptMethods",
            Object.class
        );
        asyncMethods.setAccessible(true);
        String asyncMethodList = (String) asyncMethods.invoke(null, new JavascriptTarget());
        if (!asyncMethodList.contains("post\t1") || asyncMethodList.contains("echo\t1")) {
            throw new AssertionError(
                "Only void JavaScript interface methods should use asynchronous WebKit messages: " +
                asyncMethodList
            );
        }
        java.lang.reflect.Method navigationUserAgent = providerType.getDeclaredMethod(
            "navigationUserAgent",
            String.class,
            String.class,
            String.class,
            Boolean.TYPE
        );
        navigationUserAgent.setAccessible(true);
        if (!"challenge-agent".equals(navigationUserAgent.invoke(
            null,
            "cached-agent",
            "challenge-agent",
            "session=value",
            false
        ))) {
            throw new AssertionError("Inherited WebViews must reuse the shared session user agent");
        }
        if (!"custom-agent".equals(navigationUserAgent.invoke(
            null,
            "custom-agent",
            "challenge-agent",
            "session=value",
            true
        ))) {
            throw new AssertionError("Explicit WebView user agents must remain unchanged");
        }
        if (!"challenge-agent".equals(navigationUserAgent.invoke(
            null,
            "cached-agent",
            "challenge-agent",
            "session=value; cf_clearance=cleared",
            true
        ))) {
            throw new AssertionError("Cloudflare WebViews must reuse the clearance user agent");
        }
        java.lang.reflect.Method hasExplicitUserAgent = providerType.getDeclaredMethod(
            "hasExplicitUserAgent",
            webSettings
        );
        hasExplicitUserAgent.setAccessible(true);
        Object compatibilitySettings = Class.forName(
            "xyz.nulldev.androidcompat.webkit.KcefWebSettings"
        ).getConstructor().newInstance();
        if ((Boolean) hasExplicitUserAgent.invoke(null, compatibilitySettings)) {
            throw new AssertionError("Default WebSettings were mistaken for an explicit user agent");
        }
        webSettings.getMethod("setUserAgentString", String.class)
            .invoke(compatibilitySettings, "custom-agent");
        if (!(Boolean) hasExplicitUserAgent.invoke(null, compatibilitySettings)) {
            throw new AssertionError("Explicit WebSettings user agent was not detected");
        }
        webViewFactoryType.getMethod("install").invoke(null);
        Object cookieManager = cookieManagerType.getMethod("getInstance")
            .invoke(null);
        if (!cookieManager.getClass().getName().contains("IOSCookieManager")) {
            throw new AssertionError("iOS CookieManager provider was not installed");
        }
        Class<?> webResourceError = Class.forName(
            "android.webkit.IOSWebResourceError"
        );
        Object nativeError = webResourceError
            .getConstructor(int.class, CharSequence.class)
            .newInstance(-2, "host lookup failed");
        if ((Integer) webResourceError.getMethod("getErrorCode").invoke(nativeError) != -2) {
            throw new AssertionError("iOS WebResourceError lost its error code");
        }
        Class<?> renderGone = Class.forName(
            "android.webkit.IOSRenderProcessGoneDetail"
        );
        Object renderDetail = renderGone
            .getConstructor(boolean.class, int.class)
            .newInstance(true, 0);
        if (!(Boolean) renderGone.getMethod("didCrash").invoke(renderDetail)) {
            throw new AssertionError("iOS render-process failure was not preserved");
        }
        Class<?> activityManagerType = Class.forName(
            "android.app.ActivityManager"
        );
        Class<?> memoryInfoType = Class.forName(
            "android.app.ActivityManager$MemoryInfo"
        );
        activityManagerType.getMethod(
            "getMemoryInfo",
            memoryInfoType
        );
        Object activityManager = activityManagerType
            .getConstructor()
            .newInstance();
        Object memoryInfo = memoryInfoType.getConstructor().newInstance();
        activityManagerType.getMethod("getMemoryInfo", memoryInfoType)
            .invoke(activityManager, memoryInfo);
        if (memoryInfoType.getField("totalMem").getLong(memoryInfo) <= 0) {
            throw new AssertionError("Activity memory information is invalid");
        }
        Class.forName("android.content.SharedPreferences");
        Class.forName("android.widget.Toast").getMethod(
            "makeText",
            Class.forName("android.content.Context"),
            CharSequence.class,
            int.class
        );
        Class.forName("android.os.Looper").getMethod("getMainLooper");
        assertMobileServerConfig();
        assertConfigurableUserAgent();
        assertHeadlessAndroidUtilities();

        byte[] decoded = (byte[]) Class.forName("android.util.Base64")
            .getMethod("decode", String.class, int.class)
            .invoke(null, "VGFjaGl5b21pQVo=", 0);
        if (!"TachiyomiAZ".equals(new String(decoded, StandardCharsets.UTF_8))) {
            throw new AssertionError("Android Base64 decoding failed");
        }

        byte[] key = new byte[16];
        byte[] iv = new byte[16];
        Arrays.fill(key, (byte) 7);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(
            Cipher.ENCRYPT_MODE,
            new SecretKeySpec(key, "AES"),
            new IvParameterSpec(iv)
        );
        byte[] encrypted = cipher.doFinal("compatibility".getBytes("UTF-8"));
        cipher.init(
            Cipher.DECRYPT_MODE,
            new SecretKeySpec(key, "AES"),
            new IvParameterSpec(iv)
        );
        if (!"compatibility".equals(new String(cipher.doFinal(encrypted), "UTF-8"))) {
            throw new AssertionError("AES-CBC compatibility failed");
        }
        Class.forName("java.text.Collator").getMethod(
            "getInstance",
            java.util.Locale.class
        ).invoke(null, java.util.Locale.ENGLISH);

        System.out.println("Android compatibility surface test passed");
    }

    private static void assertMobileServerConfig() {
        ServerConfig config = ServerConfigKt.getServerConfig();
        if (config == null || config != ServerConfigKt.getServerConfig()) {
            throw new AssertionError("Mobile server configuration is not a singleton");
        }
        assertFlowValue(config.getFlareSolverrEnabled(), Boolean.FALSE);
        assertFlowValue(config.getFlareSolverrAsResponseFallback(), Boolean.FALSE);
        assertFlowValue(config.getFlareSolverrTimeout(), 60);
        assertFlowValue(config.getFlareSolverrUrl(), "");
        assertFlowValue(config.getFlareSolverrSessionName(), "tachiyomiaz");
        assertFlowValue(config.getFlareSolverrSessionTtl(), 15);
    }

    private static void assertFlowValue(
        MutableStateFlow<?> flow,
        Object expected
    ) {
        if (flow == null || !expected.equals(flow.getValue())) {
            throw new AssertionError(
                "Unexpected mobile server setting: " + (flow == null ? null : flow.getValue())
            );
        }
    }

    private static void assertConfigurableUserAgent() {
        final String[] configured = { "TachiyomiAZ/First" };
        UserAgentInterceptor interceptor = new UserAgentInterceptor(
            new Function0<String>() {
                @Override
                public String invoke() {
                    return configured[0];
                }
            }
        );
        String first = interceptor.effectiveUserAgent(
            UserAgentInterceptor.BUILT_IN_DEFAULT
        );
        if (!configured[0].equals(first)) {
            throw new AssertionError("Built-in user agent was not configurable");
        }
        configured[0] = "TachiyomiAZ/Second";
        if (!configured[0].equals(interceptor.effectiveUserAgent(first))) {
            throw new AssertionError("Cached default user agent was not refreshed");
        }
        if (!"Extension/Custom".equals(
            interceptor.effectiveUserAgent("Extension/Custom")
        )) {
            throw new AssertionError("An extension's custom user agent was overwritten");
        }
        interceptor.forceUserAgent("WebKit/Clearance");
        if (!"WebKit/Clearance".equals(
            interceptor.effectiveUserAgent("Extension/Custom")
        )) {
            throw new AssertionError("Cloudflare user agent was not forced");
        }
        interceptor.clearForcedUserAgent();
        if (!"Extension/Custom".equals(
            interceptor.effectiveUserAgent("Extension/Custom")
        )) {
            throw new AssertionError("Cloudflare user-agent force was not cleared");
        }
    }

    private static void assertHeadlessAndroidUtilities() throws Exception {
        android.util.DisplayMetrics metrics = android.content.res.Resources
            .getSystem()
            .getDisplayMetrics();
        if (metrics.widthPixels <= 0 || metrics.heightPixels <= 0 || metrics.density <= 0) {
            throw new AssertionError("Headless display metrics are invalid");
        }

        android.util.JsonReader reader = new android.util.JsonReader(
            new StringReader("{\"items\":[1,{\"skip\":true},null],\"value\":2.5}")
        );
        reader.beginObject();
        if (!"items".equals(reader.nextName())) throw new AssertionError("JSON name lost");
        reader.beginArray();
        if (reader.nextInt() != 1) throw new AssertionError("JSON integer lost");
        reader.skipValue();
        reader.nextNull();
        reader.endArray();
        if (!"value".equals(reader.nextName()) || reader.nextDouble() != 2.5d) {
            throw new AssertionError("JSON numeric value lost");
        }
        reader.endObject();
        if (reader.peek() != android.util.JsonToken.END_DOCUMENT) {
            throw new AssertionError("JSON document did not terminate");
        }
        reader.close();

        android.icu.text.RuleBasedCollator collator =
            (android.icu.text.RuleBasedCollator) android.icu.text.Collator.getInstance();
        collator.setStrength(android.icu.text.Collator.PRIMARY);
        android.icu.text.StringSearch search = new android.icu.text.StringSearch(
            "café",
            new java.text.StringCharacterIterator("A CAFE title"),
            collator
        );
        if (search.first() != 2 || !"CAFE".equals(search.getMatchedText())) {
            throw new AssertionError("ICU-compatible collation search failed");
        }
        String folded = android.icu.text.Normalizer2
            .getNFKCCasefoldInstance()
            .normalize("Straße");
        if (!"strasse".equals(folded)) {
            throw new AssertionError("ICU-compatible case folding failed: " + folded);
        }
    }

    public static final class JavascriptTarget {
        @JavascriptInterface
        public void post(String message) {
        }

        @JavascriptInterface
        public String echo(String message) {
            return message;
        }
    }
}
