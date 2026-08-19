package android.graphics;

import app.tachiaz.compat.NativeBridge;
import java.io.IOException;
import java.io.OutputStream;

public final class Bitmap {
    private long nativeHandle;
    private final int width;
    private final int height;
    private final Config config;

    private Bitmap(long nativeHandle, int width, int height, Config config) {
        this.nativeHandle = nativeHandle;
        this.width = width;
        this.height = height;
        this.config = config;
    }

    static Bitmap fromNative(long[] result, Config config) {
        if (result == null || result.length < 3 || result[0] == 0) {
            return null;
        }
        return new Bitmap(result[0], (int) result[1], (int) result[2], config);
    }

    public long nativeHandle() {
        requireLive();
        return nativeHandle;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public Config getConfig() {
        return config;
    }

    public static Bitmap createBitmap(int width, int height, Config config) {
        checkWidthHeight(width, height);
        if (config == null) {
            throw new NullPointerException("config");
        }
        return fromNative(
            NativeBridge.bitmapCreate(width, height),
            config
        );
    }

    public static Bitmap createBitmap(
        Bitmap source,
        int x,
        int y,
        int width,
        int height
    ) {
        if (source == null) {
            throw new NullPointerException("source");
        }
        checkRegion(source, x, y, width, height);
        return fromNative(
            NativeBridge.bitmapCrop(
                source.nativeHandle(),
                x,
                y,
                width,
                height
            ),
            source.config
        );
    }

    public boolean compress(
        CompressFormat format,
        int quality,
        OutputStream stream
    ) {
        if (format == null || stream == null) {
            throw new NullPointerException();
        }
        if (quality < 0 || quality > 100) {
            throw new IllegalArgumentException("quality must be 0..100");
        }
        byte[] data = NativeBridge.bitmapCompress(
            nativeHandle(),
            format.nativeInt,
            quality
        );
        if (data == null) {
            return false;
        }
        try {
            stream.write(data);
            return true;
        } catch (IOException error) {
            throw new RuntimeException(error);
        }
    }

    public Bitmap copy(Config destinationConfig, boolean mutable) {
        return createBitmap(this, 0, 0, width, height);
    }

    public int getPixel(int x, int y) {
        checkPixel(x, y);
        return NativeBridge.bitmapGetPixel(nativeHandle(), x, y);
    }

    public void setPixel(int x, int y, int color) {
        checkPixel(x, y);
        NativeBridge.bitmapSetPixel(nativeHandle(), x, y, color);
    }

    public void getPixels(
        int[] pixels,
        int offset,
        int stride,
        int x,
        int y,
        int width,
        int height
    ) {
        checkPixels(pixels, offset, stride, x, y, width, height);
        NativeBridge.bitmapGetPixels(
            nativeHandle(),
            pixels,
            new int[] {offset, stride, x, y, width, height}
        );
    }

    public void setPixels(
        int[] pixels,
        int offset,
        int stride,
        int x,
        int y,
        int width,
        int height
    ) {
        checkPixels(pixels, offset, stride, x, y, width, height);
        NativeBridge.bitmapSetPixels(
            nativeHandle(),
            pixels,
            new int[] {offset, stride, x, y, width, height}
        );
    }

    public void eraseColor(int color) {
        NativeBridge.bitmapErase(nativeHandle(), color);
    }

    public void recycle() {
        if (nativeHandle != 0) {
            NativeBridge.bitmapRelease(nativeHandle);
            nativeHandle = 0;
        }
    }

    public boolean isRecycled() {
        return nativeHandle == 0;
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            recycle();
        } finally {
            super.finalize();
        }
    }

    private void requireLive() {
        if (nativeHandle == 0) {
            throw new IllegalStateException("Bitmap has been recycled");
        }
    }

    private void checkPixel(int x, int y) {
        if (x < 0 || y < 0 || x >= width || y >= height) {
            throw new IllegalArgumentException("pixel is outside the bitmap");
        }
    }

    private static void checkWidthHeight(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("width and height must be > 0");
        }
    }

    private static void checkRegion(
        Bitmap source,
        int x,
        int y,
        int width,
        int height
    ) {
        checkWidthHeight(width, height);
        if (
            x < 0 || y < 0 ||
            x + width > source.width || y + height > source.height
        ) {
            throw new IllegalArgumentException("region is outside the bitmap");
        }
    }

    private void checkPixels(
        int[] pixels,
        int offset,
        int stride,
        int x,
        int y,
        int regionWidth,
        int regionHeight
    ) {
        if (pixels == null) {
            throw new NullPointerException("pixels");
        }
        if (
            regionWidth < 0 || regionHeight < 0 ||
            x < 0 || y < 0 ||
            x + regionWidth > width || y + regionHeight > height ||
            Math.abs(stride) < regionWidth
        ) {
            throw new IllegalArgumentException("invalid pixel region");
        }
        int last = offset + ((regionHeight - 1) * stride);
        if (
            offset < 0 || offset + regionWidth > pixels.length ||
            last < 0 || last + regionWidth > pixels.length
        ) {
            throw new ArrayIndexOutOfBoundsException();
        }
    }

    public enum CompressFormat {
        JPEG(0), PNG(1), WEBP(2), WEBP_LOSSY(3), WEBP_LOSSLESS(4);

        final int nativeInt;

        CompressFormat(int nativeInt) {
            this.nativeInt = nativeInt;
        }
    }

    public enum Config {
        ALPHA_8(1),
        RGB_565(3),
        ARGB_4444(4),
        ARGB_8888(5),
        RGBA_F16(6),
        HARDWARE(7),
        RGBA_1010102(8);

        final int nativeInt;

        Config(int nativeInt) {
            this.nativeInt = nativeInt;
        }
    }
}
