package com.josexato.navaja.measure.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Une dos escaneos del mismo objeto en dos posiciones (por ejemplo parado y
 * acostado): halla el giro y el desplazamiento que llevan el escaneo A sobre
 * el B. Sirve para completar lo que cada toma no ve (la base apoyada).
 *
 * <p>Cómo: de cada foto se deja sólo el objeto (fondo gris fuera de la
 * silueta, recortado y ampliado), porque el fondo y la hoja son distintos en
 * cada toma y confunden al emparejador. Las fotos de B se prueban además
 * giradas 90°, 180° y 270°: XFeat no reconoce nada con la imagen girada 90° o
 * más. Cada par de puntos emparejados se pasa a un par de puntos 3D (el punto
 * 3D cuya observación en esa foto está más cerca) y con todos se ajusta un
 * movimiento rígido con RANSAC. Aquí está la parte que no depende de Android
 * ni de los modelos: leer la reconstrucción guardada, los recortes, la
 * asociación a puntos 3D y el ajuste.
 */
public final class PoseJoin {

    private PoseJoin() { }

    /** Margen del recorte alrededor de la silueta y aumento máximo. */
    public static final double MARGIN = 1.12, MAX_ZOOM = 4;
    /** Silueta mínima (px) para usar una foto. */
    public static final int MIN_MASK = 400;
    /** Un punto clave toma el punto 3D de la observación más cercana, hasta esta distancia (px). */
    public static final double NEAR_PX = 3;
    /** Pares coherentes: a menos de esto (mm) después de aplicar el movimiento. */
    public static final double TOL_MM = 4;
    /** Pares coherentes mínimos para dar por buena la unión, y parte mínima del total. */
    public static final int MIN_INLIERS = 20;
    public static final int GRAY = 0xff808080;

    /** La reconstrucción guardada de un escaneo (colmap/sparse/0 del ZIP). */
    public static final class Scan {
        public int width, height;
        public final List<String> names = new ArrayList<>();
        public final List<Geometry.Pose> poses = new ArrayList<>();
        /** Por foto: observaciones (x, y en px de la foto) y el índice del punto 3D de cada una. */
        public final List<float[]> obsXY = new ArrayList<>();
        public final List<int[]> obsPoint = new ArrayList<>();
        public double[][] points = new double[0][];
        public int[] colors = new int[0];

        public int index(String name) { return names.indexOf(name); }
    }

    /** Lee cameras.txt, images.txt y points3D.txt (los que escribe {@link Colmap#text}). */
    public static Scan parse(String cameras, String images, String points3D) {
        Scan s = new Scan();
        for (String l : cameras.split("\n")) {
            l = l.trim();
            if (l.isEmpty() || l.startsWith("#")) continue;
            String[] t = l.split("\\s+");
            s.width = Integer.parseInt(t[2]);
            s.height = Integer.parseInt(t[3]);
            break;
        }
        Map<Integer, Integer> pid = new HashMap<>();
        List<double[]> pts = new ArrayList<>();
        List<Integer> cols = new ArrayList<>();
        for (String l : points3D.split("\n")) {
            l = l.trim();
            if (l.isEmpty() || l.startsWith("#")) continue;
            String[] t = l.split("\\s+");
            pid.put(Integer.parseInt(t[0]), pts.size());
            pts.add(new double[]{Double.parseDouble(t[1]), Double.parseDouble(t[2]), Double.parseDouble(t[3])});
            cols.add(0xff000000 | Integer.parseInt(t[4]) << 16 | Integer.parseInt(t[5]) << 8 | Integer.parseInt(t[6]));
        }
        s.points = pts.toArray(new double[0][]);
        s.colors = new int[cols.size()];
        for (int i = 0; i < s.colors.length; i++) s.colors[i] = cols.get(i);
        // images.txt: una línea con la pose y la siguiente con las observaciones (puede estar vacía)
        String[] ls = images.split("\n", -1);
        for (int i = 0; i < ls.length; i++) {
            String l = ls[i].trim();
            if (l.isEmpty() || l.startsWith("#")) continue;
            String[] t = l.split("\\s+");
            if (t.length < 10) continue;
            double qw = Double.parseDouble(t[1]), qx = Double.parseDouble(t[2]), qy = Double.parseDouble(t[3]), qz = Double.parseDouble(t[4]);
            Geometry.Pose p = new Geometry.Pose();
            p.r = new double[]{1 - 2 * (qy * qy + qz * qz), 2 * (qx * qy - qw * qz), 2 * (qx * qz + qw * qy),
                    2 * (qx * qy + qw * qz), 1 - 2 * (qx * qx + qz * qz), 2 * (qy * qz - qw * qx),
                    2 * (qx * qz - qw * qy), 2 * (qy * qz + qw * qx), 1 - 2 * (qx * qx + qy * qy)};
            p.t = new double[]{Double.parseDouble(t[5]), Double.parseDouble(t[6]), Double.parseDouble(t[7])};
            s.poses.add(p);
            s.names.add(t[9]);
            String[] o = i + 1 < ls.length ? ls[i + 1].trim().split("\\s+") : new String[0];
            i++;
            int n = o.length / 3;
            float[] xy = new float[2 * n];
            int[] pt = new int[n];
            int k = 0;
            for (int q = 0; q < n; q++) {
                Integer id = pid.get(Integer.parseInt(o[3 * q + 2]));
                if (id == null) continue;
                // Colmap cuenta desde la esquina del píxel: medio píxel más que las coordenadas de la app
                xy[2 * k] = Float.parseFloat(o[3 * q]) - 0.5f;
                xy[2 * k + 1] = Float.parseFloat(o[3 * q + 1]) - 0.5f;
                pt[k++] = id;
            }
            s.obsXY.add(Arrays.copyOf(xy, 2 * k));
            s.obsPoint.add(Arrays.copyOf(pt, k));
        }
        return s;
    }

    /**
     * Recorte {x, y, ancho, alto} alrededor de la silueta ({@code mask}, de
     * {@code w}×{@code h}, ≠ 0 = objeto), con margen y la proporción de la
     * foto. null si la silueta es muy chica.
     */
    public static double[] rect(byte[] mask, int w, int h) {
        int x0 = w, x1 = -1, y0 = h, y1 = -1, n = 0;
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                if (mask[y * w + x] != 0) {
                    n++;
                    if (x < x0) x0 = x;
                    if (x > x1) x1 = x;
                    if (y < y0) y0 = y;
                    if (y > y1) y1 = y;
                }
        if (n < MIN_MASK) return null;
        double rw = (x1 + 1 - x0) * MARGIN, rh = (y1 + 1 - y0) * MARGIN;
        double zoom = Math.min(MAX_ZOOM, Math.min(w / rw, h / rh));
        rw = w / zoom;
        rh = h / zoom;
        double rx = Math.max(0, Math.min(w - rw, (x0 + x1 + 1) / 2.0 - rw / 2)), ry = Math.max(0, Math.min(h - rh, (y0 + y1 + 1) / 2.0 - rh / 2));
        return new double[]{rx, ry, rw, rh};
    }

    /** Tamaño {ancho, alto} del recorte girado {@code turn} grados (0, 90, 180 o 270, antihorario). */
    public static int[] cutSize(int w, int h, int turn) {
        return turn == 90 || turn == 270 ? new int[]{h, w} : new int[]{w, h};
    }

    /**
     * La foto con el fondo gris fuera de la silueta, recortada a
     * {@code rect}, ampliada al tamaño de la foto (bilineal) y girada
     * {@code turn} grados en sentido antihorario.
     */
    public static int[] cut(int[] argb, byte[] mask, int w, int h, double[] rect, int turn) {
        int[] flat = new int[w * h];
        for (int y = 0; y < h; y++) {
            double sy = rect[1] + (y + 0.5) * rect[3] / h - 0.5;
            int iy = (int) Math.max(0, Math.min(h - 2, Math.floor(sy)));
            double fy = Math.max(0, Math.min(1, sy - iy));
            for (int x = 0; x < w; x++) {
                double sx = rect[0] + (x + 0.5) * rect[2] / w - 0.5;
                int ix = (int) Math.max(0, Math.min(w - 2, Math.floor(sx)));
                double fx = Math.max(0, Math.min(1, sx - ix));
                int[] q = {iy * w + ix, iy * w + ix + 1, (iy + 1) * w + ix, (iy + 1) * w + ix + 1};
                double[] wt = {(1 - fx) * (1 - fy), fx * (1 - fy), (1 - fx) * fy, fx * fy};
                int v = 0;
                for (int s = 0; s < 24; s += 8) {
                    double c = 0;
                    for (int k = 0; k < 4; k++) c += wt[k] * (mask[q[k]] != 0 ? (argb[q[k]] >> s) & 255 : 128);
                    v |= (int) Math.round(c) << s;
                }
                flat[y * w + x] = 0xff000000 | v;
            }
        }
        if (turn == 0) return flat;
        int[] wh = cutSize(w, h, turn);
        int[] out = new int[w * h];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int ox, oy;
                if (turn == 90) { ox = y; oy = w - 1 - x; }
                else if (turn == 180) { ox = w - 1 - x; oy = h - 1 - y; }
                else { ox = h - 1 - y; oy = x; }
                out[oy * wh[0] + ox] = flat[y * w + x];
            }
        return out;
    }

    /** Un punto del recorte girado ({@link #cut}), de vuelta a px de la foto original. */
    public static double[] toPhoto(double x, double y, double[] rect, int w, int h, int turn) {
        double ux, uy; // sin girar
        if (turn == 90) { ux = w - 1 - y; uy = x; }
        else if (turn == 180) { ux = w - 1 - x; uy = h - 1 - y; }
        else if (turn == 270) { ux = y; uy = h - 1 - x; }
        else { ux = x; uy = y; }
        return new double[]{rect[0] + (ux + 0.5) * rect[2] / w - 0.5, rect[1] + (uy + 0.5) * rect[3] / h - 0.5};
    }

    /**
     * Para cada punto clave (x, y en px de la foto {@code image}): el punto
     * 3D de la observación más cercana de esa foto que cae dentro de la
     * silueta, si está a menos de {@link #NEAR_PX}; −1 si no hay.
     */
    public static int[] to3d(Scan s, int image, byte[] mask, double[][] xy) {
        float[] ob = s.obsXY.get(image);
        int[] pt = s.obsPoint.get(image);
        int[] out = new int[xy.length];
        for (int i = 0; i < xy.length; i++) {
            double best = NEAR_PX * NEAR_PX;
            out[i] = -1;
            for (int k = 0; k < pt.length; k++) {
                double dx = ob[2 * k] - xy[i][0], dy = ob[2 * k + 1] - xy[i][1], d = dx * dx + dy * dy;
                if (d >= best) continue;
                int mx = Math.round(ob[2 * k]), my = Math.round(ob[2 * k + 1]);
                if (mx < 0 || my < 0 || mx >= s.width || my >= s.height || mask[my * s.width + mx] == 0) continue;
                best = d;
                out[i] = pt[k];
            }
        }
        return out;
    }

    /** El movimiento hallado: x_B = R·x_A + t. */
    public static final class Fit {
        public double[] r, t;
        public boolean[] inlier;
        public int inliers, pairs;
        /** Error mediano de los pares coherentes (mm). */
        public double error;
        /** Giro total y cuánto inclina la vertical (grados). */
        public double angleDeg, tiltDeg;

        public double[] apply(double[] x) { return Lin.add(Lin.mulV(r, x), t); }

        /** Si hay bastantes pares coherentes como para fiarse. */
        public boolean ok() { return inliers >= MIN_INLIERS; }
    }

    /** Giro y desplazamiento que mejor llevan {@code p} sobre {@code q} (mínimos cuadrados, sin reflejo). */
    public static double[][] rigid(List<double[]> p, List<double[]> q) {
        int n = p.size();
        double[] cp = new double[3], cq = new double[3];
        for (int i = 0; i < n; i++) for (int k = 0; k < 3; k++) { cp[k] += p.get(i)[k] / n; cq[k] += q.get(i)[k] / n; }
        double[] hm = new double[9]; // H = Σ (p − cp)(q − cq)ᵀ
        for (int i = 0; i < n; i++)
            for (int a = 0; a < 3; a++)
                for (int b = 0; b < 3; b++) hm[3 * a + b] += (p.get(i)[a] - cp[a]) * (q.get(i)[b] - cq[b]);
        Object[] svd = Lin.svd3(hm);
        double[] u = (double[]) svd[0], v = (double[]) svd[2];
        double[] r = Lin.mul(v, Lin.tr(u));
        if (Lin.det(r) < 0) { // reflejo: se da vuelta la dirección de menor peso
            double[] v2 = v.clone();
            for (int k = 0; k < 3; k++) v2[3 * k + 2] = -v2[3 * k + 2];
            r = Lin.mul(v2, Lin.tr(u));
        }
        return new double[][]{r, Lin.sub(cq, Lin.mulV(r, cp))};
    }

    /** Ajuste robusto: prueba ternas al azar y se queda con el movimiento que más pares deja a menos de {@link #TOL_MM}. null si no hay con qué. */
    public static Fit fit(List<double[]> p, List<double[]> q, long seed) {
        int n = p.size();
        if (n < 6) return null;
        Random rnd = new Random(seed);
        boolean[] best = null;
        int bestN = 0;
        List<double[]> sp = new ArrayList<>(), sq = new ArrayList<>();
        for (int it = 0; it < 20000; it++) {
            int a = rnd.nextInt(n), b = rnd.nextInt(n), c = rnd.nextInt(n);
            if (a == b || b == c || a == c) continue;
            sp.clear();
            sq.clear();
            for (int k : new int[]{a, b, c}) { sp.add(p.get(k)); sq.add(q.get(k)); }
            double[][] m = rigid(sp, sq);
            boolean[] in = new boolean[n];
            int cnt = 0;
            for (int i = 0; i < n; i++) if (dist(m, p.get(i), q.get(i)) < TOL_MM) { in[i] = true; cnt++; }
            if (cnt > bestN) { bestN = cnt; best = in; }
        }
        if (best == null || bestN < 3) return null;
        double[][] m = null;
        for (int round = 0; round < 3; round++) {
            sp.clear();
            sq.clear();
            for (int i = 0; i < n; i++) if (best[i]) { sp.add(p.get(i)); sq.add(q.get(i)); }
            if (sp.size() < 3) return null;
            m = rigid(sp, sq);
            for (int i = 0; i < n; i++) best[i] = dist(m, p.get(i), q.get(i)) < TOL_MM;
        }
        Fit f = new Fit();
        f.r = m[0];
        f.t = m[1];
        f.inlier = best;
        f.pairs = n;
        List<Double> e = new ArrayList<>();
        for (int i = 0; i < n; i++) if (best[i]) e.add(dist(m, p.get(i), q.get(i)));
        f.inliers = e.size();
        if (e.isEmpty()) return null;
        java.util.Collections.sort(e);
        f.error = e.get(e.size() / 2);
        f.angleDeg = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, (f.r[0] + f.r[4] + f.r[8] - 1) / 2))));
        f.tiltDeg = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, f.r[8]))));
        return f;
    }

    private static double dist(double[][] m, double[] p, double[] q) {
        double[] x = Lin.add(Lin.mulV(m[0], p), m[1]);
        return Math.sqrt((x[0] - q[0]) * (x[0] - q[0]) + (x[1] - q[1]) * (x[1] - q[1]) + (x[2] - q[2]) * (x[2] - q[2]));
    }

    /**
     * Comprobación independiente de los pares: distancia mediana (mm) de cada
     * punto de {@code a} (ya movido con {@code f}; con null, sólo centrado
     * sobre {@code b}, sin girar) al punto más cercano de {@code b}.
     */
    public static double cloudGap(List<double[]> a, List<double[]> b, Fit f) {
        if (a.isEmpty() || b.isEmpty()) return Double.NaN;
        double[] ca = new double[3], cb = new double[3];
        for (double[] x : a) for (int k = 0; k < 3; k++) ca[k] += x[k] / a.size();
        for (double[] x : b) for (int k = 0; k < 3; k++) cb[k] += x[k] / b.size();
        double[] d = new double[a.size()];
        for (int i = 0; i < d.length; i++) {
            double[] x = f != null ? f.apply(a.get(i)) : Lin.add(Lin.sub(a.get(i), ca), cb);
            double best = Double.MAX_VALUE;
            for (double[] y : b) {
                double e = (x[0] - y[0]) * (x[0] - y[0]) + (x[1] - y[1]) * (x[1] - y[1]) + (x[2] - y[2]) * (x[2] - y[2]);
                if (e < best) best = e;
            }
            d[i] = Math.sqrt(best);
        }
        Arrays.sort(d);
        return d[d.length / 2];
    }

    /**
     * Otra comprobación independiente, física: el objeto está apoyado sobre
     * la hoja (Z = 0 en los dos escaneos), así que los puntos de {@code a} ya
     * movidos no pueden quedar debajo de ella. Devuelve qué parte queda a más
     * de {@code mm} por debajo (0..1). Con un movimiento equivocado suele ser
     * grande; con uno bueno, casi nada.
     */
    public static double below(List<double[]> a, Fit f, double mm) {
        if (a.isEmpty()) return Double.NaN;
        int n = 0;
        for (double[] x : a) if (f.apply(x)[2] < -mm) n++;
        return n / (double) a.size();
    }
}
