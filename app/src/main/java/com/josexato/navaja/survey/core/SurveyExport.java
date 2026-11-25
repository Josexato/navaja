package com.josexato.navaja.survey.core;

import com.josexato.navaja.cellular.core.CellReading;
import com.josexato.navaja.cellular.core.SignalMetrics;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * Los archivos que salen de un levantamiento: mediciones en CSV, plano en DXF, mapa en GeoJSON (sólo si el
 * levantamiento está ubicado con GPS y rumbo) y un informe en texto.
 */
public final class SurveyExport {

    private SurveyExport() { }

    /** Tolerancia al simplificar el recorrido para dibujarlo (metros). */
    static final double LINE_TOL_M = 0.03;
    /** Altura de la letra en el plano (metros). */
    static final double TEXT_M = 0.12;
    /** Radio de cada medición dibujada en el plano (metros). */
    static final double DOT_M = 0.05;

    // -------------------------------------------------------------------- CSV

    /** Una fila por medición de la celda servidora, con su posición en metros del plano. */
    public static String csv(SurveyLog log) {
        boolean geo = log.georeferenced();
        StringBuilder sb = new StringBuilder("t_s,fecha_utc,x_m,y_m,alto_m,sim,red,plmn,cell_id,tac,pci,canal,banda,"
                + "rsrp_dbm,rsrq_db,sinr_db,nivel,antiguedad_ms");
        if (geo) sb.append(",lat,lon");
        sb.append('\n');
        SimpleDateFormat f = iso();
        for (SurveyLog.Point p : log.points()) {
            SurveyLog.Reading r = p.reading;
            CellReading c = r.cell;
            sb.append(String.format(Locale.US, "%.1f,", r.measuredMs() / 1000.0))
                    .append(log.startWallMs == 0 ? "" : f.format(new Date(log.startWallMs + r.measuredMs()))).append(',')
                    .append(String.format(Locale.US, "%.2f,%.2f,%.2f,", p.x, p.y, p.z))
                    .append(r.sim + 1).append(',')
                    .append(c.rat == null ? "" : c.rat.name()).append(',')
                    .append(c.plmn() == null ? "" : c.plmn()).append(',')
                    .append(c.cellId == CellReading.NA_LONG ? "" : Long.toString(c.cellId)).append(',')
                    .append(num(c.area)).append(',').append(num(c.pci)).append(',').append(num(c.channel)).append(',')
                    .append(c.reportedBands.isEmpty() ? "" : Integer.toString(c.reportedBands.get(0))).append(',')
                    .append(num(c.rsrp)).append(',').append(num(c.rsrq)).append(',').append(num(c.sinr)).append(',')
                    .append(SignalMetrics.rsrp(c.rsrp).name()).append(',')
                    .append(r.ageMs < 0 ? "" : Long.toString(r.ageMs));
            if (geo) {
                double[] ll = log.geoOf(p.x, p.y);
                sb.append(String.format(Locale.US, ",%.7f,%.7f", ll[0], ll[1]));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------- DXF

    /** El plano: recorrido, mediciones coloreadas por potencia, y cada habitación con sus medidas. */
    public static Dxf plan(SurveyLog log) {
        log.measureRooms();
        Dxf d = new Dxf()
                .layer("RECORRIDO", Dxf.BLUE)
                .layer("HABITACION", Dxf.WHITE)
                .layer("MEDIDAS", Dxf.MAGENTA)
                .layer("SENAL_EXCELENTE", Dxf.GREEN)
                .layer("SENAL_BUENA", Dxf.CYAN)
                .layer("SENAL_REGULAR", Dxf.ORANGE)
                .layer("SENAL_CRITICA", Dxf.RED)
                .layer("SENAL_SIN_DATO", Dxf.GRAY);
        for (List<double[]> line : log.track.lines(LINE_TOL_M)) d.polyline("RECORRIDO", line, false);
        if (!log.track.samples.isEmpty()) {
            Track.Sample s = log.track.samples.get(0);
            d.text("RECORRIDO", s.x + DOT_M, s.y + DOT_M, TEXT_M, "Inicio");
        }
        for (SurveyLog.Point p : log.points()) d.circle(signalLayer(p.reading.cell.rsrp), p.x, p.y, DOT_M);
        for (Room r : log.rooms) {
            if (!r.closed()) {
                if (r.corners.size() == 2) d.polyline("HABITACION", r.corners, false);
                continue;
            }
            d.polyline("HABITACION", r.corners, true);
            for (int i = 0; i < r.corners.size(); i++) {
                double[] a = r.corners.get(i), b = r.corners.get((i + 1) % r.corners.size());
                d.text("MEDIDAS", (a[0] + b[0]) / 2, (a[1] + b[1]) / 2, TEXT_M,
                        String.format(Locale.US, "%.2f m", r.side(i)));
            }
            double[] c = r.centroid();
            d.text("MEDIDAS", c[0], c[1] + TEXT_M * 1.6, TEXT_M * 1.4, r.name);
            d.text("MEDIDAS", c[0], c[1], TEXT_M, String.format(Locale.US, "Area %.2f m2", r.area()));
            d.text("MEDIDAS", c[0], c[1] - TEXT_M * 1.6, TEXT_M, Double.isNaN(r.height()) ? "Altura sin medir"
                    : String.format(Locale.US, "Altura %.2f m", r.height()));
        }
        return d;
    }

    static String signalLayer(int rsrp) {
        switch (SignalMetrics.rsrp(rsrp)) {
            case EXCELLENT: return "SENAL_EXCELENTE";
            case GOOD: return "SENAL_BUENA";
            case FAIR: return "SENAL_REGULAR";
            case POOR: return "SENAL_CRITICA";
            default: return "SENAL_SIN_DATO";
        }
    }

    // ---------------------------------------------------------------- GeoJSON

    /**
     * GeoJSON (RFC 7946, latitud y longitud): recorrido, mediciones y habitaciones. Devuelve null si el levantamiento
     * no tiene un punto GPS y un rumbo: sin eso no hay coordenadas reales que escribir.
     */
    public static String geoJson(SurveyLog log) {
        if (!log.georeferenced()) return null;
        log.measureRooms();
        StringBuilder sb = new StringBuilder("{\"type\":\"FeatureCollection\",\"features\":[");
        boolean first = true;
        for (List<double[]> line : log.track.lines(LINE_TOL_M)) {
            first = sep(sb, first);
            sb.append("{\"type\":\"Feature\",\"properties\":{\"tipo\":\"recorrido\"},\"geometry\":{\"type\":\"LineString\",")
                    .append("\"coordinates\":").append(coords(log, line, false)).append("}}");
        }
        for (SurveyLog.Point p : log.points()) {
            CellReading c = p.reading.cell;
            double[] ll = log.geoOf(p.x, p.y);
            first = sep(sb, first);
            sb.append("{\"type\":\"Feature\",\"properties\":{\"tipo\":\"medicion\"")
                    .append(",\"t_s\":").append(String.format(Locale.US, "%.1f", p.reading.measuredMs() / 1000.0))
                    .append(",\"sim\":").append(p.reading.sim + 1)
                    .append(",\"red\":\"").append(c.rat == null ? "" : c.rat.name()).append('"')
                    .append(",\"rsrp_dbm\":").append(json(c.rsrp))
                    .append(",\"rsrq_db\":").append(json(c.rsrq))
                    .append(",\"sinr_db\":").append(json(c.sinr))
                    .append(",\"pci\":").append(json(c.pci))
                    .append(",\"nivel\":\"").append(SignalMetrics.rsrp(c.rsrp).name()).append('"')
                    .append(",\"alto_m\":").append(String.format(Locale.US, "%.2f", p.z))
                    .append("},\"geometry\":{\"type\":\"Point\",\"coordinates\":")
                    .append(String.format(Locale.US, "[%.7f,%.7f]", ll[1], ll[0])).append("}}");
        }
        for (Room r : log.rooms) {
            if (!r.closed()) continue;
            first = sep(sb, first);
            sb.append("{\"type\":\"Feature\",\"properties\":{\"tipo\":\"habitacion\",\"nombre\":\"")
                    .append(r.name.replace("\"", "'")).append('"')
                    .append(",\"area_m2\":").append(String.format(Locale.US, "%.2f", r.area()))
                    .append(",\"perimetro_m\":").append(String.format(Locale.US, "%.2f", r.perimeter()))
                    .append(",\"altura_m\":").append(Double.isNaN(r.height()) ? "null"
                            : String.format(Locale.US, "%.2f", r.height()))
                    .append("},\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[")
                    .append(coords(log, ccw(r), true)).append("]}}");
        }
        return sb.append("]}\n").toString();
    }

    /** RFC 7946 pide el contorno exterior en sentido antihorario. */
    private static List<double[]> ccw(Room r) {
        if (r.signedArea() >= 0) return r.corners;
        List<double[]> rev = new java.util.ArrayList<>(r.corners);
        java.util.Collections.reverse(rev);
        return rev;
    }

    private static String coords(SurveyLog log, List<double[]> pts, boolean ring) {
        StringBuilder sb = new StringBuilder("[");
        int n = pts.size() + (ring ? 1 : 0);
        for (int i = 0; i < n; i++) {
            double[] p = pts.get(i % pts.size()), ll = log.geoOf(p[0], p[1]);
            if (i > 0) sb.append(',');
            sb.append(String.format(Locale.US, "[%.7f,%.7f]", ll[1], ll[0]));
        }
        return sb.append(']').toString();
    }

    private static boolean sep(StringBuilder sb, boolean first) {
        if (!first) sb.append(',');
        return false;
    }

    // ----------------------------------------------------------------- informe

    /** Resumen legible: qué se grabó, qué tan bien siguió el teléfono y cómo estuvo la señal. */
    public static String report(SurveyLog log) {
        log.measureRooms();
        StringBuilder sb = new StringBuilder("# Levantamiento\n\n");
        if (log.startWallMs != 0) sb.append("- Fecha (UTC): ").append(iso().format(new Date(log.startWallMs))).append('\n');
        for (String n : log.notes) sb.append("- ").append(n).append('\n');
        sb.append(String.format(Locale.US, "- Duración: %.0f s\n", log.durationMs() / 1000.0))
                .append(String.format(Locale.US, "- Distancia caminada: %.1f m\n", log.track.length()))
                .append(String.format(Locale.US, "- Tiempo con seguimiento: %.0f %%\n", 100 * log.track.trackedShare()));
        for (Map.Entry<String, Integer> e : log.lost.entrySet()) {
            sb.append("  - sin seguimiento por ").append(e.getKey()).append(": ").append(e.getValue()).append(" muestras\n");
        }
        sb.append("- Ubicado en el mapa: ").append(log.georeferenced()
                ? String.format(Locale.US, "sí (GPS ±%.0f m, rumbo %.0f°)", log.geoAccM, log.northDeg)
                : Double.isNaN(log.lat) ? "no (sin punto GPS)" : "no (sin rumbo)").append('\n');

        List<SurveyLog.Point> pts = log.points();
        sb.append("\n## Señal\n\n- Mediciones con posición: ").append(pts.size())
                .append(" (de ").append(log.readings.size()).append(" lecturas recibidas)\n");
        long fresh = log.freshIntervalMs();
        sb.append("- Una medición nueva cada: ").append(fresh < 0 ? "sin datos suficientes"
                : String.format(Locale.US, "%.1f s (mediana)", fresh / 1000.0)).append('\n');
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE, n = 0;
        long sum = 0;
        Map<SignalMetrics.Level, Integer> levels = new EnumMap<>(SignalMetrics.Level.class);
        for (SurveyLog.Point p : pts) {
            int v = p.reading.cell.rsrp;
            levels.merge(SignalMetrics.rsrp(v), 1, Integer::sum);
            if (!CellReading.has(v)) continue;
            min = Math.min(min, v);
            max = Math.max(max, v);
            sum += v;
            n++;
        }
        if (n > 0) {
            sb.append(String.format(Locale.US, "- RSRP: mínimo %d, promedio %.0f, máximo %d dBm\n", min, sum / (double) n, max));
            for (Map.Entry<SignalMetrics.Level, Integer> e : levels.entrySet()) {
                sb.append(String.format(Locale.US, "  - %s: %.0f %%\n", e.getKey().label, 100.0 * e.getValue() / pts.size()));
            }
        }

        sb.append("\n## Planos detectados\n\n");
        int floors = 0, ceilings = 0, walls = 0;
        for (SurveyLog.Plane p : log.planes.values()) {
            if (p.type == SurveyLog.FLOOR) floors++;
            else if (p.type == SurveyLog.CEILING) ceilings++;
            else walls++;
        }
        sb.append("- Pisos: ").append(floors).append(", techos: ").append(ceilings).append(", muros: ").append(walls).append('\n');
        double fz = log.floorZ(), cz = log.ceilingZ();
        sb.append("- Altura piso–techo: ").append(Double.isNaN(fz) || Double.isNaN(cz) ? "sin medir (falta el piso o el techo)"
                : String.format(Locale.US, "%.2f m", cz - fz)).append('\n');

        if (!log.rooms.isEmpty()) {
            sb.append("\n## Habitaciones\n\n| Nombre | Esquinas | Área (m²) | Perímetro (m) | Altura (m) | Volumen (m³) |\n")
                    .append("|---|---|---|---|---|---|\n");
            for (Room r : log.rooms) {
                sb.append("| ").append(r.name).append(" | ").append(r.corners.size()).append(" | ");
                if (!r.closed()) {
                    sb.append("faltan esquinas | | | |\n");
                    continue;
                }
                sb.append(String.format(Locale.US, "%.2f%s | %.2f | ", r.area(),
                        r.selfIntersects() ? " (las paredes se cruzan: revisar el orden)" : "", r.perimeter()));
                if (Double.isNaN(r.height())) sb.append("sin medir | |\n");
                else sb.append(String.format(Locale.US, "%.2f | %.1f |\n", r.height(), r.volume()));
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------- utilidades

    private static SimpleDateFormat iso() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f;
    }

    private static String num(int v) { return CellReading.has(v) ? Integer.toString(v) : ""; }

    private static String json(int v) { return CellReading.has(v) ? Integer.toString(v) : "null"; }
}
