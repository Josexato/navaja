package com.josexato.navaja.measure.core;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import com.josexato.navaja.scan3d.core.Carver;
/** Arnés XFeat: modo "in" (imagenes del modelo), "sel" (puntos + entradas del emparejador), "fin" (pistas, triangulación y medida). uso: <modo> <zip> <trabajo> */
public class XfHarness {
    static File[] fotos(String z) { File[] fs = new File(z + "/fotos").listFiles((d, n) -> n.endsWith(".jpg")); Arrays.sort(fs); return fs; }
    static void put(Path p, float[] v) throws IOException { ByteBuffer b = ByteBuffer.allocate(4 * v.length).order(ByteOrder.LITTLE_ENDIAN); b.asFloatBuffer().put(v); Files.write(p, b.array()); }
    static float[] getF(Path p) throws IOException { ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(p)).order(ByteOrder.LITTLE_ENDIAN); float[] v = new float[b.capacity() / 4]; b.asFloatBuffer().get(v); return v; }
    static int[] getI(Path p) throws IOException { ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(p)).order(ByteOrder.LITTLE_ENDIAN); int[] v = new int[b.capacity() / 4]; b.asIntBuffer().get(v); return v; }
    static int[] px(File f, int[] wh) throws IOException { BufferedImage im = ImageIO.read(f); wh[0] = im.getWidth(); wh[1] = im.getHeight(); return im.getRGB(0, 0, wh[0], wh[1], null, 0, wh[0]); }
    static List<Integer> todos(SheetPose.Fix[] fx) { List<Integer> o = new ArrayList<>(); for (int i = 0; i < fx.length; i++) if (fx[i] != null) o.add(i); return o; }
    public static void main(String[] a) throws Exception {
        if ("0".equals(System.getenv("PLANO"))) SheetScan.PLANE = false;
        String mode = a[0], z = a[1]; Path wk = Paths.get(a[2]); File[] fs = fotos(z); int[] wh = new int[2];
        if (mode.equals("in")) {
            float[] in = new float[XFeatTracks.IN_W * XFeatTracks.IN_H]; long t = 0;
            for (int i = 0; i < fs.length; i++) { int[] p = px(fs[i], wh); long t0 = System.nanoTime(); XFeatTracks.input(p, wh[0], wh[1], in); t += System.nanoTime() - t0; put(wk.resolve(String.format("in_%03d.bin", i)), in); }
            System.out.printf("%d imágenes · preparar %.1f ms/foto%n", fs.length, t / 1e6 / fs.length); return;
        }
        if (mode.equals("sel")) {
            long t = 0; int few = 0;
            for (int i = 0; i < fs.length; i++) {
                Path d = wk.resolve(String.format("d_%03d.bin", i)); if (!Files.exists(d)) continue;
                int[] p = px(fs[i], wh); float[] de = getF(d), he = getF(wk.resolve(String.format("h_%03d.bin", i))), re = getF(wk.resolve(String.format("r_%03d.bin", i)));
                long t0 = System.nanoTime(); XFeatTracks.Frame f = XFeatTracks.select(i, de, he, re, p, wh[0], wh[1]); t += System.nanoTime() - t0;
                if (f.n < XFeatTracks.N) few++;
                put(wk.resolve(String.format("gxy_%03d.bin", i)), f.glueXY); put(wk.resolve(String.format("gd_%03d.bin", i)), f.desc);
                float[] kp = new float[2 * f.n]; for (int k = 0; k < f.n; k++) { kp[2 * k] = f.x[k]; kp[2 * k + 1] = f.y[k]; } put(wk.resolve(String.format("kp_%03d.bin", i)), kp);
            }
            { List<List<Markers.Marker>> mk = new ArrayList<>(); int ww = 0, hh = 0;
              for (File f : fs) { int[] p = px(f, wh); ww = wh[0]; hh = wh[1]; mk.add(Markers.detect(p, ww, hh)); }
              SheetScan.Report rp = new SheetScan.Report(); Lens ln = SheetScan.chooseLens(mk, 0, ww, hh, rp);
              SheetPose.Fix[] fxx = SheetScan.poses(mk, ln, rp); List<Integer> ks = todos(fxx);
              { List<Integer> ka = SheetScan.keyframes(fxx); SheetScan.Report r2 = new SheetScan.Report(); SheetScan.coverage(fxx, ka, r2);
                System.out.printf("por ángulo: de %d fotos ubicadas quedarían %d · vuelta %.0f° · altura %.0f–%.0f° · paso típico %.1f°, máx %.1f° · huecos %d%n", ks.size(), ka.size(), r2.turnDeg, r2.elevMin, r2.elevMax, r2.stepMedian, r2.stepMax, r2.gaps.size()); }
              StringBuilder sb = new StringBuilder(); for (int k : ks) sb.append(k).append('\n'); Files.write(wk.resolve("keys.txt"), sb.toString().getBytes());
              System.out.printf("lente f=%.1f k1=%.3f · %s · fotos ubicadas %d de %d%n", ln.f, ln.k1, rp.lensNote, ks.size(), fs.length); }
            System.out.printf("elegir puntos %.1f ms/foto · fotos con menos de %d puntos: %d%n", t / 1e6 / fs.length, XFeatTracks.N, few); return;
        }
        // fin: poses de la hoja, pistas KLT del ZIP (colmap) + pistas XFeat, triangulación, medida, siluetas
        List<List<Markers.Marker>> mk = new ArrayList<>(); int w = 0, h = 0;
        for (File f : fs) { int[] p = px(f, wh); w = wh[0]; h = wh[1]; mk.add(Markers.detect(p, w, h)); }
        SheetScan.Report rep0 = new SheetScan.Report();
        Lens lens = SheetScan.chooseLens(mk, 0, w, h, rep0);
        SheetPose.Fix[] fx = SheetScan.poses(mk, lens, rep0);
        List<Sfm.Track> klt = HojaTracks.fromColmap(z, fs);
        List<Integer> keys = todos(fx);
        int skip = a.length > 3 ? Integer.parseInt(a[3]) : 1;
        XFeatTracks.Linker lk = new XFeatTracks.Linker();
        Map<Integer, XFeatTracks.Frame> fr = new HashMap<>();
        for (int k = 0; k < keys.size(); k++) {
            int i = keys.get(k); XFeatTracks.Frame f = new XFeatTracks.Frame(i);
            float[] kp = getF(wk.resolve(String.format("kp_%03d.bin", i))); f.n = kp.length / 2;
            int[] p = px(fs[i], wh);
            for (int q = 0; q < f.n; q++) { f.x[q] = kp[2 * q]; f.y[q] = kp[2 * q + 1]; f.color[q] = p[Math.round(f.y[q]) * w + Math.round(f.x[q])]; }
            fr.put(i, f);
        }
        for (int k = 0; k < keys.size(); k++) for (int s = 1; s <= skip && k + s < keys.size(); s++) {
            int i = keys.get(k), j = keys.get(k + s); Path m = wk.resolve(String.format("m_%03d_%03d.bin", i, j)); if (!Files.exists(m)) continue;
            int[] mm = getI(m); int[] m0 = Arrays.copyOfRange(mm, 0, 1024), m1 = Arrays.copyOfRange(mm, 1024, 2048);
            float[] v0 = getF(wk.resolve(String.format("v_%03d_%03d.bin", i, j)));
            int[][] pr = XFeatTracks.pairs(fr.get(i), fr.get(j), m0, m1, v0);
            lk.add(fr.get(i), fr.get(j), pr, fx[i].pose, fx[j].pose, lens, s);
        }
        List<Sfm.Track> xt = lk.tracks(a.length > 4 ? Integer.parseInt(a[4]) : 3);
        System.out.printf("XFeat (uniones contradictorias descartadas %d): %d pares de fotos · pares de puntos %d → %d con la geometría (%.0f %%) · pistas de 3+ fotos %d%n", lk.conflicts, lk.framePairs, lk.raw, lk.kept, 100.0 * lk.kept / Math.max(1, lk.raw), xt.size());
        for (String cual : new String[]{"KLT", "KLT+XFeat", "XFeat"}) {
            List<Sfm.Track> all = new ArrayList<>();
            if (!cual.equals("XFeat")) all.addAll(klt);
            if (!cual.equals("KLT")) all.addAll(xt);
            SheetScan.Report rep = new SheetScan.Report(); rep.lens = lens;
            SheetScan.poses(mk, lens, rep);
            Sfm.Result r = SheetScan.build(all, fx, lens, w, h, rep, todos(fx));
            if (r == null) { System.out.println(cual + ": sin reconstrucción"); continue; }
            System.out.printf("   [plano: %d puntos en Z=0 (%d anclas) · ruido de altura libre %.2f → %.2f mm · cámaras movidas %.2f mm · esquinas %.2f px]%n",
                    rep.planePoints, rep.planeAnchors, rep.sheetNoiseBefore, rep.sheetNoiseAfter, rep.poseShiftMm, rep.cornerRmsAfter);
            try (PrintWriter pw = new PrintWriter(wk.resolve("puntos_" + cual + ".txt").toFile())) {
                for (int q = 0; q < r.points.length; q++) { Sfm.Track t0 = r.pointTracks.get(q).get(0);
                    pw.printf(Locale.ROOT, "%.3f %.3f %.3f %d %d%n", r.points[q][0], r.points[q][1], r.points[q][2], t0.isSparse() ? 1 : 0, r.nObs[q]); } }
            Measure.Choice ch = SheetScan.measure(r);
            if (ch == null) { System.out.println(cual + ": sin objeto"); continue; }
            Measure.Box b = ch.box;
            { int out = 0, n = 0; double[] xs = new double[b.objectPoints.length], ys = new double[xs.length], zs = new double[xs.length];
              for (int q : b.objectPoints) { double[] P = r.points[q]; xs[n] = P[0]; ys[n] = P[1]; zs[n++] = P[2]; if (Math.abs(P[0]) > 105 || Math.abs(P[1]) > 148.5) out++; }
              Arrays.sort(xs); Arrays.sort(ys); Arrays.sort(zs);
              System.out.printf("   [objeto: %d fuera de la hoja · x p5..p95 %.0f..%.0f · y %.0f..%.0f · z %.0f..%.0f]%n", out, xs[n/20], xs[n*19/20], ys[n/20], ys[n*19/20], zs[n/20], zs[n*19/20]); }
            System.out.printf("%-10s puntos %5d (KLT %d, XFeat %d, reproy. %.2f px) · por puntos: alto %.0f ancho %.0f largo %.0f mm (%d del objeto)", cual, rep.triangulated, rep.kltPoints, rep.xfeatPoints, rep.pointRms, b.alto + b.separation, b.ancho, b.prof, b.objectPoints.length);
            int mw = w / 2, mh = h / 2; byte[][] masks = new byte[r.poses.size()][];
            for (int c = 0; c < masks.length; c++) {
                File m = new File(z + "/colmap/mascaras/" + fs[r.camFrames.get(c)].getName() + ".png"); if (!m.exists()) continue;
                BufferedImage mi = ImageIO.read(m); byte[] mm = new byte[mw * mh];
                for (int y = 0; y < mh; y++) for (int x = 0; x < mw; x++) mm[y * mw + x] = (byte) ((mi.getRGB(x * 2, y * 2) & 255) > 127 ? 1 : 0);
                masks[c] = mm;
            }
            byte[][] carveMasks = null;
            if ("1".equals(System.getenv("SIL"))) {
                SheetMasks.Stats st = new SheetMasks.Stats();
                if (System.getenv("DENTRO") != null) SheetMasks.MIN_INSIDE = Double.parseDouble(System.getenv("DENTRO"));
                masks = SheetMasks.refineAll(r, b, masks, mw, mh, st, false);
                if (System.getenv("ROMPER") != null) { // estropea una parte de las siluetas: desborde (mancha grande al lado) o corte (media silueta)
                    double share = Double.parseDouble(System.getenv("ROMPER")); Random rr = new Random(11); int broke = 0;
                    for (int c = 0; c < masks.length; c++) { if (masks[c] == null || rr.nextDouble() > share) continue; broke++;
                        byte[] m = masks[c].clone(); int x0 = mw, x1 = 0, y0 = mh, y1 = 0;
                        for (int i = 0; i < m.length; i++) if (m[i] != 0) { x0 = Math.min(x0, i % mw); x1 = Math.max(x1, i % mw); y0 = Math.min(y0, i / mw); y1 = Math.max(y1, i / mw); }
                        if (rr.nextBoolean()) { int mid = (x0 + x1) / 2; for (int y = y0; y <= y1; y++) for (int x = mid; x <= x1; x++) m[y * mw + x] = 0; }
                        else { for (int y = y0; y < Math.min(mh, y1 + (y1 - y0)); y++) for (int x = x1; x < Math.min(mw, x1 + (x1 - x0)); x++) m[y * mw + x] = 1; }
                        masks[c] = m; }
                    System.out.printf("[rotas %d] ", broke); }
                if ("1".equals(System.getenv("CONSENSO"))) { masks = SheetMasks.consensus(r, b, masks, mw, mh, st);
                    System.out.printf("[consenso: IoU mediano %.2f · reparadas %d · descartadas %d] ", st.iouMedian, st.repaired.size(), st.dropped.size()); System.out.printf("[no calzan %d] ", st.flagged); }
                if (!"0".equals(System.getenv("AGREGAR"))) carveMasks = SheetMasks.refineAll(r, b, masks, mw, mh, st, true);
                System.out.printf("[corregidas %d · borrados %d · agregados %d · con %d puntos] ", st.masks, st.clipped, st.added, st.addPoints);
            }
            if (carveMasks == null) carveMasks = masks;
            byte[][] used = Carve.validate(r, b, masks, mw, mh);
            Measure.Box nb = Measure.rebox(r, b, Carve.silhouetteMembers(r, b, used, mw, mh)); if (nb != null) b = nb;
            System.out.printf(" · con siluetas: alto %.0f ancho %.0f largo %.0f (%d)", b.alto + b.separation, b.ancho, b.prof, b.objectPoints.length);
            if (cual.equals("KLT+XFeat")) try (PrintWriter pw = new PrintWriter(wk.resolve("obj3d.txt").toFile())) {
                // Para alinear dos escaneos: cada punto 3D con pista XFeat, con el índice de su punto clave en cada foto
                Set<Integer> obj = new HashSet<>(); for (int q : b.objectPoints) obj.add(q);
                Map<Integer, Map<Long, Integer>> kpIdx = new HashMap<>();
                for (int q = 0; q < r.pointTracks.size() && q < r.points.length; q++) { Sfm.Track t0 = r.pointTracks.get(q).get(0); if (!t0.isSparse()) continue;
                    StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "%.3f %.3f %.3f %d", r.points[q][0], r.points[q][1], r.points[q][2], obj.contains(q) ? 1 : 0));
                    for (int c = 0; c < r.camFrames.size(); c++) { int f = r.camFrames.get(c); if (!t0.visible(f)) continue;
                        Map<Long, Integer> m = kpIdx.get(f);
                        if (m == null) { m = new HashMap<>(); float[] kp = getF(wk.resolve(String.format("kp_%03d.bin", f))); for (int i = 0; i < kp.length / 2; i++) m.put(((long) Float.floatToIntBits(kp[2 * i]) << 32) ^ (Float.floatToIntBits(kp[2 * i + 1]) & 0xffffffffL), i); kpIdx.put(f, m); }
                        Integer ki = m.get(((long) Float.floatToIntBits(t0.x(f)) << 32) ^ (Float.floatToIntBits(t0.y(f)) & 0xffffffffL));
                        if (ki != null) sb.append(' ').append(f).append(':').append(ki); }
                    pw.println(sb); }
            }
            used = Carve.validate(r, b, carveMasks, mw, mh);
            Carve.Result hr = Carve.carve(r, b, used, mw, mh, "1".equals(System.getenv("LIBRE")));
            if (hr != null && System.getenv("CASCO") != null) try (PrintWriter pw = new PrintWriter(System.getenv("CASCO"))) {
                Carver.Grid g = hr.grid; pw.printf(Locale.ROOT, "%d %d %d %.4f%n", g.nx, g.ny, g.nz, g.step);
                for (int k = 0; k < g.nz; k++) for (int j = 0; j < g.ny; j++) for (int i = 0; i < g.nx; i++) if (g.occ[g.idx(i, j, k)] != 0) pw.println(i + " " + j + " " + k);
                System.out.printf(" [volumen %.0f cm3]", hr.volume / 1000);
            }
            if (hr != null) { Measure.refineWithHull(r, b, hr); System.out.printf(" · casco %.0f×%.0f×%.0f · final alto %.0f ancho %.0f largo %.0f", hr.front, hr.ancho, hr.prof, b.alto + b.separation, b.ancho, b.prof); }
            System.out.println();
        }
    }
}
