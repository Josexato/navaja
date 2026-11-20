package com.josexato.navaja;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BuildFactsTest {

    @Test
    public void elHechoEsElMasRecienteHastaEseDia() {
        assertTrue(BuildFacts.fact(1926, 11, 20).startsWith("el 15 de noviembre de 1926, empezó a transmitir"));
        assertTrue(BuildFacts.fact(1926, 11, 15).startsWith("justo ese día, el 15 de noviembre de 1926"));
        assertTrue(BuildFacts.fact(1926, 11, 14).contains("Houdini"));
        assertEquals("", BuildFacts.fact(1926, 1, 1));
        // Están en orden de fecha
        int prev = 0;
        for (Object[] f : BuildFacts.FACTS) {
            int k = (Integer) f[0] * 10000 + (Integer) f[1] * 100 + (Integer) f[2];
            assertTrue(k > prev);
            prev = k;
        }
    }

    @Test
    public void elTextoDiceLaVersionLaFechaYLasCompilaciones() {
        // 20 de noviembre de 2026, 15:05 UTC = 10:05 en Lima
        long t = 1795187100000L;
        String one = BuildFacts.line("1.0.49", t, 1);
        assertTrue(one, one.startsWith("Versión 1.0.49 · código terminado el 20/11/2026 10:05 (hora de Lima). Hace 100 años: el 15 de noviembre de 1926"));
        assertFalse(one.contains("compilaste"));
        assertTrue(BuildFacts.line("1.0.50", t, 3).endsWith("Y tú ya compilaste esto 3 veces hoy."));
    }
}
