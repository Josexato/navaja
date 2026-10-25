package com.josexato.navaja.sign.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Responde "¿esto lo firmé yo?" para un PDF o para el texto de un QR.
 * Comprueba, en este orden: formato, que los primeros n0 bytes den h0 (el
 * documento es el que se firmó), que los primeros n1 den h1 (el sello no
 * se tocó), la firma ECDSA con la clave pública, si la clave es mía, si la
 * firma está en mi historial, y si se agregó algo después de firmar.
 */
public final class Verifier {

    private Verifier() { }

    public enum Verdict {
        /** Firma válida, con mi clave y registrada en mi historial. */
        MINE,
        /** Firma válida con mi clave, pero no está en el historial (¿historial perdido o de otro teléfono?). */
        MINE_NOT_IN_LEDGER,
        /** Firma matemáticamente válida pero con una clave que no es mía. */
        OTHER_KEY,
        /** La firma no corresponde a los datos: QR falsificado o alterado. */
        BAD_SIGNATURE,
        /** El documento no es el que se firmó (los primeros n0 bytes no dan h0). */
        DOC_MISMATCH,
        /** El documento original coincide, pero el sello o el anexo se modificaron. */
        STAMP_ALTERED,
        /** Datos ilegibles. */
        MALFORMED
    }

    /** Claves conocidas: kid → clave comprimida, y cuáles son mías. */
    public static final class KeyRing {
        final Map<String, byte[]> keys = new HashMap<>();
        final Map<String, String> owners = new HashMap<>();

        public KeyRing add(byte[] compressed, String owner) {
            String kid = Ecdsa.keyId(compressed);
            keys.put(kid, compressed);
            owners.put(kid, owner);
            return this;
        }

        public boolean isMine(String kid) { return "yo".equals(owners.get(kid)); }

        public String owner(String kid) { return owners.get(kid); }

        public byte[] get(String kid) { return keys.get(kid); }
    }

    public static final class Check {
        public Verdict verdict;
        public Ssjf.Claim claim;
        public Annex annex;
        public boolean prefixOk, stampOk, sigOk, mine;
        public Ledger.Entry entry;
        /** Entrada "sello" del historial con el OpenTimestamps ya anclado en Bitcoin, si existe. */
        public Ledger.Entry otsProof;
        /** Bytes agregados después de esta firma que NO son otra firma SSJF (cambios sin firmar). */
        public long unsignedBytesAfter;
        /** Bytes agregados después de esta firma (incluye firmas SSJF posteriores). */
        public long bytesAfter;
        public int annexEnd;
        public String detail;

        public boolean valid() { return verdict == Verdict.MINE || verdict == Verdict.MINE_NOT_IN_LEDGER || verdict == Verdict.OTHER_KEY; }
    }

    public static final class Report {
        public final List<Check> checks = new ArrayList<>();
        public String fileHash;
        /** Si el archivo entero es el original (sin firmar) de una firma mía. */
        public Ledger.Entry originalOf;
        /** Si el archivo entero es idéntico a un PDF firmado que guardé. */
        public Ledger.Entry sameAsSaved;
    }

    public static Report verifyPdf(byte[] f, KeyRing keys, Ledger ledger) {
        Report r = new Report();
        r.fileHash = Bytes.hex(Bytes.sha256(f));
        if (ledger != null) {
            r.originalOf = ledger.findHash("h0", r.fileHash);
            r.sameAsSaved = ledger.findHash("h2", r.fileHash);
        }
        List<Annex.Found> found = Annex.scan(f);
        for (int i = 0; i < found.size(); i++) {
            Annex.Found fd = found.get(i);
            Check c = new Check();
            c.annexEnd = fd.end;
            r.checks.add(c);
            if (fd.annex == null) {
                c.verdict = Verdict.MALFORMED;
                c.detail = fd.error;
                continue;
            }
            Annex a = fd.annex;
            c.annex = a;
            try {
                c.claim = Ssjf.parse(a.qr);
            } catch (Ssjf.FormatException e) {
                c.verdict = Verdict.MALFORMED;
                c.detail = e.getMessage();
                continue;
            }
            c.prefixOk = a.n0 > 0 && a.n0 <= f.length && c.claim.n0 == a.n0 && c.claim.h0.equals(a.h0)
                    && Bytes.hex(Bytes.sha256(f, (int) a.n0)).equals(a.h0);
            c.stampOk = a.n1 > a.n0 && a.n1 <= f.length && a.h1 != null
                    && Bytes.hex(Bytes.sha256(f, (int) a.n1)).equals(a.h1) && fd.offset >= a.n1;
            byte[] pk = keyFor(c.claim, a, keys);
            checkSignature(c, pk, keys, ledger);
            c.bytesAfter = Math.max(0, f.length - fd.end);
            // Lo que viene después y no es una firma SSJF posterior (que empieza justo donde termina este anexo).
            boolean nextChains = i + 1 < found.size() && found.get(i + 1).annex != null && found.get(i + 1).annex.n0 == fd.end;
            c.unsignedBytesAfter = nextChains ? unsignedTail(f, found, i + 1) : c.bytesAfter;
            if (c.verdict != Verdict.BAD_SIGNATURE && c.verdict != Verdict.MALFORMED) {
                if (!c.prefixOk) c.verdict = Verdict.DOC_MISMATCH;
                else if (!c.stampOk) c.verdict = Verdict.STAMP_ALTERED;
            }
        }
        return r;
    }

    /** Bytes sin firmar al final, mirando la última firma SSJF de una cadena consecutiva. */
    private static long unsignedTail(byte[] f, List<Annex.Found> found, int from) {
        int j = from;
        while (j + 1 < found.size() && found.get(j + 1).annex != null && found.get(j + 1).annex.n0 == found.get(j).end) j++;
        return Math.max(0, f.length - found.get(j).end);
    }

    /** Verificación sólo con el texto del QR (p. ej. de un papel impreso). */
    public static Check verifyQr(String text, KeyRing keys, Ledger ledger) {
        Check c = new Check();
        try {
            c.claim = Ssjf.parse(text);
        } catch (Ssjf.FormatException e) {
            c.verdict = Verdict.MALFORMED;
            c.detail = e.getMessage();
            return c;
        }
        checkSignature(c, keys == null ? null : keys.get(c.claim.kid), keys, ledger);
        return c;
    }

    private static byte[] keyFor(Ssjf.Claim claim, Annex a, KeyRing keys) {
        byte[] known = keys == null ? null : keys.get(claim.kid);
        if (known != null) return known;
        if (a != null && a.pk != null) {
            try {
                byte[] pk = Bytes.unb64url(a.pk);
                if (Ecdsa.keyId(pk).equals(claim.kid)) return pk;
            } catch (IllegalArgumentException ignored) {
                // pk ilegible: se trata como clave desconocida
            }
        }
        return null;
    }

    private static void checkSignature(Check c, byte[] pk, KeyRing keys, Ledger ledger) {
        if (pk == null) {
            c.verdict = Verdict.OTHER_KEY;
            c.sigOk = false;
            c.detail = "Clave desconocida (" + c.claim.kid + "): no se puede comprobar la firma sin su clave pública";
            return;
        }
        c.sigOk = c.claim.verify(pk);
        if (!c.sigOk) {
            c.verdict = Verdict.BAD_SIGNATURE;
            return;
        }
        c.mine = keys != null && keys.isMine(c.claim.kid);
        if (ledger != null) c.entry = ledger.findSignature(c.claim.h0, Ledger.coreOf(c.claim.qrText()));
        if (c.entry != null) c.otsProof = OtsUpdater.completedProof(ledger, c.entry.seq());
        c.verdict = !c.mine ? Verdict.OTHER_KEY : c.entry != null ? Verdict.MINE : Verdict.MINE_NOT_IN_LEDGER;
        if (!c.mine && keys != null && keys.owner(c.claim.kid) != null) c.detail = "Clave de " + keys.owner(c.claim.kid);
    }

    /** La versión exacta que se firmó (original) o la sellada (original + sello), para extraerla. */
    public static byte[] prefix(byte[] f, long n) {
        return Arrays.copyOf(f, (int) Math.min(n, f.length));
    }
}
