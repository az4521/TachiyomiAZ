package java.util.logging;

import java.util.concurrent.ConcurrentHashMap;

/**
 * No-op JUL logger sufficient for OkHttp and Okio on iOS.
 */
public class Logger {
    private static final ConcurrentHashMap<String, Logger> LOGGERS =
        new ConcurrentHashMap<>();

    private final String name;

    protected Logger(String name, String resourceBundleName) {
        this.name = name;
    }

    public static Logger getLogger(String name) {
        Logger existing = LOGGERS.get(name);
        if (existing != null) {
            return existing;
        }
        Logger created = new Logger(name, null);
        Logger raced = LOGGERS.putIfAbsent(name, created);
        return raced == null ? created : raced;
    }

    public String getName() {
        return name;
    }

    public boolean isLoggable(Level level) {
        return false;
    }

    public void fine(String message) {
    }

    public void log(Level level, String message, Throwable thrown) {
    }
}
