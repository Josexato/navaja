package com.josexato.navaja.sign;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.josexato.navaja.sign.core.Bytes;
import com.josexato.navaja.sign.core.Ots;
import com.josexato.navaja.sign.core.TimeAnchors;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Con los ejemplos oficiales del cliente OpenTimestamps (opentimestamps-client/examples). */
public class OtsTest {

    /** Bloques reales (mempool.space): raíz Merkle tal como la muestran los exploradores. */
    static final TimeAnchors.BlockSource BLOCKS = h -> {
        if (h == 358391) return new TimeAnchors.BlockInfo(h, "000000000000000003e892881a8cdcdc117c06d444057c98b6f04a9ee75a2319",
                "8a1b66ecb7cbd07d8139a7e7d7f2c41aab1f5009b8364aaf61d03ad245e47e00", 1432827678L);
        if (h == 428648) return new TimeAnchors.BlockInfo(h, "000000000000000000ca478eb185560e1f1178746ee05e3d9bc9a31765f6f4a3",
                "078cdde9c89f2e3c58c96b1658627fd9298c63c6618954ea24ac3b5a13fe18da", 1473227803L);
        throw new IOException("bloque desconocido " + h);
    };

    @Test
    public void pruebaCompletaSeLeeEscribeYComprueba() throws Exception {
        byte[] file = SignCoreTest.resource("hello-world.txt.ots");
        Ots.Proof p = Ots.Proof.parse(file);
        assertArrayEquals(file, p.serialize());
        byte[] digest = Bytes.sha256("Hello World!\n".getBytes(StandardCharsets.US_ASCII));
        assertArrayEquals(digest, p.digest);
        Ots.Status st = Ots.check(p, digest, BLOCKS);
        assertTrue(st.complete);
        assertTrue(st.verified);
        assertEquals(358391, st.height);
        assertEquals(1432827678L, st.blockTime);
        // Otro resumen: no sirve.
        assertFalse(Ots.check(p, Bytes.sha256(new byte[1]), BLOCKS).complete);
        // Bloque con otra raíz: se detecta.
        TimeAnchors.BlockSource fake = h -> new TimeAnchors.BlockInfo(h, "00", Bytes.hex(new byte[32]), 0);
        assertFalse(Ots.check(p, digest, fake).verified);
    }

    @Test
    public void actualizaPruebaPendienteConElCalendario() throws Exception {
        byte[] file = SignCoreTest.resource("incomplete.txt.ots");
        Ots.Proof p = Ots.Proof.parse(file);
        assertArrayEquals(file, p.serialize());
        byte[] digest = Bytes.sha256(SignCoreTest.resource("incomplete.txt"));
        assertFalse(Ots.check(p, digest, BLOCKS).complete);

        // Calendario todavía sin bloque.
        Ots.Upgrade none = Ots.upgrade(p, (uri, c) -> null);
        assertFalse(none.changed);
        assertFalse(p.complete());

        byte[] resp = SignCoreTest.resource("incomplete_calendar_response.bin");
        String[] asked = new String[2];
        Ots.Upgrade u = Ots.upgrade(p, (uri, commitment) -> {
            asked[0] = uri;
            asked[1] = Bytes.hex(commitment);
            return resp;
        });
        assertTrue(u.changed);
        assertEquals("https://alice.btc.calendar.opentimestamps.org", asked[0]);
        assertEquals("57cfa5c46716df9bd9e83595bce439c58108d8fcc1678f30d4c6731c3f1fa6c79ed712c66fb1ac8d4e4eb0e7", asked[1]);
        Ots.Status st = Ots.check(p, digest, BLOCKS);
        assertTrue(st.message, st.verified);
        assertEquals(428648, st.height);
        // Lo actualizado se vuelve a leer igual y ya no queda nada pendiente.
        Ots.Proof again = Ots.Proof.parse(p.serialize());
        assertTrue(Ots.check(again, digest, BLOCKS).verified);
        boolean[] pending = {false};
        again.root.walk((n, a) -> pending[0] |= a.pending());
        assertFalse(pending[0]);
        assertNull(Ots.upgrade(again, (x, y) -> { throw new AssertionError("no debía consultar"); }).notes.isEmpty() ? null : "x");
    }

    @Test
    public void ripemd160VectoresOficiales() throws Exception {
        // Vectores de la página de RIPEMD-160 (homes.esat.kuleuven.be/~bosselae/ripemd160.html).
        String[][] v = {{"", "9c1185a5c5e9fc54612808977ee8f548b2258d31"}, {"abc", "8eb208f7e05d987a9b044a8e98c6b087f15a0bfc"},
            {"message digest", "5d0689ef49d2fae572b881b123a85ffa21595f36"},
            {"12345678901234567890123456789012345678901234567890123456789012345678901234567890", "9b752e45573d4b39f4dbd3323cab82bf63326bfb"}};
        java.lang.reflect.Method m = Class.forName("com.josexato.navaja.sign.core.Ripemd160").getDeclaredMethod("digest", byte[].class);
        m.setAccessible(true);
        for (String[] t : v) assertEquals(t[1], Bytes.hex((byte[]) m.invoke(null, (Object) t[0].getBytes(StandardCharsets.US_ASCII))));
    }

    @Test
    public void elSelloQueGeneraNavajaSeLee() throws Exception {
        byte[] h = Bytes.sha256("h1".getBytes(StandardCharsets.UTF_8));
        byte[] ots = TimeAnchors.otsFile(h, new byte[16], SignCoreTest.resource("calendar_response.bin"));
        Ots.Proof p = Ots.Proof.parse(ots);
        assertArrayEquals(ots, p.serialize());
        assertFalse(p.complete());
        String[] uri = new String[1];
        p.root.walk((n, a) -> { if (a.pending()) uri[0] = a.uri(); });
        assertTrue(uri[0], uri[0].startsWith("https://"));
    }

    @Test
    public void historialRegistraElSelloCompletado() throws Exception {
        com.josexato.navaja.sign.core.Ledger l = new com.josexato.navaja.sign.core.Ledger();
        java.util.Map<String, Object> pub = new java.util.LinkedHashMap<>();
        pub.put("h0", "aa");
        pub.put("h1", Bytes.hex(Bytes.sha256(SignCoreTest.resource("incomplete.txt"))));
        pub.put("ots", Bytes.b64(SignCoreTest.resource("incomplete.txt.ots")));
        l.append(com.josexato.navaja.sign.core.Ledger.TYPE_SIGN, 1L, pub, null);
        byte[] resp = SignCoreTest.resource("incomplete_calendar_response.bin");

        com.josexato.navaja.sign.core.OtsUpdater.Summary pending =
                com.josexato.navaja.sign.core.OtsUpdater.run(l, (u, c) -> null, BLOCKS, 2L);
        assertEquals(1, pending.pending);
        assertEquals(1, l.entries().size());

        com.josexato.navaja.sign.core.OtsUpdater.Summary s =
                com.josexato.navaja.sign.core.OtsUpdater.run(l, (u, c) -> resp, BLOCKS, 3L);
        assertEquals(1, s.completed);
        assertEquals(2, l.entries().size());
        assertNull(l.verifyChain());
        com.josexato.navaja.sign.core.Ledger.Entry proof = com.josexato.navaja.sign.core.OtsUpdater.completedProof(l, 1);
        assertEquals(428648, proof.l("btc_height"));
        assertEquals(1473227803L, proof.l("btc_time"));
        assertTrue(Ots.Proof.parse(Bytes.unb64(proof.s("ots"))).complete());
        // Segunda pasada: nada que hacer, sin consultar a nadie.
        assertTrue(com.josexato.navaja.sign.core.OtsUpdater.run(l, (u, c) -> { throw new AssertionError(); }, BLOCKS, 4L).nothingToDo());
    }
}
