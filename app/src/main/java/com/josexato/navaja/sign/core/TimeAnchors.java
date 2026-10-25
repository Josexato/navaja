package com.josexato.navaja.sign.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Anclas de tiempo. A estos servicios sólo viajan resúmenes de 32 bytes:
 * el documento nunca sale del teléfono.
 * <ul>
 *   <li><b>Bitcoin</b> (no antes de): el hash del último bloque es
 *   impredecible, así que una firma que lo incluye no pudo hacerse antes de
 *   que ese bloque existiera. Sólo se LEE de un explorador público.</li>
 *   <li><b>RFC 3161</b> (no después de): una autoridad de sellado firma
 *   "este resumen existía a tal hora". Se verifica con {@code openssl ts}.</li>
 *   <li><b>OpenTimestamps</b> (no después de): el resumen queda anclado en
 *   una transacción Bitcoin en unas horas; se verifica con el cliente
 *   {@code ots} sin confiar en nadie.</li>
 * </ul>
 */
public final class TimeAnchors {

    private TimeAnchors() { }

    // ------------------------------------------------------------- Bitcoin

    public static final String[] BTC_APIS = {"https://mempool.space/api", "https://blockstream.info/api"};

    public static final class Block {
        public final long height;
        public final String hash;

        public Block(long height, String hash) { this.height = height; this.hash = hash; }
    }

    /** Último bloque (altura + hash completo) desde el primer explorador que responda. */
    public static Block bitcoinTip() throws IOException {
        IOException last = null;
        for (String api : BTC_APIS) {
            try {
                String hash = Http.request("GET", api + "/blocks/tip/hash", null, null).text().trim();
                if (!Bytes.isHex(hash, 64) || !hash.startsWith("0000000000")) throw new IOException("hash de bloque inválido");
                String json = Http.request("GET", api + "/block/" + hash, null, null).text();
                long h = jsonLong(json, "height");
                if (h < 800_000) throw new IOException("altura inválida");
                return new Block(h, hash);
            } catch (IOException e) {
                last = e;
            }
        }
        throw last;
    }

    static long jsonLong(String json, String key) throws IOException {
        int i = json.indexOf("\"" + key + "\"");
        if (i < 0) throw new IOException("falta " + key);
        i = json.indexOf(':', i) + 1;
        while (i < json.length() && json.charAt(i) == ' ') i++;
        int j = i;
        while (j < json.length() && Character.isDigit(json.charAt(j))) j++;
        try {
            return Long.parseLong(json.substring(i, j));
        } catch (NumberFormatException e) {
            throw new IOException("número inválido en " + key);
        }
    }

    // ------------------------------------------------------------ RFC 3161

    public static final String[] TSA_URLS = {"https://freetsa.org/tsr", "http://timestamp.digicert.com"};

    /** TimeStampReq DER (versión 1, SHA-256, sin nonce, certReq = true) como {@code openssl ts -query -sha256 -cert -no_nonce}. */
    public static byte[] tsaRequest(byte[] sha256) {
        byte[] alg = der(0x30, Bytes.concat(der(0x06, OID_SHA256), new byte[]{0x05, 0x00}));
        byte[] imprint = der(0x30, Bytes.concat(alg, der(0x04, sha256)));
        return der(0x30, Bytes.concat(new byte[]{0x02, 0x01, 0x01}, imprint, new byte[]{0x01, 0x01, (byte) 0xFF}));
    }

    public static final class TsaResult {
        public final String url;
        public final byte[] tsr;
        public final String genTime;

        TsaResult(String url, byte[] tsr, String genTime) { this.url = url; this.tsr = tsr; this.genTime = genTime; }
    }

    public static TsaResult timestampRfc3161(byte[] sha256) throws IOException {
        IOException last = null;
        Map<String, String> h = new HashMap<>();
        h.put("Content-Type", "application/timestamp-query");
        h.put("Accept", "application/timestamp-reply");
        for (String url : TSA_URLS) {
            try {
                Http.Response r = Http.request("POST", url, h, tsaRequest(sha256));
                if (!r.ok()) throw new IOException("HTTP " + r.code);
                TsaInfo info = parseTsaResponse(r.body);
                if (!Arrays.equals(info.imprint, sha256)) throw new IOException("la respuesta sella otro resumen");
                return new TsaResult(url, r.body, info.genTime);
            } catch (IOException e) {
                last = e;
            }
        }
        throw last;
    }

    public static final class TsaInfo {
        public int status;
        public byte[] imprint;
        public String genTime;
    }

    /** Lee estado, resumen sellado y hora de un TimeStampResp. No valida la cadena de certificados (eso, con openssl). */
    public static TsaInfo parseTsaResponse(byte[] resp) throws IOException {
        TsaInfo info = new TsaInfo();
        Der top = new Der(resp, 0).enter();       // TimeStampResp
        Der status = top.copy().enter();           // PKIStatusInfo
        info.status = status.integer().intValue();
        if (info.status != 0 && info.status != 1) throw new IOException("TSA rechazó la solicitud (estado " + info.status + ")");
        int at = Bytes.indexOf(resp, der(0x06, OID_TSTINFO), 0);
        if (at < 0) throw new IOException("respuesta sin TSTInfo");
        Der d = new Der(resp, at);
        d.skip();                                  // OID id-ct-TSTInfo
        d = d.enter();                             // [0] EXPLICIT
        d = d.enterOctets();                       // OCTET STRING
        Der tst = d.enter();                       // TSTInfo
        tst.skip();                                // version
        tst.skip();                                // policy
        Der mi = tst.copy().enter();               // messageImprint
        mi.skip();                                 // algoritmo
        info.imprint = mi.octets();
        tst.skip();
        tst.skip();                                // serialNumber
        info.genTime = new String(tst.value(0x18), StandardCharsets.US_ASCII);
        return info;
    }

    static final byte[] OID_SHA256 = {0x60, (byte) 0x86, 0x48, 0x01, 0x65, 0x03, 0x04, 0x02, 0x01};
    static final byte[] OID_TSTINFO = {0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0x0D, 0x01, 0x09, 0x10, 0x01, 0x04};

    static byte[] der(int tag, byte[] content) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(tag);
        int n = content.length;
        if (n < 128) o.write(n);
        else if (n < 256) { o.write(0x81); o.write(n); }
        else { o.write(0x82); o.write(n >> 8); o.write(n & 255); }
        o.write(content, 0, n);
        return o.toByteArray();
    }

    /** Lector DER mínimo (cursor sobre una secuencia de TLV). */
    static final class Der {
        final byte[] b;
        int p;
        final int end;

        Der(byte[] b, int p) { this(b, p, b.length); }

        Der(byte[] b, int p, int end) { this.b = b; this.p = p; this.end = end; }

        Der copy() { return new Der(b, p, end); }

        private int[] header() throws IOException {
            if (p + 2 > end) throw new IOException("DER truncado");
            int tag = b[p] & 0xFF, q = p + 1, len = b[q++] & 0xFF;
            if (len >= 0x80) {
                int k = len & 0x7F;
                if (k > 3) throw new IOException("DER demasiado largo");
                len = 0;
                for (int i = 0; i < k; i++) len = len << 8 | (b[q++] & 0xFF);
            }
            if (q + len > end) throw new IOException("DER truncado");
            return new int[]{tag, q, len};
        }

        /** Entra al contenido del TLV actual. */
        Der enter() throws IOException {
            int[] h = header();
            return new Der(b, h[1], h[1] + h[2]);
        }

        Der enterOctets() throws IOException {
            int[] h = header();
            if (h[0] != 0x04) throw new IOException("se esperaba OCTET STRING");
            return new Der(b, h[1], h[1] + h[2]);
        }

        void skip() throws IOException {
            int[] h = header();
            p = h[1] + h[2];
        }

        byte[] value(int tag) throws IOException {
            int[] h = header();
            if (h[0] != tag) throw new IOException("DER: tag " + h[0] + " en vez de " + tag);
            p = h[1] + h[2];
            return Arrays.copyOfRange(b, h[1], h[1] + h[2]);
        }

        BigInteger integer() throws IOException { return new BigInteger(value(0x02)); }

        byte[] octets() throws IOException { return value(0x04); }
    }

    // ------------------------------------------------------ OpenTimestamps

    public static final String[] OTS_CALENDARS = {"https://a.pool.opentimestamps.org", "https://b.pool.opentimestamps.org",
        "https://a.pool.eternitywall.com"};

    static final byte[] OTS_MAGIC = Bytes.concat(new byte[]{0x00}, "OpenTimestamps".getBytes(StandardCharsets.US_ASCII),
            new byte[]{0x00, 0x00}, "Proof".getBytes(StandardCharsets.US_ASCII),
            new byte[]{0x00, (byte) 0xbf, (byte) 0x89, (byte) 0xe2, (byte) 0xe8, (byte) 0x84, (byte) 0xe8, (byte) 0x92, (byte) 0x94});

    public static final class OtsResult {
        public final String calendar;
        public final byte[] ots;

        OtsResult(String calendar, byte[] ots) { this.calendar = calendar; this.ots = ots; }
    }

    /**
     * Envía SHA-256(resumen ‖ nonce) a un calendario y arma el archivo .ots
     * (pendiente; se completa con {@code ots upgrade} cuando el calendario
     * lo incluya en un bloque, en pocas horas). El nonce evita que el
     * calendario vea el resumen real.
     */
    public static OtsResult timestampOts(byte[] sha256) throws IOException {
        byte[] nonce = new byte[16];
        new SecureRandom().nextBytes(nonce);
        byte[] commit = Bytes.sha256(Bytes.concat(sha256, nonce));
        Map<String, String> h = new HashMap<>();
        h.put("Accept", "application/vnd.opentimestamps.v1");
        h.put("Content-Type", "application/x-www-form-urlencoded");
        IOException last = null;
        for (String cal : OTS_CALENDARS) {
            try {
                Http.Response r = Http.request("POST", cal + "/digest", h, commit);
                if (!r.ok() || r.body.length == 0) throw new IOException("HTTP " + r.code);
                return new OtsResult(cal, otsFile(sha256, nonce, r.body));
            } catch (IOException e) {
                last = e;
            }
        }
        throw last;
    }

    /** Archivo .ots: cabecera, versión 1, SHA-256 del "archivo" (h1), append(nonce), sha256 y la respuesta del calendario. */
    public static byte[] otsFile(byte[] sha256, byte[] nonce, byte[] calendarTimestamp) {
        return Bytes.concat(OTS_MAGIC, new byte[]{0x01, 0x08}, sha256,
                new byte[]{(byte) 0xf0, (byte) nonce.length}, nonce, new byte[]{0x08}, calendarTimestamp);
    }
}
