package com.josexato.navaja.cellular.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.Test;

/** Núcleo de Red móvil: bandas 3GPP, identificadores, señal, registro y estimación de torres. */
public class CellularTest {

    // ------------------------------------------------------------------ bandas

    @Test
    public void lteBandEdgesAndFrequencies() {
        int[][] edges = {{0, 1}, {599, 1}, {600, 2}, {1200, 3}, {1949, 3}, {1950, 4}, {2400, 5}, {2750, 7},
                {3450, 8}, {6150, 20}, {6449, 20}, {9210, 28}, {9659, 28}, {37750, 38}, {66436, 66}, {68586, 71}};
        for (int[] e : edges) assertEquals("EARFCN " + e[0], Integer.valueOf(e[1]), BandCatalog.lte(e[0]).band());
        // Rangos corregidos respecto de la propuesta: B20 termina en 6449, B28 cubre 758–803 MHz.
        assertFalse(BandCatalog.lte(6450).bands.contains(20));
        assertEquals(21, (int) BandCatalog.lte(6450).band());
        assertEquals(803.0 - 0.1, BandCatalog.lte(9659).dlMhz, 1e-9);
        assertEquals(1805.0, BandCatalog.lte(1200).dlMhz, 1e-9);
        assertEquals(2140.0, BandCatalog.lte(300).dlMhz, 1e-9);
        assertEquals(806.0, BandCatalog.lte(6300).dlMhz, 1e-9);
        assertTrue(BandCatalog.lte(6300).label.startsWith("B20 (800 DD) · 806.0 MHz"));
        assertNull(BandCatalog.lte(10500)); // hueco sin banda
    }

    @Test
    public void nrArfcnRasterAndBands() {
        assertEquals(3500.0, BandCatalog.nrArfcnToMhz(633333), 0.01);
        assertEquals(780.0, BandCatalog.nrArfcnToMhz(156000), 1e-9);
        assertEquals(2140.0, BandCatalog.nrArfcnToMhz(428000), 1e-9);
        assertEquals(24250.08, BandCatalog.nrArfcnToMhz(2016667), 1e-9);
        // n28 empieza en 151600 (758 MHz), no en 140000.
        assertEquals(28, (int) BandCatalog.nr(151600).band());
        assertNull(BandCatalog.nr(140000));
        // n78 dentro de n77: se prefiere n78, pero n77 queda como candidata.
        BandCatalog.Info c = BandCatalog.nr(633333);
        assertEquals(78, (int) c.band());
        assertTrue(c.bands.contains(77));
        // 3,9 GHz sólo es n77.
        assertEquals(77, (int) BandCatalog.nr(660000).band());
        // 2140 MHz: n1 (y n66 como candidata).
        BandCatalog.Info n1 = BandCatalog.nr(428000);
        assertEquals(1, (int) n1.band());
        assertTrue(n1.bands.contains(66));
    }

    @Test
    public void reportedBandWinsOverChannel() {
        CellReading c = new CellReading();
        c.rat = CellReading.Rat.LTE;
        c.channel = 2000;                 // B4 por EARFCN…
        c.reportedBands.add(66);          // …pero el módem dice B66
        assertTrue(c.bandLabel(), c.bandLabel().startsWith("B66"));
        c.reportedBands.clear();
        assertTrue(c.bandLabel().startsWith("B4"));
    }

    // ------------------------------------------------------------ identificadores

    @Test
    public void enbAndSector() {
        assertEquals(14289, CellIds.enb(3657987));
        assertEquals(3, CellIds.lteSector(3657987));
        long eci = (123456L << 8) | 21;
        assertEquals(123456, CellIds.enb(eci));
        assertEquals(21, CellIds.lteSector(eci));
    }

    @Test
    public void gnbLengthIsConfigurable() {
        long nci = (0x123456L << 12) | 0x0AB; // gNB de 24 bits + celda de 12 bits
        assertEquals(0x123456L, CellIds.gnb(nci, 24));
        assertEquals(0x0AB, CellIds.nrSector(nci, 24));
        // El mismo NCI leído con 22 bits da otra torre: por eso es un parámetro.
        assertEquals(0x123456L >> 2, CellIds.gnb(nci, 22));
        try {
            CellIds.gnb(nci, 40);
            throw new AssertionError("debió rechazar 40 bits");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    // ------------------------------------------------------------------- señal

    @Test
    public void timingAdvance() {
        assertEquals(78.07, SignalMetrics.LTE_TA_METERS, 0.01);
        assertEquals(0, SignalMetrics.lteTaMeters(0), 1e-9);              // TA 0 es válido: < 78 m
        assertEquals(78.07, SignalMetrics.lteTaMeters(1), 0.01);
        assertEquals(312.3, SignalMetrics.lteTaMeters(4), 0.1);
        assertTrue(Double.isNaN(SignalMetrics.lteTaMeters(CellReading.NA))); // ausente
        assertTrue(Double.isNaN(SignalMetrics.lteTaMeters(1283)));
    }

    @Test
    public void qualityLevels() {
        assertEquals(SignalMetrics.Level.EXCELLENT, SignalMetrics.rsrp(-84));
        assertEquals(SignalMetrics.Level.GOOD, SignalMetrics.rsrp(-85));
        assertEquals(SignalMetrics.Level.FAIR, SignalMetrics.rsrp(-110));
        assertEquals(SignalMetrics.Level.POOR, SignalMetrics.rsrp(-111));
        assertEquals(SignalMetrics.Level.UNKNOWN, SignalMetrics.rsrp(CellReading.NA));
        assertEquals(SignalMetrics.Level.EXCELLENT, SignalMetrics.sinr(18));
        assertEquals(SignalMetrics.Level.POOR, SignalMetrics.sinr(-1));
        assertEquals(SignalMetrics.Level.FAIR, SignalMetrics.rsrq(-12));
        assertEquals("256QAM", SignalMetrics.cqiModulation(14));
        assertEquals("QPSK", SignalMetrics.cqiModulation(3));
        assertNull(SignalMetrics.cqiModulation(CellReading.NA));
    }

    @Test
    public void rssnrUnitsAreNormalized() {
        assertEquals(18, SignalMetrics.normalizeRssnr(18));     // ya en dB
        assertEquals(19, SignalMetrics.normalizeRssnr(185));    // décimas de dB
        assertEquals(-12, SignalMetrics.normalizeRssnr(-120));
        assertEquals(CellReading.NA, SignalMetrics.normalizeRssnr(5000));
        assertEquals(CellReading.NA, SignalMetrics.normalizeRssnr(CellReading.NA));
    }

    // ------------------------------------------------------ torres y registro

    /** Torre en (lat0, lon0); el teléfono recorre una calle en L y mide TA cuantizado. */
    static DriveLog syntheticDrive(double towerLat, double towerLon, boolean straight, long seed) {
        DriveLog log = new DriveLog();
        Random rnd = new Random(seed);
        double kx = Math.toRadians(1) * 6_371_000 * Math.cos(Math.toRadians(towerLat)), ky = Math.toRadians(1) * 6_371_000;
        for (int i = 0; i < 60; i++) {
            // Recorrido: 600 m hacia el este y luego 600 m hacia el norte (o sólo el tramo recto).
            double ex, ny;
            if (straight || i < 30) { ex = -900 + 20 * i; ny = -400; }
            else { ex = -900 + 20 * 30; ny = -400 + 20 * (i - 30); }
            double gpsErr = 5;
            double mx = ex + rnd.nextGaussian() * gpsErr, my = ny + rnd.nextGaussian() * gpsErr;
            double dist = Math.hypot(ex, ny);
            int ta = (int) Math.round(dist / SignalMetrics.LTE_TA_METERS);
            CellReading c = new CellReading();
            c.rat = CellReading.Rat.LTE;
            c.mcc = "716";
            c.mnc = "10";
            c.cellId = (54321L << 8) | 2;
            c.pci = 101;
            c.channel = 9410;
            c.rsrp = -80 - (int) (dist / 30);
            c.timingAdvance = ta;
            c.taMeters = SignalMetrics.lteTaMeters(ta);
            log.add(new DriveLog.Point(1_760_000_000_000L + i * 1000L, towerLat + my / ky, towerLon + mx / kx, gpsErr, c));
        }
        return log;
    }

    @Test
    public void towerIsLocatedFromTaAndGps() {
        double tLat = -12.0464, tLon = -77.0428; // Lima
        DriveLog log = syntheticDrive(tLat, tLon, false, 1);
        Map<String, TowerLocator.Estimate> towers = log.towers();
        assertEquals(1, towers.size());
        TowerLocator.Estimate e = towers.get("LTE 716-10 eNB 54321");
        assertNotNull(e);
        double err = TowerLocator.distanceM(e.lat, e.lon, tLat, tLon);
        assertTrue("error " + err + " m", err < 60);
        assertFalse(e.ambiguous);
        assertEquals(60, e.used);
        assertTrue(e.rmsM < 40);
    }

    @Test
    public void straightRoadIsFlaggedAmbiguous() {
        DriveLog log = syntheticDrive(-33.4489, -70.6693, true, 2); // Santiago
        TowerLocator.Estimate e = log.towers().values().iterator().next();
        assertTrue(e.ambiguous);
    }

    @Test
    public void notEnoughDataGivesNoEstimate() {
        List<TowerLocator.Obs> few = new ArrayList<>();
        for (int i = 0; i < 3; i++) few.add(new TowerLocator.Obs(-12, -77, 5, 500, 78));
        assertNull(TowerLocator.locate(few));
        List<TowerLocator.Obs> still = new ArrayList<>();
        for (int i = 0; i < 10; i++) still.add(new TowerLocator.Obs(-12, -77, 5, 500, 78)); // sin moverse
        assertNull(TowerLocator.locate(still));
    }

    @Test
    public void badGpsIsDiscarded() {
        DriveLog log = new DriveLog();
        CellReading c = new CellReading();
        c.rat = CellReading.Rat.LTE;
        assertFalse(log.add(new DriveLog.Point(0, -12, -77, 120, c)));
        assertFalse(log.add(new DriveLog.Point(0, -12, -77, 5, null)));
        assertTrue(log.add(new DriveLog.Point(0, -12, -77, 5, c)));
    }

    @Test
    public void csvAndKmlExport() {
        DriveLog log = syntheticDrive(-12.0464, -77.0428, false, 3);
        String csv = log.csv();
        String[] lines = csv.split("\n");
        assertEquals(61, lines.length);
        assertTrue(lines[0].startsWith("fecha_utc,lat,lon,precision_m,red,plmn,cell_id,enb_gnb,sector"));
        String[] f = lines[1].split(",", -1);
        assertEquals(lines[0].split(",").length, f.length);
        assertEquals("LTE", f[4]);
        assertEquals("716-10", f[5]);
        assertEquals("54321", f[7]);
        assertEquals("2", f[8]);
        assertTrue(lines[1].contains("B28 (700 APT)"));

        String kml = log.kml("Recorrido");
        assertTrue(kml.startsWith("<?xml"));
        assertTrue(kml.contains("<kml xmlns=\"http://www.opengis.net/kml/2.2\">"));
        assertEquals(60 + 1, kml.split("<Placemark>").length - 1); // 60 mediciones + 1 torre
        assertTrue(kml.contains("Torres estimadas"));
        assertTrue(kml.contains("LTE 716-10 eNB 54321"));
        assertTrue(kml.trim().endsWith("</kml>"));
        assertEquals("ffa0e76e", DriveLog.abgr(0xFF6EE7A0));
        assertEquals("a &amp; b &lt;c&gt;", DriveLog.xml("a & b <c>"));
        // Sin datos: el recorrido sigue siendo un KML válido.
        assertTrue(new DriveLog().kml("vacío").contains("</Document></kml>"));
        assertEquals(Arrays.asList("fecha_utc"), Arrays.asList(new DriveLog().csv().split(",")[0]));
    }
}
