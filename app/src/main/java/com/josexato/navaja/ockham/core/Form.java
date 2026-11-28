package com.josexato.navaja.ockham.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * El formulario de una receta. El servidor describe los parámetros de cada receta con un esquema JSON; de ahí sale
 * qué preguntarle a la persona, y con sus respuestas se arma lo que se manda. Lo que se deja vacío no se manda: el
 * servidor usa su valor por omisión.
 *
 * <p>Sólo se entienden parámetros simples (número, texto, sí o no, o una lista de opciones). El servidor vuelve a
 * comprobar todo: esto es para avisar antes de subir un archivo grande, no para reemplazar esa comprobación.
 */
public final class Form {

    public enum Kind { INTEGER, NUMBER, BOOLEAN, TEXT, CHOICE }

    public static final class Field {
        public String name = "", description = "";
        public Kind kind = Kind.TEXT;
        /** Las opciones, si es una lista. */
        public final List<String> options = new ArrayList<>();
        /** Límites de un número; null si no tiene. */
        public Double min, max;
        public int maxLength;

        /** El nombre para mostrar: «quitar_plano» se lee «quitar plano». */
        public String label() {
            return name.replace('_', ' ');
        }
    }

    private Form() { }

    /** Los campos de un esquema, en el orden en que vienen. Lo que no se sabe preguntar se saltea. */
    public static List<Field> of(Map<String, Object> schema) {
        List<Field> out = new ArrayList<>();
        Object props = schema == null ? null : schema.get("properties");
        if (!(props instanceof Map)) return out;
        for (Map.Entry<?, ?> e : ((Map<?, ?>) props).entrySet()) {
            if (!(e.getValue() instanceof Map)) continue;
            Map<?, ?> p = (Map<?, ?>) e.getValue();
            Field f = new Field();
            f.name = String.valueOf(e.getKey());
            f.description = OckhamClient.text(p.get("description"));
            Object type = p.get("type");
            if (p.get("enum") instanceof List) {
                f.kind = Kind.CHOICE;
                for (Object o : (List<?>) p.get("enum")) if (o instanceof String) f.options.add((String) o);
                if (f.options.isEmpty()) continue;
            } else if ("integer".equals(type)) {
                f.kind = Kind.INTEGER;
            } else if ("number".equals(type)) {
                f.kind = Kind.NUMBER;
            } else if ("boolean".equals(type)) {
                f.kind = Kind.BOOLEAN;
            } else if ("string".equals(type)) {
                f.kind = Kind.TEXT;
            } else {
                continue;
            }
            if (p.get("minimum") instanceof Number) f.min = ((Number) p.get("minimum")).doubleValue();
            if (p.get("maximum") instanceof Number) f.max = ((Number) p.get("maximum")).doubleValue();
            f.maxLength = OckhamClient.number(p.get("maxLength"));
            out.add(f);
        }
        return out;
    }

    /**
     * Lo que se manda al servidor, a partir de lo que se escribió en cada campo (por nombre). Un campo vacío no se
     * manda. Un sí o no se manda sólo cuando es «true».
     */
    public static Map<String, Object> values(List<Field> fields, Map<String, String> raw) throws OckhamException {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Field f : fields) {
            String v = raw.get(f.name);
            v = v == null ? "" : v.trim();
            if (v.isEmpty()) continue;
            switch (f.kind) {
                case BOOLEAN:
                    if (v.equals("true")) out.put(f.name, Boolean.TRUE);
                    break;
                case CHOICE:
                    if (!f.options.contains(v)) throw bad(f, "no es una de las opciones");
                    out.put(f.name, v);
                    break;
                case TEXT:
                    if (f.maxLength > 0 && v.length() > f.maxLength) throw bad(f, "admite hasta " + f.maxLength + " caracteres");
                    out.put(f.name, v);
                    break;
                default:
                    double d;
                    try {
                        d = Double.parseDouble(v.replace(',', '.'));
                    } catch (NumberFormatException e) {
                        throw bad(f, "tiene que ser un número");
                    }
                    if (Double.isNaN(d) || Double.isInfinite(d)) throw bad(f, "tiene que ser un número");
                    if (f.kind == Kind.INTEGER && d != Math.rint(d)) throw bad(f, "tiene que ser un número entero");
                    if (f.min != null && d < f.min) throw bad(f, "no puede ser menor que " + show(f.min));
                    if (f.max != null && d > f.max) throw bad(f, "no puede ser mayor que " + show(f.max));
                    out.put(f.name, f.kind == Kind.INTEGER ? (Object) Long.valueOf((long) d) : (Object) Double.valueOf(d));
            }
        }
        return out;
    }

    private static OckhamException bad(Field f, String why) {
        return new OckhamException(0, "«" + f.label() + "» " + why + ".");
    }

    private static String show(double d) {
        return d == Math.rint(d) ? Long.toString((long) d) : Double.toString(d);
    }
}
