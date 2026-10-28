package com.josexato.navaja.measure.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Random;

public class CloudTest {

    /** Superficie de una caja de 15 × 20 × 10 (puntos al azar en sus 6 caras), más {@code extra} puntos sueltos lejos. */
    static double[][] box(int n, int extra, Random rnd) {
        double[] s = {15, 20, 10};
        double[][] p = new double[n + extra][];
        for (int i = 0; i < n; i++) {
            double[] q = {rnd.nextDouble() * s[0], rnd.nextDouble() * s[1], rnd.nextDouble() * s[2]};
            int a = rnd.nextInt(3);
            q[a] = rnd.nextBoolean() ? 0 : s[a];
            p[i] = q;
        }
        for (int i = 0; i < extra; i++) p[n + i] = new double[]{-20 - 30 * rnd.nextDouble(), 30 * rnd.nextDouble(), 40 + 20 * rnd.nextDouble()};
        return p;
    }

    @Test
    public void dropsLoosePointsAndKeepsTheEdges() {
        Random rnd = new Random(1);
        double[][] p = box(3000, 30, rnd);
        boolean[] keep = Cloud.inliers(p);
        int lostBox = 0, keptLoose = 0;
        for (int i = 0; i < 3000; i++) if (!keep[i]) lostBox++;
        for (int i = 3000; i < p.length; i++) if (keep[i]) keptLoose++;
        assertEquals("sueltos que quedaron", 0, keptLoose);
        assertTrue("puntos de la caja perdidos " + lostBox, lostBox < 30);
        // Las aristas siguen: los extremos de la caja quedan dentro
        double maxX = 0, maxY = 0;
        for (int i = 0; i < 3000; i++) if (keep[i]) { maxX = Math.max(maxX, p[i][0]); maxY = Math.max(maxY, p[i][1]); }
        assertEquals(15, maxX, 0.3);
        assertEquals(20, maxY, 0.3);
    }

    @Test
    public void fewPointsAreLeftAlone() {
        boolean[] keep = Cloud.inliers(new double[][]{{0, 0, 0}, {1, 0, 0}, {50, 50, 50}});
        for (boolean k : keep) assertTrue(k);
    }

    @Test
    public void voxelsEvenOutTheDensity() {
        // 1000 puntos amontonados en un rincón y 100 repartidos: en vóxeles de 1 el rincón pesa como uno más
        Random rnd = new Random(2);
        double[][] p = new double[1100][];
        int[] col = new int[p.length];
        for (int i = 0; i < 1000; i++) { p[i] = new double[]{rnd.nextDouble() * 0.9, rnd.nextDouble() * 0.9, 0.5}; col[i] = 0xFFFF0000; }
        for (int i = 0; i < 100; i++) { p[1000 + i] = new double[]{1.5 + 2 * (i % 10), 1.5 + 2 * (i / 10), 0.5}; col[1000 + i] = 0xFF0000FF; }
        Cloud.Points v = Cloud.voxel(p, col, 1);
        assertEquals(101, v.size());
        int total = 0;
        for (int c : v.count) total += c;
        assertEquals(1100, total);
        for (int i = 0; i < v.size(); i++)
            if (v.count[i] == 1000) {
                assertEquals(0.45, v.p[i][0], 0.05);
                assertEquals(0xFFFF0000, v.color[i]);
            }
    }

    @Test
    public void normalsPointToTheCamera() {
        // Un plano z = 0 visto desde z = −10: normales (0, 0, −1)
        Random rnd = new Random(3);
        double[][] p = new double[500][];
        for (int i = 0; i < p.length; i++) p[i] = new double[]{rnd.nextDouble() * 10, rnd.nextDouble() * 10, 0.01 * rnd.nextGaussian()};
        double[][] n = Cloud.normals(p, new double[][]{{5, 5, -10}});
        for (double[] v : n) {
            assertNotNull(v);
            assertEquals(-1, v[2], 0.05);
        }
        // Visto desde el otro lado, al revés
        n = Cloud.normals(p, new double[][]{{5, 5, 10}});
        for (double[] v : n) assertEquals(1, v[2], 0.05);
    }

    @Test
    public void looseIsolatedPointHasNoNeighborsNearby() {
        double[][] p = box(800, 1, new Random(4));
        boolean[] keep = Cloud.inliers(p);
        assertFalse(keep[800]);
    }
}
