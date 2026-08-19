package android.content.res;

import android.util.DisplayMetrics;

/** Headless system resources used by extensions to size generated content. */
public class Resources {
    private static final Resources SYSTEM = new Resources();

    private final DisplayMetrics displayMetrics;

    public Resources() {
        displayMetrics = new DisplayMetrics();
    }

    public static Resources getSystem() {
        return SYSTEM;
    }

    public DisplayMetrics getDisplayMetrics() {
        return displayMetrics;
    }

    public static class NotFoundException extends RuntimeException {
        public NotFoundException() {}
        public NotFoundException(String message) { super(message); }
    }
}
