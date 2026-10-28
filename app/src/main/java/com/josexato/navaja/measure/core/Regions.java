package com.josexato.navaja.measure.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Regiones de color dentro de la silueta del objeto (por ejemplo, en la
 * máscara: los ojos, la boca, las cejas) como puntos de referencia. Cada una
 * aporta su centro, su color medio, su tamaño relativo, su orientación y sus
 * momentos de Hu (forma, invariantes a giro y escala en la imagen).
 * Emparejando regiones entre dos cuadros con una disposición coherente se
 * sabe cuánto giró y cambió de escala el objeto en la imagen, aunque los
 * puntos no se reconozcan; y eso guía la búsqueda de puntos. Los colores
 * salen solos (k-medias en Lab), sin umbrales hechos para un objeto.
 */
public final class Regions {

    private Regions() { }

    /** Grupos de color (k-medias) y tamaño mínimo de una región (fracción de la silueta). */
    static final int K = 6;
    static final double MIN_AREA = 0.004, DOMINANT = 0.35;
    /** Variantes de sombra del color dominante: distancia de tono (a, b) y de luminosidad máximas. */
    static final double SKIN_AB = 10, SKIN_L = 25;
    /** Regiones que se guardan por cuadro (las más grandes): con textura fina salen decenas de manchas sin valor. */
    static final int MAX_REGIONS = 10;

    public static final class Region {
        /** Centro (px del cuadro), color medio (L, a, b), área (fracción de la silueta), orientación (rad) y alargamiento (ejes). */
        public double x, y, area, angle, elong;
        public double[] lab, hu;
    }

    /** Regiones de un cuadro: las regiones y el centro y tamaño (raíz del área en px) de la silueta. */
    public static final class Set {
        public final List<Region> regions = new ArrayList<>();
        public double cx, cy, size;
    }

    /**
     * Regiones de color del cuadro {@code argb} ({@code w}×{@code h}) dentro
     * de la silueta {@code mask} ({@code mw}×{@code mh}, 1 = objeto). Null si
     * la silueta es muy chica.
     */
    public static Set detect(int[] argb, int w, int h, byte[] mask, int mw, int mh) {
        // Trabajar a la resolución de la silueta
        int n = 0;
        for (byte b : mask) if (b != 0) n++;
        if (n < 400) return null;
        float[][] lab = new float[mw * mh][];
        double sx = (double) w / mw, sy = (double) h / mh;
        double cx = 0, cy = 0;
        for (int y = 0; y < mh; y++)
            for (int x = 0; x < mw; x++) {
                int i = y * mw + x;
                if (mask[i] == 0) continue;
                int px = Math.min(w - 1, (int) ((x + 0.5) * sx)), py = Math.min(h - 1, (int) ((y + 0.5) * sy));
                lab[i] = toLab(argb[py * w + px]);
                cx += x;
                cy += y;
            }
        lab = smooth(lab, mw, mh);
        Set out = new Set();
        out.cx = cx / n * sx;
        out.cy = cy / n * sy;
        out.size = Math.sqrt(n * sx * sy);
        // k-medias en Lab (con una muestra), semilla fija
        float[][] cen = kmeans(lab, K, new Random(5));
        int[] lbl = new int[mw * mh];
        int[] count = new int[K];
        Arrays.fill(lbl, -1);
        for (int i = 0; i < lab.length; i++) {
            if (lab[i] == null) continue;
            lbl[i] = nearest(cen, lab[i]);
            count[lbl[i]]++;
        }
        // La "piel" del objeto: el color dominante y sus variantes de sombra (mismo tono, luminosidad parecida)
        boolean[] skin = new boolean[K];
        int dom = 0;
        for (int j = 1; j < K; j++) if (count[j] > count[dom]) dom = j;
        for (int j = 0; j < K; j++)
            skin[j] = count[j] > DOMINANT * n || (Math.hypot(cen[j][1] - cen[dom][1], cen[j][2] - cen[dom][2]) < SKIN_AB
                    && Math.abs(cen[j][0] - cen[dom][0]) < SKIN_L);
        for (int i = 0; i < lbl.length; i++) if (lbl[i] >= 0 && skin[lbl[i]]) lbl[i] = -1;
        lbl = open(lbl, mw, mh);
        // Regiones: manchas conexas de cada color que no domine la silueta (la "piel" del objeto no es un punto de referencia)
        int[] comp = new int[mw * mh];
        ArrayDeque<Integer> q = new ArrayDeque<>();
        int next = 0;
        for (int s = 0; s < comp.length; s++) {
            if (lbl[s] < 0 || comp[s] != 0) continue;
            int c = lbl[s];
            comp[s] = ++next;
            q.add(s);
            List<Integer> px = new ArrayList<>();
            boolean edge = false;
            while (!q.isEmpty()) {
                int id = q.poll(), x = id % mw, y = id / mw;
                px.add(id);
                int[] nb = {x > 0 ? id - 1 : -1, x < mw - 1 ? id + 1 : -1, y > 0 ? id - mw : -1, y < mh - 1 ? id + mw : -1};
                for (int o : nb) {
                    if (o < 0 || mask[o] == 0) edge = true;
                    else if (comp[o] == 0 && lbl[o] == c) { comp[o] = next; q.add(o); }
                }
            }
            // Las que tocan el borde de la silueta suelen ser sombra o fondo colado, y cambian con la vista
            if (edge || px.size() < Math.max(12, MIN_AREA * n)) continue;
            out.regions.add(describe(px, lab, mw, sx, sy, n));
        }
        out.regions.sort((p, r) -> Double.compare(r.area, p.area));
        while (out.regions.size() > MAX_REGIONS) out.regions.remove(out.regions.size() - 1);
        return out;
    }

    /** Promedio de 3×3 dentro de la silueta (quita el grano antes de agrupar colores). */
    static float[][] smooth(float[][] lab, int mw, int mh) {
        float[][] out = new float[lab.length][];
        for (int y = 0; y < mh; y++)
            for (int x = 0; x < mw; x++) {
                if (lab[y * mw + x] == null) continue;
                float[] acc = new float[3];
                int c = 0;
                for (int dy = -1; dy <= 1; dy++)
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = x + dx, yy = y + dy;
                        if (xx < 0 || yy < 0 || xx >= mw || yy >= mh || lab[yy * mw + xx] == null) continue;
                        for (int k = 0; k < 3; k++) acc[k] += lab[yy * mw + xx][k];
                        c++;
                    }
                for (int k = 0; k < 3; k++) acc[k] /= c;
                out[y * mw + x] = acc;
            }
        return out;
    }

    /**
     * Apertura de 3×3 por color: un píxel queda con su color sólo si todo su
     * entorno lo comparte después de erosionar (quita hilos y puntitos sueltos).
     */
    static int[] open(int[] lbl, int mw, int mh) {
        int[] er = new int[lbl.length];
        Arrays.fill(er, -1);
        for (int y = 1; y < mh - 1; y++)
            for (int x = 1; x < mw - 1; x++) {
                int c = lbl[y * mw + x];
                if (c < 0) continue;
                boolean all = true;
                for (int dy = -1; dy <= 1 && all; dy++)
                    for (int dx = -1; dx <= 1 && all; dx++) all = lbl[(y + dy) * mw + x + dx] == c;
                if (all) er[y * mw + x] = c;
            }
        int[] out = new int[lbl.length];
        Arrays.fill(out, -1);
        for (int y = 1; y < mh - 1; y++)
            for (int x = 1; x < mw - 1; x++) {
                int c = er[y * mw + x];
                if (c < 0) continue;
                for (int dy = -1; dy <= 1; dy++)
                    for (int dx = -1; dx <= 1; dx++) {
                        int i = (y + dy) * mw + x + dx;
                        if (lbl[i] == c) out[i] = c;
                    }
            }
        // Lo que no sobrevive queda fuera de toda región, pero sigue siendo silueta (cuenta en n)
        return out;
    }

    private static Region describe(List<Integer> px, float[][] lab, int mw, double sx, double sy, int n) {
        double m00 = px.size(), m10 = 0, m01 = 0;
        double[] col = new double[3];
        for (int id : px) {
            m10 += id % mw;
            m01 += id / mw;
            for (int k = 0; k < 3; k++) col[k] += lab[id][k];
        }
        double xc = m10 / m00, yc = m01 / m00;
        double mu20 = 0, mu02 = 0, mu11 = 0, mu30 = 0, mu03 = 0, mu21 = 0, mu12 = 0;
        for (int id : px) {
            double dx = id % mw - xc, dy = id / mw - yc;
            mu20 += dx * dx;
            mu02 += dy * dy;
            mu11 += dx * dy;
            mu30 += dx * dx * dx;
            mu03 += dy * dy * dy;
            mu21 += dx * dx * dy;
            mu12 += dx * dy * dy;
        }
        Region r = new Region();
        r.x = (xc + 0.5) * sx;
        r.y = (yc + 0.5) * sy;
        r.area = m00 / n;
        r.angle = 0.5 * Math.atan2(2 * mu11, mu20 - mu02);
        double tr = mu20 + mu02, dif = Math.sqrt((mu20 - mu02) * (mu20 - mu02) + 4 * mu11 * mu11);
        r.elong = Math.sqrt((tr + dif) / Math.max(1e-9, tr - dif));
        r.lab = new double[]{col[0] / m00, col[1] / m00, col[2] / m00};
        r.hu = hu(m00, mu20, mu02, mu11, mu30, mu03, mu21, mu12);
        return r;
    }

    /** Los 4 primeros momentos de Hu (en log10 con signo), de los momentos centrales. */
    static double[] hu(double m00, double mu20, double mu02, double mu11, double mu30, double mu03, double mu21, double mu12) {
        double n2 = m00 * m00, n25 = Math.pow(m00, 2.5);
        double e20 = mu20 / n2, e02 = mu02 / n2, e11 = mu11 / n2, e30 = mu30 / n25, e03 = mu03 / n25, e21 = mu21 / n25, e12 = mu12 / n25;
        double h1 = e20 + e02;
        double h2 = (e20 - e02) * (e20 - e02) + 4 * e11 * e11;
        double h3 = Math.pow(e30 - 3 * e12, 2) + Math.pow(3 * e21 - e03, 2);
        double h4 = Math.pow(e30 + e12, 2) + Math.pow(e21 + e03, 2);
        double[] h = {h1, h2, h3, h4};
        for (int k = 0; k < 4; k++) h[k] = -Math.signum(h[k]) * Math.log10(Math.abs(h[k]) + 1e-30);
        return h;
    }

    /** Resultado de emparejar regiones: pares {región de b, región de a} y la similitud en la imagen (de b hacia a). */
    public static final class Match {
        public final List<int[]> pairs = new ArrayList<>();
        /** Giro (grados), escala y desplazamiento que llevan la imagen de b a la de a. */
        public double angleDeg, scale, tx, ty;

        /** Dónde cae en a el punto (x, y) de b. */
        public double[] map(double x, double y) {
            double c = Math.cos(Math.toRadians(angleDeg)) * scale, s = Math.sin(Math.toRadians(angleDeg)) * scale;
            return new double[]{c * x - s * y + tx, s * x + c * y + ty};
        }
    }

    /** Diferencia de color (ΔE en Lab) y de forma (Hu) máximas para que dos regiones sean candidatas. */
    static final double MAX_DE = 18, MAX_HU = 0.9;
    /** Error máximo de la disposición, en fracción del tamaño de la silueta de a. */
    static final double MAX_LAYOUT = 0.12;
    /** Parejas coherentes mínimas. */
    static final int MIN_PAIRS = 3;
    /** Fracción mínima de las regiones del cuadro con menos que deben emparejarse. */
    static final double MIN_SHARE = 0.5;
    /** Desvío máximo de la escala de las regiones respecto de la de la silueta, y razón máxima de áreas relativas. */
    static final double MAX_SCALE_DEV = 1.5, MAX_AREA_RATIO = 2;

    /**
     * Empareja las regiones del cuadro {@code b} con las del {@code a}: por
     * color y forma, y que la disposición de las parejas calce con un mismo
     * giro, escala y desplazamiento en la imagen (RANSAC con 2 parejas).
     * Null si no hay al menos {@link #MIN_PAIRS} parejas coherentes: con 2,
     * entre varias regiones siempre hay alguna disposición que calza por azar.
     */
    public static Match match(Set b, Set a) {
        if (b == null || a == null) return null;
        List<int[]> cand = new ArrayList<>();
        double scaleSil = a.size / b.size;
        for (int i = 0; i < b.regions.size(); i++)
            for (int j = 0; j < a.regions.size(); j++) {
                Region rb = b.regions.get(i), ra = a.regions.get(j);
                double de = Math.sqrt(sq(rb.lab[0] - ra.lab[0]) + sq(rb.lab[1] - ra.lab[1]) + sq(rb.lab[2] - ra.lab[2]));
                double dh = 0;
                for (int k = 0; k < 2; k++) dh = Math.max(dh, Math.abs(rb.hu[k] - ra.hu[k]));
                double ar = Math.max(rb.area, ra.area) / Math.max(1e-9, Math.min(rb.area, ra.area));
                if (de < MAX_DE && dh < MAX_HU && ar < MAX_AREA_RATIO) cand.add(new int[]{i, j});
            }
        Match best = null;
        int bestN = MIN_PAIRS - 1;
        double tol = MAX_LAYOUT * a.size;
        for (int p = 0; p < cand.size(); p++)
            for (int r = 0; r < cand.size(); r++) {
                int[] u = cand.get(p), v = cand.get(r);
                if (u[0] == v[0] || u[1] == v[1]) continue;
                Region b1 = b.regions.get(u[0]), b2 = b.regions.get(v[0]), a1 = a.regions.get(u[1]), a2 = a.regions.get(v[1]);
                double bx = b2.x - b1.x, by = b2.y - b1.y, ax = a2.x - a1.x, ay = a2.y - a1.y;
                double lb = Math.hypot(bx, by), la = Math.hypot(ax, ay);
                if (lb < 0.1 * b.size || la < 0.1 * a.size) continue;
                Match m = new Match();
                m.scale = la / lb;
                // Las regiones son parte del objeto: su escala debe seguir a la de la silueta
                if (m.scale > MAX_SCALE_DEV * scaleSil || m.scale < scaleSil / MAX_SCALE_DEV) continue;
                m.angleDeg = Math.toDegrees(Math.atan2(bx * ay - by * ax, bx * ax + by * ay)); // en (−180°, 180°]
                double[] o = m.map(b1.x, b1.y);
                m.tx = a1.x - o[0];
                m.ty = a1.y - o[1];
                // Parejas que calzan con esta disposición (una por región)
                boolean[] usedB = new boolean[b.regions.size()], usedA = new boolean[a.regions.size()];
                for (int[] c : cand) {
                    if (usedB[c[0]] || usedA[c[1]]) continue;
                    Region rb = b.regions.get(c[0]), ra = a.regions.get(c[1]);
                    double[] mp = m.map(rb.x, rb.y);
                    if (Math.hypot(mp[0] - ra.x, mp[1] - ra.y) < tol) {
                        m.pairs.add(c);
                        usedB[c[0]] = true;
                        usedA[c[1]] = true;
                    }
                }
                // Elongadas: su orientación debe girar lo mismo que la disposición (módulo 180°)
                int bad = 0;
                for (int[] c : m.pairs) {
                    Region rb = b.regions.get(c[0]), ra = a.regions.get(c[1]);
                    if (Math.min(rb.elong, ra.elong) < 2) continue;
                    double d = Math.toDegrees(ra.angle - rb.angle) - m.angleDeg;
                    d = ((d % 180) + 270) % 180 - 90;
                    if (Math.abs(d) > 25) bad++;
                }
                if (bad > 0) continue;
                if (m.pairs.size() > bestN && m.pairs.size() >= MIN_SHARE * Math.min(a.regions.size(), b.regions.size())) {
                    bestN = m.pairs.size();
                    best = m;
                }
            }
        return best;
    }

    private static double sq(double v) { return v * v; }

    // ------------------------------------------------------------ color

    /** sRGB → CIELAB (D65). */
    static float[] toLab(int c) {
        double r = lin(((c >> 16) & 255) / 255.0), g = lin(((c >> 8) & 255) / 255.0), b = lin((c & 255) / 255.0);
        double x = (0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.95047, y = 0.2126 * r + 0.7152 * g + 0.0722 * b,
                z = (0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.08883;
        double fx = f(x), fy = f(y), fz = f(z);
        return new float[]{(float) (116 * fy - 16), (float) (500 * (fx - fy)), (float) (200 * (fy - fz))};
    }

    private static double lin(double v) { return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4); }

    private static double f(double t) { return t > 0.008856 ? Math.cbrt(t) : 7.787 * t + 16.0 / 116; }

    static float[][] kmeans(float[][] px, int k, Random rnd) {
        List<float[]> pts = new ArrayList<>();
        for (float[] p : px) if (p != null) pts.add(p);
        int step = Math.max(1, pts.size() / 4000);
        List<float[]> sm = new ArrayList<>();
        for (int i = 0; i < pts.size(); i += step) sm.add(pts.get(i));
        float[][] c = new float[k][];
        // k-means++ simple
        c[0] = sm.get(rnd.nextInt(sm.size())).clone();
        for (int j = 1; j < k; j++) {
            double[] d = new double[sm.size()];
            double tot = 0;
            for (int i = 0; i < sm.size(); i++) {
                double best = Double.MAX_VALUE;
                for (int q = 0; q < j; q++) best = Math.min(best, dist2(sm.get(i), c[q]));
                d[i] = best;
                tot += best;
            }
            double r = rnd.nextDouble() * tot;
            int pick = 0;
            for (; pick < d.length - 1 && (r -= d[pick]) > 0; pick++) { }
            c[j] = sm.get(pick).clone();
        }
        for (int it = 0; it < 12; it++) {
            double[][] acc = new double[k][3];
            int[] cnt = new int[k];
            for (float[] p : sm) {
                int j = nearest(c, p);
                for (int q = 0; q < 3; q++) acc[j][q] += p[q];
                cnt[j]++;
            }
            for (int j = 0; j < k; j++) if (cnt[j] > 0) for (int q = 0; q < 3; q++) c[j][q] = (float) (acc[j][q] / cnt[j]);
        }
        return c;
    }

    static int nearest(float[][] c, float[] p) {
        int best = 0;
        double bd = Double.MAX_VALUE;
        for (int j = 0; j < c.length; j++) {
            double d = dist2(c[j], p);
            if (d < bd) { bd = d; best = j; }
        }
        return best;
    }

    private static double dist2(float[] a, float[] b) {
        double s = 0;
        for (int q = 0; q < 3; q++) s += (a[q] - b[q]) * (a[q] - b[q]);
        return s;
    }
}
