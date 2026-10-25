package com.josexato.navaja.sign.core;

import com.josexato.navaja.pantonera.core.Json;
import com.josexato.navaja.sign.core.Pdf.Dict;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Anexo SSJF: objeto JSON agregado al final del PDF DESPUÉS de dibujar el
 * sello. Lleva lo que no puede ir en el QR sin circularidad: el resumen del
 * documento ya sellado (h1) y los sellos de tiempo sobre ese resumen. No
 * contiene metadatos personales (nombre de archivo, ubicación, comentario:
 * eso vive sólo en el historial del firmante).
 */
public final class Annex {

    /** Comentario PDF justo antes del objeto anexo, para encontrarlo sin interpretar el PDF. */
    public static final String MARKER = "SSJF-anexo-v1";

    public String qr;
    public String h0, h1;
    public long n0, n1;
    /** Clave pública comprimida (33 bytes) en base64url. */
    public String pk;
    /** Firma del sello (r‖s base64url) sobre {@link Ssjf#sealMessage}: el firmante avala D1, no sólo D0. */
    public String seal;
    public long btcHeight = -1;
    public String btcHash;
    /** Respuesta RFC 3161 (TimeStampResp DER) en base64, sobre h1. */
    public String tsaUrl, tsr;
    /** Archivo .ots (OpenTimestamps) en base64, sobre h1. */
    public String otsCalendar, ots;

    public String toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ssjf", 1L);
        m.put("qr", qr);
        m.put("h0", h0);
        m.put("n0", n0);
        m.put("h1", h1);
        m.put("n1", n1);
        m.put("pk", pk);
        if (seal != null) m.put("seal_sig", seal);
        if (btcHeight >= 0) {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("height", btcHeight);
            b.put("hash", btcHash);
            m.put("btc", b);
        }
        if (tsr != null) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("url", tsaUrl);
            t.put("tsr", tsr);
            m.put("rfc3161", t);
        }
        if (ots != null) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("calendar", otsCalendar);
            o.put("ots", ots);
            m.put("opentimestamps", o);
        }
        m.put("guia", Ssjf.DEFAULT_URL);
        return Json.write(m);
    }

    @SuppressWarnings("unchecked")
    public static Annex fromJson(String s) throws Json.ParseException {
        Map<String, Object> m = (Map<String, Object>) Json.parse(s);
        Annex a = new Annex();
        a.qr = str(m.get("qr"));
        a.h0 = str(m.get("h0"));
        a.h1 = str(m.get("h1"));
        a.n0 = lng(m.get("n0"));
        a.n1 = lng(m.get("n1"));
        a.pk = str(m.get("pk"));
        a.seal = str(m.get("seal_sig"));
        if (m.get("btc") instanceof Map) {
            Map<String, Object> b = (Map<String, Object>) m.get("btc");
            a.btcHeight = lng(b.get("height"));
            a.btcHash = str(b.get("hash"));
        }
        if (m.get("rfc3161") instanceof Map) {
            Map<String, Object> t = (Map<String, Object>) m.get("rfc3161");
            a.tsaUrl = str(t.get("url"));
            a.tsr = str(t.get("tsr"));
        }
        if (m.get("opentimestamps") instanceof Map) {
            Map<String, Object> o = (Map<String, Object>) m.get("opentimestamps");
            a.otsCalendar = str(o.get("calendar"));
            a.ots = str(o.get("ots"));
        }
        return a;
    }

    private static String str(Object o) { return o instanceof String ? (String) o : null; }

    private static long lng(Object o) { return o instanceof Number ? ((Number) o).longValue() : -1; }

    /** Un anexo hallado en un archivo. */
    public static final class Found {
        public final int offset;
        /** Byte siguiente al %%EOF de la actualización que contiene el anexo. */
        public final int end;
        public final Annex annex;
        public final String error;

        Found(int offset, int end, Annex annex, String error) {
            this.offset = offset;
            this.end = end;
            this.annex = annex;
            this.error = error;
        }
    }

    /** Busca anexos por su comentario marcador, en orden de aparición. */
    public static List<Found> scan(byte[] pdf) {
        List<Found> out = new ArrayList<>();
        byte[] mk = ("%" + MARKER).getBytes(StandardCharsets.ISO_8859_1);
        for (int at = Bytes.indexOf(pdf, mk, 0); at >= 0; at = Bytes.indexOf(pdf, mk, at + mk.length)) {
            int eof = Bytes.indexOf(pdf, "%%EOF".getBytes(StandardCharsets.ISO_8859_1), at);
            int end = eof < 0 ? pdf.length : eof + 5;
            while (end < pdf.length && (pdf[end] == '\r' || pdf[end] == '\n')) end++;
            try {
                PdfFile.Lexer l = new PdfFile.Lexer(pdf, at + mk.length);
                l.next();
                l.next();
                if (!"obj".equals(l.keyword())) throw new IllegalStateException("objeto");
                Object d = l.next();
                if (!(d instanceof Dict) || !(((Dict) d).get("Length") instanceof Long) || !"stream".equals(l.keyword()))
                    throw new IllegalStateException("stream");
                int p = l.pos;
                if (pdf[p] == '\r') p++;
                if (pdf[p] == '\n') p++;
                int len = (int) (long) (Long) ((Dict) d).get("Length");
                if (len < 0 || p + len > pdf.length) throw new IllegalStateException("largo");
                String json = new String(pdf, p, len, StandardCharsets.UTF_8);
                out.add(new Found(at, end, fromJson(json), null));
            } catch (Exception e) {
                out.add(new Found(at, end, null, "Anexo ilegible: " + e.getMessage()));
            }
        }
        return out;
    }
}
