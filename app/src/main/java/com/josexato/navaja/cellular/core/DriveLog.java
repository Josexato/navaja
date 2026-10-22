package com.josexato.navaja.cellular.core;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * Registro estilo drive test: cada punto es la posición GPS + la celda
 * servidora en ese momento. Exporta a CSV (planilla) y KML (Google Earth,
 * QGIS) y agrupa por estación base para estimar dónde está cada torre.
 */
public final class DriveLog {

    public static final class Point {
        public final long timeMs;
        public final double lat, lon, accM;
        public final CellReading cell;

        public Point(long timeMs, double lat, double lon, double accM, CellReading cell) {
            this.timeMs = timeMs;
            this.lat = lat;
            this.lon = lon;
            this.accM = accM;
            this.cell = cell;
        }
    }

    public final List<Point> points = new ArrayList<>();
    public int gnbBits = CellIds.DEFAULT_GNB_BITS;

    /** No se registran puntos con GPS peor que esto (metros). */
    public static final double MAX_GPS_ACC_M = 50;

    /** Agrega un punto; devuelve false si se descartó (sin celda o GPS impreciso). */
    public boolean add(Point p) {
        if (p.cell == null || Double.isNaN(p.lat) || p.accM > MAX_GPS_ACC_M) return false;
        points.add(p);
        return true;
    }

    // -------------------------------------------------------------------- CSV

    public String csv() {
        StringBuilder sb = new StringBuilder(
                "fecha_utc,lat,lon,precision_m,red,plmn,cell_id,enb_gnb,sector,tac,pci,canal,banda,"
                        + "rsrp_dbm,rsrq_db,sinr_db,cqi,ta,ta_m\n");
        SimpleDateFormat f = iso();
        for (Point p : points) {
            CellReading c = p.cell;
            String[] site = site(c);
            sb.append(f.format(new Date(p.timeMs))).append(',')
                    .append(String.format(Locale.US, "%.6f,%.6f,%.0f,", p.lat, p.lon, p.accM))
                    .append(c.rat == null ? "" : c.rat.name()).append(',')
                    .append(nz(c.plmn())).append(',')
                    .append(c.cellId == CellReading.NA_LONG ? "" : Long.toString(c.cellId)).append(',')
                    .append(site[0]).append(',').append(site[1]).append(',')
                    .append(num(c.area)).append(',').append(num(c.pci)).append(',').append(num(c.channel)).append(',')
                    .append(csvText(nz(c.bandLabel()))).append(',')
                    .append(num(c.rsrp)).append(',').append(num(c.rsrq)).append(',').append(num(c.sinr)).append(',')
                    .append(num(c.cqi)).append(',').append(num(c.timingAdvance)).append(',')
                    .append(Double.isNaN(c.taMeters) ? "" : String.format(Locale.US, "%.0f", c.taMeters)).append('\n');
        }
        return sb.toString();
    }

    private String[] site(CellReading c) {
        if (c.cellId == CellReading.NA_LONG) return new String[]{"", ""};
        if (c.rat == CellReading.Rat.LTE)
            return new String[]{Long.toString(CellIds.enb(c.cellId)), Integer.toString(CellIds.lteSector(c.cellId))};
        if (c.rat == CellReading.Rat.NR)
            return new String[]{Long.toString(CellIds.gnb(c.cellId, gnbBits)), Long.toString(CellIds.nrSector(c.cellId, gnbBits))};
        return new String[]{"", ""};
    }

    // -------------------------------------------------------------------- KML

    /** KML con los puntos coloreados por RSRP y las torres estimadas. */
    public String kml(String name) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document>\n")
                .append("<name>").append(xml(name)).append("</name>\n");
        // Estilos: KML usa colores aabbggrr.
        for (SignalMetrics.Level l : SignalMetrics.Level.values()) {
            sb.append("<Style id=\"").append(l.name()).append("\"><IconStyle><color>").append(abgr(l.color))
                    .append("</color><scale>0.6</scale><Icon><href>http://maps.google.com/mapfiles/kml/shapes/shaded_dot.png</href></Icon></IconStyle></Style>\n");
        }
        sb.append("<Style id=\"torre\"><IconStyle><scale>1.2</scale><Icon><href>http://maps.google.com/mapfiles/kml/shapes/target.png</href></Icon></IconStyle></Style>\n");
        sb.append("<Folder><name>Mediciones</name>\n");
        SimpleDateFormat f = iso();
        for (Point p : points) {
            CellReading c = p.cell;
            SignalMetrics.Level lvl = SignalMetrics.rsrp(c.rsrp);
            sb.append("<Placemark><styleUrl>#").append(lvl.name()).append("</styleUrl><description>")
                    .append(xml(String.format(Locale.US, "%s %s · PCI %s · %s · RSRP %s dBm · SINR %s dB · TA %s",
                            c.rat == null ? "" : c.rat.label, nz(c.siteKey(gnbBits)), num(c.pci), nz(c.bandLabel()),
                            num(c.rsrp), num(c.sinr), num(c.timingAdvance))))
                    .append("</description><TimeStamp><when>").append(f.format(new Date(p.timeMs)))
                    .append("</when></TimeStamp><Point><coordinates>")
                    .append(String.format(Locale.US, "%.6f,%.6f,0", p.lon, p.lat))
                    .append("</coordinates></Point></Placemark>\n");
        }
        sb.append("</Folder>\n<Folder><name>Torres estimadas</name>\n");
        for (Map.Entry<String, TowerLocator.Estimate> e : towers().entrySet()) {
            TowerLocator.Estimate t = e.getValue();
            sb.append("<Placemark><name>").append(xml(e.getKey())).append("</name><styleUrl>#torre</styleUrl><description>")
                    .append(xml(String.format(Locale.US, "Estimada con %d mediciones · ±%.0f m · error medio %.0f m%s",
                            t.used, t.uncertaintyM, t.rmsM, t.ambiguous ? " · AMBIGUA (recorrido en línea recta)" : "")))
                    .append("</description><Point><coordinates>")
                    .append(String.format(Locale.US, "%.6f,%.6f,0", t.lon, t.lat))
                    .append("</coordinates></Point></Placemark>\n");
        }
        sb.append("</Folder>\n</Document></kml>\n");
        return sb.toString();
    }

    // ----------------------------------------------------------------- torres

    /** Estimación por estación base (sólo las que tienen suficientes mediciones con TA). */
    public Map<String, TowerLocator.Estimate> towers() {
        Map<String, List<TowerLocator.Obs>> bySite = new LinkedHashMap<>();
        for (Point p : points) {
            CellReading c = p.cell;
            if (Double.isNaN(c.taMeters)) continue;
            String key = c.siteKey(gnbBits);
            if (key == null) continue;
            double step = c.rat == CellReading.Rat.LTE ? SignalMetrics.LTE_TA_METERS : 30;
            bySite.computeIfAbsent(key, k -> new ArrayList<>()).add(new TowerLocator.Obs(p.lat, p.lon, p.accM, c.taMeters, step));
        }
        Map<String, TowerLocator.Estimate> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<TowerLocator.Obs>> e : bySite.entrySet()) {
            TowerLocator.Estimate est = TowerLocator.locate(e.getValue());
            if (est != null) out.put(e.getKey(), est);
        }
        return out;
    }

    /** Cuántas mediciones con TA tiene cada estación base (para mostrar el avance). */
    public Map<String, Integer> taCounts() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Point p : points) {
            if (Double.isNaN(p.cell.taMeters)) continue;
            String key = p.cell.siteKey(gnbBits);
            if (key != null) out.merge(key, 1, Integer::sum);
        }
        return out;
    }

    // ------------------------------------------------------------- utilidades

    private static SimpleDateFormat iso() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f;
    }

    private static String num(int v) { return CellReading.has(v) ? Integer.toString(v) : ""; }

    private static String nz(String s) { return s == null ? "" : s; }

    private static String csvText(String s) {
        return s.contains(",") || s.contains("\"") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }

    static String xml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** 0xAARRGGBB → "aabbggrr" de KML. */
    static String abgr(int argb) {
        int a = (argb >>> 24) & 255, r = (argb >> 16) & 255, g = (argb >> 8) & 255, b = argb & 255;
        return String.format(Locale.US, "%02x%02x%02x%02x", a, b, g, r);
    }
}
