package com.josexato.navaja.measure.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Modo hoja: corrige las siluetas de MagicTouch con lo que ya se sabe en 3D
 * (todo en mm, sistema de la hoja, Z hacia arriba).
 *
 * <ol>
 * <li><b>Recortar</b>: un píxel sólo puede ser del objeto si su rayo pasa por
 * el espacio que ocupa el objeto: la caja de sus puntos (percentiles 2–98,
 * con margen) desde la hoja hasta un poco más arriba de su tope. Lo demás
 * (la pared del fondo, otra cosa en la mesa) se borra de la silueta.</li>
 * <li><b>Agregar</b>: un punto 3D del objeto siempre se proyecta dentro de su
 * silueta, aunque desde esa foto esté tapado. Alrededor de la proyección de
 * cada punto confiable del objeto se marca un disco chico y luego se cierran
 * los huecos: así vuelven partes que MagicTouch dejó afuera (un tallo
 * delgado, un costado).</li>
 * </ol>
 */
public final class SheetMasks {

    private SheetMasks() { }

    /** Margen de la caja del objeto: fracción de su tamaño y mínimo en mm; su tope, al menos tantas veces el de los puntos y tantos mm más. */
    static final double MARGIN_FRAC = 0.5, MARGIN_MM = 30, TOP_FACTOR = 1.6, TOP_MM = 40;
    /** Puntos que agregan silueta: vistos en al menos tantas fotos y a esta altura mínima (mm) sobre la hoja. */
    static final int MIN_OBS = 4;
    static final double MIN_HEIGHT = 3;
    /** Radio (px de la silueta) del disco de cada punto y del cierre de huecos. */
    static final int DISK = 2, CLOSE = 2;

    /** Un punto agrega silueta sólo si ya cae dentro de la silueta original en al menos esta parte de las fotos donde se proyecta (0 = sin pedirlo; > 1 = no se agrega nada). */
    static double MIN_INSIDE = 0.5;

    /**
     * Corrige todas las siluetas (nuevas; las null quedan null): elige los
     * puntos confiables del objeto (ver {@link #MIN_INSIDE}) y aplica
     * {@link #refine} a cada una.
     */
    public static byte[][] refineAll(Sfm.Result r, Measure.Box b, byte[][] masks, int mw, int mh, Stats st) {
        return refineAll(r, b, masks, mw, mh, st, true);
    }

    /** Igual; con {@code add} false sólo se recorta. */
    public static byte[][] refineAll(Sfm.Result r, Measure.Box b, byte[][] masks, int mw, int mh, Stats st, boolean add) {
        double[] region = region(r, b);
        if (region == null) return masks;
        double sx = (double) mw / r.width, sy = (double) mh / r.height, cx = r.width / 2.0, cy = r.height / 2.0;
        List<Integer> good = new ArrayList<>();
        for (int k : b.objectPoints) {
            if (r.nObs[k] < MIN_OBS) continue;
            double[] x = r.points[k];
            if (x[2] < MIN_HEIGHT || x[0] < region[0] || x[0] > region[1] || x[1] < region[2] || x[1] > region[3] || x[2] > region[5]) continue;
            if (!add || MIN_INSIDE > 1) continue;
            int seen = 0, in = 0;
            for (int c = 0; c < masks.length; c++) {
                if (masks[c] == null) continue;
                double[] xc = r.poses.get(c).apply(x);
                if (xc[2] <= 1) continue;
                int u = (int) Math.floor((r.focal * xc[0] / xc[2] + cx) * sx), v = (int) Math.floor((r.focal * xc[1] / xc[2] + cy) * sy);
                if (u < 0 || v < 0 || u >= mw || v >= mh) continue;
                seen++;
                if (masks[c][v * mw + u] != 0) in++;
            }
            if (seen > 0 && in >= MIN_INSIDE * seen) good.add(k);
        }
        st.addPoints = good.size();
        int[] keep = good.stream().mapToInt(Integer::intValue).toArray();
        byte[][] out = new byte[masks.length][];
        for (int c = 0; c < masks.length; c++) out[c] = refine(r, keep, region, c, masks[c], mw, mh, st);
        return out;
    }

    /** Consenso: vóxeles por lado, parte de las siluetas que deben contener un vóxel, y cuánto menos que las buenas debe parecerse (IoU) una silueta a la predicción para ser mala (al menos). */
    static final int VOTE_N = 72;
    static final double VOTE_SHARE = 0.7, STRICT_SHARE = 0.9, MIN_DROP = 0.2;
    /** Parte de las siluetas que como mucho puede salir mala para confiar en el consenso. */
    static final double MAX_BAD_SHARE = 0.5;
    /** Parecido mediano a partir del cual la predicción es buena como para reemplazar una silueta mala (si no, sólo se descarta). */
    static final double GOOD_IOU = 0.78;

    /**
     * Compara cada silueta con lo que predicen las demás y repara las que no
     * calzan. Con las fotos clave a pocos grados una de otra, el contorno
     * real cambia poco entre vecinas: una silueta muy distinta es un error
     * de la IA (se desbordó a la mesa o cortó un pedazo del objeto).
     *
     * <p>La predicción sale de un volumen por votación: un vóxel del espacio
     * del objeto queda si está dentro de al menos {@link #VOTE_SHARE} de las
     * siluetas que lo ven (las malas, mientras sean minoría, pierden la
     * votación). Ese volumen se proyecta en cada foto; si la silueta de la
     * foto se parece a la proyección mucho menos que las demás, es mala: se
     * reemplaza por la proyección si la predicción es buena en general
     * (parecido mediano ≥ {@link #GOOD_IOU}) o, si no, se descarta (null).
     * Anota en {@code st.repaired} y {@code st.dropped} cuáles.
     */
    public static byte[][] consensus(Sfm.Result r, Measure.Box b, byte[][] masks, int mw, int mh, Stats st) {
        double[] reg = region(r, b);
        int views = 0;
        for (byte[] m : masks) if (m != null) views++;
        if (reg == null || views < 8) return masks;
        // Dos vueltas: la segunda vota sin las que la primera encontró malas (la predicción mejora)
        boolean[] bad = new boolean[masks.length];
        byte[][] preds = null;
        double med = 0;
        for (int round = 0; round < 2; round++) {
            byte[][] voters = masks.clone();
            for (int c = 0; c < voters.length; c++) if (bad[c]) voters[c] = null;
            List<double[]> vox = new ArrayList<>();
            // (ya sin las malas, se exige casi unanimidad: el volumen queda ajustado y la predicción no engorda)
            double step = vote(r, reg, voters, mw, mh, vox, round == 0 ? VOTE_SHARE : STRICT_SHARE);
            st.voteVoxels = vox.size();
            if (vox.size() < 50) return masks;
            preds = new byte[masks.length][];
            double[] ious = new double[masks.length];
            List<Double> all = new ArrayList<>();
            for (int c = 0; c < masks.length; c++) {
                if (masks[c] == null) continue;
                preds[c] = project(r, vox, step, c, mw, mh);
                int inter = 0, union = 0;
                for (int i = 0; i < preds[c].length; i++) {
                    boolean a = masks[c][i] != 0, q = preds[c][i] != 0;
                    if (a && q) inter++;
                    if (a || q) union++;
                }
                ious[c] = union == 0 ? 0 : (double) inter / union;
                all.add(ious[c]);
            }
            // Mala = se parece a la predicción mucho menos que las buenas. "Las buenas" son la mitad de arriba (así
            // aguanta que casi la mitad esté mal); no se usa un umbral fijo porque con pocas fotos la predicción es
            // pobre para todas.
            double[] so = all.stream().mapToDouble(Double::doubleValue).sorted().toArray();
            double[] top = Arrays.copyOfRange(so, so.length / 2, so.length);
            double tm = top[top.length / 2];
            double[] dev = new double[top.length];
            for (int i = 0; i < top.length; i++) dev[i] = Math.abs(top[i] - tm);
            Arrays.sort(dev);
            double cut = tm - MIN_DROP;
            med = tm;
            for (int c = 0; c < masks.length; c++) bad[c] = masks[c] != null && ious[c] < cut;
        }
        st.iouMedian = med;
        int nBad = 0;
        for (boolean x : bad) if (x) nBad++;
        st.flagged = nBad;
        // Si la mayoría no calza, el consenso no es de fiar (pocas fotos, o las malas son demasiadas): no se toca nada
        if (nBad > MAX_BAD_SHARE * views) return masks;
        byte[][] out = masks.clone();
        for (int c = 0; c < masks.length; c++) {
            if (!bad[c]) continue;
            // Con una predicción buena, se usa en su lugar; si no, la silueta sólo se descarta
            if (med >= GOOD_IOU) {
                out[c] = preds[c];
                st.repaired.add(c);
            } else {
                out[c] = null;
                st.dropped.add(c);
            }
        }
        return out;
    }

    /** Volumen por votación de las siluetas {@code masks} (centros de los vóxeles en {@code vox}); devuelve el lado del vóxel. */
    private static double vote(Sfm.Result r, double[] reg, byte[][] masks, int mw, int mh, List<double[]> vox, double share) {
        double z0 = 0, ext = Math.max(reg[1] - reg[0], Math.max(reg[3] - reg[2], reg[5] - z0));
        double step = ext / VOTE_N;
        int nx = (int) Math.ceil((reg[1] - reg[0]) / step), ny = (int) Math.ceil((reg[3] - reg[2]) / step), nz = (int) Math.ceil((reg[5] - z0) / step);
        short[] seen = new short[nx * ny * nz], in = new short[nx * ny * nz];
        double sx = (double) mw / r.width, sy = (double) mh / r.height, cx = r.width / 2.0, cy = r.height / 2.0, f = r.focal;
        int views = 0;
        for (int c = 0; c < masks.length; c++) {
            byte[] m = masks[c];
            if (m == null) continue;
            views++;
            Geometry.Pose p = r.poses.get(c);
            for (int k = 0, id = 0; k < nz; k++)
                for (int j = 0; j < ny; j++)
                    for (int i = 0; i < nx; i++, id++) {
                        double[] xc = p.apply(new double[]{reg[0] + (i + 0.5) * step, reg[2] + (j + 0.5) * step, z0 + (k + 0.5) * step});
                        if (xc[2] <= 1) continue;
                        int u = (int) Math.floor((f * xc[0] / xc[2] + cx) * sx), v = (int) Math.floor((f * xc[1] / xc[2] + cy) * sy);
                        if (u < 0 || v < 0 || u >= mw || v >= mh) continue;
                        seen[id]++;
                        if (m[v * mw + u] != 0) in[id]++;
                    }
        }
        int minSeen = Math.max(5, views / 3);
        for (int k = 0, id = 0; k < nz; k++)
            for (int j = 0; j < ny; j++)
                for (int i = 0; i < nx; i++, id++)
                    if (seen[id] >= minSeen && in[id] >= share * seen[id])
                        vox.add(new double[]{reg[0] + (i + 0.5) * step, reg[2] + (j + 0.5) * step, z0 + (k + 0.5) * step});
        return step;
    }

    /** La silueta que predicen los vóxeles {@code vox} en la cámara {@code c}. */
    private static byte[] project(Sfm.Result r, List<double[]> vox, double step, int c, int mw, int mh) {
        double sx = (double) mw / r.width, sy = (double) mh / r.height, cx = r.width / 2.0, cy = r.height / 2.0, f = r.focal;
        Geometry.Pose p = r.poses.get(c);
        byte[] pred = new byte[mw * mh];
        for (double[] x : vox) {
            double[] xc = p.apply(x);
            if (xc[2] <= 1) continue;
            double u = (f * xc[0] / xc[2] + cx) * sx, v = (f * xc[1] / xc[2] + cy) * sy;
            // El vóxel ocupa unos píxeles: se marca su huella
            int rad = (int) Math.ceil(0.5 * step * f / xc[2] * sx);
            for (int yy = (int) Math.floor(v) - rad; yy <= (int) Math.floor(v) + rad; yy++)
                for (int xx = (int) Math.floor(u) - rad; xx <= (int) Math.floor(u) + rad; xx++)
                    if (xx >= 0 && yy >= 0 && xx < mw && yy < mh) pred[yy * mw + xx] = 1;
        }
        return pred;
    }

    /** Cuántos píxeles de silueta se borraron y se agregaron (en todas las fotos). */
    public static final class Stats {
        public long clipped, added;
        public int masks, addPoints;
        /** Consenso: cámaras cuya silueta se reemplazó por la que predicen las demás, vóxeles del volumen votado y parecido (IoU) acumulado. */
        public final List<Integer> repaired = new ArrayList<>();
        public final List<Integer> dropped = new ArrayList<>();
        public int voteVoxels, flagged;
        public double iouMedian;
    }

    /** El espacio del objeto: {xmin, xmax, ymin, ymax, zmin, zmax} en mm; null sin puntos. */
    public static double[] region(Sfm.Result r, Measure.Box b) {
        int n = b.objectPoints.length;
        if (n < 10) return null;
        double[] xs = new double[n], ys = new double[n], zs = new double[n];
        for (int k = 0; k < n; k++) {
            double[] p = r.points[b.objectPoints[k]];
            xs[k] = p[0];
            ys[k] = p[1];
            zs[k] = p[2];
        }
        Arrays.sort(xs);
        Arrays.sort(ys);
        Arrays.sort(zs);
        double x0 = xs[n * 2 / 100], x1 = xs[n * 98 / 100], y0 = ys[n * 2 / 100], y1 = ys[n * 98 / 100], z1 = zs[n * 98 / 100];
        // Holgada: los puntos pueden no llegar a los bordes del objeto (una cara brillante sin puntos); sólo debe sacar lo lejano
        double mx = Math.max(MARGIN_MM, MARGIN_FRAC * (x1 - x0)), my = Math.max(MARGIN_MM, MARGIN_FRAC * (y1 - y0));
        double top = Math.max(TOP_FACTOR * z1, z1 + TOP_MM);
        return new double[]{x0 - mx, x1 + mx, y0 - my, y1 + my, -2, top};
    }

    /**
     * La silueta {@code m} ({@code mw}×{@code mh}) de la cámara {@code c}
     * corregida (nueva; null si {@code m} es null o no hay región): recortada
     * a {@code region} y con discos en la proyección de los puntos {@code pts}.
     */
    public static byte[] refine(Sfm.Result r, int[] pts, double[] region, int c, byte[] m, int mw, int mh, Stats st) {
        if (m == null || region == null) return m;
        Geometry.Pose p = r.poses.get(c);
        double sx = (double) mw / r.width, sy = (double) mh / r.height, cx = r.width / 2.0, cy = r.height / 2.0, f = r.focal;
        byte[] out = m.clone();
        // 1. Recortar a la proyección de la caja (convexa: su imagen es el casco convexo de sus 8 esquinas)
        List<double[]> pr = new ArrayList<>();
        boolean front = true;
        for (int i = 0; i < 8 && front; i++) {
            double[] x = {region[(i & 1)], region[2 + ((i >> 1) & 1)], region[4 + ((i >> 2) & 1)]};
            double[] xc = p.apply(x);
            if (xc[2] <= 1) front = false;
            else pr.add(new double[]{(f * xc[0] / xc[2] + cx) * sx, (f * xc[1] / xc[2] + cy) * sy});
        }
        if (front) {
            double[][] hull = convexHull(pr);
            for (int y = 0; y < mh; y++)
                for (int x = 0; x < mw; x++) {
                    int i = y * mw + x;
                    if (out[i] != 0 && !inside(hull, x + 0.5, y + 0.5)) {
                        out[i] = 0;
                        st.clipped++;
                    }
                }
        }
        // 2. Agregar discos en la proyección de los puntos confiables del objeto y cerrar huecos
        byte[] add = new byte[mw * mh];
        int marked = 0;
        for (int k : pts) {
            double[] x = r.points[k];
            double[] xc = p.apply(x);
            if (xc[2] <= 1) continue;
            int u = (int) Math.floor((f * xc[0] / xc[2] + cx) * sx), v = (int) Math.floor((f * xc[1] / xc[2] + cy) * sy);
            for (int dy = -DISK; dy <= DISK; dy++)
                for (int dx = -DISK; dx <= DISK; dx++) {
                    int xx = u + dx, yy = v + dy;
                    if (dx * dx + dy * dy > DISK * DISK || xx < 0 || yy < 0 || xx >= mw || yy >= mh) continue;
                    add[yy * mw + xx] = 1;
                    marked++;
                }
        }
        if (marked > 0) {
            for (int i = 0; i < add.length; i++) if (out[i] != 0) add[i] = 1;
            byte[] closed = erode(dilate(add, mw, mh, CLOSE), mw, mh, CLOSE);
            for (int i = 0; i < out.length; i++)
                if (closed[i] != 0 && out[i] == 0) {
                    out[i] = 1;
                    st.added++;
                }
        }
        st.masks++;
        return out;
    }

    static byte[] dilate(byte[] m, int w, int h, int r) {
        byte[] o = new byte[m.length];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                if (m[y * w + x] == 0) continue;
                for (int dy = -r; dy <= r; dy++)
                    for (int dx = -r; dx <= r; dx++) {
                        int xx = x + dx, yy = y + dy;
                        if (dx * dx + dy * dy <= r * r && xx >= 0 && yy >= 0 && xx < w && yy < h) o[yy * w + xx] = 1;
                    }
            }
        return o;
    }

    static byte[] erode(byte[] m, int w, int h, int r) {
        byte[] o = new byte[m.length];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                boolean all = true;
                for (int dy = -r; dy <= r && all; dy++)
                    for (int dx = -r; dx <= r && all; dx++) {
                        if (dx * dx + dy * dy > r * r) continue;
                        int xx = x + dx, yy = y + dy;
                        // Fuera de la imagen cuenta como lleno: no se come el borde
                        if (xx >= 0 && yy >= 0 && xx < w && yy < h && m[yy * w + xx] == 0) all = false;
                    }
                if (all) o[y * w + x] = 1;
            }
        return o;
    }

    /** Casco convexo (cadena monótona), en orden antihorario. */
    static double[][] convexHull(List<double[]> pts) {
        List<double[]> ps = new ArrayList<>(pts);
        ps.sort((a, b) -> a[0] != b[0] ? Double.compare(a[0], b[0]) : Double.compare(a[1], b[1]));
        int n = ps.size();
        double[][] h = new double[2 * n][];
        int k = 0;
        for (int i = 0; i < n; i++) {
            while (k >= 2 && cross(h[k - 2], h[k - 1], ps.get(i)) <= 0) k--;
            h[k++] = ps.get(i);
        }
        for (int i = n - 2, t = k + 1; i >= 0; i--) {
            while (k >= t && cross(h[k - 2], h[k - 1], ps.get(i)) <= 0) k--;
            h[k++] = ps.get(i);
        }
        return Arrays.copyOf(h, Math.max(0, k - 1));
    }

    private static double cross(double[] o, double[] a, double[] b) {
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
    }

    /** Si (x, y) está dentro del polígono convexo {@code h} (antihorario). */
    static boolean inside(double[][] h, double x, double y) {
        if (h.length < 3) return false;
        for (int i = 0; i < h.length; i++) {
            double[] a = h[i], b = h[(i + 1) % h.length];
            if ((b[0] - a[0]) * (y - a[1]) - (b[1] - a[1]) * (x - a[0]) < 0) return false;
        }
        return true;
    }
}
