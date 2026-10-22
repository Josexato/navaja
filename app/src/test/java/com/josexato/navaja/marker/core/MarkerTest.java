package com.josexato.navaja.marker.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.Test;

/** Marcador: EXIF GPS, guardado JSON y exportación KML/KMZ/GPX. */
public class MarkerTest {

    static Waypoint wp(String id, String name, double lat, double lon, String photo) {
        Waypoint w = new Waypoint();
        w.id = id;
        w.name = name;
        w.lat = lat;
        w.lon = lon;
        w.altM = 154.3;
        w.accM = 4.8;
        w.timeMs = 1_761_150_000_000L;
        w.photo = photo;
        return w;
    }

    @Test
    public void exifDmsRoundTrip() {
        // Lima, Plaza de Armas: −12.046374, −77.042793
        String lat = ExifGps.dms(-12.046374), lon = ExifGps.dms(-77.042793);
        assertEquals("12/1,2/1,46946/1000", lat);
        assertEquals("S", ExifGps.latRef(-12.046374));
        assertEquals("W", ExifGps.lonRef(-77.042793));
        assertEquals(12.046374, ExifGps.parseDms(lat), 1e-6);
        assertEquals(77.042793, ExifGps.parseDms(lon), 1e-6);
        assertEquals("N", ExifGps.latRef(40.4));
        assertEquals("E", ExifGps.lonRef(2.17));
    }

    @Test
    public void exifCarriesRoundingToTheNextMinute() {
        // 10° 59' 59.9999" se redondea a 11° 0' 0".
        double v = 10 + 59 / 60.0 + 59.9999 / 3600;
        assertEquals("11/1,0/1,0/1000", ExifGps.dms(v));
        assertEquals("0/1,0/1,0/1000", ExifGps.dms(0));
    }

    @Test
    public void exifAltitudeAndTime() {
        assertEquals("1543/10", ExifGps.altitude(154.3));
        assertEquals("0", ExifGps.altitudeRef(154.3));
        assertEquals("1", ExifGps.altitudeRef(-20));
        assertEquals("200/10", ExifGps.altitude(-20));
        String[] dt = ExifGps.dateTime(1_761_150_000_000L); // 2025-10-22 16:20:00 UTC
        assertEquals("2025:10:22", dt[0]);
        assertEquals("16/1,20/1,0/1", dt[1]);
    }

    @Test
    public void jsonRoundTrip() throws Exception {
        Waypoint a = wp("w1", "Poste 12 \"esquina\"", -12.0464, -77.0428, "foto_1.jpg");
        a.note = "Fibra cortada\nrevisar caja";
        Waypoint b = wp("w2", "Antena", -33.4489, -70.6693, null);
        b.altM = Double.NaN;
        b.accM = Double.NaN;
        List<Waypoint> back = Waypoint.fromJson(Waypoint.toJson(Arrays.asList(a, b)));
        assertEquals(2, back.size());
        assertEquals(a.name, back.get(0).name);
        assertEquals(a.note, back.get(0).note);
        assertEquals(a.timeMs, back.get(0).timeMs);
        assertEquals(a.lat, back.get(0).lat, 1e-9);
        assertEquals("foto_1.jpg", back.get(0).photo);
        assertTrue(Double.isNaN(back.get(1).altM));
        assertFalse(back.get(1).hasPhoto());
        // Puntos sin coordenadas válidas se descartan; JSON roto lanza error.
        assertEquals(0, Waypoint.fromJson("{\"waypoints\":[{\"id\":\"x\",\"lat\":200,\"lon\":0}]}").size());
        assertEquals(0, Waypoint.fromJson("[]").size());
    }

    @Test
    public void kmzContainsKmlFirstAndPhotos() throws Exception {
        byte[] jpg = new byte[5000];
        for (int i = 0; i < jpg.length; i++) jpg[i] = (byte) (i * 31);
        Waypoint a = wp("w1", "Poste <12> & caja", -12.0464, -77.0428, "foto_1.jpg");
        a.note = "Nota con ]]> y <b>html</b>";
        Waypoint b = wp("w2", "Sin foto", -12.05, -77.04, null);
        Waypoint c = wp("w3", "Foto perdida", -12.06, -77.03, "no_existe.jpg");
        Map<String, byte[]> photos = new HashMap<>();
        photos.put("foto_1.jpg", jpg);
        byte[] kmz = KmzWriter.kmz("Visita técnica", Arrays.asList(a, b, c), photos);

        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(kmz))) {
            for (ZipEntry e; (e = zin.getNextEntry()) != null; ) {
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                for (int n; (n = zin.read(buf)) > 0; ) o.write(buf, 0, n);
                entries.put(e.getName(), o.toByteArray());
            }
        }
        List<String> names = new ArrayList<>(entries.keySet());
        assertEquals(Arrays.asList("doc.kml", "files/foto_1.jpg"), names); // doc.kml primero
        assertArrayEquals(jpg, entries.get("files/foto_1.jpg"));

        String kml = new String(entries.get("doc.kml"), StandardCharsets.UTF_8);
        assertTrue(kml.startsWith("<?xml"));
        assertEquals(3, kml.split("<Placemark>").length - 1);
        assertTrue(kml.contains("<name>Poste &lt;12&gt; &amp; caja</name>"));
        assertTrue(kml.contains("<img src=\"files/foto_1.jpg\""));
        assertFalse(kml.contains("no_existe.jpg\" width")); // foto ausente: sin <img>
        assertTrue(kml.contains("&lt;b&gt;html&lt;/b&gt;"));   // la nota no se interpreta como HTML
        assertEquals(3, kml.split("]]></description>").length - 1); // el ]]> de la nota no rompe el CDATA
        assertTrue(kml.contains("<coordinates>-77.0428000,-12.0464000,154.3</coordinates>"));
        assertTrue(kml.contains("<when>2025-10-22T16:20:00Z</when>"));
        assertTrue(kml.trim().endsWith("</kml>"));
    }

    @Test
    public void gpxExport() {
        Waypoint a = wp("w1", "Cumbre", -13.163, -72.545, null);
        a.note = "Machu Picchu";
        String gpx = KmzWriter.gpx("Ruta", Arrays.asList(a));
        assertTrue(gpx.contains("<gpx version=\"1.1\""));
        assertTrue(gpx.contains("<wpt lat=\"-13.1630000\" lon=\"-72.5450000\"><ele>154.3</ele>"));
        assertTrue(gpx.contains("<desc>Machu Picchu</desc>"));
        assertNotNull(gpx);
    }
}
