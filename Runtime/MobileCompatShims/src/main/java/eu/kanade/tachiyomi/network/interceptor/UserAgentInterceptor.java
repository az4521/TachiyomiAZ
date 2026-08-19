package eu.kanade.tachiyomi.network.interceptor;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import kotlin.jvm.functions.Function0;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Mobile-compatible default user-agent interceptor.
 *
 * HttpSource caches its default headers, so changing NetworkHelper's provider
 * alone leaves the former default on existing sources. Remembering defaults
 * that this process has supplied lets settings changes update those cached
 * values without replacing an extension's explicit custom user agent.
 */
public final class UserAgentInterceptor implements Interceptor {
    public static final String BUILT_IN_DEFAULT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/120.0.0.0 Safari/537.36";

    private static final Set<String> KNOWN_DEFAULTS =
        ConcurrentHashMap.newKeySet();

    static {
        KNOWN_DEFAULTS.add(BUILT_IN_DEFAULT);
    }

    private final Function0<String> defaultUserAgentProvider;
    private volatile String forcedUserAgent;

    public UserAgentInterceptor(Function0<String> defaultUserAgentProvider) {
        this.defaultUserAgentProvider = defaultUserAgentProvider;
    }

    /** Returns the user agent that should be sent for an existing header. */
    public String effectiveUserAgent(String existing) {
        String forced = forcedUserAgent;
        if (forced != null && !forced.isEmpty()) {
            return forced;
        }
        String configured = defaultUserAgentProvider.invoke();
        if (configured == null || configured.trim().isEmpty()) {
            configured = BUILT_IN_DEFAULT;
        }
        KNOWN_DEFAULTS.add(configured);
        if (existing == null || existing.isEmpty() || KNOWN_DEFAULTS.contains(existing)) {
            return configured;
        }
        return existing;
    }

    /** Pins requests to the browser fingerprint that issued a clearance cookie. */
    public void forceUserAgent(String userAgent) {
        forcedUserAgent = userAgent == null ? null : userAgent.trim();
    }

    public void clearForcedUserAgent() {
        forcedUserAgent = null;
    }

    @Override
    public Response intercept(Interceptor.Chain chain) throws IOException {
        Request original = chain.request();
        String existing = original.header("User-Agent");
        String effective = effectiveUserAgent(existing);
        if (effective.equals(existing)) {
            return chain.proceed(original);
        }
        Request request = original.newBuilder()
            .removeHeader("User-Agent")
            .addHeader("User-Agent", effective)
            .build();
        return chain.proceed(request);
    }
}
