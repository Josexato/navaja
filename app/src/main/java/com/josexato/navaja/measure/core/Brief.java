package com.josexato.navaja.measure.core;

import java.util.Random;

/**
 * "Huella" binaria de 256 bits del entorno de un punto (BRIEF orientado,
 * como ORB): compara 256 pares de píxeles de la imagen suavizada alrededor
 * del punto, girados según la orientación propia del parche, así sobrevive
 * a que el celular se gire. Sirve para reconocer un punto ya conocido
 * después de perderle el rastro (imagen movida, objeto que tapa la vista).
 */
public final class Brief {

    private Brief() { }

    public static final int RADIUS = 13, MARGIN = 20;
    private static final float[] PAIRS = new float[256 * 4];

    static {
        Random r = new Random(1234);
        for (int i = 0; i < 256; i++)
            for (int k = 0; k < 4; k++) {
                double v;
                do v = r.nextGaussian() * RADIUS / 2.5; while (Math.abs(v) > RADIUS);
                PAIRS[4 * i + k] = (float) v;
            }
    }

    /** Imagen suavizada (caja de 5×5 por imagen integral). */
    public static float[] smooth(float[] g, int w, int h) {
        double[] in = new double[(w + 1) * (h + 1)];
        for (int y = 0; y < h; y++) {
            double row = 0;
            for (int x = 0; x < w; x++) {
                row += g[y * w + x];
                in[(y + 1) * (w + 1) + x + 1] = in[y * (w + 1) + x + 1] + row;
            }
        }
        float[] out = new float[w * h];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int x0 = Math.max(0, x - 2), x1 = Math.min(w, x + 3), y0 = Math.max(0, y - 2), y1 = Math.min(h, y + 3);
                double s = in[y1 * (w + 1) + x1] - in[y0 * (w + 1) + x1] - in[y1 * (w + 1) + x0] + in[y0 * (w + 1) + x0];
                out[y * w + x] = (float) (s / ((x1 - x0) * (y1 - y0)));
            }
        return out;
    }

    /** Huella en (x, y), o null si el punto está muy cerca del borde. */
    public static long[] describe(float[] sm, int w, int h, float x, float y) {
        int cx = Math.round(x), cy = Math.round(y);
        if (cx < MARGIN || cy < MARGIN || cx >= w - MARGIN || cy >= h - MARGIN) return null;
        // Orientación por el centroide de intensidad
        double m10 = 0, m01 = 0;
        for (int dy = -RADIUS; dy <= RADIUS; dy++)
            for (int dx = -RADIUS; dx <= RADIUS; dx++) {
                if (dx * dx + dy * dy > RADIUS * RADIUS) continue;
                float v = sm[(cy + dy) * w + cx + dx];
                m10 += dx * v;
                m01 += dy * v;
            }
        double a = Math.atan2(m01, m10), c = Math.cos(a), s = Math.sin(a);
        long[] d = new long[4];
        for (int i = 0; i < 256; i++) {
            float ax = PAIRS[4 * i], ay = PAIRS[4 * i + 1], bx = PAIRS[4 * i + 2], by = PAIRS[4 * i + 3];
            int pax = cx + (int) Math.round(c * ax - s * ay), pay = cy + (int) Math.round(s * ax + c * ay);
            int pbx = cx + (int) Math.round(c * bx - s * by), pby = cy + (int) Math.round(s * bx + c * by);
            if (sm[pay * w + pax] < sm[pby * w + pbx]) d[i >> 6] |= 1L << (i & 63);
        }
        return d;
    }

    public static int distance(long[] a, long[] b) {
        return Long.bitCount(a[0] ^ b[0]) + Long.bitCount(a[1] ^ b[1]) + Long.bitCount(a[2] ^ b[2]) + Long.bitCount(a[3] ^ b[3]);
    }
}
