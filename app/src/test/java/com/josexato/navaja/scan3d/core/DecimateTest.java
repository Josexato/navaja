package com.josexato.navaja.scan3d.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

public class DecimateTest {

    /** Malla de vóxeles (Surface Nets + suavizado) de lo que cumple {@code in}, en una grilla de n³ de lado 1. */
    interface Shape { boolean in(double x, double y, double z); }

    static Mesh voxels(int n, Shape s) {
        Carver.Grid g = new Carver.Grid();
        g.nx = g.ny = g.nz = n;
        g.x0 = g.y0 = g.z0 = -n / 2.0;
        g.step = 1;
        g.occ = new byte[n * n * n];
        for (int k = 0; k < n; k++)
            for (int j = 0; j < n; j++)
                for (int i = 0; i < n; i++)
                    if (s.in(g.x0 + i + 0.5, g.y0 + j + 0.5, g.z0 + k + 0.5)) g.occ[g.idx(i, j, k)] = 1;
        Mesh m = SurfaceNets.extract(g);
        m.taubinSmooth(8);
        return m;
    }

    /** Cada arista en exactamente 2 triángulos (cerrada y variedad), sin triángulos repetidos en un vértice. */
    static void assertClosed(Mesh m) {
        Map<Long, Integer> edges = new HashMap<>();
        for (int t = 0; t < m.tri.length; t += 3)
            for (int e = 0; e < 3; e++) {
                int a = m.tri[t + e], b = m.tri[t + (e + 1) % 3];
                assertTrue("triángulo degenerado", a != b);
                long k = (long) Math.min(a, b) << 32 | Math.max(a, b);
                edges.merge(k, 1, Integer::sum);
            }
        for (int c : edges.values()) assertEquals("arista en " + c + " triángulos", 2, c);
    }

    static double[] radii(Mesh m) {
        double lo = 1e9, hi = 0;
        for (int i = 0; i < m.pos.length; i += 3) {
            double r = Math.sqrt(m.pos[i] * m.pos[i] + m.pos[i + 1] * m.pos[i + 1] + m.pos[i + 2] * m.pos[i + 2]);
            lo = Math.min(lo, r);
            hi = Math.max(hi, r);
        }
        return new double[]{lo, hi};
    }

    /** Volumen encerrado (teorema de la divergencia). */
    static double volume(Mesh m) {
        double v = 0;
        for (int t = 0; t < m.tri.length; t += 3) {
            int a = 3 * m.tri[t], b = 3 * m.tri[t + 1], c = 3 * m.tri[t + 2];
            float[] p = m.pos;
            v += p[a] * (p[b + 1] * p[c + 2] - p[b + 2] * p[c + 1]) - p[a + 1] * (p[b] * p[c + 2] - p[b + 2] * p[c])
                    + p[a + 2] * (p[b] * p[c + 1] - p[b + 1] * p[c]);
        }
        return Math.abs(v) / 6;
    }

    @Test
    public void unaCajaQuedaEnPocasCarasSinPerderForma() {
        Mesh m = voxels(40, (x, y, z) -> Math.abs(x) < 12 && Math.abs(y) < 8 && Math.abs(z) < 5);
        assertClosed(m);
        double v0 = volume(m);
        float[] b0 = m.bounds();
        Mesh s = Decimate.simplify(m, 200);
        assertTrue(m.triangleCount() + " → " + s.triangleCount(), s.triangleCount() <= 200);
        assertClosed(s);
        assertEquals("volumen", v0, volume(s), 0.03 * v0);
        float[] b1 = s.bounds();
        for (int k = 0; k < 6; k++) assertEquals("borde " + k, b0[k], b1[k], 0.5);
    }

    @Test
    public void unaEsferaConservaSuRedondez() {
        Mesh m = voxels(40, (x, y, z) -> x * x + y * y + z * z < 15 * 15);
        double v0 = volume(m);
        Mesh s = Decimate.simplify(m, 500);
        assertTrue(s.triangleCount() <= 500);
        assertClosed(s);
        assertEquals("volumen", v0, volume(s), 0.03 * v0);
        // Todos los vértices siguen cerca de la esfera original
        double[] r0 = radii(m), r1 = radii(s);
        assertTrue(r1[0] > r0[0] - 0.5 && r1[1] < r0[1] + 0.5);
    }
}
