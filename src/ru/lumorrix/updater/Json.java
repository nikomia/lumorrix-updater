package ru.lumorrix.updater;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Минимальный JSON — только чтобы дописать профиль в launcher_profiles.json, не теряя чужих полей.
 * Объекты → LinkedHashMap (порядок сохраняется), массивы → ArrayList, числа хранятся исходным текстом.
 */
final class Json {
    /** Число как есть, чтобы при перезаписи не превратить 1 в 1.0. */
    record Num(String raw) {
    }

    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    static Object parse(String text) throws IOException {
        Json p = new Json(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i != text.length()) {
            throw p.error("лишние символы");
        }
        return v;
    }

    static String write(Object v) {
        StringBuilder sb = new StringBuilder();
        write(sb, v, "");
        return sb.append('\n').toString();
    }

    private Object value() throws IOException {
        if (i >= s.length()) {
            throw error("неожиданный конец");
        }
        char c = s.charAt(i);
        switch (c) {
            case '{' -> {
                i++;
                Map<String, Object> m = new LinkedHashMap<>();
                ws();
                if (peek('}')) {
                    return m;
                }
                do {
                    ws();
                    String k = string();
                    ws();
                    expect(':');
                    ws();
                    m.put(k, value());
                    ws();
                } while (peek(','));
                expect('}');
                return m;
            }
            case '[' -> {
                i++;
                List<Object> l = new ArrayList<>();
                ws();
                if (peek(']')) {
                    return l;
                }
                do {
                    ws();
                    l.add(value());
                    ws();
                } while (peek(','));
                expect(']');
                return l;
            }
            case '"' -> {
                return string();
            }
            default -> {
                if (s.startsWith("true", i)) {
                    i += 4;
                    return Boolean.TRUE;
                }
                if (s.startsWith("false", i)) {
                    i += 5;
                    return Boolean.FALSE;
                }
                if (s.startsWith("null", i)) {
                    i += 4;
                    return null;
                }
                int start = i;
                while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
                    i++;
                }
                if (start == i) {
                    throw error("неожиданный символ '" + c + "'");
                }
                return new Num(s.substring(start, i));
            }
        }
    }

    private String string() throws IOException {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (i < s.length()) {
            char c = s.charAt(i++);
            if (c == '"') {
                return sb.toString();
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (i >= s.length()) {
                break;
            }
            char e = s.charAt(i++);
            switch (e) {
                case 'n' -> sb.append('\n');
                case 't' -> sb.append('\t');
                case 'r' -> sb.append('\r');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'u' -> {
                    sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    i += 4;
                }
                default -> sb.append(e);
            }
        }
        throw error("незакрытая строка");
    }

    private void ws() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        // BOM в начале файла
        if (i == 0 && s.startsWith("﻿")) {
            i++;
            ws();
        }
    }

    private boolean peek(char c) {
        if (i < s.length() && s.charAt(i) == c) {
            i++;
            return true;
        }
        return false;
    }

    private void expect(char c) throws IOException {
        if (!peek(c)) {
            throw error("ожидался '" + c + "'");
        }
    }

    private IOException error(String what) {
        return new IOException("JSON: " + what + " (позиция " + i + ")");
    }

    @SuppressWarnings("unchecked")
    private static void write(StringBuilder sb, Object v, String indent) {
        if (v instanceof Map<?, ?> m) {
            if (m.isEmpty()) {
                sb.append("{}");
                return;
            }
            sb.append("{\n");
            String in = indent + "  ";
            int n = 0;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) m).entrySet()) {
                sb.append(in);
                str(sb, e.getKey());
                sb.append(" : ");
                write(sb, e.getValue(), in);
                sb.append(++n < m.size() ? ",\n" : "\n");
            }
            sb.append(indent).append('}');
        } else if (v instanceof List<?> l) {
            if (l.isEmpty()) {
                sb.append("[]");
                return;
            }
            sb.append("[\n");
            String in = indent + "  ";
            for (int k = 0; k < l.size(); k++) {
                sb.append(in);
                write(sb, l.get(k), in);
                sb.append(k + 1 < l.size() ? ",\n" : "\n");
            }
            sb.append(indent).append(']');
        } else if (v instanceof String str) {
            str(sb, str);
        } else if (v instanceof Num num) {
            sb.append(num.raw());
        } else {
            sb.append(v);
        }
    }

    private static void str(StringBuilder sb, String v) {
        sb.append('"');
        for (char c : v.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
