package java.util.logging;

import java.io.Serializable;

/**
 * Minimal JUL level surface used by OkHttp/Okio. OpenJDK/mobile currently
 * ships java.base only, so the full java.logging module is unavailable.
 */
public class Level implements Serializable {
    private static final long serialVersionUID = 1L;

    public static final Level OFF = new Level("OFF", Integer.MAX_VALUE);
    public static final Level SEVERE = new Level("SEVERE", 1000);
    public static final Level WARNING = new Level("WARNING", 900);
    public static final Level INFO = new Level("INFO", 800);
    public static final Level CONFIG = new Level("CONFIG", 700);
    public static final Level FINE = new Level("FINE", 500);
    public static final Level FINER = new Level("FINER", 400);
    public static final Level FINEST = new Level("FINEST", 300);
    public static final Level ALL = new Level("ALL", Integer.MIN_VALUE);

    private final String name;
    private final int value;

    protected Level(String name, int value) {
        this.name = name;
        this.value = value;
    }

    public String getName() {
        return name;
    }

    public int intValue() {
        return value;
    }

    @Override
    public String toString() {
        return name;
    }
}
