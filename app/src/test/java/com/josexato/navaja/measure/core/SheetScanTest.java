package com.josexato.navaja.measure.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class SheetScanTest {

    static final int W = 540, H = 960;
    static final double F = 640;
    /** Caja de 60 × 40 × 80 mm (ancho × largo × alto) sobre el centro de la hoja. */
    static final double BX = 30, BY = 20, BZ = 80;

    /** Un recorrido alrededor de la hoja: 240 cuadros, a 30 cm, subiendo y bajando. */
    static List<Geometry.Pose> orbit() {
        List<Geometry.Pose> out = new ArrayList<>();
        for (int f = 0; f < 240; f++) {
            double a = 2 * Math.PI * f / 240, el = Math.toRadians(45 + 12 * Math.sin(3 * a));
            double[] c = {300 * Math.cos(el) * Math.cos(a), 300 * Math.cos(el) * Math.sin(a), 300 * Math.sin(el)};
            out.add(MarkersTest.look(c, new double[]{0, 0, 30}, new double[]{0, 0, 1}));
        }
        return out;
    }

    /** Las marcas que verían las cámaras (proyección exacta + ruido de {@code noise} px), sin las que tapa la caja ni las cortadas. */
    static List<List<Markers.Marker>> markers(List<Geometry.Pose> cams, double noise, Random rnd) {
        List<List<Markers.Marker>> out = new ArrayList<>();
        for (Geometry.Pose p : cams) {
            List<Markers.Marker> ms = new ArrayList<>();
            for (int id = 0; id < Sheet.count(); id++) {
                double[][] c3 = Sheet.corners(id);
                double[][] px = new double[4][];
                boolean ok = true;
                for (int k = 0; k < 4 && ok; k++) {
                    double[] xc = p.apply(c3[k]);
                    px[k] = new double[]{F * xc[0] / xc[2] + W / 2.0 + noise * rnd.nextGaussian(), F * xc[1] / xc[2] + H / 2.0 + noise * rnd.nextGaussian()};
                    ok = xc[2] > 0 && px[k][0] > 2 && px[k][1] > 2 && px[k][0] < W - 3 && px[k][1] < H - 3;
                }
                if (!ok) continue;
                Markers.Marker m = new Markers.Marker();
                m.id = id;
                m.corners = px;
                ms.add(m);
            }
            out.add(ms);
        }
        return out;
    }

    /** Pistas de puntos en las caras de la caja y en la hoja, mientras se ven (cara hacia la cámara y dentro de la imagen). */
    static List<Sfm.Track> tracks(List<Geometry.Pose> cams, Random rnd) {
        List<double[][]> pts = new ArrayList<>(); // {punto, normal}
        for (int i = 0; i < 1500; i++) {
            double u = rnd.nextDouble() * 2 - 1, v = rnd.nextDouble() * 2 - 1;
            switch (rnd.nextInt(5)) {
                case 0: pts.add(new double[][]{{BX, u * BY, (v + 1) / 2 * BZ}, {1, 0, 0}}); break;
                case 1: pts.add(new double[][]{{-BX, u * BY, (v + 1) / 2 * BZ}, {-1, 0, 0}}); break;
                case 2: pts.add(new double[][]{{u * BX, BY, (v + 1) / 2 * BZ}, {0, 1, 0}}); break;
                case 3: pts.add(new double[][]{{u * BX, -BY, (v + 1) / 2 * BZ}, {0, -1, 0}}); break;
                default: pts.add(new double[][]{{u * BX, v * BY, BZ}, {0, 0, 1}});
            }
        }
        // La hoja alrededor (textura del papel y la mesa)
        for (int i = 0; i < 1500; i++) {
            double x = (rnd.nextDouble() - 0.5) * 260, y = (rnd.nextDouble() - 0.5) * 340;
            if (Math.abs(x) < BX + 2 && Math.abs(y) < BY + 2) continue;
            pts.add(new double[][]{{x, y, 0}, {0, 0, 1}});
        }
        List<Sfm.Track> out = new ArrayList<>();
        for (double[][] q : pts) {
            Sfm.Track t = null;
            for (int f = 0; f < cams.size(); f++) {
                Geometry.Pose p = cams.get(f);
                double[] xc = p.apply(q[0]);
                double u = F * xc[0] / xc[2] + W / 2.0, v = F * xc[1] / xc[2] + H / 2.0;
                boolean vis = xc[2] > 0 && u > 5 && v > 5 && u < W - 5 && v < H - 5 && Lin.dot(q[1], Lin.sub(p.center(), q[0])) > 0;
                if (vis) {
                    float nu = (float) (u + 0.3 * rnd.nextGaussian()), nv = (float) (v + 0.3 * rnd.nextGaussian());
                    if (t == null) t = new Sfm.Track(f, nu, nv, 0xFF808080);
                    else t.add(nu, nv);
                } else if (t != null) {
                    out.add(t);
                    t = null;
                }
            }
            if (t != null) out.add(t);
        }
        return out;
    }

    @Test
    public void laHojaDaLaFocalYPrefiereLaDeLaCamaraSiCalza() {
        Random rnd = new Random(1);
        List<Geometry.Pose> cams = orbit();
        List<List<Markers.Marker>> ms = markers(cams, 0.4, rnd);
        SheetScan.Report rep = new SheetScan.Report();
        Lens est = SheetScan.chooseLens(ms, 0, W, H, rep);
        assertNotNull(est);
        assertEquals("hoja", est.source);
        assertEquals(F, est.f, 0.01 * F);
        assertTrue(rep.lensNote, rep.lensNote.contains("galería"));
        // Focal de la cámara correcta: se usa
        Lens cam = SheetScan.chooseLens(ms, F * 1.003, W, H, rep = new SheetScan.Report());
        assertEquals("cámara", cam.source);
        // Focal de la cámara 10 % corrida (estabilización que recorta): se usa la de la hoja y se avisa
        Lens off = SheetScan.chooseLens(ms, F * 1.1, W, H, rep = new SheetScan.Report());
        assertEquals("hoja", off.source);
        assertEquals(F, off.f, 0.01 * F);
        assertTrue(rep.lensNote, rep.lensNote.startsWith("⚠"));
    }

    @Test
    public void midoLaCajaEnMilimetros() {
        Random rnd = new Random(2);
        List<Geometry.Pose> cams = orbit();
        List<List<Markers.Marker>> ms = markers(cams, 0.4, rnd);
        SheetScan.Report rep = new SheetScan.Report();
        Lens lens = SheetScan.chooseLens(ms, 0, W, H, rep);
        SheetPose.Fix[] fx = SheetScan.poses(ms, lens, rep);
        assertTrue("ubicados " + rep.posed, rep.posed > 200);
        // Las poses en mm: el centro de cada cámara a menos de 3 mm del real
        double worst = 0;
        for (int f = 0; f < fx.length; f++)
            if (fx[f] != null) worst = Math.max(worst, Lin.norm(Lin.sub(fx[f].pose.center(), cams.get(f).center())));
        assertTrue("centro de cámara a " + worst + " mm", worst < 3);
        Sfm.Result r = SheetScan.build(tracks(cams, rnd), fx, lens, W, H, rep);
        assertNotNull(r);
        assertTrue("puntos " + rep.triangulated, rep.triangulated > 1000);
        assertTrue(r.poses.size() <= SheetScan.MAX_CAMS);
        Measure.Choice ch = SheetScan.measure(r);
        assertNotNull(ch);
        Measure.Box b = ch.box;
        assertTrue("sobre la mesa", !b.onWall);
        assertEquals("alto (del apoyo al tope)", BZ, b.alto + b.separation, 3);
        assertEquals("ancho", 2 * BY, Math.min(b.ancho, b.prof), 3);
        assertEquals("largo", 2 * BX, Math.max(b.ancho, b.prof), 3);
    }

    @Test
    public void laHojaPlanaDejaLosPuntosEnZCeroYAfinaLasCamaras() {
        Random rnd = new Random(5);
        List<Geometry.Pose> cams = orbit();
        // Marcas con más ruido (1,5 px): las poses de cada cuadro tiemblan
        List<List<Markers.Marker>> ms = markers(cams, 1.5, rnd);
        SheetScan.Report rep = new SheetScan.Report();
        Lens lens = new Lens(F, W / 2.0, H / 2.0);
        SheetPose.Fix[] fx = SheetScan.poses(ms, lens, rep);
        List<Sfm.Track> tr = tracks(cams, rnd);
        // Sin usar el plano
        SheetScan.PLANE = false;
        SheetScan.Report r0 = new SheetScan.Report();
        Sfm.Result a;
        try {
            a = SheetScan.build(tr, fx, lens, W, H, r0);
        } finally {
            SheetScan.PLANE = true;
        }
        Sfm.Result b = SheetScan.build(tr, fx, lens, W, H, rep);
        assertNotNull(a);
        assertNotNull(b);
        assertTrue("puntos del plano " + rep.planePoints, rep.planePoints > 500);
        assertTrue("anclas " + rep.planeAnchors, rep.planeAnchors > 200);
        // Los puntos del plano quedan exactamente en Z = 0; los del objeto, arriba
        int onPlane = 0;
        for (double[] p : b.points) if (p[2] == 0) onPlane++;
        assertEquals(rep.planePoints, onPlane);
        // Las cámaras afinadas quedan más cerca de las reales
        assertTrue("error de cámara " + camErr(b, cams) + " mm (antes " + camErr(a, cams) + ")", camErr(b, cams) < camErr(a, cams));
        assertTrue(rep.sheetNoiseAfter <= rep.sheetNoiseBefore);
        Measure.Box box = SheetScan.measure(b).box;
        assertEquals("alto", BZ, box.alto + box.separation, 3);
        assertEquals("ancho", 2 * BY, Math.min(box.ancho, box.prof), 3);
        assertEquals("largo", 2 * BX, Math.max(box.ancho, box.prof), 3);
    }

    /** Mediana de la distancia (mm) entre los centros de las cámaras de {@code r} y los reales. */
    static double camErr(Sfm.Result r, List<Geometry.Pose> cams) {
        double[] e = new double[r.poses.size()];
        for (int c = 0; c < e.length; c++) e[c] = Lin.norm(Lin.sub(r.poses.get(c).center(), cams.get(r.camFrames.get(c)).center()));
        java.util.Arrays.sort(e);
        return e[e.length / 2];
    }

    @Test
    public void lasPistasQueSaltanCuadrosTambiénSeTriangulanYSeCuentanAparte() {
        Random rnd = new Random(4);
        List<Geometry.Pose> cams = orbit();
        List<List<Markers.Marker>> ms = markers(cams, 0.4, rnd);
        SheetScan.Report rep = new SheetScan.Report();
        Lens lens = SheetScan.chooseLens(ms, 0, W, H, rep);
        SheetPose.Fix[] fx = SheetScan.poses(ms, lens, rep);
        List<Integer> keys = SheetScan.keyframes(fx);
        for (int i = 1; i < keys.size(); i++) assertTrue(keys.get(i) - keys.get(i - 1) >= SheetScan.MIN_GAP);
        // La mitad de las pistas, vista sólo en los cuadros clave (como las de XFeat)
        List<Sfm.Track> all = new ArrayList<>(), klt = tracks(cams, rnd);
        for (int i = 0; i < klt.size(); i++) {
            Sfm.Track t = klt.get(i);
            if (i % 2 == 0) { all.add(t); continue; }
            List<Integer> fr = new ArrayList<>();
            for (int k : keys) if (t.visible(k)) fr.add(k);
            if (fr.size() < 3) continue;
            int[] f = fr.stream().mapToInt(Integer::intValue).toArray();
            float[] xs = new float[f.length], ys = new float[f.length];
            for (int j = 0; j < f.length; j++) { xs[j] = t.x(f[j]); ys[j] = t.y(f[j]); }
            all.add(Sfm.Track.sparse(f, xs, ys, t.color));
        }
        Sfm.Result r = SheetScan.build(all, fx, lens, W, H, rep);
        assertNotNull(r);
        assertEquals(rep.triangulated, rep.kltPoints + rep.xfeatPoints);
        assertTrue("puntos XFeat " + rep.xfeatPoints, rep.xfeatPoints > 300);
        assertTrue("puntos KLT " + rep.kltPoints, rep.kltPoints > 300);
        Measure.Box b = SheetScan.measure(r).box;
        assertEquals("alto", BZ, b.alto + b.separation, 3);
    }
}
