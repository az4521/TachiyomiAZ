package app.tachiaz.compat;

/** Native iOS implementations used by the Android compatibility surface. */
public final class NativeBridge {
    private NativeBridge() {
    }

    public static native long[] bitmapCreate(int width, int height);
    public static native long[] bitmapDecode(byte[] data, int offset, int length);
    public static native long[] bitmapCrop(
        long handle,
        int x,
        int y,
        int width,
        int height
    );
    public static native void bitmapRelease(long handle);
    public static native byte[] bitmapCompress(
        long handle,
        int format,
        int quality
    );
    public static native void bitmapErase(long handle, int color);
    public static native int bitmapGetPixel(long handle, int x, int y);
    public static native void bitmapSetPixel(
        long handle,
        int x,
        int y,
        int color
    );
    public static native void bitmapGetPixels(
        long handle,
        int[] pixels,
        int[] parameters
    );
    public static native void bitmapSetPixels(
        long handle,
        int[] pixels,
        int[] parameters
    );
    public static native void bitmapCopyPixels(
        long destination,
        long source,
        int[] rectangles
    );
    public static native void canvasDrawBitmap(
        long destination,
        long source,
        int[] rectangles,
        float[] matrix
    );
    public static native void canvasDrawText(
        long handle,
        String text,
        int[] style,
        String fontName,
        float[] geometry
    );
    public static native String textRegisterFont(String path);
    public static native float textMeasure(
        String text,
        float size,
        boolean bold,
        String fontName
    );
    public static native float[] textFontMetrics(
        float size,
        boolean bold,
        String fontName
    );
    public static native int[] textLineEnds(
        String text,
        float width,
        float size,
        boolean bold,
        String fontName
    );

    public static native long pdfOpen(String path);
    public static native int pdfPageCount(long handle);
    public static native int[] pdfPageSize(long handle, int pageIndex);
    public static native boolean pdfRender(
        long handle,
        long bitmapHandle,
        int[] parameters
    );
    public static native void pdfClose(long handle);

    public static native long javascriptCreate();
    public static native Object javascriptEvaluate(long handle, String script);
    public static native void javascriptClose(long handle);

    /** Commands implemented by the app's WKWebView bridge on iOS. */
    public static native String webkitCommand(
        String operation,
        long handle,
        String argument1,
        String argument2
    );

    /** Called by WKWebView through JNI for asynchronous Android callbacks. */
    public static String dispatchWebKitEvent(
        long handle,
        String event,
        String argument1,
        String argument2
    ) {
        return IOSWebViewProviderFactory.dispatchEvent(
            handle,
            event,
            argument1,
            argument2
        );
    }
}
