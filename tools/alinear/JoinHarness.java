package com.josexato.navaja.measure.core;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
/** Lo mismo que JoinActivity (Agregar otra pose), en el PC y por pasos, con los modelos corridos por xfpy.py / unir.py.
 *  uso: JoinHarness cut|sel|fit <zip A descomprimido> <zip B descomprimido> <trabajo> [fotos por escaneo] */
public class JoinHarness {
    static final int[] TURNS = {0, 90, 180, 270};
    static PoseJoin.Scan scan(String z) throws IOException { String d = z + "/colmap/sparse/0/";
        return PoseJoin.parse(new String(Files.readAllBytes(Paths.get(d + "cameras.txt"))), new String(Files.readAllBytes(Paths.get(d + "images.txt"))), new String(Files.readAllBytes(Paths.get(d + "points3D.txt")))); }
    static int[] px(String f, int w, int h) throws IOException { File ff = new File(f); if (!ff.exists()) return null; BufferedImage im = ImageIO.read(ff);
        return im.getWidth() == w && im.getHeight() == h ? im.getRGB(0, 0, w, h, null, 0, w) : null; }
    static List<Integer> pick(String z, PoseJoin.Scan s, int n) { List<Integer> ok = new ArrayList<>();
        for (int i = 0; i < s.names.size(); i++) if (new File(z + "/fotos/" + s.names.get(i)).exists() && new File(z + "/colmap/mascaras/" + s.names.get(i) + ".png").exists()) ok.add(i);
        if (ok.size() <= n) return ok; List<Integer> o = new ArrayList<>(); for (int k = 0; k < n; k++) o.add(ok.get((int) Math.round(k * (ok.size() - 1) / (double) (n - 1)))); return o; }
    static void put(Path p, float[] v) throws IOException { ByteBuffer b = ByteBuffer.allocate(4 * v.length).order(ByteOrder.LITTLE_ENDIAN); b.asFloatBuffer().put(v); Files.write(p, b.array()); }
    static float[] getF(Path p) throws IOException { ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(p)).order(ByteOrder.LITTLE_ENDIAN); float[] v = new float[b.capacity() / 4]; b.asFloatBuffer().get(v); return v; }
    static int[] getI(Path p) throws IOException { ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(p)).order(ByteOrder.LITTLE_ENDIAN); int[] v = new int[b.capacity() / 4]; b.asIntBuffer().get(v); return v; }
    /** Qué parte de cada silueta (sin ensanchar) cubre el volumen visto desde esa foto, y qué parte del volumen proyectado cae fuera de ella: medianas por escaneo. */
    static String cover(PoseJoin.Hull hu, List<PoseJoin.Cam> cams, List<byte[]> raw) {
        com.josexato.navaja.scan3d.core.Carver.Grid g = hu.grid; StringBuilder sb = new StringBuilder();
        for (int sidx = 0; sidx < 2; sidx++) { List<Double> rec = new ArrayList<>(), out = new ArrayList<>();
            for (int ci = 0; ci < cams.size(); ci++) { PoseJoin.Cam c = cams.get(ci); if (c.scan != sidx) continue; byte[] m = raw.get(ci); boolean[] cov = new boolean[c.w * c.h];
                for (int k = 0; k < g.nz; k++) for (int j = 0; j < g.ny; j++) for (int i = 0; i < g.nx; i++) { if (g.occ[g.idx(i, j, k)] == 0) continue;
                    double[] x = c.pose.apply(new double[]{g.x0 + (i + 0.5) * g.step, g.y0 + (j + 0.5) * g.step, g.z0 + (k + 0.5) * g.step}); if (x[2] <= 0) continue;
                    double u = c.f * x[0] / x[2] + c.cx, v = c.f * x[1] / x[2] + c.cy; int r = (int) Math.ceil(0.75 * g.step * c.f / x[2]);
                    for (int dv = -r; dv <= r; dv++) for (int du = -r; du <= r; du++) { int uu = (int) u + du, vv = (int) v + dv; if (uu >= 0 && vv >= 0 && uu < c.w && vv < c.h) cov[vv * c.w + uu] = true; } }
                int mk = 0, both = 0, cv = 0; for (int q = 0; q < cov.length; q++) { if (m[q] != 0) mk++; if (cov[q]) cv++; if (cov[q] && m[q] != 0) both++; }
                if (mk > 400 && cv > 0) { rec.add(both / (double) mk); out.add((cv - both) / (double) cv); } }
            Collections.sort(rec); Collections.sort(out);
            if (!rec.isEmpty()) sb.append(String.format(Locale.ROOT, " · toma %s: cubre %.0f %% de la silueta (peor foto %.0f %%), %.0f %% del volumen cae fuera", sidx == 0 ? "A" : "B", 100 * rec.get(rec.size() / 2), 100 * rec.get(0), 100 * out.get(out.size() / 2))); }
        return sb.toString();
    }
    public static void main(String[] a) throws Exception {
        String mode = a[0]; String[] z = {a[1], a[2]}; Path wk = Paths.get(a[3]); Files.createDirectories(wk); int n = a.length > 4 ? Integer.parseInt(a[4]) : 12;
        PoseJoin.Scan[] sc = {scan(z[0]), scan(z[1])};
        // Vistas en orden fijo: las de A (giro 0) y luego las de B (4 giros por foto); el número de vista es el nombre de los archivos
        List<int[]> views = new ArrayList<>(); // {escaneo, foto, giro}
        for (int s = 0; s < 2; s++) for (int i : pick(z[s], sc[s], n)) for (int t : s == 0 ? new int[]{0} : TURNS) views.add(new int[]{s, i, t});
        List<Set<Integer>> obj = Arrays.asList(new LinkedHashSet<>(), new LinkedHashSet<>());
        float[] in = new float[XFeatTracks.IN_W * XFeatTracks.IN_H]; StringBuilder list = new StringBuilder(); int na = 0;
        for (int v = 0; v < views.size(); v++) {
            int s = views.get(v)[0], i = views.get(v)[1], turn = views.get(v)[2]; PoseJoin.Scan S = sc[s]; int w = S.width, h = S.height; String name = S.names.get(i);
            if (s == 0) na++;
            int[] argb = px(z[s] + "/fotos/" + name, w, h), mk = px(z[s] + "/colmap/mascaras/" + name + ".png", w, h); if (argb == null || mk == null) continue;
            byte[] mask = new byte[w * h]; for (int k = 0; k < mask.length; k++) mask[k] = (byte) ((mk[k] & 255) > 127 ? 1 : 0);
            double[] rect = PoseJoin.rect(mask, w, h); if (rect == null) continue;
            float[] ob = S.obsXY.get(i); int[] pt = S.obsPoint.get(i);
            for (int k = 0; k < pt.length; k++) { int x = Math.round(ob[2 * k]), y = Math.round(ob[2 * k + 1]); if (x >= 0 && y >= 0 && x < w && y < h && mask[y * w + x] != 0) obj.get(s).add(pt[k]); }
            int[] wh = PoseJoin.cutSize(w, h, turn); int[] cut = PoseJoin.cut(argb, mask, w, h, rect, turn);
            if (mode.equals("cut")) { XFeatTracks.input(cut, wh[0], wh[1], in); put(wk.resolve(String.format("in_%03d.bin", v)), in); list.append(v).append(' ').append(s).append('\n'); continue; }
            if (mode.equals("sel")) {
                XFeatTracks.Frame f = XFeatTracks.select(v, getF(wk.resolve(String.format("d_%03d.bin", v))), getF(wk.resolve(String.format("h_%03d.bin", v))), getF(wk.resolve(String.format("r_%03d.bin", v))), cut, wh[0], wh[1]);
                put(wk.resolve(String.format("gxy_%03d.bin", v)), f.glueXY); put(wk.resolve(String.format("gd_%03d.bin", v)), f.desc);
                double[][] xy = new double[XFeatTracks.N][]; for (int k = 0; k < xy.length; k++) xy[k] = k < f.n ? PoseJoin.toPhoto(f.x[k], f.y[k], rect, w, h, turn) : new double[]{-1e9, -1e9};
                int[] p3 = PoseJoin.to3d(S, i, mask, xy); ByteBuffer b = ByteBuffer.allocate(4 * p3.length + 4).order(ByteOrder.LITTLE_ENDIAN); b.putInt(f.n); for (int q : p3) b.putInt(q); Files.write(wk.resolve(String.format("p3_%03d.bin", v)), b.array());
            }
        }
        if (mode.equals("cut")) { Files.write(wk.resolve("vistas.txt"), list.toString().getBytes()); System.out.println(views.size() + " vistas (" + na + " de A)"); return; }
        if (mode.equals("sel")) { System.out.println("puntos elegidos"); return; }
        // fit: pares de LighterGlue (m_ y v_ de unir.py) → pares 3D → ajuste
        List<double[]> p = new ArrayList<>(), q = new ArrayList<>(); int[] perTurn = new int[4];
        for (int x = 0; x < views.size(); x++) { if (views.get(x)[0] != 0) continue;
            for (int y = 0; y < views.size(); y++) { if (views.get(y)[0] != 1) continue;
                Path m = wk.resolve(String.format("m_%03d_%03d.bin", x, y)); if (!Files.exists(m)) continue;
                int[] mm = getI(m), px3 = getI(wk.resolve(String.format("p3_%03d.bin", x))), py3 = getI(wk.resolve(String.format("p3_%03d.bin", y)));
                XFeatTracks.Frame fa = new XFeatTracks.Frame(x), fb = new XFeatTracks.Frame(y); fa.n = px3[0]; fb.n = py3[0];
                for (int[] pr : XFeatTracks.pairs(fa, fb, Arrays.copyOfRange(mm, 0, 1024), Arrays.copyOfRange(mm, 1024, 2048), getF(wk.resolve(String.format("v_%03d_%03d.bin", x, y))))) {
                    int i3 = px3[1 + pr[0]], j3 = py3[1 + pr[1]]; if (i3 < 0 || j3 < 0) continue;
                    p.add(sc[0].points[i3]); q.add(sc[1].points[j3]); perTurn[views.get(y)[2] / 90]++; } } }
        PoseJoin.Fit f = PoseJoin.fit(p, q, 1);
        List<double[]> ca = new ArrayList<>(), cb = new ArrayList<>(); for (int id : obj.get(0)) ca.add(sc[0].points[id]); for (int id : obj.get(1)) cb.add(sc[1].points[id]);
        System.out.printf("pares 3D %d (giros 0/90/180/270: %d %d %d %d) · puntos del objeto %d y %d%n", p.size(), perTurn[0], perTurn[1], perTurn[2], perTurn[3], ca.size(), cb.size());
        if (f == null) { System.out.println("sin ajuste"); return; }
        System.out.printf("coherentes %d de %d (%.0f %%), error %.1f mm · giro %.1f°, inclinación %.1f° · nubes: alineadas %.1f mm, sin girar %.1f mm · %s%n", f.inliers, f.pairs, 100.0 * f.inliers / f.pairs, f.error,
                f.angleDeg, f.tiltDeg, PoseJoin.cloudGap(ca, cb, f), PoseJoin.cloudGap(ca, cb, null), f.ok() ? "UNIDAS" : "no alcanza");
        System.out.printf("bajo la hoja (más de 5 mm): %.1f %% de los puntos de A ya movidos%n", 100 * PoseJoin.below(ca, f, 5));
        System.out.printf(Locale.ROOT, "R %s%nt %s%n", Arrays.toString(f.r), Arrays.toString(f.t));
        if (!"1".equals(System.getenv("TALLAR"))) return;
        // Un solo volumen con las siluetas de las dos tomas (como JoinActivity), más cada toma sola para comparar
        int grow = System.getenv("CRECER") != null ? Integer.parseInt(System.getenv("CRECER")) : PoseJoin.GROW_A;
        List<PoseJoin.Cam> cams = new ArrayList<>(); List<byte[]> raw = new ArrayList<>(); List<String> photoPath = new ArrayList<>();
        for (int sidx = 0; sidx < 2; sidx++) { PoseJoin.Scan S = sc[sidx]; int w = S.width, h = S.height;
            for (int i = 0; i < S.names.size(); i++) { int[] mk = px(z[sidx] + "/colmap/mascaras/" + S.names.get(i) + ".png", w, h); if (mk == null) continue;
                byte[] mask = new byte[w * h]; for (int k = 0; k < mask.length; k++) mask[k] = (byte) ((mk[k] & 255) > 127 ? 1 : 0);
                PoseJoin.Cam c = new PoseJoin.Cam(); c.scan = sidx; c.w = w; c.h = h; c.f = S.focal; c.cx = S.cx; c.cy = S.cy;
                c.pose = sidx == 0 ? PoseJoin.moved(S.poses.get(i), f) : S.poses.get(i); c.mask = sidx == 0 && grow > 0 ? PoseJoin.grow(mask, w, h, grow) : mask; cams.add(c); raw.add(mask); photoPath.add(z[sidx] + "/fotos/" + S.names.get(i)); } }
        double[][] box = PoseJoin.box(ca, cb, f); long t0 = System.nanoTime();
        PoseJoin.Hull hu = PoseJoin.carve(cams, box, f);
        if (hu == null) { System.out.println("sin volumen"); return; }
        System.out.printf("volumen con las dos tomas: %.0f cm3 · %.0f × %.0f × %.0f mm · %d + %d siluetas · %.1f s · %s%n", hu.volume, hu.size[0], hu.size[1], hu.size[2], hu.viewsA, hu.viewsB, (System.nanoTime() - t0) / 1e9, hu.grid);
        System.out.println("  las dos" + cover(hu, cams, raw));
        com.josexato.navaja.scan3d.core.Mesh m = com.josexato.navaja.scan3d.core.SurfaceNets.extract(hu.grid); m.taubinSmooth(8); m.writeStl(wk.resolve("union.stl").toFile());
        System.out.println("malla " + m.triangleCount() + " triángulos → " + wk.resolve("union.stl"));
        { // Modelo simplificado con textura de las fotos de las dos tomas (como JoinActivity) → union.glb
            com.josexato.navaja.scan3d.core.Mesh low = com.josexato.navaja.scan3d.core.Decimate.simplify(m, 3000);
            Sfm.Result r = new Sfm.Result(); r.width = cams.get(0).w; r.height = cams.get(0).h; double[] focal = new double[cams.size()];
            for (int c = 0; c < cams.size(); c++) { r.poses.add(cams.get(c).pose); r.camFrames.add(c); focal[c] = cams.get(c).f; } r.focal = focal[0];
            double[][] verts = new double[low.vertexCount()][]; for (int v = 0; v < verts.length; v++) verts[v] = new double[]{low.pos[3 * v], low.pos[3 * v + 1], low.pos[3 * v + 2]};
            final int W = r.width, H = r.height; final List<String> ph = photoPath;
            Texture.Result tx = Texture.build(r, verts, low.tri, null, c -> { try { return px(ph.get(c), W, H); } catch (IOException e) { return null; } }, 2048, focal);
            BufferedImage bi = new BufferedImage(tx.width, tx.height, BufferedImage.TYPE_INT_RGB); bi.setRGB(0, 0, tx.width, tx.height, tx.atlas, 0, tx.width);
            ByteArrayOutputStream jo = new ByteArrayOutputStream(); ImageIO.write(bi, "jpg", jo);
            Files.write(wk.resolve("union.glb"), com.josexato.navaja.scan3d.core.Glb.write(low.pos, low.tri, tx.uv, jo.toByteArray(), null, true, 0.001, "union"));
            System.out.printf("modelo %d triángulos · textura %d × %d con %d fotos, %d sin ver → %s%n", low.triangleCount(), tx.width, tx.height, tx.photosUsed, tx.unseen, wk.resolve("union.glb")); }
        // Cada toma sola, con la misma caja y la misma regla (para ver qué recorta la otra)
        for (int only = 0; only < 2; only++) { List<PoseJoin.Cam> one = new ArrayList<>();
            for (PoseJoin.Cam c : cams) { one.add(c); if (c.scan != only) { PoseJoin.Cam d = new PoseJoin.Cam(); d.scan = c.scan; d.w = c.w; d.h = c.h; d.f = c.f; d.cx = c.cx; d.cy = c.cy; d.pose = c.pose; d.mask = new byte[c.w * c.h]; Arrays.fill(d.mask, (byte) 1); one.set(one.size() - 1, d); } }
            PoseJoin.Hull ho = PoseJoin.carve(one, box, f);
            if (ho != null) { System.out.printf("sólo la toma %s: %.0f cm3 · %.0f × %.0f × %.0f mm%n", only == 0 ? "A" : "B", ho.volume, ho.size[0], ho.size[1], ho.size[2]); System.out.println("  " + cover(ho, cams, raw));
                com.josexato.navaja.scan3d.core.Mesh mo = com.josexato.navaja.scan3d.core.SurfaceNets.extract(ho.grid); mo.taubinSmooth(8); mo.writeStl(wk.resolve(only == 0 ? "solo_a.stl" : "solo_b.stl").toFile()); } }
    }
}
