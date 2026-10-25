package com.josexato.navaja.sign;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import com.josexato.navaja.sign.core.Annex;
import com.josexato.navaja.sign.core.Bytes;
import com.josexato.navaja.sign.core.Ecdsa;
import com.josexato.navaja.sign.core.Ledger;
import com.josexato.navaja.sign.core.PdfFile;
import com.josexato.navaja.sign.core.QrCode;
import com.josexato.navaja.sign.core.Signer;
import com.josexato.navaja.sign.core.Ssjf;
import com.josexato.navaja.sign.core.Stamper;
import com.josexato.navaja.sign.core.TimeAnchors;
import com.josexato.navaja.sign.core.Verifier;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class SignCoreTest {

    // ------------------------------------------------------------ utilidades

    @Test
    public void base64PropioIgualAlDeJava() {
        Random r = new Random(1);
        for (int n = 0; n < 70; n++) {
            byte[] b = new byte[n];
            r.nextBytes(b);
            assertEquals(Base64.getEncoder().encodeToString(b), Bytes.b64(b));
            assertEquals(Base64.getUrlEncoder().withoutPadding().encodeToString(b), Bytes.b64url(b));
            assertArrayEquals(b, Bytes.unb64(Bytes.b64(b)));
            assertArrayEquals(b, Bytes.unb64url(Bytes.b64url(b)));
        }
    }

    // ------------------------------------------------------------------- QR

    static String decodeQr(QrCode q, int scale) throws Exception {
        int quiet = 4, n = (q.size + 2 * quiet) * scale;
        int[] px = new int[n * n];
        Arrays.fill(px, 0xFFFFFFFF);
        for (int y = 0; y < q.size; y++)
            for (int x = 0; x < q.size; x++)
                if (q.dark(x, y))
                    for (int dy = 0; dy < scale; dy++)
                        for (int dx = 0; dx < scale; dx++) px[((y + quiet) * scale + dy) * n + (x + quiet) * scale + dx] = 0xFF000000;
        LuminanceSource src = new RGBLuminanceSource(n, n, px);
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.PURE_BARCODE, Boolean.TRUE);
        hints.put(DecodeHintType.CHARACTER_SET, "UTF-8");
        return new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(src)), hints).getText();
    }

    @Test
    public void qrDecodificableEnVariasVersionesYNiveles() throws Exception {
        Random r = new Random(7);
        String abc = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_;=:/#.";
        int[] lens = {1, 10, 17, 40, 77, 120, 200, 260, 304, 333, 400, 700, 1200};
        for (QrCode.Ecc e : QrCode.Ecc.values()) {
            for (int len : lens) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < len; i++) sb.append(abc.charAt(r.nextInt(abc.length())));
                String s = sb.toString();
                QrCode q;
                try {
                    q = QrCode.encode(s, e);
                } catch (IllegalArgumentException tooBig) {
                    continue;
                }
                assertEquals("v" + q.version + " " + e, s, decodeQr(q, 3));
            }
        }
        assertEquals("ñandú ✓", decodeQr(QrCode.encode("ñandú ✓", QrCode.Ecc.M), 3));
    }

    // ----------------------------------------------------------- ECDSA/SSJF

    static KeyPair keys() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        return g.generateKeyPair();
    }

    static Signer.KeySigner jcaSigner(KeyPair kp) {
        PrivateKey sk = kp.getPrivate();
        return msg -> {
            Signature s = Signature.getInstance("SHA256withECDSA");
            s.initSign(sk);
            s.update(msg);
            return s.sign();
        };
    }

    @Test
    public void clavesComprimidasYFirmaCruda() throws Exception {
        for (int i = 0; i < 10; i++) {
            KeyPair kp = keys();
            byte[] c = Ecdsa.compress(kp.getPublic());
            assertEquals(33, c.length);
            assertEquals(kp.getPublic(), Ecdsa.decompress(c));
            byte[] msg = ("hola " + i).getBytes(StandardCharsets.UTF_8);
            byte[] raw = Ecdsa.derToRaw(jcaSigner(kp).sign(msg));
            assertEquals(64, raw.length);
            assertTrue(Ecdsa.verify(kp.getPublic(), msg, raw));
            raw[10] ^= 1;
            assertFalse(Ecdsa.verify(kp.getPublic(), msg, raw));
        }
        assertEquals(16, Ecdsa.keyId(Ecdsa.compress(keys().getPublic())).length());
    }

    @Test
    public void qrSsjfIdaYVuelta() throws Exception {
        KeyPair kp = keys();
        byte[] pk = Ecdsa.compress(kp.getPublic());
        Ssjf.Claim c = new Ssjf.Claim();
        c.url = Ssjf.DEFAULT_URL;
        c.h0 = Bytes.hex(Bytes.sha256("doc".getBytes(StandardCharsets.UTF_8)));
        c.n0 = 3;
        c.t = 1761400000L;
        c.btcHeight = 921345;
        c.btcHash = "000000000000000000017a81affd3819";
        c.kid = Ecdsa.keyId(pk);
        c.sig = Ecdsa.derToRaw(jcaSigner(kp).sign(c.signedBytes()));
        String qr = c.qrText();
        assertTrue(qr.length() < 321); // cabe en un QR versión 11-M
        Ssjf.Claim p = Ssjf.parse("Escaneado:\n" + qr + "\n");
        assertEquals(Ssjf.DEFAULT_URL, p.url);
        assertEquals(c.signedPart(), p.signedPart());
        assertTrue(p.verify(pk));
        // Un dígito cambiado en el resumen invalida la firma.
        String forged = qr.replace("h=" + c.h0.charAt(0), "h=" + (c.h0.charAt(0) == 'a' ? 'b' : 'a'));
        assertFalse(Ssjf.parse(forged).verify(pk));
        // Campos fuera de orden: rechazado (lo firmado debe reproducirse exacto).
        try {
            Ssjf.parse("SSJF1;n=3;h=" + c.h0 + ";t=1;k=x;s=" + Bytes.b64url(new byte[64]));
            fail();
        } catch (Ssjf.FormatException expected) {
            // ok
        }
        assertFalse(Ssjf.parse(qr).verify(Ecdsa.compress(keys().getPublic())));
    }

    // ------------------------------------------------------------------ PDF

    /** PDF mínimo con xref clásica, Resources y MediaBox heredados del nodo /Pages, y Contents como array. */
    static byte[] classicPdf() {
        String[] objs = {
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 612 792] /Resources << /Font << /F1 5 0 R >> >> >>",
            "<< /Type /Page /Parent 2 0 R /Contents [4 0 R] /Annots [] >>",
            null,
            "<< /Type /Font /Subtype /Type1 /BaseFont /Times-Roman >>",
        };
        String content = "BT /F1 24 Tf 72 700 Td (Hola \\(mundo\\)) Tj ET";
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        w(o, "%PDF-1.4\n%âãÏÓ\n");
        long[] off = new long[objs.length + 1];
        for (int i = 0; i < objs.length; i++) {
            off[i + 1] = o.size();
            w(o, (i + 1) + " 0 obj\n");
            if (objs[i] == null) w(o, "<< /Length " + content.length() + " >>\nstream\n" + content + "\nendstream");
            else w(o, objs[i]);
            w(o, "\nendobj\n");
        }
        long x = o.size();
        w(o, "xref\n0 " + (objs.length + 1) + "\n0000000000 65535 f \n");
        for (int i = 1; i <= objs.length; i++) w(o, String.format("%010d 00000 n \n", off[i]));
        w(o, "trailer\n<< /Size " + (objs.length + 1) + " /Root 1 0 R >>\nstartxref\n" + x + "\n%%EOF\n");
        return o.toByteArray();
    }

    /** Deja copias en build/ssjf-out para revisarlas con qpdf/pdfium/ots fuera de los tests. */
    static void dump(String name, byte[] b) throws IOException {
        java.io.File d = new java.io.File("build/ssjf-out");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        java.nio.file.Files.write(new java.io.File(d, name).toPath(), b);
    }

    static void w(ByteArrayOutputStream o, String s) {
        byte[] b = s.getBytes(StandardCharsets.ISO_8859_1);
        o.write(b, 0, b.length);
    }

    static byte[] resource(String name) throws IOException {
        try (InputStream in = SignCoreTest.class.getResourceAsStream("/ssjf/" + name)) {
            assertNotNull(name, in);
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            return o.toByteArray();
        }
    }

    static Stamper.Ink ink() {
        List<float[]> strokes = new ArrayList<>();
        float[] s = new float[60];
        for (int i = 0; i < 20; i++) {
            s[3 * i] = 0.05f + i * 0.045f;
            s[3 * i + 1] = 0.5f + 0.3f * (float) Math.sin(i * 0.7);
            s[3 * i + 2] = 0.2f + i * 0.04f;
        }
        strokes.add(s);
        strokes.add(new float[]{0.5f, 0.2f, 0.6f});
        return new Stamper.Ink(strokes, 2.5f);
    }

    @Test
    public void lectorPdfEntiendeAmbosXref() throws Exception {
        PdfFile a = new PdfFile(classicPdf());
        assertFalse(a.xrefStream);
        assertEquals(1, a.pages().size());
        assertEquals(612, a.pages().get(0).mediaBox[2], 0);
        assertNotNull(a.pages().get(0).resources.get("Font"));
        PdfFile b = new PdfFile(resource("xrefstream.pdf"));
        assertTrue(b.xrefStream);
        assertEquals(2, b.pages().size());
        assertEquals(90, b.pages().get(1).rotate);
        assertEquals(842, Stamper.visualSize(b.pages().get(1))[0], 0);
    }

    static Signer.Request request(byte[] pdf, int page) {
        Signer.Request r = new Signer.Request();
        r.pdf = pdf;
        r.page = page;
        r.u = 0.45;
        r.v = 0.8;
        r.w = 0.5;
        r.ink = ink();
        r.lines = new String[]{"Firmado 25/10/2026 · SSJF v1", "Verificar: github.com/Josexato/navaja/tree/main/docs/ssjf"};
        r.unix = 1761400000L;
        r.btc = new TimeAnchors.Block(921345, "000000000000000000017a81affd38193f5d776267ad294b7dd941aef5c1488b");
        return r;
    }

    @Test
    public void firmaYVerificacionCompletas() throws Exception {
        KeyPair kp = keys();
        byte[] pk = Ecdsa.compress(kp.getPublic());
        Verifier.KeyRing ring = new Verifier.KeyRing().add(pk, "yo");
        for (byte[] orig : new byte[][]{classicPdf(), resource("xrefstream.pdf")}) {
            for (int page = 0; page < new PdfFile(orig).pages().size(); page++) {
                Ledger ledger = new Ledger();
                Signer.Result res = Signer.sign(request(orig, page), pk, jcaSigner(kp), null);
                byte[] signed = res.pdf;
                dump("firmado_" + (orig.length) + "_p" + page + ".pdf", signed);
                // El original queda intacto al inicio del archivo.
                assertArrayEquals(orig, Arrays.copyOf(signed, orig.length));
                assertEquals(Bytes.hex(Bytes.sha256(orig)), res.annex.h0);
                assertEquals(orig.length, res.annex.n0);
                // El PDF firmado se vuelve a leer y la página tiene el sello.
                PdfFile re = new PdfFile(signed);
                assertEquals(new PdfFile(orig).pages().size(), re.pages().size());
                assertNotNull(re.catalog().get("SSJF"));
                Ledger.Entry e = Signer.record(ledger, res, null, null);
                Verifier.Report rep = Verifier.verifyPdf(signed, ring, ledger);
                assertEquals(1, rep.checks.size());
                Verifier.Check c = rep.checks.get(0);
                assertEquals(Verifier.Verdict.MINE, c.verdict);
                assertEquals(0, c.unsignedBytesAfter);
                assertEquals(e.seq(), c.entry.seq());
                assertEquals(e, rep.sameAsSaved);
                // El original sin firmar se reconoce.
                assertEquals(e, Verifier.verifyPdf(orig, ring, ledger).originalOf);
                // El QR (sin el PDF) también verifica.
                assertEquals(Verifier.Verdict.MINE, Verifier.verifyQr(res.claim.qrText(), ring, ledger).verdict);
                // Sin historial: firma mía pero no registrada.
                assertEquals(Verifier.Verdict.MINE_NOT_IN_LEDGER, Verifier.verifyPdf(signed, ring, new Ledger()).checks.get(0).verdict);
                // Con otra clave como "mía": la firma es de otra persona.
                Verifier.KeyRing other = new Verifier.KeyRing().add(Ecdsa.compress(keys().getPublic()), "yo");
                assertEquals(Verifier.Verdict.OTHER_KEY, Verifier.verifyPdf(signed, other, ledger).checks.get(0).verdict);
            }
        }
    }

    @Test
    public void detectaAlteracionesYCambiosPosteriores() throws Exception {
        KeyPair kp = keys();
        byte[] pk = Ecdsa.compress(kp.getPublic());
        Verifier.KeyRing ring = new Verifier.KeyRing().add(pk, "yo");
        byte[] orig = classicPdf();
        Signer.Result res = Signer.sign(request(orig, 0), pk, jcaSigner(kp), null);
        Ledger ledger = new Ledger();
        Signer.record(ledger, res, null, null);

        // 1) Cambio dentro del documento original (antes de n0): no es el documento firmado.
        byte[] t1 = res.pdf.clone();
        int at = Bytes.indexOf(t1, "Hola".getBytes(StandardCharsets.ISO_8859_1), 0);
        t1[at] = 'J';
        assertEquals(Verifier.Verdict.DOC_MISMATCH, Verifier.verifyPdf(t1, ring, ledger).checks.get(0).verdict);

        // 2) Cambio en el sello (entre n0 y n1).
        byte[] t2 = res.pdf.clone();
        t2[(int) ((res.annex.n0 + res.annex.n1) / 2)] ^= 1;
        assertEquals(Verifier.Verdict.STAMP_ALTERED, Verifier.verifyPdf(t2, ring, ledger).checks.get(0).verdict);

        // 3) QR falsificado en el anexo: otro resumen con la misma firma.
        String json = res.annex.toJson();
        String fakeH = Bytes.hex(Bytes.sha256("otro".getBytes(StandardCharsets.UTF_8)));
        byte[] t3 = new String(res.pdf, StandardCharsets.ISO_8859_1).replace("h=" + res.annex.h0, "h=" + fakeH)
                .getBytes(StandardCharsets.ISO_8859_1);
        assertTrue(json.contains(res.annex.h0));
        assertEquals(Verifier.Verdict.BAD_SIGNATURE, Verifier.verifyPdf(t3, ring, ledger).checks.get(0).verdict);

        // 4) Alguien agrega una actualización incremental después de firmar: la firma sigue válida
        //    para la versión firmada, pero se avisa y se puede extraer esa versión.
        PdfFile pf = new PdfFile(res.pdf);
        com.josexato.navaja.sign.core.PdfUpdate up = new com.josexato.navaja.sign.core.PdfUpdate(pf);
        up.add(new com.josexato.navaja.sign.core.Pdf.Stream(new com.josexato.navaja.sign.core.Pdf.Dict(),
                "BT /F1 40 Tf 100 100 Td (PAGARE) Tj ET".getBytes(StandardCharsets.ISO_8859_1)));
        byte[] t4 = up.write();
        Verifier.Check c4 = Verifier.verifyPdf(t4, ring, ledger).checks.get(0);
        assertEquals(Verifier.Verdict.MINE, c4.verdict);
        assertTrue(c4.unsignedBytesAfter > 0);
        assertArrayEquals(orig, Verifier.prefix(t4, c4.annex.n0));

        // 5) Segunda firma sobre el ya firmado: ambas válidas y sin "cambios sin firmar".
        Signer.Result res2 = Signer.sign(request(res.pdf, 0), pk, jcaSigner(kp), null);
        Signer.record(ledger, res2, null, null);
        Verifier.Report r5 = Verifier.verifyPdf(res2.pdf, ring, ledger);
        assertEquals(2, r5.checks.size());
        for (Verifier.Check c : r5.checks) {
            assertEquals(Verifier.Verdict.MINE, c.verdict);
            assertEquals(0, c.unsignedBytesAfter);
        }
        assertTrue(r5.checks.get(0).bytesAfter > 0);
        assertEquals(1, Annex.scan(res.pdf).size());
    }

    // ------------------------------------------------------------ historial

    @Test
    public void historialEncadenadoConDatosPrivados() throws Exception {
        Ledger l = new Ledger();
        Map<String, Object> pub = new LinkedHashMap<>();
        pub.put("h0", "aa");
        Map<String, String> priv = new LinkedHashMap<>();
        priv.put("file", "contrato.pdf");
        priv.put("comment", "Firma en la notaría\u0007");
        priv.put("lat", "-12.046374");
        l.append(Ledger.TYPE_SIGN, 1L, pub, priv);
        l.append(Ledger.TYPE_SIGN, 2L, pub, null);
        Map<String, Object> proof = new LinkedHashMap<>();
        proof.put("ref", 1L);
        l.append(Ledger.TYPE_PROOF, 3L, proof, null);
        assertNull(l.verifyChain());
        assertEquals("Firma en la notaría", l.entries().get(0).privS("comment"));
        dump("historial.jsonl", l.toJsonl(true).getBytes(StandardCharsets.UTF_8));

        // Ida y vuelta por texto: misma cadena.
        Ledger full = Ledger.parse(l.toJsonl(true));
        assertNull(full.verifyChain());
        assertEquals(l.head(), full.head());
        // La copia pública también verifica sola y no contiene los datos privados.
        String pubText = l.toJsonl(false);
        assertFalse(pubText.contains("contrato.pdf"));
        assertFalse(pubText.contains("salt"));
        assertNull(Ledger.parse(pubText).verifyChain());
        assertEquals(l.head(), Ledger.parse(pubText).head());
        assertEquals(1, l.proofsFor(1).size());

        // Borrar una entrada o cambiar un dato privado se detecta.
        String[] lines = l.toJsonl(true).split("\n");
        assertNotNull(Ledger.parse(lines[0] + "\n" + lines[2]).verifyChain());
        assertNotNull(Ledger.parse(l.toJsonl(true).replace("contrato.pdf", "otro.pdf")).verifyChain());
    }

    // ------------------------------------------------------ sellos de tiempo

    @Test
    public void solicitudRfc3161IgualAOpenssl() {
        // openssl ts -query -data <(printf hola) -sha256 -cert -no_nonce | xxd -p
        String expected = "30390201013031300d060960864801650304020105000420"
                + "b221d9dbb083a7f33428d7c2a3c3198ae925614d70210e28716ccaa7cd4ddb790101ff";
        byte[] h = Bytes.sha256("hola".getBytes(StandardCharsets.UTF_8));
        assertEquals(expected, Bytes.hex(TimeAnchors.tsaRequest(h)));
    }

    @Test
    public void leeRespuestaRealDeFreeTsa() throws Exception {
        TimeAnchors.TsaInfo info = TimeAnchors.parseTsaResponse(resource("freetsa_hola.tsr"));
        assertEquals(0, info.status);
        assertArrayEquals(Bytes.sha256("hola".getBytes(StandardCharsets.UTF_8)), info.imprint);
        assertTrue(info.genTime, info.genTime.startsWith("2026"));
    }

    @Test
    public void archivoOtsConCabeceraYNonce() throws Exception {
        byte[] h = Bytes.sha256("h1".getBytes(StandardCharsets.UTF_8));
        byte[] nonce = new byte[16];
        Arrays.fill(nonce, (byte) 7);
        byte[] ots = TimeAnchors.otsFile(h, nonce, resource("calendar_response.bin"));
        assertEquals(0x00, ots[0]);
        assertEquals("OpenTimestamps", new String(ots, 1, 14, StandardCharsets.US_ASCII));
        assertEquals(0x01, ots[31]);  // versión
        assertEquals(0x08, ots[32]);  // sha256
        assertArrayEquals(h, Arrays.copyOfRange(ots, 33, 65));
        assertEquals((byte) 0xf0, ots[65]); // append(nonce)
        dump("prueba.ots", ots);
        dump("prueba.h1", h);
    }
}
