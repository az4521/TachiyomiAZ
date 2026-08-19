package app.tachiaz.runtime;

import android.net.Uri;
import android.os.Build;
import android.util.Base64;
import android.util.Log;
import java.nio.charset.StandardCharsets;

public final class ExtensionHostTest {
    private ExtensionHostTest() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1) {
            throw new AssertionError("Expected the fixture JAR path");
        }

        String ping = ExtensionHost.dispatch("{\"operation\":\"ping\"}");
        assertContains(ping, "\"result\":\"pong\"");
        assertEquals("true", MiniJson.parseObject(ping).get("success"));
        assertEquals("pong", MiniJson.parseObject(ping).get("result"));
        assertEquals(
            "https://example.test/manga/one?q=hello%20world",
            Uri.parse("https://example.test")
                .buildUpon()
                .appendPath("manga")
                .appendPath("one")
                .appendQueryParameter("q", "hello world")
                .build()
                .toString()
        );
        assertEquals(
            "aGVsbG8",
            Base64.encodeToString(
                "hello".getBytes(StandardCharsets.UTF_8),
                Base64.NO_WRAP | Base64.NO_PADDING
            )
        );
        assertTrue(TachiyomiXJarMetadata.supportsExtensionLibrary("1.4"));
        assertTrue(TachiyomiXJarMetadata.supportsExtensionLibrary("1.4.211"));
        assertTrue(TachiyomiXJarMetadata.supportsExtensionLibrary("1.5"));
        assertTrue(TachiyomiXJarMetadata.supportsExtensionLibrary("1.5.2"));
        assertTrue(TachiyomiXJarMetadata.supportsExtensionLibrary("1.6"));
        assertTrue(!TachiyomiXJarMetadata.supportsExtensionLibrary("1.3"));
        assertTrue(!TachiyomiXJarMetadata.supportsExtensionLibrary("1.7"));
        assertTrue(!TachiyomiXJarMetadata.supportsExtensionLibrary(null));
        assertFloatEquals(
            7,
            ChapterNumberParser.parse(
                "Fixture Manga",
                "Fixture Manga Vol. 2 Ch. 7",
                -1
            )
        );
        assertEquals(
            "https://example.test/path",
            Uri.parse("https://example.test")
                .buildUpon()
                .appendPath("path")
                .toString()
        );
        assertTrue(!Build.ID.isEmpty());
        assertTrue(Log.d("ExtensionHostTest", "debug", null) > 0);
        assertTrue(Log.wtf("ExtensionHostTest", "assert") > 0);
        assertFloatEquals(
            12.5f,
            ChapterNumberParser.parse(
                "Fixture Manga",
                "Fixture Manga Chapter 12.5",
                -1
            )
        );
        assertFloatEquals(
            12.2f,
            ChapterNumberParser.parse(
                "Fixture Manga",
                "Fixture Manga Chapter 12b",
                -1
            )
        );
        assertFloatEquals(
            4,
            ChapterNumberParser.parse("Fixture", "Chapter 99", 4)
        );

        String escapedPath = arguments[0]
            .replace("\\", "\\\\")
            .replace("\"", "\\\"");
        ClassLoader originalLoader = Thread.currentThread()
            .getContextClassLoader();
        Thread.currentThread().setContextClassLoader(null);
        try {
            assertContains(
                ExtensionHost.dispatch(
                    "{\"operation\":\"loadExtension\"," +
                        "\"extensionId\":\"fixture\"," +
                        "\"userAgent\":\"TachiyomiAZ-Test-UA\"," +
                        "\"jarPath\":\"" + escapedPath + "\"," +
                        "\"entryClass\":\"fixture.EchoExtension\"}"
                ),
                "\"success\":true"
            );
            assertEquals(
                "TachiyomiAZ-Test-UA",
                System.getProperty("http.agent")
            );
            assertNull(Thread.currentThread().getContextClassLoader());
            assertContains(
                ExtensionHost.dispatch(
                    "{\"operation\":\"invoke\"," +
                        "\"extensionId\":\"fixture\"," +
                        "\"method\":\"echo\"," +
                        "\"argument\":\"hello\"}"
                ),
                "\"result\":\"echo:hello\""
            );
            assertNull(Thread.currentThread().getContextClassLoader());
            assertContains(
                ExtensionHost.dispatch(
                    "{\"operation\":\"unloadExtension\"," +
                        "\"extensionId\":\"fixture\"}"
                ),
                "\"success\":true"
            );
        } finally {
            Thread.currentThread().setContextClassLoader(originalLoader);
        }

        System.out.println("ExtensionHost tests passed");
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

    private static void assertEquals(String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError(
                "Expected " + expected + ", got " + actual
            );
        }
    }

    private static void assertTrue(boolean value) {
        if (!value) {
            throw new AssertionError("Expected condition to be true");
        }
    }

    private static void assertNull(Object value) {
        if (value != null) {
            throw new AssertionError("Expected null, got " + value);
        }
    }

    private static void assertFloatEquals(float expected, float actual) {
        if (Math.abs(expected - actual) > 0.0001f) {
            throw new AssertionError(
                "Expected " + expected + ", got " + actual
            );
        }
    }
}
