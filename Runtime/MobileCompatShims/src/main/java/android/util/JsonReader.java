package android.util;

import java.io.Closeable;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Small streaming JSON reader compatible with the Android API used by
 * extensions. It deliberately has no dependency on Android's stub parser.
 */
public final class JsonReader implements Closeable {
    private static final int EMPTY_DOCUMENT = 0;
    private static final int NONEMPTY_DOCUMENT = 1;
    private static final int EMPTY_ARRAY = 2;
    private static final int NONEMPTY_ARRAY = 3;
    private static final int EMPTY_OBJECT = 4;
    private static final int DANGLING_NAME = 5;
    private static final int NONEMPTY_OBJECT = 6;
    private static final int CLOSED = 7;

    private final Reader input;
    private final Deque<Integer> scopes = new ArrayDeque<Integer>();
    private int buffered = Integer.MIN_VALUE;
    private JsonToken token;
    private String tokenValue;
    private boolean lenient;

    public JsonReader(Reader input) {
        if (input == null) throw new NullPointerException("input");
        this.input = input;
        scopes.push(EMPTY_DOCUMENT);
    }

    public void setLenient(boolean lenient) { this.lenient = lenient; }
    public boolean isLenient() { return lenient; }

    public JsonToken peek() throws IOException {
        if (token != null) return token;
        int scope = scopes.peek();
        switch (scope) {
            case CLOSED:
                throw new IllegalStateException("JsonReader is closed");
            case EMPTY_DOCUMENT:
                replaceTop(NONEMPTY_DOCUMENT);
                return token = readValue();
            case NONEMPTY_DOCUMENT:
                skipWhitespace();
                if (peekChar() == -1) return token = JsonToken.END_DOCUMENT;
                if (!lenient) throw syntaxError("Expected end of document");
                return token = readValue();
            case EMPTY_ARRAY:
                skipWhitespace();
                if (peekChar() == ']') {
                    readChar();
                    scopes.pop();
                    return token = JsonToken.END_ARRAY;
                }
                replaceTop(NONEMPTY_ARRAY);
                return token = readValue();
            case NONEMPTY_ARRAY:
                skipWhitespace();
                int arraySeparator = readChar();
                if (arraySeparator == ']') {
                    scopes.pop();
                    return token = JsonToken.END_ARRAY;
                }
                if (arraySeparator != ',') throw syntaxError("Expected ',' or ']'");
                return token = readValue();
            case EMPTY_OBJECT:
                skipWhitespace();
                if (peekChar() == '}') {
                    readChar();
                    scopes.pop();
                    return token = JsonToken.END_OBJECT;
                }
                return readName();
            case NONEMPTY_OBJECT:
                skipWhitespace();
                int objectSeparator = readChar();
                if (objectSeparator == '}') {
                    scopes.pop();
                    return token = JsonToken.END_OBJECT;
                }
                if (objectSeparator != ',') throw syntaxError("Expected ',' or '}'");
                return readName();
            case DANGLING_NAME:
                skipWhitespace();
                if (readChar() != ':') throw syntaxError("Expected ':'");
                replaceTop(NONEMPTY_OBJECT);
                return token = readValue();
            default:
                throw new AssertionError();
        }
    }

    public void beginArray() throws IOException { expect(JsonToken.BEGIN_ARRAY); }
    public void endArray() throws IOException { expect(JsonToken.END_ARRAY); }
    public void beginObject() throws IOException { expect(JsonToken.BEGIN_OBJECT); }
    public void endObject() throws IOException { expect(JsonToken.END_OBJECT); }
    public boolean hasNext() throws IOException {
        JsonToken next = peek();
        return next != JsonToken.END_OBJECT && next != JsonToken.END_ARRAY &&
            next != JsonToken.END_DOCUMENT;
    }

    public String nextName() throws IOException {
        expectToken(JsonToken.NAME);
        String result = tokenValue;
        clearToken();
        return result;
    }

    public String nextString() throws IOException {
        JsonToken next = peek();
        if (next != JsonToken.STRING && next != JsonToken.NUMBER) {
            throw typeError("a string", next);
        }
        String result = tokenValue;
        clearToken();
        return result;
    }

    public boolean nextBoolean() throws IOException {
        expectToken(JsonToken.BOOLEAN);
        boolean result = Boolean.parseBoolean(tokenValue);
        clearToken();
        return result;
    }

    public void nextNull() throws IOException {
        expect(JsonToken.NULL);
    }

    public double nextDouble() throws IOException {
        String value = nextString();
        try {
            double result = Double.parseDouble(value);
            if (!lenient && (Double.isNaN(result) || Double.isInfinite(result))) {
                throw new NumberFormatException("JSON forbids NaN and infinities");
            }
            return result;
        } catch (NumberFormatException error) {
            throw typeError("a double", value);
        }
    }

    public long nextLong() throws IOException {
        String value = nextString();
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            double parsed = Double.parseDouble(value);
            long result = (long) parsed;
            if (result != parsed) throw typeError("a long", value);
            return result;
        }
    }

    public int nextInt() throws IOException {
        long result = nextLong();
        if (result < Integer.MIN_VALUE || result > Integer.MAX_VALUE) {
            throw typeError("an int", String.valueOf(result));
        }
        return (int) result;
    }

    public void skipValue() throws IOException {
        JsonToken next = peek();
        if (next == JsonToken.BEGIN_ARRAY) {
            beginArray();
            while (hasNext()) skipValue();
            endArray();
        } else if (next == JsonToken.BEGIN_OBJECT) {
            beginObject();
            while (hasNext()) {
                nextName();
                skipValue();
            }
            endObject();
        } else if (next == JsonToken.NAME) {
            nextName();
            skipValue();
        } else {
            clearToken();
        }
    }

    @Override
    public void close() throws IOException {
        token = null;
        scopes.clear();
        scopes.push(CLOSED);
        input.close();
    }

    private JsonToken readName() throws IOException {
        skipWhitespace();
        int quote = readChar();
        if (quote != '"' && quote != '\'') {
            if (!lenient) throw syntaxError("Expected a quoted name");
            tokenValue = readUnquoted(quote);
        } else {
            tokenValue = readQuoted(quote);
        }
        replaceTop(DANGLING_NAME);
        return token = JsonToken.NAME;
    }

    private JsonToken readValue() throws IOException {
        skipWhitespace();
        int first = readChar();
        switch (first) {
            case '{':
                scopes.push(EMPTY_OBJECT);
                return JsonToken.BEGIN_OBJECT;
            case '[':
                scopes.push(EMPTY_ARRAY);
                return JsonToken.BEGIN_ARRAY;
            case '"':
            case '\'':
                if (first == '\'' && !lenient) throw syntaxError("Single-quoted string");
                tokenValue = readQuoted(first);
                return JsonToken.STRING;
            case -1:
                throw syntaxError("Unexpected end of input");
            default:
                tokenValue = readUnquoted(first);
                if ("null".equals(tokenValue)) return JsonToken.NULL;
                if ("true".equalsIgnoreCase(tokenValue) || "false".equalsIgnoreCase(tokenValue)) {
                    return JsonToken.BOOLEAN;
                }
                if (isNumber(tokenValue)) return JsonToken.NUMBER;
                if (lenient) return JsonToken.STRING;
                throw syntaxError("Invalid JSON value '" + tokenValue + "'");
        }
    }

    private String readQuoted(int quote) throws IOException {
        StringBuilder result = new StringBuilder();
        while (true) {
            int value = readChar();
            if (value == -1) throw syntaxError("Unterminated string");
            if (value == quote) return result.toString();
            if (value == '\\') {
                int escaped = readChar();
                switch (escaped) {
                    case '"': case '\'': case '\\': case '/': result.append((char) escaped); break;
                    case 'b': result.append('\b'); break;
                    case 'f': result.append('\f'); break;
                    case 'n': result.append('\n'); break;
                    case 'r': result.append('\r'); break;
                    case 't': result.append('\t'); break;
                    case 'u':
                        int codePoint = 0;
                        for (int index = 0; index < 4; index++) {
                            int digit = Character.digit(readChar(), 16);
                            if (digit < 0) throw syntaxError("Invalid unicode escape");
                            codePoint = (codePoint << 4) | digit;
                        }
                        result.append((char) codePoint);
                        break;
                    default:
                        if (!lenient) throw syntaxError("Invalid escape");
                        result.append((char) escaped);
                }
            } else {
                if (value < 0x20 && !lenient) throw syntaxError("Unescaped control character");
                result.append((char) value);
            }
        }
    }

    private String readUnquoted(int first) throws IOException {
        StringBuilder result = new StringBuilder();
        result.append((char) first);
        while (true) {
            int value = peekChar();
            if (value == -1 || Character.isWhitespace(value) || value == ',' || value == ':' ||
                value == ']' || value == '}') {
                return result.toString();
            }
            result.append((char) readChar());
        }
    }

    private void skipWhitespace() throws IOException {
        while (true) {
            int value = peekChar();
            if (value == ' ' || value == '\t' || value == '\r' || value == '\n') {
                readChar();
                continue;
            }
            if (value == '/' && lenient) {
                readChar();
                int next = readChar();
                if (next == '/') {
                    while ((value = readChar()) != -1 && value != '\n' && value != '\r') {}
                    continue;
                }
                if (next == '*') {
                    int previous = 0;
                    while ((value = readChar()) != -1 && !(previous == '*' && value == '/')) {
                        previous = value;
                    }
                    if (value == -1) throw syntaxError("Unterminated comment");
                    continue;
                }
                throw syntaxError("Unexpected '/'");
            }
            return;
        }
    }

    private int peekChar() throws IOException {
        if (buffered == Integer.MIN_VALUE) buffered = input.read();
        return buffered;
    }

    private int readChar() throws IOException {
        int result = peekChar();
        buffered = Integer.MIN_VALUE;
        return result;
    }

    private void replaceTop(int scope) {
        scopes.pop();
        scopes.push(scope);
    }

    private void expect(JsonToken expected) throws IOException {
        expectToken(expected);
        clearToken();
    }

    private void expectToken(JsonToken expected) throws IOException {
        JsonToken actual = peek();
        if (actual != expected) throw typeError(expected.toString(), actual);
    }

    private void clearToken() {
        token = null;
        tokenValue = null;
    }

    private static boolean isNumber(String value) {
        return value.matches("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?");
    }

    private IOException syntaxError(String message) {
        return new IOException(message);
    }

    private IllegalStateException typeError(String expected, Object actual) {
        return new IllegalStateException("Expected " + expected + " but was " + actual);
    }
}
