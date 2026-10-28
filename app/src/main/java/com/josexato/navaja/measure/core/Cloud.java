package com.josexato.navaja.measure.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Limpieza de la nube de puntos, en el teléfono:
 * <ol>
 * <li>Puntos atípicos (SOR): la distancia media de cada punto a sus k
 * vecinos; los que quedan muy lejos de la típica son puntos sueltos
 * (coincidencias erradas, reflejos) y se descartan.</li>
 * <li>Cuadrícula de vóxeles: los puntos de cada cubo se reemplazan por su
 * centroide (y su color medio), para que la densidad sea pareja: donde la
 * cámara pasó más cerca o más veces no se amontonan puntos.</li>
 * <li>Normales: plano ajustado a los k vecinos (el eje de menor varianza),
 * apuntando hacia la cámara más cercana, que está del lado visible.</li>
 * </ol>
 * Los vecinos se buscan con una tabla de celdas (sin árboles): con unos
 * miles de puntos tarda decenas de milisegundos.
 */
public final class Cloud {

    private Cloud() { }

    /** Vecinos para atípicos y normales. */
    public static final int K_SOR = 8, K_NORMAL = 12;
    /**
     * Atípico: distancia media a sus vecinos mayor que la mediana + 3
     * desvíos (robustos) Y que el doble de la mediana. La segunda condición
     * protege los bordes del objeto, cuyos vecinos están de un solo lado
     * (~1,3× más lejos) y que con la primera sola se perderían si la nube es
     * muy pareja.
     */
    static final double SOR_SIGMAS = 3, SOR_MIN_RATIO = 2;
    /** Cámaras desde las que se vio un punto para conservarlo aunque esté aislado (el borde del objeto tiene pocos puntos). */
    public static final int TRUSTED_OBS = 4;
    /** Lado del vóxel, en fracción de la mayor medida del objeto. */
    public static final double VOXEL_FRACTION = 0.01;

    // ------------------------------------------------------------ vecinos

    /** Anillos de celdas que se revisan como máximo: un punto sin k vecinos más cerca queda sin ellos (y es atípico). */
    static final int MAX_RING = 24;

    /** Tabla de celdas para buscar vecinos. */
    static final class Grid {
        final double[][] p;
        final double cell;
        final Map<Long, List<Integer>> cells = new HashMap<>();

        Grid(double[][] p, double cell) {
            this.p = p;
            this.cell = cell;
            for (int i = 0; i < p.length; i++) cells.computeIfAbsent(key(p[i]), q -> new ArrayList<>()).add(i);
        }

        long key(double[] q) { return pack(c(q[0]), c(q[1]), c(q[2])); }

        long c(double v) { return (long) Math.floor(v / cell); }

        /**
         * Los {@code k} vecinos más cercanos de {@code i} (sin él mismo), de
         * más cerca a más lejos: busca en anillos de celdas hasta tener k y
         * asegurar que ninguno más cercano quede fuera.
         */
        int[] knn(int i, int k) {
            double[] q = p[i];
            long cx = c(q[0]), cy = c(q[1]), cz = c(q[2]);
            int[] best = new int[k];
            double[] bd = new double[k];
            Arrays.fill(best, -1);
            Arrays.fill(bd, Double.MAX_VALUE);
            int seen = 0;
            for (int ring = 0; ring < MAX_RING && seen < p.length - 1; ring++) {
                // Sólo la cáscara del cubo de celdas a distancia "ring"
                for (long x = cx - ring; x <= cx + ring; x++)
                    for (long y = cy - ring; y <= cy + ring; y++) {
                        boolean side = Math.abs(x - cx) == ring || Math.abs(y - cy) == ring;
                        for (long z = cz - ring; z <= cz + ring; z += side || ring == 0 ? 1 : 2 * ring) {
                            List<Integer> l = cells.get(pack(x, y, z));
                            if (l == null) continue;
                            for (int j : l) {
                                if (j == i) continue;
                                seen++;
                                double d = dist2(q, p[j]);
                                if (d >= bd[k - 1]) continue;
                                int s = k - 1;
                                while (s > 0 && bd[s - 1] > d) { bd[s] = bd[s - 1]; best[s] = best[s - 1]; s--; }
                                bd[s] = d;
                                best[s] = j;
                            }
                        }
                    }
                // Lo que no se miró está a más de ring·cell: si el k-ésimo está más cerca, ya está
                if (bd[k - 1] <= sq(ring * cell)) break;
            }
            int n = 0;
            while (n < k && best[n] >= 0) n++;
            return Arrays.copyOf(best, n);
        }
    }

    /** Celda para la tabla de vecinos: la que deja ~k puntos por celda en una nube del tamaño de {@code p}. */
    static double cellFor(double[][] p, int k) {
        double[] lo = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE}, hi = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (double[] q : p)
            for (int a = 0; a < 3; a++) { lo[a] = Math.min(lo[a], q[a]); hi[a] = Math.max(hi[a], q[a]); }
        double diag = Math.sqrt(sq(hi[0] - lo[0]) + sq(hi[1] - lo[1]) + sq(hi[2] - lo[2]));
        // Una nube de objeto es casi una superficie: n puntos en ~diag² de área
        return Math.max(1e-9, diag * Math.sqrt((double) k / Math.max(1, p.length)));
    }

    // ------------------------------------------------------------ 1) atípicos

    /**
     * Qué puntos quedan (true) tras quitar los atípicos. Con menos de
     * {@code K_SOR + 2} puntos no se quita ninguno.
     */
    public static boolean[] inliers(double[][] p) { return inliers(p, null); }

    /**
     * Igual, pero los puntos marcados en {@code trusted} se quedan aunque
     * estén aislados: un punto visto desde muchas cámaras es real aunque
     * tenga pocos vecinos (el borde de un objeto suele tener pocos puntos).
     */
    public static boolean[] inliers(double[][] p, boolean[] trusted) {
        boolean[] keep = new boolean[p.length];
        Arrays.fill(keep, true);
        if (p.length < K_SOR + 2) return keep;
        Grid g = new Grid(p, cellFor(p, K_SOR));
        double[] md = new double[p.length];
        for (int i = 0; i < p.length; i++) {
            int[] nb = g.knn(i, K_SOR);
            double s = 0;
            for (int j : nb) s += Math.sqrt(dist2(p[i], p[j]));
            md[i] = nb.length > 0 ? s / nb.length : Double.MAX_VALUE;
        }
        double med = Measure.percentile(md, 50);
        double[] dev = new double[md.length];
        for (int i = 0; i < md.length; i++) dev[i] = Math.abs(md[i] - med);
        double sigma = 1.4826 * Measure.percentile(dev, 50);
        double lim = Math.max(med + SOR_SIGMAS * sigma, SOR_MIN_RATIO * med);
        for (int i = 0; i < p.length; i++) keep[i] = md[i] <= lim || trusted != null && trusted[i];
        return keep;
    }

    // ------------------------------------------------------------ 2) vóxeles

    /** Nube resultante: puntos, colores (ARGB), cuántos puntos originales tiene cada uno y normales (o null). */
    public static final class Points {
        public double[][] p;
        public int[] color, count;
        public double[][] normal;

        public int size() { return p.length; }
    }

    /** Un punto por vóxel de lado {@code size}: el centroide y el color medio de los puntos que caen en él. */
    public static Points voxel(double[][] p, int[] color, double size) {
        Map<Long, Integer> at = new HashMap<>();
        List<double[]> acc = new ArrayList<>(); // {x, y, z, r, g, b, n}
        for (int i = 0; i < p.length; i++) {
            long k = pack((long) Math.floor(p[i][0] / size), (long) Math.floor(p[i][1] / size), (long) Math.floor(p[i][2] / size));
            Integer v = at.get(k);
            if (v == null) {
                v = acc.size();
                at.put(k, v);
                acc.add(new double[7]);
            }
            double[] a = acc.get(v);
            for (int q = 0; q < 3; q++) a[q] += p[i][q];
            int c = color[i];
            a[3] += (c >> 16) & 255;
            a[4] += (c >> 8) & 255;
            a[5] += c & 255;
            a[6]++;
        }
        Points out = new Points();
        out.p = new double[acc.size()][];
        out.color = new int[acc.size()];
        out.count = new int[acc.size()];
        for (int v = 0; v < acc.size(); v++) {
            double[] a = acc.get(v);
            double n = a[6];
            out.p[v] = new double[]{a[0] / n, a[1] / n, a[2] / n};
            out.color[v] = 0xFF000000 | ((int) Math.round(a[3] / n) << 16) | ((int) Math.round(a[4] / n) << 8) | (int) Math.round(a[5] / n);
            out.count[v] = (int) n;
        }
        return out;
    }

    // ------------------------------------------------------------ 3) normales

    /**
     * Normal de cada punto (unitaria): el eje de menor varianza de sus
     * {@code K_NORMAL} vecinos, girada hacia el centro de cámara más cercano
     * de {@code cams}. Donde no hay vecinos suficientes queda null.
     */
    public static double[][] normals(double[][] p, double[][] cams) {
        double[][] out = new double[p.length][];
        if (p.length < 4) return out;
        Grid g = new Grid(p, cellFor(p, K_NORMAL));
        for (int i = 0; i < p.length; i++) {
            int[] nb = g.knn(i, K_NORMAL);
            if (nb.length < 3) continue;
            double[] m = p[i].clone();
            for (int j : nb) m = Lin.add(m, p[j]);
            m = Lin.scale(m, 1.0 / (nb.length + 1));
            double[][] cov = new double[3][3];
            for (int t = -1; t < nb.length; t++) {
                double[] d = Lin.sub(t < 0 ? p[i] : p[nb[t]], m);
                for (int a = 0; a < 3; a++) for (int b = 0; b < 3; b++) cov[a][b] += d[a] * d[b];
            }
            double[] n = Lin.unit(((double[][]) Lin.eigSym(cov)[1])[0]);
            double[] cam = nearest(cams, p[i]);
            if (cam != null && Lin.dot(n, Lin.sub(cam, p[i])) < 0) n = Lin.scale(n, -1);
            out[i] = n;
        }
        return out;
    }

    private static double[] nearest(double[][] cams, double[] q) {
        double[] best = null;
        double bd = Double.MAX_VALUE;
        for (double[] c : cams) {
            double d = dist2(c, q);
            if (d < bd) { bd = d; best = c; }
        }
        return best;
    }

    // ------------------------------------------------------------ todo junto

    /** La nube del objeto procesada y cuánto quitó cada paso. */
    public static final class Result {
        public Points points;
        /** Puntos del objeto al entrar, atípicos quitados y lado del vóxel (unidades del modelo). */
        public int input, outliers;
        public double voxel;
    }

    /**
     * Nube del objeto (todos sus puntos, ralos y densos) sin atípicos, en
     * vóxeles de 1 % de su mayor medida y con normales hacia las cámaras.
     * La medida de la caja no la usa: sus percentiles 1–99 ya ignoran los
     * puntos sueltos, y quitar los aislados se llevaba también el borde de
     * una máscara contra la pared (la separación pasaba de 1,7 a 3,5 cm).
     */
    public static Result object(Sfm.Result r, Measure.Box b) {
        double[][] p = new double[b.objectPoints.length][];
        int[] col = new int[p.length];
        for (int k = 0; k < p.length; k++) {
            p[k] = r.points[b.objectPoints[k]];
            col[k] = r.colors[b.objectPoints[k]];
        }
        boolean[] trusted = new boolean[p.length];
        for (int k = 0; k < p.length; k++) {
            int i = b.objectPoints[k];
            trusted[k] = r.nObs != null && i < r.nObs.length && r.nObs[i] >= TRUSTED_OBS;
        }
        boolean[] keep = inliers(p, trusted);
        List<Integer> kept = new ArrayList<>();
        for (int k = 0; k < p.length; k++) if (keep[k]) kept.add(k);
        double[][] q = new double[kept.size()][];
        int[] qc = new int[kept.size()];
        for (int k = 0; k < q.length; k++) {
            q[k] = p[kept.get(k)];
            qc[k] = col[kept.get(k)];
        }
        Result out = new Result();
        out.input = p.length;
        out.outliers = p.length - q.length;
        out.voxel = VOXEL_FRACTION * Math.max(b.alto, Math.max(b.ancho, b.prof));
        out.points = out.voxel > 0 ? voxel(q, qc, out.voxel) : voxel(q, qc, 1);
        double[][] cams = new double[r.poses.size()][];
        for (int c = 0; c < cams.length; c++) cams[c] = r.poses.get(c).center();
        out.points.normal = normals(out.points.p, cams);
        return out;
    }

    // ------------------------------------------------------------ utilidades

    static long pack(long x, long y, long z) { return ((x & 0x1FFFFF) << 42) | ((y & 0x1FFFFF) << 21) | (z & 0x1FFFFF); }

    private static double dist2(double[] a, double[] b) { return sq(a[0] - b[0]) + sq(a[1] - b[1]) + sq(a[2] - b[2]); }

    private static double sq(double v) { return v * v; }
}
