package okhttp3.zstd;

import com.squareup.zstd.okio.OkioZstd;
import okhttp3.CompressionInterceptor;
import okio.BufferedSource;
import okio.Source;

/**
 * Mobile-safe replacement for OkHttp's optional native Zstandard adapter.
 *
 * TachiyomiX 1.6 extensions may install this adapter unconditionally. iOS
 * cannot load the desktop native library bundled by zstd-kmp, so this class
 * routes decompression through the bundled pure-Java implementation.
 */
public final class Zstd
    implements CompressionInterceptor.DecompressionAlgorithm {
    public static final Zstd INSTANCE = new Zstd();

    private Zstd() {
    }

    @Override
    public String getEncoding() {
        return "zstd";
    }

    @Override
    public Source decompress(BufferedSource source) {
        return OkioZstd.zstdDecompress(source);
    }
}
