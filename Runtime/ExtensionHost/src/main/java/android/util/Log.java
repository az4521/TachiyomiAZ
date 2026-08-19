package android.util;

public final class Log {
    public static final int VERBOSE = 2;
    public static final int DEBUG = 3;
    public static final int INFO = 4;
    public static final int WARN = 5;
    public static final int ERROR = 6;
    public static final int ASSERT = 7;

    private Log() {
    }

    public static int v(String tag, String message) {
        return print("VERBOSE", tag, message, null);
    }

    public static int d(String tag, String message) {
        return print("DEBUG", tag, message, null);
    }

    public static int d(String tag, String message, Throwable error) {
        return print("DEBUG", tag, message, error);
    }

    public static int i(String tag, String message) {
        return print("INFO", tag, message, null);
    }

    public static int w(String tag, String message) {
        return print("WARN", tag, message, null);
    }

    public static int w(String tag, String message, Throwable error) {
        return print("WARN", tag, message, error);
    }

    public static int e(String tag, String message) {
        return print("ERROR", tag, message, null);
    }

    public static int e(String tag, String message, Throwable error) {
        return print("ERROR", tag, message, error);
    }

    public static int wtf(String tag, String message) {
        return print("ASSERT", tag, message, null);
    }

    public static int wtf(String tag, String message, Throwable error) {
        return print("ASSERT", tag, message, error);
    }

    public static int println(int priority, String tag, String message) {
        return print(Integer.toString(priority), tag, message, null);
    }

    public static String getStackTraceString(Throwable error) {
        if (error == null) {
            return "";
        }
        java.io.StringWriter writer = new java.io.StringWriter();
        error.printStackTrace(new java.io.PrintWriter(writer));
        return writer.toString();
    }

    private static int print(
        String level,
        String tag,
        String message,
        Throwable error
    ) {
        String line = level + "/" + tag + ": " + message;
        if ("ERROR".equals(level) || "WARN".equals(level)) {
            System.err.println(line);
        } else {
            System.out.println(line);
        }
        if (error != null) {
            error.printStackTrace(System.err);
        }
        return message == null ? 0 : message.length();
    }
}
