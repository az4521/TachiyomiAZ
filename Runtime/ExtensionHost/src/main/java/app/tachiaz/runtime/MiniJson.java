package app.tachiaz.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class MiniJson {
    private MiniJson() {
    }

    static Map<String, String> parseObject(String json) {
        Map<String, String> result = new LinkedHashMap<>();
        if (json == null) {
            return result;
        }

        int index = skipWhitespace(json, 0);
        if (index >= json.length() || json.charAt(index) != '{') {
            throw new IllegalArgumentException("Expected a JSON object");
        }
        index++;

        while (true) {
            index = skipWhitespace(json, index);
            if (index >= json.length()) {
                throw new IllegalArgumentException("Unterminated JSON object");
            }
            if (json.charAt(index) == '}') {
                return result;
            }

            ParsedString key = parseString(json, index);
            index = skipWhitespace(json, key.nextIndex);
            if (index >= json.length() || json.charAt(index) != ':') {
                throw new IllegalArgumentException("Expected ':' after JSON key");
            }
            index = skipWhitespace(json, index + 1);

            String value;
            if (json.startsWith("null", index)) {
                value = null;
                index += 4;
            } else if (json.charAt(index) != '"') {
                int start = index;
                while (
                    index < json.length() &&
                    json.charAt(index) != ',' &&
                    json.charAt(index) != '}'
                ) {
                    index++;
                }
                value = json.substring(start, index).trim();
                if (value.isEmpty()) {
                    throw new IllegalArgumentException(
                        "Expected a JSON value"
                    );
                }
            } else {
                ParsedString parsedValue = parseString(json, index);
                value = parsedValue.value;
                index = parsedValue.nextIndex;
            }
            result.put(key.value, value);

            index = skipWhitespace(json, index);
            if (index >= json.length()) {
                throw new IllegalArgumentException("Unterminated JSON object");
            }
            char separator = json.charAt(index++);
            if (separator == '}') {
                return result;
            }
            if (separator != ',') {
                throw new IllegalArgumentException(
                    "Expected ',' between JSON fields"
                );
            }
        }
    }

    /** Parses the request's JSON array of flat chapter objects without taking a JSON dependency. */
    static List<Map<String, String>> parseObjectArray(String json) {
        List<Map<String, String>> result = new ArrayList<>();
        if (json == null || json.trim().isEmpty()) {
            return result;
        }

        int index = skipWhitespace(json, 0);
        if (index >= json.length() || json.charAt(index) != '[') {
            throw new IllegalArgumentException("Expected a JSON array");
        }
        index++;

        while (true) {
            index = skipWhitespace(json, index);
            if (index >= json.length()) {
                throw new IllegalArgumentException("Unterminated JSON array");
            }
            if (json.charAt(index) == ']') {
                return result;
            }
            if (json.charAt(index) != '{') {
                throw new IllegalArgumentException("Expected a JSON object in array");
            }

            int start = index;
            int depth = 0;
            boolean quoted = false;
            boolean escaped = false;
            while (index < json.length()) {
                char character = json.charAt(index++);
                if (quoted) {
                    if (escaped) {
                        escaped = false;
                    } else if (character == '\\') {
                        escaped = true;
                    } else if (character == '"') {
                        quoted = false;
                    }
                    continue;
                }
                if (character == '"') {
                    quoted = true;
                } else if (character == '{') {
                    depth++;
                } else if (character == '}' && --depth == 0) {
                    break;
                }
            }
            if (depth != 0 || quoted) {
                throw new IllegalArgumentException("Unterminated JSON object in array");
            }
            result.add(parseObject(json.substring(start, index)));

            index = skipWhitespace(json, index);
            if (index >= json.length()) {
                throw new IllegalArgumentException("Unterminated JSON array");
            }
            char separator = json.charAt(index++);
            if (separator == ']') {
                return result;
            }
            if (separator != ',') {
                throw new IllegalArgumentException("Expected ',' between JSON array items");
            }
        }
    }

    static String response(
        boolean success,
        String result,
        String error,
        Map<String, String> metadata
    ) {
        StringBuilder output = new StringBuilder("{");
        append(output, "success", success ? "true" : "false", false);
        append(output, "result", result, true);
        append(output, "error", error, true);
        if (metadata != null) {
            for (Map.Entry<String, String> entry : metadata.entrySet()) {
                append(output, entry.getKey(), entry.getValue(), true);
            }
        }
        return output.append('}').toString();
    }

    private static void append(
        StringBuilder output,
        String key,
        String value,
        boolean quoteValue
    ) {
        if (output.length() > 1) {
            output.append(',');
        }
        output.append('"').append(escape(key)).append('"').append(':');
        if (value == null) {
            output.append("null");
        } else if (quoteValue) {
            output.append('"').append(escape(value)).append('"');
        } else {
            output.append(value);
        }
    }

    private static int skipWhitespace(String value, int index) {
        while (
            index < value.length() &&
            Character.isWhitespace(value.charAt(index))
        ) {
            index++;
        }
        return index;
    }

    private static ParsedString parseString(String json, int index) {
        if (index >= json.length() || json.charAt(index) != '"') {
            throw new IllegalArgumentException("Expected a JSON string");
        }
        StringBuilder result = new StringBuilder();
        index++;
        while (index < json.length()) {
            char character = json.charAt(index++);
            if (character == '"') {
                return new ParsedString(result.toString(), index);
            }
            if (character != '\\') {
                result.append(character);
                continue;
            }
            if (index >= json.length()) {
                throw new IllegalArgumentException("Incomplete JSON escape");
            }
            char escaped = json.charAt(index++);
            switch (escaped) {
                case '"':
                case '\\':
                case '/':
                    result.append(escaped);
                    break;
                case 'b':
                    result.append('\b');
                    break;
                case 'f':
                    result.append('\f');
                    break;
                case 'n':
                    result.append('\n');
                    break;
                case 'r':
                    result.append('\r');
                    break;
                case 't':
                    result.append('\t');
                    break;
                case 'u':
                    if (index + 4 > json.length()) {
                        throw new IllegalArgumentException(
                            "Incomplete Unicode escape"
                        );
                    }
                    result.append((char) Integer.parseInt(
                        json.substring(index, index + 4),
                        16
                    ));
                    index += 4;
                    break;
                default:
                    throw new IllegalArgumentException("Unknown JSON escape");
            }
        }
        throw new IllegalArgumentException("Unterminated JSON string");
    }

    private static String escape(String value) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"':
                    result.append("\\\"");
                    break;
                case '\\':
                    result.append("\\\\");
                    break;
                case '\n':
                    result.append("\\n");
                    break;
                case '\r':
                    result.append("\\r");
                    break;
                case '\t':
                    result.append("\\t");
                    break;
                default:
                    if (character < 0x20) {
                        result.append(String.format(
                            "\\u%04x",
                            (int) character
                        ));
                    } else {
                        result.append(character);
                    }
            }
        }
        return result.toString();
    }

    static String escapeValue(String value) {
        return escape(value);
    }

    private static final class ParsedString {
        final String value;
        final int nextIndex;

        ParsedString(String value, int nextIndex) {
            this.value = value;
            this.nextIndex = nextIndex;
        }
    }
}
