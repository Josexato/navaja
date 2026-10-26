package com.josexato.navaja.measure.core;

/**
 * Álgebra lineal mínima para la reconstrucción: matrices 3×3 como double[9]
 * (fila mayor), autovalores de matrices simétricas (Jacobi), SVD 3×3,
 * sistemas lineales (Cholesky con amortiguación) y rotaciones (Rodrigues).
 */
public final class Lin {

    private Lin() { }

    // ------------------------------------------------------------ 3×3 y vectores

    public static double[] mul(double[] a, double[] b) {
        double[] c = new double[9];
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++) c[3 * i + j] = a[3 * i] * b[j] + a[3 * i + 1] * b[3 + j] + a[3 * i + 2] * b[6 + j];
        return c;
    }

    public static double[] mulV(double[] a, double[] v) {
        return new double[]{a[0] * v[0] + a[1] * v[1] + a[2] * v[2], a[3] * v[0] + a[4] * v[1] + a[5] * v[2],
            a[6] * v[0] + a[7] * v[1] + a[8] * v[2]};
    }

    public static double[] tr(double[] a) {
        return new double[]{a[0], a[3], a[6], a[1], a[4], a[7], a[2], a[5], a[8]};
    }

    public static double det(double[] a) {
        return a[0] * (a[4] * a[8] - a[5] * a[7]) - a[1] * (a[3] * a[8] - a[5] * a[6]) + a[2] * (a[3] * a[7] - a[4] * a[6]);
    }

    public static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    public static double dot(double[] a, double[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }

    public static double norm(double[] a) { return Math.sqrt(dot(a, a)); }

    public static double[] sub(double[] a, double[] b) { return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]}; }

    public static double[] add(double[] a, double[] b) { return new double[]{a[0] + b[0], a[1] + b[1], a[2] + b[2]}; }

    public static double[] scale(double[] a, double s) { return new double[]{a[0] * s, a[1] * s, a[2] * s}; }

    public static double[] unit(double[] a) { return scale(a, 1 / norm(a)); }

    /** Cambia el signo de todos los elementos (vector o matriz). */
    public static double[] neg(double[] a) {
        double[] b = new double[a.length];
        for (int i = 0; i < a.length; i++) b[i] = -a[i];
        return b;
    }

    // ------------------------------------------------------------ rotaciones

    /** Vector de rotación (eje·ángulo) → matriz. */
    public static double[] rodrigues(double[] w) {
        double th = norm(w);
        if (th < 1e-12) return new double[]{1, -w[2], w[1], w[2], 1, -w[0], -w[1], w[0], 1};
        double x = w[0] / th, y = w[1] / th, z = w[2] / th, c = Math.cos(th), s = Math.sin(th), v = 1 - c;
        return new double[]{
            c + x * x * v, x * y * v - z * s, x * z * v + y * s,
            y * x * v + z * s, c + y * y * v, y * z * v - x * s,
            z * x * v - y * s, z * y * v + x * s, c + z * z * v};
    }

    /** Matriz de rotación → vector de rotación. */
    public static double[] logRot(double[] r) {
        double c = Math.max(-1, Math.min(1, (r[0] + r[4] + r[8] - 1) / 2)), th = Math.acos(c);
        if (th < 1e-9) return new double[]{(r[7] - r[5]) / 2, (r[2] - r[6]) / 2, (r[3] - r[1]) / 2};
        if (Math.PI - th < 1e-6) {
            // Cerca de 180°: eje desde la diagonal
            double[] axis = new double[]{Math.sqrt(Math.max(0, (r[0] + 1) / 2)), Math.sqrt(Math.max(0, (r[4] + 1) / 2)),
                Math.sqrt(Math.max(0, (r[8] + 1) / 2))};
            if (r[1] < 0) axis[1] = -axis[1];
            if (r[2] < 0) axis[2] = -axis[2];
            return scale(unit(axis), th);
        }
        double k = th / (2 * Math.sin(th));
        return new double[]{(r[7] - r[5]) * k, (r[2] - r[6]) * k, (r[3] - r[1]) * k};
    }

    // ------------------------------------------------------------ autovalores y SVD

    /**
     * Autovalores y autovectores de una matriz simétrica n×n (Jacobi cíclico).
     * Devuelve {valores[n], vectores[n][n]} ordenados de MENOR a mayor; vectores[k] es el k-ésimo.
     */
    public static Object[] eigSym(double[][] a0) {
        int n = a0.length;
        double[][] a = new double[n][];
        for (int i = 0; i < n; i++) a[i] = a0[i].clone();
        double[][] v = new double[n][n];
        for (int i = 0; i < n; i++) v[i][i] = 1;
        for (int sweep = 0; sweep < 100; sweep++) {
            double off = 0;
            for (int i = 0; i < n; i++) for (int j = i + 1; j < n; j++) off += a[i][j] * a[i][j];
            if (off < 1e-30) break;
            for (int p = 0; p < n; p++)
                for (int q = p + 1; q < n; q++) {
                    if (Math.abs(a[p][q]) < 1e-300) continue;
                    double theta = (a[q][q] - a[p][p]) / (2 * a[p][q]);
                    double t = Math.signum(theta) / (Math.abs(theta) + Math.sqrt(theta * theta + 1));
                    if (theta == 0) t = 1;
                    double c = 1 / Math.sqrt(t * t + 1), s = t * c;
                    for (int k = 0; k < n; k++) {
                        double akp = a[k][p], akq = a[k][q];
                        a[k][p] = c * akp - s * akq;
                        a[k][q] = s * akp + c * akq;
                    }
                    for (int k = 0; k < n; k++) {
                        double apk = a[p][k], aqk = a[q][k];
                        a[p][k] = c * apk - s * aqk;
                        a[q][k] = s * apk + c * aqk;
                    }
                    for (int k = 0; k < n; k++) {
                        double vkp = v[k][p], vkq = v[k][q];
                        v[k][p] = c * vkp - s * vkq;
                        v[k][q] = s * vkp + c * vkq;
                    }
                }
        }
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        java.util.Arrays.sort(idx, (x, y) -> Double.compare(a[x][x], a[y][y]));
        double[] vals = new double[n];
        double[][] vecs = new double[n][n];
        for (int k = 0; k < n; k++) {
            vals[k] = a[idx[k]][idx[k]];
            for (int i = 0; i < n; i++) vecs[k][i] = v[i][idx[k]];
        }
        return new Object[]{vals, vecs};
    }

    /** Vector que minimiza |A x| con |x| = 1 (autovector menor de AᵀA). */
    public static double[] nullVector(double[][] ata) {
        return ((double[][]) eigSym(ata)[1])[0];
    }

    /** SVD de 3×3: devuelve {U, s[3], V} con A = U·diag(s)·Vᵀ, s de MAYOR a menor. */
    public static Object[] svd3(double[] a) {
        double[] ata = mul(tr(a), a);
        Object[] e = eigSym(new double[][]{{ata[0], ata[1], ata[2]}, {ata[3], ata[4], ata[5]}, {ata[6], ata[7], ata[8]}});
        double[] vals = (double[]) e[0];
        double[][] vecs = (double[][]) e[1];
        double[] s = new double[3];
        double[][] vcol = new double[3][], ucol = new double[3][];
        for (int k = 0; k < 3; k++) {
            vcol[k] = vecs[2 - k];
            s[k] = Math.sqrt(Math.max(0, vals[2 - k]));
        }
        for (int k = 0; k < 2; k++) {
            double[] av = mulV(a, vcol[k]);
            double nn = norm(av);
            ucol[k] = nn > 1e-15 ? scale(av, 1 / nn) : new double[]{k == 0 ? 1 : 0, k == 1 ? 1 : 0, 0};
        }
        ucol[2] = cross(ucol[0], ucol[1]);
        double[] av3 = mulV(a, vcol[2]);
        if (dot(av3, ucol[2]) < 0 && s[2] > 1e-12) { ucol[2] = scale(ucol[2], -1); }
        double[] u = new double[9], v = new double[9];
        for (int i = 0; i < 3; i++)
            for (int k = 0; k < 3; k++) {
                u[3 * i + k] = ucol[k][i];
                v[3 * i + k] = vcol[k][i];
            }
        return new Object[]{u, s, v};
    }

    // ------------------------------------------------------------ sistemas lineales

    /** Resuelve A x = b (A simétrica definida positiva) por Cholesky; null si no es definida positiva. */
    public static double[] cholSolve(double[][] a, double[] b) {
        int n = b.length;
        double[][] l = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j <= i; j++) {
                double s = a[i][j];
                double[] li = l[i], lj = l[j];
                for (int k = 0; k < j; k++) s -= li[k] * lj[k];
                if (i == j) {
                    if (s <= 0) return null;
                    li[i] = Math.sqrt(s);
                } else li[j] = s / lj[j];
            }
        }
        double[] y = new double[n];
        for (int i = 0; i < n; i++) {
            double s = b[i];
            for (int k = 0; k < i; k++) s -= l[i][k] * y[k];
            y[i] = s / l[i][i];
        }
        double[] x = new double[n];
        for (int i = n - 1; i >= 0; i--) {
            double s = y[i];
            for (int k = i + 1; k < n; k++) s -= l[k][i] * x[k];
            x[i] = s / l[i][i];
        }
        return x;
    }

    /** Inversa de una 3×3 simétrica (bloque de punto en el ajuste); null si es singular. */
    public static double[] inv3(double[] a) {
        double d = det(a);
        if (Math.abs(d) < 1e-300) return null;
        return new double[]{
            (a[4] * a[8] - a[5] * a[7]) / d, (a[2] * a[7] - a[1] * a[8]) / d, (a[1] * a[5] - a[2] * a[4]) / d,
            (a[5] * a[6] - a[3] * a[8]) / d, (a[0] * a[8] - a[2] * a[6]) / d, (a[2] * a[3] - a[0] * a[5]) / d,
            (a[3] * a[7] - a[4] * a[6]) / d, (a[1] * a[6] - a[0] * a[7]) / d, (a[0] * a[4] - a[1] * a[3]) / d};
    }
}
