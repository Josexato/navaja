package com.josexato.navaja.scan3d.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Simplificación de mallas por colapso de aristas con error cuadrático
 * (Garland y Heckbert, 1997). Cada vértice acumula los planos de sus
 * triángulos; colapsar una arista cuesta la suma de las distancias² del
 * punto nuevo a esos planos. Se colapsa siempre la arista más barata, así
 * que las zonas planas se juntan en pocas caras grandes y las curvas
 * conservan más triángulos. No colapsa una arista si eso dejaría la malla
 * no-variedad (dos caras pegadas por un borde de más) o daría vuelta un
 * triángulo.
 */
public final class Decimate {

    private Decimate() { }

    /** Un triángulo no puede girar más que esto al colapsar (coseno mínimo entre la normal vieja y la nueva). */
    static final double MIN_COS = 0.2;

    /** Nueva malla con a lo sumo {@code target} triángulos (o los que se pudo); sin color ni normales. */
    public static Mesh simplify(Mesh m, int target) {
        int nv = m.vertexCount(), nt = m.triangleCount();
        double[] p = new double[nv * 3];
        for (int i = 0; i < p.length; i++) p[i] = m.pos[i];
        int[] tri = m.tri.clone();
        boolean[] deadT = new boolean[nt];
        boolean[] deadV = new boolean[nv];
        int[] ver = new int[nv];
        // Triángulos de cada vértice
        List<List<Integer>> vt = new ArrayList<>(nv);
        for (int v = 0; v < nv; v++) vt.add(new ArrayList<>(8));
        for (int t = 0; t < nt; t++) for (int e = 0; e < 3; e++) vt.get(tri[3 * t + e]).add(t);
        // Cuádricas (10 coeficientes de la matriz simétrica 4×4), ponderadas por área
        double[][] q = new double[nv][10];
        for (int t = 0; t < nt; t++) {
            double[] pl = plane(p, tri[3 * t], tri[3 * t + 1], tri[3 * t + 2]);
            if (pl == null) continue;
            for (int e = 0; e < 3; e++) addPlane(q[tri[3 * t + e]], pl, pl[4]);
        }
        PriorityQueue<double[]> heap = new PriorityQueue<>((x, y) -> Double.compare(x[0], y[0]));
        for (int t = 0; t < nt; t++)
            for (int e = 0; e < 3; e++) {
                int a = tri[3 * t + e], b = tri[3 * t + (e + 1) % 3];
                if (a < b) push(heap, q, p, a, b, ver);
            }
        int alive = nt;
        while (alive > target && !heap.isEmpty()) {
            double[] h = heap.poll();
            int a = (int) h[1], b = (int) h[2];
            if (deadV[a] || deadV[b] || ver[a] != (int) h[3] || ver[b] != (int) h[4]) continue;
            double[] x = {h[5], h[6], h[7]};
            if (!linkOk(a, b, vt, tri, deadT) || flips(a, b, x, vt, tri, deadT, p) || flips(b, a, x, vt, tri, deadT, p)) continue;
            // Colapsar b en a
            for (int t : vt.get(b)) {
                if (deadT[t]) continue;
                boolean hasA = false;
                for (int e = 0; e < 3; e++) hasA |= tri[3 * t + e] == a;
                if (hasA) {
                    deadT[t] = true;
                    alive--;
                } else {
                    for (int e = 0; e < 3; e++) if (tri[3 * t + e] == b) tri[3 * t + e] = a;
                    vt.get(a).add(t);
                }
            }
            deadV[b] = true;
            vt.get(b).clear();
            vt.get(a).removeIf(t -> deadT[t]);
            System.arraycopy(x, 0, p, 3 * a, 3);
            for (int k = 0; k < 10; k++) q[a][k] += q[b][k];
            ver[a]++;
            // Aristas nuevas alrededor de a
            List<Integer> nb = neighbors(a, vt, tri, deadT);
            for (int c : nb) push(heap, q, p, Math.min(a, c), Math.max(a, c), ver);
        }
        // Compactar
        int[] map = new int[nv];
        Arrays.fill(map, -1);
        List<Integer> keepT = new ArrayList<>();
        int n = 0;
        for (int t = 0; t < nt; t++) {
            if (deadT[t]) continue;
            keepT.add(t);
            for (int e = 0; e < 3; e++) if (map[tri[3 * t + e]] < 0) map[tri[3 * t + e]] = n++;
        }
        Mesh out = new Mesh();
        out.pos = new float[3 * n];
        for (int v = 0; v < nv; v++) if (map[v] >= 0) for (int c = 0; c < 3; c++) out.pos[3 * map[v] + c] = (float) p[3 * v + c];
        out.tri = new int[3 * keepT.size()];
        for (int k = 0; k < keepT.size(); k++) for (int e = 0; e < 3; e++) out.tri[3 * k + e] = map[tri[3 * keepT.get(k) + e]];
        return out;
    }

    /** Arista (a, b) a la cola: {costo, a, b, versión a, versión b, x, y, z}. */
    private static void push(PriorityQueue<double[]> heap, double[][] q, double[] p, int a, int b, int[] ver) {
        double[] s = new double[10];
        for (int k = 0; k < 10; k++) s[k] = q[a][k] + q[b][k];
        double[] x = optimal(s, p, a, b);
        heap.add(new double[]{error(s, x), a, b, ver[a], ver[b], x[0], x[1], x[2]});
    }

    /**
     * El punto de menor error: resuelve el sistema 3×3 de la cuádrica; si
     * está mal condicionado (zona plana), el mejor entre los extremos y el medio.
     */
    static double[] optimal(double[] s, double[] p, int a, int b) {
        // s = {a11 a12 a13 a14 a22 a23 a24 a33 a34 a44}
        double a11 = s[0], a12 = s[1], a13 = s[2], a22 = s[4], a23 = s[5], a33 = s[7];
        double det = a11 * (a22 * a33 - a23 * a23) - a12 * (a12 * a33 - a23 * a13) + a13 * (a12 * a23 - a22 * a13);
        double scale = Math.abs(a11) + Math.abs(a22) + Math.abs(a33);
        double[] pa = {p[3 * a], p[3 * a + 1], p[3 * a + 2]}, pb = {p[3 * b], p[3 * b + 1], p[3 * b + 2]};
        if (Math.abs(det) > 1e-9 * scale * scale * scale) {
            double b1 = -s[3], b2 = -s[6], b3 = -s[8];
            double x = (b1 * (a22 * a33 - a23 * a23) - a12 * (b2 * a33 - a23 * b3) + a13 * (b2 * a23 - a22 * b3)) / det;
            double y = (a11 * (b2 * a33 - a23 * b3) - b1 * (a12 * a33 - a23 * a13) + a13 * (a12 * b3 - b2 * a13)) / det;
            double z = (a11 * (a22 * b3 - b2 * a23) - a12 * (a12 * b3 - b2 * a13) + b1 * (a12 * a23 - a22 * a13)) / det;
            double[] v = {x, y, z};
            // Lejos de la arista (sistema casi singular): no confiar
            double len = dist(pa, pb), da = dist(v, pa), db = dist(v, pb);
            if (Math.min(da, db) <= 2 * len + 1e-12) return v;
        }
        double[] mid = {(pa[0] + pb[0]) / 2, (pa[1] + pb[1]) / 2, (pa[2] + pb[2]) / 2};
        double ea = error(s, pa), eb = error(s, pb), em = error(s, mid);
        return em <= ea && em <= eb ? mid : ea <= eb ? pa : pb;
    }

    static double error(double[] s, double[] v) {
        double x = v[0], y = v[1], z = v[2];
        return s[0] * x * x + 2 * s[1] * x * y + 2 * s[2] * x * z + 2 * s[3] * x
                + s[4] * y * y + 2 * s[5] * y * z + 2 * s[6] * y
                + s[7] * z * z + 2 * s[8] * z + s[9];
    }

    /** Plano {a, b, c, d, área} del triángulo (normal unitaria), o null si es degenerado. */
    private static double[] plane(double[] p, int i, int j, int k) {
        double ux = p[3 * j] - p[3 * i], uy = p[3 * j + 1] - p[3 * i + 1], uz = p[3 * j + 2] - p[3 * i + 2];
        double vx = p[3 * k] - p[3 * i], vy = p[3 * k + 1] - p[3 * i + 1], vz = p[3 * k + 2] - p[3 * i + 2];
        double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
        double l = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (l < 1e-20) return null;
        nx /= l;
        ny /= l;
        nz /= l;
        return new double[]{nx, ny, nz, -(nx * p[3 * i] + ny * p[3 * i + 1] + nz * p[3 * i + 2]), l / 2};
    }

    private static void addPlane(double[] q, double[] pl, double w) {
        double a = pl[0], b = pl[1], c = pl[2], d = pl[3];
        q[0] += w * a * a; q[1] += w * a * b; q[2] += w * a * c; q[3] += w * a * d;
        q[4] += w * b * b; q[5] += w * b * c; q[6] += w * b * d;
        q[7] += w * c * c; q[8] += w * c * d; q[9] += w * d * d;
    }

    /** Vértices vecinos de {@code v} (por sus triángulos vivos). */
    private static List<Integer> neighbors(int v, List<List<Integer>> vt, int[] tri, boolean[] deadT) {
        List<Integer> out = new ArrayList<>();
        for (int t : vt.get(v)) {
            if (deadT[t]) continue;
            for (int e = 0; e < 3; e++) {
                int c = tri[3 * t + e];
                if (c != v && !out.contains(c)) out.add(c);
            }
        }
        return out;
    }

    /**
     * Condición de enlace: los vecinos comunes de a y b deben ser exactamente
     * los vértices opuestos de los triángulos de la arista (2 en una malla
     * cerrada); si no, el colapso pega dos partes de la superficie.
     */
    private static boolean linkOk(int a, int b, List<List<Integer>> vt, int[] tri, boolean[] deadT) {
        List<Integer> na = neighbors(a, vt, tri, deadT), nb = neighbors(b, vt, tri, deadT);
        int common = 0;
        for (int c : na) if (nb.contains(c)) common++;
        int shared = 0;
        for (int t : vt.get(a)) {
            if (deadT[t]) continue;
            for (int e = 0; e < 3; e++) if (tri[3 * t + e] == b) shared++;
        }
        return shared >= 1 && common == shared;
    }

    /** ¿Mover {@code from} a {@code x} da vuelta (o aplasta) algún triángulo suyo que no desaparece? */
    private static boolean flips(int from, int other, double[] x, List<List<Integer>> vt, int[] tri, boolean[] deadT, double[] p) {
        for (int t : vt.get(from)) {
            if (deadT[t]) continue;
            int i = tri[3 * t], j = tri[3 * t + 1], k = tri[3 * t + 2];
            if (i == other || j == other || k == other) continue;
            double[] before = normal(p, i, j, k, -1, null);
            double[] after = normal(p, i, j, k, from, x);
            if (before == null) continue;
            if (after == null) return true;
            if (before[0] * after[0] + before[1] * after[1] + before[2] * after[2] < MIN_COS) return true;
        }
        return false;
    }

    private static double[] normal(double[] p, int i, int j, int k, int moved, double[] x) {
        double[] a = at(p, i, moved, x), b = at(p, j, moved, x), c = at(p, k, moved, x);
        double ux = b[0] - a[0], uy = b[1] - a[1], uz = b[2] - a[2], vx = c[0] - a[0], vy = c[1] - a[1], vz = c[2] - a[2];
        double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
        double l = Math.sqrt(nx * nx + ny * ny + nz * nz);
        return l < 1e-20 ? null : new double[]{nx / l, ny / l, nz / l};
    }

    private static double[] at(double[] p, int v, int moved, double[] x) {
        return v == moved ? x : new double[]{p[3 * v], p[3 * v + 1], p[3 * v + 2]};
    }

    private static double dist(double[] a, double[] b) {
        return Math.sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2]));
    }
}
