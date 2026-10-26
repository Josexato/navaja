package com.josexato.navaja.measure.core;

import java.util.Arrays;

/** Casco convexo 2D (cadena monótona) y prueba de punto dentro. */
final class Hull {

    private Hull() { }

    static double[][] convex(double[][] pts) {
        double[][] p = pts.clone();
        Arrays.sort(p, (a, b) -> a[0] != b[0] ? Double.compare(a[0], b[0]) : Double.compare(a[1], b[1]));
        int n = p.length, k = 0;
        if (n < 3) return p;
        double[][] h = new double[2 * n][];
        for (int i = 0; i < n; i++) {
            while (k >= 2 && cross(h[k - 2], h[k - 1], p[i]) <= 0) k--;
            h[k++] = p[i];
        }
        for (int i = n - 2, t = k + 1; i >= 0; i--) {
            while (k >= t && cross(h[k - 2], h[k - 1], p[i]) <= 0) k--;
            h[k++] = p[i];
        }
        double[][] out = new double[k - 1][];
        for (int i = 0; i < k - 1; i++) out[i] = h[i].clone();
        return out;
    }

    private static double cross(double[] o, double[] a, double[] b) {
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
    }

    /** Punto dentro de un polígono convexo en sentido antihorario. */
    static boolean contains(double[][] hull, double x, double y) {
        if (hull.length < 3) return false;
        double[] q = {x, y};
        for (int i = 0; i < hull.length; i++) if (cross(hull[i], hull[(i + 1) % hull.length], q) < 0) return false;
        return true;
    }
}
