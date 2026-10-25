package com.josexato.navaja.sign.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Completa los sellos OpenTimestamps pendientes del historial: para cada
 * firma con un .ots aún sin bloque, consulta su calendario y, cuando ya está
 * anclado en Bitcoin y la raíz Merkle coincide con la del bloque real,
 * agrega una entrada "sello" al historial (sólo-agregar: la firma original
 * no se toca). A la red sólo viajan compromisos de 32-46 bytes.
 */
public final class OtsUpdater {

    private OtsUpdater() { }

    public static final class Summary {
        public int completed, pending, failed;
        public final List<String> lines = new ArrayList<>();

        public boolean nothingToDo() { return completed + pending + failed == 0; }
    }

    /** Entrada "sello" que ancla en Bitcoin la firma {@code seq}, o null. */
    public static Ledger.Entry completedProof(Ledger l, long seq) {
        for (Ledger.Entry p : l.proofsFor(seq)) if (p.s("ots") != null && p.l("btc_height") >= 0) return p;
        return null;
    }

    /** El .ots más completo de una firma: el de su entrada "sello" si existe, si no el original. */
    public static String latestOts(Ledger l, Ledger.Entry sign) {
        Ledger.Entry p = completedProof(l, sign.seq());
        return p != null ? p.s("ots") : sign.s("ots");
    }

    public static Summary run(Ledger l, Ots.Calendar cal, TimeAnchors.BlockSource blocks, long nowUnix) {
        Summary s = new Summary();
        for (Ledger.Entry e : new ArrayList<>(l.entries())) {
            if (!Ledger.TYPE_SIGN.equals(e.type()) || e.s("ots") == null || completedProof(l, e.seq()) != null) continue;
            try {
                Ots.Proof p = Ots.Proof.parse(Bytes.unb64(e.s("ots")));
                byte[] h1 = Bytes.unhex(e.s("h1"));
                if (!p.complete()) Ots.upgrade(p, cal);
                Ots.Status st = Ots.check(p, h1, blocks);
                if (st.complete && st.verified) {
                    record(l, e.seq(), e.s("h1"), p, st, nowUnix);
                    s.completed++;
                    s.lines.add("Firma #" + e.seq() + ": anclada en el bloque " + st.height);
                } else if (st.complete) {
                    s.failed++;
                    s.lines.add("Firma #" + e.seq() + ": " + st.message);
                } else {
                    s.pending++;
                    s.lines.add("Firma #" + e.seq() + ": aún pendiente (los calendarios tardan unas horas)");
                }
            } catch (IOException | IllegalArgumentException ex) {
                s.failed++;
                s.lines.add("Firma #" + e.seq() + ": " + ex.getMessage());
            }
        }
        return s;
    }

    /** Agrega la entrada "sello" con la prueba completa y el bloque comprobado. */
    public static Ledger.Entry record(Ledger l, long seq, String h1, Ots.Proof p, Ots.Status st, long nowUnix) {
        Map<String, Object> pub = new LinkedHashMap<>();
        pub.put("ref", seq);
        pub.put("h1", h1);
        pub.put("ots", Bytes.b64(p.serialize()));
        pub.put("btc_height", st.height);
        if (st.blockHash != null) pub.put("btc_hash", st.blockHash);
        if (st.blockTime > 0) pub.put("btc_time", st.blockTime);
        return l.append(Ledger.TYPE_PROOF, nowUnix, pub, null);
    }
}
