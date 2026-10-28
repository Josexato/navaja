package com.josexato.navaja.measure.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Puntos multiescala de un cuadro, para reconocer lugares entre tramos
 * (cierre de bucle): esquinas buscadas en una pirámide de imágenes con
 * pasos de ×1,41 (6 escalas, de ×1 a ×5,7) y su huella BRIEF calculada en la
 * imagen de esa escala. Detectar en cada escala es lo que permite que un
 * punto visto de lejos se reconozca visto de cerca: con el video de la
 * máscara, entre el primer y el último cuadro (vistos a 0,69 de tamaño) da
 * ~120 coincidencias válidas, contra ~20 con las esquinas de una sola escala.
 */
public final class Features {

    private Features() { }

    static final int LEVELS = 6, PER_LEVEL = 200;

    /** Los puntos de un cuadro: posición en el cuadro original, escala y huella. */
    public static final class Set {
        public final int frame;
        public final float[] x, y, scale;
        public final long[][] desc;

        Set(int frame, int n) {
            this.frame = frame;
            x = new float[n];
            y = new float[n];
            scale = new float[n];
            desc = new long[n][];
        }

        public int size() { return x.length; }
    }

    /** Puntos multiescala del cuadro {@code gray} ({@code w}×{@code h}). */
    public static Set detect(int frame, float[] gray, int w, int h) {
        List<float[]> kp = new ArrayList<>();
        List<long[]> ds = new ArrayList<>();
        float[] img = gray;
        int iw = w, ih = h;
        for (int l = 0; l < LEVELS; l++) {
            double s = Math.pow(Math.sqrt(2), l);
            if (l > 0) {
                // Cada escala desde la original (promedio de área): sin acumular suavizados
                iw = (int) Math.round(w / s);
                ih = (int) Math.round(h / s);
                if (iw < 48 || ih < 48) break;
                img = resizeArea(gray, w, h, iw, ih);
            }
            float[] sm = Brief.smooth(img, iw, ih);
            int cell = Math.max(8, (int) Math.sqrt((double) iw * ih / PER_LEVEL));
            for (float[] c : Klt.corners(img, iw, ih, cell, PER_LEVEL, null)) {
                long[] d = Brief.describe(sm, iw, ih, c[0], c[1]);
                if (d == null) continue;
                kp.add(new float[]{(float) (c[0] * s), (float) (c[1] * s), (float) s});
                ds.add(d);
            }
        }
        Set out = new Set(frame, kp.size());
        for (int i = 0; i < kp.size(); i++) {
            out.x[i] = kp.get(i)[0];
            out.y[i] = kp.get(i)[1];
            out.scale[i] = kp.get(i)[2];
            out.desc[i] = ds.get(i);
        }
        return out;
    }

    /** Achica la imagen promediando el área que cubre cada píxel nuevo (sin aliasing). */
    static float[] resizeArea(float[] g, int w, int h, int nw, int nh) {
        float[] out = new float[nw * nh];
        double fx = (double) w / nw, fy = (double) h / nh;
        for (int y = 0; y < nh; y++) {
            int y0 = (int) (y * fy), y1 = Math.max(y0 + 1, (int) Math.min(h, Math.ceil((y + 1) * fy)));
            for (int x = 0; x < nw; x++) {
                int x0 = (int) (x * fx), x1 = Math.max(x0 + 1, (int) Math.min(w, Math.ceil((x + 1) * fx)));
                double acc = 0;
                for (int yy = y0; yy < y1; yy++)
                    for (int xx = x0; xx < x1; xx++) acc += g[yy * w + xx];
                out[y * nw + x] = (float) (acc / ((x1 - x0) * (y1 - y0)));
            }
        }
        return out;
    }
}
