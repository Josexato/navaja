package com.josexato.navaja.measure.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Qué tan brusco fue el movimiento del celular: entre fotos (cuadros clave)
 * consecutivas, cuánto tiempo pasó, cuántos puntos 3D siguen a la vista y
 * cuánto giró y se desplazó la cámara; y, cuadro a cuadro, cuántos puntos se
 * perdieron. Una caída fuerte de puntos en poco tiempo es un movimiento
 * brusco (o la imagen movida) y suele ser donde el video se corta en tramos.
 */
public final class Motion {

    private Motion() { }

    /** Un paso entre dos fotos consecutivas (en orden de tiempo). */
    public static final class Step {
        public int fromFrame, toFrame;
        /** Puntos 3D vistos en ambas fotos. */
        public int shared;
        /** Giro (grados) y desplazamiento (unidades del modelo) de la cámara entre las dos fotos. */
        public double turnDeg, move;

        public double seconds(double fps) { return (toFrame - fromFrame) / fps; }

        public double turnPerSecond(double fps) { return turnDeg / Math.max(1e-9, seconds(fps)); }
    }

    /** Pasos entre fotos consecutivas del tramo, en orden de tiempo. */
    public static List<Step> steps(Sfm.Result r) {
        Integer[] o = new Integer[r.poses.size()];
        for (int c = 0; c < o.length; c++) o[c] = c;
        Arrays.sort(o, (a, b) -> Integer.compare(r.camFrames.get(a), r.camFrames.get(b)));
        List<Step> out = new ArrayList<>();
        for (int k = 1; k < o.length; k++) {
            Geometry.Pose pa = r.poses.get(o[k - 1]), pb = r.poses.get(o[k]);
            Step s = new Step();
            s.fromFrame = r.camFrames.get(o[k - 1]);
            s.toFrame = r.camFrames.get(o[k]);
            s.turnDeg = Math.toDegrees(Lin.norm(Lin.logRot(Lin.mul(pb.r, Lin.tr(pa.r)))));
            s.move = Lin.norm(Lin.sub(pb.center(), pa.center()));
            for (List<Sfm.Track> tl : r.pointTracks) {
                boolean a = false, b = false;
                for (Sfm.Track t : tl) {
                    a |= t.visible(s.fromFrame);
                    b |= t.visible(s.toFrame);
                }
                if (a && b) s.shared++;
            }
            out.add(s);
        }
        return out;
    }

    /**
     * Resumen por segundo del seguimiento cuadro a cuadro ({@code perFrame}:
     * activos, perdidos, nuevos por cuadro): {segundo, mínimo de activos,
     * perdidos, nuevos, peor fracción perdida en un cuadro}.
     */
    public static List<double[]> perSecond(List<int[]> perFrame, double fps) {
        List<double[]> out = new ArrayList<>();
        int per = (int) Math.round(fps);
        for (int s = 0; s * per < perFrame.size(); s++) {
            int min = Integer.MAX_VALUE, lost = 0, born = 0;
            double worst = 0;
            for (int f = s * per; f < Math.min(perFrame.size(), (s + 1) * per); f++) {
                int[] v = perFrame.get(f);
                min = Math.min(min, v[0]);
                lost += v[1];
                born += v[2];
                worst = Math.max(worst, lostFraction(perFrame, f));
            }
            out.add(new double[]{s, min, lost, born, worst});
        }
        return out;
    }

    /** Fracción de los puntos activos del cuadro anterior que se perdió en el cuadro {@code f}. */
    public static double lostFraction(List<int[]> perFrame, int f) {
        if (f == 0) return 0;
        int before = perFrame.get(f - 1)[0];
        return before > 0 ? (double) perFrame.get(f)[1] / before : 0;
    }

    /** El cuadro donde se perdió la mayor fracción de puntos (−1 si no hay datos). */
    public static int worstFrame(List<int[]> perFrame) {
        int best = -1;
        double w = -1;
        for (int f = 1; f < perFrame.size(); f++) {
            double l = lostFraction(perFrame, f);
            if (l > w) { w = l; best = f; }
        }
        return best;
    }
}
