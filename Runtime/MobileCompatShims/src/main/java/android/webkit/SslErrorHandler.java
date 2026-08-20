package android.webkit;

import android.os.Handler;
import android.os.Looper;

/** Non-throwing SSL decision object for WebView client callbacks. */
public class SslErrorHandler extends Handler {
    private boolean decided;
    private boolean proceeding;

    public SslErrorHandler() {
        super(Looper.getMainLooper());
    }

    public void proceed() {
        decided = true;
        proceeding = true;
    }

    public void cancel() {
        decided = true;
        proceeding = false;
    }

    public boolean isDecided() {
        return decided;
    }

    public boolean isProceeding() {
        return decided && proceeding;
    }
}
