package com.josexato.navaja.pantonera.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * JSON mínimo en Java puro (sin org.json, que en los tests de JVM no existe).
 * Objetos → LinkedHashMap (conserva el orden), arrays → ArrayList,
 * números → Double, y String/Boolean/null tal cual.
 */
public final class Json {

    private Json() { }

    /** Error de sintaxis con la posición (carácter) donde ocurrió. */
    public static final class ParseException extends Exception {
        public final int pos;

        ParseException(String msg, int pos) {
            super(msg + " (carácter " + pos + ")");
            this.pos = pos;
        }
    }

    // ------------------------------------------------------------- lectura

    public static Object parse(String s) throws ParseException {
        Parser p = new Parser(s);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i < s.length()) throw new ParseException("Texto sobrante después del JSON", p.i);
        return v;
    }

    private static final class Parser {
        final String s;
        int i;

        Parser(String s) { this.s = s; }

        void ws() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\uFEFF') i++;
                else break;
            }
        }

        Object value() throws ParseException {
            if (i >= s.length()) throw new ParseException("JSON incompleto", i);
            char c = s.charAt(i);
            switch (c) {
                case '{': return object();
                case '[': return array();
                case '"': return string();
                case 't': return literal("true", Boolean.TRUE);
                case 'f': return literal("false", Boolean.FALSE);
                case 'n': return literal("null", null);
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) return number();
                    throw new ParseException("Carácter inesperado '" + c + "'", i);
            }
        }

        Object literal(String word, Object v) throws ParseException {
            if (!s.startsWith(word, i)) throw new ParseException("Se esperaba " + word, i);
            i += word.length();
            return v;
        }

        Map<String, Object> object() throws ParseException {
            Map<String, Object> m = new LinkedHashMap<>();
            i++; // {
            ws();
            if (peek() == '}') { i++; return m; }
            while (true) {
                ws();
                if (peek() != '"') throw new ParseException("Se esperaba una clave entre comillas", i);
                String k = string();
                ws();
                if (peek() != ':') throw new ParseException("Se esperaba ':'", i);
                i++;
                ws();
                m.put(k, value());
                ws();
                char c = peek();
                i++;
                if (c == '}') return m;
                if (c != ',') throw new ParseException("Se esperaba ',' o '}'", i - 1);
            }
        }

        List<Object> array() throws ParseException {
            List<Object> a = new ArrayList<>();
            i++; // [
            ws();
            if (peek() == ']') { i++; return a; }
            while (true) {
                ws();
                a.add(value());
                ws();
                char c = peek();
                i++;
                if (c == ']') return a;
                if (c != ',') throw new ParseException("Se esperaba ',' o ']'", i - 1);
            }
        }

        String string() throws ParseException {
            StringBuilder sb = new StringBuilder();
            i++; // "
            while (true) {
                if (i >= s.length()) throw new ParseException("Texto sin cerrar", i);
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c != '\\') { sb.append(c); continue; }
                if (i >= s.length()) throw new ParseException("Escape incompleto", i);
                char e = s.charAt(i++);
                switch (e) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u':
                        if (i + 4 > s.length()) throw new ParseException("Escape \\u incompleto", i);
                        try {
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        } catch (NumberFormatException ex) {
                            throw new ParseException("Escape \\u inválido", i);
                        }
                        i += 4;
                        break;
                    default: throw new ParseException("Escape inválido \\" + e, i - 1);
                }
            }
        }

        Double number() throws ParseException {
            int start = i;
            if (peek() == '-') i++;
            while (i < s.length() && "0123456789.eE+-".indexOf(s.charAt(i)) >= 0) i++;
            try {
                return Double.parseDouble(s.substring(start, i));
            } catch (NumberFormatException e) {
                throw new ParseException("Número inválido", start);
            }
        }

        char peek() { return i < s.length() ? s.charAt(i) : '\0'; }
    }

    // ------------------------------------------------------------ escritura

    /** JSON con sangría de 2 espacios; los arrays de números van en una línea. */
    public static String write(Object v) {
        StringBuilder sb = new StringBuilder();
        write(sb, v, 0, true);
        return sb.append('\n').toString();
    }

    /** JSON compacto (sin espacios), p. ej. para incrustar en glTF. */
    public static String writeCompact(Object v) {
        StringBuilder sb = new StringBuilder();
        write(sb, v, 0, false);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(StringBuilder sb, Object v, int ind, boolean pretty) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String) {
            quote(sb, (String) v);
        } else if (v instanceof Boolean) {
            sb.append(v);
        } else if (v instanceof Number) {
            sb.append(num(((Number) v).doubleValue()));
        } else if (v instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) v;
            if (m.isEmpty()) { sb.append("{}"); return; }
            sb.append('{');
            int n = 0;
            for (Map.Entry<String, Object> e : m.entrySet()) {
                if (n++ > 0) sb.append(',');
                if (pretty) newline(sb, ind + 1);
                quote(sb, e.getKey());
                sb.append(pretty ? ": " : ":");
                write(sb, e.getValue(), ind + 1, pretty);
            }
            if (pretty) newline(sb, ind);
            sb.append('}');
        } else if (v instanceof List) {
            List<Object> a = (List<Object>) v;
            if (a.isEmpty()) { sb.append("[]"); return; }
            boolean flat = !pretty || allScalars(a);
            sb.append('[');
            for (int k = 0; k < a.size(); k++) {
                if (k > 0) sb.append(flat && pretty ? ", " : ",");
                if (!flat) newline(sb, ind + 1);
                write(sb, a.get(k), ind + 1, pretty);
            }
            if (!flat) newline(sb, ind);
            sb.append(']');
        } else {
            quote(sb, v.toString());
        }
    }

    private static boolean allScalars(List<Object> a) {
        for (Object o : a) if (o instanceof Map || o instanceof List) return false;
        return true;
    }

    private static void newline(StringBuilder sb, int ind) {
        sb.append('\n');
        for (int k = 0; k < ind; k++) sb.append("  ");
    }

    /** Números sin ".0" sobrante y con 4 decimales como máximo. */
    static String num(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) return "null";
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return Long.toString((long) d);
        String s = String.format(Locale.US, "%.4f", d);
        s = s.replaceAll("0+$", "");
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format(Locale.US, "\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
    }
}
