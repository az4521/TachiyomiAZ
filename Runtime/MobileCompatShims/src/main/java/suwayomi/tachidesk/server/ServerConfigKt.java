package suwayomi.tachidesk.server;

/** Kotlin top-level accessor ABI expected by Suwayomi networking classes. */
public final class ServerConfigKt {
    private static final ServerConfig SERVER_CONFIG = new ServerConfig();

    private ServerConfigKt() {
    }

    public static ServerConfig getServerConfig() {
        return SERVER_CONFIG;
    }
}
