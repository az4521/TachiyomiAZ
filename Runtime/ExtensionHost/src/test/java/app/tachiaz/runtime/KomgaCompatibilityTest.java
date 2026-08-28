package app.tachiaz.runtime;

import java.util.HashMap;
import java.util.Map;
import java.io.File;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.text.StringSubstitutor;

/** Validates the Commons Text surface used by Keiyoushi Komga 69 (extension-lib 1.6). */
public final class KomgaCompatibilityTest {
    private KomgaCompatibilityTest() {
    }

    public static void main(String[] arguments) {
        Map<String, String> values = new HashMap<>();
        values.put("number", "2");
        values.put("title", "Arrival");
        StringSubstitutor substitutor = new StringSubstitutor(values, "{", "}");
        substitutor.setEnableUndefinedVariableException(true);

        assertEquals(
            "2 - Arrival ({literal})",
            substitutor.replace("{number} - {title} (${literal})")
        );

        try {
            substitutor.replace("{unknown}");
            throw new AssertionError("Undefined variables must be rejected by Komga validation");
        } catch (IllegalArgumentException expected) {
            // Expected: this is how Komga rejects invalid chapter-title templates.
        }
        if (arguments.length == 1) {
            verifyExtensionSettings(arguments[0]);
        } else if (arguments.length != 0) {
            throw new AssertionError("Expected zero arguments or the Komga JAR path");
        }
        System.out.println("Komga extension compatibility test passed");
    }

    private static void verifyExtensionSettings(String jarPath) {
        String escapedPath = new File(jarPath)
            .getAbsolutePath()
            .replace("\\", "\\\\")
            .replace("\"", "\\\"");
        assertSuccess(ExtensionHost.dispatch(
            "{\"operation\":\"initializeCompatibility\"}"
        ));
        assertSuccess(ExtensionHost.dispatch(
            "{\"operation\":\"loadExtension\",\"extensionId\":\"komga\"," +
                "\"jarPath\":\"" + escapedPath + "\"}"
        ));
        String sources = ExtensionHost.dispatch(
            "{\"operation\":\"listSources\",\"extensionId\":\"komga\"}"
        );
        assertSuccess(sources);
        Matcher sourceId = Pattern.compile("\\\\\"id\\\\\":(-?[0-9]+)")
            .matcher(sources);
        if (!sourceId.find()) {
            throw new AssertionError("Unable to find Komga source id in " + sources);
        }
        String requestIdentity =
            "\"extensionId\":\"komga\",\"sourceId\":\"" +
            sourceId.group(1) + "\"";
        String settings = ExtensionHost.dispatch(
            "{\"operation\":\"getSettings\"," + requestIdentity + "}"
        );
        assertSuccess(settings);
        assertContains(settings, "Address");
        assertSuccess(ExtensionHost.dispatch(
            "{\"operation\":\"getCookieSummary\"," + requestIdentity + "}"
        ));
    }

    private static void assertSuccess(String response) {
        assertContains(response, "\"success\":true");
    }

    private static void assertContains(String actual, String expected) {
        if (!actual.contains(expected)) {
            throw new AssertionError(
                "Expected response to contain " + expected + ", got " + actual
            );
        }
    }

    private static void assertEquals(String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("Expected " + expected + ", got " + actual);
        }
    }
}
