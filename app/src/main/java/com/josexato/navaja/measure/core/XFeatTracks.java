package com.josexato.navaja.measure.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * La parte de XFeat + LighterGlue que no es la red: preparar la imagen,
 * elegir los puntos y sus descriptores con lo que devuelve el modelo, leer
 * los pares del emparejador y unir los pares de cuadros clave en pistas.
 * Las redes corren en el teléfono (TFLite); esto es Java puro para poder
 * probarlo sin Android.
 *
 * <p>Sigue a {@code detectAndCompute} y {@code match_lighterglue} del
 * repositorio de XFeat (verlab/accelerated_features, Apache-2.0): imagen
 * en grises llevada a 512×960 (múltiplo de 32), puntos con supresión de no
 * máximos 5×5 sobre el mapa de puntos (eso ya viene hecho del modelo),
 * puntaje = mapa × confiabilidad, los {@link #N} mejores, descriptores
 * bicúbicos normalizados; pares mutuos con confianza ≥ {@link #MIN_CONF}.
 *
 * <p>Los videos apaisados se trasponen antes de entrar (el modelo es de
 * tamaño fijo y vertical); todos los cuadros de un video se trasponen
 * igual, así que los descriptores se comparan entre sí sin problema.
 */
public final class XFeatTracks {

    private XFeatTracks() { }

    /** Entrada del modelo (vertical) y puntos por cuadro (fijo: el emparejador es de tamaño fijo). */
    public static final int IN_W = 512, IN_H = 960, N = 1024, DIM = 64;
    static final int GW = IN_W / 8, GH = IN_H / 8;
    /** Mapa de puntos mínimo (el de XFeat) y confianza mínima de un par (la de {@code match_lighterglue}). */
    static final float THRESHOLD = 0.05f;
    public static final float MIN_CONF = 0.1f;
    /** Cuántos cuadros clave anteriores se emparejan con cada uno (1 = sólo el anterior). */
    public static final int LOOK_BACK = 2;
    /** Distancia a la recta epipolar (px, Sampson) por encima de la cual un par se descarta: las poses de la hoja ya se conocen. */
    static final double MAX_EPIPOLAR = 2;

    /** Puntos y descriptores de un cuadro. */
    public static final class Frame {
        public final int frame;
        /** Puntos válidos (los demás, hasta {@link #N}, son relleno). */
        public int n;
        /** Posición en el cuadro (px) y color del cuadro ahí. */
        public final float[] x = new float[N], y = new float[N];
        public final int[] color = new int[N];
        /** Para el emparejador: posición normalizada (N×2) y descriptores unitarios (N×64). */
        public final float[] glueXY = new float[2 * N], desc = new float[DIM * N];

        Frame(int frame) { this.frame = frame; }
    }

    /** Si el cuadro se traspone para entrar al modelo (los apaisados). */
    static boolean transposed(int w, int h) { return w > h; }

    /**
     * La imagen para el modelo: grises (promedio de R, G y B, 0..255) en
     * {@link #IN_W}×{@link #IN_H}, con interpolación bilineal como
     * {@code F.interpolate(align_corners=False)} (en {@code out}, por filas).
     */
    public static void input(int[] argb, int w, int h, float[] out) {
        boolean tr = transposed(w, h);
        int pw = tr ? h : w, ph = tr ? w : h;
        double sx = (double) pw / IN_W, sy = (double) ph / IN_H;
        int[] x0 = new int[IN_W], x1 = new int[IN_W];
        float[] fx = new float[IN_W];
        for (int u = 0; u < IN_W; u++) {
            double s = Math.max(0, (u + 0.5) * sx - 0.5);
            x0[u] = Math.min((int) s, pw - 1);
            x1[u] = Math.min(x0[u] + 1, pw - 1);
            fx[u] = (float) (s - x0[u]);
        }
        for (int v = 0; v < IN_H; v++) {
            double s = Math.max(0, (v + 0.5) * sy - 0.5);
            int y0 = Math.min((int) s, ph - 1), y1 = Math.min(y0 + 1, ph - 1);
            float fy = (float) (s - y0);
            for (int u = 0; u < IN_W; u++) {
                float a = gray(argb, w, tr, x0[u], y0), b = gray(argb, w, tr, x1[u], y0);
                float c = gray(argb, w, tr, x0[u], y1), d = gray(argb, w, tr, x1[u], y1);
                float top = a + (b - a) * fx[u], bot = c + (d - c) * fx[u];
                out[v * IN_W + u] = top + (bot - top) * fy;
            }
        }
    }

    /** Gris del píxel (px, py) de la imagen vertical (traspuesta si {@code tr}). */
    private static float gray(int[] argb, int w, boolean tr, int px, int py) {
        int c = tr ? argb[px * w + py] : argb[py * w + px];
        return (((c >> 16) & 255) + ((c >> 8) & 255) + (c & 255)) / 3f;
    }

    /**
     * Los puntos del cuadro con lo que devolvió el modelo: {@code desc}
     * (GH×GW×64, unitarios), {@code heat} (IN_H×IN_W, ya con supresión de no
     * máximos: 0 fuera de los máximos locales) y {@code rel} (GH×GW,
     * confiabilidad). Si hay menos de {@link #N} puntos sobre el umbral, se
     * completan con los máximos locales más débiles (el emparejador es de
     * tamaño fijo).
     */
    public static Frame select(int frame, float[] desc, float[] heat, float[] rel, int[] argb, int w, int h) {
        boolean tr = transposed(w, h);
        int pw = tr ? h : w, ph = tr ? w : h;
        int cand = 0;
        for (float v : heat) if (v > 0) cand++;
        int[] pos = new int[cand];
        double[] score = new double[cand];
        int k = 0;
        for (int i = 0; i < heat.length; i++) {
            if (heat[i] <= 0) continue;
            int u = i % IN_W, v = i / IN_W;
            double s = heat[i] * bilinear(rel, GW, GH, gridX(u), gridY(v));
            // Los que pasan el umbral van primero; el resto sólo rellena
            score[k] = heat[i] > THRESHOLD ? s + 10 : s;
            pos[k++] = i;
        }
        Integer[] order = new Integer[cand];
        for (int i = 0; i < cand; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Double.compare(score[b], score[a]));
        Frame f = new Frame(frame);
        f.n = Math.min(N, cand);
        double half = Math.max(pw, ph) / 2.0;
        float[] tmp = new float[DIM];
        for (int i = 0; i < f.n; i++) {
            int p = pos[order[i]], u = p % IN_W, v = p / IN_W;
            // Como XFeat: la posición en la grilla de 512×960 escalada al cuadro
            double px = u * (double) pw / IN_W, py = v * (double) ph / IN_H;
            f.x[i] = (float) (tr ? py : px);
            f.y[i] = (float) (tr ? px : py);
            int cx = Math.min(w - 1, Math.max(0, Math.round(f.x[i]))), cy = Math.min(h - 1, Math.max(0, Math.round(f.y[i])));
            f.color[i] = argb[cy * w + cx] | 0xFF000000;
            f.glueXY[2 * i] = (float) ((px - pw / 2.0) / half);
            f.glueXY[2 * i + 1] = (float) ((py - ph / 2.0) / half);
            bicubic(desc, gridX(u), gridY(v), tmp);
            double nn = 0;
            for (float t : tmp) nn += t * t;
            nn = Math.max(1e-12, Math.sqrt(nn));
            for (int d = 0; d < DIM; d++) f.desc[i * DIM + d] = (float) (tmp[d] / nn);
        }
        // Relleno: lejos de la imagen y sin descriptor (no empareja con puntos reales)
        for (int i = f.n; i < N; i++) {
            f.glueXY[2 * i] = 1000;
            f.glueXY[2 * i + 1] = 1000;
        }
        return f;
    }

    /** Posición en la grilla de 1/8 que usa XFeat: normaliza con (W − 1) y muestrea con align_corners=False. */
    static double gridX(int u) { return (double) u / (IN_W - 1) * GW - 0.5; }

    static double gridY(int v) { return (double) v / (IN_H - 1) * GH - 0.5; }

    /** Bilineal con ceros fuera (grid_sample). */
    static double bilinear(float[] m, int w, int h, double x, double y) {
        int x0 = (int) Math.floor(x), y0 = (int) Math.floor(y);
        double fx = x - x0, fy = y - y0;
        return at(m, w, h, x0, y0) * (1 - fx) * (1 - fy) + at(m, w, h, x0 + 1, y0) * fx * (1 - fy)
                + at(m, w, h, x0, y0 + 1) * (1 - fx) * fy + at(m, w, h, x0 + 1, y0 + 1) * fx * fy;
    }

    private static double at(float[] m, int w, int h, int x, int y) {
        return x < 0 || y < 0 || x >= w || y >= h ? 0 : m[y * w + x];
    }

    /** Bicúbica (A = −0,75, ceros fuera) de los descriptores GH×GW×64 en (x, y). */
    static void bicubic(float[] d, double x, double y, float[] out) {
        int x0 = (int) Math.floor(x), y0 = (int) Math.floor(y);
        double[] wx = cubic(x - x0), wy = cubic(y - y0);
        Arrays.fill(out, 0);
        for (int i = 0; i < 4; i++) {
            int yy = y0 - 1 + i;
            if (yy < 0 || yy >= GH) continue;
            for (int j = 0; j < 4; j++) {
                int xx = x0 - 1 + j;
                if (xx < 0 || xx >= GW) continue;
                float wgt = (float) (wx[j] * wy[i]);
                int o = (yy * GW + xx) * DIM;
                for (int c = 0; c < DIM; c++) out[c] += wgt * d[o + c];
            }
        }
    }

    private static double[] cubic(double t) {
        final double a = -0.75;
        return new double[]{c2(t + 1, a), c1(t, a), c1(1 - t, a), c2(2 - t, a)};
    }

    private static double c1(double x, double a) { return ((a + 2) * x - (a + 3)) * x * x + 1; }

    private static double c2(double x, double a) { return ((a * x - 5 * a) * x + 8 * a) * x - 4 * a; }

    /**
     * Pares del emparejador: {@code m0[i]} el mejor de b para el punto i de
     * a, {@code m1[j]} el mejor de a para j, {@code v0[i]} el log-puntaje de
     * i. Se quedan los mutuos, entre puntos reales y con confianza ≥
     * {@link #MIN_CONF}. Devuelve {i, j, confianza × 1000} por par.
     */
    public static int[][] pairs(Frame a, Frame b, int[] m0, int[] m1, float[] v0) {
        List<int[]> out = new ArrayList<>();
        for (int i = 0; i < a.n; i++) {
            int j = m0[i];
            if (j < 0 || j >= b.n || m1[j] != i) continue;
            double conf = Math.exp(v0[i]);
            if (conf < MIN_CONF) continue;
            out.add(new int[]{i, j, (int) Math.round(1000 * Math.min(1, conf))});
        }
        return out.toArray(new int[0][]);
    }

    /**
     * Une los pares de cuadros clave en pistas: cada punto de cada cuadro es
     * un nodo y cada par (que pase la geometría epipolar con las poses de la
     * hoja) es una arista. Los cuadros se emparejan con el siguiente y
     * también con otros más adelante (pistas más largas, con más ángulo entre
     * vistas). Las aristas se unen de la más confiable a la menos: primero
     * las de cuadros más cercanos y, entre ellas, las de mayor confianza; una
     * unión que dejaría dos puntos del mismo cuadro en una pista (pares
     * contradictorios) no se hace, así una arista equivocada no arruina la
     * pista entera.
     */
    public static final class Linker {
        private final Map<Integer, float[][]> pts = new HashMap<>();
        private final Map<Integer, int[]> colors = new HashMap<>();
        private final Map<Integer, Integer> base = new HashMap<>();
        private int[] parent = new int[0], frameOf = new int[0];
        private int nodes;
        /** Aristas: {nodo a, nodo b, separación entre cuadros clave, confianza × 1000}. */
        private final List<int[]> edges = new ArrayList<>();
        /** Pares leídos y los que pasaron la geometría epipolar. */
        public int raw, kept;
        /** Pares de cuadros emparejados; uniones descartadas por contradictorias. */
        public int framePairs, conflicts;

        private int node(Frame f) {
            Integer b = base.get(f.frame);
            if (b == null) {
                b = nodes;
                base.put(f.frame, b);
                pts.put(f.frame, new float[][]{Arrays.copyOf(f.x, f.n), Arrays.copyOf(f.y, f.n)});
                colors.put(f.frame, Arrays.copyOf(f.color, f.n));
                nodes += f.n;
                int old = parent.length;
                if (nodes > old) {
                    parent = Arrays.copyOf(parent, Math.max(nodes, old * 2));
                    frameOf = Arrays.copyOf(frameOf, parent.length);
                    for (int i = old; i < parent.length; i++) parent[i] = i;
                }
                for (int i = b; i < nodes; i++) frameOf[i] = f.frame;
            }
            return b;
        }

        private int find(int i) {
            while (parent[i] != i) {
                parent[i] = parent[parent[i]];
                i = parent[i];
            }
            return i;
        }

        /** Agrega los pares entre a y el cuadro clave siguiente b (poses de la hoja pa y pb; lente l). */
        public void add(Frame a, Frame b, int[][] pairs, Geometry.Pose pa, Geometry.Pose pb, Lens l) {
            add(a, b, pairs, pa, pb, l, 1);
        }

        /** Igual, con b a {@code gap} cuadros clave de a (1 = el siguiente). */
        public void add(Frame a, Frame b, int[][] pairs, Geometry.Pose pa, Geometry.Pose pb, Lens l, int gap) {
            int ba = node(a), bb = node(b);
            framePairs++;
            // Matriz esencial de a hacia b: E = [t]× R
            double[] r = Lin.mul(pb.r, Lin.tr(pa.r));
            double[] t = Lin.sub(pb.t, Lin.mulV(r, pa.t));
            double[] tx = {0, -t[2], t[1], t[2], 0, -t[0], -t[1], t[0], 0};
            double[] e = Lin.mul(tx, r);
            for (int[] p : pairs) {
                raw++;
                double[] x0 = l.normalize(a.x[p[0]], a.y[p[0]]), x1 = l.normalize(b.x[p[1]], b.y[p[1]]);
                if (sampson(e, x0, x1) * l.f > MAX_EPIPOLAR) continue;
                kept++;
                edges.add(new int[]{ba + p[0], bb + p[1], gap, p.length > 2 ? p[2] : 1000});
            }
        }

        /** Distancia de Sampson (en coordenadas normalizadas). */
        static double sampson(double[] e, double[] a, double[] b) {
            double[] ea = {e[0] * a[0] + e[1] * a[1] + e[2], e[3] * a[0] + e[4] * a[1] + e[5], e[6] * a[0] + e[7] * a[1] + e[8]};
            double[] etb = {e[0] * b[0] + e[3] * b[1] + e[6], e[1] * b[0] + e[4] * b[1] + e[7]};
            double num = b[0] * ea[0] + b[1] * ea[1] + ea[2];
            double den = ea[0] * ea[0] + ea[1] * ea[1] + etb[0] * etb[0] + etb[1] * etb[1];
            return Math.abs(num) / Math.sqrt(Math.max(den, 1e-24));
        }

        /** Une las aristas (de la más confiable a la menos, sin contradicciones). */
        private void link() {
            for (int i = 0; i < nodes; i++) parent[i] = i;
            conflicts = 0;
            Map<Integer, java.util.Set<Integer>> framesOf = new HashMap<>();
            List<int[]> es = new ArrayList<>(edges);
            es.sort((p, q) -> p[2] != q[2] ? Integer.compare(p[2], q[2]) : Integer.compare(q[3], p[3]));
            for (int[] ed : es) {
                int u = find(ed[0]), v = find(ed[1]);
                if (u == v) continue;
                java.util.Set<Integer> fu = framesOf.get(u), fv = framesOf.get(v);
                if (fu == null) { fu = new java.util.HashSet<>(); fu.add(frameOf[u]); }
                if (fv == null) { fv = new java.util.HashSet<>(); fv.add(frameOf[v]); }
                // El conjunto chico se recorre sobre el grande
                java.util.Set<Integer> small = fu.size() <= fv.size() ? fu : fv, big = small == fu ? fv : fu;
                boolean clash = false;
                for (int f : small) if (big.contains(f)) { clash = true; break; }
                if (clash) { conflicts++; continue; }
                big.addAll(small);
                int root = small == fu ? v : u, child = root == u ? v : u;
                parent[child] = root;
                framesOf.remove(child);
                framesOf.put(root, big);
            }
        }

        /** Las pistas con al menos {@code minObs} cuadros. */
        public List<Sfm.Track> tracks(int minObs) {
            link();
            Map<Integer, List<int[]>> groups = new HashMap<>();
            for (Map.Entry<Integer, Integer> fe : base.entrySet()) {
                int fr = fe.getKey(), b0 = fe.getValue(), n = pts.get(fr)[0].length;
                for (int i = 0; i < n; i++) {
                    int root = find(b0 + i);
                    groups.computeIfAbsent(root, z -> new ArrayList<>()).add(new int[]{fr, i});
                }
            }
            List<Sfm.Track> out = new ArrayList<>();
            for (List<int[]> g : groups.values()) {
                if (g.size() < minObs) continue;
                g.sort((p, q) -> Integer.compare(p[0], q[0]));
                boolean dup = false;
                for (int i = 1; i < g.size() && !dup; i++) dup = g.get(i)[0] == g.get(i - 1)[0];
                if (dup) continue;
                int[] fr = new int[g.size()];
                float[] xs = new float[g.size()], ys = new float[g.size()];
                for (int i = 0; i < fr.length; i++) {
                    int[] p = g.get(i);
                    fr[i] = p[0];
                    xs[i] = pts.get(p[0])[0][p[1]];
                    ys[i] = pts.get(p[0])[1][p[1]];
                }
                int[] c0 = g.get(0);
                out.add(Sfm.Track.sparse(fr, xs, ys, colors.get(c0[0])[c0[1]]));
            }
            out.sort((p, q) -> Integer.compare(p.start, q.start));
            return out;
        }
    }
}
