package com.josexato.navaja.survey.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.josexato.navaja.cellular.core.CellReading;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.Test;

/** Núcleo del levantamiento: recorrido, habitación, registro y archivos de salida. */
public class SurveyTest {

    // ---------------------------------------------------------------- recorrido

    @Test
    public void planAxesLookFromAbove() {
        // En ARCore, adelante es −Z y arriba es +Y: caminar hacia adelante sube en el plano.
        double[] p = Track.plan(1, 1.4, -3);
        assertEquals(1, p[0], 1e-12);
        assertEquals(3, p[1], 1e-12);
        assertEquals(1.4, p[2], 1e-12);
    }

    @Test
    public void lengthIgnoresJitterAndGaps() {
        Track t = new Track();
        long ms = 0;
        // 5 m en línea recta, con un temblor de 5 mm
        for (int i = 0; i <= 50; i++) t.add(ms += 200, i * 0.1, (i % 2) * 0.005, 1.4, true);
        assertEquals(5.0, t.length(), 0.05);
        // quieto 10 s: no suma
        for (int i = 0; i < 50; i++) t.add(ms += 200, 5.0 + (i % 2) * 0.004, 0, 1.4, true);
        assertEquals(5.0, t.length(), 0.05);
        // se pierde el seguimiento y reaparece 3 m más allá: ese salto no se cuenta
        t.add(ms += 200, 5.0, 0, 1.4, false);
        t.add(ms += 200, 8.0, 0, 1.4, true);
        t.add(ms += 200, 8.5, 0, 1.4, true);
        assertEquals(5.5, t.length(), 0.05);
        assertEquals(2, t.lines(0.03).size());
        assertTrue(t.trackedShare() > 0.95 && t.trackedShare() < 1);
    }

    @Test
    public void positionAtAnInstant() {
        Track t = new Track();
        t.add(1000, 0, 0, 1, true);
        t.add(2000, 2, 4, 1, true);
        t.add(3000, 2, 4, 1, false);
        t.add(9000, 6, 4, 1, true);
        assertEquals(1.0, t.at(1500)[0], 1e-9);
        assertEquals(2.0, t.at(1500)[1], 1e-9);
        assertNull("antes de empezar", t.at(500));
        assertNull("sin seguimiento", t.at(2500));
        assertNull("hueco largo", t.at(6000));
        assertNotNull(t.at(9000));
        assertFalse("el reloj no retrocede", t.add(8000, 0, 0, 0, true));
    }

    @Test
    public void simplifyKeepsCorners() {
        Track t = new Track();
        long ms = 0;
        for (int i = 0; i <= 40; i++) t.add(ms += 200, i * 0.1, 0, 1, true);
        for (int i = 1; i <= 30; i++) t.add(ms += 200, 4.0, i * 0.1, 1, true);
        List<double[]> line = t.lines(0.03).get(0);
        assertEquals(3, line.size());
        assertEquals(4.0, line.get(1)[0], 1e-9);
        assertEquals(0.0, line.get(1)[1], 1e-9);
    }

    // --------------------------------------------------------------- habitación

    @Test
    public void roomAreaHeightAndVolume() {
        Room r = new Room("Sala");
        r.corner(0, 0);
        r.corner(4, 0);
        r.corner(4, 3);
        assertEquals(6.0, r.area(), 1e-9); // triángulo
        r.corner(0, 3);
        assertEquals(12.0, r.area(), 1e-9);
        assertEquals(14.0, r.perimeter(), 1e-9);
        assertTrue(Double.isNaN(r.height()));
        r.floorZ = -1.35;
        r.ceilingZ = 1.05;
        assertEquals(2.40, r.height(), 1e-9);
        assertEquals(28.8, r.volume(), 1e-9);
        assertEquals(2.0, r.centroid()[0], 1e-9);
        assertEquals(1.5, r.centroid()[1], 1e-9);
        assertTrue(r.contains(1, 1));
        assertFalse(r.contains(5, 1));
        assertFalse(r.selfIntersects());
    }

    @Test
    public void roomInAnyOrderAndShape() {
        // En L, marcada en sentido horario: el área no depende del sentido.
        Room l = new Room("L");
        double[][] pts = {{0, 0}, {0, 4}, {2, 4}, {2, 2}, {5, 2}, {5, 0}};
        for (double[] p : pts) l.corner(p[0], p[1]);
        assertEquals(14.0, l.area(), 1e-9);
        assertFalse(l.selfIntersects());
        assertFalse("el hueco de la L queda fuera", l.contains(4, 3));
        // Esquinas en desorden: las paredes se cruzan y se avisa.
        Room x = new Room("cruzada");
        x.corner(0, 0);
        x.corner(4, 3);
        x.corner(4, 0);
        x.corner(0, 3);
        assertTrue(x.selfIntersects());
        // Techo por debajo del piso: no es una altura.
        x.floorZ = 1;
        x.ceilingZ = 0;
        assertTrue(Double.isNaN(x.height()));
    }

    // ----------------------------------------------------------------- registro

    /** Un departamento de 4 × 3 m recorrido en círculo, con señal que empeora hacia un lado. */
    static SurveyLog sample() {
        SurveyLog log = new SurveyLog();
        log.start(1795608000000L, "Teléfono de prueba; ARCore 1.57");
        long t = 0;
        for (int i = 0; i <= 300; i++) {
            t = i * 200L;
            double a = 2 * Math.PI * i / 300;
            boolean ok = i < 150 || i > 160; // dos segundos sin seguimiento
            log.pose(t, 2 + 1.5 * Math.cos(a), 1.5 + 1.0 * Math.sin(a), 0.05 * Math.sin(a), Math.toDegrees(a) + 90, ok,
                    ok ? "" : "INSUFFICIENT_FEATURES");
            if (i % 5 == 0) {
                // El teléfono entrega la misma medición dos veces seguidas: cuenta una.
                long measured = (i / 10) * 2000L;
                CellReading c = cell(-80 - (int) Math.round(15 * (1 + Math.cos(a))), true);
                log.cell(t, 0, t - measured, c);
                log.cell(t, 0, t - measured, cell(-112, false)); // vecina
            }
            if (i % 50 == 0) log.pressure(t, 1009.3);
        }
        log.plane(1000, "p1", SurveyLog.FLOOR, -1.35, 2, 1.5, 9.0);
        log.plane(1200, "p2", SurveyLog.FLOOR, -0.60, 3, 2, 0.8);   // una mesa
        log.plane(1400, "p3", SurveyLog.CEILING, 1.05, 2, 1.5, 6.0);
        log.plane(1500, "p4", SurveyLog.CEILING, 0.80, 1, 1, 0.2);  // una viga chica
        log.plane(1600, "p5", SurveyLog.WALL, 0, 0, 1.5, 4.0);
        log.corner(5000, 0, 0, -1.36);
        log.corner(6000, 4, 0, -1.34);
        log.corner(7000, 4, 3, -1.35);
        log.corner(7500, 9, 9, -1.35);
        log.undoCorner(7600);
        log.corner(8000, 0, 3, -1.35);
        log.closeRoom(9000);
        return log;
    }

    static CellReading cell(int rsrp, boolean serving) {
        CellReading c = new CellReading();
        c.rat = CellReading.Rat.LTE;
        c.registered = serving;
        c.mcc = "716";
        c.mnc = "10";
        c.cellId = serving ? 12345678L : CellReading.NA_LONG;
        c.pci = serving ? 101 : 202;
        c.channel = 1900;
        c.reportedBands.add(2);
        c.rsrp = rsrp;
        c.rsrq = -10;
        c.sinr = 12;
        return c;
    }

    @Test
    public void readingsGetAPositionOnce() {
        SurveyLog log = sample();
        List<SurveyLog.Point> pts = log.points();
        // 31 mediciones distintas (una cada 2 s en 60 s); las dos que caen en el tramo sin seguimiento se descartan.
        assertEquals(29, pts.size());
        for (SurveyLog.Point p : pts) assertTrue(p.reading.cell.registered);
        assertEquals(2000, log.freshIntervalMs());
        assertEquals(Integer.valueOf(11), log.lost.get("INSUFFICIENT_FEATURES"));
    }

    @Test
    public void floorAndCeilingSkipSmallPlanes() {
        SurveyLog log = sample();
        assertEquals(-1.35, log.floorZ(), 1e-9);
        assertEquals(1.05, log.ceilingZ(), 1e-9);
        log.measureRooms();
        Room r = log.rooms.get(0);
        assertEquals(4, r.corners.size());
        assertEquals(12.0, r.area(), 1e-9);
        assertEquals(2.40, r.height(), 1e-9);
        assertNull(log.openRoom());
    }

    @Test
    public void textRoundTrip() {
        SurveyLog a = sample();
        SurveyLog b = SurveyLog.parse(a.text());
        assertEquals(a.text(), b.text());
        assertEquals(a.track.samples.size(), b.track.samples.size());
        assertEquals(a.points().size(), b.points().size());
        // El texto guarda milímetros: lo que se lee de él ya no cambia al volver a guardarlo.
        SurveyLog c = SurveyLog.parse(b.text());
        assertEquals(SurveyExport.csv(b), SurveyExport.csv(c));
        assertEquals(SurveyExport.plan(b).toString(), SurveyExport.plan(c).toString());
        assertEquals(SurveyExport.report(b), SurveyExport.report(c));
        // Un registro cortado a la mitad de una línea se lee hasta donde llega.
        String cut = a.text().substring(0, a.text().length() / 2);
        assertTrue(SurveyLog.parse(cut).track.samples.size() > 100);
    }

    @Test
    public void geoNeedsFixAndHeading() {
        SurveyLog log = sample();
        assertFalse(log.georeferenced());
        assertNull(SurveyExport.geoJson(log));
        log.geo(100, -12.0464, -77.0428, 12, 0, 0);
        log.geo(200, -12.0500, -77.0500, 30, 1, 1); // peor: se queda con el primero
        assertEquals(-12.0464, log.lat, 1e-12);
        assertFalse(log.georeferenced());
        log.north(300, 90); // el eje +y del plano apunta al este
        assertTrue(log.georeferenced());
        double[] east = log.geoOf(0, 100), south = log.geoOf(100, 0);
        assertEquals(-12.0464, east[0], 1e-6);
        assertEquals(100.0, (east[1] + 77.0428) * 111_195 * Math.cos(Math.toRadians(-12.0464)), 0.5);
        assertEquals(-100.0, (south[0] + 12.0464) * 111_195, 0.5);
        String g = SurveyExport.geoJson(log);
        assertTrue(g.contains("\"LineString\"") && g.contains("\"Polygon\"") && g.contains("\"area_m2\":12.00"));
    }

    // ----------------------------------------------------------------- archivos

    @Test
    public void csvHasOneRowPerMeasurement() {
        String csv = SurveyExport.csv(sample());
        String[] rows = csv.split("\n");
        assertEquals(30, rows.length);
        assertTrue(rows[0].startsWith("t_s,fecha_utc,x_m,y_m,alto_m,sim,red,plmn"));
        assertEquals(rows[0].split(",").length, rows[1].split(",", -1).length);
        assertTrue(rows[1].contains(",LTE,716-10,12345678,"));
    }

    @Test
    public void dxfIsWellFormed() {
        Dxf d = SurveyExport.plan(sample());
        String s = d.toString();
        String[] lines = s.split("\n", -1);
        assertEquals("pares código/valor", 1, lines.length % 2); // termina en salto de línea
        for (int i = 0; i + 1 < lines.length; i += 2) Integer.parseInt(lines[i].trim());
        assertTrue(s.startsWith("0\nSECTION\n2\nHEADER\n9\n$ACADVER\n1\nAC1009\n"));
        assertTrue(s.endsWith("0\nENDSEC\n0\nEOF\n"));
        assertEquals(count(s, "\nPOLYLINE\n"), count(s, "\nSEQEND\n"));
        assertEquals(29, count(s, "\nCIRCLE\n"));
        assertTrue(s.contains("Area 12.00 m2") && s.contains("Altura 2.40 m") && s.contains("4.00 m"));
        for (char ch : s.toCharArray()) assertTrue("sólo ASCII", ch < 127);
        assertEquals("HABITACION_1", Dxf.clean("Habitación 1"));
    }

    @Test
    public void reportSummarises() {
        String r = SurveyExport.report(sample());
        assertTrue(r, r.contains("Distancia caminada: 7."));
        assertTrue(r, r.contains("Una medición nueva cada: 2.0 s"));
        assertTrue(r, r.contains("| Habitación 1 | 4 | 12.00 | 14.00 | 2.40 | 28.8 |"));
        assertTrue(r, r.contains("sin seguimiento por INSUFFICIENT_FEATURES"));
    }

    /** Deja los archivos de ejemplo en build/ para abrirlos con otros programas. */
    @Test
    public void writeSampleFiles() throws Exception {
        SurveyLog log = sample();
        log.geo(100, -12.0464, -77.0428, 8, 0, 0);
        log.north(300, 20);
        File dir = new File("build/levantamiento-ejemplo");
        dir.mkdirs();
        write(new File(dir, "plano.dxf"), SurveyExport.plan(log).toString());
        write(new File(dir, "mediciones.csv"), SurveyExport.csv(log));
        write(new File(dir, "mapa.geojson"), SurveyExport.geoJson(log));
        write(new File(dir, "informe.md"), SurveyExport.report(log));
        write(new File(dir, "registro.txt"), log.text());
    }

    private static void write(File f, String s) throws Exception {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(s.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static int count(String s, String what) {
        int n = 0;
        for (int i = s.indexOf(what); i >= 0; i = s.indexOf(what, i + 1)) n++;
        return n;
    }
}
