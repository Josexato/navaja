package com.josexato.navaja.measure.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class ZoomPassTest {

    private static final int W = 540, H = 960;

    /** Cámara a {@code dist} mm sobre la hoja, mirando hacia abajo al origen. */
    private static Geometry.Pose above(double dist, double dx) {
        Geometry.Pose p = new Geometry.Pose();
        p.r = new double[]{1, 0, 0, 0, -1, 0, 0, 0, -1}; // x a la derecha, y de la imagen hacia abajo, eje óptico hacia −Z
        p.t = new double[]{-dx, 0, dist};
        return p;
    }

    private static double[][] cube(double half, double top) {
        return new double[][]{{-half, -half, 0}, {half, half, top}};
    }

    @Test
    public void boxIgnoresStrayPointsAndStaysOnTheSheet() {
        Random rnd = new Random(3);
        double[][] pts = new double[220][];
        for (int i = 0; i < 200; i++) pts[i] = new double[]{40 * rnd.nextDouble() - 20, 60 * rnd.nextDouble() - 30, 80 * rnd.nextDouble()};
        for (int i = 200; i < 210; i++) pts[i] = new double[]{-150 - i, -200, -30}; // sueltos, lejos
        for (int i = 210; i < 220; i++) pts[i] = new double[]{150 + i, 200, 300};
        int[] idx = new int[pts.length];
        for (int i = 0; i < idx.length; i++) idx[i] = i;
        double[][] b = ZoomPass.box(pts, idx);
        assertNotNull(b);
        assertTrue("x", b[0][0] > -20 - ZoomPass.PAD_MM - 1 && b[1][0] < 20 + ZoomPass.PAD_MM + 1);
        assertTrue("y", b[0][1] > -30 - ZoomPass.PAD_MM - 1 && b[1][1] < 30 + ZoomPass.PAD_MM + 1);
        assertEquals("no baja de la hoja", 0, b[0][2], 1e-9);
        assertTrue("alto", b[1][2] < 80 + ZoomPass.PAD_MM + 1);
        assertNull("muy pocos puntos", ZoomPass.box(pts, new int[]{0, 1, 2}));
    }

    @Test
    public void rectKeepsThePhotoShapeAndContainsTheObject() {
        Lens lens = new Lens(800, W / 2.0, H / 2.0);
        double[] r = ZoomPass.rect(cube(30, 40), above(400, 0), lens, W, H);
        assertNotNull(r);
        assertEquals("proporción de la foto", W / (double) H, r[2] / r[3], 1e-9);
        double zoom = W / r[2];
        assertTrue("aumento " + zoom, zoom >= ZoomPass.MIN_ZOOM && zoom <= ZoomPass.MAX_ZOOM + 1e-9);
        assertTrue("dentro de la foto", r[0] >= 0 && r[1] >= 0 && r[0] + r[2] <= W + 1e-6 && r[1] + r[3] <= H + 1e-6);
        // Las esquinas de arriba de la caja (las que más se abren) caen dentro del recorte
        for (int k = 0; k < 4; k++) {
            double[] p = lens.project(above(400, 0).apply(new double[]{(k & 1) == 0 ? -30 : 30, (k & 2) == 0 ? -30 : 30, 40}));
            assertTrue("esquina " + k, p[0] >= r[0] && p[0] <= r[0] + r[2] && p[1] >= r[1] && p[1] <= r[1] + r[3]);
        }
    }

    @Test
    public void noRectWhenTheObjectFillsThePhotoOrIsBehind() {
        Lens lens = new Lens(800, W / 2.0, H / 2.0);
        assertNull("ya llena la foto", ZoomPass.rect(cube(110, 40), above(400, 0), lens, W, H));
        assertNull("detrás de la cámara", ZoomPass.rect(cube(30, 500), above(400, 0), lens, W, H));
    }

    @Test
    public void rectIsCappedAndClampedToThePhoto() {
        Lens lens = new Lens(800, W / 2.0, H / 2.0);
        // Objeto muy chico y corrido hacia un borde
        double[] r = ZoomPass.rect(cube(3, 3), above(400, 120), lens, W, H);
        assertNotNull(r);
        assertEquals("aumento máximo", ZoomPass.MAX_ZOOM, W / r[2], 1e-9);
        assertTrue(r[0] >= 0 && r[0] + r[2] <= W + 1e-6);
    }

    @Test
    public void cropTakesDetailFromTheLargerFrameAndPointsMapBack() {
        // Cuadro del video al doble: un punto blanco en (300, 500) de la foto = (600..601, 1000..1001) del cuadro grande
        int sw = 2 * W, sh = 2 * H;
        int[] src = new int[sw * sh];
        java.util.Arrays.fill(src, 0xff000000);
        for (int y = 1000; y < 1002; y++) for (int x = 600; x < 602; x++) src[y * sw + x] = 0xffffffff;
        double[] rect = {200, 400, W / 2.0, H / 2.0}; // aumento 2
        int[] cut = ZoomPass.crop(src, sw, sh, rect, W, H);
        int bx = -1, by = -1, best = -1;
        for (int i = 0; i < cut.length; i++) if ((cut[i] & 255) > best) { best = cut[i] & 255; bx = i % W; by = i / W; }
        assertEquals("el punto llega con todo su brillo (hay detalle del cuadro grande)", 255, best);
        XFeatTracks.Frame f = new XFeatTracks.Frame(0);
        f.n = 1;
        f.x[0] = bx;
        f.y[0] = by;
        ZoomPass.toPhoto(f, rect, W, H);
        assertEquals(300, f.x[0], 0.75);
        assertEquals(500, f.y[0], 0.75);
    }

    @Test
    public void addTriangulatesTracksWithTheKnownPoses() {
        Lens lens = new Lens(800, W / 2.0, H / 2.0);
        Sfm.Result r = new Sfm.Result();
        r.width = W;
        r.height = H;
        r.sheetPlane = true;
        r.points = new double[][]{{0, 0, 0}};
        r.colors = new int[]{0};
        r.nObs = new int[]{3};
        for (int c = 0; c < 5; c++) {
            r.poses.add(above(400, -80 + 40 * c));
            r.camFrames.add(10 * c);
        }
        double[][] truth = {{10, -5, 35}, {-20, 15, 60}};
        List<Sfm.Track> tracks = new ArrayList<>();
        int[] frames = {0, 10, 20, 30, 40};
        for (double[] x : truth) {
            float[] xs = new float[5], ys = new float[5];
            for (int c = 0; c < 5; c++) {
                double[] p = lens.project(r.poses.get(c).apply(x));
                xs[c] = (float) p[0];
                ys[c] = (float) p[1];
            }
            tracks.add(Sfm.Track.sparse(frames, xs, ys, 0xff112233));
        }
        // Una pista equivocada: no cuadra con ningún punto 3D
        float[] bx = new float[5], by = new float[5];
        for (int c = 0; c < 5; c++) { bx[c] = 100 + 37 * ((c * 7) % 5); by[c] = 300 + 53 * ((c * 3) % 5); }
        tracks.add(Sfm.Track.sparse(frames, bx, by, 0));
        assertEquals(2, ZoomPass.add(r, tracks, lens));
        assertEquals(3, r.points.length);
        for (int i = 0; i < 2; i++) for (int k = 0; k < 3; k++) assertEquals(truth[i][k], r.points[1 + i][k], 0.5);
        assertEquals(5, r.nObs[1]);
        assertEquals(0xff112233, r.colors[2]);
    }
}
