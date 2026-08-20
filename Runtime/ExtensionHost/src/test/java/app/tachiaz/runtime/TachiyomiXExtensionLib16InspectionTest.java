package app.tachiaz.runtime;

import java.io.File;

public final class TachiyomiXExtensionLib16InspectionTest {
    private TachiyomiXExtensionLib16InspectionTest() {
    }

    public static void main(String[] arguments) {
        if (arguments.length != 1) {
            throw new AssertionError("Expected the extension-lib 1.6 JAR path");
        }

        String escapedPath = new File(arguments[0])
            .getAbsolutePath()
            .replace("\\", "\\\\")
            .replace("\"", "\\\"");
        String response = ExtensionHost.dispatch(
            "{\"operation\":\"inspectExtension\"," +
                "\"jarPath\":\"" + escapedPath + "\"}"
        );

        assertContains(response, "\"success\":true");
        assertContains(
            response,
            "\"packageName\":" +
                "\"eu.kanade.tachiyomi.extension.en.asurascans\""
        );
        assertContains(response, "\"name\":\"Asura Scans\"");
        assertContains(response, "\"version\":\"1.6.66\"");
        assertContains(response, "\"versionCode\":\"66\"");
        assertContains(
            response,
            "\"entryClass\":" +
                "\"eu.kanade.tachiyomi.extension.en.asurascans." +
                "ExtensionGenerated\""
        );
        assertContains(response, "\"extensionLibrary\":\"1.6\"");
        assertContains(response, "\"maximumClassVersion\":\"55\"");
        assertContains(response, "\"requiredJavaVersion\":\"11\"");
        System.out.println("TachiyomiX extension-lib 1.6 inspection test passed");
    }

    private static void assertContains(String actual, String expected) {
        if (!actual.contains(expected)) {
            throw new AssertionError(
                "Expected response to contain " +
                    expected +
                    ", got " +
                    actual
            );
        }
    }
}
