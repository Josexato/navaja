package com.josexato.navaja.sign.core;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * SSJF v1 (Simple Signature JSON Format): el contenido del QR que va junto a
 * la firma dibujada. Especificación completa en docs/ssjf/SSJF_v1.md.
 *
 * <pre>
 * [URL#]SSJF1;h=&lt;sha256 hex&gt;;n=&lt;bytes&gt;;t=&lt;unix&gt;[;b=&lt;altura&gt;-&lt;hash32&gt;];k=&lt;kid&gt;;s=&lt;firma&gt;
 * </pre>
 *
 * La firma ECDSA P-256/SHA-256 (r‖s en base64url) cubre exactamente los bytes
 * UTF-8 desde "SSJF1" hasta antes de ";s=". El prefijo URL (opcional) sólo
 * sirve para que cualquier cámara abra la guía de verificación; el fragmento
 * (#…) nunca se envía al servidor.
 */
public final class Ssjf {

    private Ssjf() { }

    public static final String MAGIC = "SSJF1";
    /** Guía pública de verificación (lo que abre el QR al escanearlo con cualquier cámara). */
    public static final String DEFAULT_URL = "https://github.com/Josexato/navaja/tree/main/docs/ssjf";

    public static final class Claim {
        public String url;          // puede ser null
        public String h0;           // SHA-256 hex del documento antes de firmar
        public long n0;             // largo en bytes del documento antes de firmar
        public long t;              // segundos Unix (UTC), declarado por el firmante
        public long btcHeight = -1; // bloque Bitcoin más reciente al firmar (prueba "no antes de")
        public String btcHash;      // primeros 32 hex del hash de ese bloque
        public String kid;          // identificador de la clave pública
        public byte[] sig;          // r‖s, 64 bytes

        public String signedPart() {
            StringBuilder sb = new StringBuilder(MAGIC).append(";h=").append(h0).append(";n=").append(n0).append(";t=").append(t);
            if (btcHeight >= 0 && btcHash != null) sb.append(";b=").append(btcHeight).append('-').append(btcHash);
            return sb.append(";k=").append(kid).toString();
        }

        public byte[] signedBytes() { return signedPart().getBytes(StandardCharsets.UTF_8); }

        /** Texto completo del QR. */
        public String qrText() {
            String core = signedPart() + ";s=" + Bytes.b64url(sig);
            return url == null || url.isEmpty() ? core : url + "#" + core;
        }

        public boolean verify(PublicKey pk) { return sig != null && Ecdsa.verify(pk, signedBytes(), sig); }

        public boolean verify(byte[] compressedPk) {
            try {
                return Ecdsa.keyId(compressedPk).equals(kid) && verify(Ecdsa.decompress(compressedPk));
            } catch (GeneralSecurityException e) {
                return false;
            }
        }
    }

    public static final class FormatException extends Exception {
        public FormatException(String m) { super(m); }
    }

    /**
     * Interpreta el texto de un QR SSJF (con o sin URL delante, con espacios o
     * saltos de línea alrededor, p. ej. al pegarlo desde otra app).
     */
    public static Claim parse(String text) throws FormatException {
        if (text == null) throw new FormatException("Texto vacío");
        int at = text.indexOf(MAGIC + ";");
        if (at < 0) throw new FormatException("No es un QR de firma SSJF");
        String core = text.substring(at).trim();
        int end = 0;
        while (end < core.length() && !Character.isWhitespace(core.charAt(end))) end++;
        core = core.substring(0, end);
        Claim c = new Claim();
        if (at > 0) {
            String pre = text.substring(0, at).trim();
            if (pre.endsWith("#")) pre = pre.substring(0, pre.length() - 1);
            int sp = Math.max(pre.lastIndexOf(' '), pre.lastIndexOf('\n'));
            c.url = pre.substring(sp + 1);
        }
        Map<String, String> f = new LinkedHashMap<>();
        String[] parts = core.split(";");
        for (int i = 1; i < parts.length; i++) {
            int eq = parts[i].indexOf('=');
            if (eq <= 0) throw new FormatException("Campo inválido: " + parts[i]);
            String k = parts[i].substring(0, eq);
            if (f.put(k, parts[i].substring(eq + 1)) != null) throw new FormatException("Campo repetido: " + k);
        }
        c.h0 = f.get("h");
        if (!Bytes.isHex(c.h0, 64)) throw new FormatException("Resumen h inválido");
        try {
            c.n0 = Long.parseLong(req(f, "n"));
            c.t = Long.parseLong(req(f, "t"));
            String b = f.get("b");
            if (b != null) {
                int dash = b.indexOf('-');
                if (dash <= 0) throw new FormatException("Bloque b inválido");
                c.btcHeight = Long.parseLong(b.substring(0, dash));
                c.btcHash = b.substring(dash + 1);
                if (!Bytes.isHex(c.btcHash, 32)) throw new FormatException("Hash de bloque inválido");
            }
        } catch (NumberFormatException e) {
            throw new FormatException("Número inválido en el QR");
        }
        c.kid = req(f, "k");
        try {
            c.sig = Bytes.unb64url(req(f, "s"));
        } catch (IllegalArgumentException e) {
            throw new FormatException("Firma s inválida");
        }
        if (c.sig.length != 64) throw new FormatException("La firma debe tener 64 bytes");
        // El orden de campos es parte del formato: lo firmado debe reproducirse exacto.
        String expect = c.signedPart() + ";s=" + Bytes.b64url(c.sig);
        if (!expect.equals(core)) throw new FormatException("Campos fuera de orden o desconocidos");
        return c;
    }

    private static String req(Map<String, String> f, String k) throws FormatException {
        String v = f.get(k);
        if (v == null || v.isEmpty()) throw new FormatException("Falta el campo " + k);
        return v;
    }

    /** Fecha UTC legible (ISO 8601) de un instante Unix. */
    public static String iso(long unix) {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return f.format(new java.util.Date(unix * 1000));
    }
}
