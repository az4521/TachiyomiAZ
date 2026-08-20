package app.tachiaz.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Applies the chapter-name normalization normally performed by Mihon. */
final class ChapterNumberParser {
    private static final String NUMBER_PATTERN =
        "([0-9]+)(\\.[0-9]+)?(\\.?[a-z]+)?";
    private static final Pattern NUMBER = Pattern.compile(NUMBER_PATTERN);
    private static final Pattern BASIC = Pattern.compile(
        "(?<=ch\\.) *" + NUMBER_PATTERN
    );
    private static final Pattern UNWANTED = Pattern.compile(
        "\\b(?:v|ver|vol|version|volume|season|s)[^a-z]?[0-9]+"
    );
    private static final Pattern SPECIAL_WHITESPACE = Pattern.compile(
        "\\s(?=extra|special|omake)"
    );

    private ChapterNumberParser() {
    }

    static float parse(
        String mangaTitle,
        String chapterName,
        Number suppliedNumber
    ) {
        double supplied = suppliedNumber == null
            ? -1
            : suppliedNumber.doubleValue();
        if (supplied == -2 || supplied > -1) {
            return (float) supplied;
        }

        String title = mangaTitle == null
            ? ""
            : mangaTitle.toLowerCase(Locale.ROOT);
        String name = chapterName == null
            ? ""
            : chapterName.toLowerCase(Locale.ROOT);
        String cleaned = name
            .replace(title, "")
            .trim()
            .replace(',', '.')
            .replace('-', '.');
        cleaned = SPECIAL_WHITESPACE.matcher(cleaned).replaceAll("");

        List<Match> matches = matches(NUMBER, cleaned);
        if (matches.isEmpty()) {
            return (float) supplied;
        }
        if (matches.size() > 1) {
            String withoutTags = UNWANTED.matcher(cleaned).replaceAll("");
            Matcher chapter = BASIC.matcher(withoutTags);
            if (chapter.find()) {
                return value(chapter);
            }
            Matcher first = NUMBER.matcher(withoutTags);
            if (first.find()) {
                return value(first);
            }
        }
        return matches.get(0).value;
    }

    private static List<Match> matches(Pattern pattern, String value) {
        List<Match> result = new ArrayList<>();
        Matcher matcher = pattern.matcher(value);
        while (matcher.find()) {
            result.add(new Match(value(matcher)));
        }
        return result;
    }

    private static float value(Matcher matcher) {
        double number = Double.parseDouble(matcher.group(1));
        String decimal = matcher.group(2);
        if (decimal != null && !decimal.isEmpty()) {
            return (float) (number + Double.parseDouble(decimal));
        }
        String suffix = matcher.group(3);
        if (suffix == null || suffix.isEmpty()) {
            return (float) number;
        }
        suffix = suffix.replaceFirst("^\\.", "");
        if (suffix.contains("extra")) {
            return (float) (number + 0.99);
        }
        if (suffix.contains("omake")) {
            return (float) (number + 0.98);
        }
        if (suffix.contains("special")) {
            return (float) (number + 0.97);
        }
        if (suffix.length() == 1) {
            int alpha = suffix.charAt(0) - 'a' + 1;
            if (alpha > 0 && alpha < 10) {
                return (float) (number + alpha / 10.0);
            }
        }
        return (float) number;
    }

    private static final class Match {
        final float value;

        Match(float value) {
            this.value = value;
        }
    }
}
