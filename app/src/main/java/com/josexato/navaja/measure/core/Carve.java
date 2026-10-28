package com.josexato.navaja.measure.core;

import com.josexato.navaja.scan3d.core.Carver;

import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * Segunda opinión de la medida: el volumen del objeto tallado con sus
 * siluetas (una por cuadro clave, de un segmentador) y con el espacio libre
 * que dejan los puntos 3D (delante del punto más cercano que vio cada cámara
 * no hay nada). Las siluetas dan el contorno, incluso en zonas lisas sin puntos;
 * el espacio libre corrige el frente, que con un recorrido sólo horizontal
 * ninguna silueta recorta. Es independiente de la medida por puntos: si las
 * dos coinciden, la medida es confiable.
 */
public final class Carve {

    private Carve() { }

    /** Vóxeles por lado del eje más largo de la grilla. */
    static final int N = 100;
    /**
     * Cuadros en los que un vóxel puede caer fuera de la silueta y aun así
     * quedar (errores de segmentación): al menos 2, o el 10 % de los que lo
     * ven. Con 87 fotos y algunas movidas, 2 fijos dejaban que una sola
     * silueta mala por zona afinara el objeto a la mitad.
     */
    static final int TOL = 2;
    static final double TOL_FRACTION = 0.1;
    /** Zonas de la imagen (px) para la profundidad del punto más cercano que vio cada cámara. */
    static final int CELL = 24;
    /** Cámaras que deben ver vacío un vóxel para quitarlo (un punto 3D malo no alcanza). */
    static final int MIN_FREE = 2;

    public static final class Result {
        /** Ocupación en ejes de la caja: i = ancho, j = alto, k = profundidad (unidades del modelo). */
        public Carver.Grid grid;
        /** Medidas (percentiles 1–99) en unidades del modelo; {@code front} = de la pared/mesa al punto más alejado. */
        public double ancho, alto, prof, front, volume;
        /** Cuadros con silueta usados. */
        public int views;
        /** Centro y ejes (ancho, alto, profundidad) de la grilla: los de la caja al tallar (la caja puede cambiar después). */
        public double[] center;
        public double[][] axes;
    }

    /**
     * @param masks silueta de cada cámara de {@code r} (mismo orden que
     *              {@code r.poses}), {@code mw}×{@code mh} píxeles, 1 = objeto;
     *              null si no hay silueta para esa cámara
     * @return null si hay menos de 8 siluetas o no queda volumen
     */
    public static Result carve(Sfm.Result r, Measure.Box b, byte[][] masks, int mw, int mh) {
        int views = 0;
        for (byte[] m : masks) if (m != null) views++;
        if (views < 8) return null;
        double[][] axes = {b.axisW, b.axisH, b.axisD};
        double[] ext = {b.ancho, b.alto, b.prof};
        // Eje de la normal del plano de apoyo: desde el plano hasta bien delante; los otros, ±75 %
        int na = 0;
        for (int a = 1; a < 3; a++) if (Math.abs(Lin.dot(axes[a], b.planeN)) > Math.abs(Lin.dot(axes[na], b.planeN))) na = a;
        double side = Math.max(b.ancho, Math.max(b.alto, b.prof));
        double[] lo = new double[3], hi = new double[3];
        for (int a = 0; a < 3; a++) {
            lo[a] = -0.75 * ext[a];
            hi[a] = 0.75 * ext[a];
        }
        double sgn = Math.signum(Lin.dot(axes[na], b.planeN));
        double d0 = Lin.dot(Lin.sub(b.center, b.planeP), b.planeN); // centro de la caja → plano
        double pLo = -d0 - 0.05 * side, pHi = -d0 + 2.0 * ext[na];
        lo[na] = sgn > 0 ? pLo : -pHi;
        hi[na] = sgn > 0 ? pHi : -pLo;
        double step = 1.5 * side / N;
        Carver.Grid g = new Carver.Grid();
        g.nx = (int) Math.ceil((hi[0] - lo[0]) / step);
        g.ny = (int) Math.ceil((hi[1] - lo[1]) / step);
        g.nz = (int) Math.ceil((hi[2] - lo[2]) / step);
        g.x0 = lo[0];
        g.y0 = lo[1];
        g.z0 = lo[2];
        g.step = step;
        int n = g.nx * g.ny * g.nz;
        short[] bad = new short[n], seen = new short[n], free = new short[n];
        double sx = (double) mw / r.width, sy = (double) mh / r.height;
        double cx = r.width / 2.0, cy = r.height / 2.0;
        int gw = (r.width + CELL - 1) / CELL, gh = (r.height + CELL - 1) / CELL;
        for (int c = 0; c < r.poses.size(); c++) {
            byte[] m = masks[c];
            if (m == null) continue;
            Geometry.Pose p = r.poses.get(c);
            double[] depth = nearestDepth(r, c, gw, gh);
            // Proyección incremental: x_c = R·(center + i·aW + j·aH + k·aD) + t
            double[] ri = Lin.scale(Lin.mulV(p.r, axes[0]), step), rj = Lin.scale(Lin.mulV(p.r, axes[1]), step),
                    rk = Lin.scale(Lin.mulV(p.r, axes[2]), step);
            double[] o = Lin.add(p.apply(b.center), Lin.add(Lin.scale(Lin.mulV(p.r, axes[0]), lo[0] + step / 2),
                    Lin.add(Lin.scale(Lin.mulV(p.r, axes[1]), lo[1] + step / 2), Lin.scale(Lin.mulV(p.r, axes[2]), lo[2] + step / 2))));
            double margin = 2 * step;
            for (int k = 0; k < g.nz; k++)
                for (int j = 0; j < g.ny; j++) {
                    double x = o[0] + j * rj[0] + k * rk[0], y = o[1] + j * rj[1] + k * rk[1], z = o[2] + j * rj[2] + k * rk[2];
                    int row = (k * g.ny + j) * g.nx;
                    for (int i = 0; i < g.nx; i++, x += ri[0], y += ri[1], z += ri[2]) {
                        if (z <= 0) continue;
                        double fu = r.focal * x / z + cx, fv = r.focal * y / z + cy;
                        double u = fu * sx, v = fv * sy;
                        if (u < 1 || v < 1 || u >= mw - 1 || v >= mh - 1) continue;
                        // 1) Silueta: cuántas veces cae fuera (y cuántas se ve)
                        seen[row + i]++;
                        if (m[(int) v * mw + (int) u] == 0) bad[row + i]++;
                        // 2) Espacio libre: claramente delante del punto más cercano que vio esta cámara en esa zona
                        double dz = depth[(int) (fv / CELL) * gw + (int) (fu / CELL)];
                        if (dz > 0 && z < dz - margin) free[row + i]++;
                    }
                }
        }
        // 3) Ocupado: dentro de casi todas las siluetas, visto lo suficiente, sin rayos que lo crucen y del lado del objeto
        int minSeen = Math.max(5, views / 5);
        g.occ = new byte[n];
        for (int k = 0; k < g.nz; k++)
            for (int j = 0; j < g.ny; j++)
                for (int i = 0; i < g.nx; i++) {
                    int id = g.idx(i, j, k);
                    if (bad[id] > Math.max(TOL, TOL_FRACTION * seen[id]) || seen[id] < minSeen) continue;
                    if (free[id] >= MIN_FREE) continue;
                    if (planeDist(g, b, axes, i, j, k) <= 0) continue;
                    g.occ[id] = 1;
                }
        largestComponent(g);
        // 4) Medidas
        int cnt = g.count();
        if (cnt < 50) return null;
        double[][] coord = new double[3][cnt];
        double[] pd = new double[cnt];
        int q = 0;
        for (int k = 0; k < g.nz; k++)
            for (int j = 0; j < g.ny; j++)
                for (int i = 0; i < g.nx; i++)
                    if (g.occ[g.idx(i, j, k)] != 0) {
                        coord[0][q] = g.x0 + (i + 0.5) * step;
                        coord[1][q] = g.y0 + (j + 0.5) * step;
                        coord[2][q] = g.z0 + (k + 0.5) * step;
                        pd[q++] = planeDist(g, b, axes, i, j, k);
                    }
        Result res = new Result();
        res.grid = g;
        res.views = views;
        res.center = b.center.clone();
        res.axes = new double[][]{b.axisW.clone(), b.axisH.clone(), b.axisD.clone()};
        double[] e = new double[3];
        for (int a = 0; a < 3; a++) e[a] = Measure.percentile(coord[a], 99) - Measure.percentile(coord[a], 1);
        res.ancho = e[0];
        res.alto = e[1];
        res.prof = e[2];
        res.front = Measure.percentile(pd, 99);
        res.volume = cnt * step * step * step;
        return res;
    }

    /**
     * Profundidad del punto observado más cercano a la cámara {@code c} en
     * cada zona de CELL×CELL px, tomando también las 8 zonas vecinas (más
     * prudente cerca de bordes y en zonas sin puntos); 0 = sin datos.
     */
    static double[] nearestDepth(Sfm.Result r, int c, int gw, int gh) {
        double[] d = new double[gw * gh];
        int fr = r.camFrames.get(c);
        Geometry.Pose p = r.poses.get(c);
        for (int i = 0; i < r.pointTracks.size() && i < r.points.length; i++)
            for (Sfm.Track t : r.pointTracks.get(i))
                if (t.visible(fr)) {
                    double z = p.apply(r.points[i])[2];
                    int gx = (int) (t.x(fr) / CELL), gy = (int) (t.y(fr) / CELL);
                    if (z > 0 && gx >= 0 && gy >= 0 && gx < gw && gy < gh && (d[gy * gw + gx] == 0 || z < d[gy * gw + gx])) d[gy * gw + gx] = z;
                    break;
                }
        double[] out = new double[gw * gh];
        for (int y = 0; y < gh; y++)
            for (int x = 0; x < gw; x++) {
                double best = 0;
                for (int dy = -1; dy <= 1; dy++)
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = x + dx, yy = y + dy;
                        if (xx < 0 || yy < 0 || xx >= gw || yy >= gh) continue;
                        double v = d[yy * gw + xx];
                        if (v > 0 && (best == 0 || v < best)) best = v;
                    }
                out[y * gw + x] = best;
            }
        return out;
    }

    /**
     * Puntos (con pistas) del objeto según las siluetas: por encima del
     * plano de apoyo y dentro de la silueta en casi todas las vistas donde
     * caen en la imagen (se toleran {@code max(1, 10 %)} vistas en
     * desacuerdo, por errores de segmentación). No depende de ninguna caja
     * previa. Vacío si hay menos de 8 siluetas.
     */
    public static java.util.List<Integer> silhouetteMembers(Sfm.Result r, Measure.Box b, byte[][] masks, int mw, int mh) {
        java.util.List<Integer> out = new java.util.ArrayList<>();
        int views = 0;
        for (byte[] m : masks) if (m != null) views++;
        if (views < 8) return out;
        double sx = (double) mw / r.width, sy = (double) mh / r.height, cx = r.width / 2.0, cy = r.height / 2.0;
        double size = Math.max(b.alto, Math.max(b.ancho, b.prof));
        for (int i = 0; i < r.pointTracks.size() && i < r.points.length; i++) {
            double[] x = r.points[i];
            if (Lin.dot(Lin.sub(x, b.planeP), b.planeN) < 0.03 * size) continue; // la pared o el piso no
            int in = 0, outN = 0;
            for (int c = 0; c < masks.length; c++) {
                byte[] m = masks[c];
                if (m == null) continue;
                double[] xc = r.poses.get(c).apply(x);
                if (xc[2] <= 0) continue;
                double u = (r.focal * xc[0] / xc[2] + cx) * sx, v = (r.focal * xc[1] / xc[2] + cy) * sy;
                if (u < 1 || v < 1 || u >= mw - 1 || v >= mh - 1) continue;
                if (m[(int) v * mw + (int) u] != 0) in++;
                else outN++;
            }
            if (in >= 5 && outN <= Math.max(1, (in + outN) / 10)) out.add(i);
        }
        return out;
    }

    /** Puntos del objeto que deben caer dentro de la silueta, y cuánto más grande que ellos puede ser la silueta. */
    static final double MIN_INSIDE = 0.5, MAX_AREA_RATIO = 3;
    /** Puntos del objeto visibles en la foto que hacen falta para juzgar su silueta. */
    static final int MIN_JUDGE = 8;

    /**
     * Las siluetas que concuerdan con los puntos del objeto (las otras quedan
     * null en la copia devuelta): al menos la mitad de los puntos con pista
     * del objeto vistos en esa foto deben caer dentro, y la silueta no puede
     * ocupar más de 3 veces el rectángulo que cubren esos puntos (percentiles
     * 10–90, ampliado 25 %). Así se descartan una mancha de la mesa tomada
     * por el objeto y una silueta que se desborda por el piso, que con una
     * foto movida pasan seguido. Con menos de 8 puntos visibles no se juzga.
     */
    public static byte[][] validate(Sfm.Result r, Measure.Box b, byte[][] masks, int mw, int mh) {
        byte[][] out = masks.clone();
        float[][] pts = seeds(r, b);
        double sx = (double) mw / r.width, sy = (double) mh / r.height;
        for (int c = 0; c < masks.length; c++) {
            byte[] m = masks[c];
            if (m == null || pts[c].length < 2 * MIN_JUDGE) continue;
            int n = pts[c].length / 2, in = 0;
            double[] xs = new double[n], ys = new double[n];
            for (int k = 0; k < n; k++) {
                xs[k] = pts[c][2 * k];
                ys[k] = pts[c][2 * k + 1];
                int u = (int) (xs[k] * sx), v = (int) (ys[k] * sy);
                if (u >= 0 && v >= 0 && u < mw && v < mh && near(m, mw, mh, u, v)) in++;
            }
            double x0 = Measure.percentile(xs, 10), x1 = Measure.percentile(xs, 90), y0 = Measure.percentile(ys, 10), y1 = Measure.percentile(ys, 90);
            double rect = Math.max(1, (x1 - x0) * 1.25 * sx) * Math.max(1, (y1 - y0) * 1.25 * sy);
            int area = 0;
            for (byte v : m) if (v != 0) area++;
            if (in < MIN_INSIDE * n || area > MAX_AREA_RATIO * rect) out[c] = null;
        }
        return out;
    }

    /** ¿Hay silueta en (u, v) o a un píxel? (los bordes de la silueta son imprecisos) */
    private static boolean near(byte[] m, int mw, int mh, int u, int v) {
        for (int dv = -1; dv <= 1; dv++)
            for (int du = -1; du <= 1; du++) {
                int x = u + du, y = v + dv;
                if (x >= 0 && y >= 0 && x < mw && y < mh && m[y * mw + x] != 0) return true;
            }
        return false;
    }

    /** Distancia al plano del centro del vóxel (i, j, k): es lineal en i, j, k. */
    private static double planeDist(Carver.Grid g, Measure.Box b, double[][] axes, int i, int j, int k) {
        double base = Lin.dot(Lin.sub(b.center, b.planeP), b.planeN);
        return base + Lin.dot(axes[0], b.planeN) * (g.x0 + (i + 0.5) * g.step) + Lin.dot(axes[1], b.planeN) * (g.y0 + (j + 0.5) * g.step)
                + Lin.dot(axes[2], b.planeN) * (g.z0 + (k + 0.5) * g.step);
    }

    /** ¿El punto {@code x} cae en un vóxel ocupado (o a menos de {@code dilate} vóxeles de uno)? */
    public static boolean inside(Result h, double[] x, int dilate) {
        Carver.Grid g = h.grid;
        double[] q = Lin.sub(x, h.center);
        int i = (int) Math.floor((Lin.dot(q, h.axes[0]) - g.x0) / g.step), j = (int) Math.floor((Lin.dot(q, h.axes[1]) - g.y0) / g.step),
            k = (int) Math.floor((Lin.dot(q, h.axes[2]) - g.z0) / g.step);
        for (int dk = -dilate; dk <= dilate; dk++)
            for (int dj = -dilate; dj <= dilate; dj++)
                for (int di = -dilate; di <= dilate; di++) {
                    int a = i + di, c = j + dj, e = k + dk;
                    if (a >= 0 && c >= 0 && e >= 0 && a < g.nx && c < g.ny && e < g.nz && g.occ[g.idx(a, c, e)] != 0) return true;
                }
        return false;
    }

    /** Deja sólo la mayor región conexa (vecinos de 6): quita restos sueltos de siluetas imperfectas. */
    static void largestComponent(Carver.Grid g) {
        int n = g.occ.length;
        int[] label = new int[n];
        int best = 0, bestSize = 0, next = 0;
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int s = 0; s < n; s++) {
            if (g.occ[s] == 0 || label[s] != 0) continue;
            next++;
            int size = 0;
            label[s] = next;
            queue.add(s);
            while (!queue.isEmpty()) {
                int id = queue.poll();
                size++;
                int i = id % g.nx, j = (id / g.nx) % g.ny, k = id / (g.nx * g.ny);
                int[][] nb = {{i - 1, j, k}, {i + 1, j, k}, {i, j - 1, k}, {i, j + 1, k}, {i, j, k - 1}, {i, j, k + 1}};
                for (int[] c : nb) {
                    if (c[0] < 0 || c[1] < 0 || c[2] < 0 || c[0] >= g.nx || c[1] >= g.ny || c[2] >= g.nz) continue;
                    int o = g.idx(c[0], c[1], c[2]);
                    if (g.occ[o] != 0 && label[o] == 0) {
                        label[o] = next;
                        queue.add(o);
                    }
                }
            }
            if (size > bestSize) { bestSize = size; best = next; }
        }
        for (int s = 0; s < n; s++) if (label[s] != best) g.occ[s] = 0;
    }

    /**
     * Qué tanto coinciden las dos medidas: la mayor diferencia relativa entre
     * las proporciones (ancho, alto y distancia al plano, cada una dividida
     * por la media geométrica de las tres). 0,05 = 5 %.
     */
    public static double disagreement(Measure.Box b, Result h) {
        double[] pts = {b.ancho, b.alto, b.separation + b.prof};
        double[] hull = {h.ancho, h.alto, h.front};
        if (!b.onWall) {
            // En una mesa la normal es el alto: el casco va del plano al tope
            pts = new double[]{b.ancho, b.separation + b.alto, b.prof};
            hull = new double[]{h.ancho, h.front, h.prof};
        }
        double gp = Math.cbrt(pts[0] * pts[1] * pts[2]), gh = Math.cbrt(hull[0] * hull[1] * hull[2]);
        double worst = 0;
        for (int a = 0; a < 3; a++) worst = Math.max(worst, Math.abs(hull[a] / gh - pts[a] / gp) / (pts[a] / gp));
        return worst;
    }

    /** Puntos de pista visibles en cada cámara: dónde "tocar" el objeto para segmentarlo (x, y intercalados, en píxeles del cuadro). */
    public static float[][] seeds(Sfm.Result r, Measure.Box b) {
        float[][] out = new float[r.poses.size()][];
        for (int c = 0; c < out.length; c++) {
            int fr = r.camFrames.get(c);
            float[] tmp = new float[2 * b.objectPoints.length];
            int m = 0;
            for (int i : b.objectPoints) {
                if (i >= r.pointTracks.size()) continue; // puntos densos: sin pistas
                for (Sfm.Track t : r.pointTracks.get(i))
                    if (t.visible(fr)) {
                        tmp[m++] = t.x(fr);
                        tmp[m++] = t.y(fr);
                        break;
                    }
            }
            out[c] = Arrays.copyOf(tmp, m);
        }
        return out;
    }
}
