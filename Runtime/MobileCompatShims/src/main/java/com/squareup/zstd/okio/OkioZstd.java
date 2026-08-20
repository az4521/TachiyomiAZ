package com.squareup.zstd.okio;

import io.airlift.compress.zstd.ZstdInputStream;
import io.airlift.compress.zstd.ZstdOutputStream;
import java.io.IOException;
import okio.Okio;
import okio.Sink;
import okio.Source;

/**
 * Pure-Java adapter matching zstd-kmp's public Okio API.
 *
 * Extensions use these methods for HTTP decompression and private cache
 * files. Aircompressor provides the same streaming behavior without loading
 * an unavailable native Zstandard library on iOS.
 */
public final class OkioZstd {
    private static final byte[] EMPTY_BYTE_ARRAY = new byte[0];

    private OkioZstd() {
    }

    public static Sink zstdCompress(Sink sink) throws IOException {
        return Okio.sink(
            new ZstdOutputStream(Okio.buffer(sink).outputStream())
        );
    }

    public static Source zstdDecompress(Source source) {
        return Okio.source(
            new ZstdInputStream(Okio.buffer(source).inputStream())
        );
    }

    public static byte[] getEmptyByteArray() {
        return EMPTY_BYTE_ARRAY;
    }
}
