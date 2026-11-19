package com.josexato.navaja.measure.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class XFeatTracksTest {

    static final int W = XFeatTracks.IN_W, H = XFeatTracks.IN_H, N = XFeatTracks.N, D = XFeatTracks.DIM;

    /** Imagen en grises (R = G = B) con el valor {@code v(x, y)}. */
    interface Val { int at(int x, int y); }

    static int[] image(int w, int h, Val v) {
        int[] p = new int[w * h];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int g = v.at(x, y) & 255;
                p[y * w + x] = 0xFF000000 | (g << 16) | (g << 8) | g;
            }
        return p;
    }

    /** Lo que da F.interpolate(bilinear, align_corners=False) de una rampa en una coordenada. */
    static double ramp(int dst, int src, int out) {
        double s = Math.max(0, (dst + 0.5) * src / out - 0.5);
        return Math.min(s, src - 1);
    }

    @Test
    public void laImagenSeAchicaComoEnPyTorch() {
        float[] out = new float[W * H];
        // Vertical 540×960: rampa en x (0..255 cubre 256 columnas, por eso módulo)
        XFeatTracks.input(image(540, 960, (x, y) -> x / 3), 540, 960, out);
        for (int u = 0; u < W; u += 37) {
            double s = ramp(u, 540, W);
            int x0 = (int) s, x1 = Math.min(x0 + 1, 539);
            double want = (x0 / 3) + ((x1 / 3) - (x0 / 3)) * (s - x0);
            assertEquals("columna " + u, want, out[100 * W + u], 1e-3);
        }
        // Apaisado 960×540: se traspone, así que la x del modelo es la y del cuadro
        XFeatTracks.input(image(960, 540, (x, y) -> y / 3), 960, 540, out);
        for (int u = 0; u < W; u += 37) {
            double s = ramp(u, 540, W);
            int y0 = (int) s, y1 = Math.min(y0 + 1, 539);
            double want = (y0 / 3) + ((y1 / 3) - (y0 / 3)) * (s - y0);
            assertEquals("columna " + u + " (traspuesto)", want, out[200 * W + u], 1e-3);
        }
    }

    /** Salida del modelo con máximos en {@code at} (u, v, valor), confiabilidad 1 y el mismo descriptor en toda la grilla. */
    static float[][] model(int[][] at, float[] value) {
        float[] heat = new float[W * H], rel = new float[(W / 8) * (H / 8)], desc = new float[rel.length * D];
        java.util.Arrays.fill(rel, 1);
        for (int c = 0; c < rel.length; c++) {
            desc[c * D] = 0.6f;
            desc[c * D + 1] = 0.8f;
        }
        for (int i = 0; i < at.length; i++) heat[at[i][1] * W + at[i][0]] = value[i];
        return new float[][]{desc, heat, rel};
    }

    @Test
    public void eligeLosPuntosPorPuntajeYRellenaLoQueFalta() {
        int[][] at = {{100, 200}, {300, 600}, {50, 900}, {400, 30}};
        float[] val = {0.2f, 0.9f, 0.5f, 0.01f}; // el último no pasa el umbral: va al final, como relleno
        float[][] m = model(at, val);
        int[] img = image(540, 960, (x, y) -> 128);
        XFeatTracks.Frame f = XFeatTracks.select(7, m[0], m[1], m[2], img, 540, 960);
        assertEquals(7, f.frame);
        assertEquals(4, f.n);
        // Orden: 0,9 · 0,5 · 0,2 · 0,01
        assertEquals(300 * 540.0 / W, f.x[0], 1e-4);
        assertEquals(600, f.y[0], 1e-4);
        assertEquals(50 * 540.0 / W, f.x[1], 1e-4);
        assertEquals(100 * 540.0 / W, f.x[2], 1e-4);
        assertEquals(400 * 540.0 / W, f.x[3], 1e-4);
        // Descriptor: el de la grilla, unitario
        assertEquals(0.6, f.desc[0], 1e-5);
        assertEquals(0.8, f.desc[1], 1e-5);
        assertEquals(0, f.desc[2], 1e-6);
        // Posición normalizada para el emparejador: centro de la imagen y la mitad del lado mayor
        assertEquals((300 * 540.0 / W - 270) / 480, f.glueXY[0], 1e-5);
        assertEquals((600 - 480) / 480.0, f.glueXY[1], 1e-5);
        // Relleno lejos y sin descriptor
        assertEquals(1000, f.glueXY[2 * N - 1], 0);
        assertEquals(0, f.desc[D * N - 1], 0);
        assertEquals(0xFF808080, f.color[0]);
    }

    @Test
    public void enUnApaisadoLasPosicionesVuelvenAlCuadro() {
        float[][] m = model(new int[][]{{100, 200}}, new float[]{0.5f});
        int[] img = image(960, 540, (x, y) -> 50);
        XFeatTracks.Frame f = XFeatTracks.select(0, m[0], m[1], m[2], img, 960, 540);
        assertEquals(1, f.n);
        // (u, v) del modelo vertical = (y, x) del cuadro apaisado
        assertEquals(200, f.x[0], 1e-4);
        assertEquals(100 * 540.0 / W, f.y[0], 1e-4);
    }

    @Test
    public void sóloSeQuedanLosParesMutuosYConfiables() {
        XFeatTracks.Frame a = new XFeatTracks.Frame(0), b = new XFeatTracks.Frame(1);
        a.n = 4;
        b.n = 3;
        int[] m0 = new int[N], m1 = new int[N];
        float[] v0 = new float[N];
        java.util.Arrays.fill(v0, (float) Math.log(0.9));
        m0[0] = 2; m1[2] = 0;           // mutuo: sí
        m0[1] = 1; m1[1] = 3;           // no mutuo
        m0[2] = 0; m1[0] = 2; v0[2] = (float) Math.log(0.05); // poco confiable
        m0[3] = 5;                      // a un punto de relleno de b
        int[][] p = XFeatTracks.pairs(a, b, m0, m1, v0);
        assertEquals(1, p.length);
        assertArrayEquals(new int[]{0, 2, 900}, p[0]);
    }

    @Test
    public void unoLosParesEnPistasYDescartoLosQueNoCalzanConLasPoses() {
        Random rnd = new Random(3);
        List<Geometry.Pose> orbit = SheetScanTest.orbit();
        int[] frames = {0, 4, 8, 12};
        Lens lens = new Lens(SheetScanTest.F, SheetScanTest.W / 2.0, SheetScanTest.H / 2.0);
        int np = 200;
        double[][] pts = new double[np][];
        for (int i = 0; i < np; i++) pts[i] = new double[]{rnd.nextDouble() * 60 - 30, rnd.nextDouble() * 40 - 20, rnd.nextDouble() * 80};
        List<XFeatTracks.Frame> fs = new ArrayList<>();
        for (int fr : frames) {
            XFeatTracks.Frame f = new XFeatTracks.Frame(fr);
            Geometry.Pose p = orbit.get(fr);
            // El mismo orden de puntos en todas las fotos, menos una permutación para que el índice no coincida
            for (int i = 0; i < np; i++) {
                int k = (i * 7 + fr) % np;
                double[] xc = p.apply(pts[i]);
                f.x[k] = (float) (lens.f * xc[0] / xc[2] + lens.cx);
                f.y[k] = (float) (lens.f * xc[1] / xc[2] + lens.cy);
            }
            f.n = np;
            fs.add(f);
        }
        XFeatTracks.Linker lk = new XFeatTracks.Linker();
        for (int s = 0; s + 1 < fs.size(); s++) {
            XFeatTracks.Frame a = fs.get(s), b = fs.get(s + 1);
            List<int[]> pr = new ArrayList<>();
            for (int i = 0; i < np; i++) pr.add(new int[]{(i * 7 + a.frame) % np, (i * 7 + b.frame) % np});
            // Un par equivocado (punto i con el punto i + 100): no respeta la geometría epipolar
            pr.set(5, new int[]{(5 * 7 + a.frame) % np, (105 * 7 + b.frame) % np});
            lk.add(a, b, pr.toArray(new int[0][]), orbit.get(a.frame), orbit.get(b.frame), lens);
        }
        assertEquals(3, lk.framePairs);
        assertEquals(3 * np, lk.raw);
        assertTrue("descartados " + (lk.raw - lk.kept), lk.raw - lk.kept >= 2 && lk.raw - lk.kept <= 3);
        List<Sfm.Track> tr = lk.tracks(4);
        // El punto 5 queda cortado en las 4 fotos: el resto sí
        assertEquals(np - 1, tr.size());
        for (Sfm.Track t : tr) {
            assertTrue(t.isSparse());
            assertEquals(4, t.observations());
            assertTrue(t.visible(8));
            assertFalse(t.visible(9));
            assertEquals(12, t.end());
        }
        // Triangulan en el lugar correcto
        SheetPose.Fix[] fx = new SheetPose.Fix[13];
        for (int fr : frames) {
            fx[fr] = new SheetPose.Fix();
            fx[fr].pose = orbit.get(fr);
        }
        double[] x = Geometry.triangulate(new Geometry.Pose[]{orbit.get(0), orbit.get(12)},
                new double[][]{lens.normalize(tr.get(0).x(0), tr.get(0).y(0)), lens.normalize(tr.get(0).x(12), tr.get(0).y(12))});
        boolean near = false;
        for (double[] p : pts) near |= Lin.norm(Lin.sub(p, x)) < 0.5;
        assertTrue(near);
    }

    @Test
    public void unParContradictorioNoArruinaLaPista() {
        List<Geometry.Pose> orbit = SheetScanTest.orbit();
        Lens lens = new Lens(SheetScanTest.F, SheetScanTest.W / 2.0, SheetScanTest.H / 2.0);
        int[] frames = {0, 4, 8};
        double[] p = {10, -5, 20};
        // Q está sobre el rayo de la cámara 0 que pasa por P: el par (P en 0, Q en 8) respeta la geometría epipolar, pero es otro punto
        double[] c0 = orbit.get(0).center();
        double[] q = Lin.add(p, Lin.scale(Lin.sub(p, c0), 0.15));
        XFeatTracks.Frame[] f = new XFeatTracks.Frame[3];
        for (int k = 0; k < 3; k++) {
            f[k] = new XFeatTracks.Frame(frames[k]);
            double[] xc = orbit.get(frames[k]).apply(p), xq = orbit.get(frames[k]).apply(q);
            f[k].x[0] = (float) (lens.f * xc[0] / xc[2] + lens.cx);
            f[k].y[0] = (float) (lens.f * xc[1] / xc[2] + lens.cy);
            f[k].x[1] = (float) (lens.f * xq[0] / xq[2] + lens.cx);
            f[k].y[1] = (float) (lens.f * xq[1] / xq[2] + lens.cy);
            f[k].n = 2;
        }
        XFeatTracks.Linker lk = new XFeatTracks.Linker();
        lk.add(f[0], f[1], new int[][]{{0, 0, 950}}, orbit.get(0), orbit.get(4), lens, 1);
        lk.add(f[1], f[2], new int[][]{{0, 0, 950}}, orbit.get(4), orbit.get(8), lens, 1);
        // El equivocado, con más confianza pero entre fotos más separadas: va después y choca
        lk.add(f[0], f[2], new int[][]{{0, 1, 990}}, orbit.get(0), orbit.get(8), lens, 2);
        assertEquals("pasa la geometría", 3, lk.kept);
        List<Sfm.Track> tr = lk.tracks(3);
        assertEquals(1, tr.size());
        assertEquals(1, lk.conflicts);
        assertEquals(f[2].x[0], tr.get(0).x(8), 0);
    }

    @Test
    public void unaPistaSueltaSeLeeEnSusCuadros() {
        Sfm.Track t = Sfm.Track.sparse(new int[]{3, 9, 20}, new float[]{1, 2, 3}, new float[]{4, 5, 6}, 0xFF112233);
        assertEquals(3, t.start);
        assertEquals(20, t.end());
        assertTrue(t.visible(9));
        assertFalse(t.visible(10));
        assertEquals(2, t.x(9), 0);
        assertEquals(6, t.y(20), 0);
        Sfm.Track k = new Sfm.Track(3, 1, 4, 0);
        k.add(2, 5);
        assertFalse(k.isSparse());
        assertTrue(k.visible(4));
        assertEquals(4, k.end());
    }
}
