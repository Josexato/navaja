package com.josexato.navaja.measure.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;
import java.util.Random;

public class MarkersTest {

    static final int W = 720, H = 1280;
    static final double F = 1050;

    /** Foto sintética de la hoja vista por {@code p}: marcas negras sobre papel blanco, mesa gris alrededor, 2×2 muestras por píxel y ruido. */
    static float[] render(Geometry.Pose p, Random rnd) {
        float[] g = new float[W * H];
        double[] o = p.center(), rt = Lin.tr(p.r);
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++) {
                double s = 0;
                for (int sy = 0; sy < 2; sy++)
                    for (int sx = 0; sx < 2; sx++) {
                        double u = x - 0.25 + 0.5 * sx, v = y - 0.25 + 0.5 * sy;
                        double[] d = Lin.mulV(rt, new double[]{(u - W / 2.0) / F, (v - H / 2.0) / F, 1});
                        if (Math.abs(d[2]) < 1e-9) { s += 110; continue; }
                        double t = -o[2] / d[2];
                        if (t <= 0) { s += 110; continue; }
                        double X = o[0] + t * d[0], Y = o[1] + t * d[1];
                        double px = X + Sheet.PAGE_W / 2, py = Sheet.PAGE_H / 2 - Y;
                        if (px < 0 || py < 0 || px > Sheet.PAGE_W || py > Sheet.PAGE_H) s += 110 + 20 * Math.sin(X * 0.3) * Math.cos(Y * 0.2);
                        else s += Sheet.blackAtPage(px, py) ? 35 : 220;
                    }
                g[y * W + x] = (float) (s / 4 + rnd.nextGaussian() * 4);
            }
        return g;
    }

    /** Cámara en {@code c} mirando a {@code t}, con el "arriba" de la imagen hacia {@code up}. */
    static Geometry.Pose look(double[] c, double[] t, double[] up) {
        double[] z = Lin.unit(Lin.sub(t, c));
        double[] x = Lin.unit(Lin.cross(Lin.scale(up, -1), z));
        double[] y = Lin.cross(z, x);
        Geometry.Pose p = new Geometry.Pose();
        p.r = new double[]{x[0], x[1], x[2], y[0], y[1], y[2], z[0], z[1], z[2]};
        p.t = Lin.scale(Lin.mulV(p.r, c), -1);
        return p;
    }

    /** Detecta y compara con las esquinas proyectadas: {encontradas, error máximo de esquina (px)}. */
    static double[] check(Geometry.Pose p, long seed) {
        List<Markers.Marker> ms = Markers.detect(render(p, new Random(seed)), W, H);
        double worst = 0;
        for (Markers.Marker m : ms) {
            double[][] c3 = Sheet.corners(m.id);
            for (int k = 0; k < 4; k++) {
                double[] xc = p.apply(c3[k]);
                double u = F * xc[0] / xc[2] + W / 2.0, v = F * xc[1] / xc[2] + H / 2.0;
                worst = Math.max(worst, Math.hypot(u - m.corners[k][0], v - m.corners[k][1]));
            }
        }
        return new double[]{ms.size(), worst};
    }

    @Test
    public void desdeArribaSeVenTodas() {
        // A 45 cm, con la hoja derecha en la foto
        double[] r = check(look(new double[]{0, 0, 450}, new double[]{0, 0, 0}, new double[]{0, 1, 0}), 1);
        assertEquals(18, r[0], 0);
        assertTrue("error de esquina " + r[1], r[1] < 0.6);
    }

    @Test
    public void inclinadaYGiradaTambien() {
        // Desde un costado, 50° de inclinación, y con la hoja al revés en la foto (cámara del otro lado)
        double[] a = check(look(new double[]{0, -330, 280}, new double[]{0, 0, 0}, new double[]{0, 0, 1}), 2);
        assertTrue("inclinada: " + a[0], a[0] >= 14);
        assertTrue("error de esquina " + a[1], a[1] < 1.0);
        double[] b = check(look(new double[]{250, 250, 300}, new double[]{0, 0, 0}, new double[]{0, 0, 1}), 3);
        assertTrue("en diagonal: " + b[0], b[0] >= 12);
        assertTrue("error de esquina " + b[1], b[1] < 1.0);
    }

    @Test
    public void elDiccionarioSeparaBien() {
        // Ningún par de códigos (con cualquier giro) a menos de 6 bits, y ninguno a menos de 6 de sus propios giros
        for (int i = 0; i < Sheet.count(); i++) {
            int[] ri = rotations(Sheet.CODES[i]);
            for (int k = 1; k < 4; k++) assertTrue(Integer.bitCount(ri[0] ^ ri[k]) >= 6);
            for (int j = i + 1; j < Sheet.count(); j++)
                for (int k = 0; k < 4; k++) assertTrue(Integer.bitCount(ri[k] ^ Sheet.CODES[j]) >= 6);
        }
    }

    static int[] rotations(int c) {
        int[] out = new int[4];
        out[0] = c;
        for (int k = 1; k < 4; k++) {
            int prev = out[k - 1], r = 0;
            for (int row = 0; row < 4; row++)
                for (int col = 0; col < 4; col++) r = (r << 1) | (Sheet.bit(prev, 3 - col, row) ? 1 : 0);
            out[k] = r;
        }
        return out;
    }

    @Test
    public void unaFotoSinHojaNoDaMarcas() {
        Random rnd = new Random(4);
        float[] g = new float[W * H];
        // Cuadrados oscuros al azar (como teclas): ninguno es una marca del diccionario
        for (int i = 0; i < g.length; i++) g[i] = 200;
        for (int k = 0; k < 60; k++) {
            int x0 = rnd.nextInt(W - 60), y0 = rnd.nextInt(H - 60), s = 20 + rnd.nextInt(30);
            for (int y = y0; y < y0 + s; y++) for (int x = x0; x < x0 + s; x++) g[y * W + x] = rnd.nextInt(3) == 0 ? 200 : 40;
        }
        assertEquals(0, Markers.detect(g, W, H).size());
    }
}
