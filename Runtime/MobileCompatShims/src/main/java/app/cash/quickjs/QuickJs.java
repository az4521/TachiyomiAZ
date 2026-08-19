package app.cash.quickjs;

import app.tachiaz.compat.NativeBridge;
import java.io.Closeable;
import java.nio.charset.StandardCharsets;

/** iOS-safe implementation of the QuickJs API backed by JavaScriptCore. */
public final class QuickJs implements Closeable {
    private long nativeHandle;

    public static QuickJs create() {
        return new QuickJs();
    }

    public QuickJs() {
        nativeHandle = NativeBridge.javascriptCreate();
        if (nativeHandle == 0) {
            throw new IllegalStateException("Unable to create JavaScript context");
        }
    }

    public Object evaluate(String script) {
        requireOpen();
        return NativeBridge.javascriptEvaluate(nativeHandle, script);
    }

    public Object evaluate(String script, String fileName) {
        return evaluate(script);
    }

    public byte[] compile(String sourceCode, String fileName) {
        return sourceCode.getBytes(StandardCharsets.UTF_8);
    }

    public Object execute(byte[] bytecode) {
        return evaluate(new String(bytecode, StandardCharsets.UTF_8));
    }

    public <T> void set(String name, Class<T> type, T value) {
        if (value == null) {
            evaluate("globalThis[" + quote(name) + "] = null");
        } else if (value instanceof Number || value instanceof Boolean) {
            evaluate("globalThis[" + quote(name) + "] = " + value);
        } else if (value instanceof String) {
            evaluate("globalThis[" + quote(name) + "] = " + quote((String) value));
        } else {
            throw new UnsupportedOperationException(
                "JavaScript host objects are not supported on iOS"
            );
        }
    }

    public <T> T get(String name, Class<T> type) {
        Object value = evaluate("globalThis[" + quote(name) + "]");
        if (value == null || type.isInstance(value)) {
            return type.cast(value);
        }
        if (value instanceof Number) {
            Number number = (Number) value;
            if (type == Integer.class) return type.cast(number.intValue());
            if (type == Long.class) return type.cast(number.longValue());
            if (type == Double.class) return type.cast(number.doubleValue());
            if (type == Float.class) return type.cast(number.floatValue());
        }
        throw new ClassCastException(
            "JavaScript value cannot be converted to " + type.getName()
        );
    }

    @Override
    public void close() {
        if (nativeHandle != 0) {
            NativeBridge.javascriptClose(nativeHandle);
            nativeHandle = 0;
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            close();
        } finally {
            super.finalize();
        }
    }

    private void requireOpen() {
        if (nativeHandle == 0) {
            throw new IllegalStateException("JavaScript context is closed");
        }
    }

    private static String quote(String value) {
        StringBuilder result = new StringBuilder(value.length() + 2);
        result.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\\': result.append("\\\\"); break;
                case '"': result.append("\\\""); break;
                case '\n': result.append("\\n"); break;
                case '\r': result.append("\\r"); break;
                case '\t': result.append("\\t"); break;
                default: result.append(character);
            }
        }
        return result.append('"').toString();
    }
}
