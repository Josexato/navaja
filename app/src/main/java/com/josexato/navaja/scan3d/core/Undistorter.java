package com.josexato.navaja.scan3d.core;

/**
 * Corrige la distorsión de lente con el modelo de Android (LENS_DISTORTION):
 * para cada píxel ideal (x_i, y_i) normalizado con los intrínsecos,
 *   x_c = x_i (1 + k1 r² + k2 r⁴ + k3 r⁶) + 2 p1 x_i y_i + p2 (r² + 2 x_i²)
 *   y_c = y_i (1 + k1 r² + k2 r⁴ + k3 r⁶) + p1 (r² + 2 y_i²) + 2 p2 x_i y_i
 * y se muestrea la imagen de entrada (distorsionada) en (x_c, y_c).
 * El mapa se precalcula una vez; aplicarlo es un remuestreo bilineal.
 */
public final class Undistorter {

    public final int w, h;
    public final double fx, fy, cx, cy;
    private final float[] mapX, mapY;

    /**
     * @param k coeficientes de Android en su orden: {k1, k2, k3, p1, p2}
     */
    public Undistorter(int w, int h, double fx, double fy, double cx, double cy, float[] k) {
        this.w = w; this.h = h; this.fx = fx; this.fy = fy; this.cx = cx; this.cy = cy;
        mapX = new float[w * h];
        mapY = new float[w * h];
        double k1 = k[0], k2 = k[1], k3 = k[2], p1 = k[3], p2 = k[4];
        for (int v = 0; v < h; v++) {
            double y = (v - cy) / fy;
            for (int u = 0; u < w; u++) {
                double x = (u - cx) / fx;
                double r2 = x * x + y * y;
                double rad = 1 + r2 * (k1 + r2 * (k2 + r2 * k3));
                double xc = x * rad + 2 * p1 * x * y + p2 * (r2 + 2 * x * x);
                double yc = y * rad + p1 * (r2 + 2 * y * y) + 2 * p2 * x * y;
                mapX[v * w + u] = (float) (fx * xc + cx);
                mapY[v * w + u] = (float) (fy * yc + cy);
            }
        }
    }

    /** ¿La distorsión desplaza algún píxel más de 0.5 px? Si no, no merece la pena corregir. */
    public double maxShiftPx() {
        double m = 0;
        for (int v = 0; v < h; v++)
            for (int u = 0; u < w; u++) {
                int i = v * w + u;
                m = Math.max(m, Math.hypot(mapX[i] - u, mapY[i] - v));
            }
        return m;
    }

    /** Devuelve la imagen ARGB corregida (fuera de la imagen original: negro). */
    public int[] apply(int[] argb) {
        int[] out = new int[w * h];
        for (int i = 0; i < out.length; i++) {
            int c = Segmenter.sampleArgb(argb, w, h, mapX[i], mapY[i]);
            out[i] = c == 0 ? 0xFF000000 : c;
        }
        return out;
    }

    /** Posición en la imagen distorsionada que corresponde al píxel ideal (u, v). */
    public double[] distortedOf(int u, int v) {
        int i = v * w + u;
        return new double[]{mapX[i], mapY[i]};
    }
}
