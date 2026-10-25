package com.josexato.navaja.sign.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Orquesta una firma SSJF completa:
 * <ol>
 *   <li>h0 = SHA-256 del PDF tal como llegó (n0 bytes).</li>
 *   <li>QR = h0, n0, hora, bloque Bitcoin (opcional), id de clave y la firma
 *   ECDSA de todo eso con la clave del teléfono.</li>
 *   <li>Actualización incremental 1: el sello visible (firma + QR + enlace).
 *   h1 = SHA-256 del resultado (n1 bytes).</li>
 *   <li>Sellos de tiempo sobre h1 (opcional, sólo viaja el resumen).</li>
 *   <li>Actualización incremental 2: el anexo JSON. h2 = archivo final.</li>
 * </ol>
 */
public final class Signer {

    private Signer() { }

    /** Firma con la clave privada (Android Keystore en la app, JCA en los tests); devuelve DER. */
    public interface KeySigner {
        byte[] sign(byte[] msg) throws Exception;
    }

    /** Servicios de sellado de tiempo; null = sin conexión. */
    public interface Stamps {
        TimeAnchors.TsaResult rfc3161(byte[] sha256) throws IOException;

        TimeAnchors.OtsResult ots(byte[] sha256) throws IOException;
    }

    public static final Stamps ONLINE = new Stamps() {
        @Override public TimeAnchors.TsaResult rfc3161(byte[] h) throws IOException { return TimeAnchors.timestampRfc3161(h); }

        @Override public TimeAnchors.OtsResult ots(byte[] h) throws IOException { return TimeAnchors.timestampOts(h); }
    };

    public static final class Request {
        public byte[] pdf;
        public int page;
        public double u, v, w;
        public Stamper.Ink ink;
        public int inkRgb = 0x14307A;
        public String[] lines;
        public long unix;
        public TimeAnchors.Block btc;
        public String url = Ssjf.DEFAULT_URL;
    }

    public static final class Result {
        public byte[] pdf;
        public Ssjf.Claim claim;
        public Annex annex;
        public String h2;
        public long n2, unix;
        public final List<String> warnings = new ArrayList<>();
    }

    public static Result sign(Request r, byte[] pk, KeySigner key, Stamps stamps) throws Exception {
        PdfFile f0 = new PdfFile(r.pdf);
        Ssjf.Claim c = new Ssjf.Claim();
        c.url = r.url;
        c.h0 = Bytes.hex(Bytes.sha256(r.pdf));
        c.n0 = r.pdf.length;
        c.t = r.unix;
        if (r.btc != null) {
            c.btcHeight = r.btc.height;
            c.btcHash = r.btc.hash.substring(0, 32);
        }
        c.kid = Ecdsa.keyId(pk);
        c.rev = 2;
        c.sig = Ecdsa.derToRaw(key.sign(c.signedBytes()));
        if (!c.verify(pk)) throw new IllegalStateException("La firma recién hecha no verifica con la clave pública");

        Stamper.Spec s = new Stamper.Spec();
        s.page = r.page;
        s.u = r.u;
        s.v = r.v;
        s.w = r.w;
        s.ink = r.ink;
        s.ink_rgb = r.inkRgb;
        s.qrText = c.qrText();
        s.lines = r.lines;
        s.linkUrl = r.url;
        byte[] d1 = Stamper.stamp(f0, s);

        Result res = new Result();
        res.claim = c;
        res.unix = r.unix;
        Annex a = new Annex();
        a.qr = c.qrText();
        a.h0 = c.h0;
        a.n0 = c.n0;
        a.h1 = Bytes.hex(Bytes.sha256(d1));
        a.n1 = d1.length;
        a.pk = Bytes.b64url(pk);
        // Segunda firma, ahora sobre la versión sellada: sin ella, alguien podría reutilizar el QR con otra D1.
        byte[] seal = Ecdsa.derToRaw(key.sign(Ssjf.sealMessage(a.h0, a.h1, a.n1, c.kid).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        if (!Ecdsa.verify(Ecdsa.decompress(pk), Ssjf.sealMessage(a.h0, a.h1, a.n1, c.kid).getBytes(java.nio.charset.StandardCharsets.UTF_8), seal))
            throw new IllegalStateException("La firma del sello no verifica con la clave pública");
        a.seal = Bytes.b64url(seal);
        if (r.btc != null) {
            a.btcHeight = r.btc.height;
            a.btcHash = r.btc.hash;
        }
        if (stamps != null) {
            byte[] h1 = Bytes.unhex(a.h1);
            try {
                TimeAnchors.TsaResult t = stamps.rfc3161(h1);
                a.tsaUrl = t.url;
                a.tsr = Bytes.b64(t.tsr);
            } catch (IOException e) {
                res.warnings.add("Sello RFC 3161 no disponible: " + e.getMessage());
            }
            try {
                TimeAnchors.OtsResult o = stamps.ots(h1);
                a.otsCalendar = o.calendar;
                a.ots = Bytes.b64(o.ots);
            } catch (IOException e) {
                res.warnings.add("OpenTimestamps no disponible: " + e.getMessage());
            }
        }
        res.annex = a;
        res.pdf = Stamper.addAnnex(new PdfFile(d1), a.toJson());
        res.h2 = Bytes.hex(Bytes.sha256(res.pdf));
        res.n2 = res.pdf.length;
        return res;
    }

    /** Agrega la firma al historial. {@code priv}: nombre de archivo, comentario, ubicación (sólo local). */
    public static Ledger.Entry record(Ledger l, Result r, Map<String, String> priv, String note) {
        Annex a = r.annex;
        Map<String, Object> pub = new LinkedHashMap<>();
        pub.put("h0", a.h0);
        pub.put("n0", a.n0);
        pub.put("h1", a.h1);
        pub.put("n1", a.n1);
        pub.put("h2", r.h2);
        pub.put("n2", r.n2);
        pub.put("qr", a.qr);
        pub.put("pk", a.pk);
        if (a.btcHeight >= 0) {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("height", a.btcHeight);
            b.put("hash", a.btcHash);
            pub.put("btc", b);
        }
        if (a.tsr != null) pub.put("rfc3161", a.tsr);
        if (a.ots != null) pub.put("ots", a.ots);
        if (note != null) pub.put("note", note);
        return l.append(Ledger.TYPE_SIGN, r.unix, pub, priv);
    }
}
