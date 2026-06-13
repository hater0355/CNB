package chatshared;

import java.util.LinkedHashMap;
import java.util.Map;

public final class JsonUtil {
    private JsonUtil() {
    }

    public static String stringify(Map<String, ?> values) {
        StringBuilder out = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            out.append('"').append(escape(entry.getKey())).append("\":");
            Object value = entry.getValue();
            if (value == null) {
                out.append("null");
            } else if (value instanceof Number || value instanceof Boolean) {
                out.append(value);
            } else {
                out.append('"').append(escape(String.valueOf(value))).append('"');
            }
        }
        out.append('}');
        return out.toString();
    }

    public static Map<String, String> parseObject(String json) {
        Map<String, String> values = new LinkedHashMap<>();
        if (json == null) {
            return values;
        }
        String text = json.trim();
        if (text.startsWith("{")) {
            text = text.substring(1);
        }
        if (text.endsWith("}")) {
            text = text.substring(0, text.length() - 1);
        }
        int index = 0;
        while (index < text.length()) {
            index = skipSpaceAndComma(text, index);
            if (index >= text.length() || text.charAt(index) != '"') {
                break;
            }
            ParseResult key = readQuoted(text, index);
            index = skipSpace(text, key.next);
            if (index >= text.length() || text.charAt(index) != ':') {
                break;
            }
            index = skipSpace(text, index + 1);
            ParseResult value;
            if (index < text.length() && text.charAt(index) == '"') {
                value = readQuoted(text, index);
            } else {
                int start = index;
                while (index < text.length() && text.charAt(index) != ',') {
                    index++;
                }
                value = new ParseResult(text.substring(start, index).trim(), index);
            }
            values.put(key.value, "null".equals(value.value) ? "" : value.value);
            index = value.next;
        }
        return values;
    }

    public static Map<String, Object> event(String type, long conversationId, String username) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("type", type);
        values.put("conversationId", conversationId);
        values.put("username", username == null ? "" : username);
        values.put("at", System.currentTimeMillis());
        return values;
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static int skipSpaceAndComma(String text, int index) {
        while (index < text.length() && (Character.isWhitespace(text.charAt(index)) || text.charAt(index) == ',')) {
            index++;
        }
        return index;
    }

    private static int skipSpace(String text, int index) {
        while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
            index++;
        }
        return index;
    }

    private static ParseResult readQuoted(String text, int index) {
        StringBuilder out = new StringBuilder();
        index++;
        while (index < text.length()) {
            char ch = text.charAt(index++);
            if (ch == '"') {
                break;
            }
            if (ch == '\\' && index < text.length()) {
                char next = text.charAt(index++);
                out.append(switch (next) {
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    default -> next;
                });
            } else {
                out.append(ch);
            }
        }
        return new ParseResult(out.toString(), index);
    }

    private record ParseResult(String value, int next) {
    }
}
