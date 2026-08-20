package android.graphics;

import app.tachiaz.compat.NativeBridge;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

public final class BitmapFactory {
    private BitmapFactory() {
    }

    public static Bitmap decodeByteArray(byte[] data, int offset, int length) {
        return decodeByteArray(data, offset, length, null);
    }

    public static Bitmap decodeByteArray(
        byte[] data,
        int offset,
        int length,
        Options options
    ) {
        if (
            data == null || offset < 0 || length < 0 ||
            offset + length > data.length
        ) {
            throw new ArrayIndexOutOfBoundsException();
        }
        long[] decoded = NativeBridge.bitmapDecode(data, offset, length);
        if (decoded == null || decoded.length < 3 || decoded[0] == 0) {
            return null;
        }
        if (options != null) {
            options.outWidth = (int) decoded[1];
            options.outHeight = (int) decoded[2];
            if (options.inJustDecodeBounds) {
                NativeBridge.bitmapRelease(decoded[0]);
                return null;
            }
        }
        Bitmap result = Bitmap.fromNative(decoded, Bitmap.Config.ARGB_8888);
        int sampleSize = options == null ? 1 : Math.max(1, options.inSampleSize);
        if (sampleSize <= 1 || result == null) {
            return result;
        }
        int width = Math.max(1, result.getWidth() / sampleSize);
        int height = Math.max(1, result.getHeight() / sampleSize);
        Bitmap sampled = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        new Canvas(sampled).drawBitmap(
            result,
            new Rect(0, 0, result.getWidth(), result.getHeight()),
            new Rect(0, 0, width, height),
            null
        );
        result.recycle();
        return sampled;
    }

    public static Bitmap decodeStream(InputStream stream) {
        return decodeStream(stream, null, null);
    }

    public static Bitmap decodeStream(
        InputStream stream,
        Rect outPadding,
        Options options
    ) {
        if (stream == null) {
            return null;
        }
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = stream.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            byte[] data = output.toByteArray();
            return decodeByteArray(data, 0, data.length, options);
        } catch (IOException error) {
            return null;
        }
    }

    public static class Options {
        public Bitmap inBitmap;
        public boolean inMutable;
        public boolean inJustDecodeBounds;
        public int inSampleSize = 1;
        public Bitmap.Config inPreferredConfig;
        public boolean inPremultiplied = true;
        public boolean inDither;
        public boolean inScaled = true;
        public int inDensity;
        public int inTargetDensity;
        public int inScreenDensity;
        public int outWidth = -1;
        public int outHeight = -1;
        public String outMimeType;
    }
}
