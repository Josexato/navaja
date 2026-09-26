package com.josexato.navaja.scan3d.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class UndistorterTest {

    /** Coeficientes reales de la cámara principal del S26 Ultra (id 5), escalados a 1280x960. */
    private static final float[] K_MAIN = {0.1206207f, -0.3267061f, 0.2481931f, 0, 0};

    @Test
    public void zeroCoefficientsIsIdentity() {
        Undistorter u = new Undistorter(64, 48, 50, 50, 32, 24, new float[5]);
        assertEquals(0, u.maxShiftPx(), 1e-4);
        int[] img = new int[64 * 48];
        for (int i = 0; i < img.length; i++) img[i] = 0xFF000000 | (i * 7919 & 0xFFFFFF);
        int[] out = u.apply(img);
        for (int y = 0; y < 47; y++)
            for (int x = 0; x < 63; x++) assertEquals(img[y * 64 + x], out[y * 64 + x]);
    }

    @Test
    public void straightLineBecomesStraight() {
        // Se dibuja una recta ideal "distorsionándola" con el modelo inverso aproximado
        // (muestreo denso de la recta ideal mapeada con el propio mapa) y se comprueba
        // que tras corregir vuelve a estar en la misma fila.
        int w = 1280, h = 960;
        double s = 1280.0 / 4080;
        Undistorter u = new Undistorter(w, h, 2808.68 * s, 2813.25 * s, 2044.14 * s, 1519.98 * s, K_MAIN);
        double shift = u.maxShiftPx();
        System.out.println("Desplazamiento máx. por distorsión (cam principal): " + shift + " px");
        assertTrue("la cámara principal sí tiene distorsión apreciable", shift > 2);
        int[] img = new int[w * h];
        java.util.Arrays.fill(img, 0xFFFFFFFF);
        int row = 850; // cerca del borde, donde más se nota
        for (int x = 0; x < w; x++) {
            double[] d = u.distortedOf(x, row);
            int dx = (int) Math.round(d[0]), dy = (int) Math.round(d[1]);
            for (int yy = dy - 1; yy <= dy + 1; yy++)
                for (int xx = dx - 1; xx <= dx + 1; xx++)
                    if (xx >= 0 && yy >= 0 && xx < w && yy < h) img[yy * w + xx] = 0xFF000000;
        }
        int[] out = u.apply(img);
        int bad = 0;
        for (int x = 100; x < w - 100; x++) if ((out[row * w + x] & 0xFF) > 128) bad++;
        assertTrue("la recta corregida debe quedar en su fila (fallos=" + bad + ")", bad < 20);
    }
}
