package com.josexato.navaja.measure.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Seguimiento de puntos entre cuadros consecutivos de un video (Lucas-Kanade
 * piramidal) y detección de esquinas buenas para seguir (Shi-Tomasi). Usa la
 * continuidad del video: cada punto se busca cerca de donde estaba, primero
 * en una versión reducida de la imagen y luego afinando. Un punto se acepta
 * sólo si al seguirlo de vuelta regresa a su lugar (chequeo ida y vuelta).
 */
public final class Klt {

    private Klt() { }

    public static final int LEVELS = 4, HALF = 5;

    /** Pirámide de imágenes en gris (nivel 0 = original). */
    public static final class Pyramid {
        public final float[][] img;
        public final int[] w, h;

        public Pyramid(float[] gray, int width, int height) {
            img = new float[LEVELS][];
            w = new int[LEVELS];
            h = new int[LEVELS];
            img[0] = gray;
            w[0] = width;
            h[0] = height;
            for (int l = 1; l < LEVELS; l++) {
                int pw = w[l - 1], ph = h[l - 1], nw = pw / 2, nh = ph / 2;
                float[] src = img[l - 1], dst = new float[nw * nh];
                for (int y = 0; y < nh; y++)
                    for (int x = 0; x < nw; x++) {
                        int i = 2 * y * pw + 2 * x;
                        dst[y * nw + x] = (src[i] + src[i + 1] + src[i + pw] + src[i + pw + 1]) * 0.25f;
                    }
                img[l] = dst;
                w[l] = nw;
                h[l] = nh;
            }
        }

        float at(int l, double x, double y) {
            int ww = w[l], hh = h[l];
            if (x < 0) x = 0;
            if (y < 0) y = 0;
            if (x > ww - 1.001) x = ww - 1.001;
            if (y > hh - 1.001) y = hh - 1.001;
            int x0 = (int) x, y0 = (int) y;
            double fx = x - x0, fy = y - y0;
            float[] im = img[l];
            int i = y0 * ww + x0;
            return (float) ((im[i] * (1 - fx) + im[i + 1] * fx) * (1 - fy) + (im[i + ww] * (1 - fx) + im[i + ww + 1] * fx) * fy);
        }
    }

    /** Gris (0..255) desde ARGB. */
    public static float[] gray(int[] argb) {
        float[] g = new float[argb.length];
        for (int i = 0; i < argb.length; i++) {
            int c = argb[i];
            g[i] = 0.299f * ((c >> 16) & 255) + 0.587f * ((c >> 8) & 255) + 0.114f * (c & 255);
        }
        return g;
    }

    /**
     * Esquinas de Shi-Tomasi: el menor autovalor del tensor de estructura en
     * una ventana de 7×7. Una por celda de {@code cell} píxeles, lejos de las
     * ya existentes ({@code taken}), y sólo si superan el 1 % de la mejor.
     */
    public static List<float[]> corners(float[] g, int w, int h, int cell, int max, List<float[]> taken) {
        float[] gx = new float[w * h], gy = new float[w * h];
        for (int y = 1; y < h - 1; y++)
            for (int x = 1; x < w - 1; x++) {
                int i = y * w + x;
                gx[i] = (g[i + 1] - g[i - 1]) * 0.5f;
                gy[i] = (g[i + w] - g[i - w]) * 0.5f;
            }
        // Imágenes integrales de Ixx, Ixy, Iyy
        double[] sxx = new double[(w + 1) * (h + 1)], sxy = new double[(w + 1) * (h + 1)], syy = new double[(w + 1) * (h + 1)];
        for (int y = 0; y < h; y++) {
            double rxx = 0, rxy = 0, ryy = 0;
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                rxx += gx[i] * gx[i];
                rxy += gx[i] * gy[i];
                ryy += gy[i] * gy[i];
                int j = (y + 1) * (w + 1) + x + 1;
                sxx[j] = sxx[j - w - 1] + rxx;
                sxy[j] = sxy[j - w - 1] + rxy;
                syy[j] = syy[j - w - 1] + ryy;
            }
        }
        int r = 3, margin = HALF + 4;
        float[] score = new float[w * h];
        float best = 0;
        for (int y = margin; y < h - margin; y++)
            for (int x = margin; x < w - margin; x++) {
                int a = (y - r) * (w + 1) + (x - r), b = (y - r) * (w + 1) + (x + r + 1), c = (y + r + 1) * (w + 1) + (x - r),
                    d = (y + r + 1) * (w + 1) + (x + r + 1);
                double xx = sxx[d] - sxx[b] - sxx[c] + sxx[a], xy = sxy[d] - sxy[b] - sxy[c] + sxy[a], yy = syy[d] - syy[b] - syy[c] + syy[a];
                double m = (xx + yy) / 2 - Math.sqrt((xx - yy) * (xx - yy) / 4 + xy * xy);
                score[y * w + x] = (float) m;
                if (m > best) best = (float) m;
            }
        boolean[] occupied = new boolean[((w + cell - 1) / cell) * ((h + cell - 1) / cell)];
        int cw = (w + cell - 1) / cell;
        if (taken != null) for (float[] p : taken) {
            int cx = (int) p[0] / cell, cy = (int) p[1] / cell;
            if (cx >= 0 && cy >= 0 && cx < cw && cy * cw + cx < occupied.length) occupied[cy * cw + cx] = true;
        }
        List<float[]> out = new ArrayList<>();
        float thr = best * 0.01f;
        for (int cy = 0; cy * cell < h; cy++)
            for (int cx = 0; cx * cell < w; cx++) {
                if (occupied[cy * cw + cx]) continue;
                float m = thr;
                int bx = -1, by = -1;
                for (int y = cy * cell; y < Math.min(h, (cy + 1) * cell); y++)
                    for (int x = cx * cell; x < Math.min(w, (cx + 1) * cell); x++)
                        if (score[y * w + x] > m) { m = score[y * w + x]; bx = x; by = y; }
                if (bx >= 0) out.add(new float[]{bx, by, m});
            }
        out.sort((p, q) -> Float.compare(q[2], p[2]));
        return out.size() > max ? new ArrayList<>(out.subList(0, max)) : out;
    }

    /**
     * Sigue {@code pts} de {@code a} a {@code b}. Devuelve las posiciones nuevas
     * (null donde se perdió el punto).
     */
    public static float[][] track(Pyramid a, Pyramid b, List<float[]> pts) {
        float[][] fwd = new float[pts.size()][];
        for (int k = 0; k < pts.size(); k++) fwd[k] = lk(a, b, pts.get(k)[0], pts.get(k)[1], pts.get(k)[0], pts.get(k)[1]);
        // Ida y vuelta: el punto seguido de b hacia a debe volver a su lugar (< 1 px)
        for (int k = 0; k < pts.size(); k++) {
            if (fwd[k] == null) continue;
            float[] back = lk(b, a, fwd[k][0], fwd[k][1], pts.get(k)[0], pts.get(k)[1]);
            if (back == null || Math.hypot(back[0] - pts.get(k)[0], back[1] - pts.get(k)[1]) > 1.0) fwd[k] = null;
        }
        return fwd;
    }

    private static float[] lk(Pyramid a, Pyramid b, double px, double py, double gx0, double gy0) {
        int n = (2 * HALF + 1) * (2 * HALF + 1);
        float[] tI = new float[n], tx = new float[n], ty = new float[n];
        double gx = (gx0 - px) / (1 << (LEVELS - 1)), gy = (gy0 - py) / (1 << (LEVELS - 1));
        for (int l = LEVELS - 1; l >= 0; l--) {
            double s = 1.0 / (1 << l), cx = px * s, cy = py * s;
            if (cx < HALF + 1 || cy < HALF + 1 || cx > a.w[l] - HALF - 2 || cy > a.h[l] - HALF - 2) {
                if (l == 0) return null;
                gx *= 2;
                gy *= 2;
                continue;
            }
            double gxx = 0, gxy = 0, gyy = 0;
            int k = 0;
            for (int dy = -HALF; dy <= HALF; dy++)
                for (int dx = -HALF; dx <= HALF; dx++, k++) {
                    double x = cx + dx, y = cy + dy;
                    tI[k] = a.at(l, x, y);
                    tx[k] = (a.at(l, x + 1, y) - a.at(l, x - 1, y)) * 0.5f;
                    ty[k] = (a.at(l, x, y + 1) - a.at(l, x, y - 1)) * 0.5f;
                    gxx += tx[k] * tx[k];
                    gxy += tx[k] * ty[k];
                    gyy += ty[k] * ty[k];
                }
            double det = gxx * gyy - gxy * gxy;
            if (det < 1e-6 * n * n) return null; // zona sin textura
            double vx = 0, vy = 0;
            for (int it = 0; it < 12; it++) {
                double bx = 0, by = 0;
                k = 0;
                for (int dy = -HALF; dy <= HALF; dy++)
                    for (int dx = -HALF; dx <= HALF; dx++, k++) {
                        double e = tI[k] - b.at(l, cx + gx + vx + dx, cy + gy + vy + dy);
                        bx += e * tx[k];
                        by += e * ty[k];
                    }
                double ux = (gyy * bx - gxy * by) / det, uy = (gxx * by - gxy * bx) / det;
                vx += ux;
                vy += uy;
                if (ux * ux + uy * uy < 1e-4) break;
            }
            gx += vx;
            gy += vy;
            if (l > 0) {
                gx *= 2;
                gy *= 2;
            }
        }
        double nx = px + gx, ny = py + gy;
        // Fuera de la imagen (o tan al borde que la ventana se sale): el punto se perdió
        if (nx < HALF + 1 || ny < HALF + 1 || nx > a.w[0] - HALF - 2 || ny > a.h[0] - HALF - 2) return null;
        // La ventana final debe parecerse a la original (descarta oclusiones y deslizamientos)
        double err = 0, mean = 0, mad = 0;
        float[] ta = new float[n];
        int k = 0;
        for (int dy = -HALF; dy <= HALF; dy++)
            for (int dx = -HALF; dx <= HALF; dx++, k++) {
                ta[k] = a.at(0, px + dx, py + dy);
                err += Math.abs(ta[k] - b.at(0, nx + dx, ny + dy));
                mean += ta[k];
            }
        mean /= n;
        for (float v : ta) mad += Math.abs(v - mean);
        // Diferencia media relativa al contraste propio de la ventana
        if (err > 0.7 * mad) return null;
        return new float[]{(float) nx, (float) ny};
    }
}
