package android.security;

/**
 * iOS transport policy for Android networking libraries.
 *
 * Extension sources such as Komga accept user-configured HTTP servers. Android's platform
 * implementation consults the app manifest, but the compatibility JAR previously supplied only
 * an SDK stub whose methods throw "Stub!". The iOS app explicitly permits those endpoints through
 * App Transport Security, so expose that policy to OkHttp and extension code as well.
 */
public final class NetworkSecurityPolicy {
    private static final NetworkSecurityPolicy INSTANCE =
        new NetworkSecurityPolicy();

    private NetworkSecurityPolicy() {
    }

    public static NetworkSecurityPolicy getInstance() {
        return INSTANCE;
    }

    public boolean isCleartextTrafficPermitted() {
        return true;
    }

    public boolean isCleartextTrafficPermitted(String hostname) {
        return true;
    }
}
