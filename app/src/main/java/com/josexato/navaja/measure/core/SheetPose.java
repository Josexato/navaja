package com.josexato.navaja.measure.core;

import java.util.List;

/**
 * Pose de la cámara en un cuadro desde las marcas de la hoja: las esquinas
 * de las marcas tienen posición conocida en mm (todas en Z = 0), así que la
 * homografía hoja → imagen da la pose y un ajuste fino (Gauss-Newton con
 * Huber) la deja con el menor error de reproyección. La pose queda en mm,
 * en el sistema de la hoja (Z hacia arriba).
 */
public final class SheetPose {

    private SheetPose() { }

    /** Error de las esquinas (px) por encima del cual el cuadro se descarta (marca mal leída, imagen movida). */
    public static final double MAX_RMS = 2.5;

    /** Una pose: la cámara, el error medio de las esquinas (px) y cuántas marcas se usaron. */
    public static final class Fix {
        public Geometry.Pose pose;
        public double rms;
        public int markers;
    }

    /** La pose con las marcas {@code ms} y el lente {@code l}; null con menos de 2 marcas o si no converge. */
    public static Fix solve(List<Markers.Marker> ms, Lens l) {
        if (ms.size() < 2) return null;
        int n = 4 * ms.size();
        double[][] xs = new double[n][], obs = new double[n][];
        int[] idx = new int[n];
        int k = 0;
        for (Markers.Marker m : ms) {
            double[][] c3 = Sheet.corners(m.id);
            for (int c = 0; c < 4; c++) {
                xs[k] = c3[c];
                obs[k] = l.normalize(m.corners[c][0], m.corners[c][1]);
                idx[k] = k;
                k++;
            }
        }
        Geometry.Pose p = Geometry.planarPose(xs, obs, idx);
        if (p == null) return null;
        double[] res = Geometry.refinePose(p, xs, obs, l.f, 2, 10);
        double s = 0;
        for (double v : res) s += v * v;
        // La hoja debe quedar delante de la cámara
        for (double[] x : xs) if (p.apply(x)[2] <= 0) return null;
        Fix fx = new Fix();
        fx.pose = p;
        fx.rms = Math.sqrt(s / res.length);
        fx.markers = ms.size();
        return fx;
    }
}
