package pl.stillista.plugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tiny, dependency-free JSON parser + writer.
 *
 * <p>This deliberately avoids any third-party library (org.json, Gson, ...) so
 * the plugin has ZERO runtime dependencies to shade and loads cleanly on every
 * Bukkit version from 1.8 to 26.x. It implements just enough of RFC 8259 to
 * read the StilLista API responses and to build the small claim request body.</p>
 *
 * <p>Parsed values map to: {@link Map}&lt;String,Object&gt; (objects),
 * {@link List}&lt;Object&gt; (arrays), {@link String}, {@link Double}/{@link Long}
 * (numbers), {@link Boolean}, or {@code null}.</p>
 */
public final class Json {

    private Json() {
    }

    // ----- Parsing ----------------------------------------------------------

    public static Object parse(String input) {
        if (input == null) {
            return null;
        }
        Parser p = new Parser(input);
        p.skipWhitespace();
        Object value = p.readValue();
        p.skipWhitespace();
        if (!p.atEnd()) {
            throw new IllegalArgumentException("Trailing characters after JSON value");
        }
        return value;
    }

    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) {
            this.s = s;
            this.i = 0;
        }

        boolean atEnd() {
            return i >= s.length();
        }

        void skipWhitespace() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    i++;
                } else {
                    break;
                }
            }
        }

        Object readValue() {
            skipWhitespace();
            if (atEnd()) {
                throw new IllegalArgumentException("Unexpected end of JSON");
            }
            char c = s.charAt(i);
            switch (c) {
                case '{':
                    return readObject();
                case '[':
                    return readArray();
                case '"':
                    return readString();
                case 't':
                case 'f':
                    return readBoolean();
                case 'n':
                    return readNull();
                default:
                    return readNumber();
            }
        }

        Map<String, Object> readObject() {
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            i++; // consume '{'
            skipWhitespace();
            if (!atEnd() && s.charAt(i) == '}') {
                i++;
                return map;
            }
            while (true) {
                skipWhitespace();
                if (atEnd() || s.charAt(i) != '"') {
                    throw new IllegalArgumentException("Expected object key at " + i);
                }
                String key = readString();
                skipWhitespace();
                if (atEnd() || s.charAt(i) != ':') {
                    throw new IllegalArgumentException("Expected ':' at " + i);
                }
                i++; // consume ':'
                Object value = readValue();
                map.put(key, value);
                skipWhitespace();
                if (atEnd()) {
                    throw new IllegalArgumentException("Unterminated object");
                }
                char c = s.charAt(i++);
                if (c == ',') {
                    continue;
                }
                if (c == '}') {
                    break;
                }
                throw new IllegalArgumentException("Expected ',' or '}' at " + (i - 1));
            }
            return map;
        }

        List<Object> readArray() {
            List<Object> list = new ArrayList<Object>();
            i++; // consume '['
            skipWhitespace();
            if (!atEnd() && s.charAt(i) == ']') {
                i++;
                return list;
            }
            while (true) {
                Object value = readValue();
                list.add(value);
                skipWhitespace();
                if (atEnd()) {
                    throw new IllegalArgumentException("Unterminated array");
                }
                char c = s.charAt(i++);
                if (c == ',') {
                    continue;
                }
                if (c == ']') {
                    break;
                }
                throw new IllegalArgumentException("Expected ',' or ']' at " + (i - 1));
            }
            return list;
        }

        String readString() {
            StringBuilder sb = new StringBuilder();
            i++; // consume opening quote
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (i >= s.length()) {
                        break;
                    }
                    char esc = s.charAt(i++);
                    switch (esc) {
                        case '"':
                            sb.append('"');
                            break;
                        case '\\':
                            sb.append('\\');
                            break;
                        case '/':
                            sb.append('/');
                            break;
                        case 'b':
                            sb.append('\b');
                            break;
                        case 'f':
                            sb.append('\f');
                            break;
                        case 'n':
                            sb.append('\n');
                            break;
                        case 'r':
                            sb.append('\r');
                            break;
                        case 't':
                            sb.append('\t');
                            break;
                        case 'u':
                            if (i + 4 > s.length()) {
                                throw new IllegalArgumentException("Bad unicode escape");
                            }
                            String hex = s.substring(i, i + 4);
                            i += 4;
                            sb.append((char) Integer.parseInt(hex, 16));
                            break;
                        default:
                            throw new IllegalArgumentException("Bad escape: \\" + esc);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new IllegalArgumentException("Unterminated string");
        }

        Boolean readBoolean() {
            if (s.startsWith("true", i)) {
                i += 4;
                return Boolean.TRUE;
            }
            if (s.startsWith("false", i)) {
                i += 5;
                return Boolean.FALSE;
            }
            throw new IllegalArgumentException("Invalid literal at " + i);
        }

        Object readNull() {
            if (s.startsWith("null", i)) {
                i += 4;
                return null;
            }
            throw new IllegalArgumentException("Invalid literal at " + i);
        }

        Object readNumber() {
            int start = i;
            while (i < s.length()) {
                char c = s.charAt(i);
                if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.'
                        || c == 'e' || c == 'E') {
                    i++;
                } else {
                    break;
                }
            }
            String num = s.substring(start, i);
            if (num.isEmpty()) {
                throw new IllegalArgumentException("Invalid number at " + start);
            }
            if (num.indexOf('.') >= 0 || num.indexOf('e') >= 0 || num.indexOf('E') >= 0) {
                return Double.valueOf(num);
            }
            try {
                return Long.valueOf(num);
            } catch (NumberFormatException ex) {
                return Double.valueOf(num);
            }
        }
    }

    // ----- Writing ----------------------------------------------------------

    /** Build the claim request body: {"key":"...","ids":["a","b",...]}. */
    public static String claimBody(String key, List<String> ids) {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        sb.append("\"key\":").append(quote(key)).append(',');
        sb.append("\"ids\":[");
        for (int n = 0; n < ids.size(); n++) {
            if (n > 0) {
                sb.append(',');
            }
            sb.append(quote(ids.get(n)));
        }
        sb.append("]}");
        return sb.toString();
    }

    public static String quote(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(value.length() + 2);
        sb.append('"');
        for (int n = 0; n < value.length(); n++) {
            char c = value.charAt(n);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
