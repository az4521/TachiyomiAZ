package app.tachiaz.runtime;

import java.util.HashMap;
import java.util.Map;
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
        System.out.println("Komga extension compatibility test passed");
    }

    private static void assertEquals(String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("Expected " + expected + ", got " + actual);
        }
    }
}
