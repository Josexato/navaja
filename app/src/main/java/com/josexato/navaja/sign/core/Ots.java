package com.josexato.navaja.sign.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Pruebas OpenTimestamps (.ots): lectura, escritura y actualización.
 *
 * <p>Al sellar, el calendario devuelve una prueba "pendiente": un camino de
 * operaciones (append/prepend/sha256) desde nuestro resumen hasta un
 * compromiso que el calendario promete incluir en Bitcoin. Unas horas
 * después, {@code GET <calendario>/timestamp/<compromiso>} devuelve el
 * resto del camino hasta la raíz Merkle de un bloque. Aquí se fusiona esa
 * respuesta (igual que {@code ots upgrade}) y se comprueba la raíz contra un
 * explorador público. Formato: github.com/opentimestamps/python-opentimestamps.
 */
public final class Ots {

    private Ots() { }

    static final byte[] MAGIC = TimeAnchors.OTS_MAGIC;
    static final byte[] TAG_PENDING = {(byte) 0x83, (byte) 0xdf, (byte) 0xe3, 0x0d, 0x2e, (byte) 0xf9, 0x0c, (byte) 0x8e};
    static final byte[] TAG_BITCOIN = {0x05, (byte) 0x88, (byte) 0x96, 0x0d, 0x73, (byte) 0xd7, 0x19, 0x01};
    static final int SHA1 = 0x02, RIPEMD160 = 0x03, SHA256 = 0x08, APPEND = 0xf0, PREPEND = 0xf1, REVERSE = 0xf2, HEXLIFY = 0xf3;

    /** Una atestación: "pendiente en tal calendario" o "en tal bloque de Bitcoin" (u otra, que se conserva tal cual). */
    public static final class Attestation {
        public final byte[] tag, payload;

        Attestation(byte[] tag, byte[] payload) { this.tag = tag; this.payload = payload; }

        public boolean pending() { return Arrays.equals(tag, TAG_PENDING); }

        public boolean bitcoin() { return Arrays.equals(tag, TAG_BITCOIN); }

        /** URL del calendario (sólo pendientes). */
        public String uri() throws IOException {
            Reader r = new Reader(payload);
            return new String(r.varbytes(1000), StandardCharsets.US_ASCII);
        }

        /** Altura del bloque (sólo Bitcoin). */
        public long height() throws IOException { return new Reader(payload).varuint(); }
    }

    public static final class Op {
        public final int tag;
        public final byte[] arg;

        Op(int tag, byte[] arg) { this.tag = tag; this.arg = arg; }

        byte[] apply(byte[] msg) throws IOException {
            switch (tag) {
                case SHA256: return Bytes.sha256(msg);
                case RIPEMD160: return Ripemd160.digest(msg);
                case SHA1:
                    try {
                        return java.security.MessageDigest.getInstance("SHA-1").digest(msg);
                    } catch (java.security.NoSuchAlgorithmException e) {
                        throw new IOException(e);
                    }
                case REVERSE: {
                    byte[] r = new byte[msg.length];
                    for (int i = 0; i < r.length; i++) r[i] = msg[msg.length - 1 - i];
                    return r;
                }
                case HEXLIFY: return Bytes.hex(msg).getBytes(StandardCharsets.US_ASCII);
                case APPEND: return Bytes.concat(msg, arg);
                case PREPEND: return Bytes.concat(arg, msg);
                default: throw new IOException(String.format("Operación OTS 0x%02x no soportada", tag));
            }
        }

        boolean binary() { return tag == APPEND || tag == PREPEND; }
    }

    /** Nodo del árbol: el mensaje en este punto, sus atestaciones y las operaciones que salen de él. */
    public static final class Timestamp {
        public final byte[] msg;
        public final List<Attestation> attestations = new ArrayList<>();
        public final List<Op> ops = new ArrayList<>();
        public final List<Timestamp> children = new ArrayList<>();

        Timestamp(byte[] msg) { this.msg = msg; }

        /** Recorre todas las atestaciones con el nodo donde están. */
        public void walk(Visitor v) throws IOException {
            for (Attestation a : attestations) v.visit(this, a);
            for (Timestamp c : children) c.walk(v);
        }

        /** Agrega (sin duplicar) lo que trae {@code other}, que debe partir del mismo mensaje. */
        void merge(Timestamp other) {
            for (Attestation a : other.attestations) {
                boolean dup = false;
                for (Attestation b : attestations) dup |= Arrays.equals(a.tag, b.tag) && Arrays.equals(a.payload, b.payload);
                if (!dup) attestations.add(a);
            }
            outer:
            for (int i = 0; i < other.ops.size(); i++) {
                Op o = other.ops.get(i);
                for (int j = 0; j < ops.size(); j++) {
                    if (ops.get(j).tag == o.tag && Arrays.equals(ops.get(j).arg, o.arg)) {
                        children.get(j).merge(other.children.get(i));
                        continue outer;
                    }
                }
                ops.add(o);
                children.add(other.children.get(i));
            }
        }
    }

    public interface Visitor {
        void visit(Timestamp node, Attestation a) throws IOException;
    }

    /** Archivo .ots completo: resumen del "archivo" (aquí h1) y el árbol. */
    public static final class Proof {
        public final byte[] digest;
        public final Timestamp root;

        Proof(byte[] digest, Timestamp root) { this.digest = digest; this.root = root; }

        public static Proof parse(byte[] file) throws IOException {
            if (file.length < MAGIC.length + 2 || !Arrays.equals(Arrays.copyOf(file, MAGIC.length), MAGIC))
                throw new IOException("No es un archivo OpenTimestamps");
            Reader r = new Reader(file);
            r.p = MAGIC.length;
            if (r.varuint() != 1) throw new IOException("Versión .ots no soportada");
            if (r.u8() != SHA256) throw new IOException("El .ots no usa SHA-256");
            byte[] d = r.bytes(32);
            Timestamp t = readTimestamp(r, d, 0);
            if (r.p != file.length) throw new IOException("Bytes sobrantes en el .ots");
            return new Proof(d, t);
        }

        public byte[] serialize() {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            o.write(MAGIC, 0, MAGIC.length);
            o.write(1);
            o.write(SHA256);
            o.write(digest, 0, 32);
            writeTimestamp(o, root);
            return o.toByteArray();
        }

        /** Bloques de Bitcoin que anclan la prueba (vacío si aún está pendiente). */
        public List<Anchor> anchors() throws IOException {
            List<Anchor> out = new ArrayList<>();
            root.walk((node, a) -> {
                if (a.bitcoin()) out.add(new Anchor(a.height(), node.msg));
            });
            return out;
        }

        public boolean complete() throws IOException { return !anchors().isEmpty(); }
    }

    /** Un anclaje: altura y raíz Merkle esperada (orden interno de bytes; los exploradores la muestran invertida). */
    public static final class Anchor {
        public final long height;
        public final byte[] merkleRoot;

        Anchor(long height, byte[] merkleRoot) { this.height = height; this.merkleRoot = merkleRoot; }

        /** Raíz tal como la muestran mempool.space / blockstream (hex invertido). */
        public String merkleRootDisplay() {
            byte[] r = new byte[merkleRoot.length];
            for (int i = 0; i < r.length; i++) r[i] = merkleRoot[merkleRoot.length - 1 - i];
            return Bytes.hex(r);
        }
    }

    // ------------------------------------------------------------- lectura

    static Timestamp readTimestamp(Reader r, byte[] msg, int depth) throws IOException {
        if (depth > 256) throw new IOException(".ots demasiado profundo");
        Timestamp t = new Timestamp(msg);
        int tag = r.u8();
        while (tag == 0xff) {
            item(r, t, r.u8(), depth);
            tag = r.u8();
        }
        item(r, t, tag, depth);
        return t;
    }

    private static void item(Reader r, Timestamp t, int tag, int depth) throws IOException {
        if (tag == 0x00) {
            byte[] atag = r.bytes(8);
            t.attestations.add(new Attestation(atag, r.varbytes(8192)));
            return;
        }
        Op op = new Op(tag, tag == APPEND || tag == PREPEND ? r.varbytes(4096) : null);
        byte[] next = op.apply(t.msg);
        if (next.length > 4096) throw new IOException("Mensaje OTS demasiado largo");
        t.ops.add(op);
        t.children.add(readTimestamp(r, next, depth + 1));
    }

    /** Respuesta de un calendario (un árbol que parte de {@code msg}). */
    public static Timestamp parseTimestamp(byte[] data, byte[] msg) throws IOException {
        Reader r = new Reader(data);
        Timestamp t = readTimestamp(r, msg, 0);
        if (r.p != data.length) throw new IOException("Respuesta del calendario con bytes sobrantes");
        return t;
    }

    // ------------------------------------------------------------ escritura

    static void writeTimestamp(ByteArrayOutputStream o, Timestamp t) {
        int items = t.attestations.size() + t.ops.size(), k = 0;
        for (Attestation a : t.attestations) {
            if (++k < items) o.write(0xff);
            o.write(0x00);
            o.write(a.tag, 0, 8);
            writeVarbytes(o, a.payload);
        }
        for (int i = 0; i < t.ops.size(); i++) {
            if (++k < items) o.write(0xff);
            Op op = t.ops.get(i);
            o.write(op.tag);
            if (op.binary()) writeVarbytes(o, op.arg);
            writeTimestamp(o, t.children.get(i));
        }
    }

    static void writeVarbytes(ByteArrayOutputStream o, byte[] b) {
        writeVaruint(o, b.length);
        o.write(b, 0, b.length);
    }

    static void writeVaruint(ByteArrayOutputStream o, long v) {
        do {
            int b = (int) (v & 0x7f);
            v >>>= 7;
            o.write(v != 0 ? b | 0x80 : b);
        } while (v != 0);
    }

    static final class Reader {
        final byte[] b;
        int p;

        Reader(byte[] b) { this.b = b; }

        int u8() throws IOException {
            if (p >= b.length) throw new IOException(".ots truncado");
            return b[p++] & 0xff;
        }

        byte[] bytes(int n) throws IOException {
            if (n < 0 || p + n > b.length) throw new IOException(".ots truncado");
            byte[] r = Arrays.copyOfRange(b, p, p + n);
            p += n;
            return r;
        }

        long varuint() throws IOException {
            long v = 0;
            for (int shift = 0; shift < 63; shift += 7) {
                int c = u8();
                v |= (long) (c & 0x7f) << shift;
                if ((c & 0x80) == 0) return v;
            }
            throw new IOException("varuint demasiado largo");
        }

        byte[] varbytes(int max) throws IOException {
            long n = varuint();
            if (n > max) throw new IOException("Campo .ots demasiado largo");
            return bytes((int) n);
        }
    }

    // --------------------------------------------------------- actualización

    /** Consulta un calendario: el árbol desde {@code commitment}, o null si todavía está pendiente. */
    public interface Calendar {
        byte[] get(String uri, byte[] commitment) throws IOException;
    }

    /** Calendario real por HTTPS (sólo URLs https de la propia prueba). */
    public static final Calendar HTTPS = (uri, commitment) -> {
        if (!uri.startsWith("https://") || uri.length() > 200 || !uri.matches("https://[A-Za-z0-9.\\-]+(:[0-9]+)?(/[A-Za-z0-9._\\-/]*)?"))
            throw new IOException("Calendario no válido: " + uri);
        java.util.Map<String, String> h = new java.util.HashMap<>();
        h.put("Accept", "application/vnd.opentimestamps.v1");
        Http.Response r = Http.request("GET", uri.replaceAll("/+$", "") + "/timestamp/" + Bytes.hex(commitment), h, null);
        if (r.code == 404) return null; // "Pending confirmation in Bitcoin blockchain"
        if (!r.ok()) throw new IOException(uri + " respondió HTTP " + r.code);
        return r.body;
    };

    public static final class Upgrade {
        /** true si se agregó algo a la prueba. */
        public boolean changed;
        public final List<String> notes = new ArrayList<>();
    }

    /**
     * Pide a cada calendario pendiente el resto del camino y lo fusiona. Si
     * el nodo queda anclado en Bitcoin, la atestación pendiente se quita (ya
     * no hace falta). Devuelve qué pasó; la prueba se modifica en sitio.
     */
    public static Upgrade upgrade(Proof p, Calendar cal) throws IOException {
        Upgrade u = new Upgrade();
        List<Timestamp> nodes = new ArrayList<>();
        List<Attestation> pend = new ArrayList<>();
        p.root.walk((node, a) -> {
            if (a.pending()) {
                nodes.add(node);
                pend.add(a);
            }
        });
        for (int i = 0; i < nodes.size(); i++) {
            Timestamp node = nodes.get(i);
            String uri = pend.get(i).uri();
            byte[] resp;
            try {
                resp = cal.get(uri, node.msg);
            } catch (IOException e) {
                u.notes.add(uri + ": " + e.getMessage());
                continue;
            }
            if (resp == null) {
                u.notes.add(uri + ": aún pendiente de confirmación en Bitcoin");
                continue;
            }
            Timestamp got = parseTimestamp(resp, node.msg);
            boolean[] anchored = {false};
            got.walk((n, a) -> anchored[0] |= a.bitcoin());
            node.merge(got);
            if (anchored[0]) node.attestations.remove(pend.get(i));
            u.changed = true;
            u.notes.add(uri + (anchored[0] ? ": anclado en Bitcoin" : ": actualizado, aún sin bloque"));
        }
        return u;
    }

    /** Estado de una prueba: pendiente, o anclada en un bloque comprobado contra un explorador. */
    public static final class Status {
        public boolean complete, verified;
        public long height = -1, blockTime;
        public String blockHash, message;
    }

    /**
     * Comprueba que la prueba sella {@code digest} y, si está anclada, que la
     * raíz Merkle a la que llega coincide con la del bloque real.
     */
    public static Status check(Proof p, byte[] digest, TimeAnchors.BlockSource blocks) throws IOException {
        Status st = new Status();
        if (!Arrays.equals(p.digest, digest)) {
            st.message = "La prueba OpenTimestamps sella otro resumen";
            return st;
        }
        List<Anchor> anchors = p.anchors();
        if (anchors.isEmpty()) {
            st.message = "Pendiente: el calendario aún no lo incluyó en un bloque";
            return st;
        }
        st.complete = true;
        Anchor best = anchors.get(0);
        for (Anchor a : anchors) if (a.height < best.height) best = a;
        st.height = best.height;
        if (blocks == null) {
            st.message = "Anclado en el bloque " + best.height + " (sin comprobar contra un explorador)";
            return st;
        }
        TimeAnchors.BlockInfo b = blocks.at(best.height);
        st.blockHash = b.hash;
        st.blockTime = b.time;
        st.verified = b.merkleRoot.equalsIgnoreCase(best.merkleRootDisplay());
        st.message = st.verified ? "Anclado en el bloque " + best.height
                : "¡La raíz Merkle no coincide con el bloque " + best.height + "!";
        return st;
    }
}
