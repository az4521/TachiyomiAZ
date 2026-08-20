package android.graphics.pdf;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.os.ParcelFileDescriptor;
import app.tachiaz.compat.NativeBridge;
import java.io.Closeable;
import java.io.IOException;

/** CoreGraphics-backed implementation of the PdfRenderer API used by extensions. */
public final class PdfRenderer implements Closeable {
    private long nativeHandle;
    private final int pageCount;
    private Page currentPage;

    public PdfRenderer(ParcelFileDescriptor descriptor) throws IOException {
        if (descriptor == null) {
            throw new NullPointerException("descriptor");
        }
        nativeHandle = NativeBridge.pdfOpen(descriptor.getFile().getAbsolutePath());
        if (nativeHandle == 0) {
            throw new IOException("Unable to open PDF document");
        }
        pageCount = NativeBridge.pdfPageCount(nativeHandle);
    }

    public int getPageCount() {
        requireOpen();
        return pageCount;
    }

    public boolean shouldScaleForPrinting() {
        requireOpen();
        return false;
    }

    public Page openPage(int index) {
        requireOpen();
        if (currentPage != null) {
            throw new IllegalStateException("Current page not closed");
        }
        if (index < 0 || index >= pageCount) {
            throw new IllegalArgumentException("Page index out of bounds");
        }
        int[] size = NativeBridge.pdfPageSize(nativeHandle, index);
        if (size == null || size.length < 2 || size[0] <= 0 || size[1] <= 0) {
            throw new IllegalStateException("Unable to read PDF page dimensions");
        }
        currentPage = new Page(index, size[0], size[1]);
        return currentPage;
    }

    @Override
    public void close() {
        if (currentPage != null) {
            throw new IllegalStateException("Current page not closed");
        }
        if (nativeHandle != 0) {
            NativeBridge.pdfClose(nativeHandle);
            nativeHandle = 0;
        }
    }

    private void requireOpen() {
        if (nativeHandle == 0) {
            throw new IllegalStateException("Renderer is closed");
        }
    }

    public final class Page implements Closeable {
        public static final int RENDER_MODE_FOR_DISPLAY = 1;
        public static final int RENDER_MODE_FOR_PRINT = 2;

        private final int index;
        private final int width;
        private final int height;
        private boolean closed;

        private Page(int index, int width, int height) {
            this.index = index;
            this.width = width;
            this.height = height;
        }

        public int getIndex() {
            return index;
        }

        public int getWidth() {
            requirePageOpen();
            return width;
        }

        public int getHeight() {
            requirePageOpen();
            return height;
        }

        public void render(
            Bitmap destination,
            Rect destinationClip,
            Matrix transform,
            int renderMode
        ) {
            requirePageOpen();
            if (destination == null) {
                throw new NullPointerException("destination");
            }
            if (transform != null) {
                throw new UnsupportedOperationException(
                    "PDF render matrices are not used by Tachiyomi extensions"
                );
            }
            if (
                renderMode != RENDER_MODE_FOR_DISPLAY &&
                renderMode != RENDER_MODE_FOR_PRINT
            ) {
                throw new IllegalArgumentException("Invalid render mode");
            }
            Rect clip = destinationClip == null
                ? new Rect(0, 0, destination.getWidth(), destination.getHeight())
                : destinationClip;
            if (
                clip.left < 0 || clip.top < 0 ||
                clip.right > destination.getWidth() ||
                clip.bottom > destination.getHeight() ||
                clip.isEmpty()
            ) {
                throw new IllegalArgumentException("Invalid destination clip");
            }
            if (!NativeBridge.pdfRender(
                nativeHandle,
                destination.nativeHandle(),
                new int[] {
                    index,
                    clip.left,
                    clip.top,
                    clip.right,
                    clip.bottom
                }
            )) {
                throw new IllegalStateException("Unable to render PDF page");
            }
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                currentPage = null;
            }
        }

        private void requirePageOpen() {
            requireOpen();
            if (closed) {
                throw new IllegalStateException("Page is closed");
            }
        }
    }
}
