package com.autosentry.app.obd;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds the systems scan's two reports at once: the text one people read
 * and a JSON copy with the same sections for tools. Each section is a JSON
 * object of its labeled lines plus an "items" list for its rows.
 */
final class ScanReport {
    private final StringBuilder head = new StringBuilder();
    private final StringBuilder summary = new StringBuilder();
    private final StringBuilder body = new StringBuilder();
    private final Map<String, Object> root = new LinkedHashMap<>();
    private Map<String, Object> current;
    private StringBuilder currentText = body;

    /** A line at the very top of the text report. */
    void title(String line) {
        head.append(line).append('\n');
    }

    /** A top-level JSON value with no text of its own. */
    void put(String key, Object value) {
        root.put(key, value);
    }

    /** Starts the summary, which prints right under the title however late it's written. */
    void summarySection() {
        Map<String, Object> section = new LinkedHashMap<>();
        Map<String, Object> rest = new LinkedHashMap<>(root);
        root.clear();
        root.put("summary", section);
        root.putAll(rest);
        current = section;
        currentText = summary;
        summary.append('\n').append("SUMMARY").append('\n');
    }

    void section(String key, String title) {
        current = new LinkedHashMap<>();
        root.put(key, current);
        currentText = body;
        body.append('\n').append(title).append('\n');
    }

    void line(String label, Object value) {
        currentText.append("  ").append(label).append(": ").append(value).append('\n');
        current.put(label, value);
    }

    /** A row: {@code text} for people, {@code fields} for the JSON. */
    @SuppressWarnings("unchecked")
    void item(String text, Map<String, Object> fields) {
        currentText.append("  ").append(text).append('\n');
        ((List<Object>) current.computeIfAbsent("items", k -> new ArrayList<>())).add(fields);
    }

    /** A remark in the text, kept in the JSON under "notes". */
    @SuppressWarnings("unchecked")
    void note(String text) {
        currentText.append("  ").append(text).append('\n');
        ((List<Object>) current.computeIfAbsent("notes", k -> new ArrayList<>())).add(text);
    }

    /** A line of text only (the JSON already has it some other way). */
    void textOnly(String text) {
        currentText.append(text).append('\n');
    }

    String text() {
        return head.toString() + summary + body;
    }

    String json() {
        StringBuilder out = new StringBuilder();
        writeJson(out, root, "");
        return out.append('\n').toString();
    }

    static Map<String, Object> fields(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keysAndValues.length; i += 2) map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        return map;
    }

    // ---------------------------------------------------------------- JSON

    static void writeJson(StringBuilder out, Object value, String indent) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) value;
            if (map.isEmpty()) {
                out.append("{}");
                return;
            }
            out.append("{\n");
            int i = 0;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                out.append(indent).append("  ");
                quote(out, String.valueOf(e.getKey()));
                out.append(": ");
                writeJson(out, e.getValue(), indent + "  ");
                out.append(++i < map.size() ? ",\n" : "\n");
            }
            out.append(indent).append('}');
        } else if (value instanceof Collection) {
            Collection<?> list = (Collection<?>) value;
            if (list.isEmpty()) {
                out.append("[]");
                return;
            }
            out.append("[\n");
            int i = 0;
            for (Object item : list) {
                out.append(indent).append("  ");
                writeJson(out, item, indent + "  ");
                out.append(++i < list.size() ? ",\n" : "\n");
            }
            out.append(indent).append(']');
        } else if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            out.append(Double.isNaN(d) || Double.isInfinite(d) ? "null" : String.format(Locale.US, "%s", trim(d)));
        } else if (value instanceof Number || value instanceof Boolean) {
            out.append(value);
        } else {
            quote(out, value.toString());
        }
    }

    private static String trim(double d) {
        return d == Math.rint(d) && Math.abs(d) < 1e15 ? Long.toString((long) d) : Double.toString(d);
    }

    private static void quote(StringBuilder out, String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) out.append(String.format(Locale.US, "\\u%04x", (int) c));
                    else out.append(c);
            }
        }
        out.append('"');
    }
}
