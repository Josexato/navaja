package com.josexato.navaja.measure.core;

import java.nio.FloatBuffer;
import java.util.ArrayDeque;

/**
 * Entrada y salida del modelo de siluetas MagicTouch (MediaPipe, Apache 2.0):
 * entra una imagen de 512×512 con 4 canales (rojo, verde, azul en [0, 1] y
 * un "mapa previo" que marca dónde está el objeto) y sale la probabilidad de
 * objeto por píxel. El modelo corre en la capa Android; aquí sólo se arma la
 * entrada y se interpreta la salida, para poder probarlo sin teléfono.
 */
public final class SilhouetteIO {

    private SilhouetteIO() { }

    public static final int SIZE = 512;
    /** Ancho de la marca del toque en el mapa previo (px de la entrada de 512). */
    static final double SIGMA = 10;

    /**
     * Llena {@code out} (512·512·4 floats) con el cuadro reescalado y el toque
     * en ({@code px}, {@code py}) (píxeles del cuadro) como una mancha gaussiana.
     */
    public static void input(int[] argb, int w, int h, double px, double py, FloatBuffer out) {
        out.rewind();
        double kx = px * SIZE / w, ky = py * SIZE / h, inv = 1 / (2 * SIGMA * SIGMA);
        for (int y = 0; y < SIZE; y++) {
            double sy = Math.min(h - 1.001, Math.max(0, (y + 0.5) * h / SIZE - 0.5));
            int y0 = (int) sy;
            double fy = sy - y0;
            for (int x = 0; x < SIZE; x++) {
                double sx = Math.min(w - 1.001, Math.max(0, (x + 0.5) * w / SIZE - 0.5));
                int x0 = (int) sx;
                double fx = sx - x0;
                int a = argb[y0 * w + x0], b = argb[y0 * w + x0 + 1], c = argb[(y0 + 1) * w + x0], d = argb[(y0 + 1) * w + x0 + 1];
                for (int sh = 16; sh >= 0; sh -= 8) {
                    double v = (((a >> sh) & 255) * (1 - fx) + ((b >> sh) & 255) * fx) * (1 - fy)
                            + (((c >> sh) & 255) * (1 - fx) + ((d >> sh) & 255) * fx) * fy;
                    out.put((float) (v / 255));
                }
                double dx = x - kx, dy = y - ky;
                out.put((float) Math.exp(-(dx * dx + dy * dy) * inv));
            }
        }
        out.rewind();
    }

    /**
     * Silueta de {@code mw}×{@code mh} desde la salida del modelo (512·512
     * probabilidades): umbral 0,5 y sólo las regiones que contienen algún
     * punto del objeto ({@code seeds}: x, y intercalados en píxeles de un
     * cuadro de {@code w}×{@code h}). Así se descartan manchas sueltas de
     * pared o de otros objetos. Devuelve null si ninguna región tiene puntos.
     */
    public static byte[] mask(FloatBuffer prob, int mw, int mh, float[] seeds, int w, int h) {
        byte[] m = new byte[mw * mh];
        for (int y = 0; y < mh; y++) {
            int sy = Math.min(SIZE - 1, (int) ((y + 0.5) * SIZE / mh));
            for (int x = 0; x < mw; x++) {
                int sx = Math.min(SIZE - 1, (int) ((x + 0.5) * SIZE / mw));
                if (prob.get(sy * SIZE + sx) > 0.5f) m[y * mw + x] = 1;
            }
        }
        // Regiones (vecinos de 4) y cuáles tienen puntos del objeto
        int[] label = new int[mw * mh];
        int next = 0;
        ArrayDeque<Integer> q = new ArrayDeque<>();
        for (int s = 0; s < m.length; s++) {
            if (m[s] == 0 || label[s] != 0) continue;
            label[s] = ++next;
            q.add(s);
            while (!q.isEmpty()) {
                int id = q.poll(), x = id % mw, y = id / mw;
                if (x > 0) visit(m, label, q, id - 1, next);
                if (x < mw - 1) visit(m, label, q, id + 1, next);
                if (y > 0) visit(m, label, q, id - mw, next);
                if (y < mh - 1) visit(m, label, q, id + mw, next);
            }
        }
        boolean[] keep = new boolean[next + 1];
        boolean any = false;
        for (int k = 0; k + 1 < seeds.length; k += 2) {
            int x = (int) (seeds[k] * mw / w), y = (int) (seeds[k + 1] * mh / h);
            if (x < 0 || y < 0 || x >= mw || y >= mh) continue;
            int l = label[y * mw + x];
            if (l > 0) { keep[l] = true; any = true; }
        }
        if (!any) return null;
        for (int s = 0; s < m.length; s++) if (m[s] != 0 && !keep[label[s]]) m[s] = 0;
        return m;
    }

    private static void visit(byte[] m, int[] label, ArrayDeque<Integer> q, int id, int l) {
        if (m[id] != 0 && label[id] == 0) {
            label[id] = l;
            q.add(id);
        }
    }

    /** Mediana de los puntos (x, y intercalados): dónde va el toque. Null si no hay puntos. */
    public static double[] median(float[] seeds) {
        int n = seeds.length / 2;
        if (n == 0) return null;
        double[] xs = new double[n], ys = new double[n];
        for (int k = 0; k < n; k++) {
            xs[k] = seeds[2 * k];
            ys[k] = seeds[2 * k + 1];
        }
        return new double[]{Measure.percentile(xs, 50), Measure.percentile(ys, 50)};
    }
}
