package com.josexato.navaja.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Los colores del sistema de diseño cumplen el contraste mínimo WCAG AA (4,5:1 para texto). */
public class PaletteTest {

    static void readable(String what, int fg, int bg) {
        double c = Palette.contrast(fg, bg);
        assertTrue(String.format("%s: %.2f:1 (< 4,5)", what, c), c >= 4.5);
    }

    @Test
    public void contrastFormula() {
        assertEquals(21.0, Palette.contrast(0xFF000000, 0xFFFFFFFF), 1e-9);
        assertEquals(1.0, Palette.contrast(0xFF777777, 0xFF777777), 1e-9);
        assertEquals(4.48, Palette.contrast(0xFF777777, 0xFFFFFFFF), 0.01); // valor de referencia conocido
    }

    @Test
    public void textOnEverySurface() {
        for (int bg : new int[]{Palette.BG, Palette.SURFACE, Palette.SURFACE_ALT}) {
            String s = Integer.toHexString(bg);
            readable("texto en " + s, Palette.TEXT, bg);
            readable("texto 2 en " + s, Palette.TEXT_2, bg);
            readable("texto 3 en " + s, Palette.TEXT_3, bg);
            readable("ámbar en " + s, Palette.ACCENT, bg);
            for (int st : new int[]{Palette.OK, Palette.GOOD, Palette.WARN, Palette.BAD, Palette.UNKNOWN, Palette.NORTH})
                readable("estado " + Integer.toHexString(st) + " en " + s, st, bg);
        }
        readable("texto sobre botón ámbar", Palette.ON_ACCENT, Palette.ACCENT);
    }

    @Test
    public void alpha() {
        assertEquals(0x80E0913F, Palette.withAlpha(Palette.ACCENT, 0x80));
    }
}
