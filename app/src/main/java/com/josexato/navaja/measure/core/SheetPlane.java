package com.josexato.navaja.measure.core;

/**
 * La hoja (y la mesa donde está) es plana: Z = 0 en el sistema de la hoja.
 * Un punto que está sobre ese plano se ubica mejor cortando sus rayos con
 * el plano (2 incógnitas: x, y) que cruzándolos entre sí (3 incógnitas):
 * así no tiene error de altura y la hoja queda plana en la nube.
 *
 * <p>Se decide si un punto es del plano por lo que ven las fotos: si al
 * dejarlo en Z = 0 su error de reproyección sigue siendo bajo (≤
 * {@link #MAX_REPROJ} px en todas las vistas) y triangulado libre quedó
 * cerca (|Z| ≤ {@link #BAND} mm), es del plano. Un punto del objeto a unos
 * milímetros de altura no pasa: forzado al plano, sus rayos ya no calzan.
 */
public final class SheetPlane {

    private SheetPlane() { }

    /** Altura (mm) hasta la que un punto triangulado libre puede ser del plano, y error máximo (px) al dejarlo en Z = 0. */
    static final double BAND = 15, MAX_REPROJ = 2;

    /**
     * El punto (x, y, 0) que mejor explica las observaciones {@code ob}
     * (coordenadas normalizadas) desde las cámaras {@code ps}, por mínimos
     * cuadrados lineales; null si el sistema es degenerado.
     */
    public static double[] onPlane(Geometry.Pose[] ps, double[][] ob) {
        double a00 = 0, a01 = 0, a11 = 0, b0 = 0, b1 = 0;
        for (int v = 0; v < ps.length; v++) {
            double[] r = ps[v].r, t = ps[v].t;
            for (int k = 0; k < 2; k++) {
                // obs·(r2·X + t2) = rk·X + tk, con X = (x, y, 0)
                double o = ob[v][k];
                double c0 = o * r[6] - r[3 * k], c1 = o * r[7] - r[3 * k + 1], rhs = t[k] - o * t[2];
                a00 += c0 * c0;
                a01 += c0 * c1;
                a11 += c1 * c1;
                b0 += c0 * rhs;
                b1 += c1 * rhs;
            }
        }
        double det = a00 * a11 - a01 * a01;
        if (Math.abs(det) < 1e-18 * Math.max(1, a00 * a11)) return null;
        return new double[]{(a11 * b0 - a01 * b1) / det, (a00 * b1 - a01 * b0) / det, 0};
    }

    /** Peor error de reproyección (px) del punto {@code x}; infinito si queda detrás de alguna cámara. */
    public static double worst(Geometry.Pose[] ps, double[][] ob, double[] x, double f) {
        double w = 0;
        for (int v = 0; v < ps.length; v++) {
            double[] xc = ps[v].apply(x);
            if (xc[2] <= 0) return Double.POSITIVE_INFINITY;
            w = Math.max(w, f * Math.hypot(xc[0] / xc[2] - ob[v][0], xc[1] / xc[2] - ob[v][1]));
        }
        return w;
    }

    /**
     * Si el punto triangulado libre {@code free} es del plano, su versión en
     * Z = 0; si no, null.
     */
    public static double[] snap(Geometry.Pose[] ps, double[][] ob, double[] free, double f) {
        if (free == null || Math.abs(free[2]) > BAND) return null;
        double[] p = onPlane(ps, ob);
        if (p == null || worst(ps, ob, p, f) > MAX_REPROJ) return null;
        return p;
    }
}
