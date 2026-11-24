package com.josexato.navaja.measure.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class PoseJoinTest {

    private static final int W = 96, H = 54;

    @Test
    public void parsesTheSavedReconstruction() {
        String cams = "# Navaja\n1 SIMPLE_PINHOLE 960 540 700.0 480.5 270.5\n";
        String imgs = "# IMAGE_ID, QW, QX, QY, QZ, TX, TY, TZ, CAMERA_ID, NAME\n# POINTS2D[]\n"
                + "1 1 0 0 0 1.5 -2 30 1 cuadro_000.jpg\n100.50 50.50 7 200.50 60.50 9 300.5 70.5 99\n"
                + "2 0.7071067811865476 0 0 0.7071067811865476 0 0 10 1 cuadro_004.jpg\n\n";
        String pts = "# POINT3D_ID, X, Y, Z, R, G, B, ERROR, TRACK[]\n7 1 2 3 255 0 0 1.4 1 0\n9 -4 5 6 0 128 255 1.4 1 1\n";
        PoseJoin.Scan s = PoseJoin.parse(cams, imgs, pts);
        assertEquals(960, s.width);
        assertEquals(540, s.height);
        assertEquals(2, s.points.length);
        assertEquals(0xffff0000, s.colors[0]);
        assertEquals(2, s.names.size());
        assertEquals(1, s.index("cuadro_004.jpg"));
        // La observación de un punto que no está en points3D se descarta; las otras pierden el medio píxel de Colmap
        assertEquals(2, s.obsPoint.get(0).length);
        assertEquals(100f, s.obsXY.get(0)[0], 1e-4);
        assertEquals(60f, s.obsXY.get(0)[3], 1e-4);
        assertEquals(1, s.obsPoint.get(0)[1]);
        assertEquals("foto sin observaciones", 0, s.obsPoint.get(1).length);
        // Cuaternión de 90° alrededor de Z: x → y
        double[] x = s.poses.get(1).apply(new double[]{1, 0, 0});
        assertEquals(0, x[0], 1e-9);
        assertEquals(1, x[1], 1e-9);
        assertEquals(10, x[2], 1e-9);
        assertEquals(30, s.poses.get(0).t[2], 1e-9);
    }

    private static byte[] blob(int x0, int y0, int x1, int y1) {
        byte[] m = new byte[W * H];
        for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) m[y * W + x] = 1;
        return m;
    }

    @Test
    public void rectFollowsTheSilhouetteWithThePhotoShape() {
        double[] r = PoseJoin.rect(blob(30, 10, 60, 40), W, H);
        assertNotNull(r);
        assertEquals(W / (double) H, r[2] / r[3], 1e-9);
        assertTrue("contiene la silueta", r[0] <= 30 && r[1] <= 10 && r[0] + r[2] >= 60 && r[1] + r[3] >= 40);
        assertTrue("dentro de la foto", r[0] >= 0 && r[1] >= 0 && r[0] + r[2] <= W + 1e-9 && r[1] + r[3] <= H + 1e-9);
        assertNull("silueta muy chica", PoseJoin.rect(blob(5, 5, 9, 9), W, H));
    }

    @Test
    public void cutKeepsOnlyTheObjectAndPointsMapBackForEveryTurn() {
        byte[] mask = blob(30, 10, 60, 40);
        int[] photo = new int[W * H];
        java.util.Arrays.fill(photo, 0xff00ff00); // fondo verde: tiene que desaparecer
        for (int i = 0; i < photo.length; i++) if (mask[i] != 0) photo[i] = 0xff202020;
        photo[20 * W + 40] = 0xffffffff; // una marca blanca dentro del objeto
        double[] r = PoseJoin.rect(mask, W, H);
        for (int turn : new int[]{0, 90, 180, 270}) {
            int[] wh = PoseJoin.cutSize(W, H, turn);
            int[] c = PoseJoin.cut(photo, mask, W, H, r, turn);
            assertEquals(W * H, c.length);
            int best = -1, bx = 0, by = 0;
            for (int i = 0; i < c.length; i++) {
                assertTrue("sin verde del fondo (giro " + turn + ")", ((c[i] >> 8) & 255) <= Math.max((c[i] >> 16) & 255, c[i] & 255) + 1);
                if ((c[i] & 255) > best) { best = c[i] & 255; bx = i % wh[0]; by = i / wh[0]; }
            }
            double[] p = PoseJoin.toPhoto(bx, by, r, W, H, turn);
            assertEquals("x, giro " + turn, 40, p[0], 1.0);
            assertEquals("y, giro " + turn, 20, p[1], 1.0);
        }
        assertEquals(PoseJoin.GRAY, PoseJoin.cut(photo, new byte[W * H], W, H, new double[]{0, 0, W, H}, 0)[0]);
    }

    @Test
    public void keypointsTakeTheNearest3dPointInsideTheSilhouette() {
        PoseJoin.Scan s = new PoseJoin.Scan();
        s.width = W;
        s.height = H;
        s.names.add("a.jpg");
        s.obsXY.add(new float[]{40, 20, 41.5f, 20, 80, 30});
        s.obsPoint.add(new int[]{5, 6, 7});
        int[] got = PoseJoin.to3d(s, 0, blob(30, 10, 60, 40), new double[][]{{40.4, 20.2}, {41.3, 20.1}, {50, 30}, {80, 30}});
        assertEquals(5, got[0]);
        assertEquals(6, got[1]);
        assertEquals("lejos de toda observación", -1, got[2]);
        assertEquals("la observación está fuera de la silueta", -1, got[3]);
    }

    private static double[] move(double[] r, double[] t, double[] x) { return Lin.add(Lin.mulV(r, x), t); }

    @Test
    public void fitRecoversTheMotionDespiteWrongPairs() {
        Random rnd = new Random(4);
        double[] r = Lin.rodrigues(new double[]{1.4, 0.3, -0.2}), t = {12, -30, 7};
        List<double[]> p = new ArrayList<>(), q = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            double[] x = {100 * rnd.nextDouble() - 50, 100 * rnd.nextDouble() - 50, 80 * rnd.nextDouble()};
            p.add(x);
            double[] y = move(r, t, x);
            for (int k = 0; k < 3; k++) y[k] += rnd.nextGaussian();
            q.add(y);
        }
        for (int i = 0; i < 240; i++) { // 80 % de pares equivocados
            p.add(new double[]{100 * rnd.nextDouble() - 50, 100 * rnd.nextDouble() - 50, 80 * rnd.nextDouble()});
            q.add(new double[]{100 * rnd.nextDouble() - 50, 100 * rnd.nextDouble() - 50, 80 * rnd.nextDouble()});
        }
        PoseJoin.Fit f = PoseJoin.fit(p, q, 1);
        assertNotNull(f);
        assertTrue("coherentes " + f.inliers, f.inliers >= 55 && f.inliers <= 75);
        assertTrue(f.ok());
        assertEquals(300, f.pairs);
        for (int k = 0; k < 9; k++) assertEquals(r[k], f.r[k], 0.02);
        for (int k = 0; k < 3; k++) assertEquals(t[k], f.t[k], 1.0);
        assertEquals(Math.toDegrees(Lin.norm(new double[]{1.4, 0.3, -0.2})), f.angleDeg, 1.5);
        assertTrue(f.error < 2.5);
        assertEquals(1, Lin.det(f.r), 1e-6);
    }

    @Test
    public void fitRefusesPureNoise() {
        Random rnd = new Random(5);
        List<double[]> p = new ArrayList<>(), q = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            p.add(new double[]{100 * rnd.nextDouble(), 100 * rnd.nextDouble(), 80 * rnd.nextDouble()});
            q.add(new double[]{100 * rnd.nextDouble(), 100 * rnd.nextDouble(), 80 * rnd.nextDouble()});
        }
        PoseJoin.Fit f = PoseJoin.fit(p, q, 1);
        assertTrue(f == null || !f.ok());
        assertNull(PoseJoin.fit(p.subList(0, 4), q.subList(0, 4), 1));
    }

    @Test
    public void rigidHandlesFlatPointSetsWithoutMirroring() {
        // Puntos en un plano: el ajuste no puede devolver un reflejo
        double[] r = Lin.rodrigues(new double[]{0, 0, 2.5}), t = {3, 4, 5};
        List<double[]> p = new ArrayList<>(), q = new ArrayList<>();
        Random rnd = new Random(6);
        for (int i = 0; i < 20; i++) {
            double[] x = {50 * rnd.nextDouble(), 50 * rnd.nextDouble(), 0};
            p.add(x);
            q.add(move(r, t, x));
        }
        double[][] m = PoseJoin.rigid(p, q);
        assertEquals(1, Lin.det(m[0]), 1e-6);
        for (int k = 0; k < 9; k++) assertEquals(r[k], m[0][k], 1e-6);
    }

    @Test
    public void cloudGapDropsWhenTheCloudsAreAligned() {
        Random rnd = new Random(7);
        double[] r = Lin.rodrigues(new double[]{1.5, 0, 0}), t = {5, 5, 5};
        List<double[]> a = new ArrayList<>(), b = new ArrayList<>(), p = new ArrayList<>(), q = new ArrayList<>();
        for (int i = 0; i < 400; i++) { // un objeto alargado: sin girar no calza
            double[] x = {120 * rnd.nextDouble(), 20 * rnd.nextDouble(), 50 * rnd.nextDouble()};
            a.add(x);
            b.add(move(r, t, new double[]{120 * rnd.nextDouble(), 20 * rnd.nextDouble(), 50 * rnd.nextDouble()}));
            if (i < 40) { p.add(x); q.add(move(r, t, x)); }
        }
        PoseJoin.Fit f = PoseJoin.fit(p, q, 1);
        assertNotNull(f);
        double aligned = PoseJoin.cloudGap(a, b, f), loose = PoseJoin.cloudGap(a, b, null);
        assertTrue("alineado " + aligned + " contra sin alinear " + loose, aligned < 0.6 * loose);
        // El objeto no puede quedar bajo la hoja (Z = 0): dado vuelta queda casi todo abajo; sin mover, nada
        PoseJoin.Fit flip = new PoseJoin.Fit();
        flip.r = Lin.rodrigues(new double[]{Math.PI, 0, 0});
        flip.t = new double[]{0, 0, 0};
        assertTrue(PoseJoin.below(a, flip, 5) > 0.8);
        PoseJoin.Fit up = PoseJoin.fit(p, new ArrayList<>(p), 1);
        assertNotNull(up);
        assertEquals(0, PoseJoin.below(a, up, 5), 1e-9);
    }

    // ------------------------------------------------------------ un solo volumen con las dos tomas

    private static final int CW = 160, CH = 120;
    private static final double CF = 200;

    /** Cámara a {@code dist} mm del punto {@code at}, a {@code elev}° sobre la mesa y {@code az}° alrededor, mirándolo. */
    private static Geometry.Pose look(double az, double elev, double dist, double[] at) {
        double a = Math.toRadians(az), e = Math.toRadians(elev);
        double[] c = {at[0] + dist * Math.cos(e) * Math.cos(a), at[1] + dist * Math.cos(e) * Math.sin(a), at[2] + dist * Math.sin(e)};
        double[] fwd = Lin.unit(Lin.sub(at, c)), right = Lin.unit(Lin.cross(fwd, new double[]{0, 0, 1})), down = Lin.cross(fwd, right);
        Geometry.Pose p = new Geometry.Pose();
        p.r = new double[]{right[0], right[1], right[2], down[0], down[1], down[2], fwd[0], fwd[1], fwd[2]};
        p.t = Lin.neg(Lin.mulV(p.r, c));
        return p;
    }

    /** Silueta de la caja {lo, hi} (en los ejes en que está dada la cámara): un píxel es objeto si su rayo la cruza. */
    private static byte[] silhouette(Geometry.Pose p, double[] lo, double[] hi) {
        byte[] m = new byte[CW * CH];
        double[] c = Lin.neg(Lin.mulV(Lin.tr(p.r), p.t));
        for (int v = 0; v < CH; v++)
            for (int u = 0; u < CW; u++) {
                double[] d = Lin.mulV(Lin.tr(p.r), new double[]{(u + 0.5 - CW / 2.0) / CF, (v + 0.5 - CH / 2.0) / CF, 1});
                double t0 = 0, t1 = 1e9;
                for (int k = 0; k < 3; k++) {
                    double a = (lo[k] - c[k]) / d[k], b = (hi[k] - c[k]) / d[k];
                    t0 = Math.max(t0, Math.min(a, b));
                    t1 = Math.min(t1, Math.max(a, b));
                }
                if (t0 <= t1) m[v * CW + u] = 1;
            }
        return m;
    }

    @Test
    public void movedCameraSeesTheSamePixelForTheMovedPoint() {
        PoseJoin.Fit f = new PoseJoin.Fit();
        f.r = Lin.rodrigues(new double[]{1.2, -0.4, 0.3});
        f.t = new double[]{10, -20, 30};
        Geometry.Pose p = look(40, 25, 300, new double[]{0, 0, 20}), q = PoseJoin.moved(p, f);
        double[] x = {12, -7, 33}, a = p.apply(x), b = q.apply(f.apply(x));
        for (int k = 0; k < 3; k++) assertEquals(a[k], b[k], 1e-9);
    }

    @Test
    public void growWidensTheSilhouette() {
        byte[] m = new byte[W * H];
        m[20 * W + 40] = 1;
        byte[] g = PoseJoin.grow(m, W, H, 2);
        int n = 0;
        for (byte b : g) if (b != 0) n++;
        assertEquals("rombo de radio 2", 13, n);
        assertTrue(g[20 * W + 42] != 0 && g[22 * W + 40] != 0 && g[21 * W + 42] == 0);
    }

    @Test
    public void twoPosesCarveWhatEachOneCannotSee() {
        // Un ladrillo de 80 × 40 × 20 mm. En B está acostado (20 de alto); en A, parado sobre su cara chica (80 de alto).
        // Las cámaras rodean cada toma a baja altura: ninguna ve bien el contorno desde arriba, así que cada toma sola
        // deja material de más en lo alto. La otra toma, que lo ve de costado, lo recorta.
        double[] loB = {-40, -20, 0}, hiB = {40, 20, 20};
        PoseJoin.Fit f = new PoseJoin.Fit(); // x_B = R·x_A + t: en A el largo (X de B) está en Z
        f.r = new double[]{0, 0, 1, 0, 1, 0, -1, 0, 0};
        f.t = new double[]{-40, 0, 20};
        double[] loA = {0, -20, 0}, hiA = {20, 20, 80};
        double[] chk = f.apply(new double[]{0, -20, 0}), chk2 = f.apply(new double[]{20, 20, 80});
        for (int k = 0; k < 3; k++) {
            assertEquals(Math.min(chk[k], chk2[k]), loB[k], 1e-9);
            assertEquals(Math.max(chk[k], chk2[k]), hiB[k], 1e-9);
        }
        List<PoseJoin.Cam> cams = new ArrayList<>();
        for (int s = 0; s < 2; s++)
            for (int i = 0; i < 16; i++) {
                Geometry.Pose p = look(22.5 * i, 15, 320, s == 0 ? new double[]{10, 0, 40} : new double[]{0, 0, 10});
                PoseJoin.Cam c = new PoseJoin.Cam();
                c.scan = s;
                c.w = CW;
                c.h = CH;
                c.f = CF;
                c.cx = CW / 2.0 - 0.5;
                c.cy = CH / 2.0 - 0.5;
                c.mask = s == 0 ? silhouette(p, loA, hiA) : silhouette(p, loB, hiB);
                c.pose = s == 0 ? PoseJoin.moved(p, f) : p;
                cams.add(c);
            }
        double[][] box = {{-55, -35, 0}, {55, 35, 45}};
        PoseJoin.Hull both = PoseJoin.carve(cams, box, f);
        assertNotNull(both);
        double truth = 80 * 40 * 20 / 1000.0;
        assertTrue("volumen " + both.volume + " cm³ contra " + truth, both.volume > 0.85 * truth && both.volume < 1.35 * truth);
        assertEquals(16, both.viewsA);
        assertEquals(16, both.viewsB);
        // Cada toma sola (las siluetas de la otra no recortan nada) deja más material
        for (int blind = 0; blind < 2; blind++) {
            List<PoseJoin.Cam> one = new ArrayList<>();
            byte[] full = new byte[CW * CH];
            java.util.Arrays.fill(full, (byte) 1);
            for (PoseJoin.Cam c : cams) {
                PoseJoin.Cam d = new PoseJoin.Cam();
                d.scan = c.scan; d.w = c.w; d.h = c.h; d.f = c.f; d.cx = c.cx; d.cy = c.cy; d.pose = c.pose;
                d.mask = c.scan == blind ? full : c.mask;
                one.add(d);
            }
            PoseJoin.Hull alone = PoseJoin.carve(one, box, f);
            assertNotNull(alone);
            assertTrue("sola " + alone.volume + " contra las dos " + both.volume, alone.volume > 1.05 * both.volume);
        }
        assertNull("pocas siluetas", PoseJoin.carve(cams.subList(0, 20), box, f));
    }

    @Test
    public void boxCoversBothCloudsAndStartsAtTheSheet() {
        PoseJoin.Fit f = new PoseJoin.Fit();
        f.r = new double[]{1, 0, 0, 0, 1, 0, 0, 0, 1};
        f.t = new double[]{100, 0, 0};
        List<double[]> a = new ArrayList<>(), b = new ArrayList<>();
        Random rnd = new Random(8);
        for (int i = 0; i < 200; i++) {
            a.add(new double[]{20 * rnd.nextDouble(), 20 * rnd.nextDouble(), 50 * rnd.nextDouble()});
            b.add(new double[]{20 * rnd.nextDouble(), 20 * rnd.nextDouble(), 30 * rnd.nextDouble()});
        }
        double[][] box = PoseJoin.box(a, b, f);
        assertNotNull(box);
        assertEquals(0, box[0][2], 1e-9);
        assertTrue(box[0][0] < 0 && box[1][0] > 120 && box[1][2] > 50);
        assertNull(PoseJoin.box(a.subList(0, 5), b.subList(0, 5), f));
    }
}
