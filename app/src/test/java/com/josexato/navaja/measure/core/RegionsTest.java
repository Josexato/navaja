package com.josexato.navaja.measure.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RegionsTest {

    static final int W = 320, H = 240;

    /**
     * Un "objeto": disco color piel con manchas (dos ojos oscuros, una boca
     * roja alargada, una ceja azul), girado {@code deg} y escalado {@code s}
     * alrededor de (cx, cy). Devuelve el cuadro y su silueta (misma resolución).
     */
    static Object[] scene(double deg, double s, double cx, double cy) { return scene(deg, s, cx, cy, 1); }

    /** {@code flip} = −1 pone la boca arriba y la ceja abajo (otra disposición, mismos colores). */
    static Object[] scene(double deg, double s, double cx, double cy, int flip) {
        int[] img = new int[W * H];
        byte[] mask = new byte[W * H];
        double c = Math.cos(Math.toRadians(deg)), sn = Math.sin(Math.toRadians(deg));
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++) {
                // Coordenadas del objeto (sin girar ni escalar)
                double dx = (x - cx) / s, dy = (y - cy) / s;
                double u = c * dx + sn * dy, v = -sn * dx + c * dy;
                int col = 0xFF303838; // fondo
                if (u * u + v * v < 80 * 80) {
                    mask[y * W + x] = 1;
                    col = 0xFFE0B090; // piel
                    if (sq((u + 28) / 9) + sq((v + 12) / 9) < 1 || sq((u - 28) / 9) + sq((v + 12) / 9) < 1) col = 0xFF202020;
                    if (sq(u / 30) + sq((v - flip * 35) / 7) < 1) col = 0xFFC02020;
                    if (u > 10 && u < 50 && -flip * v > 30 && -flip * v < 38) col = 0xFF2040C0;
                }
                img[y * W + x] = col;
            }
        return new Object[]{img, mask};
    }

    static double sq(double v) { return v * v; }

    static Regions.Set detect(Object[] sc) { return Regions.detect((int[]) sc[0], W, H, (byte[]) sc[1], W, H); }

    @Test
    public void findsTheSpotsAndNotTheSkin() {
        Regions.Set s = detect(scene(0, 1, 160, 120));
        assertNotNull(s);
        assertEquals(160, s.cx, 1);
        assertEquals(120, s.cy, 1);
        // Ojos, boca y ceja; la piel domina la silueta y no cuenta
        assertEquals(4, s.regions.size());
    }

    @Test
    public void recoversTurnAndScale() {
        Regions.Set a = detect(scene(0, 1, 160, 120));
        Regions.Set b = detect(scene(30, 0.8, 150, 125));
        Regions.Match m = Regions.match(a, b);
        assertNotNull(m);
        assertTrue(m.pairs.size() >= 3);
        assertEquals(30, m.angleDeg, 3);
        assertEquals(0.8, m.scale, 0.05);
        // El centro de a cae en el centro de b
        double[] p = m.map(160, 120);
        assertEquals(150, p[0], 4);
        assertEquals(125, p[1], 4);
    }

    @Test
    public void otherLayoutDoesNotMatch() {
        // Los ojos solos (2 parejas) no bastan: boca y ceja están en otro lugar
        assertNull(Regions.match(detect(scene(0, 1, 160, 120)), detect(scene(0, 1, 160, 120, -1))));
    }

    @Test
    public void tinySilhouetteGivesNothing() {
        byte[] mask = new byte[W * H];
        for (int i = 0; i < 100; i++) mask[i] = 1;
        assertNull(Regions.detect(new int[W * H], W, H, mask, W, H));
    }
}
