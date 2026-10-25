package com.josexato.navaja.sign.core;

import com.josexato.navaja.pantonera.core.Json;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Historial de firmas: un archivo JSON Lines de sólo-agregar, encadenado
 * por resúmenes (cada entrada incluye el SHA-256 de la anterior), como un
 * pequeño "certificate transparency" personal.
 *
 * <p>Cada entrada tiene una parte pública (resúmenes, QR, sellos) y una
 * privada (nombre de archivo, ubicación, comentario) que sólo vive en el
 * teléfono. La parte pública incluye un compromiso con sal
 * {@code commit = SHA-256(sal ‖ JSON(priv))}: el respaldo público prueba que
 * esos datos existían sin revelarlos, y quien tenga el historial completo
 * puede demostrarlos después. El encadenamiento se calcula SÓLO sobre la
 * parte pública, así que la copia pública se puede verificar sola.
 */
public final class Ledger {

    public static final String GENESIS = "0000000000000000000000000000000000000000000000000000000000000000";
    public static final String TYPE_SIGN = "firma", TYPE_PROOF = "sello", TYPE_KEY = "clave";
    static final String PRIV = "priv", SALT = "salt";

    public static final class Entry {
        public final Map<String, Object> m;

        Entry(Map<String, Object> m) { this.m = m; }

        public String type() { return s("type"); }

        public long seq() { return l("seq"); }

        public String s(String k) { Object o = m.get(k); return o instanceof String ? (String) o : null; }

        public long l(String k) { Object o = m.get(k); return o instanceof Number ? ((Number) o).longValue() : -1; }

        @SuppressWarnings("unchecked")
        public Map<String, Object> priv() {
            Object o = m.get(PRIV);
            return o instanceof Map ? (Map<String, Object>) o : null;
        }

        public String privS(String k) {
            Map<String, Object> p = priv();
            Object o = p == null ? null : p.get(k);
            return o instanceof String ? (String) o : null;
        }

        /** JSON compacto de la parte pública: lo que se encadena y lo que se respalda. */
        public String publicJson() {
            Map<String, Object> pub = new LinkedHashMap<>(m);
            pub.remove(PRIV);
            pub.remove(SALT);
            return Json.writeCompact(pub);
        }

        public String hash() { return Bytes.hex(Bytes.sha256(publicJson().getBytes(StandardCharsets.UTF_8))); }

        /** ¿El compromiso coincide con los datos privados? (En la copia pública no hay nada que comparar.) */
        public boolean commitmentOk() {
            Map<String, Object> p = priv();
            if (p == null) return true;
            if (s(SALT) == null || s("commit") == null) return false;
            return commitment(Bytes.unb64url(s(SALT)), p).equals(s("commit"));
        }
    }

    private final List<Entry> entries = new ArrayList<>();

    public List<Entry> entries() { return Collections.unmodifiableList(entries); }

    public Entry last() { return entries.isEmpty() ? null : entries.get(entries.size() - 1); }

    public String head() { return entries.isEmpty() ? GENESIS : last().hash(); }

    @SuppressWarnings("unchecked")
    public static Ledger parse(String jsonl) throws Json.ParseException {
        Ledger l = new Ledger();
        for (String line : jsonl.split("\n")) {
            if (line.trim().isEmpty()) continue;
            l.entries.add(new Entry((Map<String, Object>) Json.parse(line)));
        }
        return l;
    }

    /** JSONL completo (con datos privados) o sólo público. */
    public String toJsonl(boolean includePrivate) {
        StringBuilder sb = new StringBuilder();
        for (Entry e : entries) sb.append(includePrivate ? Json.writeCompact(e.m) : e.publicJson()).append('\n');
        return sb.toString();
    }

    /**
     * Agrega una entrada. {@code pub} son los campos públicos (en el orden en
     * que se escribirán); {@code priv} los privados o null.
     */
    public Entry append(String type, long unix, Map<String, Object> pub, Map<String, String> priv) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("v", 1L);
        m.put("type", type);
        m.put("seq", (long) entries.size() + 1);
        m.put("prev", head());
        m.put("t", unix);
        m.putAll(pub);
        if (priv != null && !priv.isEmpty()) {
            byte[] salt = new byte[16];
            new SecureRandom().nextBytes(salt);
            Map<String, Object> p = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : priv.entrySet())
                if (e.getValue() != null && !e.getValue().isEmpty()) p.put(e.getKey(), clean(e.getValue()));
            m.put("commit", commitment(salt, p));
            m.put(PRIV, p);
            m.put(SALT, Bytes.b64url(salt));
        }
        Entry e = new Entry(m);
        entries.add(e);
        return e;
    }

    static String commitment(byte[] salt, Map<String, Object> priv) {
        return Bytes.hex(Bytes.sha256(Bytes.concat(salt, Json.writeCompact(priv).getBytes(StandardCharsets.UTF_8))));
    }

    /** Sin caracteres de control (salvo salto de línea): la forma canónica JSON queda igual en cualquier lenguaje. */
    static String clean(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) if (c >= 0x20 || c == '\n') sb.append(c);
        return sb.toString().trim();
    }

    /**
     * Restaurar en otro teléfono: si un historial es continuación del otro
     * (mismas entradas al inicio), devuelve el más largo; si divergen, null.
     */
    public static Ledger adopt(Ledger local, Ledger imported) {
        if (imported.verifyChain() != null) return null;
        int n = Math.min(local.entries.size(), imported.entries.size());
        for (int i = 0; i < n; i++)
            if (!local.entries.get(i).hash().equals(imported.entries.get(i).hash())) return null;
        return imported.entries.size() >= local.entries.size() ? imported : local;
    }

    /** Verifica la cadena; devuelve null si está íntegra o la descripción del primer problema. */
    public String verifyChain() {
        String prev = GENESIS;
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            if (e.seq() != i + 1) return "Entrada " + (i + 1) + ": número de secuencia " + e.seq();
            if (!prev.equals(e.s("prev"))) return "Entrada " + (i + 1) + ": no encadena con la anterior (¿borrada o modificada?)";
            if (!e.commitmentOk()) return "Entrada " + (i + 1) + ": los datos privados no coinciden con su compromiso";
            prev = e.hash();
        }
        return null;
    }

    /** Firma registrada con ese resumen de antes y esa firma (QR), o null. */
    public Entry findSignature(String h0, String qrCore) {
        for (Entry e : entries)
            if (TYPE_SIGN.equals(e.type()) && h0.equals(e.s("h0")) && (qrCore == null || coreOf(e.s("qr")).equals(qrCore))) return e;
        return null;
    }

    /** Busca un resumen como documento original (h0), sellado (h1) o archivo final guardado (h2). */
    public Entry findHash(String field, String hash) {
        for (Entry e : entries) if (TYPE_SIGN.equals(e.type()) && hash.equals(e.s(field))) return e;
        return null;
    }

    public List<Entry> proofsFor(long seq) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries) if (TYPE_PROOF.equals(e.type()) && e.l("ref") == seq) out.add(e);
        return out;
    }

    /** El QR sin el prefijo URL (desde "SSJF1;"). */
    public static String coreOf(String qr) {
        if (qr == null) return "";
        int i = qr.indexOf(Ssjf.MAGIC + ";");
        return i < 0 ? qr : qr.substring(i);
    }
}
