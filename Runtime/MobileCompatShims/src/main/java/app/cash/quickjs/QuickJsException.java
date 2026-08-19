package app.cash.quickjs;

public final class QuickJsException extends RuntimeException {
    public QuickJsException(String message) {
        super(message);
    }

    public QuickJsException(String message, String javaScriptStack) {
        super(
            javaScriptStack == null || javaScriptStack.isEmpty()
                ? message
                : message + "\n" + javaScriptStack
        );
    }
}
