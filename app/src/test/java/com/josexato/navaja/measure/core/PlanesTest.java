package com.josexato.navaja.measure.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class PlanesTest {

    /** Cámara en {@code c} mirando a {@code t}, con y de la imagen hacia abajo (−y del mundo es "arriba"). */
    static Geometry.Pose lookAt(double[] c, double[] t) {
        double[] z = Lin.unit(Lin.sub(t, c));
        double[] x = Lin.unit(Lin.cross(new double[]{0, 1, 0}, z));
        double[] y = Lin.cross(z, x);
        Geometry.Pose p = new Geometry.Pose();
        p.r = new double[]{x[0], x[1], x[2], y[0], y[1], y[2], z[0], z[1], z[2]};
        p.t = Lin.scale(Lin.mulV(p.r, c), -1);
        return p;
    }

    /** Puntos al azar en las caras de la caja [lo, hi] (con ruido), salvo las de {@code skip} (eje·2 + lado). */
    static void box(List<double[]> out, double[] lo, double[] hi, int n, int skip, Random rnd) {
        for (int k = 0; k < n; k++) {
            int f;
            do f = rnd.nextInt(6); while (f == skip);
            double[] q = new double[3];
            for (int a = 0; a < 3; a++) q[a] = lo[a] + rnd.nextDouble() * (hi[a] - lo[a]);
            q[f / 2] = f % 2 == 0 ? lo[f / 2] : hi[f / 2];
            for (int a = 0; a < 3; a++) q[a] += 0.05 * rnd.nextGaussian();
            out.add(q);
        }
    }

    static Sfm.Result result(List<double[]> pts, List<Geometry.Pose> cams) {
        Sfm.Result r = new Sfm.Result();
        r.width = 1280;
        r.height = 720;
        r.focal = 1000;
        r.points = pts.toArray(new double[0][]);
        r.colors = new int[r.points.length];
        r.nObs = new int[r.points.length];
        for (int c = 0; c < cams.size(); c++) { r.poses.add(cams.get(c)); r.camFrames.add(c * 3); }
        return r;
    }

    /** Una caja colgada en la pared que toca el piso: el piso se quita y no infla la caja. */
    @Test
    public void quitaElPisoBajoUnObjetoEnLaPared() {
        Random rnd = new Random(1);
        List<double[]> pts = new ArrayList<>();
        for (int k = 0; k < 2500; k++) pts.add(new double[]{-40 + 80 * rnd.nextDouble(), -30 + 42 * rnd.nextDouble(), 0.05 * rnd.nextGaussian()});
        for (int k = 0; k < 1200; k++) pts.add(new double[]{-40 + 80 * rnd.nextDouble(), 12 + 0.05 * rnd.nextGaussian(), 40 * rnd.nextDouble()});
        box(pts, new double[]{-7.5, -8, 1}, new double[]{7.5, 12, 15}, 900, 4, rnd); // sin la cara de atrás
        List<Geometry.Pose> cams = new ArrayList<>();
        for (int c = 0; c < 20; c++) {
            double a = Math.toRadians(-40 + 4 * c);
            cams.add(lookAt(new double[]{70 * Math.sin(a), -15, 70 * Math.cos(a)}, new double[]{0, 2, 8}));
        }
        Measure.Box b = Measure.measure(result(pts, cams));
        assertNotNull(b);
        assertTrue(b.onWall);
        assertEquals("planos quitados", 1, b.otherPlanes.size());
        assertEquals(90, b.otherPlanes.get(0)[7], 5);
        assertEquals("ancho", 15, b.ancho, 1.5);
        assertEquals("alto", 20, b.alto, 1.5);
        assertEquals("de la pared al frente", 15, b.prof + b.separation, 1.5);
    }

    /** Un plano real deja a las cámaras de un lado; uno que corta el recorrido no (salvo las que pasan casi a ras). */
    @Test
    public void lasCamarasQuedanDeUnLado() {
        double[][] eyes = new double[40][];
        for (int c = 0; c < 40; c++) {
            double a = Math.toRadians(9 * c);
            eyes[c] = new double[]{30 * Math.sin(a), -20 + (c == 7 ? 21 : 0), 30 * Math.cos(a)}; // una cámara 1 por debajo
        }
        double[] origin = {0, 0, 0};
        assertTrue("la mesa", Measure.camerasOnOneSide(eyes, origin, new double[]{0, -1, 0}, 3));
        assertTrue("un plano vertical por el centro corta el recorrido", !Measure.camerasOnOneSide(eyes, origin, new double[]{1, 0, 0}, 3));
    }

    /** Una caja grande sobre la mesa: sus caras planas son del objeto (están al centro de las fotos) y no se quitan. */
    @Test
    public void noQuitaLasCarasDeUnaCaja() {
        Random rnd = new Random(2);
        List<double[]> pts = new ArrayList<>();
        for (int k = 0; k < 2500; k++) pts.add(new double[]{-50 + 100 * rnd.nextDouble(), 10 + 0.05 * rnd.nextGaussian(), -50 + 100 * rnd.nextDouble()});
        box(pts, new double[]{-10, -10, -8}, new double[]{10, 10, 8}, 1500, 3, rnd); // sin la cara de abajo
        List<Geometry.Pose> cams = new ArrayList<>();
        for (int c = 0; c < 24; c++) {
            double a = Math.toRadians(15 * c);
            cams.add(lookAt(new double[]{60 * Math.sin(a), -35, 60 * Math.cos(a)}, new double[]{0, 0, 0}));
        }
        Measure.Box b = Measure.measure(result(pts, cams));
        assertNotNull(b);
        assertTrue(!b.onWall);
        assertEquals("planos quitados", 0, b.otherPlanes.size());
        assertEquals("alto", 20, b.alto + b.separation, 1.5);
        assertEquals("ancho", 16, Math.min(b.ancho, b.prof), 1.5);
        assertEquals("largo", 20, Math.max(b.ancho, b.prof), 1.5);
    }
}
