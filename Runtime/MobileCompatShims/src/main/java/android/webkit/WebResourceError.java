package android.webkit;

/** Android API base class without the android.jar throwing stub constructor. */
public abstract class WebResourceError {
    protected WebResourceError() {
    }

    public abstract int getErrorCode();
    public abstract CharSequence getDescription();
}
