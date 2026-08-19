package app.tachiaz.runtime;

import java.io.File;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TachiyomiXExtensionLib16RuntimeTest {
    private TachiyomiXExtensionLib16RuntimeTest() {
    }

    public static void main(String[] arguments) {
        if (arguments.length != 1) {
            throw new AssertionError("Expected the extension-lib 1.6 JAR path");
        }

        String escapedPath = new File(arguments[0])
            .getAbsolutePath()
            .replace("\\", "\\\\")
            .replace("\"", "\\\"");
        assertSuccess(ExtensionHost.dispatch(
            "{\"operation\":\"initializeCompatibility\"}"
        ));
        assertSuccess(ExtensionHost.dispatch(
            "{\"operation\":\"loadExtension\",\"extensionId\":\"extlib16\"," +
                "\"jarPath\":\"" + escapedPath + "\"}"
        ));

        String sources = ExtensionHost.dispatch(
            "{\"operation\":\"listSources\",\"extensionId\":\"extlib16\"}"
        );
        assertSuccess(sources);
        Matcher sourceId = Pattern.compile("\\\\\"id\\\\\":(-?[0-9]+)")
            .matcher(sources);
        if (!sourceId.find()) {
            throw new AssertionError("Unable to find source id in " + sources);
        }
        String webLoginInfo = ExtensionHost.dispatch(
            "{\"operation\":\"getWebLoginInfo\"," +
                "\"extensionId\":\"extlib16\"," +
                "\"sourceId\":\"" + sourceId.group(1) + "\"," +
                "\"userAgent\":\"TachiyomiAZ-Configured-UA\"}"
        );
        assertSuccess(webLoginInfo);
        assertContains(webLoginInfo, "TachiyomiAZ-Configured-UA");

        String response = ExtensionHost.dispatch(
            "{\"operation\":\"getPopularManga\"," +
                "\"extensionId\":\"extlib16\",\"argument\":\"1\"}"
        );
        assertSuccess(response);
        assertContains(response, "\\\"mangas\\\":[{");
        assertContains(response, "\\\"title\\\":");
        assertContains(response, "\\\"thumbnailURL\\\":");
        assertContains(response, "\\\"hasNextPage\\\":");

        String filters = ExtensionHost.dispatch(
            "{\"operation\":\"getSearchFilters\"," +
                "\"extensionId\":\"extlib16\"}"
        );
        assertSuccess(filters);
        assertContains(filters, "\\\"type\\\":\\\"sort\\\"");
        assertStringScalar(filters, "defaultValue");
        assertStringScalar(filters, "auxiliary");
        System.out.println("TachiyomiX extension-lib 1.6 runtime test passed");

        // OkHttp and coroutine pools are deliberately process-wide in the app.
        // End this short-lived command line test without waiting for them.
        System.exit(0);
    }

    private static void assertSuccess(String response) {
        assertContains(response, "\"success\":true");
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

    private static void assertStringScalar(String response, String field) {
        assertContains(response, "\\\"" + field + "\\\":\\\"");
    }
}
