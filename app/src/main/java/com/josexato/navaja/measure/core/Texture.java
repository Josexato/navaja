package com.josexato.navaja.measure.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Mapeo UV de una malla con las fotos clave: cada triángulo toma su color
 * de la foto que mejor lo ve (de frente, grande y sin nada delante), los
 * triángulos vecinos con la misma foto forman un "parche", y los parches
 * recortados de sus fotos se acomodan en una sola imagen de textura. Es
 * la idea de la texturización multivista (como MVS-Texturing), simplificada:
 * sin igualar el color entre fotos, así que puede verse una costura donde
 * cambia la foto.
 */
public final class Texture {

    private Texture() { }

    /** Fotos clave: el ARGB de la cámara {@code c} ({@code r.width}×{@code r.height}), o null si no hay. */
    public interface Photos {
        int[] argb(int camera);
    }

    /** Resolución de la prueba de visibilidad (fracción del cuadro). */
    static final int ZBUF_DIV = 4;
    /** Tolerancia de profundidad para la visibilidad (fracción de la distancia). */
    static final double DEPTH_EPS = 0.01;
    /** Un triángulo se pasa a la foto de sus vecinos si ahí se ve al menos así de bien (para tener menos costuras). */
    static final double KEEP_NEIGHBOR = 0.6;
    /** Borde (px) alrededor de cada parche en la textura: evita que el filtrado mezcle parches. */
    static final int PAD = 2;

    /** La textura: imagen, UV de cada esquina de cada triángulo (u, v en [0, 1], v hacia arriba como en OBJ) y estadísticas. */
    public static final class Result {
        public int[] atlas;
        public int width, height;
        /** 6 por triángulo: u0 v0 u1 v1 u2 v2. */
        public float[] uv;
        /** Foto de cada triángulo (−1 = ninguna lo ve: gris). */
        public int[] camera;
        public int charts, unseen, photosUsed;
        /** Escala aplicada a los recortes para que entren en la textura (1 = tamaño de la foto). */
        public double scale;
    }

    /**
     * @param verts  vértices de la malla en coordenadas del modelo (las de {@code r.points})
     * @param tri    3 índices por triángulo; la normal hacia afuera según la regla de la mano derecha
     * @param usable cámaras cuyas fotos se pueden usar (p. ej. con silueta válida); null = todas
     * @param maxSide lado máximo de la textura (px)
     */
    public static Result build(Sfm.Result r, double[][] verts, int[] tri, boolean[] usable, Photos photos, int maxSide) {
        int nt = tri.length / 3, nc = r.poses.size();
        double f = r.focal, cx = r.width / 2.0, cy = r.height / 2.0;
        // Hacia afuera: si el volumen con signo es negativo, los triángulos giran al revés
        double vol = 0;
        for (int t = 0; t < nt; t++)
            vol += Lin.dot(verts[tri[3 * t]], Lin.cross(verts[tri[3 * t + 1]], verts[tri[3 * t + 2]]));
        double out = vol >= 0 ? 1 : -1;
        // 1) Qué tan bien ve cada cámara a cada triángulo: área proyectada × coseno, si es visible
        float[][] score = new float[nt][nc];
        for (int c = 0; c < nc; c++) {
            if (usable != null && !usable[c]) continue;
            Geometry.Pose p = r.poses.get(c);
            double[][] cam = new double[verts.length][];
            for (int v = 0; v < verts.length; v++) cam[v] = p.apply(verts[v]);
            float[] zb = zbuffer(cam, tri, f, cx, cy, r.width, r.height);
            int zw = r.width / ZBUF_DIV, zh = r.height / ZBUF_DIV;
            double[] eye = p.center();
            for (int t = 0; t < nt; t++) {
                double[] a = cam[tri[3 * t]], b = cam[tri[3 * t + 1]], d = cam[tri[3 * t + 2]];
                if (a[2] <= 0 || b[2] <= 0 || d[2] <= 0) continue;
                double[] ua = proj(a, f, cx, cy), ub = proj(b, f, cx, cy), ud = proj(d, f, cx, cy);
                if (!inImage(ua, r) || !inImage(ub, r) || !inImage(ud, r)) continue;
                // De frente: normal (en el mundo) hacia la cámara
                double[] wa = verts[tri[3 * t]], wb = verts[tri[3 * t + 1]], wd = verts[tri[3 * t + 2]];
                double[] n = Lin.cross(Lin.sub(wb, wa), Lin.sub(wd, wa));
                double nl = Lin.norm(n);
                if (nl < 1e-18) continue;
                double[] ctr = Lin.scale(Lin.add(Lin.add(wa, wb), wd), 1.0 / 3);
                double[] toEye = Lin.unit(Lin.sub(eye, ctr));
                double cos = out * Lin.dot(n, toEye) / nl;
                if (cos <= 0.1) continue;
                // Visible: el centro no está detrás de otro triángulo
                double[] cc = {(a[0] + b[0] + d[0]) / 3, (a[1] + b[1] + d[1]) / 3, (a[2] + b[2] + d[2]) / 3};
                double[] uc = proj(cc, f, cx, cy);
                int zx = (int) (uc[0] / ZBUF_DIV), zy = (int) (uc[1] / ZBUF_DIV);
                if (zx < 0 || zy < 0 || zx >= zw || zy >= zh || cc[2] > zb[zy * zw + zx] * (1 + DEPTH_EPS)) continue;
                double area = Math.abs((ub[0] - ua[0]) * (ud[1] - ua[1]) - (ub[1] - ua[1]) * (ud[0] - ua[0])) / 2;
                score[t][c] = (float) (area * cos);
            }
        }
        // 2) La mejor foto de cada triángulo y, para tener parches grandes, la de los vecinos si la ve casi igual de bien
        int[] label = new int[nt];
        for (int t = 0; t < nt; t++) label[t] = argmax(score[t]);
        int[][] adj = adjacency(tri);
        for (int pass = 0; pass < 3; pass++)
            for (int t = 0; t < nt; t++) {
                if (label[t] < 0) continue;
                Map<Integer, Integer> votes = new HashMap<>();
                for (int u : adj[t]) if (u >= 0 && label[u] >= 0) votes.merge(label[u], 1, Integer::sum);
                int best = label[t], bv = votes.getOrDefault(best, 0);
                for (Map.Entry<Integer, Integer> e : votes.entrySet())
                    if (e.getValue() > bv && score[t][e.getKey()] >= KEEP_NEIGHBOR * score[t][label[t]]) { best = e.getKey(); bv = e.getValue(); }
                label[t] = best;
            }
        // 3) Parches: triángulos vecinos con la misma foto; su rectángulo en la foto
        int[] chart = new int[nt];
        Arrays.fill(chart, -1);
        List<int[]> rects = new ArrayList<>(); // {foto, x0, y0, x1, y1}
        List<List<Integer>> members = new ArrayList<>();
        ArrayDeque<Integer> q = new ArrayDeque<>();
        Result res = new Result();
        res.uv = new float[6 * nt];
        res.camera = label;
        double[][] px = new double[3 * nt][]; // posición en su foto de cada esquina
        for (int s = 0; s < nt; s++) {
            if (chart[s] >= 0 || label[s] < 0) continue;
            int id = rects.size(), c = label[s];
            Geometry.Pose p = r.poses.get(c);
            double x0 = 1e18, y0 = 1e18, x1 = -1e18, y1 = -1e18;
            List<Integer> mem = new ArrayList<>();
            chart[s] = id;
            q.add(s);
            while (!q.isEmpty()) {
                int t = q.poll();
                mem.add(t);
                for (int e = 0; e < 3; e++) {
                    double[] u = proj(p.apply(verts[tri[3 * t + e]]), f, cx, cy);
                    px[3 * t + e] = u;
                    x0 = Math.min(x0, u[0]);
                    y0 = Math.min(y0, u[1]);
                    x1 = Math.max(x1, u[0]);
                    y1 = Math.max(y1, u[1]);
                }
                for (int u : adj[t]) if (u >= 0 && chart[u] < 0 && label[u] == c) { chart[u] = id; q.add(u); }
            }
            rects.add(new int[]{c, (int) Math.floor(x0) - PAD, (int) Math.floor(y0) - PAD, (int) Math.ceil(x1) + PAD, (int) Math.ceil(y1) + PAD});
            members.add(mem);
        }
        res.charts = rects.size();
        // 4) Acomodar los parches (por estantes, de más alto a más bajo); si no entran, achicarlos a todos por igual
        double area = 64;
        for (int[] rc : rects) area += (double) (rc[3] - rc[1]) * (rc[4] - rc[2]);
        double scale = Math.min(1, maxSide / Math.sqrt(area * 1.15));
        int[][] at;
        int W, H;
        while (true) {
            W = Math.max(16, Math.min(maxSide, (int) Math.ceil(Math.sqrt(area * 1.15) * scale)));
            at = new int[rects.size()][];
            Integer[] order = new Integer[rects.size()];
            for (int k = 0; k < order.length; k++) order[k] = k;
            final double sc = scale;
            Arrays.sort(order, (a, b) -> Integer.compare(h(rects.get(b), sc), h(rects.get(a), sc)));
            int x = 8 + PAD, y = 0, shelf = 8 + PAD; // el primer rincón (8×8) es el gris de lo que no se vio
            for (int k : order) {
                int rw = w(rects.get(k), scale), rh = h(rects.get(k), scale);
                if (x + rw > W) { x = 0; y += shelf; shelf = 0; }
                at[k] = new int[]{x, y};
                x += rw;
                shelf = Math.max(shelf, rh);
            }
            H = y + shelf;
            if (H <= maxSide) break;
            scale *= Math.sqrt((double) maxSide / H) * 0.97;
        }
        res.scale = scale;
        res.width = W;
        res.height = Math.max(16, H);
        res.atlas = new int[res.width * res.height];
        Arrays.fill(res.atlas, 0xFF808080);
        // 5) Copiar cada parche de su foto (una foto a la vez) y poner sus UV
        boolean[] used = new boolean[nc];
        for (int c = 0; c < nc; c++) {
            boolean any = false;
            for (int[] rc : rects) any |= rc[0] == c;
            if (!any) continue;
            int[] img = photos.argb(c);
            used[c] = true;
            for (int k = 0; k < rects.size(); k++) {
                int[] rc = rects.get(k);
                if (rc[0] != c) continue;
                int rw = w(rc, scale), rh = h(rc, scale);
                for (int yy = 0; yy < rh; yy++)
                    for (int xx = 0; xx < rw; xx++) {
                        int ax = at[k][0] + xx, ay = at[k][1] + yy;
                        if (ax >= res.width || ay >= res.height) continue;
                        double sx = rc[1] + (xx + 0.5) / scale - 0.5, sy = rc[2] + (yy + 0.5) / scale - 0.5;
                        res.atlas[ay * res.width + ax] = img == null ? 0xFF808080 : bilinear(img, r.width, r.height, sx, sy);
                    }
                for (int t : members.get(k))
                    for (int e = 0; e < 3; e++) {
                        double[] u = px[3 * t + e];
                        double ax = at[k][0] + (u[0] - rc[1]) * scale, ay = at[k][1] + (u[1] - rc[2]) * scale;
                        res.uv[6 * t + 2 * e] = (float) (ax / res.width);
                        res.uv[6 * t + 2 * e + 1] = (float) (1 - ay / res.height);
                    }
            }
        }
        for (boolean b : used) if (b) res.photosUsed++;
        // Lo que ninguna foto ve: al centro del rincón gris
        for (int t = 0; t < nt; t++) {
            if (label[t] >= 0) continue;
            res.unseen++;
            for (int e = 0; e < 3; e++) {
                res.uv[6 * t + 2 * e] = 4f / res.width;
                res.uv[6 * t + 2 * e + 1] = 1 - 4f / res.height;
            }
        }
        return res;
    }

    private static int w(int[] rc, double s) { return Math.max(1, (int) Math.ceil((rc[3] - rc[1]) * s)); }

    private static int h(int[] rc, double s) { return Math.max(1, (int) Math.ceil((rc[4] - rc[2]) * s)); }

    private static double[] proj(double[] x, double f, double cx, double cy) { return new double[]{f * x[0] / x[2] + cx, f * x[1] / x[2] + cy}; }

    private static boolean inImage(double[] u, Sfm.Result r) { return u[0] >= 1 && u[1] >= 1 && u[0] < r.width - 1 && u[1] < r.height - 1; }

    private static int argmax(float[] s) {
        int b = -1;
        for (int c = 0; c < s.length; c++) if (s[c] > 0 && (b < 0 || s[c] > s[b])) b = c;
        return b;
    }

    /** Profundidad más cercana por zona de {@code ZBUF_DIV}×{@code ZBUF_DIV} px (rasterizando los triángulos). */
    static float[] zbuffer(double[][] cam, int[] tri, double f, double cx, double cy, int w, int h) {
        int zw = w / ZBUF_DIV, zh = h / ZBUF_DIV;
        float[] zb = new float[zw * zh];
        Arrays.fill(zb, Float.MAX_VALUE);
        double k = 1.0 / ZBUF_DIV;
        for (int t = 0; t < tri.length; t += 3) {
            double[] a = cam[tri[t]], b = cam[tri[t + 1]], c = cam[tri[t + 2]];
            if (a[2] <= 0 || b[2] <= 0 || c[2] <= 0) continue;
            double ax = (f * a[0] / a[2] + cx) * k, ay = (f * a[1] / a[2] + cy) * k;
            double bx = (f * b[0] / b[2] + cx) * k, by = (f * b[1] / b[2] + cy) * k;
            double qx = (f * c[0] / c[2] + cx) * k, qy = (f * c[1] / c[2] + cy) * k;
            double den = (by - qy) * (ax - qx) + (qx - bx) * (ay - qy);
            if (Math.abs(den) < 1e-12) continue;
            int x0 = Math.max(0, (int) Math.floor(Math.min(ax, Math.min(bx, qx)))), x1 = Math.min(zw - 1, (int) Math.ceil(Math.max(ax, Math.max(bx, qx))));
            int y0 = Math.max(0, (int) Math.floor(Math.min(ay, Math.min(by, qy)))), y1 = Math.min(zh - 1, (int) Math.ceil(Math.max(ay, Math.max(by, qy))));
            for (int y = y0; y <= y1; y++)
                for (int x = x0; x <= x1; x++) {
                    double px = x + 0.5, py = y + 0.5;
                    double l1 = ((by - qy) * (px - qx) + (qx - bx) * (py - qy)) / den;
                    double l2 = ((qy - ay) * (px - qx) + (ax - qx) * (py - qy)) / den;
                    double l3 = 1 - l1 - l2;
                    if (l1 < -0.01 || l2 < -0.01 || l3 < -0.01) continue;
                    // Profundidad interpolada en perspectiva (1/z es lineal en la imagen)
                    double z = 1 / (l1 / a[2] + l2 / b[2] + l3 / c[2]);
                    int i = y * zw + x;
                    if (z < zb[i]) zb[i] = (float) z;
                }
        }
        return zb;
    }

    /** Para cada triángulo, sus 3 vecinos por arista (−1 si no hay). */
    static int[][] adjacency(int[] tri) {
        int nt = tri.length / 3;
        Map<Long, Integer> first = new HashMap<>();
        int[][] adj = new int[nt][3];
        for (int[] a : adj) Arrays.fill(a, -1);
        for (int t = 0; t < nt; t++)
            for (int e = 0; e < 3; e++) {
                int a = tri[3 * t + e], b = tri[3 * t + (e + 1) % 3];
                long key = (long) Math.min(a, b) << 32 | Math.max(a, b);
                Integer o = first.remove(key);
                if (o == null) first.put(key, t * 3 + e);
                else {
                    adj[t][e] = o / 3;
                    adj[o / 3][o % 3] = t;
                }
            }
        return adj;
    }

    static int bilinear(int[] img, int w, int h, double x, double y) {
        x = Math.max(0, Math.min(w - 1.001, x));
        y = Math.max(0, Math.min(h - 1.001, y));
        int x0 = (int) x, y0 = (int) y;
        double fx = x - x0, fy = y - y0;
        int c00 = img[y0 * w + x0], c10 = img[y0 * w + x0 + 1], c01 = img[(y0 + 1) * w + x0], c11 = img[(y0 + 1) * w + x0 + 1];
        int out = 0xFF000000;
        for (int sh = 0; sh <= 16; sh += 8) {
            double v = ((c00 >> sh) & 255) * (1 - fx) * (1 - fy) + ((c10 >> sh) & 255) * fx * (1 - fy)
                    + ((c01 >> sh) & 255) * (1 - fx) * fy + ((c11 >> sh) & 255) * fx * fy;
            out |= ((int) Math.round(v) & 255) << sh;
        }
        return out;
    }
}
