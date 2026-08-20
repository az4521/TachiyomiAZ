package android.util;

public final class Base64 {
    public static final int DEFAULT = 0;
    public static final int NO_PADDING = 1;
    public static final int NO_WRAP = 2;
    public static final int CRLF = 4;
    public static final int URL_SAFE = 8;
    public static final int NO_CLOSE = 16;

    private Base64() {
    }

    public static byte[] decode(String value, int flags) {
        return decoder(flags).decode(value);
    }

    public static byte[] decode(byte[] value, int flags) {
        return decoder(flags).decode(value);
    }

    public static String encodeToString(byte[] value, int flags) {
        java.util.Base64.Encoder encoder = encoder(flags);
        if ((flags & NO_PADDING) != 0) {
            encoder = encoder.withoutPadding();
        }
        String encoded = encoder.encodeToString(value);
        if ((flags & NO_WRAP) != 0) {
            return encoded;
        }
        String newline = (flags & CRLF) != 0 ? "\r\n" : "\n";
        StringBuilder wrapped = new StringBuilder();
        for (int index = 0; index < encoded.length(); index += 76) {
            if (index > 0) {
                wrapped.append(newline);
            }
            wrapped.append(
                encoded,
                index,
                Math.min(index + 76, encoded.length())
            );
        }
        return wrapped.toString();
    }

    public static byte[] encode(byte[] value, int flags) {
        return encodeToString(value, flags)
            .getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }

    private static java.util.Base64.Decoder decoder(int flags) {
        return (flags & URL_SAFE) != 0
            ? java.util.Base64.getUrlDecoder()
            : java.util.Base64.getMimeDecoder();
    }

    private static java.util.Base64.Encoder encoder(int flags) {
        return (flags & URL_SAFE) != 0
            ? java.util.Base64.getUrlEncoder()
            : java.util.Base64.getEncoder();
    }
}
