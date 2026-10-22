package com.josexato.navaja.marker.core;

import com.josexato.navaja.pantonera.core.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Un punto guardado: posición GPS, nombre, nota y (opcional) la foto tomada
 * en ese lugar. Se guarda como JSON (waypoints.json) en la carpeta de la app.
 */
public final class Waypoint {

    public String id;
    public String name;
    public String note;
    public long timeMs;
    public double lat, lon;
    /** Altitud sobre el elipsoide WGS84 (m); NaN si el GPS no la dio. */
    public double altM = Double.NaN;
    /** Precisión horizontal (m, 68 %); NaN si no se conoce. */
    public double accM = Double.NaN;
    /** Nombre del archivo de la foto en la carpeta de fotos, o null. */
    public String photo;

    public boolean hasPhoto() { return photo != null && !photo.isEmpty(); }

    // ---------------------------------------------------------- JSON

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        if (note != null && !note.isEmpty()) m.put("note", note);
        m.put("time", (double) timeMs);
        m.put("lat", lat);
        m.put("lon", lon);
        if (!Double.isNaN(altM)) m.put("alt_m", altM);
        if (!Double.isNaN(accM)) m.put("acc_m", accM);
        if (hasPhoto()) m.put("photo", photo);
        return m;
    }

    static Waypoint fromMap(Map<?, ?> m) {
        Waypoint w = new Waypoint();
        w.id = str(m.get("id"));
        w.name = str(m.get("name"));
        w.note = str(m.get("note"));
        w.timeMs = (long) num(m.get("time"), 0);
        w.lat = num(m.get("lat"), Double.NaN);
        w.lon = num(m.get("lon"), Double.NaN);
        w.altM = num(m.get("alt_m"), Double.NaN);
        w.accM = num(m.get("acc_m"), Double.NaN);
        w.photo = str(m.get("photo"));
        return w;
    }

    /** Lista completa → texto JSON. */
    public static String toJson(List<Waypoint> list) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", 1.0);
        List<Object> arr = new ArrayList<>();
        for (Waypoint w : list) arr.add(w.toMap());
        root.put("waypoints", arr);
        return Json.write(root);
    }

    /** Texto JSON → lista (los puntos sin coordenadas válidas se descartan). */
    public static List<Waypoint> fromJson(String text) throws Json.ParseException {
        List<Waypoint> out = new ArrayList<>();
        Object root = Json.parse(text);
        if (!(root instanceof Map)) return out;
        Object arr = ((Map<?, ?>) root).get("waypoints");
        if (!(arr instanceof List)) return out;
        for (Object o : (List<?>) arr) {
            if (!(o instanceof Map)) continue;
            Waypoint w = fromMap((Map<?, ?>) o);
            if (!Double.isNaN(w.lat) && !Double.isNaN(w.lon) && Math.abs(w.lat) <= 90 && Math.abs(w.lon) <= 180) out.add(w);
        }
        return out;
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }

    private static double num(Object o, double def) { return o instanceof Number ? ((Number) o).doubleValue() : def; }
}
