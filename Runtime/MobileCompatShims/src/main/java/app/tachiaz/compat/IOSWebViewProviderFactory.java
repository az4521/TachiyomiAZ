package app.tachiaz.compat;

import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.IOSRenderProcessGoneDetail;
import android.webkit.IOSWebResourceError;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebViewProvider;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.CookieHandler;
import java.net.HttpCookie;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import xyz.nulldev.androidcompat.CallableArgument;
import xyz.nulldev.androidcompat.webkit.KcefWebSettings;

/** AndroidCompat WebView provider backed by the app's native WKWebView. */
public final class IOSWebViewProviderFactory
    implements CallableArgument<WebView, WebViewProvider> {

    private static final Map<Long, Provider> PROVIDERS = new ConcurrentHashMap<>();
    private static final ExecutorService CALLBACK_EXECUTOR =
        Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "tachiyomiaz-webkit-callback");
            thread.setDaemon(true);
            return thread;
        });

    public static void install() throws Exception {
        WebView.setProviderFactory(new IOSWebViewProviderFactory());
        Field singleton = CookieManager.class.getDeclaredField("INSTANCE");
        singleton.setAccessible(true);
        singleton.set(null, new IOSCookieManager());
    }

    /** Entry point called from the reverse JNI bridge. */
    public static String dispatchEvent(
        long handle,
        String event,
        String argument1,
        String argument2
    ) {
        Provider provider = PROVIDERS.get(handle);
        if (provider == null) return "";
        try {
            return provider.event(event, argument1, argument2);
        } catch (Throwable error) {
            return "__ERROR__" + error.getClass().getName() + ": " + error.getMessage();
        }
    }

    @Override
    public WebViewProvider call(WebView view) {
        Provider provider = new Provider(view);
        return (WebViewProvider) Proxy.newProxyInstance(
            WebViewProvider.class.getClassLoader(),
            new Class<?>[] {WebViewProvider.class},
            provider
        );
    }

    private static final class Provider implements InvocationHandler {
        private final WebView view;
        private final WebSettings settings = new KcefWebSettings();
        private final Handler mainHandler = new Handler(Looper.getMainLooper());
        private final Map<String, Object> javascriptInterfaces = new ConcurrentHashMap<>();
        private long handle;
        private WebViewClient webViewClient = new WebViewClient();
        private WebChromeClient webChromeClient;
        private boolean paused;

        Provider(WebView view) {
            this.view = view;
            String value = command("create", 0, "false", null);
            try {
                handle = Long.parseLong(value);
            } catch (RuntimeException error) {
                throw new IllegalStateException(
                    "Unable to create the iOS WebView backend: " + value,
                    error
                );
            }
            PROVIDERS.put(handle, this);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] arguments) {
            String name = method.getName();
            Object[] args = arguments == null ? new Object[0] : arguments;
            if (name.equals("toString")) return "IOSWebViewProvider(" + handle + ")";
            if (name.equals("hashCode")) return System.identityHashCode(this);
            if (name.equals("equals")) return args.length == 1 && proxy == args[0];

            switch (name) {
                case "init": return null;
                case "destroy":
                    if (handle != 0) {
                        long oldHandle = handle;
                        PROVIDERS.remove(oldHandle);
                        handle = 0;
                        command("destroy", oldHandle, null, null);
                    }
                    javascriptInterfaces.clear();
                    return null;
                case "loadUrl":
                    applySettings((String) args[0]);
                    command(
                        "load",
                        handle,
                        (String) args[0],
                        encodeHeaders(args.length > 1 ? castHeaders(args[1]) : null)
                    );
                    return null;
                case "postUrl":
                    applySettings((String) args[0]);
                    command(
                        "post",
                        handle,
                        (String) args[0],
                        Base64.getEncoder().encodeToString((byte[]) args[1])
                    );
                    return null;
                case "loadData":
                    applySettings(null);
                    loadData(null, (String) args[0], (String) args[2]);
                    return null;
                case "loadDataWithBaseURL":
                    applySettings((String) args[0]);
                    loadData((String) args[0], (String) args[1], (String) args[3]);
                    return null;
                case "evaluateJavaScript":
                    String result = command("evaluate", handle, (String) args[0], null);
                    if (args.length > 1 && args[1] != null) {
                        @SuppressWarnings("unchecked")
                        ValueCallback<String> callback = (ValueCallback<String>) args[1];
                        callback.onReceiveValue(result);
                    }
                    return null;
                case "addJavascriptInterface":
                    addJavascriptInterface(args[0], (String) args[1]);
                    return null;
                case "removeJavascriptInterface":
                    javascriptInterfaces.remove((String) args[0]);
                    return run("removeJSInterface", (String) args[0]);
                case "stopLoading": return run("stop");
                case "reload": return run("reload");
                case "goBack": return run("goBack");
                case "goForward": return run("goForward");
                case "goBackOrForward": return run("go", Integer.toString((Integer) args[0]));
                case "canGoBack": return bool(command("canGoBack", handle, null, null));
                case "canGoForward": return bool(command("canGoForward", handle, null, null));
                case "canGoBackOrForward":
                    return bool(command("canGo", handle, Integer.toString((Integer) args[0]), null));
                case "getUrl": return emptyToNull(command("url", handle, null, null));
                case "getOriginalUrl": return emptyToNull(command("originalUrl", handle, null, null));
                case "getTitle": return emptyToNull(command("title", handle, null, null));
                case "getProgress": return integer(command("progress", handle, null, null));
                case "getContentHeight": return integer(command("contentHeight", handle, null, null));
                case "getContentWidth": return integer(command("contentWidth", handle, null, null));
                case "clearCache": return run("clearCache", Boolean.toString((Boolean) args[0]));
                case "clearHistory": return run("clearHistory");
                case "onPause": paused = true; return null;
                case "onResume": paused = false; return null;
                case "isPaused": return paused;
                case "isPrivateBrowsingEnabled": return false;
                case "setWebViewClient":
                    webViewClient = args[0] == null ? new WebViewClient() : (WebViewClient) args[0];
                    return null;
                case "getWebViewClient": return webViewClient;
                case "setWebChromeClient": webChromeClient = (WebChromeClient) args[0]; return null;
                case "getWebChromeClient": return webChromeClient;
                case "getSettings": return settings;
                case "insertVisualStateCallback":
                    if (args[1] != null) {
                        ((WebView.VisualStateCallback) args[1]).onComplete((Long) args[0]);
                    }
                    return null;
                case "getViewDelegate":
                case "getScrollDelegate": return defaultProxy(method.getReturnType());
                case "zoomBy":
                case "zoomIn":
                case "zoomOut": return false;
                case "getScale": return 1.0f;
                case "getRendererRequestedPriority": return 0;
                case "getRendererPriorityWaivedWhenNotVisible": return false;
                default: return defaultValue(method.getReturnType());
            }
        }

        String event(String event, String argument1, String argument2) throws Exception {
            switch (event) {
                case "shouldOverride":
                    return Boolean.toString(callOnMain(() -> shouldOverride(decodeRequest(argument1))));
                case "intercept": {
                    Future<String> future = CALLBACK_EXECUTOR.submit(() ->
                        encodeResponse(intercept(decodeRequest(argument1)))
                    );
                    return future.get();
                }
                case "pageStarted":
                    postMain(() -> webViewClient.onPageStarted(view, argument1, (Bitmap) null));
                    return "";
                case "pageFinished":
                    postMain(() -> webViewClient.onPageFinished(view, argument1));
                    return "";
                case "progress":
                    postMain(() -> {
                        if (webChromeClient != null) {
                            webChromeClient.onProgressChanged(view, integer(argument1));
                        }
                    });
                    return "";
                case "title":
                    postMain(() -> {
                        if (webChromeClient != null) webChromeClient.onReceivedTitle(view, argument1);
                    });
                    return "";
                case "console":
                    postMain(() -> dispatchConsole(argument1));
                    return "";
                case "error":
                    postMain(() -> dispatchError(argument1, argument2));
                    return "";
                case "renderGone":
                    postMain(() -> webViewClient.onRenderProcessGone(
                        view,
                        new IOSRenderProcessGoneDetail("true".equals(argument1), 0)
                    ));
                    return "";
                case "jsBridge":
                    CALLBACK_EXECUTOR.execute(() -> dispatchJavascript(argument1, argument2));
                    return "";
                case "jsBridgeSync":
                    return dispatchJavascript(argument1, argument2);
                default: return "";
            }
        }

        private boolean shouldOverride(WebResourceRequest request) {
            if (overrides(webViewClient, "shouldOverrideUrlLoading", WebView.class, WebResourceRequest.class)) {
                return webViewClient.shouldOverrideUrlLoading(view, request);
            }
            return webViewClient.shouldOverrideUrlLoading(view, request.getUrl().toString());
        }

        private WebResourceResponse intercept(WebResourceRequest request) {
            if (overrides(
                webViewClient,
                "shouldInterceptRequest",
                WebView.class,
                WebResourceRequest.class
            )) {
                return webViewClient.shouldInterceptRequest(view, request);
            }
            return webViewClient.shouldInterceptRequest(view, request.getUrl().toString());
        }

        private void dispatchError(String requestPayload, String errorPayload) {
            WebResourceRequest request = decodeRequest(requestPayload);
            String[] fields = errorPayload.split("\\n", -1);
            int code = fields.length > 0 ? integer(fields[0]) : WebViewClient.ERROR_UNKNOWN;
            String description = fields.length > 1 ? decode(fields[1]) : "Unknown WebKit error";
            IOSWebResourceError error = new IOSWebResourceError(code, description);
            webViewClient.onReceivedError(view, request, error);
            if (!overrides(webViewClient, "onReceivedError", WebView.class, WebResourceRequest.class, android.webkit.WebResourceError.class)) {
                webViewClient.onReceivedError(view, code, description, request.getUrl().toString());
            }
        }

        private void dispatchConsole(String payload) {
            if (webChromeClient == null) return;
            String[] fields = payload.split("\\n", -1);
            if (fields.length < 4) return;
            ConsoleMessage.MessageLevel level;
            try {
                level = ConsoleMessage.MessageLevel.valueOf(fields[0]);
            } catch (RuntimeException ignored) {
                level = ConsoleMessage.MessageLevel.LOG;
            }
            webChromeClient.onConsoleMessage(new ConsoleMessage(
                decode(fields[1]),
                decode(fields[2]),
                integer(fields[3]),
                level
            ));
        }

        private void addJavascriptInterface(Object object, String name) {
            if (object == null || name == null || name.isEmpty()) return;
            javascriptInterfaces.put(name, object);
            command(
                "addJSInterface",
                handle,
                name,
                asynchronousJavascriptMethods(object)
            );
        }

        private static String asynchronousJavascriptMethods(Object target) {
            StringBuilder output = new StringBuilder();
            for (Method method : target.getClass().getMethods()) {
                if (
                    method.isAnnotationPresent(JavascriptInterface.class) &&
                    method.getReturnType() == Void.TYPE
                ) {
                    if (output.length() > 0) output.append('\n');
                    output.append(method.getName())
                        .append('\t')
                        .append(method.getParameterTypes().length);
                }
            }
            return output.toString();
        }

        private String dispatchJavascript(String name, String payload) {
            Object target = javascriptInterfaces.get(name);
            if (target == null) return "null";
            String methodName = "";
            String message = payload == null ? "" : payload;
            int separator = message.indexOf('\n');
            String[] arguments = new String[0];
            if (separator >= 0) {
                methodName = message.substring(0, separator);
                message = message.substring(separator + 1);
                int countSeparator = message.indexOf('\n');
                try {
                    int count = Integer.parseInt(message.substring(0, countSeparator));
                    String[] encoded = message.substring(countSeparator + 1)
                        .split("\\t", -1);
                    if (count >= 0 && encoded.length == count) {
                        arguments = new String[count];
                        for (int index = 0; index < count; index++) {
                            arguments[index] = decodeJavascriptArgument(encoded[index]);
                        }
                    }
                } catch (RuntimeException ignored) {
                    // Backward compatibility with the former one-string payload.
                    arguments = new String[] {decodeJavascriptArgument(message)};
                }
            }
            for (Method method : target.getClass().getMethods()) {
                if (
                    (methodName.isEmpty() || method.getName().equals(methodName)) &&
                    method.isAnnotationPresent(JavascriptInterface.class) &&
                    (methodName.isEmpty() || method.getParameterTypes().length == arguments.length)
                ) {
                    try {
                        method.setAccessible(true);
                        Class<?>[] types = method.getParameterTypes();
                        Object[] converted = new Object[types.length];
                        for (int index = 0; index < types.length; index++) {
                            converted[index] = convertJavascriptArgument(arguments[index], types[index]);
                        }
                        return encodeJavascriptResult(method.invoke(target, converted));
                    } catch (Throwable error) {
                        throw new RuntimeException("JavaScript interface callback failed", error);
                    }
                }
            }
            return "null";
        }

        private static String decodeJavascriptArgument(String value) {
            try {
                return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException ignored) {
                return value;
            }
        }

        private static Object convertJavascriptArgument(String value, Class<?> type) {
            if (type == String.class || type == Object.class) return value;
            if (type == boolean.class || type == Boolean.class) return Boolean.parseBoolean(value);
            if (type == byte.class || type == Byte.class) return Byte.parseByte(value);
            if (type == short.class || type == Short.class) return Short.parseShort(value);
            if (type == int.class || type == Integer.class) return Integer.parseInt(value);
            if (type == long.class || type == Long.class) return Long.parseLong(value);
            if (type == float.class || type == Float.class) return Float.parseFloat(value);
            if (type == double.class || type == Double.class) return Double.parseDouble(value);
            if (type == char.class || type == Character.class) return value.isEmpty() ? '\0' : value.charAt(0);
            throw new IllegalArgumentException("Unsupported JavaScript interface type: " + type.getName());
        }

        private static String encodeJavascriptResult(Object value) {
            if (value == null) return "null";
            if (value instanceof Number || value instanceof Boolean) return String.valueOf(value);
            return "\"" + String.valueOf(value).replace("\\", "\\\\")
                .replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\"";
        }

        private void loadData(String baseUrl, String data, String encoding) {
            String encoded = "base64".equalsIgnoreCase(encoding)
                ? data
                : Base64.getEncoder().encodeToString(data.getBytes(StandardCharsets.UTF_8));
            command("loadHTML", handle, baseUrl, encoded);
        }

        private void applySettings(String url) {
            String cookieHeader = null;
            if (url != null && !url.isEmpty()) {
                try {
                    cookieHeader = CookieManager.getInstance().getCookie(url);
                } catch (RuntimeException ignored) {}
            }
            String userAgent = navigationUserAgent(
                settings.getUserAgentString(),
                System.getProperty("http.agent", ""),
                cookieHeader,
                hasExplicitUserAgent(settings)
            );
            command("userAgent", handle, userAgent, null);
            String flags = Boolean.toString(settings.getJavaScriptEnabled()) + "\n" +
                Boolean.toString(settings.getDomStorageEnabled()) + "\n" +
                Boolean.toString(settings.getBlockNetworkImage()) + "\n" +
                Boolean.toString(settings.getUseWideViewPort()) + "\n" +
                Boolean.toString(settings.getLoadWithOverviewMode());
            command("settings", handle, flags, null);
        }

        /**
         * Browser challenges and local-storage tokens can both be bound to the
         * fingerprint that solved them. Untouched WebSettings inherit the
         * committed browser/HTTP session agent. Preserve a source's deliberate
         * custom agent, except when a Cloudflare cookie requires the solver's
         * exact agent.
         */
        private static String navigationUserAgent(
            String configured,
            String session,
            String cookieHeader,
            boolean explicitlyConfigured
        ) {
            if (session == null || session.isEmpty()) {
                return configured;
            }
            // KcefWebSettings derives its untouched default from http.agent;
            // that is inherited state, not an extension-authored override.
            if (
                configured == null ||
                configured.isEmpty() ||
                !explicitlyConfigured
            ) {
                return session;
            }
            if (cookieHeader != null) {
                for (String cookie : cookieHeader.split(";")) {
                    if (cookie.trim().startsWith("cf_clearance=")) {
                        return session;
                    }
                }
            }
            return configured;
        }

        private static boolean hasExplicitUserAgent(WebSettings settings) {
            Class<?> current = settings.getClass();
            while (current != null) {
                try {
                    Field field = current.getDeclaredField("userAgentString");
                    field.setAccessible(true);
                    return field.get(settings) != null;
                } catch (NoSuchFieldException ignored) {
                    current = current.getSuperclass();
                } catch (ReflectiveOperationException ignored) {
                    // Preserve a possibly explicit override if an alternate
                    // WebSettings implementation hides its backing state.
                    return true;
                }
            }
            return true;
        }

        private Object run(String operation) {
            command(operation, handle, null, null);
            return null;
        }

        private Object run(String operation, String argument) {
            command(operation, handle, argument, null);
            return null;
        }

        private void postMain(Runnable runnable) {
            mainHandler.post(runnable);
        }

        private <T> T callOnMain(Callable<T> callable) throws Exception {
            if (mainHandler.getLooper().isCurrentThread()) return callable.call();
            FutureTask<T> task = new FutureTask<>(callable);
            mainHandler.post(task);
            try {
                return task.get();
            } catch (ExecutionException error) {
                Throwable cause = error.getCause();
                if (cause instanceof Exception) throw (Exception) cause;
                throw error;
            }
        }
    }

    private static final class Request implements WebResourceRequest {
        private final Uri url;
        private final String method;
        private final boolean mainFrame;
        private final boolean redirect;
        private final boolean gesture;
        private final Map<String, String> headers;

        Request(String payload) {
            String[] fields = payload.split("\\n", -1);
            url = Uri.parse(fields.length > 0 ? decode(fields[0]) : "about:blank");
            method = fields.length > 1 ? decode(fields[1]) : "GET";
            mainFrame = fields.length > 2 && bool(fields[2]);
            redirect = fields.length > 3 && bool(fields[3]);
            gesture = fields.length > 4 && bool(fields[4]);
            headers = fields.length > 5 ? decodeHeaders(decode(fields[5])) : Collections.emptyMap();
        }

        @Override public Uri getUrl() { return url; }
        @Override public boolean isForMainFrame() { return mainFrame; }
        @Override public boolean isRedirect() { return redirect; }
        @Override public boolean hasGesture() { return gesture; }
        @Override public String getMethod() { return method; }
        @Override public Map<String, String> getRequestHeaders() { return headers; }
    }

    private static WebResourceRequest decodeRequest(String payload) {
        return new Request(payload == null ? "" : payload);
    }

    private static String encodeResponse(WebResourceResponse response) throws Exception {
        if (response == null) return "";
        byte[] body = readAll(response.getData());
        return response.getStatusCode() + "\n" +
            encode(nullToEmpty(response.getReasonPhrase())) + "\n" +
            encode(nullToEmpty(response.getMimeType())) + "\n" +
            encode(nullToEmpty(response.getEncoding())) + "\n" +
            encode(encodeHeaders(response.getResponseHeaders())) + "\n" +
            Base64.getEncoder().encodeToString(body);
    }

    private static byte[] readAll(InputStream input) throws Exception {
        if (input == null) return new byte[0];
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int count;
            int total = 0;
            while ((count = stream.read(buffer)) >= 0) {
                total += count;
                if (total > 64 * 1024 * 1024) {
                    throw new IllegalStateException("Intercepted WebView response exceeds 64 MiB");
                }
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static boolean overrides(Object target, String name, Class<?>... arguments) {
        try {
            return target.getClass().getMethod(name, arguments).getDeclaringClass() != WebViewClient.class;
        } catch (NoSuchMethodException ignored) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> castHeaders(Object value) {
        return value == null ? Collections.emptyMap() : (Map<String, String>) value;
    }

    private static String encodeHeaders(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) return "";
        StringBuilder output = new StringBuilder();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (output.length() > 0) output.append('\n');
            output.append(encode(entry.getKey())).append(':').append(encode(entry.getValue()));
        }
        return output.toString();
    }

    private static Map<String, String> decodeHeaders(String value) {
        if (value == null || value.isEmpty()) return Collections.emptyMap();
        Map<String, String> output = new LinkedHashMap<>();
        for (String line : value.split("\\n")) {
            int separator = line.indexOf(':');
            if (separator <= 0) continue;
            output.put(decode(line.substring(0, separator)), decode(line.substring(separator + 1)));
        }
        return output;
    }

    private static String encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        try {
            return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static final class IOSCookieManager extends CookieManager {
        private final java.net.CookieManager javaCookies;
        private boolean acceptCookies = true;
        private boolean acceptThirdPartyCookies = true;
        private boolean acceptFileCookies;

        IOSCookieManager() {
            CookieHandler handler = CookieHandler.getDefault();
            javaCookies = handler instanceof java.net.CookieManager
                ? (java.net.CookieManager) handler
                : new java.net.CookieManager();
            if (!(handler instanceof java.net.CookieManager)) CookieHandler.setDefault(javaCookies);
        }

        @Override public void setAcceptCookie(boolean accept) { acceptCookies = accept; }
        @Override public boolean acceptCookie() { return acceptCookies; }
        @Override public void setAcceptThirdPartyCookies(WebView view, boolean accept) {
            acceptThirdPartyCookies = accept;
        }
        @Override public boolean acceptThirdPartyCookies(WebView view) { return acceptThirdPartyCookies; }
        @Override public void setCookie(String url, String value) {
            if (!acceptCookies || value == null) return;
            command("cookieSet", 0, url, value);
            try {
                URI uri = normalizedUri(url);
                for (HttpCookie cookie : HttpCookie.parse(value)) {
                    javaCookies.getCookieStore().add(uri, cookie);
                }
            } catch (RuntimeException ignored) {}
        }
        @Override public void setCookie(String url, String value, ValueCallback<Boolean> callback) {
            setCookie(url, value);
            if (callback != null) callback.onReceiveValue(true);
        }
        @Override public String getCookie(String url) {
            return emptyToNull(command("cookieGet", 0, url, null));
        }
        @Deprecated @Override public void removeSessionCookie() { removeSessionCookies(null); }
        @Override public void removeSessionCookies(ValueCallback<Boolean> callback) {
            boolean removed = bool(command("cookieRemoveSession", 0, null, null));
            removeJavaSessionCookies();
            if (callback != null) callback.onReceiveValue(removed);
        }
        @Deprecated @Override public void removeExpiredCookie() {}
        @Deprecated @Override public void removeAllCookie() { removeAllCookies(null); }
        @Override public void removeAllCookies(ValueCallback<Boolean> callback) {
            boolean removed = bool(command("cookieRemoveAll", 0, null, null));
            javaCookies.getCookieStore().removeAll();
            if (callback != null) callback.onReceiveValue(removed);
        }
        @Override public boolean hasCookies() { return bool(command("cookieHas", 0, null, null)); }
        @Override public void flush() { command("cookieFlush", 0, null, null); }
        @Override public boolean allowFileSchemeCookiesImpl() { return acceptFileCookies; }
        @Override public void setAcceptFileSchemeCookiesImpl(boolean accept) { acceptFileCookies = accept; }

        private static URI normalizedUri(String value) {
            return URI.create(value.startsWith("http") ? value : "http://" + value);
        }

        private void removeJavaSessionCookies() {
            for (URI uri : new ArrayList<>(javaCookies.getCookieStore().getURIs())) {
                for (HttpCookie cookie : new ArrayList<>(javaCookies.getCookieStore().get(uri))) {
                    if (cookie.getMaxAge() < 0) javaCookies.getCookieStore().remove(uri, cookie);
                }
            }
        }
    }

    private static String command(String operation, long handle, String argument1, String argument2) {
        String result = NativeBridge.webkitCommand(operation, handle, argument1, argument2);
        if (result != null && result.startsWith("__UNAVAILABLE__")) {
            throw new UnsupportedOperationException(result.substring("__UNAVAILABLE__".length()));
        }
        return result == null ? "" : result;
    }

    private static boolean bool(String value) { return "true".equalsIgnoreCase(value); }
    private static int integer(String value) {
        try { return Integer.parseInt(value); } catch (RuntimeException ignored) { return 0; }
    }
    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
    private static String nullToEmpty(String value) { return value == null ? "" : value; }
    private static Object defaultProxy(Class<?> type) {
        return Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[] {type},
            (proxy, method, args) -> defaultValue(method.getReturnType())
        );
    }
    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == Void.TYPE) return null;
        if (type == Boolean.TYPE) return false;
        if (type == Character.TYPE) return '\0';
        if (type == Byte.TYPE) return (byte) 0;
        if (type == Short.TYPE) return (short) 0;
        if (type == Integer.TYPE) return 0;
        if (type == Long.TYPE) return 0L;
        if (type == Float.TYPE) return 0f;
        if (type == Double.TYPE) return 0d;
        return null;
    }
}
