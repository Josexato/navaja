package com.josexato.navaja.measure.core;

import com.josexato.navaja.scan3d.core.Carver;

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
        /** Focal (px) y centro óptico (px, coordenadas de la app) de la cámara guardada. */
        public double focal, cx, cy;
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
            s.focal = Double.parseDouble(t[4]);
            // SIMPLE_PINHOLE: f, cx, cy (Colmap cuenta desde la esquina del píxel: medio píxel más)
            s.cx = t.length > 6 ? Double.parseDouble(t[5]) - 0.5 : s.width / 2.0;
            s.cy = t.length > 6 ? Double.parseDouble(t[6]) - 0.5 : s.height / 2.0;
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

    // ------------------------------------------------------------ un solo volumen con las dos tomas

    /** Tolerancia del tallado: vistas en desacuerdo que se perdonan por escaneo (errores de silueta). */
    public static final int TOL = 2;
    public static final double TOL_SHARE = 0.1;
    /** Vóxeles por lado mayor de la caja. */
    public static final int GRID = 110;
    /** Margen de la caja alrededor de los puntos del objeto (mm). */
    public static final double BOX_PAD = 15;
    /** Las siluetas del primer escaneo se ensanchan estos px: absorbe el error de la unión (unos mm). */
    public static final int GROW_A = 3;

    /** Una foto con su silueta, lista para tallar: la cámara ya en los ejes del segundo escaneo. */
    public static final class Cam {
        public Geometry.Pose pose;
        public double f, cx, cy;
        public int w, h;
        /** Silueta de {@code w}×{@code h}, ≠ 0 = objeto. */
        public byte[] mask;
        /** 0 = primer escaneo (A, movido), 1 = segundo (B). */
        public int scan;
    }

    /** La cámara {@code p} del primer escaneo, vista desde los ejes del segundo: x_c = R_A·Rᵀ·X_B + (t_A − R_A·Rᵀ·t). */
    public static Geometry.Pose moved(Geometry.Pose p, Fit f) {
        Geometry.Pose q = new Geometry.Pose();
        q.r = Lin.mul(p.r, Lin.tr(f.r));
        q.t = Lin.sub(p.t, Lin.mulV(q.r, f.t));
        return q;
    }

    /** Ensancha una silueta {@code n} px (vecinos de 4, {@code n} pasadas). */
    public static byte[] grow(byte[] m, int w, int h, int n) {
        byte[] a = m.clone();
        for (int it = 0; it < n; it++) {
            byte[] b = a.clone();
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++) {
                    if (a[y * w + x] != 0) continue;
                    if ((x > 0 && a[y * w + x - 1] != 0) || (x < w - 1 && a[y * w + x + 1] != 0) || (y > 0 && a[(y - 1) * w + x] != 0)
                            || (y < h - 1 && a[(y + 1) * w + x] != 0)) b[y * w + x] = 1;
                }
            a = b;
        }
        return a;
    }

    /** Caja {mín, máx} (mm, ejes del segundo escaneo) de los puntos del objeto de las dos tomas, con margen; el piso es la hoja. */
    public static double[][] box(List<double[]> a, List<double[]> b, Fit f) {
        List<double[]> all = new ArrayList<>(b);
        for (double[] x : a) all.add(f.apply(x));
        if (all.size() < 20) return null;
        double[][] box = new double[2][3];
        double[] v = new double[all.size()];
        for (int c = 0; c < 3; c++) {
            for (int i = 0; i < v.length; i++) v[i] = all.get(i)[c];
            Arrays.sort(v);
            box[0][c] = v[(int) (0.01 * (v.length - 1))] - BOX_PAD;
            box[1][c] = v[(int) (0.99 * (v.length - 1))] + BOX_PAD;
        }
        box[0][2] = 0;
        return box;
    }

    /** El volumen tallado con las dos tomas. */
    public static final class Hull {
        /** Ocupación en los ejes del segundo escaneo (mm; Z hacia arriba, la hoja en Z = 0). */
        public Carver.Grid grid;
        /** cm³. */
        public double volume;
        /** Largo en X, Y y Z (mm, percentiles 1–99). */
        public double[] size;
        public int viewsA, viewsB;
    }

    /**
     * Talla un solo volumen con las siluetas de las dos tomas: un punto del
     * espacio queda si cae dentro de la silueta en casi todas las fotos de
     * cada escaneo que lo ven (se perdonan {@link #TOL} o el 10 %), está
     * sobre la hoja del segundo escaneo y, llevado al primero, también sobre
     * la suya (el objeto estuvo apoyado en las dos). Así cada toma recorta lo
     * que la otra no veía: la parte apoyada. null si hay pocas siluetas o no
     * queda volumen.
     */
    public static Hull carve(List<Cam> cams, double[][] box, Fit f) {
        int[] views = new int[2];
        for (Cam c : cams) views[c.scan]++;
        if (views[0] < 6 || views[1] < 6 || box == null) return null;
        double side = Math.max(box[1][0] - box[0][0], Math.max(box[1][1] - box[0][1], box[1][2] - box[0][2]));
        double step = side / GRID;
        Carver.Grid g = new Carver.Grid();
        g.nx = (int) Math.ceil((box[1][0] - box[0][0]) / step);
        g.ny = (int) Math.ceil((box[1][1] - box[0][1]) / step);
        g.nz = (int) Math.ceil((box[1][2] - box[0][2]) / step);
        g.x0 = box[0][0];
        g.y0 = box[0][1];
        g.z0 = box[0][2];
        g.step = step;
        int n = g.nx * g.ny * g.nz;
        short[][] bad = {new short[n], new short[n]}, seen = {new short[n], new short[n]};
        for (Cam c : cams) {
            Geometry.Pose p = c.pose;
            short[] bd = bad[c.scan], sn = seen[c.scan];
            // x_c = R·X + t, avanzando de a un vóxel en X
            double[] o = p.apply(new double[]{g.x0 + step / 2, g.y0 + step / 2, g.z0 + step / 2});
            double[] ri = {p.r[0] * step, p.r[3] * step, p.r[6] * step}, rj = {p.r[1] * step, p.r[4] * step, p.r[7] * step},
                    rk = {p.r[2] * step, p.r[5] * step, p.r[8] * step};
            for (int k = 0; k < g.nz; k++)
                for (int j = 0; j < g.ny; j++) {
                    double x = o[0] + j * rj[0] + k * rk[0], y = o[1] + j * rj[1] + k * rk[1], z = o[2] + j * rj[2] + k * rk[2];
                    int row = (k * g.ny + j) * g.nx;
                    for (int i = 0; i < g.nx; i++, x += ri[0], y += ri[1], z += ri[2]) {
                        if (z <= 0) continue;
                        double u = c.f * x / z + c.cx, v = c.f * y / z + c.cy;
                        if (u < 1 || v < 1 || u >= c.w - 1 || v >= c.h - 1) continue;
                        sn[row + i]++;
                        if (c.mask[(int) v * c.w + (int) u] == 0) bd[row + i]++;
                    }
                }
        }
        int[] minSeen = {Math.max(4, views[0] / 5), Math.max(4, views[1] / 5)};
        g.occ = new byte[n];
        // La hoja del primer escaneo, en los ejes del segundo: z_A = (Rᵀ·(X − t))_z
        double[] na = {f.r[2], f.r[5], f.r[8]};
        for (int k = 0; k < g.nz; k++)
            for (int j = 0; j < g.ny; j++)
                for (int i = 0; i < g.nx; i++) {
                    int id = g.idx(i, j, k);
                    boolean ok = true;
                    for (int s = 0; s < 2 && ok; s++)
                        ok = seen[s][id] >= minSeen[s] && bad[s][id] <= Math.max(TOL, TOL_SHARE * seen[s][id]);
                    if (!ok) continue;
                    double X = g.x0 + (i + 0.5) * step - f.t[0], Y = g.y0 + (j + 0.5) * step - f.t[1], Z = g.z0 + (k + 0.5) * step - f.t[2];
                    if (na[0] * X + na[1] * Y + na[2] * Z < -step) continue;
                    g.occ[id] = 1;
                }
        Carve.largestComponent(g);
        int cnt = g.count();
        if (cnt < 50) return null;
        Hull h = new Hull();
        h.grid = g;
        h.viewsA = views[0];
        h.viewsB = views[1];
        h.volume = cnt * step * step * step / 1000;
        double[][] co = new double[3][cnt];
        int q = 0;
        for (int k = 0; k < g.nz; k++)
            for (int j = 0; j < g.ny; j++)
                for (int i = 0; i < g.nx; i++)
                    if (g.occ[g.idx(i, j, k)] != 0) {
                        co[0][q] = i * step;
                        co[1][q] = j * step;
                        co[2][q++] = k * step;
                    }
        h.size = new double[3];
        for (int a = 0; a < 3; a++) h.size[a] = Measure.percentile(co[a], 99) - Measure.percentile(co[a], 1) + step;
        return h;
    }
}
