package com.josexato.navaja.sign.core;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Modelo mínimo de objetos PDF (ISO 32000) y su serialización.
 * Tipos: {@code null}, Boolean, Long, Double, {@link Name}, {@link Str},
 * {@link Ref}, List (array), {@link Dict} y {@link Stream}.
 */
public final class Pdf {

    private Pdf() { }

    public static final class Name {
        public final String v;

        public Name(String v) { this.v = v; }

        @Override public boolean equals(Object o) { return o instanceof Name && ((Name) o).v.equals(v); }

        @Override public int hashCode() { return v.hashCode(); }

        @Override public String toString() { return "/" + v; }
    }

    public static final class Str {
        public final byte[] b;
        public final boolean hex;

        public Str(byte[] b, boolean hex) { this.b = b; this.hex = hex; }

        public static Str of(String s) { return new Str(s.getBytes(StandardCharsets.ISO_8859_1), false); }

        @Override public String toString() { return new String(b, StandardCharsets.ISO_8859_1); }
    }

    public static final class Ref {
        public final int num, gen;

        public Ref(int num, int gen) { this.num = num; this.gen = gen; }

        @Override public boolean equals(Object o) { return o instanceof Ref && ((Ref) o).num == num && ((Ref) o).gen == gen; }

        @Override public int hashCode() { return num * 31 + gen; }

        @Override public String toString() { return num + " " + gen + " R"; }
    }

    /** Diccionario con claves de nombre (sin la barra), en orden de inserción. */
    public static final class Dict extends LinkedHashMap<String, Object> {
        public Dict() { }

        public Dict(Map<String, Object> m) { super(m); }

        public Dict with(String k, Object v) { put(k, v); return this; }

        public boolean isName(String k, String name) {
            Object o = get(k);
            return o instanceof Name && ((Name) o).v.equals(name);
        }
    }

    public static final class Stream {
        public final Dict dict;
        /** Bytes tal como están en el archivo (aún codificados). */
        public final byte[] raw;

        public Stream(Dict dict, byte[] raw) { this.dict = dict; this.raw = raw; }
    }

    public static Name name(String s) { return new Name(s); }

    // ------------------------------------------------------------ escritura

    public static byte[] serialize(Object o) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, o);
        return out.toByteArray();
    }

    @SuppressWarnings("unchecked")
    public static void write(ByteArrayOutputStream out, Object o) {
        if (o == null) {
            ascii(out, "null");
        } else if (o instanceof Boolean || o instanceof Long || o instanceof Integer) {
            ascii(out, o.toString());
        } else if (o instanceof Double || o instanceof Float) {
            ascii(out, num(((Number) o).doubleValue()));
        } else if (o instanceof Name) {
            out.write('/');
            for (byte c : ((Name) o).v.getBytes(StandardCharsets.ISO_8859_1)) {
                int u = c & 0xFF;
                if (u < 0x21 || u > 0x7E || u == '#' || DELIMS.indexOf(u) >= 0) ascii(out, String.format(Locale.US, "#%02X", u));
                else out.write(u);
            }
        } else if (o instanceof Str) {
            Str s = (Str) o;
            if (s.hex) {
                out.write('<');
                ascii(out, Bytes.hex(s.b));
                out.write('>');
            } else {
                out.write('(');
                for (byte c : s.b) {
                    if (c == '(' || c == ')' || c == '\\') out.write('\\');
                    if (c == '\r') { ascii(out, "\\r"); continue; }
                    out.write(c);
                }
                out.write(')');
            }
        } else if (o instanceof Ref) {
            ascii(out, o.toString());
        } else if (o instanceof List) {
            out.write('[');
            int i = 0;
            for (Object e : (List<Object>) o) {
                if (i++ > 0) out.write(' ');
                write(out, e);
            }
            out.write(']');
        } else if (o instanceof Dict) {
            ascii(out, "<<");
            for (Map.Entry<String, Object> e : ((Dict) o).entrySet()) {
                write(out, new Name(e.getKey()));
                out.write(' ');
                write(out, e.getValue());
                out.write(' ');
            }
            ascii(out, ">>");
        } else if (o instanceof Stream) {
            Stream s = (Stream) o;
            Dict d = new Dict(s.dict);
            d.put("Length", (long) s.raw.length);
            write(out, d);
            ascii(out, "\nstream\n");
            out.write(s.raw, 0, s.raw.length);
            ascii(out, "\nendstream");
        } else {
            throw new IllegalArgumentException("Tipo PDF no soportado: " + o.getClass());
        }
    }

    static final String DELIMS = "()<>[]{}/%";

    public static void ascii(ByteArrayOutputStream out, String s) {
        byte[] b = s.getBytes(StandardCharsets.ISO_8859_1);
        out.write(b, 0, b.length);
    }

    /** Número real compacto: hasta 3 decimales, sin ceros sobrantes ni notación científica. */
    public static String num(double d) {
        if (Math.abs(d) < 0.0005) return "0";
        String s = String.format(Locale.US, "%.3f", d);
        if (s.indexOf('.') >= 0) {
            s = s.replaceAll("0+$", "");
            if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        }
        return s;
    }
}
