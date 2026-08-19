package fixture;

public final class EchoExtension {
    static {
        requireContextClassLoader();
    }

    public String echo(String value) {
        requireContextClassLoader();
        return "echo:" + value;
    }

    private static void requireContextClassLoader() {
        if (Thread.currentThread().getContextClassLoader() == null) {
            throw new IllegalStateException("Context class loader is null");
        }
    }
}
