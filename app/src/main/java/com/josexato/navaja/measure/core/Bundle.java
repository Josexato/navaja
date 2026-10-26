package com.josexato.navaja.measure.core;

import java.util.List;

/**
 * Ajuste de haces (bundle adjustment): afina a la vez las poses de las
 * cámaras, los puntos 3D y la distancia focal, minimizando el error de
 * reproyección en píxeles (Levenberg-Marquardt con pérdida de Huber). Los
 * puntos se eliminan con el complemento de Schur, así el sistema que se
 * resuelve sólo tiene 6 incógnitas por cámara + 1 de la focal.
 * La primera cámara queda fija (define el sistema de coordenadas).
 */
public final class Bundle {

    private Bundle() { }

    /** Observación: cámara, punto y coordenadas en píxeles relativas al centro de la imagen. */
    public static final class Obs {
        public final int cam, pt;
        public final double u, v;

        public Obs(int cam, int pt, double u, double v) {
            this.cam = cam;
            this.pt = pt;
            this.u = u;
            this.v = v;
        }
    }

    public static final class Result {
        public double rmsBefore, rmsAfter;
        public int iterations;
    }

    /**
     * @param poses   cámaras (se modifican)
     * @param pts     puntos 3D (se modifican)
     * @param f       focal en píxeles, f[0] (se modifica si {@code refineFocal})
     * @param byPoint observaciones agrupadas por punto
     */
    public static Result run(List<Geometry.Pose> poses, double[][] pts, double[] f, List<List<Obs>> byPoint, double huber,
                             int maxIter, boolean refineFocal) {
        int nc = poses.size(), np = pts.length;
        int nv = 6 * (nc - 1) + (refineFocal ? 1 : 0); // cámara 0 fija
        int fi = 6 * (nc - 1);
        Result res = new Result();
        double lambda = 1e-3;
        double cost = cost(poses, pts, f[0], byPoint, huber);
        res.rmsBefore = rms(poses, pts, f[0], byPoint);
        for (int it = 0; it < maxIter; it++) {
            res.iterations = it + 1;
            double[][] s = new double[nv][nv];
            double[] b = new double[nv];
            double[][] vinv = new double[np][];
            double[][] ep = new double[np][];
            // Por punto: bloques W (6×3 por cámara, 1×3 de la focal) para la reducción de Schur
            double[][][] wBlocks = new double[np][][];
            int[][] wIndex = new int[np][];
            for (int p = 0; p < np; p++) {
                List<Obs> obs = byPoint.get(p);
                if (obs == null || obs.size() < 2) continue;
                double[] v = new double[9];
                double[] e = new double[3];
                double[][] wb = new double[obs.size() + 1][];
                int[] wi = new int[obs.size() + 1];
                double[] wf = new double[3];
                double[] uff = new double[1];
                for (int k = 0; k < obs.size(); k++) {
                    Obs o = obs.get(k);
                    Geometry.Pose pose = poses.get(o.cam);
                    double[] xc = pose.apply(pts[p]);
                    if (xc[2] <= 1e-9) { wi[k] = -2; continue; }
                    double iz = 1 / xc[2], x = xc[0] * iz, y = xc[1] * iz, ff = f[0];
                    double ru = ff * x - o.u, rv = ff * y - o.v;
                    double err = Math.hypot(ru, rv), w = err <= huber ? 1 : huber / err;
                    double[] du = {ff * iz, 0, -ff * x * iz}, dv = {0, ff * iz, -ff * y * iz};
                    double[] r = pose.r;
                    // dXc/dX = R
                    double[] jpu = new double[3], jpv = new double[3];
                    for (int c = 0; c < 3; c++) {
                        jpu[c] = du[0] * r[c] + du[1] * r[3 + c] + du[2] * r[6 + c];
                        jpv[c] = dv[0] * r[c] + dv[1] * r[3 + c] + dv[2] * r[6 + c];
                    }
                    for (int i = 0; i < 3; i++) {
                        e[i] += w * (jpu[i] * ru + jpv[i] * rv);
                        for (int j = 0; j < 3; j++) v[3 * i + j] += w * (jpu[i] * jpu[j] + jpv[i] * jpv[j]);
                    }
                    double jfu = x, jfv = y;
                    if (refineFocal) {
                        for (int i = 0; i < 3; i++) wf[i] += w * (jfu * jpu[i] + jfv * jpv[i]);
                        uff[0] += w * (jfu * jfu + jfv * jfv);
                        b[fi] -= w * (jfu * ru + jfv * rv);
                        s[fi][fi] += w * (jfu * jfu + jfv * jfv);
                    }
                    if (o.cam == 0) { wi[k] = -2; continue; }
                    double[] q = Lin.sub(xc, pose.t);
                    double[][] jw = {{0, q[2], -q[1]}, {-q[2], 0, q[0]}, {q[1], -q[0], 0}};
                    double[] jcu = new double[6], jcv = new double[6];
                    for (int c = 0; c < 3; c++) {
                        jcu[c] = du[0] * jw[0][c] + du[1] * jw[1][c] + du[2] * jw[2][c];
                        jcv[c] = dv[0] * jw[0][c] + dv[1] * jw[1][c] + dv[2] * jw[2][c];
                        jcu[3 + c] = du[c];
                        jcv[3 + c] = dv[c];
                    }
                    int base = 6 * (o.cam - 1);
                    for (int i = 0; i < 6; i++) {
                        b[base + i] -= w * (jcu[i] * ru + jcv[i] * rv);
                        for (int j = 0; j < 6; j++) s[base + i][base + j] += w * (jcu[i] * jcu[j] + jcv[i] * jcv[j]);
                        if (refineFocal) {
                            double cf = w * (jcu[i] * jfu + jcv[i] * jfv);
                            s[base + i][fi] += cf;
                            s[fi][base + i] += cf;
                        }
                    }
                    double[] wc = new double[18];
                    for (int i = 0; i < 6; i++)
                        for (int j = 0; j < 3; j++) wc[3 * i + j] = w * (jcu[i] * jpu[j] + jcv[i] * jpv[j]);
                    wb[k] = wc;
                    wi[k] = base;
                }
                if (refineFocal) {
                    wb[obs.size()] = wf;
                    wi[obs.size()] = fi;
                } else wi[obs.size()] = -2;
                for (int i = 0; i < 3; i++) v[4 * i] += lambda * v[4 * i] + 1e-12;
                double[] vi = Lin.inv3(v);
                if (vi == null) continue;
                vinv[p] = vi;
                ep[p] = e;
                wBlocks[p] = wb;
                wIndex[p] = wi;
            }
            for (int i = 0; i < nv; i++) s[i][i] += lambda * s[i][i] + 1e-9;
            // Reducción de Schur: S −= W·V⁻¹·Wᵀ ; b −= −W·V⁻¹·e  (b ya tiene −Jᵀr de cámaras)
            for (int p = 0; p < np; p++) {
                if (vinv[p] == null) continue;
                double[][] wb = wBlocks[p];
                int[] wi = wIndex[p];
                double[] vi = vinv[p];
                // WV⁻¹ para cada bloque
                double[][] wv = new double[wb.length][];
                for (int k = 0; k < wb.length; k++) {
                    if (wi[k] < 0) continue;
                    int rows = wb[k].length / 3; // 6 = cámara, 1 = focal
                    double[] m = new double[rows * 3];
                    for (int i = 0; i < rows; i++)
                        for (int j = 0; j < 3; j++)
                            m[3 * i + j] = wb[k][3 * i] * vi[j] + wb[k][3 * i + 1] * vi[3 + j] + wb[k][3 * i + 2] * vi[6 + j];
                    wv[k] = m;
                    // b_c += W V⁻¹ e_p   (porque b_p = −e_p)
                    for (int i = 0; i < rows; i++)
                        b[wi[k] + i] += m[3 * i] * ep[p][0] + m[3 * i + 1] * ep[p][1] + m[3 * i + 2] * ep[p][2];
                }
                for (int k = 0; k < wb.length; k++) {
                    if (wv[k] == null) continue;
                    int rk = wv[k].length / 3;
                    for (int l = 0; l < wb.length; l++) {
                        if (wb[l] == null || wi[l] < 0) continue;
                        int rl = wb[l].length / 3;
                        for (int i = 0; i < rk; i++)
                            for (int j = 0; j < rl; j++)
                                s[wi[k] + i][wi[l] + j] -= wv[k][3 * i] * wb[l][3 * j] + wv[k][3 * i + 1] * wb[l][3 * j + 1]
                                        + wv[k][3 * i + 2] * wb[l][3 * j + 2];
                    }
                }
            }
            double[] dc = nv == 0 ? new double[0] : Lin.cholSolve(s, b);
            if (dc == null) { lambda *= 10; continue; }
            // Puntos: dp = V⁻¹(−e − Wᵀ dc)
            double[][] dp = new double[np][];
            for (int p = 0; p < np; p++) {
                if (vinv[p] == null) continue;
                double[] rhs = {-ep[p][0], -ep[p][1], -ep[p][2]};
                double[][] wb = wBlocks[p];
                int[] wi = wIndex[p];
                for (int k = 0; k < wb.length; k++) {
                    if (wb[k] == null || wi[k] < 0) continue;
                    int rows = wb[k].length / 3;
                    for (int i = 0; i < rows; i++)
                        for (int j = 0; j < 3; j++) rhs[j] -= wb[k][3 * i + j] * dc[wi[k] + i];
                }
                dp[p] = Lin.mulV(vinv[p], rhs);
            }
            // Aplicar y evaluar
            Geometry.Pose[] oldPoses = new Geometry.Pose[nc];
            for (int c = 0; c < nc; c++) oldPoses[c] = poses.get(c).copy();
            double[][] oldPts = new double[np][];
            for (int p = 0; p < np; p++) oldPts[p] = pts[p].clone();
            double oldF = f[0];
            for (int c = 1; c < nc; c++) {
                int base = 6 * (c - 1);
                Geometry.Pose ps = poses.get(c);
                ps.r = Lin.mul(Lin.rodrigues(new double[]{dc[base], dc[base + 1], dc[base + 2]}), ps.r);
                ps.t = Lin.add(ps.t, new double[]{dc[base + 3], dc[base + 4], dc[base + 5]});
            }
            if (refineFocal) f[0] += dc[fi];
            for (int p = 0; p < np; p++) if (dp[p] != null) pts[p] = Lin.add(pts[p], dp[p]);
            double nc2 = cost(poses, pts, f[0], byPoint, huber);
            if (nc2 < cost) {
                boolean done = (cost - nc2) / cost < 1e-6;
                cost = nc2;
                lambda = Math.max(lambda / 3, 1e-7);
                if (done) break;
            } else {
                for (int c = 0; c < nc; c++) poses.set(c, oldPoses[c]);
                for (int p = 0; p < np; p++) pts[p] = oldPts[p];
                f[0] = oldF;
                lambda *= 5;
                if (lambda > 1e6) break;
            }
        }
        res.rmsAfter = rms(poses, pts, f[0], byPoint);
        return res;
    }

    static double cost(List<Geometry.Pose> poses, double[][] pts, double f, List<List<Obs>> byPoint, double huber) {
        double c = 0;
        for (int p = 0; p < pts.length; p++) {
            List<Obs> obs = byPoint.get(p);
            if (obs == null || obs.size() < 2) continue;
            for (Obs o : obs) {
                double e = reproj(poses.get(o.cam), pts[p], f, o);
                c += e <= huber ? e * e : 2 * huber * e - huber * huber;
            }
        }
        return c;
    }

    public static double rms(List<Geometry.Pose> poses, double[][] pts, double f, List<List<Obs>> byPoint) {
        double s = 0;
        int n = 0;
        for (int p = 0; p < pts.length; p++) {
            List<Obs> obs = byPoint.get(p);
            if (obs == null || obs.size() < 2) continue;
            for (Obs o : obs) {
                double e = reproj(poses.get(o.cam), pts[p], f, o);
                if (e < 1e8) { s += e * e; n++; }
            }
        }
        return n == 0 ? 0 : Math.sqrt(s / n);
    }

    /** Error de reproyección en píxeles. */
    public static double reproj(Geometry.Pose pose, double[] x, double f, Obs o) {
        double[] xc = pose.apply(x);
        if (xc[2] <= 1e-9) return 1e9;
        return Math.hypot(f * xc[0] / xc[2] - o.u, f * xc[1] / xc[2] - o.v);
    }
}
