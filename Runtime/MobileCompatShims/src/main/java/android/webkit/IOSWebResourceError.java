package android.webkit;

/** Concrete WebResourceError used by the WKWebView compatibility provider. */
public final class IOSWebResourceError extends WebResourceError {
    private final int code;
    private final CharSequence description;

    public IOSWebResourceError(int code, CharSequence description) {
        this.code = code;
        this.description = description;
    }

    @Override public int getErrorCode() { return code; }
    @Override public CharSequence getDescription() { return description; }
}
