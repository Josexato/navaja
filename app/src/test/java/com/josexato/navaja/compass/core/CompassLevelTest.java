package com.josexato.navaja.compass.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Random;

import org.junit.Test;

/** Brújula (ángulos, filtro, rumbo) y nivel (gravedad → inclinación, calibración). */
public class CompassLevelTest {

    static final double G = Tilt.G;

    @Test
    public void normalizeAndDiff() {
        assertEquals(350, Angles.norm360(-10), 1e-9);
        assertEquals(10, Angles.norm360(370), 1e-9);
        assertEquals(20, Angles.diff(10, 350), 1e-9);
        assertEquals(-20, Angles.diff(350, 10), 1e-9);
        assertEquals(180, Angles.diff(0, 180), 1e-9);
    }

    @Test
    public void cardinals() {
        assertEquals("N", Angles.cardinal(0));
        assertEquals("N", Angles.cardinal(359));
        assertEquals("NNE", Angles.cardinal(22.5));
        assertEquals("E", Angles.cardinal(90));
        assertEquals("SO", Angles.cardinal(225));
        assertEquals("O", Angles.cardinal(270));
        assertEquals("NNO", Angles.cardinal(337.5));
    }

    @Test
    public void circularFilterDoesNotSpinAtNorth() {
        Angles.CircularFilter f = new Angles.CircularFilter(0.2);
        f.update(358);
        double v = 0;
        for (int i = 0; i < 50; i++) v = f.update(i % 2 == 0 ? 2 : 358); // ruido alrededor del norte
        assertTrue("v=" + v, Math.abs(Angles.diff(v, 0)) < 1.0);
        // Un promedio aritmético daría ~180°, el error típico de una brújula ingenua.
        assertTrue(f.stability() > 0.99);
        f.reset();
        assertEquals(90, f.update(90), 1e-9);
    }

    @Test
    public void bearingAndDistance() {
        // Lima (Plaza de Armas) → Callao (puerto): al oeste, ~13 km.
        double b = Angles.bearing(-12.0464, -77.0428, -12.0500, -77.1500);
        assertEquals(268, b, 1.5);
        double d = Angles.distance(-12.0464, -77.0428, -12.0500, -77.1500);
        assertEquals(11670, d, 120);
        assertEquals(0, Angles.bearing(0, 0, 1, 0), 1e-9);
        assertEquals(90, Angles.bearing(0, 0, 0, 1), 1e-9);
        assertEquals("850 m", Angles.formatDistance(850.2));
        assertEquals("12,4 km", Angles.formatDistance(12400));
    }

    /** Gravedad medida para un teléfono boca arriba inclinado tx (izq‑der) y ty (adelante‑atrás). */
    static double[] flat(double txDeg, double tyDeg) {
        double tx = Math.toRadians(txDeg), ty = Math.toRadians(tyDeg);
        double gx = Math.tan(tx), gy = Math.tan(ty), gz = 1;
        double n = Math.sqrt(gx * gx + gy * gy + gz * gz);
        return new double[]{G * gx / n, G * gy / n, G * gz / n};
    }

    @Test
    public void flatTilt() {
        double[] g = flat(0, 0);
        Tilt.Reading r = Tilt.read(g[0], g[1], g[2], 0, 0);
        assertEquals(Tilt.Mode.FLAT, r.mode);
        assertEquals(0, r.x, 1e-9);
        assertEquals(0, r.y, 1e-9);
        assertFalse(r.moving);
        g = flat(3, -1.5);
        r = Tilt.read(g[0], g[1], g[2], 0, 0);
        assertEquals(3, r.x, 1e-9);
        assertEquals(-1.5, r.y, 1e-9);
        assertEquals(3.354, r.total(), 0.01);
        // Boca abajo también mide.
        r = Tilt.read(g[0], g[1], -g[2], 0, 0);
        assertEquals(3, r.x, 1e-9);
    }

    @Test
    public void edgeMode() {
        // De pie, inclinado 2° (gira en el plano de la pantalla).
        double a = Math.toRadians(2);
        Tilt.Reading r = Tilt.read(G * Math.sin(a), G * Math.cos(a), 0.3, 0, 0);
        assertEquals(Tilt.Mode.EDGE, r.mode);
        assertEquals(0, r.edgeBase);
        assertEquals(2, r.edge, 0.01);
        // Apaisado sobre el borde derecho, desviado −1,5°.
        double b = Math.toRadians(270 - 1.5);
        r = Tilt.read(G * Math.sin(b), G * Math.cos(b), 0, 0, 0);
        assertEquals(270, r.edgeBase);
        assertEquals(-1.5, r.edge, 0.01);
        // Moviéndose: la aceleración total no es g.
        assertTrue(Tilt.read(0, 12, 0, 0, 0).moving);
    }

    @Test
    public void twoPointCalibrationCancelsBiasOnSlopedSurface() {
        double biasX = 0.8, biasY = -0.5;   // error del sensor
        double slopeX = 1.2, slopeY = 0.7;  // la mesa no está nivelada
        Random rnd = new Random(1);
        Tilt.Calibration cal = new Tilt.Calibration();
        // Posición 1 y luego girado 180°: la pendiente cambia de signo, el error no.
        cal.first(slopeX + biasX + rnd.nextGaussian() * 0.01, slopeY + biasY);
        assertEquals(1, cal.step());
        double[] off = cal.second(-slopeX + biasX, -slopeY + biasY + rnd.nextGaussian() * 0.01);
        assertEquals(biasX, off[0], 0.02);
        assertEquals(biasY, off[1], 0.02);
        assertEquals(0, cal.step());
        // Con el offset, una superficie realmente nivelada da 0.
        double[] g = flat(biasX, biasY);
        Tilt.Reading r = Tilt.read(g[0], g[1], g[2], off[0], off[1]);
        assertEquals(0, r.x, 0.02);
        assertEquals(0, r.y, 0.02);
        try {
            new Tilt.Calibration().second(0, 0);
            throw new AssertionError("sin primera medición debe fallar");
        } catch (IllegalStateException expected) {
            // ok
        }
    }

    @Test
    public void slopeUnits() {
        assertEquals(1.746, Tilt.percent(1), 0.001);
        assertEquals(17.46, Tilt.mmPerMeter(1), 0.01);
    }
}
