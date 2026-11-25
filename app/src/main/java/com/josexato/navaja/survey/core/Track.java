package com.josexato.navaja.survey.core;

import java.util.ArrayList;
import java.util.List;

/**
 * El recorrido de quien camina: posiciones en metros con su instante. En planta, {@code x} y {@code y}; {@code z} es
 * la altura. Los tramos en que el teléfono perdió el seguimiento quedan marcados y no se unen con una línea: ahí no
 * se sabe por dónde se pasó.
 */
public final class Track {

    /** Un salto más largo que esto entre dos muestras parte el recorrido en dos tramos. */
    public static final long MAX_GAP_MS = 2000;
    /** Lo que hay que moverse para que cuente como distancia: quieto, la posición tiembla unos milímetros. */
    public static final double MIN_STEP_M = 0.03;

    public static final class Sample {
        public final long tMs;
        public final double x, y, z;
        public final boolean tracking;

        public Sample(long tMs, double x, double y, double z, boolean tracking) {
            this.tMs = tMs;
            this.x = x;
            this.y = y;
            this.z = z;
            this.tracking = tracking;
        }
    }

    public final List<Sample> samples = new ArrayList<>();

    /** De los ejes de ARCore (Y hacia arriba, −Z hacia adelante) al plano: se mira desde arriba sin espejar. */
    public static double[] plan(double arX, double arY, double arZ) {
        return new double[]{arX, -arZ, arY};
    }

    /** Agrega una muestra; las que llegan con el reloj hacia atrás se descartan. */
    public boolean add(long tMs, double x, double y, double z, boolean tracking) {
        if (!samples.isEmpty() && tMs <= samples.get(samples.size() - 1).tMs) return false;
        if (Double.isNaN(x) || Double.isNaN(y) || Double.isNaN(z)) return false;
        samples.add(new Sample(tMs, x, y, z, tracking));
        return true;
    }

    /** Si entre dos muestras seguidas se puede trazar una línea. */
    static boolean joined(Sample a, Sample b) {
        return a.tracking && b.tracking && b.tMs - a.tMs <= MAX_GAP_MS;
    }

    /** Metros caminados en planta, sin contar el temblor de estar quieto ni los tramos sin seguimiento. */
    public double length() {
        double sum = 0;
        Sample last = null, prev = null;
        for (Sample s : samples) {
            if (prev == null || !joined(prev, s)) last = s.tracking ? s : null;
            else if (last != null) {
                double d = Math.hypot(s.x - last.x, s.y - last.y);
                if (d >= MIN_STEP_M) {
                    sum += d;
                    last = s;
                }
            }
            prev = s;
        }
        return sum;
    }

    /** Milisegundos con seguimiento sobre el total grabado; 0 si no hay nada. */
    public double trackedShare() {
        if (samples.size() < 2) return 0;
        long ok = 0;
        for (int i = 1; i < samples.size(); i++) {
            Sample a = samples.get(i - 1), b = samples.get(i);
            if (joined(a, b)) ok += b.tMs - a.tMs;
        }
        return ok / (double) (samples.get(samples.size() - 1).tMs - samples.get(0).tMs);
    }

    /**
     * Dónde estaba el teléfono en un instante: {x, y, z} interpolado entre las dos muestras vecinas, o null si ese
     * instante cae fuera de lo grabado o en un tramo sin seguimiento.
     */
    public double[] at(long tMs) {
        int n = samples.size();
        if (n == 0 || tMs < samples.get(0).tMs || tMs > samples.get(n - 1).tMs) return null;
        int lo = 0, hi = n - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (samples.get(mid).tMs <= tMs) lo = mid;
            else hi = mid;
        }
        Sample a = samples.get(lo), b = samples.get(hi);
        if (lo == hi || a.tMs == tMs) return a.tracking ? new double[]{a.x, a.y, a.z} : null;
        if (b.tMs == tMs) return b.tracking ? new double[]{b.x, b.y, b.z} : null;
        if (!joined(a, b)) return null;
        double f = (tMs - a.tMs) / (double) (b.tMs - a.tMs);
        return new double[]{a.x + f * (b.x - a.x), a.y + f * (b.y - a.y), a.z + f * (b.z - a.z)};
    }

    /**
     * El recorrido como líneas para dibujar: un tramo por cada trecho con seguimiento, con los puntos que no aportan
     * forma quitados (Douglas–Peucker con la tolerancia dada, en metros). Cada punto es {x, y}.
     */
    public List<List<double[]>> lines(double tolM) {
        List<List<double[]>> out = new ArrayList<>();
        List<double[]> cur = new ArrayList<>();
        Sample prev = null;
        for (Sample s : samples) {
            if (prev != null && !joined(prev, s)) {
                flush(out, cur, tolM);
                cur = new ArrayList<>();
            }
            if (s.tracking) cur.add(new double[]{s.x, s.y});
            prev = s;
        }
        flush(out, cur, tolM);
        return out;
    }

    private static void flush(List<List<double[]>> out, List<double[]> cur, double tolM) {
        if (cur.size() < 2) return;
        List<double[]> s = simplify(cur, tolM);
        if (s.size() >= 2) out.add(s);
    }

    /** Douglas–Peucker sin recursión (los recorridos largos tienen miles de puntos). */
    static List<double[]> simplify(List<double[]> pts, double tol) {
        int n = pts.size();
        if (n <= 2 || tol <= 0) return new ArrayList<>(pts);
        boolean[] keep = new boolean[n];
        keep[0] = keep[n - 1] = true;
        List<int[]> stack = new ArrayList<>();
        stack.add(new int[]{0, n - 1});
        while (!stack.isEmpty()) {
            int[] r = stack.remove(stack.size() - 1);
            double worst = -1;
            int at = -1;
            for (int i = r[0] + 1; i < r[1]; i++) {
                double d = distToSegment(pts.get(i), pts.get(r[0]), pts.get(r[1]));
                if (d > worst) {
                    worst = d;
                    at = i;
                }
            }
            if (at >= 0 && worst > tol) {
                keep[at] = true;
                stack.add(new int[]{r[0], at});
                stack.add(new int[]{at, r[1]});
            }
        }
        List<double[]> out = new ArrayList<>();
        for (int i = 0; i < n; i++) if (keep[i]) out.add(pts.get(i));
        return out;
    }

    static double distToSegment(double[] p, double[] a, double[] b) {
        double dx = b[0] - a[0], dy = b[1] - a[1];
        double len2 = dx * dx + dy * dy;
        if (len2 == 0) return Math.hypot(p[0] - a[0], p[1] - a[1]);
        double t = Math.max(0, Math.min(1, ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / len2));
        return Math.hypot(p[0] - (a[0] + t * dx), p[1] - (a[1] + t * dy));
    }

    /** {minX, minY, maxX, maxY} de las muestras con seguimiento, o null si no hay. */
    public double[] bounds() {
        double[] b = null;
        for (Sample s : samples) {
            if (!s.tracking) continue;
            if (b == null) b = new double[]{s.x, s.y, s.x, s.y};
            b[0] = Math.min(b[0], s.x);
            b[1] = Math.min(b[1], s.y);
            b[2] = Math.max(b[2], s.x);
            b[3] = Math.max(b[3], s.y);
        }
        return b;
    }
}
