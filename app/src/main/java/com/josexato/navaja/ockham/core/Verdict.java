package com.josexato.navaja.ockham.core;

import com.josexato.navaja.pantonera.core.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * El resultado de un trabajo, con su criterio: qué se eligió, por qué, qué se dio por cierto y qué otras
 * explicaciones se consideraron. Ockham ayuda a decidir; con los supuestos a la vista, la persona puede no estar de
 * acuerdo.
 */
public final class Verdict {

    public static final class File {
        public String name = "", path = "", sha256 = "";
        public long bytes;
    }

    /** Lo elegido, tal como vino (pares nombre y valor). */
    public Map<?, ?> chosen;
    public String why = "";
    public final List<String> assumptions = new ArrayList<>();
    /** Cada alternativa, tal como vino. */
    public final List<Map<?, ?>> alternatives = new ArrayList<>();
    public final List<File> files = new ArrayList<>();

    static Verdict of(Map<?, ?> m) {
        Verdict v = new Verdict();
        if (m.get("elegido") instanceof Map) v.chosen = (Map<?, ?>) m.get("elegido");
        v.why = OckhamClient.text(m.get("porque"));
        if (m.get("supuestos") instanceof List) for (Object o : (List<?>) m.get("supuestos")) v.assumptions.add(String.valueOf(o));
        if (m.get("alternativas") instanceof List) {
            for (Object o : (List<?>) m.get("alternativas")) if (o instanceof Map) v.alternatives.add((Map<?, ?>) o);
        }
        if (m.get("archivos") instanceof List) {
            for (Object o : (List<?>) m.get("archivos")) {
                if (!(o instanceof Map)) continue;
                Map<?, ?> a = (Map<?, ?>) o;
                File f = new File();
                f.name = OckhamClient.text(a.get("nombre"));
                f.path = OckhamClient.text(a.get("ruta"));
                f.sha256 = OckhamClient.text(a.get("sha256"));
                f.bytes = a.get("bytes") instanceof Number ? ((Number) a.get("bytes")).longValue() : 0;
                v.files.add(f);
            }
        }
        return v;
    }

    /** El veredicto en texto, para mostrarlo: las cuatro partes, en el orden en que se lee una decisión. */
    public String text() {
        StringBuilder sb = new StringBuilder("RESULTADO\n");
        if (chosen == null || chosen.isEmpty()) sb.append("  (sin datos)\n");
        else for (Map.Entry<?, ?> e : chosen.entrySet()) sb.append("  ").append(e.getKey()).append(": ").append(show(e.getValue())).append('\n');
        if (!why.isEmpty()) sb.append("\nPOR QUÉ\n  ").append(why).append('\n');
        sb.append("\nSUPUESTOS\n");
        if (assumptions.isEmpty()) sb.append("  Ninguno declarado.\n");
        else for (String a : assumptions) sb.append("  · ").append(a).append('\n');
        sb.append("\nALTERNATIVAS CONSIDERADAS\n");
        if (alternatives.isEmpty()) sb.append("  Ninguna.\n");
        else for (Map<?, ?> a : alternatives) sb.append("  · ").append(Json.writeCompact(a)).append('\n');
        if (!files.isEmpty()) {
            sb.append("\nARCHIVOS\n");
            for (File f : files) sb.append("  ").append(f.name).append(" (").append(f.bytes).append(" bytes)\n");
        }
        return sb.toString();
    }

    private static String show(Object v) {
        if (v instanceof Double && ((Double) v) == Math.rint((Double) v) && Math.abs((Double) v) < 1e15) {
            return Long.toString(((Double) v).longValue());
        }
        return v instanceof String ? (String) v : Json.writeCompact(v);
    }
}
