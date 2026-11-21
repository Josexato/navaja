package com.josexato.navaja.measure.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * La lista de escaneos guardados: agrupa los archivos de la carpeta de
 * guardado por escaneo (medida_AAAAMMDD_HHMMSS.zip, su ficha .json, su
 * video y sus sensores) y saca de un informe lo que se muestra en la lista
 * (modo, versión, medidas, sensores, acelerador). Java puro.
 */
public final class ScanIndex {

    private ScanIndex() { }

    /** Un escaneo guardado y los archivos que lo acompañan (tamaño en bytes; 0 = no está). */
    public static final class Entry {
        public String base;
        public long zip, video, sensors, json, modified;
        /** "2026-11-19 23:46:12" según el nombre, o "" si el nombre no trae fecha. */
        public String saved = "";
    }

    /** Un video suelto (grabado pero sin escaneo guardado con ese nombre). */
    public static final class Loose {
        public String name;
        public long size, modified;
    }

    private static final Pattern STAMP = Pattern.compile("_(\\d{4})(\\d{2})(\\d{2})_(\\d{2})(\\d{2})(\\d{2})$");

    /** Fecha y hora del nombre base ("medida_20261119_234612" → "2026-11-19 23:46:12"), o "". */
    public static String dateFromName(String base) {
        Matcher m = STAMP.matcher(base);
        return m.find() ? m.group(1) + "-" + m.group(2) + "-" + m.group(3) + " " + m.group(4) + ":" + m.group(5) + ":" + m.group(6) : "";
    }

    /**
     * Agrupa los archivos {nombre, tamaño, modificado (ms)} de la carpeta:
     * devuelve los escaneos (los que tienen ZIP), del más nuevo al más viejo,
     * y deja en {@code loose} los videos sin escaneo.
     */
    public static List<Entry> group(List<Object[]> files, List<Loose> loose) {
        Map<String, Entry> by = new LinkedHashMap<>();
        List<Object[]> others = new ArrayList<>();
        for (Object[] f : files) {
            String name = (String) f[0];
            if (!name.startsWith("medida_")) { others.add(f); continue; }
            String base = name.endsWith(".sensores.csv") ? name.substring(0, name.length() - 13) : name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
            // "medida_X (1).zip": copias que hace el sistema; se toman como escaneos aparte
            Entry e = by.get(base);
            if (e == null) {
                e = new Entry();
                e.base = base;
                e.saved = dateFromName(base.replaceAll(" \\(\\d+\\)$", ""));
                by.put(base, e);
            }
            long size = (Long) f[1];
            if (name.endsWith(".zip")) { e.zip = size; e.modified = (Long) f[2]; }
            else if (name.endsWith(".mp4")) e.video = size;
            else if (name.endsWith(".sensores.csv")) e.sensors = size;
            else if (name.endsWith(".json")) e.json = size;
        }
        List<Entry> out = new ArrayList<>();
        for (Entry e : by.values()) {
            if (e.zip > 0) out.add(e);
            else if (e.video > 0 && loose != null) {
                Loose l = new Loose();
                l.name = e.base + ".mp4";
                l.size = e.video;
                loose.add(l);
            }
        }
        for (Object[] f : others) {
            String name = (String) f[0];
            if (loose == null || !name.endsWith(".mp4")) continue;
            Loose l = new Loose();
            l.name = name;
            l.size = (Long) f[1];
            l.modified = (Long) f[2];
            loose.add(l);
        }
        out.sort((a, b) -> b.base.compareTo(a.base));
        return out;
    }

    /** Lo que se muestra de un escaneo, sacado de su informe.md. */
    public static final class Summary {
        public String mode = "", version = "", generated = "", duration = "", byPoints = "", bySilhouettes = "", difference = "", sensors = "",
                accelerator = "", sheet = "", lens = "";
        public boolean turned;
    }

    /** Lee del informe lo que interesa para la lista (lo que no encuentra queda vacío). */
    public static Summary parse(String report) {
        Summary s = new Summary();
        for (String line : report.split("\n")) {
            String l = line.trim();
            if (l.startsWith("# ")) {
                s.mode = l.contains("hoja con marcas") ? "Video sobre la hoja con marcas" : l.contains("alrededor") || l.contains("desde video") ? "Video alrededor del objeto" : l.substring(2);
            } else if (l.startsWith("Generado por Navaja")) {
                Matcher m = Pattern.compile("Generado por Navaja (.*?)\\s*el (\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2})").matcher(l);
                if (m.find()) {
                    s.version = m.group(1).trim();
                    s.generated = m.group(2);
                }
                if (s.version.isEmpty()) s.version = "anterior a la 1.0.42 (no se anotaba)";
            } else if (l.startsWith("- Duración:")) {
                Matcher m = Pattern.compile("([\\d.]+) s").matcher(l);
                if (m.find()) s.duration = m.group(1) + " s";
            } else if (l.startsWith("- Por puntos:")) s.byPoints = l.substring(13).trim();
            else if (l.startsWith("- Por siluetas")) s.bySilhouettes = l.substring(2).trim();
            else if (l.startsWith("- Sin segunda opinión")) s.bySilhouettes = "sin volumen (las siluetas no alcanzaron)";
            else if (l.startsWith("- Diferencia entre los dos métodos:")) s.difference = l.substring(l.indexOf(':') + 1).trim();
            else if (l.startsWith("- Grabado con sensores")) s.sensors = "sí";
            else if (l.startsWith("- Sin sensores")) s.sensors = "no (video de la galería o de la cámara del sistema)";
            else if (l.startsWith("- Cámara: ")) s.lens = l.substring(10).trim();
            else if (l.startsWith("- Celular apaisado")) s.turned = true;
            else if (l.startsWith("- Cuadros:") && l.contains("ubicados")) {
                Matcher m = Pattern.compile("(\\d+) analizados.*?(\\d+) ubicados").matcher(l);
                if (m.find()) s.sheet = m.group(2) + " de " + m.group(1) + " cuadros ubicados por la hoja";
            } else if (l.startsWith("- XFeat + LighterGlue")) {
                Matcher m = Pattern.compile("XFeat en ([^:]+): ([\\d.]+) ms por foto.*?LighterGlue en ([^:]+): ([\\d.]+) ms por par").matcher(l);
                if (m.find()) s.accelerator = "XFeat en " + m.group(1) + " (" + m.group(2) + " ms) · LighterGlue en " + m.group(3) + " (" + m.group(4) + " ms)";
                else if (l.contains("no corrió") || l.contains("⚠")) s.accelerator = "XFeat no corrió";
            }
        }
        return s;
    }
}
