package com.josexato.navaja.measure.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Detector de las marcas de la hoja ({@link Sheet}) en una imagen en grises,
 * sin OpenCV:
 * <ol>
 * <li>umbral adaptativo (media local con imagen integral);</li>
 * <li>manchas oscuras conexas de tamaño razonable;</li>
 * <li>de cada mancha, el cuadrilátero que la envuelve (envolvente convexa →
 * 4 esquinas) y las esquinas refinadas ajustando una recta a cada lado
 * por el cruce subpíxel negro → blanco;</li>
 * <li>las 6×6 celdas leídas con la homografía del cuadrado: el anillo debe
 * ser negro y las 4×4 interiores deben coincidir (hasta 2 bits) con un
 * código del diccionario en alguna de las 4 orientaciones.</li>
 * </ol>
 */
public final class Markers {

    private Markers() { }

    /** Una marca encontrada: su ID, sus esquinas en la imagen (en el orden de {@link Sheet#corners}) y los bits que hubo que corregir. */
    public static final class Marker {
        public int id, errors;
        public double[][] corners;
        /** Lado medio en píxeles (qué tan grande se ve). */
        public double side;
    }

    /** Lado mínimo de una marca en la imagen (px): más chica no se leen sus celdas. */
    static final int MIN_SIDE = 14;
    /** Bits que se pueden corregir (el diccionario tiene distancia 6). */
    static final int MAX_ERRORS = 2;

    /** Marcas en la imagen ARGB {@code w}×{@code h}. */
    public static List<Marker> detect(int[] argb, int w, int h) {
        float[] g = new float[w * h];
        for (int i = 0; i < g.length; i++) {
            int c = argb[i];
            g[i] = 0.299f * ((c >> 16) & 255) + 0.587f * ((c >> 8) & 255) + 0.114f * (c & 255);
        }
        return detect(g, w, h);
    }

    /** Marcas en la imagen en grises {@code g} (0–255). */
    public static List<Marker> detect(float[] g, int w, int h) {
        boolean[] dark = threshold(g, w, h);
        int[] label = new int[w * h];
        List<int[]> comps = components(dark, w, h, label); // {id, área, x0, y0, x1, y1}
        List<Marker> found = new ArrayList<>();
        for (int[] cp : comps) {
            int bw = cp[4] - cp[2] + 1, bh = cp[5] - cp[3] + 1;
            if (bw < MIN_SIDE || bh < MIN_SIDE || bw > 0.8 * w || bh > 0.8 * h || cp[1] < MIN_SIDE * 4) continue;
            double[][] quad = quad(label, g, w, h, cp);
            if (quad == null) continue;
            Marker m = read(g, w, h, quad);
            if (m != null) found.add(m);
        }
        // Una marca por ID: la de menos errores (y más grande)
        List<Marker> out = new ArrayList<>();
        Marker[] best = new Marker[Sheet.count()];
        for (Marker m : found)
            if (best[m.id] == null || m.errors < best[m.id].errors || (m.errors == best[m.id].errors && m.side > best[m.id].side)) best[m.id] = m;
        // Coordenadas de Navaja: el centro del píxel en 0 (aquí se trabajó con el centro en 0,5)
        for (Marker m : best) {
            if (m == null) continue;
            for (double[] c : m.corners) { c[0] -= 0.5; c[1] -= 0.5; }
            out.add(m);
        }
        return out;
    }

    // ------------------------------------------------------------ 1) umbral

    /** Oscuro: más de 7 niveles por debajo de la media de su vecindad (ventana de ~1/12 de la imagen). */
    static boolean[] threshold(float[] g, int w, int h) {
        double[] ii = new double[(w + 1) * (h + 1)];
        for (int y = 0; y < h; y++) {
            double row = 0;
            for (int x = 0; x < w; x++) {
                row += g[y * w + x];
                ii[(y + 1) * (w + 1) + x + 1] = ii[y * (w + 1) + x + 1] + row;
            }
        }
        int r = Math.max(7, Math.min(w, h) / 24);
        boolean[] out = new boolean[w * h];
        for (int y = 0; y < h; y++) {
            int y0 = Math.max(0, y - r), y1 = Math.min(h, y + r + 1);
            for (int x = 0; x < w; x++) {
                int x0 = Math.max(0, x - r), x1 = Math.min(w, x + r + 1);
                double s = ii[y1 * (w + 1) + x1] - ii[y0 * (w + 1) + x1] - ii[y1 * (w + 1) + x0] + ii[y0 * (w + 1) + x0];
                double mean = s / ((x1 - x0) * (y1 - y0));
                out[y * w + x] = g[y * w + x] < mean - 7;
            }
        }
        return out;
    }

    // ------------------------------------------------------------ 2) manchas

    /** Componentes conexas (vecinos de 4) de lo oscuro: {id, área, x0, y0, x1, y1}; {@code label} queda con el id (1…). */
    static List<int[]> components(boolean[] dark, int w, int h, int[] label) {
        List<int[]> out = new ArrayList<>();
        int[] stack = new int[w * h];
        int next = 0;
        for (int s = 0; s < dark.length; s++) {
            if (!dark[s] || label[s] != 0) continue;
            int id = ++next, top = 0, area = 0, x0 = w, y0 = h, x1 = 0, y1 = 0;
            label[s] = id;
            stack[top++] = s;
            while (top > 0) {
                int p = stack[--top], x = p % w, y = p / w;
                area++;
                x0 = Math.min(x0, x);
                x1 = Math.max(x1, x);
                y0 = Math.min(y0, y);
                y1 = Math.max(y1, y);
                if (x > 0 && dark[p - 1] && label[p - 1] == 0) { label[p - 1] = id; stack[top++] = p - 1; }
                if (x < w - 1 && dark[p + 1] && label[p + 1] == 0) { label[p + 1] = id; stack[top++] = p + 1; }
                if (y > 0 && dark[p - w] && label[p - w] == 0) { label[p - w] = id; stack[top++] = p - w; }
                if (y < h - 1 && dark[p + w] && label[p + w] == 0) { label[p + w] = id; stack[top++] = p + w; }
            }
            out.add(new int[]{id, area, x0, y0, x1, y1});
        }
        return out;
    }

    // ------------------------------------------------------------ 3) cuadrilátero

    /**
     * El cuadrilátero de la mancha: 4 esquinas de su envolvente convexa (la
     * más lejana del centro, la opuesta y las más alejadas de esa diagonal
     * a cada lado), refinadas con una recta por lado. Null si la mancha no
     * es un cuadrilátero (la envolvente se aparta del cuadrilátero).
     */
    static double[][] quad(int[] label, float[] g, int w, int h, int[] cp) {
        int id = cp[0];
        // Extremos de cada fila: bastan para la envolvente convexa
        List<double[]> ext = new ArrayList<>();
        for (int y = cp[3]; y <= cp[5]; y++) {
            int lo = -1, hi = -1;
            for (int x = cp[2]; x <= cp[4]; x++) {
                int p = y * w + x;
                if (label[p] != id) continue;
                if (lo < 0) lo = x;
                hi = x;
            }
            if (lo >= 0) {
                ext.add(new double[]{lo, y + 0.5});
                ext.add(new double[]{hi + 1, y + 0.5});
            }
        }
        // Si la mancha toca el borde de la imagen, la marca está cortada
        if (cp[2] == 0 || cp[3] == 0 || cp[4] == w - 1 || cp[5] == h - 1) return null;
        double[][] hull = Hull.convex(ext.toArray(new double[0][]));
        if (hull.length < 4) return null;
        double cx = 0, cy = 0;
        for (double[] p : hull) { cx += p[0] / hull.length; cy += p[1] / hull.length; }
        int a = 0;
        for (int i = 1; i < hull.length; i++) if (d2(hull[i], cx, cy) > d2(hull[a], cx, cy)) a = i;
        int c = a;
        for (int i = 0; i < hull.length; i++) if (d2(hull[i], hull[a][0], hull[a][1]) > d2(hull[c], hull[a][0], hull[a][1])) c = i;
        int b = -1, d = -1;
        double bs = 0, ds = 0;
        for (int i = 0; i < hull.length; i++) {
            double s = cross(hull[a], hull[c], hull[i]);
            if (s > bs) { bs = s; b = i; }
            if (s < ds) { ds = s; d = i; }
        }
        if (b < 0 || d < 0) return null;
        double[][] q = {hull[a], hull[b], hull[c], hull[d]};
        double qa = Math.abs(area(q)), ha = Math.abs(area(hull));
        if (qa < 0.9 * ha) return null; // la envolvente no es un cuadrilátero
        // Lados razonables (no un triángulo con un vértice duplicado)
        double minSide = 1e18, maxSide = 0;
        for (int k = 0; k < 4; k++) {
            double l = Math.sqrt(d2(q[k], q[(k + 1) % 4][0], q[(k + 1) % 4][1]));
            minSide = Math.min(minSide, l);
            maxSide = Math.max(maxSide, l);
        }
        if (minSide < MIN_SIDE * 0.7 || maxSide > 4 * minSide) return null;
        return refine(q, g, w, h);
    }

    /**
     * Esquinas refinadas: a lo largo de cada lado (su tramo central) se mide
     * el gris en perfiles perpendiculares y se busca, con interpolación,
     * dónde cruza el punto medio entre el negro de la marca y el blanco del
     * papel: el borde real con precisión de décimas de píxel (el umbral
     * adaptativo se come o agrega medio píxel según el desenfoque). Se
     * ajusta una recta a esos cruces y cada esquina es el cruce de sus dos
     * lados. Null si algún lado no se pudo medir.
     */
    static double[][] refine(double[][] q, float[] g, int w, int h) {
        double[][] lines = new double[4][]; // {nx, ny, c}: nx·x + ny·y = c
        double qx = 0, qy = 0;
        for (double[] p : q) { qx += p[0] / 4; qy += p[1] / 4; }
        for (int k = 0; k < 4; k++) {
            double[] p0 = q[k], p1 = q[(k + 1) % 4];
            double ex = p1[0] - p0[0], ey = p1[1] - p0[1], len = Math.hypot(ex, ey);
            ex /= len;
            ey /= len;
            double nx = -ey, ny = ex;
            if (nx * (p0[0] - qx) + ny * (p0[1] - qy) < 0) { nx = -nx; ny = -ny; } // hacia afuera
            List<double[]> pts = new ArrayList<>();
            int samples = Math.max(6, (int) (len * 0.7));
            for (int i = 0; i < samples; i++) {
                double t = 0.15 + 0.7 * i / (samples - 1);
                double bx = p0[0] + ex * len * t, by = p0[1] + ey * len * t;
                // Perfil de −3 a +3 px a través del borde (adentro negro, afuera blanco)
                int nst = 25;
                double[] prof = new double[nst];
                double lo = 1e9, hi = -1e9;
                for (int j = 0; j < nst; j++) {
                    double d = -3 + 6.0 * j / (nst - 1);
                    prof[j] = bilinear(g, w, h, bx + nx * d, by + ny * d);
                    lo = Math.min(lo, prof[j]);
                    hi = Math.max(hi, prof[j]);
                }
                if (hi - lo < 30) continue;
                double mid = (lo + hi) / 2;
                // El cruce de subida (negro → blanco) más cercano al borde grueso
                double best = Double.NaN;
                for (int j = 0; j + 1 < nst; j++)
                    if (prof[j] < mid && prof[j + 1] >= mid) {
                        double d = -3 + 6.0 * (j + (mid - prof[j]) / (prof[j + 1] - prof[j])) / (nst - 1);
                        if (Double.isNaN(best) || Math.abs(d) < Math.abs(best)) best = d;
                    }
                if (!Double.isNaN(best)) pts.add(new double[]{bx + nx * best, by + ny * best});
            }
            if (pts.size() < 4) return null;
            double mx = 0, my = 0;
            for (double[] p : pts) { mx += p[0] / pts.size(); my += p[1] / pts.size(); }
            double cxx = 0, cyy = 0, cxy = 0;
            for (double[] p : pts) {
                cxx += (p[0] - mx) * (p[0] - mx);
                cyy += (p[1] - my) * (p[1] - my);
                cxy += (p[0] - mx) * (p[1] - my);
            }
            double ang = 0.5 * Math.atan2(2 * cxy, cxx - cyy);
            double fnx = -Math.sin(ang), fny = Math.cos(ang);
            if (fnx * nx + fny * ny < 0) { fnx = -fnx; fny = -fny; }
            lines[k] = new double[]{fnx, fny, fnx * mx + fny * my};
        }
        double[][] out = new double[4][];
        for (int k = 0; k < 4; k++) {
            double[] l1 = lines[(k + 3) % 4], l2 = lines[k];
            double det = l1[0] * l2[1] - l1[1] * l2[0];
            if (Math.abs(det) < 1e-6) return null;
            out[k] = new double[]{(l1[2] * l2[1] - l1[1] * l2[2]) / det, (l1[0] * l2[2] - l1[2] * l2[0]) / det};
            if (Math.hypot(out[k][0] - q[k][0], out[k][1] - q[k][1]) > 4) return null; // el ajuste se fue: no es un borde limpio
        }
        return out;
    }

    // ------------------------------------------------------------ 4) lectura

    /** Lee las celdas del cuadrilátero; la marca si el anillo es negro y el código está en el diccionario. */
    static Marker read(float[] g, int w, int h, double[][] quad) {
        // Sentido horario en la imagen (y hacia abajo), como se ve la marca impresa
        if (area(quad) < 0) quad = new double[][]{quad[0], quad[3], quad[2], quad[1]};
        Marker best = null;
        for (int k = 0; k < 4; k++) {
            double[][] q = {quad[k], quad[(k + 1) % 4], quad[(k + 2) % 4], quad[(k + 3) % 4]};
            double[] H = com.josexato.navaja.scan3d.core.Geom.homography(new double[][]{{0, 0}, {6, 0}, {6, 6}, {0, 6}}, q);
            if (H == null) return null;
            double[] v = new double[36];
            for (int r = 0; r < 6; r++)
                for (int c = 0; c < 6; c++) v[r * 6 + c] = cell(g, w, h, H, c + 0.5, r + 0.5);
            double lo = 1e9, hi = -1e9;
            for (double x : v) { lo = Math.min(lo, x); hi = Math.max(hi, x); }
            if (hi - lo < 30) return null; // sin contraste: no es una marca
            double thr = otsu(v, lo, hi);
            int ringWhite = 0;
            for (int r = 0; r < 6; r++)
                for (int c = 0; c < 6; c++)
                    if ((r == 0 || c == 0 || r == 5 || c == 5) && v[r * 6 + c] > thr) ringWhite++;
            if (ringWhite > 2) return null;
            int code = 0;
            for (int r = 1; r < 5; r++)
                for (int c = 1; c < 5; c++) code = (code << 1) | (v[r * 6 + c] < thr ? 1 : 0);
            for (int id = 0; id < Sheet.count(); id++) {
                int err = Integer.bitCount(code ^ Sheet.CODES[id]);
                if (err <= MAX_ERRORS && (best == null || err < best.errors)) {
                    best = new Marker();
                    best.id = id;
                    best.errors = err;
                    best.corners = q;
                }
            }
        }
        if (best != null) {
            double s = 0;
            for (int k = 0; k < 4; k++) s += Math.sqrt(d2(best.corners[k], best.corners[(k + 1) % 4][0], best.corners[(k + 1) % 4][1])) / 4;
            best.side = s;
        }
        return best;
    }

    /** Gris medio del centro de la celda (u, v) de la marca: 3×3 muestras en su tercio central. */
    private static double cell(float[] g, int w, int h, double[] H, double u, double v) {
        double s = 0;
        for (int a = -1; a <= 1; a++)
            for (int b = -1; b <= 1; b++) {
                double[] p = applyH(H, u + a * 0.18, v + b * 0.18);
                s += bilinear(g, w, h, p[0], p[1]);
            }
        return s / 9;
    }

    /** Umbral que mejor separa dos grupos (Otsu sobre las 36 celdas). */
    static double otsu(double[] v, double lo, double hi) {
        double[] s = v.clone();
        Arrays.sort(s);
        double best = (lo + hi) / 2, bestVar = -1;
        for (int k = 1; k < s.length; k++) {
            double m0 = 0, m1 = 0;
            for (int i = 0; i < k; i++) m0 += s[i];
            for (int i = k; i < s.length; i++) m1 += s[i];
            m0 /= k;
            m1 /= s.length - k;
            double var = (double) k * (s.length - k) * (m1 - m0) * (m1 - m0);
            if (var > bestVar) { bestVar = var; best = (s[k - 1] + s[k]) / 2; }
        }
        return best;
    }

    static double[] applyH(double[] H, double x, double y) {
        double z = H[6] * x + H[7] * y + H[8];
        return new double[]{(H[0] * x + H[1] * y + H[2]) / z, (H[3] * x + H[4] * y + H[5]) / z};
    }

    static double bilinear(float[] g, int w, int h, double x, double y) {
        x = Math.max(0, Math.min(w - 1.001, x - 0.5));
        y = Math.max(0, Math.min(h - 1.001, y - 0.5));
        int x0 = (int) x, y0 = (int) y;
        double fx = x - x0, fy = y - y0;
        return g[y0 * w + x0] * (1 - fx) * (1 - fy) + g[y0 * w + x0 + 1] * fx * (1 - fy)
                + g[(y0 + 1) * w + x0] * (1 - fx) * fy + g[(y0 + 1) * w + x0 + 1] * fx * fy;
    }

    private static double d2(double[] p, double x, double y) { return (p[0] - x) * (p[0] - x) + (p[1] - y) * (p[1] - y); }

    private static double cross(double[] o, double[] a, double[] b) { return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]); }

    /** Área con signo (positiva en sentido horario con y hacia abajo). */
    static double area(double[][] p) {
        double s = 0;
        for (int i = 0; i < p.length; i++) {
            double[] a = p[i], b = p[(i + 1) % p.length];
            s += a[0] * b[1] - b[0] * a[1];
        }
        return s / 2;
    }
}
