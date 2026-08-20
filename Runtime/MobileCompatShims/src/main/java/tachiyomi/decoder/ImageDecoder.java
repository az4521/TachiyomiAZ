package tachiyomi.decoder;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Rect;
import java.io.InputStream;

/** Host-provided implementation of the image-decoder API used by extensions. */
public final class ImageDecoder {
    public static final Companion Companion = new Companion();

    private Bitmap source;

    private ImageDecoder(Bitmap source) {
        this.source = source;
    }

    public int getWidth() {
        return source == null ? 0 : source.getWidth();
    }

    public int getHeight() {
        return source == null ? 0 : source.getHeight();
    }

    public Bitmap decode(Rect region, int sampleSize) {
        return decodeRegion(region, sampleSize);
    }

    public Bitmap decode(
        Rect region,
        boolean rgb565,
        int sampleSize
    ) {
        return decodeRegion(region, sampleSize);
    }

    public Bitmap decode(
        Rect region,
        boolean rgb565,
        int sampleSize,
        boolean applyColorManagement,
        byte[] displayProfile
    ) {
        return decodeRegion(region, sampleSize);
    }

    public void recycle() {
        if (source != null) {
            source.recycle();
            source = null;
        }
    }

    private Bitmap decodeRegion(Rect region, int requestedSampleSize) {
        if (source == null) {
            return null;
        }
        Rect value = region == null
            ? new Rect(0, 0, source.getWidth(), source.getHeight())
            : region;
        Bitmap cropped = Bitmap.createBitmap(
            source,
            value.left,
            value.top,
            value.getWidth(),
            value.getHeight()
        );
        int sampleSize = Math.max(1, requestedSampleSize);
        if (sampleSize == 1) {
            return cropped;
        }
        int width = Math.max(1, cropped.getWidth() / sampleSize);
        int height = Math.max(1, cropped.getHeight() / sampleSize);
        Bitmap sampled = Bitmap.createBitmap(
            width,
            height,
            Bitmap.Config.ARGB_8888
        );
        new Canvas(sampled).drawBitmap(
            cropped,
            new Rect(0, 0, cropped.getWidth(), cropped.getHeight()),
            new Rect(0, 0, width, height),
            null
        );
        cropped.recycle();
        return sampled;
    }

    public static final class Companion {
        private Companion() {
        }

        public ImageDecoder newInstance(
            InputStream stream,
            boolean cropBorders
        ) {
            return create(stream);
        }

        public ImageDecoder newInstance(
            InputStream stream,
            boolean cropBorders,
            byte[] displayProfile
        ) {
            return create(stream);
        }

        private ImageDecoder create(InputStream stream) {
            Bitmap bitmap = BitmapFactory.decodeStream(stream);
            return bitmap == null ? null : new ImageDecoder(bitmap);
        }
    }
}
