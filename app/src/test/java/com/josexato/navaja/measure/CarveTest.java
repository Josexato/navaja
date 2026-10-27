package com.josexato.navaja.measure;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.josexato.navaja.measure.core.Carve;
import com.josexato.navaja.measure.core.Geometry;
import com.josexato.navaja.measure.core.Measure;
import com.josexato.navaja.measure.core.Sfm;
import com.josexato.navaja.measure.core.SilhouetteIO;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class CarveTest {

    /**
     * Caja de 15 × 20 × 10 cm separada 5 cm de una pared, vista en un arco
     * de 90° con el vaivén vertical de un video a mano (±12 cm), con poses y
     * puntos exactos y siluetas perfectas: el casco tallado debe medir lo
     * mismo que la caja y coincidir con la medida por puntos. (Si todas las
     * cámaras estuvieran a la misma altura, nada recortaría arriba y abajo
     * de la caja y el alto saldría de más: es el límite de las siluetas.)
     */
    @Test
    public void tallaUnaCajaContraLaPared() {
        Random rnd = new Random(11);
        int w = 540, h = 960;
        double f = 720;
        List<double[]> world = new ArrayList<>();
        for (int i = 0; i < 1200; i++) world.add(new double[]{rnd.nextDouble() * 120 - 60, rnd.nextDouble() * 120 - 60, 0});
        for (int i = 0; i < 1500; i++) {
            double a = rnd.nextDouble(), b = rnd.nextDouble();
            switch (rnd.nextInt(5)) {
                case 0: world.add(new double[]{-7.5 + 15 * a, -10 + 20 * b, 15}); break;
                case 1: world.add(new double[]{-7.5, -10 + 20 * b, 5 + 10 * a}); break;
                case 2: world.add(new double[]{7.5, -10 + 20 * b, 5 + 10 * a}); break;
                case 3: world.add(new double[]{-7.5 + 15 * a, -10, 5 + 10 * b}); break;
                default: world.add(new double[]{-7.5 + 15 * a, 10, 5 + 10 * b});
            }
        }
        List<Geometry.Pose> cams = new ArrayList<>();
        int frames = 60;
        for (int k = 0; k < frames; k++) {
            double ang = Math.toRadians(-45 + 90.0 * k / (frames - 1));
            double[] c = {55 * Math.sin(ang), 12 * Math.sin(k * 0.25), 8 + 55 * Math.cos(ang)};
            cams.add(MeasureTest.lookAt(c, new double[]{0, 0, 8}));
        }
        // Resultado "reconstruido" exacto: puntos del mundo, pistas sin ruido, una cámara cada 2 cuadros
        Sfm.Result r = new Sfm.Result();
        r.width = w;
        r.height = h;
        r.focal = f;
        r.startFrame = 0;
        r.endFrame = frames - 1;
        for (int k = 0; k < frames; k += 2) {
            r.camFrames.add(k);
            r.poses.add(cams.get(k));
        }
        List<double[]> pts = new ArrayList<>();
        for (double[] x : world) {
            Sfm.Track t = null;
            for (int k = 0; k < frames; k++) {
                double[] xc = cams.get(k).apply(x);
                // Visible si está en la imagen y no lo tapa la caja (los de pared detrás de la caja no se ven)
                boolean vis = xc[2] > 1 && !(x[2] == 0 && behindBox(cams.get(k).center(), x));
                double u = f * xc[0] / xc[2] + w / 2.0, v = f * xc[1] / xc[2] + h / 2.0;
                vis &= u > 5 && v > 5 && u < w - 5 && v < h - 5;
                if (vis) {
                    if (t == null) t = new Sfm.Track(k, (float) u, (float) v, 0xFF808080);
                    else if (t.end() == k - 1) t.add((float) u, (float) v);
                    else break;
                } else if (t != null) break;
            }
            if (t == null || t.end() - t.start < 4) continue;
            pts.add(x);
            List<Sfm.Track> l = new ArrayList<>();
            l.add(t);
            r.pointTracks.add(l);
        }
        r.points = pts.toArray(new double[0][]);
        r.colors = new int[r.points.length];
        r.nObs = new int[r.points.length];
        Measure.Box box = Measure.measure(r);
        assertNotNull(box);
        assertTrue(box.onWall);
        // Siluetas exactas: la envolvente de las 8 esquinas proyectadas
        int mw = w / 2, mh = h / 2;
        byte[][] masks = new byte[r.poses.size()][];
        for (int c = 0; c < masks.length; c++) masks[c] = boxMask(r.poses.get(c), f, w, h, mw, mh);
        Carve.Result hull = Carve.carve(r, box, masks, mw, mh);
        assertNotNull(hull);
        assertEquals(30, hull.views);
        // En cm: el modelo está en cm (poses exactas)
        assertEquals("ancho", 15, hull.ancho, 1.0);
        assertEquals("alto", 20, hull.alto, 1.5);
        assertEquals("de la pared al frente", 15, hull.front, 1.0);
        assertTrue("diferencia " + Carve.disagreement(box, hull), Carve.disagreement(box, hull) < 0.08);
        // Sin siluetas suficientes no hay casco
        byte[][] few = new byte[masks.length][];
        System.arraycopy(masks, 0, few, 0, 5);
        assertNull(Carve.carve(r, box, few, mw, mh));
    }

    /** ¿El segmento cámara → punto de la pared cruza la caja? */
    static boolean behindBox(double[] cam, double[] x) {
        for (int s = 1; s < 200; s++) {
            double t = s / 200.0;
            double px = cam[0] + t * (x[0] - cam[0]), py = cam[1] + t * (x[1] - cam[1]), pz = cam[2] + t * (x[2] - cam[2]);
            if (Math.abs(px) < 7.5 && Math.abs(py) < 10 && pz > 5 && pz < 15) return true;
        }
        return false;
    }

    static byte[] boxMask(Geometry.Pose p, double f, int w, int h, int mw, int mh) {
        double[][] uv = new double[8][];
        for (int i = 0; i < 8; i++) {
            double[] x = {(i & 1) == 0 ? -7.5 : 7.5, (i & 2) == 0 ? -10 : 10, (i & 4) == 0 ? 5 : 15};
            double[] xc = p.apply(x);
            uv[i] = new double[]{(f * xc[0] / xc[2] + w / 2.0) * mw / w, (f * xc[1] / xc[2] + h / 2.0) * mh / h};
        }
        // Envolvente convexa (cadena monótona) y relleno
        java.util.Arrays.sort(uv, (a, b) -> a[0] != b[0] ? Double.compare(a[0], b[0]) : Double.compare(a[1], b[1]));
        double[][] hull = new double[16][];
        int k = 0;
        for (int pass = 0; pass < 2; pass++) {
            int start = k;
            for (int n = 0; n < 8; n++) {
                double[] q = uv[pass == 0 ? n : 7 - n];
                while (k >= start + 2 && cross(hull[k - 2], hull[k - 1], q) <= 0) k--;
                hull[k++] = q;
            }
            k--;
        }
        byte[] m = new byte[mw * mh];
        for (int y = 0; y < mh; y++)
            for (int x = 0; x < mw; x++) {
                boolean in = true;
                for (int e = 0; e < k && in; e++) in = cross(hull[e], hull[(e + 1) % k], new double[]{x + 0.5, y + 0.5}) >= 0;
                if (in) m[y * mw + x] = 1;
            }
        return m;
    }

    static double cross(double[] o, double[] a, double[] b) {
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
    }

    /** La entrada del modelo: color en [0, 1] y el toque como mancha con máximo 1 en su lugar. */
    @Test
    public void entradaDelModelo() {
        int w = 100, h = 200;
        int[] argb = new int[w * h];
        for (int i = 0; i < argb.length; i++) argb[i] = 0xFF000000 | (255 << 16) | (128 << 8);
        FloatBuffer in = ByteBuffer.allocateDirect(4 * 512 * 512 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        SilhouetteIO.input(argb, w, h, 25, 150, in);
        assertEquals(1.0, in.get(0), 1e-6);
        assertEquals(128 / 255.0, in.get(1), 1e-3);
        assertEquals(0.0, in.get(2), 1e-6);
        // Toque en (25, 150) de 100×200 → (128, 384) de 512
        int peak = (384 * 512 + 128) * 4 + 3;
        assertEquals(1.0, in.get(peak), 0.01);
        assertTrue(in.get(3) < 1e-6);
    }

    /** Silueta: umbral, y sólo las regiones con puntos del objeto (las manchas sueltas se descartan). */
    @Test
    public void siluetaSinManchasSueltas() {
        FloatBuffer prob = FloatBuffer.allocate(512 * 512);
        for (int y = 0; y < 512; y++)
            for (int x = 0; x < 512; x++) {
                boolean objeto = Math.hypot(x - 200, y - 200) < 80;
                boolean mancha = x > 420 && y > 420;
                prob.put(y * 512 + x, objeto || mancha ? 0.9f : 0.1f);
            }
        byte[] m = SilhouetteIO.mask(prob, 256, 256, new float[]{100, 100}, 256, 256);
        assertNotNull(m);
        assertEquals(1, m[100 * 256 + 100]);
        assertEquals("mancha descartada", 0, m[230 * 256 + 230]);
        assertEquals(0, m[10 * 256 + 10]);
        // Sin puntos dentro de ninguna región: sin silueta
        assertNull(SilhouetteIO.mask(prob, 256, 256, new float[]{10, 10}, 256, 256));
    }
}
