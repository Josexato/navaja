package com.josexato.navaja.measure.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Reconstrucción 3D desde un video ("structure from motion"): los puntos se
 * siguen cuadro a cuadro ({@link Tracker}), y con ellos se calcula dónde
 * estuvo el celular en cada cuadro clave y dónde está cada punto en 3D. El
 * resultado queda a una escala arbitraria: los centímetros los da después
 * una medida conocida.
 */
public final class Sfm {

    private Sfm() { }

    public static StringBuilder LOG = null;

    /** Un punto seguido en cuadros consecutivos [start, start + n). */
    public static final class Track {
        public final int start;
        public final int color;
        float[] x = new float[8], y = new float[8];
        int n;

        public Track(int start, float x0, float y0, int color) {
            this.start = start;
            this.color = color;
            add(x0, y0);
        }

        public void add(float px, float py) {
            if (n == x.length) {
                x = Arrays.copyOf(x, n * 2);
                y = Arrays.copyOf(y, n * 2);
            }
            x[n] = px;
            y[n] = py;
            n++;
        }

        /** Huellas tomadas a lo largo de la pista (cuadro, huella). */
        int[] dFrame = new int[0];
        long[][] desc = new long[0][];

        void addDesc(int frame, long[] d) {
            if (d == null) return;
            dFrame = Arrays.copyOf(dFrame, dFrame.length + 1);
            desc = Arrays.copyOf(desc, desc.length + 1);
            dFrame[dFrame.length - 1] = frame;
            desc[desc.length - 1] = d;
        }

        /** La huella tomada más cerca del cuadro dado, o null si ninguna a menos de {@code maxGap} cuadros. */
        long[] descNear(int frame, int maxGap) {
            long[] best = null;
            int bg = maxGap + 1;
            for (int i = 0; i < dFrame.length; i++) {
                int g = Math.abs(dFrame[i] - frame);
                if (g < bg) { bg = g; best = desc[i]; }
            }
            return best;
        }

        public int end() { return start + n - 1; }

        public boolean visible(int frame) { return frame >= start && frame < start + n; }

        public float x(int frame) { return x[frame - start]; }

        public float y(int frame) { return y[frame - start]; }
    }

    /** Sigue puntos a lo largo del video, cuadro a cuadro. */
    public static final class Tracker {
        public final List<Track> tracks = new ArrayList<>();
        private final List<Track> active = new ArrayList<>();
        private Klt.Pyramid prev;
        private float[] prevSmooth;
        private int frame = -1;
        public final int maxPoints;
        /** Huellas BRIEF para reubicar; el seguimiento denso no las necesita. */
        private final boolean descriptors;

        public Tracker(int maxPoints) { this(maxPoints, true); }

        /** Zona a la que se limita el seguimiento (null = toda la imagen) y tamaño de celda para sembrar. */
        private Zone zone;
        private int cell;

        public Tracker(int maxPoints, boolean descriptors) {
            this.maxPoints = maxPoints;
            this.descriptors = descriptors;
        }

        /**
         * Sigue puntos sólo dentro de la zona, sembrados en celdas de
         * {@code cellPx} píxeles (más chicas que lo normal: más puntos por
         * área). Los que salen de la zona se sueltan para no gastar tiempo.
         */
        public Tracker(int maxPoints, Zone zone, int cellPx) {
            this(maxPoints, false);
            this.zone = zone;
            this.cell = cellPx;
        }

        public int frames() { return frame + 1; }

        public int activeCount() { return active.size(); }

        public void feed(int[] argb, int w, int h) {
            frame++;
            Klt.Pyramid pyr = new Klt.Pyramid(Klt.gray(argb), w, h);
            float[] sm = descriptors ? Brief.smooth(pyr.img[0], w, h) : null;
            if (prev != null && !active.isEmpty()) {
                List<float[]> pts = new ArrayList<>();
                for (Track t : active) pts.add(new float[]{t.x[t.n - 1], t.y[t.n - 1]});
                float[][] nx = Klt.track(prev, pyr, pts);
                List<Track> keep = new ArrayList<>();
                for (int k = 0; k < active.size(); k++) {
                    Track t = active.get(k);
                    if (nx[k] != null) {
                        t.add(nx[k][0], nx[k][1]);
                        keep.add(t);
                        if (descriptors && frame % 5 == 0) t.addDesc(frame, Brief.describe(sm, w, h, nx[k][0], nx[k][1]));
                    } else if (descriptors) {
                        // Se perdió: huella de su última posición, para reconocerlo después
                        t.addDesc(frame - 1, Brief.describe(prevSmooth, w, h, t.x[t.n - 1], t.y[t.n - 1]));
                    }
                }
                active.clear();
                active.addAll(keep);
            }
            int[] rect = zone == null ? null : zone.rect(frame);
            if (rect != null) {
                List<Track> in = new ArrayList<>();
                for (Track t : active) {
                    float x = t.x[t.n - 1], y = t.y[t.n - 1];
                    if (x >= rect[0] && y >= rect[1] && x < rect[2] && y < rect[3]) in.add(t);
                }
                active.clear();
                active.addAll(in);
            } else if (zone != null) {
                active.clear(); // la zona no se ve en este cuadro
            }
            if (active.size() < maxPoints * 0.8 && (zone == null || rect != null)) {
                List<float[]> taken = new ArrayList<>();
                for (Track t : active) taken.add(new float[]{t.x[t.n - 1], t.y[t.n - 1]});
                int cell = zone != null ? this.cell : Math.max(8, (int) Math.sqrt((double) w * h / maxPoints));
                for (float[] c : Klt.corners(pyr.img[0], w, h, cell, maxPoints - active.size(), taken, rect)) {
                    int ix = (int) c[0], iy = (int) c[1];
                    Track t = new Track(frame, c[0], c[1], argb[iy * w + ix]);
                    if (descriptors) t.addDesc(frame, Brief.describe(sm, w, h, c[0], c[1]));
                    tracks.add(t);
                    active.add(t);
                }
            }
            prev = pyr;
            prevSmooth = sm;
        }
    }

    /**
     * Una esfera del modelo (centro y radio) vista en cada cuadro del video:
     * el rectángulo que la contiene, con la pose del cuadro clave más cercano.
     * {@code offset} es el cuadro del video que corresponde al cuadro 0 del
     * seguimiento.
     */
    public static final class Zone {
        private final Result r;
        private final double[] center;
        private final double radius;
        private final int offset;

        public Zone(Result r, double[] center, double radius, int offset) {
            this.r = r;
            this.center = center;
            this.radius = radius;
            this.offset = offset;
        }

        /** {x0, y0, x1, y1} en píxeles, o null si la zona no se ve en ese cuadro. */
        public int[] rect(int frame) {
            int fr = frame + offset, best = 0;
            for (int c = 1; c < r.camFrames.size(); c++)
                if (Math.abs(r.camFrames.get(c) - fr) < Math.abs(r.camFrames.get(best) - fr)) best = c;
            double[] xc = r.poses.get(best).apply(center);
            if (xc[2] <= radius) return null;
            double u = r.focal * xc[0] / xc[2] + r.width / 2.0, v = r.focal * xc[1] / xc[2] + r.height / 2.0;
            // 25 % de margen: la pose es la del cuadro clave vecino, no la del cuadro mismo
            double rp = 1.25 * r.focal * radius / xc[2];
            int[] rect = {(int) (u - rp), (int) (v - rp), (int) Math.ceil(u + rp), (int) Math.ceil(v + rp)};
            if (rect[2] <= 0 || rect[3] <= 0 || rect[0] >= r.width || rect[1] >= r.height) return null;
            return rect;
        }
    }

    // ================================================================ reconstrucción

    public static final class Result {
        public final List<Integer> camFrames = new ArrayList<>();
        public final List<Geometry.Pose> poses = new ArrayList<>();
        public double[][] points;
        public int[] colors;
        public int[] nObs;
        /** Pistas de cada punto ralo (de ahí, desde qué cámaras se vio); los puntos densos no tienen. */
        public List<List<Track>> pointTracks = new ArrayList<>();
        public double focal, rms;
        public int width, height, frames, keyframes;
        /** Tramo del video usado (cuadros) y su índice de cuadro clave final. */
        public int startFrame, endFrame, endKf;
        public String note;
    }

    public interface Progress {
        void step(String what, int done, int total);
    }

    /**
     * Reconstruye el video por tramos: si el hilo se corta del todo (un
     * objeto tapa la vista, un barrido muy rápido) y no se puede reubicar, el
     * resto del video arma un tramo nuevo. Cada tramo tiene su propia escala.
     */
    public static List<Result> reconstruct(List<Track> tracks, int frames, int w, int h, double f0, Progress pr) {
        Random rnd = new Random(7);
        // 1) Cuadros clave: cuando los puntos se movieron lo suficiente desde el anterior
        List<Integer> kf = new ArrayList<>();
        kf.add(0);
        double minDisp = 0.03 * Math.max(w, h);
        for (int fr = 1; fr < frames; fr++) {
            int last = kf.get(kf.size() - 1);
            List<Double> d = new ArrayList<>();
            int lastCount = 0;
            for (Track t : tracks) {
                if (!t.visible(last)) continue;
                lastCount++;
                if (t.visible(fr)) d.add(Math.hypot(t.x(fr) - t.x(last), t.y(fr) - t.y(last)));
            }
            if (d.size() < 30 && fr - last > 1) { kf.add(fr - 1); continue; }
            d.sort(null);
            double med = d.isEmpty() ? 0 : d.get(d.size() / 2);
            if (med > minDisp || d.size() < 0.5 * lastCount) kf.add(fr);
        }
        if (kf.get(kf.size() - 1) != frames - 1) kf.add(frames - 1);
        List<Result> out = new ArrayList<>();
        for (int from = 0; from < kf.size() - 4; ) {
            Result seg = segment(tracks, kf, from, frames, w, h, f0, pr, rnd);
            if (seg.points == null) { from += 3; continue; }
            out.add(seg);
            from = seg.endKf + 1;
        }
        return out;
    }

    /**
     * Agrega puntos con un segundo seguimiento, mucho más denso, del mismo
     * tramo: las poses del celular ya se conocen, así que cada pista sólo se
     * triangula (sin volver a ajustar nada). {@code offset} es el cuadro del
     * video que corresponde al cuadro 0 de {@code dense}. Devuelve cuántos
     * puntos agregó.
     */
    public static int densify(Result r, List<Track> dense, int offset) {
        double cx = r.width / 2.0, cy = r.height / 2.0, f = r.focal;
        List<double[]> pts = new ArrayList<>(Arrays.asList(r.points));
        List<Integer> cols = new ArrayList<>(), obsN = new ArrayList<>();
        for (int i = 0; i < r.points.length; i++) { cols.add(r.colors[i]); obsN.add(r.nObs[i]); }
        int added = 0;
        List<Geometry.Pose> ps = new ArrayList<>();
        List<double[]> ob = new ArrayList<>();
        for (Track t : dense) {
            ps.clear();
            ob.clear();
            for (int c = 0; c < r.camFrames.size(); c++) {
                int fr = r.camFrames.get(c) - offset;
                if (fr < 0 || !t.visible(fr)) continue;
                ps.add(r.poses.get(c));
                ob.add(new double[]{(t.x(fr) - cx) / f, (t.y(fr) - cy) / f});
            }
            if (ps.size() < 3) continue;
            double[] x = Geometry.triangulate(ps.toArray(new Geometry.Pose[0]), ob.toArray(new double[0][]));
            if (x == null) continue;
            boolean ok = true;
            for (int i = 0; i < ps.size() && ok; i++) {
                double[] xc = ps.get(i).apply(x);
                ok = xc[2] > 0 && f * Math.hypot(xc[0] / xc[2] - ob.get(i)[0], xc[1] / xc[2] - ob.get(i)[1]) < 2;
            }
            // Paralaje: entre la primera y la última cámara que lo ven
            if (!ok || Geometry.angleDeg(x, ps.get(0).center(), ps.get(ps.size() - 1).center()) < 2) continue;
            pts.add(x);
            cols.add(t.color);
            obsN.add(ps.size());
            added++;
        }
        r.points = pts.toArray(new double[0][]);
        r.colors = new int[cols.size()];
        r.nObs = new int[cols.size()];
        for (int i = 0; i < cols.size(); i++) { r.colors[i] = cols.get(i); r.nObs[i] = obsN.get(i); }
        return added;
    }

    private static Result segment(List<Track> tracks, List<Integer> kf, int from, int frames, int w, int h, double f0,
                                  Progress pr, Random rnd) {
        double cx = w / 2.0, cy = h / 2.0;
        double[] f = {f0};
        Result out = new Result();
        out.width = w;
        out.height = h;
        out.frames = frames;
        // 2) Inicio: primer par con suficiente paralaje
        int s0 = -1, s1 = -1;
        Geometry.TwoView tv = null;
        outer:
        for (int a = from; a < Math.min(kf.size() - 1, from + 30); a += 2) {
            for (int b = a + 1; b < Math.min(kf.size(), a + 15); b++) {
                List<Track> common = new ArrayList<>();
                for (Track t : tracks) if (t.visible(kf.get(a)) && t.visible(kf.get(b))) common.add(t);
                if (common.size() < 40) break;
                double[][] pa = new double[common.size()][], pb = new double[common.size()][];
                for (int k = 0; k < common.size(); k++) {
                    Track t = common.get(k);
                    pa[k] = new double[]{(t.x(kf.get(a)) - cx) / f[0], (t.y(kf.get(a)) - cy) / f[0]};
                    pb[k] = new double[]{(t.x(kf.get(b)) - cx) / f[0], (t.y(kf.get(b)) - cy) / f[0]};
                }
                Geometry.TwoView r = Geometry.relativePose(pa, pb, 1.5 / f[0], 500, rnd);
                if (r != null && r.inliers >= 40 && r.medianAngleDeg >= 2.5) {
                    s0 = a;
                    s1 = b;
                    tv = r;
                    break outer;
                }
            }
        }
        if (LOG != null) LOG.append("kf=").append(kf).append("\ninicio ").append(s0).append('-').append(s1).append('\n');
        if (tv == null) {
            out.endKf = from + 2;
            return out;
        }
        // Cámaras registradas (índice de BA) y puntos (por pista)
        List<Integer> camKf = new ArrayList<>();
        List<Geometry.Pose> poses = new ArrayList<>();
        camKf.add(s0);
        poses.add(new Geometry.Pose());
        camKf.add(s1);
        poses.add(tv.pose);
        Map<Track, Integer> ptOf = new HashMap<>();
        List<double[]> pts = new ArrayList<>();
        // Un punto 3D puede tener varias pistas: la original y las que lo reconocieron después
        List<List<Track>> ptTrack = new ArrayList<>();
        // Normalizar la escala inicial: |t| = 1
        double sc = 1 / Lin.norm(tv.pose.t);
        tv.pose.t = Lin.scale(tv.pose.t, sc);
        triangulateNew(tracks, kf, camKf, poses, ptOf, pts, ptTrack, f[0], cx, cy, 1.0);
        bundle(camKf, kf, poses, pts, ptTrack, f, cx, cy, 15, false);
        // 3) Avanzar hacia adelante y luego hacia atrás, partiendo siempre de la pose vecina
        int lost = 0, relocated = 0;
        // Primero los cuadros clave entre los dos del inicio, luego hacia adelante y hacia atrás
        int[][] ranges = {{s0 + 1, s1 - 1, 1}, {s1 + 1, kf.size() - 1, 1}, {s0 - 1, from, -1}};
        for (int[] rg : ranges) {
            int dir = rg[2];
            Geometry.Pose last = poses.get(camKf.indexOf(dir > 0 ? (rg == ranges[0] ? s0 : s1) : s0));
            int fails = 0;
            for (int k = rg[0]; dir > 0 ? k <= rg[1] : k >= rg[1]; k += dir) {
                if (camKf.contains(k)) continue;
                if (pr != null) pr.step("Ubicando el celular", camKf.size(), kf.size());
                Geometry.Pose p = register(tracks, kf.get(k), last.copy(), ptOf, pts, f[0], cx, cy);
                if (p == null) {
                    // Se perdió el hilo: reconocer puntos ya conocidos por su huella
                    p = relocalize(tracks, kf.get(k), ptOf, pts, ptTrack, f[0], cx, cy, rnd);
                    if (p == null) {
                        lost++;
                        // Tres cuadros clave seguidos sin ubicar: aquí termina el tramo
                        if (++fails >= 3) break;
                        continue;
                    }
                    relocated++;
                }
                fails = 0;
                camKf.add(k);
                poses.add(p);
                last = p;
                triangulateNew(tracks, kf, camKf, poses, ptOf, pts, ptTrack, f[0], cx, cy, 1.0);
                if (camKf.size() % 6 == 0) bundle(camKf, kf, poses, pts, ptTrack, f, cx, cy, 8, camKf.size() >= 8);
            }
        }
        if (LOG != null) LOG.append("perdidos=").append(lost).append(" reubicados=").append(relocated).append('\n');
        if (pr != null) pr.step("Ajuste final", camKf.size(), kf.size());
        bundle(camKf, kf, poses, pts, ptTrack, f, cx, cy, 25, camKf.size() >= 8);
        // 4) Quitar puntos mal ajustados y repetir
        List<double[]> goodPts = new ArrayList<>();
        List<List<Track>> goodTr = new ArrayList<>();
        for (int i = 0; i < pts.size(); i++) {
            List<Bundle.Obs> obs = observations(ptTrack.get(i), i, camKf, kf, cx, cy);
            double worst = 0;
            for (Bundle.Obs o : obs) worst = Math.max(worst, Bundle.reproj(poses.get(o.cam), pts.get(i), f[0], o));
            if (obs.size() >= 2 && worst < 4) {
                goodPts.add(pts.get(i));
                goodTr.add(ptTrack.get(i));
            }
        }
        double rms = bundle(camKf, kf, poses, goodPts, goodTr, f, cx, cy, 20, camKf.size() >= 8);
        out.points = goodPts.toArray(new double[0][]);
        out.pointTracks = goodTr;
        out.colors = new int[goodTr.size()];
        out.nObs = new int[goodTr.size()];
        for (int i = 0; i < goodTr.size(); i++) {
            out.colors[i] = goodTr.get(i).get(0).color;
            out.nObs[i] = observations(goodTr.get(i), i, camKf, kf, cx, cy).size();
        }
        for (int c = 0; c < camKf.size(); c++) {
            out.camFrames.add(kf.get(camKf.get(c)));
            out.poses.add(poses.get(c));
        }
        out.focal = f[0];
        out.rms = rms;
        int lo = Integer.MAX_VALUE, hi = -1;
        for (int c : camKf) { lo = Math.min(lo, c); hi = Math.max(hi, c); }
        out.endKf = hi;
        out.startFrame = kf.get(lo);
        out.endFrame = kf.get(hi);
        out.keyframes = hi - lo + 1;
        if (LOG != null) LOG.append("tramo cuadros ").append(out.startFrame).append('-').append(out.endFrame).append(" cámaras ")
                .append(camKf.size()).append('\n');
        return out;
    }

    private static Geometry.Pose register(List<Track> tracks, int frame, Geometry.Pose init, Map<Track, Integer> ptOf,
                                          List<double[]> pts, double f, double cx, double cy) {
        List<double[]> xs = new ArrayList<>(), obs = new ArrayList<>();
        for (Track t : tracks) {
            Integer p = ptOf.get(t);
            if (p == null || !t.visible(frame)) continue;
            xs.add(pts.get(p));
            obs.add(new double[]{(t.x(frame) - cx) / f, (t.y(frame) - cy) / f});
        }
        if (LOG != null) LOG.append("reg frame ").append(frame).append(" pts3d=").append(xs.size());
        if (xs.size() < 15) { if (LOG != null) LOG.append(" FALLA pocos\n"); return null; }
        double[][] x = xs.toArray(new double[0][]), o = obs.toArray(new double[0][]);
        double[] res = Geometry.refinePose(init, x, o, f, 3, 15);
        // Segunda pasada sólo con los inliers
        List<double[]> x2 = new ArrayList<>(), o2 = new ArrayList<>();
        for (int k = 0; k < res.length; k++) if (res[k] < 6) { x2.add(x[k]); o2.add(o[k]); }
        if (LOG != null) LOG.append(" inl1=").append(x2.size());
        if (x2.size() < 15 || x2.size() < 0.3 * res.length) { if (LOG != null) LOG.append(" FALLA inliers\n"); return null; }
        res = Geometry.refinePose(init, x2.toArray(new double[0][]), o2.toArray(new double[0][]), f, 2, 10);
        int in = 0;
        for (double r : res) if (r < 3) in++;
        if (LOG != null) LOG.append(" inl2=").append(in).append('\n');
        return in >= 12 ? init : null;
    }

    private static void triangulateNew(List<Track> tracks, List<Integer> kf, List<Integer> camKf, List<Geometry.Pose> poses,
                                       Map<Track, Integer> ptOf, List<double[]> pts, List<List<Track>> ptTrack, double f, double cx,
                                       double cy, double minAngle) {
        for (Track t : tracks) {
            if (ptOf.containsKey(t)) continue;
            List<Geometry.Pose> ps = new ArrayList<>();
            List<double[]> ob = new ArrayList<>();
            for (int c = 0; c < camKf.size(); c++) {
                int fr = kf.get(camKf.get(c));
                if (!t.visible(fr)) continue;
                ps.add(poses.get(c));
                ob.add(new double[]{(t.x(fr) - cx) / f, (t.y(fr) - cy) / f});
            }
            if (ps.size() < 2) continue;
            double[] x = Geometry.triangulate(ps.toArray(new Geometry.Pose[0]), ob.toArray(new double[0][]));
            if (x == null) continue;
            double maxAng = 0;
            boolean ok = true;
            for (int i = 0; i < ps.size() && ok; i++) {
                double[] xc = ps.get(i).apply(x);
                if (xc[2] <= 0) ok = false;
                else if (f * Math.hypot(xc[0] / xc[2] - ob.get(i)[0], xc[1] / xc[2] - ob.get(i)[1]) > 3) ok = false;
                for (int j = i + 1; j < ps.size(); j++) maxAng = Math.max(maxAng, Geometry.angleDeg(x, ps.get(i).center(), ps.get(j).center()));
            }
            if (!ok || maxAng < minAngle) continue;
            ptOf.put(t, pts.size());
            pts.add(x);
            List<Track> l = new ArrayList<>();
            l.add(t);
            ptTrack.add(l);
        }
    }

    private static double bundle(List<Integer> camKf, List<Integer> kf, List<Geometry.Pose> poses, List<double[]> pts,
                                 List<List<Track>> ptTrack, double[] f, double cx, double cy, int iters, boolean focal) {
        List<List<Bundle.Obs>> byPoint = new ArrayList<>();
        for (int i = 0; i < pts.size(); i++) byPoint.add(observations(ptTrack.get(i), i, camKf, kf, cx, cy));
        double[][] arr = pts.toArray(new double[0][]);
        Bundle.Result r = Bundle.run(poses, arr, f, byPoint, 2.0, iters, focal);
        for (int i = 0; i < arr.length; i++) pts.set(i, arr[i]);
        return r.rmsAfter;
    }

    /** Observaciones de un punto en las cámaras registradas (una por cámara aunque lo vean dos pistas). */
    static List<Bundle.Obs> observations(List<Track> tr, int p, List<Integer> camKf, List<Integer> kf, double cx, double cy) {
        List<Bundle.Obs> l = new ArrayList<>();
        for (int c = 0; c < camKf.size(); c++) {
            int fr = kf.get(camKf.get(c));
            for (Track t : tr)
                if (t.visible(fr)) {
                    l.add(new Bundle.Obs(c, p, t.x(fr) - cx, t.y(fr) - cy));
                    break;
                }
        }
        return l;
    }

    /**
     * Reubica una cámara cuyo hilo se perdió: compara las huellas de los
     * puntos visibles en ese cuadro con las de los puntos 3D ya conocidos,
     * estima la pose con RANSAC y, si cuadra, une las pistas nuevas a sus
     * puntos (desde ahí el seguimiento continúa normalmente).
     */
    static Geometry.Pose relocalize(List<Track> tracks, int frame, Map<Track, Integer> ptOf, List<double[]> pts,
                                    List<List<Track>> ptTrack, double f, double cx, double cy, Random rnd) {
        List<long[]> md = new ArrayList<>();
        List<Integer> mp = new ArrayList<>();
        for (int p = 0; p < ptTrack.size(); p++)
            for (Track t : ptTrack.get(p))
                for (long[] d : t.desc) { md.add(d); mp.add(p); }
        List<Track> qt = new ArrayList<>();
        List<Integer> qp = new ArrayList<>();
        for (Track t : tracks) {
            if (!t.visible(frame) || ptOf.containsKey(t)) continue;
            long[] d = t.descNear(frame, 8);
            if (d == null) continue;
            int best = 999, second = 999, bp = -1;
            for (int i = 0; i < md.size(); i++) {
                int dist = Brief.distance(d, md.get(i));
                if (dist < best) {
                    if (mp.get(i) != bp) second = best;
                    best = dist;
                    bp = mp.get(i);
                } else if (dist < second && mp.get(i) != bp) second = dist;
            }
            if (bp >= 0 && best <= 64 && best < 0.8 * second) { qt.add(t); qp.add(bp); }
        }
        if (LOG != null) LOG.append("  reubicar cuadro ").append(frame).append(": ").append(qt.size()).append(" coincidencias");
        if (qt.size() < 12) { if (LOG != null) LOG.append(" (pocas)\n"); return null; }
        int n = qt.size();
        double[][] xs = new double[n][], ob = new double[n][];
        for (int k = 0; k < n; k++) {
            xs[k] = pts.get(qp.get(k));
            ob[k] = new double[]{(qt.get(k).x(frame) - cx) / f, (qt.get(k).y(frame) - cy) / f};
        }
        Geometry.Pose best = null;
        int bestIn = 0;
        double thr = 5.0 / f;
        int[] i6 = new int[6], i4 = new int[4];
        for (int it = 0; it < 600; it++) {
            // Alternar: 4 puntos con homografía (sirve para la pared) y 6 con DLT (puntos en 3D)
            int[] idx = it % 2 == 0 ? i4 : i6;
            for (int k = 0; k < idx.length; k++) {
                int c;
                boolean dup;
                do {
                    c = rnd.nextInt(n);
                    dup = false;
                    for (int q = 0; q < k; q++) dup |= idx[q] == c;
                } while (dup);
                idx[k] = c;
            }
            Geometry.Pose p = idx.length == 4 ? Geometry.planarPose(xs, ob, idx) : Geometry.dlt(xs, ob, idx);
            if (p == null) continue;
            int in = 0;
            for (int k = 0; k < n; k++) {
                double[] xc = p.apply(xs[k]);
                if (xc[2] > 0 && Math.hypot(xc[0] / xc[2] - ob[k][0], xc[1] / xc[2] - ob[k][1]) < thr) in++;
            }
            if (in > bestIn) { bestIn = in; best = p; }
        }
        if (best == null || bestIn < 12) { if (LOG != null) LOG.append(" inliers ").append(bestIn).append('\n'); return null; }
        List<double[]> x2 = new ArrayList<>(), o2 = new ArrayList<>();
        for (int k = 0; k < n; k++) {
            double[] xc = best.apply(xs[k]);
            if (xc[2] > 0 && Math.hypot(xc[0] / xc[2] - ob[k][0], xc[1] / xc[2] - ob[k][1]) < thr) { x2.add(xs[k]); o2.add(ob[k]); }
        }
        Geometry.refinePose(best, x2.toArray(new double[0][]), o2.toArray(new double[0][]), f, 2, 15);
        int linked = 0;
        for (int k = 0; k < n; k++) {
            double[] xc = best.apply(xs[k]);
            if (xc[2] > 0 && f * Math.hypot(xc[0] / xc[2] - ob[k][0], xc[1] / xc[2] - ob[k][1]) < 4) {
                ptOf.put(qt.get(k), qp.get(k));
                ptTrack.get(qp.get(k)).add(qt.get(k));
                linked++;
            }
        }
        if (LOG != null) LOG.append(" → reubicado con ").append(linked).append('\n');
        return linked >= 12 ? best : null;
    }
}
