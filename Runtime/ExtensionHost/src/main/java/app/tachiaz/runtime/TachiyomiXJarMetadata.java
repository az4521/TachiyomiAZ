package app.tachiaz.runtime;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Reads the Android-style manifest shipped inside TachiyomiX's prebuilt JARs.
 * These artifacts are already JVM class archives; no APK or DEX conversion is
 * performed.
 */
final class TachiyomiXJarMetadata {
    private static final Pattern SUPPORTED_EXTENSION_LIBRARY =
        Pattern.compile("^1\\.(4|5|6)(?:\\..*)?$");
    private static final Pattern PACKAGE = Pattern.compile(
        "<manifest[^>]*\\bpackage=\"([^\"]+)\"",
        Pattern.DOTALL
    );
    private static final Pattern VERSION_CODE = Pattern.compile(
        "\\bandroid:versionCode=\"([^\"]+)\""
    );
    private static final Pattern VERSION_NAME = Pattern.compile(
        "\\bandroid:versionName=\"([^\"]+)\""
    );
    private static final Pattern MIN_SDK = Pattern.compile(
        "\\bandroid:minSdkVersion=\"([^\"]+)\""
    );
    private static final Pattern TARGET_SDK = Pattern.compile(
        "\\bandroid:targetSdkVersion=\"([^\"]+)\""
    );

    private TachiyomiXJarMetadata() {
    }

    static Metadata inspect(File jar) throws IOException {
        String manifest = readManifest(jar);
        String packageName = requiredMatch(PACKAGE, manifest, "package");
        String entryClass = requiredMetadata(
            manifest,
            "tachiyomi.extension.class"
        );
        if (entryClass.startsWith(".")) {
            entryClass = packageName + entryClass;
        } else if (entryClass.indexOf('.') < 0) {
            entryClass = packageName + "." + entryClass;
        }

        JarBytecodeValidator.Report bytecode =
            JarBytecodeValidator.inspect(jar);
        return new Metadata(
            packageName,
            requiredMetadata(manifest, "tachiyomix.name"),
            requiredMatch(VERSION_NAME, manifest, "versionName"),
            requiredMatch(VERSION_CODE, manifest, "versionCode"),
            entryClass,
            optionalMatch(MIN_SDK, manifest),
            optionalMatch(TARGET_SDK, manifest),
            optionalMetadata(manifest, "tachiyomi.extension.nsfw"),
            optionalMetadata(manifest, "tachiyomix.extensionLib"),
            bytecode
        );
    }

    static void requireSupportedLibrary(Metadata metadata) {
        String version = metadata.extensionLibrary;
        if (
            !supportsExtensionLibrary(version)
        ) {
            throw new IllegalArgumentException(
                "Unsupported Mihon extension library " +
                    (version == null ? "(missing)" : version) +
                    "; supported range is 1.4 through 1.6"
            );
        }
    }

    static boolean supportsExtensionLibrary(String version) {
        return version != null &&
            SUPPORTED_EXTENSION_LIBRARY.matcher(version).matches();
    }

    private static String readManifest(File jar) throws IOException {
        try (JarFile input = new JarFile(jar)) {
            JarEntry entry = input.getJarEntry("AndroidManifest.xml");
            if (entry == null) {
                throw new IllegalArgumentException(
                    "Extension JAR has no AndroidManifest.xml"
                );
            }
            try (
                InputStream stream = input.getInputStream(entry);
                ByteArrayOutputStream output = new ByteArrayOutputStream()
            ) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = stream.read(buffer)) >= 0) {
                    output.write(buffer, 0, count);
                }
                byte[] bytes = output.toByteArray();
                String value = new String(bytes, StandardCharsets.UTF_8);
                if (!value.trim().startsWith("<?xml")) {
                    throw new IllegalArgumentException(
                        "AndroidManifest.xml is not the textual manifest " +
                            "used by TachiyomiX JAR artifacts"
                    );
                }
                return value;
            }
        }
    }

    private static String requiredMatch(
        Pattern pattern,
        String input,
        String field
    ) {
        String value = optionalMatch(pattern, input);
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(
                "Extension manifest is missing " + field
            );
        }
        return value;
    }

    private static String optionalMatch(Pattern pattern, String input) {
        Matcher matcher = pattern.matcher(input);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String requiredMetadata(String manifest, String name) {
        String value = optionalMetadata(manifest, name);
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(
                "Extension manifest is missing metadata " + name
            );
        }
        return value;
    }

    private static String optionalMetadata(String manifest, String name) {
        Pattern pattern = Pattern.compile(
            "<meta-data\\s+[^>]*android:name=\"" +
                Pattern.quote(name) +
                "\"[^>]*android:value=\"([^\"]+)\"[^>]*/?>",
            Pattern.DOTALL
        );
        return optionalMatch(pattern, manifest);
    }

    static final class Metadata {
        final String packageName;
        final String name;
        final String version;
        final String versionCode;
        final String entryClass;
        final String minimumSdk;
        final String targetSdk;
        final String nsfw;
        final String extensionLibrary;
        final JarBytecodeValidator.Report bytecode;

        Metadata(
            String packageName,
            String name,
            String version,
            String versionCode,
            String entryClass,
            String minimumSdk,
            String targetSdk,
            String nsfw,
            String extensionLibrary,
            JarBytecodeValidator.Report bytecode
        ) {
            this.packageName = packageName;
            this.name = name;
            this.version = version;
            this.versionCode = versionCode;
            this.entryClass = entryClass;
            this.minimumSdk = minimumSdk;
            this.targetSdk = targetSdk;
            this.nsfw = nsfw;
            this.extensionLibrary = extensionLibrary;
            this.bytecode = bytecode;
        }
    }
}
