package com.josexato.navaja.measure.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Geometría de vistas: matriz esencial entre dos vistas (8 puntos + RANSAC),
 * su descomposición en giro y desplazamiento, triangulación y ubicación de
 * una cámara a partir de puntos 3D conocidos. Las cámaras son
 * {R, t} con Xcam = R·X + t; las observaciones van en coordenadas
 * normalizadas ((u − cx)/f, (v − cy)/f).
 */
public final class Geometry {

    private Geometry() { }

    public static final class Pose {
        public double[] r = {1, 0, 0, 0, 1, 0, 0, 0, 1};
        public double[] t = {0, 0, 0};

        public Pose copy() {
            Pose p = new Pose();
            p.r = r.clone();
            p.t = t.clone();
            return p;
        }

        public double[] apply(double[] x) { return Lin.add(Lin.mulV(r, x), t); }

        /** Centro de la cámara en el mundo: −Rᵀ·t. */
        public double[] center() { return Lin.scale(Lin.mulV(Lin.tr(r), t), -1); }
    }

    /** E con x2ᵀ·E·x1 = 0, por 8 puntos (sin forzar aún la forma esencial). */
    static double[] eightPoint(double[][] a, double[][] b, int[] idx) {
        double[][] ata = new double[9][9];
        for (int k : idx) {
            double x1 = a[k][0], y1 = a[k][1], x2 = b[k][0], y2 = b[k][1];
            double[] row = {x2 * x1, x2 * y1, x2, y2 * x1, y2 * y1, y2, x1, y1, 1};
            for (int i = 0; i < 9; i++) for (int j = 0; j < 9; j++) ata[i][j] += row[i] * row[j];
        }
        double[] e = Lin.nullVector(ata);
        Object[] s = Lin.svd3(e);
        double[] u = (double[]) s[0], v = (double[]) s[2];
        double[] d = {1, 0, 0, 0, 1, 0, 0, 0, 0};
        return Lin.mul(Lin.mul(u, d), Lin.tr(v));
    }

    /** Distancia de Sampson (al cuadrado) de un par a la restricción epipolar. */
    static double sampson(double[] e, double[] p1, double[] p2) {
        double[] x1 = {p1[0], p1[1], 1}, x2 = {p2[0], p2[1], 1};
        double[] ex1 = Lin.mulV(e, x1), etx2 = Lin.mulV(Lin.tr(e), x2);
        double num = Lin.dot(x2, ex1);
        double den = ex1[0] * ex1[0] + ex1[1] * ex1[1] + etx2[0] * etx2[0] + etx2[1] * etx2[1];
        return num * num / Math.max(den, 1e-30);
    }

    public static final class TwoView {
        public Pose pose;            // de la vista 1 a la vista 2
        public boolean[] inlier;
        public int inliers;
        public double medianAngleDeg;
    }

    /**
     * Movimiento relativo entre dos vistas. {@code thr} en unidades
     * normalizadas (píxeles / f). Con una pared plana muchas muestras de 8
     * puntos son degeneradas: RANSAC se queda con la hipótesis que explica
     * también los puntos fuera del plano, porque es la que más inliers tiene.
     */
    public static TwoView relativePose(double[][] a, double[][] b, double thr, int iters, Random rnd) {
        int n = a.length;
        if (n < 12) return null;
        double[] bestE = null;
        int bestCount = -1;
        double thr2 = thr * thr;
        int[] idx = new int[8];
        for (int it = 0; it < iters; it++) {
            for (int k = 0; k < 8; k++) {
                int c;
                boolean dup;
                do {
                    c = rnd.nextInt(n);
                    dup = false;
                    for (int q = 0; q < k; q++) dup |= idx[q] == c;
                } while (dup);
                idx[k] = c;
            }
            double[] e = eightPoint(a, b, idx);
            int count = 0;
            for (int k = 0; k < n; k++) if (sampson(e, a[k], b[k]) < thr2) count++;
            if (count > bestCount) {
                bestCount = count;
                bestE = e;
            }
        }
        // Reestimación con todos los inliers
        List<Integer> in = new ArrayList<>();
        for (int k = 0; k < n; k++) if (sampson(bestE, a[k], b[k]) < thr2) in.add(k);
        if (in.size() < 12) return null;
        int[] all = new int[in.size()];
        for (int k = 0; k < all.length; k++) all[k] = in.get(k);
        double[] e = eightPoint(a, b, all);
        TwoView tv = decompose(e, a, b, thr2);
        return tv;
    }

    /** Las 4 soluciones (R, ±t) de E; gana la que deja más puntos delante de ambas cámaras. */
    static TwoView decompose(double[] e, double[][] a, double[][] b, double thr2) {
        Object[] s = Lin.svd3(e);
        double[] u = (double[]) s[0], v = (double[]) s[2];
        if (Lin.det(u) < 0) u = Lin.neg(u);
        if (Lin.det(v) < 0) v = Lin.neg(v);
        double[] w = {0, -1, 0, 1, 0, 0, 0, 0, 1};
        double[][] rs = {Lin.mul(Lin.mul(u, w), Lin.tr(v)), Lin.mul(Lin.mul(u, Lin.tr(w)), Lin.tr(v))};
        double[] t = {u[2], u[5], u[8]};
        TwoView best = null;
        Pose p1 = new Pose();
        for (double[] r : rs)
            for (int sg = -1; sg <= 1; sg += 2) {
                Pose p2 = new Pose();
                p2.r = r;
                p2.t = Lin.scale(t, sg);
                boolean[] ok = new boolean[a.length];
                int count = 0;
                List<Double> angles = new ArrayList<>();
                for (int k = 0; k < a.length; k++) {
                    if (sampson(e, a[k], b[k]) >= thr2) continue;
                    double[] x = triangulate(new Pose[]{p1, p2}, new double[][]{a[k], b[k]});
                    if (x == null) continue;
                    double z1 = p1.apply(x)[2], z2 = p2.apply(x)[2];
                    if (z1 > 0 && z2 > 0) {
                        ok[k] = true;
                        count++;
                        angles.add(angleDeg(x, p1.center(), p2.center()));
                    }
                }
                if (best == null || count > best.inliers) {
                    best = new TwoView();
                    best.pose = p2;
                    best.inlier = ok;
                    best.inliers = count;
                    angles.sort(null);
                    best.medianAngleDeg = angles.isEmpty() ? 0 : angles.get(angles.size() / 2);
                }
            }
        return best;
    }

    /** Ángulo (grados) entre los rayos desde dos centros de cámara a un punto. */
    public static double angleDeg(double[] x, double[] c1, double[] c2) {
        double[] a = Lin.sub(x, c1), b = Lin.sub(x, c2);
        double c = Lin.dot(a, b) / (Lin.norm(a) * Lin.norm(b));
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, c))));
    }

    /** Triangulación lineal (DLT) desde 2 o más vistas. */
    public static double[] triangulate(Pose[] poses, double[][] obs) {
        double[][] ata = new double[4][4];
        for (int v = 0; v < poses.length; v++) {
            double[] r = poses[v].r, t = poses[v].t;
            double x = obs[v][0], y = obs[v][1];
            double[][] rows = {
                {x * r[6] - r[0], x * r[7] - r[1], x * r[8] - r[2], x * t[2] - t[0]},
                {y * r[6] - r[3], y * r[7] - r[4], y * r[8] - r[5], y * t[2] - t[1]}};
            for (double[] row : rows) for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) ata[i][j] += row[i] * row[j];
        }
        double[] h = Lin.nullVector(ata);
        if (Math.abs(h[3]) < 1e-12) return null;
        return new double[]{h[0] / h[3], h[1] / h[3], h[2] / h[3]};
    }

    /**
     * Ajusta la pose de una cámara a puntos 3D conocidos (Gauss-Newton con
     * pérdida de Huber), partiendo de {@code init} — en un video, la pose
     * del cuadro anterior. Devuelve los residuos finales en píxeles.
     */
    public static double[] refinePose(Pose pose, double[][] xs, double[][] obs, double f, double huberPx, int iters) {
        int n = xs.length;
        double[] res = new double[n];
        for (int it = 0; it < iters; it++) {
            double[][] h = new double[6][6];
            double[] g = new double[6];
            for (int k = 0; k < n; k++) {
                double[] xc = pose.apply(xs[k]);
                if (xc[2] <= 1e-9) continue;
                double iz = 1 / xc[2], u = xc[0] * iz, v = xc[1] * iz;
                double ru = f * (u - obs[k][0]), rv = f * (v - obs[k][1]);
                double e = Math.hypot(ru, rv), w = e <= huberPx ? 1 : huberPx / e;
                // d(u,v)/dXc
                double[] du = {f * iz, 0, -f * u * iz}, dv = {0, f * iz, -f * v * iz};
                // dXc/dω = −[Xc − t]×  (perturbación por izquierda: R ← exp(ω)·R)
                double[] q = Lin.sub(xc, pose.t);
                double[][] jw = {{0, q[2], -q[1]}, {-q[2], 0, q[0]}, {q[1], -q[0], 0}};
                double[] ju = new double[6], jv = new double[6];
                for (int c = 0; c < 3; c++) {
                    ju[c] = du[0] * jw[0][c] + du[1] * jw[1][c] + du[2] * jw[2][c];
                    jv[c] = dv[0] * jw[0][c] + dv[1] * jw[1][c] + dv[2] * jw[2][c];
                    ju[3 + c] = du[c];
                    jv[3 + c] = dv[c];
                }
                for (int i = 0; i < 6; i++) {
                    g[i] += w * (ju[i] * ru + jv[i] * rv);
                    for (int j = 0; j < 6; j++) h[i][j] += w * (ju[i] * ju[j] + jv[i] * jv[j]);
                }
            }
            for (int i = 0; i < 6; i++) h[i][i] *= 1.0001;
            for (int i = 0; i < 6; i++) h[i][i] += 1e-9;
            for (int i = 0; i < 6; i++) g[i] = -g[i];
            double[] d = Lin.cholSolve(h, g);
            if (d == null) break;
            pose.r = Lin.mul(Lin.rodrigues(new double[]{d[0], d[1], d[2]}), pose.r);
            pose.t = Lin.add(pose.t, new double[]{d[3], d[4], d[5]});
            if (d[0] * d[0] + d[1] * d[1] + d[2] * d[2] + d[3] * d[3] + d[4] * d[4] + d[5] * d[5] < 1e-14) break;
        }
        for (int k = 0; k < n; k++) {
            double[] xc = pose.apply(xs[k]);
            res[k] = xc[2] <= 0 ? 1e9 : f * Math.hypot(xc[0] / xc[2] - obs[k][0], xc[1] / xc[2] - obs[k][1]);
        }
        return res;
    }

    /**
     * Pose de una cámara desde 6+ correspondencias 3D-2D sin pose inicial
     * (DLT de la matriz de proyección en coordenadas normalizadas). Con
     * puntos todos en un plano es degenerado: RANSAC lo descarta solo, porque
     * esas hipótesis explican pocos puntos fuera del plano.
     */
    public static Pose dlt(double[][] xs, double[][] obs, int[] idx) {
        double[] c = new double[3];
        for (int k : idx) c = Lin.add(c, xs[k]);
        c = Lin.scale(c, 1.0 / idx.length);
        double sc = 0;
        for (int k : idx) sc += Lin.norm(Lin.sub(xs[k], c));
        sc = sc / idx.length;
        if (sc < 1e-12) return null;
        double[][] ata = new double[12][12];
        for (int k : idx) {
            double[] q = Lin.scale(Lin.sub(xs[k], c), 1 / sc);
            double x = obs[k][0], y = obs[k][1];
            double[][] rows = {
                {q[0], q[1], q[2], 1, 0, 0, 0, 0, -x * q[0], -x * q[1], -x * q[2], -x},
                {0, 0, 0, 0, q[0], q[1], q[2], 1, -y * q[0], -y * q[1], -y * q[2], -y}};
            for (double[] r : rows) for (int i = 0; i < 12; i++) for (int j = 0; j < 12; j++) ata[i][j] += r[i] * r[j];
        }
        double[] m = Lin.nullVector(ata);
        double[] rr = {m[0], m[1], m[2], m[4], m[5], m[6], m[8], m[9], m[10]};
        double[] tt = {m[3], m[7], m[11]};
        // Cámara delante de los puntos: si no, cambiar el signo de toda la matriz
        int front = 0;
        for (int k : idx) {
            double[] q = Lin.scale(Lin.sub(xs[k], c), 1 / sc);
            if (Lin.dot(new double[]{rr[6], rr[7], rr[8]}, q) + tt[2] > 0) front++;
        }
        if (front < idx.length / 2.0) { rr = Lin.neg(rr); tt = Lin.neg(tt); }
        Object[] sv = Lin.svd3(rr);
        double[] u = (double[]) sv[0], s = (double[]) sv[1], v = (double[]) sv[2];
        double[] r = Lin.mul(u, Lin.tr(v));
        if (Lin.det(r) < 0) return null;
        double scale = (s[0] + s[1] + s[2]) / 3;
        if (scale < 1e-12) return null;
        // Xc ∝ R·(X − c)/sc + t'/scale  →  t = sc·t'/scale − R·c
        Pose p = new Pose();
        p.r = r;
        p.t = Lin.sub(Lin.scale(tt, sc / scale), Lin.mulV(r, c));
        return p;
    }

    /**
     * Pose desde 4 puntos casi coplanares (la pared): homografía entre el
     * plano y la imagen, descompuesta con la cámara calibrada. Es el caso
     * que el DLT general no resuelve.
     */
    public static Pose planarPose(double[][] xs, double[][] obs, int[] idx) {
        double[] c = new double[3];
        for (int k : idx) c = Lin.add(c, xs[k]);
        c = Lin.scale(c, 1.0 / idx.length);
        double[][] cov = new double[3][3];
        for (int k : idx) {
            double[] d = Lin.sub(xs[k], c);
            for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) cov[i][j] += d[i] * d[j];
        }
        double[][] ev = (double[][]) Lin.eigSym(cov)[1];
        double[] e1 = ev[2], e2 = ev[1], nrm = Lin.cross(e1, e2);
        double[][] ata = new double[9][9];
        for (int k : idx) {
            double[] d = Lin.sub(xs[k], c);
            double a = Lin.dot(d, e1), b = Lin.dot(d, e2), x = obs[k][0], y = obs[k][1];
            double[][] rows = {{a, b, 1, 0, 0, 0, -x * a, -x * b, -x}, {0, 0, 0, a, b, 1, -y * a, -y * b, -y}};
            for (double[] r : rows) for (int i = 0; i < 9; i++) for (int j = 0; j < 9; j++) ata[i][j] += r[i] * r[j];
        }
        double[] h = Lin.nullVector(ata);
        double[] h1 = {h[0], h[3], h[6]}, h2 = {h[1], h[4], h[7]}, h3 = {h[2], h[5], h[8]};
        double lam = (Lin.norm(h1) + Lin.norm(h2)) / 2;
        if (lam < 1e-12) return null;
        if (h3[2] < 0) lam = -lam; // el plano debe quedar delante de la cámara
        double[] r1 = Lin.scale(h1, 1 / lam), r2 = Lin.scale(h2, 1 / lam), r3 = Lin.cross(r1, r2);
        double[] rp = {r1[0], r2[0], r3[0], r1[1], r2[1], r3[1], r1[2], r2[2], r3[2]};
        Object[] sv = Lin.svd3(rp);
        rp = Lin.mul((double[]) sv[0], Lin.tr((double[]) sv[2]));
        if (Lin.det(rp) < 0) return null;
        double[] tp = Lin.scale(h3, 1 / lam);
        // Xc = Rp·[a, b, 0] + t' con [a, b, n] = Bᵀ(X − c)  →  R = Rp·Bᵀ, t = t' − R·c
        double[] bt = {e1[0], e1[1], e1[2], e2[0], e2[1], e2[2], nrm[0], nrm[1], nrm[2]};
        Pose p = new Pose();
        p.r = Lin.mul(rp, bt);
        p.t = Lin.sub(tp, Lin.mulV(p.r, c));
        return p;
    }
}
