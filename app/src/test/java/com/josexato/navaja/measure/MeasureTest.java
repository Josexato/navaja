package com.josexato.navaja.measure;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.josexato.navaja.measure.core.Geometry;
import com.josexato.navaja.measure.core.Klt;
import com.josexato.navaja.measure.core.Lin;
import com.josexato.navaja.measure.core.Measure;
import com.josexato.navaja.measure.core.Sfm;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class MeasureTest {

    @Test
    public void rotacionesIdaYVuelta() {
        double[] w = {0.3, -0.2, 0.9};
        double[] back = Lin.logRot(Lin.rodrigues(w));
        for (int i = 0; i < 3; i++) assertEquals(w[i], back[i], 1e-9);
        Object[] s = Lin.svd3(new double[]{2, 0.3, 0.1, -0.4, 1, 0.2, 0.3, 0.5, 3});
        double[] u = (double[]) s[0], sv = (double[]) s[1], v = (double[]) s[2];
        double[] rec = Lin.mul(Lin.mul(u, new double[]{sv[0], 0, 0, 0, sv[1], 0, 0, 0, sv[2]}), Lin.tr(v));
        double[] a = {2, 0.3, 0.1, -0.4, 1, 0.2, 0.3, 0.5, 3};
        for (int i = 0; i < 9; i++) assertEquals(a[i], rec[i], 1e-8);
    }

    /** El seguimiento encuentra un desplazamiento conocido en una imagen con textura. */
    @Test
    public void kltSigueUnDesplazamiento() {
        int w = 320, h = 240;
        Random r = new Random(1);
        // Textura "natural": manchas de todos los tamaños (como una pared o un objeto real)
        int W = w + 40, H = h + 40;
        float[] sm = new float[W * H];
        for (int b = 0; b < 600; b++) {
            double bx = r.nextDouble() * W, by = r.nextDouble() * H, rad = 2 + Math.pow(r.nextDouble(), 2) * 30, amp = r.nextGaussian() * 60;
            int x0 = (int) Math.max(0, bx - 3 * rad), x1 = (int) Math.min(W - 1, bx + 3 * rad);
            int y0 = (int) Math.max(0, by - 3 * rad), y1 = (int) Math.min(H - 1, by + 3 * rad);
            for (int y = y0; y <= y1; y++)
                for (int x = x0; x <= x1; x++) sm[y * W + x] += (float) (amp * Math.exp(-((x - bx) * (x - bx) + (y - by) * (y - by)) / (2 * rad * rad)));
        }
        for (int i = 0; i < sm.length; i++) sm[i] += 128;
        double dx = 7.4, dy = -3.2;
        int[] a = new int[w * h], b = new int[w * h];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                a[y * w + x] = gray(sample(sm, w + 40, x + 20, y + 20));
                b[y * w + x] = gray(sample(sm, w + 40, x + 20 - dx, y + 20 - dy));
            }
        Klt.Pyramid pa = new Klt.Pyramid(Klt.gray(a), w, h), pb = new Klt.Pyramid(Klt.gray(b), w, h);
        List<float[]> c = Klt.corners(pa.img[0], w, h, 20, 80, null);
        assertTrue(c.size() > 40);
        float[][] t = Klt.track(pa, pb, c);
        int ok = 0;
        for (int k = 0; k < c.size(); k++)
            if (t[k] != null && Math.abs(t[k][0] - c.get(k)[0] - dx) < 0.3 && Math.abs(t[k][1] - c.get(k)[1] - dy) < 0.3) ok++;
        assertTrue(ok + " de " + c.size(), ok > 0.8 * c.size());
        // Limitadas a un rectángulo: sólo ahí, y más densas con celdas chicas
        int[] rect = {100, 60, 180, 140};
        List<float[]> z = Klt.corners(pa.img[0], w, h, 4, 1000, null, rect);
        assertTrue("esquinas en la zona " + z.size(), z.size() > 60);
        for (float[] q : z) assertTrue(q[0] >= rect[0] && q[1] >= rect[1] && q[0] < rect[2] && q[1] < rect[3]);
        assertTrue(Klt.corners(pa.img[0], w, h, 4, 1000, null, new int[]{330, 250, 400, 300}).isEmpty()); // fuera de la imagen
    }

    /** La zona (esfera del modelo) se ve en cada cuadro dentro de su rectángulo, con el radio proyectado. */
    @Test
    public void zonaSeProyectaEnCadaCuadro() {
        Sfm.Result r = new Sfm.Result();
        r.width = 720;
        r.height = 1280;
        r.focal = 980;
        for (int k = 0; k < 10; k++) {
            double ang = Math.toRadians(-40 + 8 * k);
            r.camFrames.add(3 * k + 5);
            r.poses.add(lookAt(new double[]{50 * Math.sin(ang), 0, 50 * Math.cos(ang)}, new double[]{0, 0, 0}));
        }
        double[] c = {2, -3, 4};
        double rad = 3;
        Sfm.Zone zone = new Sfm.Zone(r, c, rad, 5);
        for (int k = 0; k < 10; k++) {
            int[] rect = zone.rect(3 * k); // cuadro del seguimiento = cuadro del video − 5
            assertNotNull(rect);
            double[] xc = r.poses.get(k).apply(c);
            double u = r.focal * xc[0] / xc[2] + r.width / 2.0, v = r.focal * xc[1] / xc[2] + r.height / 2.0;
            assertTrue(u > rect[0] && u < rect[2] && v > rect[1] && v < rect[3]);
            // Radio proyectado ×1,25 de margen
            assertEquals(2 * 1.25 * r.focal * rad / xc[2], rect[2] - rect[0], 2);
        }
        // Detrás de la cámara: no se ve
        assertEquals(null, new Sfm.Zone(r, new double[]{0, 0, 80}, rad, 5).rect(0));
    }

    static int gray(float v) {
        int g = Math.max(0, Math.min(255, Math.round(v)));
        return 0xFF000000 | g << 16 | g << 8 | g;
    }

    static float sample(float[] img, int w, double x, double y) {
        int x0 = (int) Math.floor(x), y0 = (int) Math.floor(y);
        double fx = x - x0, fy = y - y0;
        return (float) ((img[y0 * w + x0] * (1 - fx) + img[y0 * w + x0 + 1] * fx) * (1 - fy)
                + (img[(y0 + 1) * w + x0] * (1 - fx) + img[(y0 + 1) * w + x0 + 1] * fx) * fy);
    }

    /**
     * Escena sintética como la del video de la máscara: una pared con textura y
     * una caja de 20 × 15 × 10 cm colgada; la cámara recorre un arco de 110°
     * alrededor. Las pistas se generan proyectando con ruido de 0,5 px.
     */
    @Test
    public void reconstruyeYMideUnaCajaContraLaPared() {
        Random r = new Random(5);
        int w = 720, h = 1280;
        double f = 980;
        List<double[]> world = new ArrayList<>();
        List<Integer> col = new ArrayList<>();
        // Pared z = 0 (normal +z hacia la cámara), 1,2 × 1,2 m. Unidades: cm.
        for (int i = 0; i < 1500; i++) { world.add(new double[]{r.nextDouble() * 120 - 60, r.nextDouble() * 120 - 60, 0}); col.add(0xFFCCCCCC); }
        // Caja: alto 20 (eje y, "arriba" = −y en la imagen), ancho 15 (x), prof 10 (z), separada 5 cm de la pared
        for (int i = 0; i < 1200; i++) {
            double[] q;
            int face = r.nextInt(5);
            double a = r.nextDouble(), b = r.nextDouble();
            switch (face) {
                case 0: q = new double[]{-7.5 + 15 * a, -10 + 20 * b, 15}; break; // frente
                case 1: q = new double[]{-7.5, -10 + 20 * b, 5 + 10 * a}; break;
                case 2: q = new double[]{7.5, -10 + 20 * b, 5 + 10 * a}; break;
                case 3: q = new double[]{-7.5 + 15 * a, -10, 5 + 10 * b}; break;
                default: q = new double[]{-7.5 + 15 * a, 10, 5 + 10 * b};
            }
            world.add(q);
            col.add(0xFFE0A060);
        }
        int frames = 120;
        List<Geometry.Pose> cams = new ArrayList<>();
        for (int k = 0; k < frames; k++) {
            double ang = Math.toRadians(-55 + 110.0 * k / (frames - 1)), dist = 45 + 10 * Math.sin(k * 0.1);
            double[] c = {dist * Math.sin(ang), 4 * Math.sin(k * 0.07), 8 + dist * Math.cos(ang)};
            cams.add(lookAt(c, new double[]{0, 0, 8}));
        }
        List<Sfm.Track> tracks = tracks(world, col, cams, w, h, f, r);
        List<Sfm.Result> segs = Sfm.reconstruct(tracks, frames, w, h, 0.8 * h, null);
        assertEquals("un solo tramo (el video es continuo)", 1, segs.size());
        Sfm.Result res = segs.get(0);
        assertNotNull(res.points);
        assertTrue("cámaras " + res.poses.size(), res.poses.size() > 0.8 * res.keyframes);
        assertTrue("rms " + res.rms, res.rms < 1.0);
        assertEquals("focal estimada", f, res.focal, 0.05 * f);
        Measure.Choice ch = Measure.best(segs);
        assertNotNull(ch);
        Measure.Box box = ch.box;
        assertTrue(box.onWall);
        assertTrue(box.upKnown);
        assertTrue("arco " + ch.arcDeg, ch.arcDeg > 90);
        double s = 20 / box.alto; // escala con el alto conocido, como en la app
        assertEquals("ancho", 15, box.ancho * s, 15 * 0.04);
        assertEquals("profundidad", 10, box.prof * s, 10 * 0.08);
        assertEquals("separación", 5, box.separation * s, 1.0);

        // Segunda pasada densa: otros puntos de la misma escena, con las poses ya conocidas
        List<double[]> extra = new ArrayList<>();
        List<Integer> extraCol = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            extra.add(new double[]{-7.5 + 15 * r.nextDouble(), -10 + 20 * r.nextDouble(), 15});
            extraCol.add(0xFFE0A060);
        }
        for (int i = 0; i < 1000; i++) {
            extra.add(new double[]{r.nextDouble() * 120 - 60, r.nextDouble() * 120 - 60, 0});
            extraCol.add(0xFFCCCCCC);
        }
        int before = res.points.length, objBefore = box.objectPoints.length;
        double alto = box.alto, ancho = box.ancho;
        int added = Sfm.densify(res, tracks(extra, extraCol, cams, w, h, f, r), 0);
        assertTrue("agregados " + added, added > 1200);
        assertEquals(before + added, res.points.length);
        Measure.include(res, box, before);
        int obj = box.objectPoints.length - objBefore;
        // Los 1000 del frente de la caja entran (casi todos); los de la pared no
        assertTrue("al objeto " + obj, obj > 700 && obj < 1050);
        for (int k = objBefore; k < box.objectPoints.length; k++)
            assertEquals("frente de la caja (5 de separación + 10 de prof.)", 15, Lin.dot(Lin.sub(res.points[box.objectPoints[k]], box.planeP), box.planeN) * s, 1.0);
        assertEquals("la medida no cambia", alto, box.alto, 0);
        assertEquals(ancho, box.ancho, 0);
    }

    /** Pistas: cada punto se ve mientras cae en la imagen y está delante; ruido 0,5 px. */
    static List<Sfm.Track> tracks(List<double[]> world, List<Integer> col, List<Geometry.Pose> cams, int w, int h, double f, Random r) {
        List<Sfm.Track> tracks = new ArrayList<>();
        for (int i = 0; i < world.size(); i++) {
            Sfm.Track t = null;
            for (int k = 0; k < cams.size(); k++) {
                double[] xc = cams.get(k).apply(world.get(i));
                boolean vis = xc[2] > 1;
                double u = 0, v = 0;
                if (vis) {
                    u = f * xc[0] / xc[2] + w / 2.0 + r.nextGaussian() * 0.5;
                    v = f * xc[1] / xc[2] + h / 2.0 + r.nextGaussian() * 0.5;
                    vis = u > 5 && v > 5 && u < w - 5 && v < h - 5;
                }
                if (vis) {
                    if (t == null) t = new Sfm.Track(k, (float) u, (float) v, col.get(i));
                    else if (t.end() == k - 1) t.add((float) u, (float) v);
                    else break;
                } else if (t != null) break;
            }
            if (t != null && t.end() > t.start) tracks.add(t);
        }
        return tracks;
    }

    /** Cámara en {@code c} mirando a {@code target}, con y de la imagen hacia abajo (−y del mundo es "arriba"). */
    static Geometry.Pose lookAt(double[] c, double[] target) {
        double[] z = Lin.unit(Lin.sub(target, c));
        double[] down = {0, 1, 0};
        double[] x = Lin.unit(Lin.cross(down, z));
        double[] y = Lin.cross(z, x);
        Geometry.Pose p = new Geometry.Pose();
        p.r = new double[]{x[0], x[1], x[2], y[0], y[1], y[2], z[0], z[1], z[2]};
        p.t = Lin.scale(Lin.mulV(p.r, c), -1);
        return p;
    }
}
