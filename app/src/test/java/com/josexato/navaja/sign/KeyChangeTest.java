package com.josexato.navaja.sign;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.josexato.navaja.sign.core.Bytes;
import com.josexato.navaja.sign.core.Ecdsa;
import com.josexato.navaja.sign.core.KeyChange;
import com.josexato.navaja.sign.core.Ledger;
import com.josexato.navaja.sign.core.Signer;
import com.josexato.navaja.sign.core.Ssjf;
import com.josexato.navaja.sign.core.Verifier;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;

public class KeyChangeTest {

    static String pk(KeyPair k) { return Bytes.b64url(Ecdsa.compress(k.getPublic())); }

    static byte[] sign(KeyPair k, String msg) throws Exception {
        return Ecdsa.derToRaw(SignCoreTest.jcaSigner(k).sign(msg.getBytes(StandardCharsets.UTF_8)));
    }

    /** Lo que hace la app: el nuevo prueba posesión (solicitud), el viejo autoriza. */
    static KeyChange change(KeyPair oldK, KeyPair newK, long t, String reason) throws Exception {
        String req = KeyChange.requestText(pk(newK), t - 5, sign(newK, KeyChange.popMessage(pk(newK), t - 5)));
        KeyChange k = KeyChange.parseRequest("Mensaje de WhatsApp:\n" + req + "\n");
        k.oldPk = pk(oldK);
        k.t = t;
        k.reason = reason;
        k.sigOld = sign(oldK, k.rotMessage());
        return k;
    }

    @Test
    public void autorizacionYPosesionVerifican() throws Exception {
        KeyPair a = SignCoreTest.keys(), b = SignCoreTest.keys();
        KeyChange k = change(a, b, 1000, KeyChange.REASON_NEW_PHONE);
        assertTrue(k.verify());
        // Ida y vuelta por JSON.
        KeyChange j = KeyChange.fromJson(k.toJson());
        assertTrue(j.verify());
        assertEquals(k.rotMessage(), j.rotMessage());
        // Cambiar cualquier dato invalida.
        j.t = 1001;
        assertFalse(j.verify());
        KeyChange swapped = KeyChange.fromJson(k.toJson());
        swapped.newPk = pk(SignCoreTest.keys());
        assertFalse(swapped.verify());
        KeyChange badReason = KeyChange.fromJson(k.toJson());
        badReason.reason = "Rotación!";
        assertFalse(badReason.verify());
        // Solicitud con la prueba de posesión de otra clave: rechazada.
        String forged = KeyChange.requestText(pk(b), 5, sign(a, KeyChange.popMessage(pk(b), 5)));
        try {
            KeyChange.parseRequest(forged);
            fail();
        } catch (Ssjf.FormatException expected) {
            // ok
        }
    }

    @Test
    public void lasFirmasViejasSiguenSiendoMiasYSeAvisaElUsoPosterior() throws Exception {
        KeyPair a = SignCoreTest.keys(), b = SignCoreTest.keys(), c = SignCoreTest.keys();
        byte[] orig = SignCoreTest.classicPdf();
        Ledger ledger = new Ledger();
        // Firma con A, cambio A→B en t=2e9, luego B→C en t=2.1e9.
        Signer.Request r = SignCoreTest.request(orig, 0);
        r.unix = 1_900_000_000L;
        Signer.Result withA = Signer.sign(r, Ecdsa.compress(a.getPublic()), SignCoreTest.jcaSigner(a), null);
        Signer.record(ledger, withA, null, null);
        change(a, b, 2_000_000_000L, KeyChange.REASON_ROTATION).record(ledger, 2_000_000_000L);
        change(b, c, 2_100_000_000L, KeyChange.REASON_NEW_PHONE).record(ledger, 2_100_000_000L);
        assertNull(ledger.verifyChain());
        assertEquals(2, KeyChange.fromLedger(ledger).size());

        // En el teléfono con C, la firma hecha con A sigue siendo "mía".
        Verifier.KeyRing ring = Verifier.KeyRing.mine(Ecdsa.compress(c.getPublic()), ledger);
        Verifier.Check ok = Verifier.verifyPdf(withA.pdf, ring, ledger).checks.get(0);
        assertEquals(Verifier.Verdict.MINE, ok.verdict);
        assertEquals(-1, ok.keyRetiredAt);

        // Alguien con el teléfono viejo firma con A DESPUÉS del cambio: válida, pero se avisa.
        Signer.Request late = SignCoreTest.request(orig, 0);
        late.unix = 2_050_000_000L;
        Signer.Result lateA = Signer.sign(late, Ecdsa.compress(a.getPublic()), SignCoreTest.jcaSigner(a), null);
        Verifier.Check warn = Verifier.verifyPdf(lateA.pdf, ring, ledger).checks.get(0);
        assertEquals(Verifier.Verdict.MINE_NOT_IN_LEDGER, warn.verdict);
        assertEquals(2_000_000_000L, warn.keyRetiredAt);

        // Una entrada "clave" falsificada en el historial no da confianza.
        KeyPair x = SignCoreTest.keys();
        KeyChange fake = change(x, c, 2_200_000_000L, KeyChange.REASON_ROTATION);
        fake.oldPk = pk(SignCoreTest.keys()); // la autorización ya no corresponde
        Ledger l2 = Ledger.parse(ledger.toJsonl(true));
        fake.record(l2, 2_200_000_000L);
        assertEquals(2, KeyChange.fromLedger(l2).size());
    }

    @Test
    public void restaurarHistorialEnOtroTelefono() throws Exception {
        Ledger a = new Ledger();
        java.util.Map<String, Object> pub = new java.util.LinkedHashMap<>();
        pub.put("h0", "aa");
        a.append(Ledger.TYPE_SIGN, 1L, pub, null);
        Ledger copy = Ledger.parse(a.toJsonl(true));
        a.append(Ledger.TYPE_SIGN, 2L, pub, null);
        Ledger empty = new Ledger();
        assertSame(a, Ledger.adopt(empty, a));          // teléfono nuevo, vacío
        assertSame(a, Ledger.adopt(copy, a));           // copia vieja + historial más nuevo
        assertSame(a, Ledger.adopt(a, copy));           // importar algo más viejo: se queda el local
        Ledger other = new Ledger();
        java.util.Map<String, Object> pub2 = new java.util.LinkedHashMap<>();
        pub2.put("h0", "bb");
        other.append(Ledger.TYPE_SIGN, 1L, pub2, null);
        assertNull(Ledger.adopt(other, a));             // historiales distintos
        assertNotNull(Ledger.adopt(new Ledger(), Ledger.parse(a.toJsonl(false)))); // también la copia pública
    }
}
