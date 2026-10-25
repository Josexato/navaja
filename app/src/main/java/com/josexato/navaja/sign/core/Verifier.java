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
        final Map<String, Long> retired = new HashMap<>();

        /** La clave {@code kid} fue reemplazada en el instante {@code t} (las firmas posteriores con ella son sospechosas). */
        public KeyRing retire(String kid, long t) {
            retired.put(kid, t);
            return this;
        }

        /** Llavero "yo" a partir de la clave actual y los cambios de clave válidos del historial. */
        public static KeyRing mine(byte[] current, Ledger ledger) {
            KeyRing r = new KeyRing();
            java.util.List<KeyChange> ch = ledger == null ? new java.util.ArrayList<>() : KeyChange.fromLedger(ledger);
            for (String pk : KeyChange.myKeys(ch, current == null ? null : Bytes.b64url(current))) r.add(Bytes.unb64url(pk), "yo");
            for (Map.Entry<String, Long> e : KeyChange.retired(ch).entrySet()) r.retire(e.getKey(), e.getValue());
            return r;
        }

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
        /** Firmado con una clave que ya había sido reemplazada (instante del reemplazo), o -1. */
        public long keyRetiredAt = -1;
        /** Firma del sello sobre h1: TRUE válida, FALSE falsa o faltante cuando el QR la exige, null ausente (formato antiguo). */
        public Boolean sealOk;
        /** Sin firma del sello, mi historial confirma que esta D1 es la que yo produje. */
        public boolean sealByLedger;

        /** Lo agregado al sellar (D1 más allá de D0) no está avalado por el firmante: sólo el original es seguro. */
        public boolean sealUnattested() { return sealOk == null && !sealByLedger; }
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
            checkSeal(c, a, pk);
            c.bytesAfter = Math.max(0, f.length - fd.end);
            // Lo que viene después y no es una firma SSJF posterior (que empieza justo donde termina este anexo).
            boolean nextChains = i + 1 < found.size() && found.get(i + 1).annex != null && found.get(i + 1).annex.n0 == fd.end;
            c.unsignedBytesAfter = nextChains ? unsignedTail(f, found, i + 1) : c.bytesAfter;
            if (c.verdict != Verdict.BAD_SIGNATURE && c.verdict != Verdict.MALFORMED) {
                if (!c.prefixOk) c.verdict = Verdict.DOC_MISMATCH;
                else if (!c.stampOk) c.verdict = Verdict.STAMP_ALTERED;
                else if (Boolean.FALSE.equals(c.sealOk)) c.verdict = Verdict.STAMP_ALTERED;
            }
        }
        return r;
    }

    /**
     * La versión sellada D1 debe estar avalada por el firmante: con la firma
     * del sello (revisión 2) o, para firmas antiguas propias, con el h1 de mi
     * historial. Una D1 distinta con el mismo QR es una falsificación.
     */
    private static void checkSeal(Check c, Annex a, byte[] pk) {
        if (pk == null) return; // clave desconocida: no hay con qué comprobar
        if (c.entry != null && c.entry.s("h1") != null && !c.entry.s("h1").equals(a.h1)) {
            c.sealOk = false;
            c.detail = "La versión sellada no coincide con la que guardaste en tu historial";
            return;
        }
        if (a.seal != null) {
            boolean ok = false;
            try {
                ok = pk != null && Ecdsa.verify(Ecdsa.decompress(pk),
                        Ssjf.sealMessage(a.h0, a.h1, a.n1, c.claim.kid).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        Bytes.unb64url(a.seal));
            } catch (java.security.GeneralSecurityException | IllegalArgumentException ignored) {
                // firma o clave ilegible: falsa
            }
            c.sealOk = ok;
            if (!ok) c.detail = "La firma del sello no corresponde: lo agregado sobre el original no lo hizo el firmante";
        } else if (c.claim.rev >= 2) {
            c.sealOk = false;
            c.detail = "Falta la firma del sello que el QR exige: el anexo fue reemplazado";
        } else {
            c.sealOk = null;
            c.sealByLedger = c.entry != null && a.h1 != null && a.h1.equals(c.entry.s("h1"));
        }
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
        Long ret = keys == null ? null : keys.retired.get(c.claim.kid);
        if (ret != null && c.claim.t > ret) c.keyRetiredAt = ret;
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
