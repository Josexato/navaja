package com.josexato.navaja.measure.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Cierre de bucle: cuando el video se corta en tramos, reconocer en un tramo
 * lugares que ya se vieron en otro y unirlos. Cada {@link Sfm#LOOP_EVERY}
 * cuadros se guardan puntos multiescala ({@link Features}); los del tramo
 * principal se asocian a sus puntos 3D, y cada cuadro del otro tramo se
 * ubica contra ellos (correspondencias 2D–3D por huella + PnP con RANSAC).
 * Con 2 o más cuadros ubicados, sus posiciones dan el giro, la escala y el
 * desplazamiento entre tramos, y se ajusta todo junto. Sirve cuando la
 * cámara vuelve a vistas parecidas (una vuelta completa, volver al inicio);
 * no puede unir vistas que nunca se repiten.
 */
public final class Loop {

    private Loop() { }

    /** Distancia máxima (de 256 bits) entre huellas del mismo punto, y razón contra la segunda mejor. */
    static final int MAX_DIST = 64;
    static final double RATIO = 0.8;
    /** Correspondencias confirmadas que necesita un cuadro para darlo por ubicado. */
    static final int MIN_PNP = 15;
    /** Cuadros del otro tramo que se intentan ubicar (repartidos en el tiempo). */
    static final int MAX_FRAMES = 12;
    /** Intentos de RANSAC: entre lugares reconocidos de otro tramo, sólo ~1 de 4 coincidencias es correcta. */
    static final int PNP_ITERS = 6000;
    /** Radio para asociar un punto multiescala a la pista de un punto 3D: max(ASSOC_MIN px, ASSOC_K × su escala). */
    public static double ASSOC_MIN = 2.0, ASSOC_K = 0.6;
    /** Error de reproyección (px) para confirmar una correspondencia del cuadro ubicado. */
    public static double CONFIRM_PX = 5;
    /** Coherencia entre cuadros ubicados: giro (°) y posición (fracción de la distancia a la escena). */
    static final double MAX_TURN_DEG = 5, MAX_POS = 0.1;
    /** Ángulo máximo entre los planos de apoyo de los dos tramos ya alineados. */
    static final double MAX_PLANE_DEG = 10;

    /** Para el informe: cada intento de unión y su resultado. */
    public static StringBuilder LOG = null;

    /**
     * Une al tramo más grande (el de más cámaras) todos los que se pueda,
     * repitiendo mientras alguna unión funcione. Devuelve el tramo unido
     * primero y los que no se pudieron unir después (por tiempo).
     */
    public static List<Sfm.Result> close(List<Sfm.Result> segs, List<Sfm.Track> tracks, List<Features.Set> feats, Sfm.Progress pr, Random rnd) {
        List<Sfm.Result> rest = new ArrayList<>(segs);
        rest.sort((a, b) -> Integer.compare(b.poses.size(), a.poses.size()));
        Sfm.Result main = rest.remove(0);
        boolean changed = true;
        while (changed && !rest.isEmpty()) {
            changed = false;
            for (int i = 0; i < rest.size(); i++) {
                if (pr != null) pr.step("Uniendo tramos (cierre de bucle)", segs.size() - rest.size(), segs.size());
                Sfm.Result m = join(main, rest.get(i), tracks, feats, rnd);
                if (m != null) {
                    main = m;
                    rest.remove(i);
                    changed = true;
                    break;
                }
            }
        }
        List<Sfm.Result> out = new ArrayList<>();
        out.add(main);
        rest.sort((a, b) -> Integer.compare(a.startFrame, b.startFrame));
        out.addAll(rest);
        return out;
    }

    /** ¿El cuadro cae en alguno de los tramos originales que forman {@code r}? */
    static boolean covers(Sfm.Result r, int frame) {
        for (int[] p : r.parts) if (frame >= p[0] && frame <= p[1]) return true;
        return false;
    }

    /** Cuadros del tramo principal que se comparan con cada cuadro del otro (los más parecidos). */
    static final int TOP_A = 3;

    /** Une {@code b} a {@code a} si se reconocen sus lugares; null si no. */
    public static Sfm.Result join(Sfm.Result a, Sfm.Result b, List<Sfm.Track> tracks, List<Features.Set> feats, Random rnd) {
        String tag = String.format(Locale.ROOT, "%s con %s", span(a), span(b));
        List<Features.Set> fa = new ArrayList<>(), fb = new ArrayList<>();
        for (Features.Set fs : feats) {
            if (covers(a, fs.frame) && poseAt(a, fs.frame) != null) fa.add(fs);
            if (covers(b, fs.frame) && poseAt(b, fs.frame) != null) fb.add(fs);
        }
        if (fa.isEmpty() || fb.isEmpty()) return null;
        int[][] assocA = new int[fa.size()][];
        double[][][] posA = new double[fa.size()][][];
        for (int k = 0; k < fa.size(); k++) {
            assocA[k] = associate(a, fa.get(k));
            posA[k] = depthFromNeighbors(a, fa.get(k));
        }
        int step = Math.max(1, (fb.size() + MAX_FRAMES - 1) / MAX_FRAMES);
        double f = a.focal, cx = a.width / 2.0, cy = a.height / 2.0;
        List<Geometry.Pose> inA = new ArrayList<>(), inB = new ArrayList<>();
        int[] votes = new int[b.points.length];
        Arrays.fill(votes, -1);
        StringBuilder detail = new StringBuilder();
        for (int c = 0; c < fb.size(); c += step) {
            Features.Set q = fb.get(c);
            int[] bAssoc = associate(b, q);
            // 1) De foto a foto: coincidencias con cada cuadro de a; los más parecidos, verificados con geometría de dos vistas
            int[][] m = new int[fa.size()][];
            Integer[] order = new Integer[fa.size()];
            int guided = 0;
            int[] plain = new int[fa.size()];
            for (int k = 0; k < fa.size(); k++) {
                m[k] = matchSets(q, fa.get(k));
                order[k] = k;
                plain[k] = count(m[k]);
                // Si las regiones de color de los dos cuadros calzan, dicen dónde debería caer cada punto: se buscan
                // también coincidencias cerca de ese lugar (la prueba de razón sólo contra los vecinos de ahí)
                Regions.Match rm = Regions.match(q.regions, fa.get(k).regions);
                if (rm != null) {
                    guided++;
                    int[] g = matchGuided(q, fa.get(k), rm, GUIDE_R * fa.get(k).regions.size);
                    // Sólo donde no había coincidencia: las regiones agregan candidatas, no reemplazan
                    for (int i = 0; i < g.length; i++) if (m[k][i] < 0 && g[i] >= 0) m[k][i] = g[i];
                }
            }
            // Orden por las coincidencias sin guía: una región mal emparejada no debe desplazar a los cuadros parecidos
            Arrays.sort(order, (x, y) -> Integer.compare(plain[y], plain[x]));
            List<double[]> xs = new ArrayList<>(), ob = new ArrayList<>();
            List<int[]> pair = new ArrayList<>();
            int verified = 0;
            for (int t = 0; t < Math.min(TOP_A, order.length); t++) {
                int k = order[t];
                Features.Set s = fa.get(k);
                List<double[]> pb = new ArrayList<>(), pa = new ArrayList<>();
                List<Integer> qi = new ArrayList<>();
                for (int i = 0; i < q.size(); i++) {
                    if (m[k][i] < 0) continue;
                    pb.add(new double[]{(q.x[i] - cx) / f, (q.y[i] - cy) / f});
                    pa.add(new double[]{(s.x[m[k][i]] - cx) / f, (s.y[m[k][i]] - cy) / f});
                    qi.add(i);
                }
                if (pb.size() < 12) continue;
                Geometry.TwoView tv = Geometry.relativePose(pa.toArray(new double[0][]), pb.toArray(new double[0][]), 2.0 / f, 1500, rnd);
                if (tv == null || tv.inlier == null) continue;
                for (int j = 0; j < qi.size(); j++) {
                    if (!tv.inlier[j]) continue;
                    int i = qi.get(j), ap = assocA[k][m[k][i]];
                    verified++;
                    // 2) Las verificadas, a 3D: el punto de a de esa pista, o la profundidad de sus vecinos
                    double[] X3 = ap >= 0 ? a.points[ap] : posA[k][m[k][i]];
                    if (X3 == null) continue;
                    xs.add(X3);
                    ob.add(new double[]{(q.x[i] - cx) / f, (q.y[i] - cy) / f});
                    pair.add(new int[]{bAssoc[i], ap});
                }
            }
            detail.append(' ').append(q.frame).append(guided > 0 ? "[reg " + guided + "]" : "").append(':').append(verified).append('/').append(xs.size());
            if (xs.size() < MIN_PNP) continue;
            double[][] X = xs.toArray(new double[0][]), O = ob.toArray(new double[0][]);
            Geometry.Pose p = Sfm.pnpRansac(X, O, f, rnd, PNP_ITERS);
            if (p == null) continue;
            List<double[]> x2 = new ArrayList<>(), o2 = new ArrayList<>();
            for (int k = 0; k < X.length; k++) {
                double[] xc = p.apply(X[k]);
                if (xc[2] > 0 && f * Math.hypot(xc[0] / xc[2] - O[k][0], xc[1] / xc[2] - O[k][1]) < CONFIRM_PX) { x2.add(X[k]); o2.add(O[k]); }
            }
            if (x2.size() >= 6) Geometry.refinePose(p, x2.toArray(new double[0][]), o2.toArray(new double[0][]), f, 2, 15);
            int in = 0;
            List<int[]> good = new ArrayList<>();
            for (int k = 0; k < X.length; k++) {
                double[] xc = p.apply(X[k]);
                if (xc[2] > 0 && f * Math.hypot(xc[0] / xc[2] - O[k][0], xc[1] / xc[2] - O[k][1]) < CONFIRM_PX) { in++; good.add(pair.get(k)); }
            }
            detail.append('/').append(in);
            if (in < MIN_PNP) continue;
            inA.add(p);
            inB.add(poseAt(b, q.frame));
            for (int[] g : good) if (g[0] >= 0) votes[g[0]] = g[1];
        }
        String det = " (cuadro:verificadas/con 3D/ubicada" + detail + ")";
        if (inA.size() < 2) {
            log(tag + ": cuadros ubicados " + inA.size() + det);
            return null;
        }
        // Coherencia: los cuadros ubicados deben dar el mismo giro entre tramos y calzar con una sola similitud (una
        // ubicación espejada o errada pasa el ajuste conjunto si pocos puntos unen los tramos). Se usa el mayor
        // grupo coherente, de al menos 2 cuadros.
        double dist = 0;
        for (Geometry.Pose pa : inA) dist += Lin.norm(pa.center()) / inA.size();
        List<Integer> bestSet = new ArrayList<>();
        double[] sim = null;
        String coh = "";
        for (int i = 0; i < inA.size(); i++)
            for (int j = i + 1; j < inA.size(); j++) {
                double[] sij = similarityFromCameras(Arrays.asList(inA.get(i), inA.get(j)), Arrays.asList(inB.get(i), inB.get(j)));
                if (sij == null) continue;
                List<Integer> set = new ArrayList<>();
                for (int k = 0; k < inA.size(); k++) if (coherent(sij, inA.get(k), inB.get(k), dist)) set.add(k);
                if (set.size() > bestSet.size()) bestSet = set;
            }
        if (bestSet.size() >= 2) {
            List<Geometry.Pose> ga = new ArrayList<>(), gb = new ArrayList<>();
            for (int k : bestSet) { ga.add(inA.get(k)); gb.add(inB.get(k)); }
            sim = similarityFromCameras(ga, gb);
            if (sim != null) for (int k : bestSet) if (!coherent(sim, inA.get(k), inB.get(k), dist)) { sim = null; break; }
        }
        if (sim == null) {
            log(tag + ": " + inA.size() + " cuadros ubicados pero no coinciden entre sí" + det);
            return null;
        }
        coh = String.format(Locale.ROOT, " [coherentes %d de %d]", bestSet.size(), inA.size());
        // Los dos tramos ven el mismo plano de apoyo: alineados, sus normales deben coincidir (si no, uno está deformado)
        Measure.Box ba = Measure.measure(a), bb = Measure.measure(b);
        if (ba != null && bb != null) {
            double[] nb = Lin.mulV(Arrays.copyOfRange(sim, 1, 10), bb.planeN);
            double ang = Math.toDegrees(Math.acos(Math.min(1, Math.abs(Lin.dot(nb, ba.planeN)))));
            coh += String.format(Locale.ROOT, " [planos a %.0f°]", ang);
            if (ang > MAX_PLANE_DEG) {
                log(tag + ": " + inA.size() + " cuadros ubicados pero los planos de apoyo no coinciden: un tramo está deformado" + coh + det);
                return null;
            }
        }
        Sfm.Result res = Sfm.join(a, b, tracks, votes, sim);
        log(tag + ": " + inA.size() + " cuadros ubicados" + coh + det + " → "
                + (res == null ? "el ajuste conjunto no cuadra" : String.format(Locale.ROOT, "UNIDOS (%d cámaras, %.2f px)", res.poses.size(), res.rms)));
        return res;
    }

    /** Para cada punto de {@code q}, el índice del más parecido en {@code s} (prueba de razón), o −1. */
    static int[] matchSets(Features.Set q, Features.Set s) {
        int[] out = new int[q.size()];
        for (int i = 0; i < q.size(); i++) {
            long[] d = q.desc[i];
            int b1 = 999, b2 = 999, bj = -1;
            for (int j = 0; j < s.size(); j++) {
                long[] e = s.desc[j];
                int dist = Long.bitCount(d[0] ^ e[0]) + Long.bitCount(d[1] ^ e[1]) + Long.bitCount(d[2] ^ e[2]) + Long.bitCount(d[3] ^ e[3]);
                if (dist < b1) { b2 = b1; b1 = dist; bj = j; } else if (dist < b2) b2 = dist;
            }
            out[i] = bj >= 0 && b1 <= MAX_DIST && b1 < RATIO * b2 ? bj : -1;
        }
        return out;
    }

    /** Radio de búsqueda guiada por regiones, en fracción del tamaño de la silueta. */
    static final double GUIDE_R = 0.2;
    /** Distancia máxima de huella en la búsqueda guiada: más exigente, porque la prueba de razón es sólo local. */
    static final int GUIDE_DIST = 40;

    /**
     * Como {@link #matchSets}, pero cada punto de {@code q} sólo se compara con
     * los de {@code s} a menos de {@code radius} px de donde lo pone {@code rm}.
     */
    static int[] matchGuided(Features.Set q, Features.Set s, Regions.Match rm, double radius) {
        int[] out = new int[q.size()];
        double r2 = radius * radius;
        for (int i = 0; i < q.size(); i++) {
            double[] at = rm.map(q.x[i], q.y[i]);
            long[] d = q.desc[i];
            int b1 = 999, b2 = 999, bj = -1;
            for (int j = 0; j < s.size(); j++) {
                double dx = s.x[j] - at[0], dy = s.y[j] - at[1];
                if (dx * dx + dy * dy > r2) continue;
                long[] e = s.desc[j];
                int dist = Long.bitCount(d[0] ^ e[0]) + Long.bitCount(d[1] ^ e[1]) + Long.bitCount(d[2] ^ e[2]) + Long.bitCount(d[3] ^ e[3]);
                if (dist < b1) { b2 = b1; b1 = dist; bj = j; } else if (dist < b2) b2 = dist;
            }
            out[i] = bj >= 0 && b1 <= GUIDE_DIST && b1 < RATIO * b2 ? bj : -1;
        }
        return out;
    }

    /** Cuadros de distancia máxima al corte para comparar sus regiones. */
    static final int CUT_REACH = 3 * Sfm.LOOP_EVERY;

    /**
     * Giro y escala del objeto en la imagen a través de un corte (de lo último
     * antes de {@code endFrame} a lo primero desde {@code startFrame}), según
     * sus regiones de color. Null si no hay regiones cerca del corte o no calzan.
     */
    public static Regions.Match acrossCut(List<Features.Set> feats, int endFrame, int startFrame) {
        Features.Set before = null, after = null;
        for (Features.Set fs : feats) {
            if (fs.regions == null) continue;
            if (fs.frame <= endFrame && fs.frame >= endFrame - CUT_REACH && (before == null || fs.frame > before.frame)) before = fs;
            if (fs.frame >= startFrame && fs.frame <= startFrame + CUT_REACH && (after == null || fs.frame < after.frame)) after = fs;
        }
        return before == null || after == null ? null : Regions.match(before.regions, after.regions);
    }

    private static int count(int[] m) {
        int n = 0;
        for (int v : m) if (v >= 0) n++;
        return n;
    }

    /** ¿La cámara ubicada en a coincide con la de b llevada por {@code sim}? (giro y posición) */
    static boolean coherent(double[] sim, Geometry.Pose pa, Geometry.Pose pb, double dist) {
        double[] rot = Arrays.copyOfRange(sim, 1, 10);
        double[] rk = Lin.mul(Lin.tr(pa.r), pb.r);
        double turn = Math.toDegrees(Lin.norm(Lin.logRot(Lin.mul(rk, Lin.tr(rot)))));
        double pos = Lin.norm(Lin.sub(Geometry.applySim(sim, pb.center()), pa.center()));
        return turn <= MAX_TURN_DEG && pos <= MAX_POS * dist;
    }

    /**
     * Para cada punto multiescala del cuadro, el punto 3D de {@code r} cuya
     * pista pasa a menos de max(2 px; 0,6 × su escala) en ese cuadro (−1 si ninguno):
     * con un radio mayor se colaban puntos vecinos y la ubicación salía imprecisa.
     */
    static int[] associate(Sfm.Result r, Features.Set fs) {
        List<float[]> at = new ArrayList<>(); // {x, y, punto}
        for (int i = 0; i < r.pointTracks.size() && i < r.points.length; i++)
            for (Sfm.Track t : r.pointTracks.get(i))
                if (t.visible(fs.frame)) {
                    at.add(new float[]{t.x(fs.frame), t.y(fs.frame), i});
                    break;
                }
        int[] out = new int[fs.size()];
        Arrays.fill(out, -1);
        for (int k = 0; k < fs.size(); k++) {
            double lim = Math.max(ASSOC_MIN, ASSOC_K * fs.scale[k]), best = lim * lim;
            for (float[] q : at) {
                double dx = q[0] - fs.x[k], dy = q[1] - fs.y[k], d2 = dx * dx + dy * dy;
                if (d2 < best) { best = d2; out[k] = (int) q[2]; }
            }
        }
        return out;
    }

    /** Vecinos (px) y coincidencia de profundidad para estimar el 3D de un punto multiescala. */
    static final double NEIGHBOR_PX = 24, DEPTH_SPREAD = 0.05;

    /**
     * Posición 3D de cada punto multiescala del cuadro según la profundidad
     * de los puntos 3D de {@code r} que se ven a menos de 24 px (al menos 3,
     * y que coincidan en profundidad: superficie suave). Null donde no se puede.
     */
    static double[][] depthFromNeighbors(Sfm.Result r, Features.Set fs) {
        double[][] out = new double[fs.size()][];
        Geometry.Pose p = poseAt(r, fs.frame);
        if (p == null) return out;
        double f = r.focal, cx = r.width / 2.0, cy = r.height / 2.0;
        List<double[]> at = new ArrayList<>(); // {x, y, profundidad}
        for (int i = 0; i < r.pointTracks.size() && i < r.points.length; i++)
            for (Sfm.Track t : r.pointTracks.get(i))
                if (t.visible(fs.frame)) {
                    double z = p.apply(r.points[i])[2];
                    if (z > 0) at.add(new double[]{t.x(fs.frame), t.y(fs.frame), z});
                    break;
                }
        double r2 = NEIGHBOR_PX * NEIGHBOR_PX;
        double[] rt = Lin.tr(p.r);
        for (int k = 0; k < fs.size(); k++) {
            List<Double> zs = new ArrayList<>();
            for (double[] q : at) {
                double dx = q[0] - fs.x[k], dy = q[1] - fs.y[k];
                if (dx * dx + dy * dy < r2) zs.add(q[2]);
            }
            if (zs.size() < 3) continue;
            double[] z = zs.stream().mapToDouble(Double::doubleValue).sorted().toArray();
            double med = z[z.length / 2];
            double[] dev = new double[z.length];
            for (int i = 0; i < z.length; i++) dev[i] = Math.abs(z[i] - med);
            Arrays.sort(dev);
            if (dev[dev.length / 2] > DEPTH_SPREAD * med) continue;
            double[] xc = {(fs.x[k] - cx) / f * med, (fs.y[k] - cy) / f * med, med};
            out[k] = Lin.mulV(rt, Lin.sub(xc, p.t));
        }
        return out;
    }

    /** Pose de la cámara de {@code r} en el cuadro dado, interpolando entre sus cuadros clave (hasta 6 cuadros de distancia). */
    static Geometry.Pose poseAt(Sfm.Result r, int frame) {
        int lo = -1, hi = -1;
        for (int c = 0; c < r.camFrames.size(); c++) {
            int fr = r.camFrames.get(c);
            if (fr <= frame && (lo < 0 || fr > r.camFrames.get(lo))) lo = c;
            if (fr >= frame && (hi < 0 || fr < r.camFrames.get(hi))) hi = c;
        }
        if (lo < 0 && hi < 0) return null;
        if (lo < 0 || hi < 0) {
            int c = lo < 0 ? hi : lo;
            return Math.abs(r.camFrames.get(c) - frame) <= 2 ? r.poses.get(c).copy() : null;
        }
        int f0 = r.camFrames.get(lo), f1 = r.camFrames.get(hi);
        if (f1 - f0 > 12) return null;
        if (f0 == f1) return r.poses.get(lo).copy();
        double al = (double) (frame - f0) / (f1 - f0);
        Geometry.Pose p0 = r.poses.get(lo), p1 = r.poses.get(hi), p = new Geometry.Pose();
        p.r = Lin.mul(Lin.rodrigues(Lin.scale(Lin.logRot(Lin.mul(p1.r, Lin.tr(p0.r))), al)), p0.r);
        double[] c = Lin.add(Lin.scale(p0.center(), 1 - al), Lin.scale(p1.center(), al));
        p.t = Lin.scale(Lin.mulV(p.r, c), -1);
        return p;
    }

    /**
     * Similitud (s, R, t) que lleva el sistema de b al de a, desde cámaras
     * ubicadas en los dos: giro medio y escala/desplazamiento por los centros.
     * Null si los centros están demasiado juntos para dar la escala.
     */
    static double[] similarityFromCameras(List<Geometry.Pose> inA, List<Geometry.Pose> inB) {
        double[] m = new double[9];
        for (int k = 0; k < inA.size(); k++) {
            double[] rk = Lin.mul(Lin.tr(inA.get(k).r), inB.get(k).r); // R tal que R_b = R_a·R
            for (int q = 0; q < 9; q++) m[q] += rk[q];
        }
        Object[] svd = Lin.svd3(m);
        double[] u = (double[]) svd[0], v = (double[]) svd[2];
        double[] rot = Lin.mul(u, Lin.tr(v));
        if (Lin.det(rot) < 0) rot = Lin.mul(Lin.mul(u, new double[]{1, 0, 0, 0, 1, 0, 0, 0, -1}), Lin.tr(v));
        double[] ca = new double[3], cb = new double[3];
        List<double[]> CA = new ArrayList<>(), CB = new ArrayList<>();
        for (int k = 0; k < inA.size(); k++) {
            CA.add(inA.get(k).center());
            CB.add(Lin.mulV(rot, inB.get(k).center()));
            ca = Lin.add(ca, CA.get(k));
            cb = Lin.add(cb, CB.get(k));
        }
        ca = Lin.scale(ca, 1.0 / inA.size());
        cb = Lin.scale(cb, 1.0 / inA.size());
        double num = 0, den = 0;
        for (int k = 0; k < inA.size(); k++) {
            double[] da = Lin.sub(CA.get(k), ca), db = Lin.sub(CB.get(k), cb);
            num += Lin.dot(da, db);
            den += Lin.dot(db, db);
        }
        if (den < 1e-12 || num <= 0) return null;
        double sc = num / den;
        double[] tr = Lin.sub(ca, Lin.scale(cb, sc));
        double[] sim = new double[13];
        sim[0] = sc;
        System.arraycopy(rot, 0, sim, 1, 9);
        System.arraycopy(tr, 0, sim, 10, 3);
        return sim;
    }

    private static String span(Sfm.Result r) {
        StringBuilder s = new StringBuilder();
        for (int[] p : r.parts) s.append(s.length() == 0 ? "" : "+").append(String.format(Locale.ROOT, "%.1f–%.1f s", p[0] / 15.0, p[1] / 15.0));
        return "[" + s + "]";
    }

    private static void log(String s) { if (LOG != null) LOG.append(s).append('\n'); }
}
