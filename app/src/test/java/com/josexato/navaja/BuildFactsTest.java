package com.josexato.navaja;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BuildFactsTest {

    @Test
    public void elTextoDiceLaVersionLaFechaYLasCompilaciones() {
        // 20 de noviembre de 2026, 15:05 UTC = 10:05 en Lima
        long t = 1795187100000L;
        String one = BuildFacts.line("1.0.49", t, 1);
        assertTrue(one, one.startsWith("Versión 1.0.49 · código terminado el 20/11/2026 10:05 (hora de Lima). Hace 100 años: " + BuildFacts.FACT + "."));
        assertFalse("falta el hecho de esta entrega", BuildFacts.FACT.isEmpty());
        assertFalse(one.contains("compilaste"));
        assertTrue(BuildFacts.line("1.0.50", t, 3).endsWith("Y tú ya compilaste esto 3 veces hoy."));
    }
}
