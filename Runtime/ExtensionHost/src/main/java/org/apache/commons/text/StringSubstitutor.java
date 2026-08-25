package org.apache.commons.text;

import java.util.Map;

/**
 * Minimal binary-compatible subset used by the Komga extension.
 *
 * Komga 1.6 depends on Commons Text only to validate its chapter-name template in its preference
 * screen. Extension dependency JARs are not loaded by the iOS JVM host, so providing this small
 * host-side implementation keeps the preference screen usable without carrying the complete
 * Commons Text/Commons Lang dependency tree.
 */
public class StringSubstitutor {
    private final Map<String, ?> values;
    private final String prefix;
    private final String suffix;
    private boolean failOnUndefined;

    public StringSubstitutor(Map<String, ?> values, String prefix, String suffix) {
        this.values = values;
        this.prefix = prefix;
        this.suffix = suffix;
    }

    public void setEnableUndefinedVariableException(boolean enabled) {
        failOnUndefined = enabled;
    }

    public String replace(String source) {
        if (source == null || source.isEmpty() || prefix.isEmpty() || suffix.isEmpty()) {
            return source;
        }

        StringBuilder output = new StringBuilder(source.length());
        int cursor = 0;
        while (cursor < source.length()) {
            int start = source.indexOf(prefix, cursor);
            if (start < 0) {
                output.append(source, cursor, source.length());
                break;
            }

            // Commons Text's default escape character is '$': `${name}` is a literal `{name}`
            // when the caller chooses "{" as its variable prefix, as Komga documents.
            if (start > cursor && source.charAt(start - 1) == '$') {
                output.append(source, cursor, start - 1).append(prefix);
                cursor = start + prefix.length();
                continue;
            }

            output.append(source, cursor, start);
            int nameStart = start + prefix.length();
            int end = source.indexOf(suffix, nameStart);
            if (end < 0) {
                output.append(source, start, source.length());
                break;
            }

            String name = source.substring(nameStart, end);
            Object value = values == null ? null : values.get(name);
            if (value == null) {
                if (failOnUndefined) {
                    throw new IllegalArgumentException("Undefined variable: " + name);
                }
                output.append(source, start, end + suffix.length());
            } else {
                output.append(value);
            }
            cursor = end + suffix.length();
        }
        return output.toString();
    }
}
