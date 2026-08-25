package app.tachiaz.runtime;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.URI;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class ExtensionHost {
    private static final ConcurrentHashMap<String, LoadedExtension> EXTENSIONS =
        new ConcurrentHashMap<>();
    private static boolean compatibilityInitialized;
    private static Object compatibilityApplication;
    private static Thread compatibilityLooperThread;

    private ExtensionHost() {
    }

    /**
     * Stable JNI entry point. Exceptions are encoded into the response rather
     * than crossing the native boundary.
     */
    public static String dispatch(String requestJson) {
        try {
            return withContextClassLoader(
                ExtensionHost.class.getClassLoader(),
                () -> dispatchWithHostClassLoader(requestJson)
            );
        } catch (Throwable error) {
            String description = describe(error);
            if (isCloudflareChallenge(error)) {
                description += " [TachiyomiAZCloudflareChallenge]";
            }
            return MiniJson.response(false, null, description, null);
        }
    }

    private static String dispatchWithHostClassLoader(String requestJson)
        throws Exception {
        Map<String, String> request = MiniJson.parseObject(requestJson);
        String operation = require(request, "operation");
        String extensionId = request.get("extensionId");
        LoadedExtension extension = extensionId == null
            ? null
            : EXTENSIONS.get(extensionId);
        ClassLoader loader = extension == null
            ? ExtensionHost.class.getClassLoader()
            : extension.loader;
        return withContextClassLoader(
            loader,
            () -> dispatchOperation(operation, request)
        );
    }

    private static String dispatchOperation(
        String operation,
        Map<String, String> request
    ) throws Exception {
        applyConfiguredDefaultUserAgent(request);
        switch (operation) {
                case "ping":
                    return ping();
                case "inspectExtension":
                    return inspectExtension(request);
                case "loadExtension":
                    return loadExtension(request);
                case "initializeCompatibility":
                    return initializeCompatibility();
                case "getPopularManga":
                    return getPopularManga(request);
                case "getLatestUpdates":
                    return getLatestUpdates(request);
                case "searchManga":
                    return searchManga(request);
                case "getSearchFilters":
                    return getSearchFilters(request);
                case "getSettings":
                    return getSettings(request);
                case "setSetting":
                    return setSetting(request);
                case "getMangaUpdate":
                    return getMangaUpdate(request);
                case "getMangaUrl":
                    return getMangaUrl(request);
                case "getChapterUrl":
                    return getChapterUrl(request);
                case "getPageList":
                    return getPageList(request);
                case "getImageRequest":
                    return getImageRequest(request);
                case "materializeImage":
                    return materializeImage(request);
                case "getCookieSummary":
                    return getCookieSummary(request);
                case "clearCookies":
                    return clearCookies(request);
                case "getWebLoginInfo":
                    return getWebLoginInfo(request);
                case "getWebLoginCookies":
                    return getWebLoginCookies(request);
                case "setWebLoginCookies":
                    return setWebLoginCookies(request);
                case "listSources":
                    return listSources(request);
                case "invoke":
                    return invoke(request);
                case "unloadExtension":
                    return unloadExtension(request);
                default:
                    throw new IllegalArgumentException(
                        "Unsupported operation: " + operation
                    );
        }
    }

    private static <T> T withContextClassLoader(
        ClassLoader loader,
        ContextClassLoaderAction<T> action
    ) throws Exception {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        ClassLoader effective = loader == null
            ? ClassLoader.getSystemClassLoader()
            : loader;
        boolean changed = previous != effective;
        if (changed) {
            thread.setContextClassLoader(effective);
        }
        try {
            return action.run();
        } finally {
            if (changed) {
                thread.setContextClassLoader(previous);
            }
        }
    }

    private interface ContextClassLoaderAction<T> {
        T run() throws Exception;
    }

    private static String ping() {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("runtime", "OpenJDK/mobile Zero");
        metadata.put("javaVersion", System.getProperty("java.version"));
        metadata.put(
            "maximumClassVersion",
            Integer.toString(JarBytecodeValidator.runtimeClassVersion())
        );
        return MiniJson.response(true, "pong", null, metadata);
    }

    private static String inspectExtension(Map<String, String> request)
        throws Exception {
        File jar = new File(require(request, "jarPath"));
        TachiyomiXJarMetadata.Metadata metadata =
            TachiyomiXJarMetadata.inspect(jar);
        Map<String, String> response = metadataMap(metadata);
        return MiniJson.response(true, metadata.entryClass, null, response);
    }

    private static String loadExtension(Map<String, String> request)
        throws Exception {
        String extensionId = require(request, "extensionId");
        File jar = new File(require(request, "jarPath"));
        TachiyomiXJarMetadata.Metadata metadata =
            TachiyomiXJarMetadata.inspect(jar);
        TachiyomiXJarMetadata.requireSupportedLibrary(metadata);
        String entryClass = request.get("entryClass");
        if (entryClass == null || entryClass.isEmpty()) {
            entryClass = metadata.entryClass;
        }

        initializeSuwayomiIfPresent();
        JarBytecodeValidator.validate(jar);
        URLClassLoader loader = new URLClassLoader(
            new URL[] { jar.toURI().toURL() },
            ExtensionHost.class.getClassLoader()
        );

        LoadedExtension replacement;
        try {
            final String extensionClass = entryClass;
            replacement = withContextClassLoader(loader, () -> {
                Class<?> type = Class.forName(extensionClass, true, loader);
                Object instance = type.getDeclaredConstructor().newInstance();
                List<Object> sources = createSources(instance);
                return new LoadedExtension(loader, instance, sources);
            });
        } catch (Throwable error) {
            loader.close();
            throw error;
        }

        LoadedExtension previous = EXTENSIONS.put(extensionId, replacement);
        if (previous != null) {
            previous.close();
        }
        Map<String, String> response = new LinkedHashMap<>();
        response.put(
            "sourceCount",
            Integer.toString(replacement.sources.size())
        );
        return MiniJson.response(true, entryClass, null, response);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> createSources(Object entry) throws Exception {
        try {
            Object value = entry.getClass()
                .getMethod("createSources")
                .invoke(entry);
            if (!(value instanceof List)) {
                throw new IllegalArgumentException(
                    "SourceFactory.createSources did not return a List"
                );
            }
            List<Object> sources = new ArrayList<>((List<Object>) value);
            if (sources.isEmpty()) {
                throw new IllegalArgumentException(
                    "SourceFactory returned no sources"
                );
            }
            return sources;
        } catch (NoSuchMethodException notAFactory) {
            List<Object> sources = new ArrayList<>();
            sources.add(entry);
            return sources;
        } catch (InvocationTargetException error) {
            throw rethrow(error.getCause());
        }
    }

    private static String initializeCompatibility() throws Exception {
        boolean initialized = initializeSuwayomiIfPresent();
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put(
            "compatibility",
            initialized ? "suwayomi-androidcompat" : "none"
        );
        return MiniJson.response(true, "initialized", null, metadata);
    }

    /**
     * Initializes the Suwayomi source API and AndroidCompat without creating a
     * compile-time dependency from this stable host facade. This keeps fixture
     * tests and host-only tools runnable while allowing the app bundle to
     * supply the pinned compatibility JAR set at runtime.
     */
    private static synchronized boolean initializeSuwayomiIfPresent()
        throws Exception {
        if (compatibilityInitialized) {
            return true;
        }

        ClassLoader loader = ExtensionHost.class.getClassLoader();
        Class<?> appType;
        try {
            appType = Class.forName("eu.kanade.tachiyomi.App", true, loader);
        } catch (ClassNotFoundException unavailable) {
            return false;
        }

        startAndroidMainLooper(loader);
        if (java.net.CookieHandler.getDefault() == null) {
            java.net.CookieHandler.setDefault(new java.net.CookieManager());
        }

        Object app = appType.getDeclaredConstructor().newInstance();
        Class<?> applicationType =
            Class.forName("android.app.Application", true, loader);
        Class<?> moduleType =
            Class.forName("org.koin.core.module.Module", true, loader);

        Object appModule = invokeStatic(
            loader,
            "eu.kanade.tachiyomi.AppModuleKt",
            "createAppModule",
            new Class<?>[] { applicationType },
            app
        );
        Object androidCompatModule = invokeStatic(
            loader,
            "xyz.nulldev.androidcompat.AndroidCompatModuleKt",
            "androidCompatModule",
            new Class<?>[0]
        );
        Object configModule = invokeStatic(
            loader,
            "xyz.nulldev.ts.config.ConfigManagerModuleKt",
            "configManagerModule",
            new Class<?>[0]
        );

        Class<?> koinApplicationType =
            Class.forName("org.koin.core.KoinApplication", true, loader);
        Object companion = koinApplicationType.getField("Companion").get(null);
        Object koin = companion.getClass().getMethod("init").invoke(companion);
        Object modules = Array.newInstance(moduleType, 3);
        Array.set(modules, 0, appModule);
        Array.set(modules, 1, androidCompatModule);
        Array.set(modules, 2, configModule);
        koinApplicationType
            .getMethod("modules", modules.getClass())
            .invoke(koin, modules);

        Class<?> globalContextType =
            Class.forName("org.koin.core.context.GlobalContext", true, loader);
        Object globalContext = globalContextType.getField("INSTANCE").get(null);
        boolean koinStarted = false;
        try {
            globalContextType
                .getMethod("startKoin", koinApplicationType)
                .invoke(globalContext, koin);
            koinStarted = true;

            Object initializer = Class.forName(
                "xyz.nulldev.androidcompat.AndroidCompatInitializer",
                true,
                loader
            ).getDeclaredConstructor().newInstance();
            initializer.getClass().getMethod("init").invoke(initializer);

            // AndroidCompat normally installs its desktop KCEF provider. The
            // mobile shim replaces that provider with the app's WKWebView
            // implementation and a CookieManager backed by WebKit.
            Class.forName(
                "app.tachiaz.compat.IOSWebViewProviderFactory",
                true,
                loader
            ).getMethod("install").invoke(null);

            Object androidCompat = Class.forName(
                "xyz.nulldev.androidcompat.AndroidCompat",
                true,
                loader
            ).getDeclaredConstructor().newInstance();
            androidCompat.getClass()
                .getMethod("startApp", applicationType)
                .invoke(androidCompat, app);
        } catch (Throwable error) {
            if (koinStarted) {
                globalContextType
                    .getMethod("stopKoin")
                    .invoke(globalContext);
            }
            throw rethrow(error);
        }

        compatibilityInitialized = true;
        compatibilityApplication = app;
        return true;
    }

    private static void startAndroidMainLooper(ClassLoader loader)
        throws Exception {
        Class<?> looperType = Class.forName(
            "android.os.Looper",
            true,
            loader
        );
        Method getMainLooper = looperType.getMethod("getMainLooper");
        if (getMainLooper.invoke(null) != null) {
            return;
        }

        CountDownLatch ready = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread looperThread = new Thread(() -> {
            Thread.currentThread().setContextClassLoader(loader);
            try {
                looperType.getMethod("prepareMainLooper").invoke(null);
                ready.countDown();
                looperType.getMethod("loop").invoke(null);
            } catch (Throwable error) {
                failure.compareAndSet(null, error);
                ready.countDown();
            }
        }, "TachiyomiAZ Android Main Looper");
        looperThread.setDaemon(true);
        looperThread.start();

        if (!ready.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException(
                "Timed out starting the Android main looper"
            );
        }
        Throwable startupFailure = failure.get();
        if (startupFailure != null) {
            throw rethrow(startupFailure);
        }
        if (getMainLooper.invoke(null) == null) {
            throw new IllegalStateException(
                "Android main looper did not initialize"
            );
        }
        compatibilityLooperThread = looperThread;
    }

    private static Object invokeStatic(
        ClassLoader loader,
        String className,
        String methodName,
        Class<?>[] parameterTypes,
        Object... arguments
    ) throws Exception {
        Class<?> type = Class.forName(className, true, loader);
        return type.getMethod(methodName, parameterTypes)
            .invoke(null, arguments);
    }

    private static String getPopularManga(Map<String, String> request)
        throws Exception {
        return getPagedManga(request, "getPopularManga");
    }

    private static String getLatestUpdates(Map<String, String> request)
        throws Exception {
        return getPagedManga(request, "getLatestUpdates");
    }

    private static String getPagedManga(
        Map<String, String> request,
        String methodName
    ) throws Exception {
        String extensionId = require(request, "extensionId");
        int page = Integer.parseInt(require(request, "argument"));
        if (page < 1) {
            throw new IllegalArgumentException("Page must be at least 1");
        }

        LoadedExtension extension = EXTENSIONS.get(extensionId);
        if (extension == null) {
            throw new IllegalArgumentException(
                "Extension is not loaded: " + extensionId
            );
        }

        Object mangasPage = invokeSuspend(
            extension.source(request.get("sourceId")),
            methodName,
            new Class<?>[] { int.class },
            page
        );

        return MiniJson.response(
            true,
            serializeMangasPage(mangasPage),
            null,
            null
        );
    }

    private static String searchManga(Map<String, String> request)
        throws Exception {
        String extensionId = require(request, "extensionId");
        int page = Integer.parseInt(require(request, "argument"));
        String query = defaultValue(request.get("query"), "");
        if (page < 1) {
            throw new IllegalArgumentException("Page must be at least 1");
        }

        LoadedExtension extension = EXTENSIONS.get(extensionId);
        if (extension == null) {
            throw new IllegalArgumentException(
                "Extension is not loaded: " + extensionId
            );
        }

        Object source = extension.source(request.get("sourceId"));
        Object filters = getter(source, "getFilterList");
        applyFilterStates(filters, request.get("filterStates"));
        Object mangasPage = invokeSuspend(
            source,
            "getSearchManga",
            new Class<?>[] {
                int.class,
                String.class,
                filters.getClass()
            },
            page,
            query,
            filters
        );
        return MiniJson.response(
            true,
            serializeMangasPage(mangasPage),
            null,
            null
        );
    }

    private static String getSearchFilters(Map<String, String> request)
        throws Exception {
        Object source = requireSource(request);
        Object filters = getter(source, "getFilterList");
        return MiniJson.response(
            true,
            serializeFilters(filters),
            null,
            null
        );
    }

    @SuppressWarnings("unchecked")
    private static String serializeFilters(Object filters) throws Exception {
        List<Object> list = (List<Object>) filters;
        List<String> serialized = new ArrayList<>();
        for (int index = 0; index < list.size(); index++) {
            serializeFilter(
                list.get(index),
                Integer.toString(index),
                null,
                serialized
            );
        }
        StringBuilder output = new StringBuilder("[");
        for (int index = 0; index < serialized.size(); index++) {
            if (index > 0) {
                output.append(',');
            }
            output.append(serialized.get(index));
        }
        return output.append(']').toString();
    }

    @SuppressWarnings("unchecked")
    private static void serializeFilter(
        Object filter,
        String path,
        String parentName,
        List<String> output
    ) throws Exception {
        String name = String.valueOf(getter(filter, "getName"));
        String title = parentName == null || parentName.isEmpty()
            ? name
            : parentName + " — " + name;
        ClassLoader loader = ExtensionHost.class.getClassLoader();
        if (isFilterType(loader, filter, "Group")) {
            List<Object> children = (List<Object>) getter(filter, "getState");
            for (int index = 0; index < children.size(); index++) {
                serializeFilter(
                    children.get(index),
                    path + "." + index,
                    title,
                    output
                );
            }
            return;
        }
        if (isFilterType(loader, filter, "Separator")) {
            return;
        }
        if (isFilterType(loader, filter, "Header")) {
            output.add(filterJson(path, "note", title, null, null, null));
            return;
        }
        if (isFilterType(loader, filter, "Text")) {
            output.add(filterJson(
                path,
                "text",
                title,
                null,
                getter(filter, "getState"),
                null
            ));
            return;
        }
        if (isFilterType(loader, filter, "CheckBox")) {
            output.add(filterJson(
                path,
                "check",
                name,
                null,
                getter(filter, "getState"),
                "false",
                parentName
            ));
            return;
        }
        if (isFilterType(loader, filter, "TriState")) {
            output.add(filterJson(
                path,
                "check",
                name,
                null,
                getter(filter, "getState"),
                "true",
                parentName
            ));
            return;
        }
        if (isFilterType(loader, filter, "Sort")) {
            Object state = getter(filter, "getState");
            Object index = state == null ? null : getter(state, "getIndex");
            Object ascending = state == null
                ? null
                : getter(state, "getAscending");
            output.add(filterJson(
                path,
                "sort",
                title,
                (Object[]) getter(filter, "getValues"),
                index,
                ascending
            ));
            return;
        }
        if (isFilterType(loader, filter, "Select")) {
            List<String> values = (List<String>) getter(
                filter,
                "getDisplayValues"
            );
            output.add(filterJson(
                path,
                "select",
                title,
                values.toArray(new Object[0]),
                getter(filter, "getState"),
                null
            ));
        }
    }

    private static boolean isFilterType(
        ClassLoader loader,
        Object filter,
        String nestedName
    ) throws Exception {
        return Class.forName(
            "eu.kanade.tachiyomi.source.model.Filter$" + nestedName,
            true,
            loader
        ).isInstance(filter);
    }

    private static String filterJson(
        String id,
        String type,
        String name,
        Object[] options,
        Object defaultValue,
        Object auxiliary
    ) {
        return filterJson(
            id,
            type,
            name,
            options,
            defaultValue,
            auxiliary,
            null
        );
    }

    private static String filterJson(
        String id,
        String type,
        String name,
        Object[] options,
        Object defaultValue,
        Object auxiliary,
        String group
    ) {
        StringBuilder output = new StringBuilder("{");
        appendJsonField(output, "id", id, false);
        appendJsonField(output, "type", type, true);
        appendJsonField(output, "name", name, true);
        if (options != null) {
            output.append(",\"options\":[");
            for (int index = 0; index < options.length; index++) {
                if (index > 0) {
                    output.append(',');
                }
                output.append('"')
                    .append(MiniJson.escapeValue(String.valueOf(options[index])))
                    .append('"');
            }
            output.append(']');
        }
        if (defaultValue != null) {
            appendJsonField(
                output,
                "defaultValue",
                String.valueOf(defaultValue),
                true
            );
        }
        if (auxiliary != null) {
            appendJsonField(
                output,
                "auxiliary",
                String.valueOf(auxiliary),
                true
            );
        }
        if (group != null && !group.isEmpty()) {
            appendJsonField(output, "group", group, true);
        }
        return output.append('}').toString();
    }

    @SuppressWarnings("unchecked")
    private static void applyFilterStates(
        Object filters,
        String encodedStates
    ) throws Exception {
        if (encodedStates == null || encodedStates.isEmpty()) {
            return;
        }
        List<Object> roots = (List<Object>) filters;
        String[] lines = encodedStates.split("\\n");
        for (String line : lines) {
            String[] fields = line.split("\\t", -1);
            if (fields.length < 3) {
                continue;
            }
            Object filter = filterAtPath(roots, fields[0]);
            String kind = fields[1];
            String value = URLDecoder.decode(
                fields[2],
                StandardCharsets.UTF_8.name()
            );
            Object state;
            switch (kind) {
                case "text":
                    state = value;
                    break;
                case "check":
                case "select":
                    state = Integer.valueOf(value);
                    if ("check".equals(kind) && isFilterType(
                        ExtensionHost.class.getClassLoader(),
                        filter,
                        "CheckBox"
                    )) {
                        state = Integer.parseInt(value) != 0;
                    }
                    break;
                case "sort":
                    boolean ascending =
                        fields.length > 3 &&
                        Boolean.parseBoolean(fields[3]);
                    Class<?> selection = Class.forName(
                        "eu.kanade.tachiyomi.source.model.Filter$Sort$Selection",
                        true,
                        ExtensionHost.class.getClassLoader()
                    );
                    state = selection
                        .getConstructor(int.class, boolean.class)
                        .newInstance(Integer.parseInt(value), ascending);
                    break;
                default:
                    continue;
            }
            filter.getClass()
                .getMethod("setState", Object.class)
                .invoke(filter, state);
        }
    }

    @SuppressWarnings("unchecked")
    private static Object filterAtPath(
        List<Object> roots,
        String path
    ) throws Exception {
        String[] components = path.split("\\.");
        Object filter = roots.get(Integer.parseInt(components[0]));
        for (int index = 1; index < components.length; index++) {
            List<Object> children =
                (List<Object>) getter(filter, "getState");
            filter = children.get(Integer.parseInt(components[index]));
        }
        return filter;
    }

    private static String getSettings(Map<String, String> request)
        throws Exception {
        Object source = requireSource(request);
        List<Object> preferences = sourcePreferences(source);
        StringBuilder output = new StringBuilder("[");
        int count = 0;
        for (Object preference : preferences) {
            String type = preferenceType(preference);
            String key = String.valueOf(getter(preference, "getKey"));
            if (type == null || key == null || "null".equals(key)) {
                continue;
            }
            if (count++ > 0) {
                output.append(',');
            }
            output.append('{');
            appendJsonField(output, "key", key, false);
            appendJsonField(
                output,
                "title",
                getter(preference, "getTitle"),
                true
            );
            appendJsonField(
                output,
                "summary",
                getter(preference, "getSummary"),
                true
            );
            appendJsonField(output, "type", type, true);
            appendJsonField(
                output,
                "enabled",
                getter(preference, "isEnabled"),
                true
            );
            appendJsonField(
                output,
                "currentValue",
                serializePreferenceValue(
                    getter(preference, "getCurrentValue")
                ),
                true
            );
            if ("select".equals(type) || "multiselect".equals(type)) {
                appendStringArray(
                    output,
                    "options",
                    (Object[]) getter(preference, "getEntries")
                );
                appendStringArray(
                    output,
                    "values",
                    (Object[]) getter(preference, "getEntryValues")
                );
            }
            output.append('}');
        }
        output.append(']');
        return MiniJson.response(true, output.toString(), null, null);
    }

    private static String setSetting(Map<String, String> request)
        throws Exception {
        Object source = requireSource(request);
        String key = require(request, "settingKey");
        String type = require(request, "settingType");
        String value = defaultValue(request.get("settingValue"), "");
        for (Object preference : sourcePreferences(source)) {
            if (!key.equals(String.valueOf(getter(preference, "getKey")))) {
                continue;
            }
            Object newValue;
            switch (type) {
                case "toggle":
                    newValue = Boolean.valueOf(value);
                    break;
                case "multiselect":
                    Set<String> values = new LinkedHashSet<>();
                    if (!value.isEmpty()) {
                        for (String item : value.split("\\n", -1)) {
                            values.add(item);
                        }
                    }
                    newValue = values;
                    break;
                default:
                    newValue = value;
                    break;
            }
            preference.getClass()
                .getMethod("saveNewValue", Object.class)
                .invoke(preference, newValue);
            return MiniJson.response(true, key, null, null);
        }
        throw new IllegalArgumentException(
            "Unknown extension preference: " + key
        );
    }

    @SuppressWarnings("unchecked")
    private static List<Object> sourcePreferences(Object source)
        throws Exception {
        ClassLoader loader = ExtensionHost.class.getClassLoader();
        Class<?> configurable = Class.forName(
            "eu.kanade.tachiyomi.source.ConfigurableSource",
            true,
            loader
        );
        if (!configurable.isInstance(source)) {
            return new ArrayList<>();
        }
        if (compatibilityApplication == null) {
            initializeSuwayomiIfPresent();
        }
        Class<?> context = Class.forName(
            "android.content.Context",
            true,
            loader
        );
        Class<?> screenType = Class.forName(
            "androidx.preference.PreferenceScreen",
            true,
            loader
        );
        Object screen = screenType
            .getConstructor(context)
            .newInstance(compatibilityApplication);
        source.getClass()
            .getMethod("setupPreferenceScreen", screenType)
            .invoke(source, screen);
        List<Object> preferences = (List<Object>) screenType
            .getMethod("getPreferences")
            .invoke(screen);
        Object sharedPreferences = getter(
            source,
            "getSourcePreferences"
        );
        Class<?> sharedPreferencesType = Class.forName(
            "android.content.SharedPreferences",
            true,
            loader
        );
        for (Object preference : preferences) {
            preference.getClass()
                .getMethod(
                    "setSharedPreferences",
                    sharedPreferencesType
                )
                .invoke(preference, sharedPreferences);
        }
        return preferences;
    }

    private static String preferenceType(Object preference)
        throws Exception {
        ClassLoader loader = ExtensionHost.class.getClassLoader();
        if (Class.forName(
            "androidx.preference.SwitchPreferenceCompat",
            true,
            loader
        ).isInstance(preference)) {
            return "toggle";
        }
        if (Class.forName(
            "androidx.preference.ListPreference",
            true,
            loader
        ).isInstance(preference)) {
            return "select";
        }
        if (Class.forName(
            "androidx.preference.MultiSelectListPreference",
            true,
            loader
        ).isInstance(preference)) {
            return "multiselect";
        }
        if (Class.forName(
            "androidx.preference.EditTextPreference",
            true,
            loader
        ).isInstance(preference)) {
            return "text";
        }
        return null;
    }

    private static String serializePreferenceValue(Object value) {
        if (!(value instanceof Set)) {
            return value == null ? null : String.valueOf(value);
        }
        StringBuilder output = new StringBuilder();
        for (Object item : (Set<?>) value) {
            if (output.length() > 0) {
                output.append('\n');
            }
            output.append(String.valueOf(item));
        }
        return output.toString();
    }

    private static void appendStringArray(
        StringBuilder output,
        String key,
        Object[] values
    ) {
        output.append(",\"")
            .append(MiniJson.escapeValue(key))
            .append("\":[");
        for (int index = 0; index < values.length; index++) {
            if (index > 0) {
                output.append(',');
            }
            output.append('"')
                .append(MiniJson.escapeValue(String.valueOf(values[index])))
                .append('"');
        }
        output.append(']');
    }

    private static String getMangaUpdate(Map<String, String> request)
        throws Exception {
        Object source = requireSource(request);
        ClassLoader loader = ExtensionHost.class.getClassLoader();
        Class<?> mangaType = Class.forName(
            "eu.kanade.tachiyomi.source.model.SManga",
            true,
            loader
        );
        Object manga = Class.forName(
            "eu.kanade.tachiyomi.source.model.SMangaImpl",
            true,
            loader
        ).getDeclaredConstructor().newInstance();
        setter(manga, "setUrl", String.class, require(request, "mangaURL"));
        setter(
            manga,
            "setTitle",
            String.class,
            defaultValue(request.get("mangaTitle"), "")
        );
        setter(manga, "setThumbnail_url", String.class, request.get("mangaThumbnailURL"));
        setter(manga, "setArtist", String.class, request.get("mangaArtist"));
        setter(manga, "setAuthor", String.class, request.get("mangaAuthor"));
        setter(
            manga,
            "setStatus",
            int.class,
            Integer.parseInt(defaultValue(request.get("mangaStatus"), "0"))
        );
        setter(manga, "setDescription", String.class, request.get("mangaDescription"));
        setter(manga, "setGenre", String.class, request.get("mangaGenre"));
        Class<?> updateStrategyType = Class.forName(
            "eu.kanade.tachiyomi.source.model.UpdateStrategy",
            true,
            loader
        );
        @SuppressWarnings({ "rawtypes", "unchecked" })
        Object updateStrategy = Enum.valueOf(
            (Class<? extends Enum>) updateStrategyType.asSubclass(Enum.class),
            defaultValue(request.get("mangaUpdateStrategy"), "ALWAYS_UPDATE")
        );
        setter(manga, "setUpdate_strategy", updateStrategyType, updateStrategy);
        setter(
            manga,
            "setInitialized",
            boolean.class,
            Boolean.parseBoolean(defaultValue(request.get("mangaInitialized"), "false"))
        );
        restoreMemo(manga, request.get("mangaMemo"));
        List<Object> chapters = restoreChapters(request.get("mangaChapters"), loader);

        Object update = invokeSuspend(
            source,
            "getMangaUpdate",
            new Class<?>[] {
                mangaType,
                List.class,
                boolean.class,
                boolean.class
            },
            manga,
            chapters,
            Boolean.parseBoolean(defaultValue(request.get("fetchDetails"), "true")),
            Boolean.parseBoolean(defaultValue(request.get("fetchChapters"), "true"))
        );
        return MiniJson.response(
            true,
            serializeMangaUpdate(
                update,
                require(request, "mangaURL"),
                String.valueOf(getter(manga, "getTitle"))
            ),
            null,
            null
        );
    }

    private static List<Object> restoreChapters(String json, ClassLoader loader)
        throws Exception {
        List<Object> chapters = new ArrayList<>();
        Class<?> chapterClass = Class.forName(
            "eu.kanade.tachiyomi.source.model.SChapterImpl",
            true,
            loader
        );
        for (Map<String, String> stored : MiniJson.parseObjectArray(json)) {
            Object chapter = chapterClass.getDeclaredConstructor().newInstance();
            setter(chapter, "setUrl", String.class, defaultValue(stored.get("url"), ""));
            setter(chapter, "setName", String.class, defaultValue(stored.get("name"), ""));
            setter(
                chapter,
                "setChapter_number",
                float.class,
                Float.parseFloat(defaultValue(stored.get("chapterNumber"), "-1"))
            );
            setter(chapter, "setScanlator", String.class, stored.get("scanlator"));
            setter(
                chapter,
                "setDate_upload",
                long.class,
                Long.parseLong(defaultValue(stored.get("dateUpload"), "0"))
            );
            restoreMemo(chapter, stored.get("memo"));
            chapters.add(chapter);
        }
        return chapters;
    }

    private static String getPageList(Map<String, String> request)
        throws Exception {
        Object source = requireSource(request);
        ClassLoader loader = ExtensionHost.class.getClassLoader();
        Class<?> chapterType = Class.forName(
            "eu.kanade.tachiyomi.source.model.SChapter",
            true,
            loader
        );
        Object chapter = Class.forName(
            "eu.kanade.tachiyomi.source.model.SChapterImpl",
            true,
            loader
        ).getDeclaredConstructor().newInstance();
        setter(
            chapter,
            "setUrl",
            String.class,
            require(request, "chapterURL")
        );
        setter(
            chapter,
            "setName",
            String.class,
            defaultValue(request.get("chapterName"), "")
        );
        setter(
            chapter,
            "setChapter_number",
            float.class,
            Float.parseFloat(defaultValue(request.get("chapterNumber"), "-1"))
        );
        setter(chapter, "setScanlator", String.class, request.get("chapterScanlator"));
        setter(
            chapter,
            "setDate_upload",
            long.class,
            Long.parseLong(defaultValue(request.get("chapterDateUpload"), "0"))
        );
        restoreMemo(chapter, request.get("chapterMemo"));
        Object pages = invokeSuspend(
            source,
            "getPageList",
            new Class<?>[] { chapterType },
            chapter
        );
        return MiniJson.response(
            true,
            serializePages(source, pages),
            null,
            null
        );
    }

    @SuppressWarnings("unchecked")
    private static String getImageRequest(Map<String, String> request)
        throws Exception {
        Object source = requireSource(request);
        String imageURL = require(request, "imageURL");
        ClassLoader loader = ExtensionHost.class.getClassLoader();
        Class<?> pageType = Class.forName(
            "eu.kanade.tachiyomi.source.model.Page",
            true,
            loader
        );
        Class<?> uriType = Class.forName(
            "android.net.Uri",
            true,
            loader
        );
        Object page = pageType
            .getConstructor(
                int.class,
                String.class,
                String.class,
                uriType
            )
            .newInstance(
                0,
                defaultValue(request.get("pageURL"), imageURL),
                imageURL,
                null
            );
        Object headers;
        Object httpUrl;
        String resolvedURL;
        if (request.get("pageURL") == null) {
            headers = getter(source, "getHeaders");
            Class<?> httpUrlType = Class.forName(
                "okhttp3.HttpUrl",
                true,
                loader
            );
            httpUrl = httpUrlType
                .getMethod("parse", String.class)
                .invoke(null, imageURL);
            resolvedURL = imageURL;
        } else {
            Method imageRequest = findMethod(
                source.getClass(),
                "imageRequest",
                pageType
            );
            imageRequest.setAccessible(true);
            Object nativeRequest = imageRequest.invoke(source, page);
            headers = nativeRequest.getClass()
                .getMethod("headers")
                .invoke(nativeRequest);
            httpUrl = nativeRequest.getClass()
                .getMethod("url")
                .invoke(nativeRequest);
            resolvedURL = String.valueOf(httpUrl);
        }
        int headerCount = (Integer) headers.getClass()
            .getMethod("size")
            .invoke(headers);
        Map<String, List<String>> values = new LinkedHashMap<>();
        for (int index = 0; index < headerCount; index++) {
            String name = String.valueOf(
                headers.getClass()
                    .getMethod("name", int.class)
                    .invoke(headers, index)
            );
            String value = String.valueOf(
                headers.getClass()
                    .getMethod("value", int.class)
                    .invoke(headers, index)
            );
            values.computeIfAbsent(name, ignored -> new ArrayList<>())
                .add(value);
        }

        Object client = getter(source, "getClient");
        Object cookieJar = client.getClass()
            .getMethod("cookieJar")
            .invoke(client);
        List<Object> cookies = (List<Object>) cookieJar.getClass()
            .getMethod("loadForRequest", httpUrl.getClass())
            .invoke(cookieJar, httpUrl);
        if (!cookies.isEmpty()) {
            List<String> pairs = new ArrayList<>();
            for (Object cookie : cookies) {
                pairs.add(
                    getter(cookie, "name") + "=" + getter(cookie, "value")
                );
            }
            values.put("Cookie", pairs);
        }

        StringBuilder output = new StringBuilder("{\"url\":\"")
            .append(MiniJson.escapeValue(resolvedURL))
            .append("\",\"headers\":{");
        int index = 0;
        for (Map.Entry<String, List<String>> header : values.entrySet()) {
            if (index++ > 0) {
                output.append(',');
            }
            output.append('"')
                .append(MiniJson.escapeValue(header.getKey()))
                .append("\":\"")
                .append(MiniJson.escapeValue(
                    String.join(
                        "Cookie".equalsIgnoreCase(header.getKey()) ? "; " : ", ",
                        header.getValue()
                    )
                ))
                .append('"');
        }
        output.append("}}");
        return MiniJson.response(true, output.toString(), null, null);
    }

    private static String getMangaUrl(Map<String, String> request)
        throws Exception {
        Object source = requireSource(request);
        ClassLoader loader = source.getClass().getClassLoader();
        Class<?> mangaType = Class.forName(
            "eu.kanade.tachiyomi.source.model.SManga",
            true,
            loader
        );
        Object manga = Class.forName(
            "eu.kanade.tachiyomi.source.model.SMangaImpl",
            true,
            loader
        ).getDeclaredConstructor().newInstance();
        setter(manga, "setUrl", String.class, require(request, "mangaURL"));
        setter(
            manga,
            "setTitle",
            String.class,
            defaultValue(request.get("mangaTitle"), "")
        );
        setter(manga, "setThumbnail_url", String.class, request.get("mangaThumbnailURL"));
        setter(manga, "setArtist", String.class, request.get("mangaArtist"));
        setter(manga, "setAuthor", String.class, request.get("mangaAuthor"));
        setter(
            manga,
            "setStatus",
            int.class,
            Integer.parseInt(defaultValue(request.get("mangaStatus"), "0"))
        );
        setter(manga, "setDescription", String.class, request.get("mangaDescription"));
        setter(manga, "setGenre", String.class, request.get("mangaGenre"));
        Class<?> updateStrategyType = Class.forName(
            "eu.kanade.tachiyomi.source.model.UpdateStrategy",
            true,
            loader
        );
        @SuppressWarnings({ "rawtypes", "unchecked" })
        Object updateStrategy = Enum.valueOf(
            (Class<? extends Enum>) updateStrategyType.asSubclass(Enum.class),
            defaultValue(request.get("mangaUpdateStrategy"), "ALWAYS_UPDATE")
        );
        setter(manga, "setUpdate_strategy", updateStrategyType, updateStrategy);
        setter(
            manga,
            "setInitialized",
            boolean.class,
            Boolean.parseBoolean(defaultValue(request.get("mangaInitialized"), "false"))
        );
        restoreMemo(manga, request.get("mangaMemo"));
        Method getMangaUrl = findMethod(
            source.getClass(),
            "getMangaUrl",
            mangaType
        );
        getMangaUrl.setAccessible(true);
        String url = String.valueOf(getMangaUrl.invoke(source, manga));
        return MiniJson.response(true, url, null, null);
    }

    private static String getChapterUrl(Map<String, String> request)
        throws Exception {
        Object source = requireSource(request);
        ClassLoader loader = source.getClass().getClassLoader();
        Class<?> chapterType = Class.forName(
            "eu.kanade.tachiyomi.source.model.SChapter",
            true,
            loader
        );
        Object chapter = Class.forName(
            "eu.kanade.tachiyomi.source.model.SChapterImpl",
            true,
            loader
        ).getDeclaredConstructor().newInstance();
        setter(
            chapter,
            "setUrl",
            String.class,
            require(request, "chapterURL")
        );
        setter(
            chapter,
            "setName",
            String.class,
            defaultValue(request.get("chapterName"), "")
        );
        setter(
            chapter,
            "setChapter_number",
            float.class,
            Float.parseFloat(defaultValue(request.get("chapterNumber"), "-1"))
        );
        setter(chapter, "setScanlator", String.class, request.get("chapterScanlator"));
        setter(
            chapter,
            "setDate_upload",
            long.class,
            Long.parseLong(defaultValue(request.get("chapterDateUpload"), "0"))
        );
        restoreMemo(chapter, request.get("chapterMemo"));
        Method getChapterUrl = findMethod(
            source.getClass(),
            "getChapterUrl",
            chapterType
        );
        getChapterUrl.setAccessible(true);
        String url = String.valueOf(getChapterUrl.invoke(source, chapter));
        return MiniJson.response(true, url, null, null);
    }

    private static String materializeImage(Map<String, String> request)
        throws Exception {
        return materializeImage(
            requireSource(request),
            require(request, "imageURL"),
            request.get("pageURL"),
            require(request, "destinationPath")
        );
    }

    static String materializeImage(
        Object source,
        String imageURL,
        String pageURL,
        String destinationPath
    ) throws Exception {
        ClassLoader loader = source.getClass().getClassLoader();
        Class<?> pageType = Class.forName(
            "eu.kanade.tachiyomi.source.model.Page",
            true,
            loader
        );
        Class<?> uriType = Class.forName("android.net.Uri", true, loader);
        Object page = pageType
            .getConstructor(
                int.class,
                String.class,
                String.class,
                uriType
            )
            .newInstance(
                0,
                defaultValue(pageURL, imageURL),
                imageURL,
                null
            );
        Object nativeRequest;
        if (pageURL == null) {
            // Manga thumbnails are regular source requests in Mihon. Calling
            // imageRequest(Page) here is incorrect because extensions may
            // override it for chapter-page metadata that covers do not have.
            Object headers = getter(source, "getHeaders");
            Class<?> headersType = Class.forName(
                "okhttp3.Headers",
                true,
                loader
            );
            Class<?> builderType = Class.forName(
                "okhttp3.Request$Builder",
                true,
                loader
            );
            Object builder = builderType.getConstructor().newInstance();
            builderType.getMethod("url", String.class)
                .invoke(builder, imageURL);
            builderType.getMethod("headers", headersType)
                .invoke(builder, headers);
            nativeRequest = builderType.getMethod("build").invoke(builder);
        } else {
            Method imageRequest = findMethod(
                source.getClass(),
                "imageRequest",
                pageType
            );
            imageRequest.setAccessible(true);
            nativeRequest = imageRequest.invoke(source, page);
        }
        nativeRequest = preserveImageRequestFragment(
            nativeRequest,
            imageURL,
            loader
        );
        Object client = clientWithBufferedImageResponse(
            getter(source, "getClient"),
            loader
        );
        Class<?> requestType = Class.forName("okhttp3.Request", true, loader);
        Object call = client.getClass()
            .getMethod("newCall", requestType)
            .invoke(client, nativeRequest);
        Class<?> callType = Class.forName("okhttp3.Call", true, loader);
        Object response = callType.getMethod("execute").invoke(call);

        Path destination = new File(
            destinationPath
        ).toPath().toAbsolutePath().normalize();
        Path parent = destination.getParent();
        if (parent == null) {
            throw new IllegalArgumentException(
                "The image destination has no parent directory"
            );
        }
        Files.createDirectories(parent);
        Path partial = destination.resolveSibling(
            destination.getFileName() + ".partial"
        );
        String contentType = "application/octet-stream";
        try {
            Class<?> responseType = Class.forName(
                "okhttp3.Response",
                true,
                loader
            );
            int code = (Integer) responseType.getMethod("code").invoke(response);
            Object body = responseType.getMethod("body").invoke(response);
            if (code < 200 || code >= 300) {
                throw new java.io.IOException(
                    "Image request returned HTTP " + code
                );
            }
            if (body == null) {
                throw new java.io.IOException("Image response body is empty");
            }
            Class<?> bodyType = Class.forName(
                "okhttp3.ResponseBody",
                true,
                loader
            );
            Object mediaType = bodyType.getMethod("contentType").invoke(body);
            if (mediaType != null) {
                contentType = String.valueOf(mediaType);
            }
            try (InputStream stream = (InputStream) bodyType
                .getMethod("byteStream")
                .invoke(body)) {
                Files.copy(
                    stream,
                    partial,
                    StandardCopyOption.REPLACE_EXISTING
                );
            }
            try {
                Files.move(
                    partial,
                    destination,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                );
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(
                    partial,
                    destination,
                    StandardCopyOption.REPLACE_EXISTING
                );
            }
            if (Files.size(destination) == 0) {
                throw new java.io.IOException("Image response body is empty");
            }
            contentType = detectedImageContentType(destination, contentType);
        } finally {
            Files.deleteIfExists(partial);
            if (response instanceof java.io.Closeable) {
                ((java.io.Closeable) response).close();
            }
        }
        String result = "{\"contentType\":\"" +
            MiniJson.escapeValue(contentType) + "\"}";
        return MiniJson.response(true, result, null, null);
    }

    /**
     * OkHttp never sends a URL fragment over HTTP, but application
     * interceptors can use it as local image-processing metadata. Preserve the
     * fragment from the Page even if an extension's imageRequest rebuilds the
     * URL and accidentally drops it.
     */
    private static Object preserveImageRequestFragment(
        Object request,
        String imageURL,
        ClassLoader loader
    ) throws Exception {
        String fragment;
        try {
            fragment = URI.create(imageURL).getFragment();
        } catch (IllegalArgumentException invalidURL) {
            return request;
        }
        if (fragment == null || fragment.isEmpty()) {
            return request;
        }
        Object currentURL = request.getClass().getMethod("url").invoke(request);
        Object currentFragment = currentURL.getClass()
            .getMethod("fragment")
            .invoke(currentURL);
        if (fragment.equals(currentFragment)) {
            return request;
        }
        Object urlBuilder = currentURL.getClass()
            .getMethod("newBuilder")
            .invoke(currentURL);
        urlBuilder.getClass()
            .getMethod("fragment", String.class)
            .invoke(urlBuilder, fragment);
        Object restoredURL = urlBuilder.getClass().getMethod("build")
            .invoke(urlBuilder);
        Object requestBuilder = request.getClass()
            .getMethod("newBuilder")
            .invoke(request);
        Class<?> httpUrlType = Class.forName("okhttp3.HttpUrl", true, loader);
        requestBuilder.getClass()
            .getMethod("url", httpUrlType)
            .invoke(requestBuilder, restoredURL);
        return requestBuilder.getClass().getMethod("build")
            .invoke(requestBuilder);
    }

    /**
     * Some image interceptors peek at metadata using ResponseBody.contentLength
     * before decoding the body. OpenJDK's iOS HTTP path can expose a streamed
     * body with an unknown length, causing those interceptors to silently
     * return the encrypted response. Append this interceptor after the
     * extension's own interceptors so the wire response is replayable and has
     * its exact byte length before control returns to them.
     */
    private static Object clientWithBufferedImageResponse(
        Object client,
        ClassLoader loader
    ) throws Exception {
        Class<?> interceptorType = Class.forName(
            "okhttp3.Interceptor",
            true,
            loader
        );
        Class<?> chainType = Class.forName(
            "okhttp3.Interceptor$Chain",
            true,
            loader
        );
        Class<?> requestType = Class.forName("okhttp3.Request", true, loader);
        Class<?> responseType = Class.forName("okhttp3.Response", true, loader);
        Class<?> responseBodyType = Class.forName(
            "okhttp3.ResponseBody",
            true,
            loader
        );
        Class<?> mediaTypeType = Class.forName(
            "okhttp3.MediaType",
            true,
            loader
        );
        Object normalizer = Proxy.newProxyInstance(
            loader,
            new Class<?>[] { interceptorType },
            (proxy, method, arguments) -> {
                if (!"intercept".equals(method.getName())) {
                    if ("toString".equals(method.getName())) {
                        return "TachiyomiAZBufferedImageResponseInterceptor";
                    }
                    if ("hashCode".equals(method.getName())) {
                        return System.identityHashCode(proxy);
                    }
                    if ("equals".equals(method.getName())) {
                        return proxy == arguments[0];
                    }
                    return null;
                }
                Object chain = arguments[0];
                Object request = chainType.getMethod("request").invoke(chain);
                Object response = chainType
                    .getMethod("proceed", requestType)
                    .invoke(chain, request);
                Object body = responseType.getMethod("body").invoke(response);
                if (body == null) {
                    return response;
                }
                Object mediaType = responseBodyType.getMethod("contentType")
                    .invoke(body);
                byte[] bytes = (byte[]) responseBodyType.getMethod("bytes")
                    .invoke(body);
                Object companion = responseBodyType.getField("Companion")
                    .get(null);
                Object replacement = companion.getClass()
                    .getMethod("create", byte[].class, mediaTypeType)
                    .invoke(companion, bytes, mediaType);
                Object responseBuilder = responseType
                    .getMethod("newBuilder")
                    .invoke(response);
                responseBuilder.getClass()
                    .getMethod("body", responseBodyType)
                    .invoke(responseBuilder, replacement);
                return responseBuilder.getClass().getMethod("build")
                    .invoke(responseBuilder);
            }
        );
        Class<?> clientType = Class.forName("okhttp3.OkHttpClient", true, loader);
        Class<?> clientBuilderType = Class.forName(
            "okhttp3.OkHttpClient$Builder",
            true,
            loader
        );
        Object builder = clientType.getMethod("newBuilder")
            .invoke(client);
        clientBuilderType
            .getMethod("addInterceptor", interceptorType)
            .invoke(builder, normalizer);
        return clientBuilderType.getMethod("build").invoke(builder);
    }

    private static String detectedImageContentType(
        Path path,
        String fallback
    ) throws Exception {
        byte[] prefix = new byte[16];
        int count;
        try (InputStream stream = Files.newInputStream(path)) {
            count = stream.read(prefix);
        }
        if (
            count >= 8 &&
            (prefix[0] & 0xff) == 0x89 &&
            prefix[1] == 'P' && prefix[2] == 'N' && prefix[3] == 'G'
        ) {
            return "image/png";
        }
        if (
            count >= 3 &&
            (prefix[0] & 0xff) == 0xff &&
            (prefix[1] & 0xff) == 0xd8 &&
            (prefix[2] & 0xff) == 0xff
        ) {
            return "image/jpeg";
        }
        if (
            count >= 12 &&
            prefix[0] == 'R' && prefix[1] == 'I' &&
            prefix[2] == 'F' && prefix[3] == 'F' &&
            prefix[8] == 'W' && prefix[9] == 'E' &&
            prefix[10] == 'B' && prefix[11] == 'P'
        ) {
            return "image/webp";
        }
        if (
            count >= 6 && prefix[0] == 'G' && prefix[1] == 'I' &&
            prefix[2] == 'F'
        ) {
            return "image/gif";
        }
        if (
            count >= 12 && prefix[4] == 'f' && prefix[5] == 't' &&
            prefix[6] == 'y' && prefix[7] == 'p' &&
            prefix[8] == 'a' && prefix[9] == 'v' &&
            prefix[10] == 'i' && prefix[11] == 'f'
        ) {
            return "image/avif";
        }
        return fallback;
    }

    @SuppressWarnings("unchecked")
    private static String getCookieSummary(Map<String, String> request)
        throws Exception {
        Object source = requireSource(request);
        Object client = getter(source, "getClient");
        Object cookieJar = client.getClass()
            .getMethod("cookieJar")
            .invoke(client);
        String baseURL = String.valueOf(getter(source, "getBaseUrl"));
        ClassLoader loader = source.getClass().getClassLoader();
        Class<?> httpUrlType = Class.forName("okhttp3.HttpUrl", true, loader);
        Object httpUrl = httpUrlType
            .getMethod("parse", String.class)
            .invoke(null, baseURL);
        if (httpUrl == null) {
            return MiniJson.response(
                true,
                "No cookies stored",
                null,
                null
            );
        }
        List<Object> cookies = (List<Object>) cookieJar.getClass()
            .getMethod("loadForRequest", httpUrlType)
            .invoke(cookieJar, httpUrl);
        if (cookies.isEmpty()) {
            return MiniJson.response(
                true,
                "No cookies stored for " + baseURL,
                null,
                null
            );
        }
        List<String> descriptions = new ArrayList<>();
        for (Object cookie : cookies) {
            descriptions.add(
                getter(cookie, "name") + " — " + getter(cookie, "domain")
            );
        }
        return MiniJson.response(
            true,
            String.join("\n", descriptions),
            null,
            null
        );
    }

    private static String clearCookies(Map<String, String> request)
        throws Exception {
        Object source = requireSource(request);
        Object client = getter(source, "getClient");
        Object cookieJar = client.getClass()
            .getMethod("cookieJar")
            .invoke(client);
        if (!invokeCookieClear(cookieJar)) {
            throw new UnsupportedOperationException(
                "The extension cookie jar does not expose a clear operation"
            );
        }
        setForcedUserAgent(client, null);
        return MiniJson.response(true, "cleared", null, null);
    }

    private static String getWebLoginInfo(Map<String, String> request)
        throws Exception {
        Object source = requireSource(request);
        Object client = getter(source, "getClient");
        String baseURL = homeURL(source);
        String userAgent = sourceUserAgent(source, client);
        String result = "{\"baseURL\":\"" +
            MiniJson.escapeValue(baseURL) +
            "\",\"userAgent\":\"" +
            MiniJson.escapeValue(userAgent) + "\"}";
        return MiniJson.response(true, result, null, null);
    }

    @SuppressWarnings("unchecked")
    private static String getWebLoginCookies(Map<String, String> request)
        throws Exception {
        Object source = requireSource(request);
        Object client = getter(source, "getClient");
        Object cookieJar = client.getClass()
            .getMethod("cookieJar")
            .invoke(client);
        String url = defaultValue(
            request.get("mangaURL"),
            homeURL(source)
        );
        ClassLoader loader = source.getClass().getClassLoader();
        Class<?> httpUrlType = Class.forName("okhttp3.HttpUrl", true, loader);
        Object httpUrl = httpUrlType
            .getMethod("parse", String.class)
            .invoke(null, url);
        if (httpUrl == null) {
            throw new IllegalArgumentException("Invalid web-login URL: " + url);
        }
        List<Object> cookies = (List<Object>) cookieJar.getClass()
            .getMethod("loadForRequest", httpUrlType)
            .invoke(cookieJar, httpUrl);
        List<String> encoded = new ArrayList<>();
        for (Object cookie : cookies) {
            encoded.add(String.join(
                "\t",
                encodeCookieField(String.valueOf(getter(cookie, "name"))),
                encodeCookieField(String.valueOf(getter(cookie, "value"))),
                encodeCookieField(String.valueOf(getter(cookie, "domain"))),
                encodeCookieField(String.valueOf(getter(cookie, "path"))),
                String.valueOf(getter(cookie, "expiresAt")),
                String.valueOf(getter(cookie, "secure")),
                String.valueOf(getter(cookie, "httpOnly")),
                String.valueOf(getter(cookie, "hostOnly"))
            ));
        }
        return MiniJson.response(
            true,
            String.join("\n", encoded),
            null,
            null
        );
    }

    private static String setWebLoginCookies(Map<String, String> request)
        throws Exception {
        Object source = requireSource(request);
        Object client = getter(source, "getClient");
        Object cookieJar = client.getClass()
            .getMethod("cookieJar")
            .invoke(client);
        String baseURL = String.valueOf(getter(source, "getBaseUrl"));
        ClassLoader loader = source.getClass().getClassLoader();
        Class<?> httpUrlType = Class.forName("okhttp3.HttpUrl", true, loader);
        Object httpUrl = httpUrlType
            .getMethod("parse", String.class)
            .invoke(null, baseURL);
        String host = String.valueOf(getter(httpUrl, "host"));
        Class<?> cookieType = Class.forName("okhttp3.Cookie", true, loader);
        Class<?> builderType = Class.forName(
            "okhttp3.Cookie$Builder",
            true,
            loader
        );
        List<Object> cookies = new ArrayList<>();
        String encoded = defaultValue(request.get("argument"), "");
        if (!encoded.isEmpty()) {
            for (String line : encoded.split("\\n")) {
                String[] fields = line.split("\\t", -1);
                if (fields.length != 2 && fields.length != 8) {
                    continue;
                }
                String name = URLDecoder.decode(
                    fields[0],
                    StandardCharsets.UTF_8.name()
                );
                String value = URLDecoder.decode(
                    fields[1],
                    StandardCharsets.UTF_8.name()
                );
                Object builder = builderType.getConstructor().newInstance();
                builderType.getMethod("name", String.class)
                    .invoke(builder, name);
                builderType.getMethod("value", String.class)
                    .invoke(builder, value);
                String domain = fields.length == 8
                    ? decodeCookieField(fields[2])
                    : host;
                String path = fields.length == 8
                    ? decodeCookieField(fields[3])
                    : "/";
                if (domain.startsWith(".")) {
                    domain = domain.substring(1);
                }
                boolean hostOnly = fields.length != 8 ||
                    Boolean.parseBoolean(fields[7]);
                builderType.getMethod(
                    hostOnly ? "hostOnlyDomain" : "domain",
                    String.class
                ).invoke(builder, domain.isEmpty() ? host : domain);
                builderType.getMethod("path", String.class)
                    .invoke(builder, path.isEmpty() ? "/" : path);
                if (fields.length == 8) {
                    if (!fields[4].isEmpty()) {
                        builderType.getMethod("expiresAt", long.class)
                            .invoke(builder, Long.parseLong(fields[4]));
                    }
                    if (Boolean.parseBoolean(fields[5])) {
                        builderType.getMethod("secure").invoke(builder);
                    }
                    if (Boolean.parseBoolean(fields[6])) {
                        builderType.getMethod("httpOnly").invoke(builder);
                    }
                }
                cookies.add(builderType.getMethod("build").invoke(builder));
            }
        }
        cookieJar.getClass()
            .getMethod("saveFromResponse", httpUrlType, List.class)
            .invoke(cookieJar, httpUrl, cookies);
        String userAgent = defaultValue(request.get("userAgent"), "");
        if (!userAgent.isEmpty()) {
            updateCloudflareUserAgent(client, userAgent);
            setForcedUserAgent(client, userAgent);
        }
        return MiniJson.response(
            true,
            Integer.toString(cookies.size()),
            null,
            null
        );
    }

    private static String decodeCookieField(String value) throws Exception {
        return URLDecoder.decode(value, StandardCharsets.UTF_8.name());
    }

    private static String encodeCookieField(String value) throws Exception {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name())
            .replace("+", "%20");
    }

    private static String sourceUserAgent(Object source, Object client) {
        String headerUserAgent = "";
        try {
            Object headers = getter(source, "getHeaders");
            Object value = headers.getClass()
                .getMethod("get", String.class)
                .invoke(headers, "User-Agent");
            if (value != null && !String.valueOf(value).isEmpty()) {
                headerUserAgent = String.valueOf(value);
            }
        } catch (Throwable ignored) {
            // Some sources do not override headers.
        }
        // Android solves the challenge with the exact agent the failing request carried:
        //
        //     setUserAgent(request.header("User-Agent") ?: defaultUserAgentProvider())
        //
        // and consults the provider only when the request has none of its own. That order is
        // the whole point. `cf_clearance` is bound to the agent that earned it, and a source's
        // `headers` is a `by lazy`: it snapshots the agent once, on first use, and keeps it for
        // the life of the source object. `UserAgentInterceptor` then leaves that header alone,
        // because it only fills one in when the request has none.
        //
        // So a live agent -- one the user has just changed in Advanced settings -- reaches the
        // solver but never reaches the wire. Preferring it here handed the WebView one string
        // while OkHttp kept sending another, and the clearance it came back with was bound to
        // an agent no request would ever present. The challenge is solved and immediately
        // useless, which is a site that stops asking and never loads.
        if (!headerUserAgent.isEmpty()) {
            return headerUserAgent;
        }
        try {
            for (Object interceptor : clientInterceptors(client)) {
                if (!interceptor.getClass().getName().endsWith(
                    "UserAgentInterceptor"
                )) {
                    continue;
                }
                try {
                    Object value = interceptor.getClass()
                        .getMethod("effectiveUserAgent", String.class)
                        .invoke(
                            interceptor,
                            headerUserAgent.isEmpty() ? null : headerUserAgent
                        );
                    if (value != null && !String.valueOf(value).isEmpty()) {
                        return String.valueOf(value);
                    }
                } catch (NoSuchMethodException ignored) {
                    // Older compatibility layers expose only the provider.
                }
                Object provider = reflectedField(
                    interceptor,
                    "defaultUserAgentProvider"
                );
                Object value = provider.getClass().getMethod("invoke")
                    .invoke(provider);
                if (
                    headerUserAgent.isEmpty() &&
                    value != null &&
                    !String.valueOf(value).isEmpty()
                ) {
                    return String.valueOf(value);
                }
            }
        } catch (Throwable ignored) {
            // The WebKit user agent is a safe fallback on the Swift side.
        }
        return headerUserAgent;
    }

    private static void applyConfiguredDefaultUserAgent(
        Map<String, String> request
    ) throws Exception {
        String userAgent = defaultValue(request.get("userAgent"), "").trim();
        if (userAgent.isEmpty()) {
            return;
        }
        // WebSettings.getDefaultUserAgent is static and may be called while
        // constructing an extension, before a source ID exists. Keep the JVM
        // property synchronized for both construction and normal requests.
        System.setProperty("http.agent", userAgent);
        if (
            request.get("extensionId") == null ||
            request.get("sourceId") == null
        ) {
            return;
        }
        Object source = requireSource(request);
        Object client = getter(source, "getClient");
        try {
            Method networkGetter = findMethod(
                source.getClass(),
                "getNetwork"
            );
            networkGetter.setAccessible(true);
            Object network = networkGetter.invoke(source);
            Object flow = reflectedField(network, "userAgent");
            Class<?> mutableStateFlow = Class.forName(
                "kotlinx.coroutines.flow.MutableStateFlow",
                true,
                source.getClass().getClassLoader()
            );
            mutableStateFlow.getMethod("setValue", Object.class)
                .invoke(flow, userAgent);
        } catch (Throwable ignored) {
            // Custom sources may expose a client without NetworkHelper.
        }
        updateCloudflareUserAgent(client, userAgent);
    }

    private static void updateCloudflareUserAgent(
        Object client,
        String userAgent
    ) {
        try {
            for (Object interceptor : clientInterceptors(client)) {
                if (!interceptor.getClass().getName().endsWith(
                    "CloudflareInterceptor"
                )) {
                    continue;
                }
                Object setter = reflectedField(interceptor, "setUserAgent");
                setter.getClass().getMethod("invoke", Object.class)
                    .invoke(setter, userAgent);
            }
        } catch (Throwable ignored) {
            // Older extension libraries have no mutable Cloudflare UA hook.
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Object> clientInterceptors(Object client)
        throws Exception {
        return (List<Object>) client.getClass()
            .getMethod("interceptors")
            .invoke(client);
    }

    private static Object reflectedField(Object value, String name)
        throws Exception {
        Class<?> current = value.getClass();
        while (current != null) {
            try {
                java.lang.reflect.Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(value);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(value.getClass().getName() + "." + name);
    }

    private static boolean invokeCookieClear(Object value) throws Exception {
        if (value == null) {
            return false;
        }
        for (String methodName : new String[] { "clear", "removeAll" }) {
            try {
                Method method = value.getClass().getMethod(methodName);
                method.setAccessible(true);
                method.invoke(value);
                return true;
            } catch (NoSuchMethodException ignored) {
                // PersistentCookieJar owns a CookieStore with removeAll().
            }
        }
        Class<?> current = value.getClass();
        while (current != null) {
            for (java.lang.reflect.Field field : current.getDeclaredFields()) {
                field.setAccessible(true);
                Object child = field.get(value);
                if (
                    child != value &&
                    child != null &&
                    (child instanceof java.net.CookieStore ||
                        child.getClass().getName().contains("CookieStore")) &&
                    invokeCookieClear(child)
                ) {
                    return true;
                }
            }
            current = current.getSuperclass();
        }
        return false;
    }

    private static Method findMethod(
        Class<?> type,
        String name,
        Class<?>... parameterTypes
    ) throws NoSuchMethodException {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredMethod(name, parameterTypes);
            } catch (NoSuchMethodException missing) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchMethodException(type.getName() + "." + name);
    }

    private static Object requireSource(Map<String, String> request)
        throws Exception {
        String extensionId = require(request, "extensionId");
        LoadedExtension extension = EXTENSIONS.get(extensionId);
        if (extension == null) {
            throw new IllegalArgumentException(
                "Extension is not loaded: " + extensionId
            );
        }
        return extension.source(request.get("sourceId"));
    }

    private static String listSources(Map<String, String> request)
        throws Exception {
        String extensionId = require(request, "extensionId");
        LoadedExtension extension = EXTENSIONS.get(extensionId);
        if (extension == null) {
            throw new IllegalArgumentException(
                "Extension is not loaded: " + extensionId
            );
        }

        StringBuilder output = new StringBuilder("[");
        for (int index = 0; index < extension.sources.size(); index++) {
            if (index > 0) {
                output.append(',');
            }
            Object source = extension.sources.get(index);
            output.append('{');
            appendJsonField(output, "id", getter(source, "getId"), false);
            appendJsonField(output, "name", getter(source, "getName"), true);
            appendJsonField(output, "lang", getter(source, "getLang"), true);
            appendJsonField(
                output,
                "supportsLatest",
                getter(source, "getSupportsLatest"),
                true
            );
            try {
                appendJsonField(
                    output,
                    "baseURL",
                    getter(source, "getBaseUrl"),
                    true
                );
            } catch (ReflectiveOperationException ignored) {
                appendJsonField(output, "baseURL", "", true);
            }
            appendJsonField(output, "homeURL", homeURL(source), true);
            output.append('}');
        }
        output.append(']');
        return MiniJson.response(true, output.toString(), null, null);
    }

    static Object invokeSuspend(
        Object instance,
        String methodName,
        Class<?>[] parameterTypes,
        Object... arguments
    ) throws Exception {
        ClassLoader loader = ExtensionHost.class.getClassLoader();
        Class<?> continuationType =
            Class.forName("kotlin.coroutines.Continuation", true, loader);
        Class<?>[] suspendParameterTypes =
            new Class<?>[parameterTypes.length + 1];
        System.arraycopy(
            parameterTypes,
            0,
            suspendParameterTypes,
            0,
            parameterTypes.length
        );
        suspendParameterTypes[parameterTypes.length] = continuationType;

        CountDownLatch completion = new CountDownLatch(1);
        AtomicReference<Object> resumedValue = new AtomicReference<>();
        Object emptyContext = Class.forName(
            "kotlin.coroutines.EmptyCoroutineContext",
            true,
            loader
        ).getField("INSTANCE").get(null);

        InvocationHandler handler = (proxy, method, invocationArguments) -> {
            switch (method.getName()) {
                case "getContext":
                    return emptyContext;
                case "resumeWith":
                    resumedValue.set(invocationArguments[0]);
                    completion.countDown();
                    return null;
                case "toString":
                    return "TachiyomiAZContinuation(" + methodName + ")";
                default:
                    return null;
            }
        };
        Object continuation = Proxy.newProxyInstance(
            continuationType.getClassLoader(),
            new Class<?>[] { continuationType },
            handler
        );

        Object[] suspendArguments = new Object[arguments.length + 1];
        System.arraycopy(
            arguments,
            0,
            suspendArguments,
            0,
            arguments.length
        );
        suspendArguments[arguments.length] = continuation;

        Object immediate;
        try {
            immediate = instance.getClass()
                .getMethod(methodName, suspendParameterTypes)
                .invoke(instance, suspendArguments);
        } catch (InvocationTargetException error) {
            throw rethrow(error.getCause());
        }

        Object suspended = enumConstant(
            Class.forName(
                "kotlin.coroutines.intrinsics.CoroutineSingletons",
                true,
                loader
            ),
            "COROUTINE_SUSPENDED"
        );
        Object result = immediate;
        if (immediate == suspended) {
            if (!completion.await(2, TimeUnit.MINUTES)) {
                throw new IllegalStateException(
                    "Extension operation timed out after 2 minutes"
                );
            }
            result = resumedValue.get();
        }

        try {
            Class.forName("kotlin.ResultKt", true, loader)
                .getMethod("throwOnFailure", Object.class)
                .invoke(null, result);
        } catch (InvocationTargetException error) {
            throw rethrow(error.getCause());
        }
        return result;
    }

    private static Object enumConstant(Class<?> type, String name) {
        Object[] constants = type.getEnumConstants();
        if (constants == null) {
            throw new IllegalArgumentException(
                type.getName() + " is not an enum"
            );
        }
        for (Object constant : constants) {
            if (((Enum<?>) constant).name().equals(name)) {
                return constant;
            }
        }
        throw new IllegalArgumentException(
            "Unknown " + type.getName() + " value: " + name
        );
    }

    private static Exception rethrow(Throwable error) throws Exception {
        if (error instanceof Exception) {
            return (Exception) error;
        }
        if (error instanceof Error) {
            throw (Error) error;
        }
        return new RuntimeException(error);
    }

    private static String serializeMangasPage(Object page) throws Exception {
        @SuppressWarnings("unchecked")
        List<Object> mangas = (List<Object>) page.getClass()
            .getMethod("getMangas")
            .invoke(page);
        boolean hasNextPage = (Boolean) page.getClass()
            .getMethod("getHasNextPage")
            .invoke(page);

        StringBuilder output = new StringBuilder("{\"mangas\":[");
        for (int index = 0; index < mangas.size(); index++) {
            if (index > 0) {
                output.append(',');
            }
            Object manga = mangas.get(index);
            appendManga(output, manga);
        }
        output.append("],\"hasNextPage\":")
            .append(hasNextPage)
            .append('}');
        return output.toString();
    }

    private static String serializeMangaUpdate(
        Object update,
        String fallbackMangaURL,
        String fallbackMangaTitle
    ) throws Exception {
        Object manga = getter(update, "getManga");
        @SuppressWarnings("unchecked")
        List<Object> chapters =
            (List<Object>) getter(update, "getChapters");
        String mangaTitle = String.valueOf(
            getterOrFallback(manga, "getTitle", fallbackMangaTitle)
        );
        StringBuilder output = new StringBuilder("{\"manga\":");
        appendManga(
            output,
            manga,
            fallbackMangaURL,
            fallbackMangaTitle
        );
        output.append(",\"chapters\":[");
        for (int index = 0; index < chapters.size(); index++) {
            if (index > 0) {
                output.append(',');
            }
            Object chapter = chapters.get(index);
            Object chapterName = getter(chapter, "getName");
            Object suppliedNumber = getter(chapter, "getChapter_number");
            float chapterNumber = ChapterNumberParser.parse(
                mangaTitle,
                String.valueOf(chapterName),
                suppliedNumber instanceof Number
                    ? (Number) suppliedNumber
                    : null
            );
            output.append('{');
            appendJsonField(
                output,
                "url",
                getter(chapter, "getUrl"),
                false
            );
            appendJsonField(
                output,
                "name",
                chapterName,
                true
            );
            appendJsonField(
                output,
                "chapterNumber",
                chapterNumber,
                true
            );
            appendJsonField(
                output,
                "scanlator",
                getter(chapter, "getScanlator"),
                true
            );
            appendJsonField(
                output,
                "dateUpload",
                getter(chapter, "getDate_upload"),
                true
            );
            appendJsonField(output, "memo", memoJSON(chapter), true);
            output.append('}');
        }
        output.append("]}");
        return output.toString();
    }

    private static void appendManga(StringBuilder output, Object manga)
        throws Exception {
        appendManga(output, manga, null, null);
    }

    private static void appendManga(
        StringBuilder output,
        Object manga,
        String fallbackURL,
        String fallbackTitle
    ) throws Exception {
        output.append('{');
        appendJsonField(
            output,
            "url",
            getterOrFallback(manga, "getUrl", fallbackURL),
            false
        );
        appendJsonField(
            output,
            "title",
            getterOrFallback(manga, "getTitle", fallbackTitle),
            true
        );
        appendJsonField(
            output,
            "thumbnailURL",
            getter(manga, "getThumbnail_url"),
            true
        );
        appendJsonField(output, "artist", getter(manga, "getArtist"), true);
        appendJsonField(output, "author", getter(manga, "getAuthor"), true);
        appendJsonField(output, "status", getter(manga, "getStatus"), true);
        appendJsonField(
            output,
            "description",
            getter(manga, "getDescription"),
            true
        );
        appendJsonField(output, "genre", getter(manga, "getGenre"), true);
        appendJsonField(
            output,
            "updateStrategy",
            getter(manga, "getUpdate_strategy"),
            true
        );
        appendJsonField(output, "initialized", getter(manga, "getInitialized"), true);
        appendJsonField(output, "memo", memoJSON(manga), true);
        output.append('}');
    }

    private static String homeURL(Object source) throws Exception {
        try {
            return String.valueOf(getter(source, "getHomeUrl"));
        } catch (NoSuchMethodException ignored) {
            try {
                return String.valueOf(getter(source, "getBaseUrl"));
            } catch (NoSuchMethodException unsupported) {
                return "";
            }
        }
    }

    private static void setForcedUserAgent(Object client, String userAgent) {
        if (userAgent != null && !userAgent.isEmpty()) {
            System.setProperty("http.agent", userAgent);
        }
        try {
            for (Object interceptor : clientInterceptors(client)) {
                if (!interceptor.getClass().getName().endsWith(
                    "UserAgentInterceptor"
                )) {
                    continue;
                }
                if (userAgent == null || userAgent.isEmpty()) {
                    interceptor.getClass()
                        .getMethod("clearForcedUserAgent")
                        .invoke(interceptor);
                } else {
                    interceptor.getClass()
                        .getMethod("forceUserAgent", String.class)
                        .invoke(interceptor, userAgent);
                }
            }
        } catch (Throwable ignored) {
            // Older compatibility layers have no clearance-UA pinning hook.
        }
    }

    /**
     * Extension-lib 1.6 memo is an opaque JsonObject. Keep its serialized JSON
     * intact instead of attempting to understand or flatten extension-owned
     * state. Older extension libraries simply have no getMemo/setMemo methods.
     */
    private static String memoJSON(Object value) throws Exception {
        try {
            Object memo = getter(value, "getMemo");
            return memo == null ? null : memo.toString();
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    private static void restoreMemo(Object value, String json) throws Exception {
        if (json == null || json.trim().isEmpty()) return;
        Method setter = null;
        for (Method candidate : value.getClass().getMethods()) {
            if (candidate.getName().equals("setMemo") && candidate.getParameterCount() == 1) {
                setter = candidate;
                break;
            }
        }
        if (setter == null) return;

        ClassLoader loader = value.getClass().getClassLoader();
        Class<?> jsonType = Class.forName(
            "kotlinx.serialization.json.Json",
            true,
            loader
        );
        Object parser = jsonType.getField("Default").get(null);
        Object memo = jsonType.getMethod("parseToJsonElement", String.class)
            .invoke(parser, json);
        if (!setter.getParameterTypes()[0].isInstance(memo)) {
            throw new IllegalArgumentException("Extension memo must be a JSON object");
        }
        setter.invoke(value, memo);
    }

    @SuppressWarnings("unchecked")
    private static String serializePages(Object source, Object value)
        throws Exception {
        List<Object> pages = (List<Object>) value;
        StringBuilder output = new StringBuilder("[");
        for (int index = 0; index < pages.size(); index++) {
            if (index > 0) {
                output.append(',');
            }
            Object page = pages.get(index);
            Object imageURL = getter(page, "getImageUrl");
            Object pageURL = getter(page, "getUrl");
            if (
                (imageURL == null || imageURL.toString().isEmpty()) &&
                pageURL != null &&
                !pageURL.toString().isEmpty()
            ) {
                try {
                    imageURL = invokeSuspend(
                        source,
                        "getImageUrl",
                        new Class<?>[] { page.getClass() },
                        page
                    );
                } catch (NoSuchMethodException unsupported) {
                    imageURL = null;
                }
            }
            output.append('{');
            appendJsonField(output, "index", getter(page, "getIndex"), false);
            appendJsonField(output, "url", pageURL, true);
            appendJsonField(
                output,
                "imageURL",
                imageURL,
                true
            );
            Object uri = getter(page, "getUri");
            appendJsonField(
                output,
                "uri",
                uri == null ? null : uri.toString(),
                true
            );
            output.append('}');
        }
        output.append(']');
        return output.toString();
    }

    private static Object getter(Object instance, String name)
        throws Exception {
        return beanMethod(instance.getClass(), name).invoke(instance);
    }

    private static Object getterOrFallback(
        Object instance,
        String name,
        Object fallback
    ) throws Exception {
        try {
            Object value = getter(instance, name);
            if (value instanceof String && ((String) value).isEmpty()) {
                return fallback;
            }
            return value == null ? fallback : value;
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (
                fallback != null &&
                cause != null &&
                "kotlin.UninitializedPropertyAccessException".equals(
                    cause.getClass().getName()
                )
            ) {
                return fallback;
            }
            throw rethrow(cause == null ? error : cause);
        }
    }

    private static void setter(
        Object instance,
        String name,
        Class<?> parameterType,
        Object value
    ) throws Exception {
        beanMethod(instance.getClass(), name, parameterType)
            .invoke(instance, value);
    }

    /**
     * TachiyomiX historically used snake-case Kotlin properties, while some
     * implementations expose normalized JavaBean accessors. Accept both ABIs.
     */
    private static Method beanMethod(
        Class<?> type,
        String name,
        Class<?>... parameterTypes
    ) throws NoSuchMethodException {
        try {
            return type.getMethod(name, parameterTypes);
        } catch (NoSuchMethodException original) {
            String alternative = alternateBeanMethodName(name);
            if (alternative.equals(name)) {
                throw original;
            }
            try {
                return type.getMethod(alternative, parameterTypes);
            } catch (NoSuchMethodException ignored) {
                throw original;
            }
        }
    }

    private static String alternateBeanMethodName(String name) {
        int prefixLength = name.startsWith("is") ? 2 : 3;
        if (
            name.length() <= prefixLength ||
            !(name.startsWith("get") ||
                name.startsWith("set") ||
                name.startsWith("is"))
        ) {
            return name;
        }

        String property = name.substring(prefixLength);
        StringBuilder alternative = new StringBuilder(
            name.substring(0, prefixLength)
        );
        if (property.indexOf('_') >= 0) {
            boolean uppercaseNext = false;
            for (int index = 0; index < property.length(); index++) {
                char character = property.charAt(index);
                if (character == '_') {
                    uppercaseNext = true;
                } else if (uppercaseNext) {
                    alternative.append(Character.toUpperCase(character));
                    uppercaseNext = false;
                } else {
                    alternative.append(character);
                }
            }
        } else {
            for (int index = 0; index < property.length(); index++) {
                char character = property.charAt(index);
                if (index > 0 && Character.isUpperCase(character)) {
                    alternative.append('_')
                        .append(Character.toLowerCase(character));
                } else {
                    alternative.append(character);
                }
            }
        }
        return alternative.toString();
    }

    private static String defaultValue(String value, String fallback) {
        return value == null ? fallback : value;
    }

    private static void appendJsonField(
        StringBuilder output,
        String key,
        Object value,
        boolean comma
    ) {
        if (comma) {
            output.append(',');
        }
        output.append('"')
            .append(MiniJson.escapeValue(key))
            .append("\":");
        if (value == null) {
            output.append("null");
        } else if (value instanceof Number || value instanceof Boolean) {
            output.append(value);
        } else {
            output.append('"')
                .append(MiniJson.escapeValue(value.toString()))
                .append('"');
        }
    }

    private static String invoke(Map<String, String> request) throws Exception {
        String extensionId = require(request, "extensionId");
        String methodName = require(request, "method");
        String argument = request.get("argument");
        LoadedExtension extension = EXTENSIONS.get(extensionId);
        if (extension == null) {
            throw new IllegalArgumentException(
                "Extension is not loaded: " + extensionId
            );
        }

        Object target = extension.source(request.get("sourceId"));
        Method method;
        Object value;
        if (argument == null) {
            method = target.getClass().getMethod(methodName);
            value = method.invoke(target);
        } else {
            method = target
                .getClass()
                .getMethod(methodName, String.class);
            value = method.invoke(target, argument);
        }
        return MiniJson.response(
            true,
            value == null ? null : value.toString(),
            null,
            null
        );
    }

    private static String unloadExtension(Map<String, String> request)
        throws Exception {
        String extensionId = require(request, "extensionId");
        LoadedExtension extension = EXTENSIONS.remove(extensionId);
        if (extension != null) {
            extension.close();
        }
        return MiniJson.response(true, extensionId, null, null);
    }

    private static String require(Map<String, String> request, String key) {
        String value = request.get(key);
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("Missing field: " + key);
        }
        return value;
    }

    private static Map<String, String> metadataMap(
        TachiyomiXJarMetadata.Metadata metadata
    ) {
        Map<String, String> response = new LinkedHashMap<>();
        response.put("packageName", metadata.packageName);
        response.put("name", metadata.name);
        response.put("version", metadata.version);
        response.put("versionCode", metadata.versionCode);
        response.put("entryClass", metadata.entryClass);
        response.put("minimumSdk", metadata.minimumSdk);
        response.put("targetSdk", metadata.targetSdk);
        response.put("nsfw", metadata.nsfw);
        response.put("extensionLibrary", metadata.extensionLibrary);
        response.put(
            "classCount",
            Integer.toString(metadata.bytecode.classCount)
        );
        response.put(
            "maximumClassVersion",
            Integer.toString(metadata.bytecode.maximumMajorVersion)
        );
        response.put(
            "requiredJavaVersion",
            Integer.toString(
                JarBytecodeValidator.javaVersionForClassVersion(
                    metadata.bytecode.maximumMajorVersion
                )
            )
        );
        response.put(
            "runtimeCompatible",
            Boolean.toString(
                metadata.bytecode.isCompatibleWithCurrentRuntime()
            )
        );
        return response;
    }

    private static String describe(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return cause.getClass().getName() +
            (message == null ? "" : ": " + message);
    }

    private static boolean isCloudflareChallenge(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String message = current.getMessage();
            if (
                message != null &&
                message.toLowerCase().contains(
                    "cloudflare bypass currently disabled"
                )
            ) {
                return true;
            }
            try {
                Object response = current.getClass()
                    .getMethod("response")
                    .invoke(current);
                if (response != null && isCloudflareResponse(response)) {
                    return true;
                }
            } catch (Throwable ignored) {
                // Not an HTTP exception carrying an OkHttp response.
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean isCloudflareResponse(Object response) {
        try {
            int code = ((Number) response.getClass()
                .getMethod("code")
                .invoke(response)).intValue();
            if (code != 403 && code != 503) {
                return false;
            }
            Object server = response.getClass()
                .getMethod("header", String.class)
                .invoke(response, "Server");
            return server != null && String.valueOf(server)
                .toLowerCase()
                .contains("cloudflare");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static final class LoadedExtension {
        final URLClassLoader loader;
        final Object entry;
        final List<Object> sources;
        final Object defaultSource;

        LoadedExtension(
            URLClassLoader loader,
            Object entry,
            List<Object> sources
        ) throws Exception {
            this.loader = loader;
            this.entry = entry;
            this.sources = sources;
            Object fallback = sources.get(0);
            for (Object source : sources) {
                try {
                    Object lang = getter(source, "getLang");
                    if ("en".equals(lang)) {
                        fallback = source;
                        break;
                    }
                } catch (NoSuchMethodException notASource) {
                    // Host-only fixtures may expose arbitrary entry objects.
                }
            }
            defaultSource = fallback;
        }

        Object source(String sourceId) throws Exception {
            if (sourceId == null || sourceId.isEmpty()) {
                return defaultSource;
            }
            for (Object source : sources) {
                if (
                    sourceId.equals(
                        getter(source, "getId").toString()
                    )
                ) {
                    return source;
                }
            }
            throw new IllegalArgumentException(
                "Source is not loaded: " + sourceId
            );
        }

        void close() throws Exception {
            loader.close();
        }
    }
}
