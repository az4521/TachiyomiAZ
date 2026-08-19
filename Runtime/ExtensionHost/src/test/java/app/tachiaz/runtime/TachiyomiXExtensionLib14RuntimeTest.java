package app.tachiaz.runtime;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public final class TachiyomiXExtensionLib14RuntimeTest {
    private static final String ENGLISH_SOURCE_ID = "2499283573021220255";

    private TachiyomiXExtensionLib14RuntimeTest() {
    }

    public static void main(String[] arguments) {
        if (arguments.length != 1) {
            throw new AssertionError("Expected the extension-lib 1.4 JAR path");
        }

        String escapedPath = new File(arguments[0])
            .getAbsolutePath()
            .replace("\\", "\\\\")
            .replace("\"", "\\\"");
        String inspection = ExtensionHost.dispatch(
            "{\"operation\":\"inspectExtension\"," +
                "\"jarPath\":\"" + escapedPath + "\"}"
        );
        assertSuccess(inspection);
        assertContains(inspection, "\"name\":\"MangaDex\"");
        assertContains(inspection, "\"extensionLibrary\":\"1.4\"");

        assertSuccess(ExtensionHost.dispatch(
            "{\"operation\":\"initializeCompatibility\"}"
        ));
        String loaded = ExtensionHost.dispatch(
            "{\"operation\":\"loadExtension\",\"extensionId\":\"extlib14\"," +
                "\"jarPath\":\"" + escapedPath + "\"}"
        );
        assertSuccess(loaded);
        assertContains(loaded, "\"sourceCount\":\"61\"");

        String sources = ExtensionHost.dispatch(
            "{\"operation\":\"listSources\",\"extensionId\":\"extlib14\"}"
        );
        assertSuccess(sources);
        assertContains(sources, "\\\"id\\\":" + ENGLISH_SOURCE_ID);
        assertContains(sources, "\\\"lang\\\":\\\"en\\\"");
        assertContains(sources, "\\\"supportsLatest\\\":true");
        assertContains(
            sources,
            "\\\"baseURL\\\":\\\"https://mangadex.org\\\""
        );
        String webLoginInfo = ExtensionHost.dispatch(
            "{\"operation\":\"getWebLoginInfo\"," +
                "\"extensionId\":\"extlib14\"," +
                "\"sourceId\":\"" + ENGLISH_SOURCE_ID + "\"}"
        );
        assertSuccess(webLoginInfo);
        assertContains(webLoginInfo, "https://mangadex.org");
        String configuredWebLoginInfo = ExtensionHost.dispatch(
            "{\"operation\":\"getWebLoginInfo\"," +
                "\"extensionId\":\"extlib14\"," +
                "\"sourceId\":\"" + ENGLISH_SOURCE_ID + "\"," +
                "\"userAgent\":\"TachiyomiAZ-Configured-UA\"}"
        );
        assertSuccess(configuredWebLoginInfo);
        assertContains(configuredWebLoginInfo, "Tachiyomi Mozilla/5.0");
        assertNotContains(configuredWebLoginInfo, "TachiyomiAZ-Configured-UA");
        assertSuccess(ExtensionHost.dispatch(
            "{\"operation\":\"setWebLoginCookies\"," +
                "\"extensionId\":\"extlib14\"," +
                "\"sourceId\":\"" + ENGLISH_SOURCE_ID + "\"," +
                "\"userAgent\":\"TachiyomiAZ-iOS-Test\"," +
                "\"argument\":\"tachiaz_test\\tworking\\t" +
                "mangadex.org\\t%2F\\t4102444800000\\ttrue\\t" +
                "true\\ttrue\"}"
        ));
        String clearanceWebLoginInfo = ExtensionHost.dispatch(
            "{\"operation\":\"getWebLoginInfo\"," +
                "\"extensionId\":\"extlib14\"," +
                "\"sourceId\":\"" + ENGLISH_SOURCE_ID + "\"}"
        );
        assertSuccess(clearanceWebLoginInfo);
        assertContains(clearanceWebLoginInfo, "TachiyomiAZ-iOS-Test");
        String cookieSummary = ExtensionHost.dispatch(
            "{\"operation\":\"getCookieSummary\"," +
                "\"extensionId\":\"extlib14\"," +
                "\"sourceId\":\"" + ENGLISH_SOURCE_ID + "\"}"
        );
        assertSuccess(cookieSummary);
        assertContains(cookieSummary, "tachiaz_test");
        String webLoginCookies = ExtensionHost.dispatch(
            "{\"operation\":\"getWebLoginCookies\"," +
                "\"extensionId\":\"extlib14\"," +
                "\"sourceId\":\"" + ENGLISH_SOURCE_ID + "\"," +
                "\"mangaURL\":\"https://mangadex.org/title/test\"}"
        );
        assertSuccess(webLoginCookies);
        assertContains(webLoginCookies, "tachiaz_test\\tworking");
        assertContains(webLoginCookies, "mangadex.org\\t%2F");
        assertSuccess(ExtensionHost.dispatch(
            "{\"operation\":\"clearCookies\"," +
                "\"extensionId\":\"extlib14\"," +
                "\"sourceId\":\"" + ENGLISH_SOURCE_ID + "\"}"
        ));

        String filters = ExtensionHost.dispatch(
            "{\"operation\":\"getSearchFilters\"," +
                "\"extensionId\":\"extlib14\"," +
                "\"sourceId\":\"" + ENGLISH_SOURCE_ID + "\"}"
        );
        assertSuccess(filters);
        assertContains(filters, "\\\"type\\\":\\\"sort\\\"");
        assertContains(filters, "\\\"type\\\":\\\"select\\\"");
        assertContains(filters, "\\\"type\\\":\\\"check\\\"");
        assertContains(filters, "\\\"group\\\":\\\"");
        assertStringScalar(filters, "defaultValue");
        assertStringScalar(filters, "auxiliary");
        String filterResult = MiniJson.parseObject(filters).get("result");
        String sortFilterId = fieldForType(
            filterResult,
            "sort",
            "id"
        );

        String settings = ExtensionHost.dispatch(
            "{\"operation\":\"getSettings\"," +
                "\"extensionId\":\"extlib14\"," +
                "\"sourceId\":\"" + ENGLISH_SOURCE_ID + "\"}"
        );
        assertSuccess(settings);
        assertContains(settings, "\\\"type\\\":\\\"select\\\"");
        String settingsResult = MiniJson.parseObject(settings).get("result");
        String selectKey = fieldForType(settingsResult, "select", "key");
        String selectValue = fieldForType(
            settingsResult,
            "select",
            "currentValue"
        );
        assertSuccess(ExtensionHost.dispatch(
            "{\"operation\":\"setSetting\"," +
                "\"extensionId\":\"extlib14\"," +
                "\"sourceId\":\"" + ENGLISH_SOURCE_ID + "\"," +
                "\"settingKey\":\"" +
                MiniJson.escapeValue(selectKey) + "\"," +
                "\"settingType\":\"select\"," +
                "\"settingValue\":\"" +
                MiniJson.escapeValue(selectValue) + "\"}"
        ));

        String popular = ExtensionHost.dispatch(
            "{\"operation\":\"getPopularManga\"," +
                "\"extensionId\":\"extlib14\"," +
                "\"sourceId\":\"" + ENGLISH_SOURCE_ID + "\"," +
                "\"argument\":\"1\"}"
        );
        assertSuccess(popular);
        assertContains(popular, "\\\"mangas\\\":[{");
        assertContains(popular, "\\\"title\\\":");
        assertContains(popular, "\\\"hasNextPage\\\":");

        String latest = ExtensionHost.dispatch(
            "{\"operation\":\"getLatestUpdates\"," +
                "\"extensionId\":\"extlib14\"," +
                "\"sourceId\":\"" + ENGLISH_SOURCE_ID + "\"," +
                "\"argument\":\"1\"}"
        );
        assertSuccess(latest);
        assertContains(latest, "\\\"mangas\\\":[{");

        String search = ExtensionHost.dispatch(
            "{\"operation\":\"searchManga\"," +
                "\"extensionId\":\"extlib14\"," +
                "\"sourceId\":\"" + ENGLISH_SOURCE_ID + "\"," +
                "\"query\":\"One Piece\"," +
                "\"filterStates\":\"" + sortFilterId +
                "\\tsort\\t0\\ttrue\"," +
                "\"argument\":\"1\"}"
        );
        assertSuccess(search);
        assertContains(search, "\\\"mangas\\\":[{");
        assertContains(search, "\\\"title\\\":");

        String latestResult = MiniJson.parseObject(latest).get("result");
        String mangaURL = firstStringField(latestResult, "url");
        String mangaTitle = firstStringField(latestResult, "title");
        String coverURL = firstStringField(latestResult, "thumbnailURL");
        String mangaWebURL = ExtensionHost.dispatch(
            "{\"operation\":\"getMangaUrl\"," +
                "\"extensionId\":\"extlib14\"," +
                "\"sourceId\":\"" + ENGLISH_SOURCE_ID + "\"," +
                "\"mangaURL\":\"" +
                MiniJson.escapeValue(mangaURL) +
                "\",\"mangaTitle\":\"" +
                MiniJson.escapeValue(mangaTitle) + "\"}"
        );
        assertSuccess(mangaWebURL);
        assertContains(mangaWebURL, "https://mangadex.org/title/");
        assertSuccess(ExtensionHost.dispatch(
            "{\"operation\":\"getImageRequest\"," +
                "\"extensionId\":\"extlib14\"," +
                "\"sourceId\":\"" + ENGLISH_SOURCE_ID + "\"," +
                "\"imageURL\":\"" +
                MiniJson.escapeValue(coverURL) + "\"}"
        ));
        String update = ExtensionHost.dispatch(
            "{\"operation\":\"getMangaUpdate\"," +
                "\"extensionId\":\"extlib14\"," +
                "\"sourceId\":\"" + ENGLISH_SOURCE_ID + "\"," +
                "\"mangaURL\":\"" +
                MiniJson.escapeValue(mangaURL) +
                "\",\"mangaTitle\":\"" +
                MiniJson.escapeValue(mangaTitle) +
                "\"}"
        );
        assertSuccess(update);
        assertContains(update, "\\\"manga\\\":{");
        assertContains(update, "\\\"chapters\\\":[{");

        String updateResult = MiniJson.parseObject(update).get("result");
        String chaptersJson = updateResult.substring(
            updateResult.indexOf("\"chapters\":")
        );
        List<String> chapterURLs = stringFields(chaptersJson, "url");
        String pages = null;
        for (
            int index = 0;
            index < chapterURLs.size() && index < 20;
            index++
        ) {
            String candidate = ExtensionHost.dispatch(
                "{\"operation\":\"getPageList\"," +
                    "\"extensionId\":\"extlib14\"," +
                    "\"sourceId\":\"" + ENGLISH_SOURCE_ID + "\"," +
                    "\"chapterURL\":\"" +
                    MiniJson.escapeValue(chapterURLs.get(index)) +
                    "\",\"chapterName\":\"\"}"
            );
            if (candidate.contains("\"success\":true")) {
                pages = candidate;
                break;
            }
        }
        if (pages == null) {
            throw new AssertionError(
                "No readable chapter found in the first 20 chapters"
            );
        }
        assertContains(pages, "\\\"index\\\":");
        assertContains(pages, "\\\"imageURL\\\":");
        String pagesResult = MiniJson.parseObject(pages).get("result");
        String imageURL = firstStringField(pagesResult, "imageURL");
        String pageURL = firstStringField(pagesResult, "url");
        String imageRequest = ExtensionHost.dispatch(
            "{\"operation\":\"getImageRequest\"," +
                "\"extensionId\":\"extlib14\"," +
                "\"sourceId\":\"" + ENGLISH_SOURCE_ID + "\"," +
                "\"imageURL\":\"" +
                MiniJson.escapeValue(imageURL) + "\"," +
                "\"pageURL\":\"" +
                MiniJson.escapeValue(pageURL) + "\"}"
        );
        assertSuccess(imageRequest);
        assertContains(imageRequest, "\\\"headers\\\":{");
        System.out.println(
            "TachiyomiX extension-lib 1.4 runtime test passed"
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

    private static void assertNotContains(String actual, String unexpected) {
        if (actual.contains(unexpected)) {
            throw new AssertionError(
                "Expected response not to contain " +
                    unexpected +
                    ", got " +
                    actual
            );
        }
    }

    private static void assertStringScalar(String response, String field) {
        assertContains(response, "\\\"" + field + "\\\":\\\"");
    }

    private static String firstStringField(String json, String field) {
        List<String> values = stringFields(json, field);
        if (values.isEmpty()) {
            throw new AssertionError(
                "Unable to find " + field + " in " + json
            );
        }
        return values.get(0);
    }

    private static String fieldForType(
        String json,
        String type,
        String field
    ) {
        String marker = "\"type\":\"" + type + "\"";
        int markerIndex = json.indexOf(marker);
        if (markerIndex < 0) {
            throw new AssertionError("No " + type + " filter in " + json);
        }
        int objectStart = json.lastIndexOf('{', markerIndex);
        return firstStringField(json.substring(objectStart), field);
    }

    private static List<String> stringFields(String json, String field) {
        String prefix = "\"" + field + "\":\"";
        List<String> values = new ArrayList<>();
        int searchStart = 0;
        while (true) {
            int start = json.indexOf(prefix, searchStart);
            if (start < 0) {
                return values;
            }
            start += prefix.length();
            StringBuilder value = new StringBuilder();
            boolean escaped = false;
            for (int index = start; index < json.length(); index++) {
                char character = json.charAt(index);
                if (escaped) {
                    value.append(character);
                    escaped = false;
                } else if (character == '\\') {
                    escaped = true;
                } else if (character == '"') {
                    values.add(value.toString());
                    searchStart = index + 1;
                    break;
                } else {
                    value.append(character);
                }
                if (index == json.length() - 1) {
                    throw new AssertionError(
                        "Unterminated " + field + " in " + json
                    );
                }
            }
        }
    }
}
