package com.josexato.navaja.survey.core;

import com.josexato.navaja.cellular.core.CellReading;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Todo lo que el teléfono entregó durante un levantamiento, en el orden en que llegó: posiciones, lecturas de señal,
 * planos detectados, esquinas marcadas, presión y ubicación. Cada dato se guarda además como una línea de texto
 * ({@link #text()}), y {@link #parse(String)} reconstruye el levantamiento desde ese texto: así un registro grabado
 * en el teléfono se puede volver a procesar después, en el teléfono o en una PC.
 *
 * <p>Los tiempos son milisegundos desde que empezó la grabación.
 */
public final class SurveyLog {

    /** Versión del formato de líneas. */
    public static final int FORMAT = 1;

    /** Tipos de plano: piso (horizontal hacia arriba), techo (horizontal hacia abajo) y muro. */
    public static final char FLOOR = 'P', CEILING = 'T', WALL = 'M';
    /** Un plano más chico que esto (m²) no se toma como piso ni como techo: puede ser una mesa o una viga. */
    public static final double MIN_PLANE_M2 = 0.5;

    public static final class Reading {
        public final long tMs;
        /** Número de SIM (0 o 1). */
        public final int sim;
        /** Cuánto llevaba medida esta lectura cuando llegó; −1 si el teléfono no lo dice. */
        public final long ageMs;
        public final CellReading cell;

        Reading(long tMs, int sim, long ageMs, CellReading cell) {
            this.tMs = tMs;
            this.sim = sim;
            this.ageMs = ageMs;
            this.cell = cell;
        }

        /** El instante en que el módem midió. */
        public long measuredMs() {
            return ageMs > 0 ? Math.max(0, tMs - ageMs) : tMs;
        }
    }

    public static final class Plane {
        public final String id;
        public final char type;
        /** Altura del plano (piso y techo) y su centro en planta. */
        public final double z, cx, cy, area;
        public final long tMs;

        Plane(long tMs, String id, char type, double z, double cx, double cy, double area) {
            this.tMs = tMs;
            this.id = id;
            this.type = type;
            this.z = z;
            this.cx = cx;
            this.cy = cy;
            this.area = area;
        }
    }

    /** Una lectura de señal con el lugar donde estaba el teléfono. */
    public static final class Point {
        public final double x, y, z;
        public final Reading reading;

        Point(double[] p, Reading reading) {
            this.x = p[0];
            this.y = p[1];
            this.z = p[2];
            this.reading = reading;
        }
    }

    public final Track track = new Track();
    public final List<Reading> readings = new ArrayList<>();
    /** El último estado de cada plano, por su identificador. */
    public final Map<String, Plane> planes = new LinkedHashMap<>();
    public final List<Room> rooms = new ArrayList<>();
    /** Alturas de las esquinas marcadas, por habitación: son puntos del piso. */
    private final List<List<Double>> cornerZ = new ArrayList<>();
    /** {tMs, hPa}. */
    public final List<double[]> pressure = new ArrayList<>();
    public final List<String> notes = new ArrayList<>();

    /** Cuándo empezó, en hora del reloj (ms desde 1970); 0 si no se sabe. */
    public long startWallMs;
    /** Punto GPS de referencia y el lugar del plano donde se tomó; NaN si no hay. */
    public double lat = Double.NaN, lon = Double.NaN, geoAccM = Double.NaN, geoX, geoY;
    /** Rumbo del eje +y del plano, en grados desde el norte en sentido horario; NaN si no hay brújula. */
    public double northDeg = Double.NaN;
    /** Cuántas posiciones llegaron sin seguimiento, por motivo. */
    public final Map<String, Integer> lost = new LinkedHashMap<>();

    private final StringBuilder lines = new StringBuilder("V;" + FORMAT + "\n");
    private Room open;

    // ------------------------------------------------------------- lo que llega

    public void start(long wallMs, String device) {
        startWallMs = wallMs;
        line("I", wallMs, clean(device));
    }

    /**
     * Una posición. {@code yawDeg} es hacia dónde mira la cámara en planta (grados desde +x, antihorario);
     * {@code reason} es el motivo cuando no hay seguimiento.
     */
    public void pose(long tMs, double x, double y, double z, double yawDeg, boolean tracking, String reason) {
        track.add(tMs, x, y, z, tracking);
        if (!tracking) lost.merge(reason == null || reason.isEmpty() ? "?" : reason, 1, Integer::sum);
        line("P", tMs, f(x), f(y), f(z), String.format(Locale.US, "%.1f", yawDeg), tracking ? "1" : "0",
                tracking ? "" : clean(reason));
    }

    public void cell(long tMs, int sim, long ageMs, CellReading c) {
        if (c == null) return;
        readings.add(new Reading(tMs, sim, ageMs, c));
        line("C", tMs, sim, ageMs, c.registered ? "1" : "0", c.rat == null ? "" : c.rat.name(), nz(c.mcc), nz(c.mnc),
                c.cellId == CellReading.NA_LONG ? "" : Long.toString(c.cellId), n(c.area), n(c.pci), n(c.channel),
                c.reportedBands.isEmpty() ? "" : Integer.toString(c.reportedBands.get(0)),
                n(c.rsrp), n(c.rsrq), n(c.sinr), n(c.rssi), n(c.cqi), n(c.timingAdvance));
    }

    public void plane(long tMs, String id, char type, double z, double cx, double cy, double area) {
        planes.put(id, new Plane(tMs, id, type, z, cx, cy, area));
        line("L", tMs, clean(id), type, f(z), f(cx), f(cy), f(area));
    }

    /** Una esquina del piso de la habitación que se está marcando. */
    public void corner(long tMs, double x, double y, double z) {
        if (open == null) {
            open = new Room("Habitación " + (rooms.size() + 1));
            rooms.add(open);
            cornerZ.add(new ArrayList<>());
        }
        open.corner(x, y);
        cornerZ.get(cornerZ.size() - 1).add(z);
        line("K", tMs, f(x), f(y), f(z));
    }

    /** Quita la última esquina marcada; devuelve false si no había ninguna en la habitación abierta. */
    public boolean undoCorner(long tMs) {
        if (open == null || open.corners.isEmpty()) return false;
        open.corners.remove(open.corners.size() - 1);
        List<Double> zs = cornerZ.get(cornerZ.size() - 1);
        zs.remove(zs.size() - 1);
        if (open.corners.isEmpty()) {
            rooms.remove(rooms.size() - 1);
            cornerZ.remove(cornerZ.size() - 1);
            open = null;
        }
        line("U", tMs);
        return true;
    }

    /** Da por terminada la habitación abierta; la próxima esquina empieza otra. */
    public void closeRoom(long tMs) {
        if (open == null) return;
        open = null;
        line("R", tMs);
    }

    /** La habitación que se está marcando, o null. */
    public Room openRoom() {
        return open;
    }

    public void pressure(long tMs, double hPa) {
        pressure.add(new double[]{tMs, hPa});
        line("B", tMs, String.format(Locale.US, "%.2f", hPa));
    }

    /** Punto GPS tomado estando en (x, y) del plano. */
    public void geo(long tMs, double lat, double lon, double accM, double x, double y) {
        if (!Double.isNaN(this.geoAccM) && accM >= this.geoAccM) {
            line("G", tMs, g(lat), g(lon), f(accM), f(x), f(y), "0");
            return; // se queda con el más preciso
        }
        this.lat = lat;
        this.lon = lon;
        this.geoAccM = accM;
        this.geoX = x;
        this.geoY = y;
        line("G", tMs, g(lat), g(lon), f(accM), f(x), f(y), "1");
    }

    public void north(long tMs, double deg) {
        northDeg = deg;
        line("N", tMs, String.format(Locale.US, "%.1f", deg));
    }

    public void note(long tMs, String text) {
        notes.add(text);
        line("#", tMs, clean(text));
    }

    // ---------------------------------------------------------- lo que se deduce

    /**
     * Las lecturas de la celda servidora con su posición. Una lectura repetida (el teléfono devuelve la misma medición
     * del módem hasta que hay una nueva) cuenta una sola vez, y las que caen en un tramo sin seguimiento se descartan.
     */
    public List<Point> points() {
        List<Point> out = new ArrayList<>();
        Map<Integer, Reading> last = new LinkedHashMap<>();
        for (Reading r : readings) {
            if (!r.cell.registered) continue;
            int key = r.sim * 8 + (r.cell.rat == null ? 7 : r.cell.rat.ordinal());
            Reading prev = last.get(key);
            if (prev != null && same(prev, r)) continue;
            last.put(key, r);
            double[] p = track.at(r.measuredMs());
            if (p != null) out.add(new Point(p, r));
        }
        return out;
    }

    /** Si dos lecturas son la misma medición del módem vista dos veces. */
    private static boolean same(Reading a, Reading b) {
        if (a.ageMs >= 0 && b.ageMs >= 0) return Math.abs(a.measuredMs() - b.measuredMs()) < 100;
        CellReading x = a.cell, y = b.cell;
        return x.cellId == y.cellId && x.pci == y.pci && x.rsrp == y.rsrp && x.rsrq == y.rsrq && x.sinr == y.sinr
                && b.tMs - a.tMs < 1500;
    }

    /**
     * Cada cuántos milisegundos llega una medición nueva de la celda servidora (mediana), o −1 si no alcanzan los
     * datos. Es el dato que dice qué tan fino puede salir el mapa de señal en este teléfono.
     */
    public long freshIntervalMs() {
        List<Long> gaps = new ArrayList<>();
        Map<Integer, Reading> last = new LinkedHashMap<>();
        for (Reading r : readings) {
            if (!r.cell.registered) continue;
            int key = r.sim * 8 + (r.cell.rat == null ? 7 : r.cell.rat.ordinal());
            Reading prev = last.get(key);
            if (prev != null && same(prev, r)) continue;
            if (prev != null) gaps.add(r.measuredMs() - prev.measuredMs());
            last.put(key, r);
        }
        if (gaps.size() < 3) return -1;
        gaps.sort(null);
        return gaps.get(gaps.size() / 2);
    }

    /** Altura del piso: el plano horizontal hacia arriba más bajo de tamaño suficiente; NaN si no hay. */
    public double floorZ() {
        double z = Double.NaN;
        for (Plane p : planes.values()) {
            if (p.type == FLOOR && p.area >= MIN_PLANE_M2 && (Double.isNaN(z) || p.z < z)) z = p.z;
        }
        return z;
    }

    /** Altura del techo: el plano horizontal hacia abajo más alto de tamaño suficiente; NaN si no hay. */
    public double ceilingZ() {
        double z = Double.NaN;
        for (Plane p : planes.values()) {
            if (p.type == CEILING && p.area >= MIN_PLANE_M2 && (Double.isNaN(z) || p.z > z)) z = p.z;
        }
        return z;
    }

    /**
     * Pone el piso y el techo de cada habitación. El piso sale de las propias esquinas marcadas (son puntos del piso);
     * el techo, del plano de techo más alto cuyo centro cae dentro del contorno, o del más alto de todos si ninguno
     * cae dentro.
     */
    public void measureRooms() {
        for (int i = 0; i < rooms.size(); i++) {
            Room r = rooms.get(i);
            List<Double> zs = new ArrayList<>(cornerZ.get(i));
            zs.sort(null);
            r.floorZ = zs.isEmpty() ? floorZ() : zs.get(zs.size() / 2);
            double inside = Double.NaN;
            for (Plane p : planes.values()) {
                if (p.type != CEILING || p.area < MIN_PLANE_M2 || !r.contains(p.cx, p.cy)) continue;
                if (Double.isNaN(inside) || p.z > inside) inside = p.z;
            }
            r.ceilingZ = Double.isNaN(inside) ? ceilingZ() : inside;
        }
    }

    public boolean georeferenced() {
        return !Double.isNaN(lat) && !Double.isNaN(northDeg);
    }

    /** De metros del plano a {lat, lon}; null si el levantamiento no está ubicado. */
    public double[] geoOf(double x, double y) {
        if (!georeferenced()) return null;
        double t = Math.toRadians(northDeg), dx = x - geoX, dy = y - geoY;
        double east = dx * Math.cos(t) + dy * Math.sin(t), north = -dx * Math.sin(t) + dy * Math.cos(t);
        double r = 6_371_008.8;
        return new double[]{lat + Math.toDegrees(north / r),
                lon + Math.toDegrees(east / (r * Math.cos(Math.toRadians(lat))))};
    }

    /** Duración de lo grabado en milisegundos. */
    public long durationMs() {
        int n = track.samples.size();
        return n < 2 ? 0 : track.samples.get(n - 1).tMs - track.samples.get(0).tMs;
    }

    // ------------------------------------------------------------------- texto

    public String text() {
        return lines.toString();
    }

    /** Reconstruye un levantamiento desde su texto. Las líneas que no se entienden se saltan. */
    public static SurveyLog parse(String text) {
        SurveyLog log = new SurveyLog();
        for (String raw : text.split("\n")) {
            String[] p = raw.split(";", -1);
            try {
                if (p.length < 2 || p[0].equals("V")) continue;
                long t = Long.parseLong(p[1]);
                switch (p[0]) {
                    case "I": log.start(t, p[2]); break;
                    case "P": log.pose(t, d(p[2]), d(p[3]), d(p[4]), d(p[5]), p[6].equals("1"), p[7]); break;
                    case "C": log.cell(t, Integer.parseInt(p[2]), Long.parseLong(p[3]), cellOf(p)); break;
                    case "L": log.plane(t, p[2], p[3].charAt(0), d(p[4]), d(p[5]), d(p[6]), d(p[7])); break;
                    case "K": log.corner(t, d(p[2]), d(p[3]), d(p[4])); break;
                    case "U": log.undoCorner(t); break;
                    case "R": log.closeRoom(t); break;
                    case "B": log.pressure(t, d(p[2])); break;
                    case "G": log.geo(t, d(p[2]), d(p[3]), d(p[4]), d(p[5]), d(p[6])); break;
                    case "N": log.north(t, d(p[2])); break;
                    case "#": log.note(t, p[2]); break;
                    default: break;
                }
            } catch (RuntimeException ignored) {
                // línea cortada o de una versión posterior
            }
        }
        return log;
    }

    private static CellReading cellOf(String[] p) {
        CellReading c = new CellReading();
        c.registered = p[4].equals("1");
        c.rat = p[5].isEmpty() ? null : CellReading.Rat.valueOf(p[5]);
        c.mcc = p[6].isEmpty() ? null : p[6];
        c.mnc = p[7].isEmpty() ? null : p[7];
        c.cellId = p[8].isEmpty() ? CellReading.NA_LONG : Long.parseLong(p[8]);
        c.area = i(p[9]);
        c.pci = i(p[10]);
        c.channel = i(p[11]);
        if (!p[12].isEmpty()) c.reportedBands.add(Integer.parseInt(p[12]));
        c.rsrp = i(p[13]);
        c.rsrq = i(p[14]);
        c.sinr = i(p[15]);
        c.rssi = i(p[16]);
        c.cqi = i(p[17]);
        c.timingAdvance = i(p[18]);
        return c;
    }

    private void line(String kind, Object... fields) {
        lines.append(kind);
        for (Object o : fields) lines.append(';').append(o);
        lines.append('\n');
    }

    private static String f(double v) { return String.format(Locale.US, "%.3f", v); }

    private static String g(double v) { return String.format(Locale.US, "%.7f", v); }

    private static String n(int v) { return CellReading.has(v) ? Integer.toString(v) : ""; }

    private static String nz(String s) { return s == null ? "" : clean(s); }

    private static int i(String s) { return s.isEmpty() ? CellReading.NA : Integer.parseInt(s); }

    private static double d(String s) { return Double.parseDouble(s); }

    /** Un campo no puede traer el separador ni un salto de línea. */
    private static String clean(String s) {
        return s == null ? "" : s.replace(';', ',').replace('\n', ' ').replace('\r', ' ');
    }
}
