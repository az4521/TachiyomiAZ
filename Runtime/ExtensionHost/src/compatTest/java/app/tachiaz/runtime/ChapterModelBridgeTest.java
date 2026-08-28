package app.tachiaz.runtime;

import eu.kanade.tachiyomi.source.model.SChapter;
import java.lang.reflect.Method;
import java.util.List;

/** Verifies the iOS JVM bridge against the concrete chapter model in server-1.0.jar. */
public final class ChapterModelBridgeTest {
    private ChapterModelBridgeTest() {
    }

    public static void main(String[] arguments) throws Exception {
        Method restore = ExtensionHost.class.getDeclaredMethod(
            "restoreChapters",
            String.class,
            ClassLoader.class
        );
        restore.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<Object> chapters = (List<Object>) restore.invoke(
            null,
            "[{\"url\":\"/chapter/12\",\"name\":\"Chapter 12\"," +
                "\"chapterNumber\":\"12.5\",\"dateUpload\":\"1234\"}]",
            ExtensionHost.class.getClassLoader()
        );
        SChapter chapter = (SChapter) chapters.get(0);
        if (chapter.getChapter_number() != 12.5f) {
            throw new AssertionError("Chapter number was not restored");
        }
        if (chapter.getDate_upload() != 1234L) {
            throw new AssertionError("Upload date was not restored");
        }

        Method setter = ExtensionHost.class.getDeclaredMethod(
            "setter",
            Object.class,
            String.class,
            Class.class,
            Object.class
        );
        setter.setAccessible(true);
        CamelCaseChapter camelCaseChapter = new CamelCaseChapter();
        setter.invoke(
            null,
            camelCaseChapter,
            "setChapter_number",
            float.class,
            7.5f
        );
        if (camelCaseChapter.getChapterNumber() != 7.5f) {
            throw new AssertionError("Camel-case setter fallback failed");
        }

        Method getter = ExtensionHost.class.getDeclaredMethod(
            "getter",
            Object.class,
            String.class
        );
        getter.setAccessible(true);
        Object number = getter.invoke(
            null,
            camelCaseChapter,
            "getChapter_number"
        );
        if (!Float.valueOf(7.5f).equals(number)) {
            throw new AssertionError("Camel-case getter fallback failed");
        }
        System.out.println("iOS JVM chapter model bridge test passed");
    }

    public static final class CamelCaseChapter {
        private float chapterNumber;

        public float getChapterNumber() {
            return chapterNumber;
        }

        public void setChapterNumber(float value) {
            chapterNumber = value;
        }
    }
}
