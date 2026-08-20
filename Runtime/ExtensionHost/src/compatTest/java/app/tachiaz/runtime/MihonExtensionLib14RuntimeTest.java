package app.tachiaz.runtime;

import java.io.File;

public final class MihonExtensionLib14RuntimeTest {
    private MihonExtensionLib14RuntimeTest() {
    }

    public static void main(String[] arguments) {
        if (arguments.length != 1) {
            throw new AssertionError(
                "Expected the extension-lib 1.4 fixture JAR path"
            );
        }

        String escapedPath = new File(arguments[0])
            .getAbsolutePath()
            .replace("\\", "\\\\")
            .replace("\"", "\\\"");
        assertSuccess(ExtensionHost.dispatch(
            "{\"operation\":\"initializeCompatibility\"}"
        ));
        assertSuccess(ExtensionHost.dispatch(
            "{\"operation\":\"loadExtension\",\"extensionId\":\"mihon14\"," +
                "\"jarPath\":\"" + escapedPath + "\"}"
        ));
        String response = ExtensionHost.dispatch(
            "{\"operation\":\"getPopularManga\"," +
                "\"extensionId\":\"mihon14\",\"argument\":\"1\"}"
        );
        assertSuccess(response);
        assertContains(response, "\\\"mangas\\\":[]");
        assertContains(response, "\\\"hasNextPage\\\":true");
        System.out.println(
            "Mihon extension-lib 1.4 binary runtime test passed"
        );
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
}
