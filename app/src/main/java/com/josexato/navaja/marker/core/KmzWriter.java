package com.josexato.navaja.marker.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * KML y KMZ de los waypoints. El KMZ es un ZIP con doc.kml como primera
 * entrada y las fotos en files/; cada punto con foto la muestra en su globo
 * (Google Earth, Google My Maps, QGIS…).
 */
public final class KmzWriter {

    private KmzWriter() { }

    /** Carpeta de las fotos dentro del KMZ. */
    public static final String PHOTO_DIR = "files/";

    /**
     * @param photos nombre de foto → bytes JPEG (ya reducidos si se quiere);
     *               null o sin entrada = el punto va sin foto.
     */
    public static byte[] kmz(String title, List<Waypoint> points, Map<String, byte[]> photos) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bos)) {
            zip.putNextEntry(new ZipEntry("doc.kml"));
            zip.write(kml(title, points, photos, true).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            if (photos != null) {
                for (Waypoint w : points) {
                    byte[] data = w.hasPhoto() ? photos.get(w.photo) : null;
                    if (data == null) continue;
                    // Las fotos ya están comprimidas: se guardan sin volver a comprimir.
                    ZipEntry e = new ZipEntry(PHOTO_DIR + w.photo);
                    e.setMethod(ZipEntry.STORED);
                    e.setSize(data.length);
                    e.setCompressedSize(data.length);
                    java.util.zip.CRC32 crc = new java.util.zip.CRC32();
                    crc.update(data);
                    e.setCrc(crc.getValue());
                    zip.putNextEntry(e);
                    zip.write(data);
                    zip.closeEntry();
                }
            }
        }
        return bos.toByteArray();
    }

    /** KML (si {@code embedded}, las imágenes apuntan a files/ dentro del KMZ). */
    public static String kml(String title, List<Waypoint> points, Map<String, byte[]> photos, boolean embedded) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n<Document>\n")
                .append("<name>").append(xml(title)).append("</name>\n")
                .append("<Style id=\"punto\"><IconStyle><Icon><href>http://maps.google.com/mapfiles/kml/paddle/red-circle.png</href></Icon></IconStyle></Style>\n")
                .append("<Style id=\"foto\"><IconStyle><Icon><href>http://maps.google.com/mapfiles/kml/shapes/camera.png</href></Icon></IconStyle></Style>\n");
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));
        SimpleDateFormat local = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US);
        for (Waypoint w : points) {
            boolean withPhoto = embedded && w.hasPhoto() && photos != null && photos.containsKey(w.photo);
            sb.append("<Placemark>\n<name>").append(xml(w.name == null ? "" : w.name)).append("</name>\n");
            sb.append("<styleUrl>#").append(w.hasPhoto() ? "foto" : "punto").append("</styleUrl>\n");
            StringBuilder d = new StringBuilder();
            if (withPhoto) d.append("<img src=\"").append(PHOTO_DIR).append(w.photo).append("\" width=\"480\"/><br/>");
            if (w.note != null && !w.note.isEmpty()) d.append(html(w.note).replace("\n", "<br/>")).append("<br/>");
            d.append(String.format(Locale.US, "%.6f, %.6f", w.lat, w.lon));
            if (!Double.isNaN(w.altM)) d.append(String.format(Locale.US, " · alt. %.0f m", w.altM));
            if (!Double.isNaN(w.accM)) d.append(String.format(Locale.US, " · ±%.0f m", w.accM));
            d.append("<br/>").append(local.format(new Date(w.timeMs)));
            sb.append("<description><![CDATA[").append(d.toString().replace("]]>", "]]&gt;")).append("]]></description>\n");
            sb.append("<TimeStamp><when>").append(iso.format(new Date(w.timeMs))).append("</when></TimeStamp>\n");
            sb.append("<Point><coordinates>")
                    .append(String.format(Locale.US, "%.7f,%.7f,%.1f", w.lon, w.lat, Double.isNaN(w.altM) ? 0 : w.altM))
                    .append("</coordinates></Point>\n</Placemark>\n");
        }
        sb.append("</Document>\n</kml>\n");
        return sb.toString();
    }

    /** GPX 1.1 (para apps de GPS y senderismo). */
    public static String gpx(String title, List<Waypoint> points) {
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<gpx version=\"1.1\" creator=\"Navaja\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
                .append("<metadata><name>").append(xml(title)).append("</name></metadata>\n");
        for (Waypoint w : points) {
            sb.append(String.format(Locale.US, "<wpt lat=\"%.7f\" lon=\"%.7f\">", w.lat, w.lon));
            if (!Double.isNaN(w.altM)) sb.append(String.format(Locale.US, "<ele>%.1f</ele>", w.altM));
            sb.append("<time>").append(iso.format(new Date(w.timeMs))).append("</time>");
            sb.append("<name>").append(xml(w.name == null ? "" : w.name)).append("</name>");
            if (w.note != null && !w.note.isEmpty()) sb.append("<desc>").append(xml(w.note)).append("</desc>");
            sb.append("</wpt>\n");
        }
        return sb.append("</gpx>\n").toString();
    }

    static String xml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** Texto del usuario dentro del HTML del globo: se escapa (no se interpreta como HTML). */
    static String html(String s) { return xml(s); }
}
