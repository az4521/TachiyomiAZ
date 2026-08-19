package app.tachiaz.runtime;

import com.squareup.zstd.okio.OkioZstd;
import java.nio.charset.StandardCharsets;
import okhttp3.zstd.Zstd;
import okio.Buffer;
import okio.Sink;
import okio.Source;

public final class MobileZstdShimTest {
    private MobileZstdShimTest() {
    }

    public static void main(String[] arguments) throws Exception {
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < 16; index++) {
            text.append(
                "TachiyomiAZ pure Java Zstandard compatibility test "
            ).append("with enough repeated text to exercise compression. ");
        }
        byte[] expected = text.toString().getBytes(StandardCharsets.UTF_8);

        Buffer compressed = new Buffer();
        Sink sink = OkioZstd.zstdCompress(compressed);
        Buffer input = new Buffer().write(expected);
        sink.write(input, input.size());
        sink.close();
        if (compressed.size() >= expected.length) {
            throw new AssertionError("Zstandard output was not compressed");
        }

        Source source = OkioZstd.zstdDecompress(compressed);
        Buffer decoded = new Buffer();
        decoded.writeAll(source);
        source.close();
        byte[] actual = decoded.readByteArray();
        if (!java.util.Arrays.equals(expected, actual)) {
            throw new AssertionError("Zstandard round trip changed data");
        }
        if (!"zstd".equals(Zstd.INSTANCE.getEncoding())) {
            throw new AssertionError("OkHttp Zstandard encoding is invalid");
        }
        System.out.println("Mobile Zstandard shim test passed");
    }
}
