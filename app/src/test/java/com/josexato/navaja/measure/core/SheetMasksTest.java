package com.josexato.navaja.measure.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class SheetMasksTest {

    static final int DIV = 2;

    /** Silueta real de la caja de prueba (casco convexo de sus 8 esquinas) en la cámara c. */
    static byte[] truth(Sfm.Result r, int c, int mw, int mh) {
        double bx = SheetScanTest.BX, by = SheetScanTest.BY, bz = SheetScanTest.BZ;
        List<double[]> pr = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            double[] x = {(i & 1) == 0 ? -bx : bx, (i & 2) == 0 ? -by : by, (i & 4) == 0 ? 0 : bz};
            double[] xc = r.poses.get(c).apply(x);
            pr.add(new double[]{(r.focal * xc[0] / xc[2] + r.width / 2.0) / DIV, (r.focal * xc[1] / xc[2] + r.height / 2.0) / DIV});
        }
        double[][] h = SheetMasks.convexHull(pr);
        byte[] m = new byte[mw * mh];
        for (int y = 0; y < mh; y++) for (int x = 0; x < mw; x++) if (SheetMasks.inside(h, x + 0.5, y + 0.5)) m[y * mw + x] = 1;
        return m;
    }

    static int count(byte[] m) { int n = 0; for (byte b : m) n += b; return n; }

    static int overlap(byte[] a, byte[] b) { int n = 0; for (int i = 0; i < a.length; i++) if (a[i] != 0 && b[i] != 0) n++; return n; }

    @Test
    public void seBorraElFondoYVuelveLoQueLaIADejoAfuera() {
        Random rnd = new Random(7);
        List<Geometry.Pose> cams = SheetScanTest.orbit();
        Lens lens = new Lens(SheetScanTest.F, SheetScanTest.W / 2.0, SheetScanTest.H / 2.0);
        SheetScan.Report rep = new SheetScan.Report();
        SheetPose.Fix[] fx = SheetScan.poses(SheetScanTest.markers(cams, 0.4, rnd), lens, rep);
        Sfm.Result r = SheetScan.build(SheetScanTest.tracks(cams, rnd), fx, lens, SheetScanTest.W, SheetScanTest.H, rep);
        assertNotNull(r);
        Measure.Box b = SheetScan.measure(r).box;
        int mw = SheetScanTest.W / DIV, mh = SheetScanTest.H / DIV;
        byte[][] truth = new byte[r.poses.size()][], masks = new byte[r.poses.size()][];
        for (int c = 0; c < masks.length; c++) {
            truth[c] = truth(r, c, mw, mh);
            masks[c] = truth[c].clone();
            // Una mancha de fondo en una esquina de la imagen (la pared)
            for (int y = 0; y < 30; y++) for (int x = 0; x < 30; x++) masks[c][y * mw + x] = 1;
            // En una de cada tres fotos la IA deja afuera la parte de arriba del objeto
            if (c % 3 == 0) {
                int top = mh, bottom = 0;
                for (int i = 0; i < truth[c].length; i++) if (truth[c][i] != 0) { top = Math.min(top, i / mw); bottom = Math.max(bottom, i / mw); }
                int cut = top + (bottom - top) / 3;
                for (int y = top; y < cut; y++) for (int x = 0; x < mw; x++) masks[c][y * mw + x] = 0;
            }
        }
        SheetMasks.Stats st = new SheetMasks.Stats();
        byte[][] clipped = SheetMasks.refineAll(r, b, masks, mw, mh, st, false);
        byte[][] carve = SheetMasks.refineAll(r, b, clipped, mw, mh, st, true);
        assertTrue("puntos que agregan " + st.addPoints, st.addPoints > 100);
        for (int c = 0; c < masks.length; c++) {
            // La mancha de fondo se fue
            assertEquals("foto " + c, 0, clipped[c][5 * mw + 5]);
            // Lo del objeto no se tocó al recortar
            assertEquals(overlap(masks[c], truth[c]), overlap(clipped[c], truth[c]));
            // Para tallar: en las fotos cortadas vuelve buena parte de lo que faltaba, sin salirse mucho del objeto
            if (c % 3 == 0) {
                int missing = count(truth[c]) - overlap(masks[c], truth[c]);
                int back = overlap(carve[c], truth[c]) - overlap(clipped[c], truth[c]);
                assertTrue("foto " + c + ": vuelven " + back + " de " + missing, back > 0.5 * missing);
                int outside = count(carve[c]) - overlap(carve[c], truth[c]);
                assertTrue("foto " + c + ": fuera del objeto " + outside, outside < 0.15 * count(truth[c]));
            }
        }
    }

    @Test
    public void unaSiluetaQueNoCalzaConLasDemasSeRepara() {
        Random rnd = new Random(9);
        List<Geometry.Pose> cams = SheetScanTest.orbit();
        Lens lens = new Lens(SheetScanTest.F, SheetScanTest.W / 2.0, SheetScanTest.H / 2.0);
        SheetScan.Report rep = new SheetScan.Report();
        SheetPose.Fix[] fx = SheetScan.poses(SheetScanTest.markers(cams, 0.4, rnd), lens, rep);
        Sfm.Result r = SheetScan.build(SheetScanTest.tracks(cams, rnd), fx, lens, SheetScanTest.W, SheetScanTest.H, rep);
        Measure.Box b = SheetScan.measure(r).box;
        int mw = SheetScanTest.W / DIV, mh = SheetScanTest.H / DIV;
        byte[][] truth = new byte[r.poses.size()][], masks = new byte[r.poses.size()][];
        List<Integer> broken = new ArrayList<>();
        for (int c = 0; c < masks.length; c++) {
            truth[c] = truth(r, c, mw, mh);
            masks[c] = truth[c].clone();
            if (c % 4 != 1) continue;
            broken.add(c);
            int x0 = mw, x1 = 0, y0 = mh, y1 = 0;
            for (int i = 0; i < masks[c].length; i++) if (masks[c][i] != 0) { x0 = Math.min(x0, i % mw); x1 = Math.max(x1, i % mw); y0 = Math.min(y0, i / mw); y1 = Math.max(y1, i / mw); }
            if (c % 8 == 1) { // cortada: falta la mitad derecha
                for (int y = y0; y <= y1; y++) for (int x = (x0 + x1) / 2; x <= x1; x++) masks[c][y * mw + x] = 0;
            } else { // desbordada: una mancha del tamaño del objeto al lado
                for (int y = y0; y <= y1; y++) for (int x = x1; x < Math.min(mw, x1 + (x1 - x0)); x++) masks[c][y * mw + x] = 1;
            }
        }
        SheetMasks.Stats st = new SheetMasks.Stats();
        byte[][] out = SheetMasks.consensus(r, b, masks, mw, mh, st);
        assertTrue("parecido " + st.iouMedian, st.iouMedian > 0.8);
        // Se encuentran las rotas y casi ninguna buena
        int found = 0;
        for (int c : st.repaired) if (broken.contains(c)) found++;
        assertTrue("rotas encontradas " + found + " de " + broken.size(), found >= 0.9 * broken.size());
        assertTrue("buenas tocadas " + (st.repaired.size() - found), st.repaired.size() - found <= 2);
        // Y la reparada se parece al objeto real
        for (int c : broken) {
            if (!st.repaired.contains(c)) continue;
            int inter = overlap(out[c], truth[c]), union = count(out[c]) + count(truth[c]) - inter;
            assertTrue("foto " + c + ": parecido " + (double) inter / union, inter > 0.7 * union);
        }
        // Sin siluetas rotas no se toca nada
        SheetMasks.Stats ok = new SheetMasks.Stats();
        assertTrue(SheetMasks.consensus(r, b, truth, mw, mh, ok) != null);
        assertEquals(0, ok.repaired.size() + ok.dropped.size());
    }
}
