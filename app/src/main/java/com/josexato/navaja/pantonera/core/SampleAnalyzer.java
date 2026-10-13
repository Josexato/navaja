package com.josexato.navaja.pantonera.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Mide una muestra fotografiada sobre una hoja blanca (A4, Carta u Oficio).
 *
 * <ol>
 * <li>Encuentra la hoja: la región clara y poco saturada más grande.</li>
 * <li>Ajusta un cuadrilátero a su contorno; con el tamaño conocido del papel
 *     obtiene los mm por píxel.</li>
 * <li>La muestra es lo que hay sobre la hoja y no es hoja.</li>
 * <li>Corrige la luz (von Kries): el blanco de la hoja junto a la muestra
 *     pasa a ser un blanco neutro de reflectancia conocida.</li>
 * <li>Agrupa los colores en Lab (k‑means + fusión por ΔE2000).</li>
 * <li>El grupo más continuo es la matriz; los demás son componentes, de los
 *     que mide fracción de área, granulometría y forma.</li>
 * </ol>
 * Todo en Java puro (imágenes como int[] ARGB) para poder probarlo en la JVM.
 */
public final class SampleAnalyzer {

    /** Parámetros (los valores por defecto sirven para papel de oficina). */
    public static final class Options {
        /** Reflectancia supuesta del papel (Y). 0,85 ≈ L* 94. */
        public double paperY = 0.85;
        /** Grupos de color a menos de este ΔE2000 se funden en uno. */
        public double mergeDe = 7;
        public int maxClusters = 6;
        /** Componentes con menos área que esto se funden con el más cercano. */
        public double minFraction = 0.01;
        /** Partículas menores que esto (mm) no entran en la granulometría. */
        public double minParticleMm = 0.4;
    }

    /** Tamaños de papel reconocidos (mm). */
    static final String[] PAPER_NAMES = {"A4", "Carta", "Oficio"};
    static final double[][] PAPER_MM = {{297, 210}, {279.4, 215.9}, {330.2, 215.9}};

    public static final class Result {
        public String error;                       // null si salió bien
        public final List<String> warnings = new ArrayList<>();
        public Stjf.Sample sample;
        public double mmPerPx = Double.NaN;
        public String paper;
        public double[] quad;                      // x0,y0..x3,y3 de la hoja
        public double[] reference;                 // blanco medido (RGB lineal)
        public double specularFraction;
        /** Por píxel: -3 fuera, -2 hoja, -1 borde/reflejo descartado, ≥0 grupo. */
        public int[] labels;
        public int width, height;
        public int[] clusterArgb;                  // color corregido de cada grupo
        public int baseCluster = -1;
        /** Recorte de la muestra con la luz corregida (sRGB), para la textura. */
        public int[] texture;
        public int texW, texH;

        public boolean ok() { return error == null; }
    }

    private final Options opt;

    public SampleAnalyzer() { this(new Options()); }

    public SampleAnalyzer(Options opt) { this.opt = opt; }

    // =================================================================== API

    public Result analyze(int[] argb, int w, int h) {
        Result res = new Result();
        res.width = w;
        res.height = h;
        int n = w * h;
        float[] r = new float[n], g = new float[n], b = new float[n];
        for (int i = 0; i < n; i++) {
            int c = argb[i];
            r[i] = ColorMath.LIN8[(c >> 16) & 255];
            g[i] = ColorMath.LIN8[(c >> 8) & 255];
            b[i] = ColorMath.LIN8[c & 255];
        }

        // ---- 1. Hoja: región clara y neutra más grande.
        float[] lum = new float[n];
        for (int i = 0; i < n; i++) lum[i] = 0.2126f * r[i] + 0.7152f * g[i] + 0.0722f * b[i];
        double thr = Math.max(0.12, otsu(lum));
        boolean[] bright = new boolean[n];
        for (int i = 0; i < n; i++) {
            float mx = Math.max(r[i], Math.max(g[i], b[i])), mn = Math.min(r[i], Math.min(g[i], b[i]));
            bright[i] = lum[i] > thr && (mx - mn) < 0.45f * mx;
        }
        boolean[] sheet = largestComponent(bright, w, h);
        int sheetArea = count(sheet);
        if (sheetArea < 0.05 * n) {
            res.error = "No se ve la hoja blanca. Pon la muestra sobre una hoja y encuádrala completa.";
            return res;
        }

        // ---- 2. Cuadrilátero y escala.
        double[] quad = fitQuad(sheet, w, h);
        res.quad = quad;
        boolean cut = touchesBorder(sheet, w, h);
        measureScale(res, quad, cut);

        // ---- 3. Muestra: lo que hay dentro de la hoja y no es hoja.
        boolean[] inside = rasterQuad(quad, w, h);
        boolean[] cand = new boolean[n];
        for (int i = 0; i < n; i++) cand[i] = inside[i] && !sheet[i];
        boolean[] sampleMask = pickSample(cand, inside, w, h);
        int sampleArea = count(sampleMask);
        if (sampleArea < 0.003 * sheetArea) {
            res.error = "No encontré la muestra sobre la hoja. Ponla en el centro, sin tocar los bordes. "
                    + "(Si es blanca o muy clara se confunde con el papel: todavía no se puede medir así.)";
            return res;
        }

        // Quitar el borde de la muestra (sombra y desenfoque).
        double shortPx = shortSide(quad);
        int erode = (int) Math.max(2, Math.round(0.006 * shortPx));
        int[] dIn = distanceInside(sampleMask, w, h);
        boolean[] core = new boolean[n];
        for (int i = 0; i < n; i++) core[i] = dIn[i] > erode;
        if (count(core) < 200) {
            res.error = "La muestra se ve demasiado chica. Acerca el teléfono (con la hoja completa en la foto).";
            return res;
        }

        // ---- 4. Blanco de referencia junto a la muestra.
        int[] dSheet = distanceInside(sheet, w, h);
        int[] bb = bbox(sampleMask, w, h);
        int mx = (bb[2] - bb[0]) * 3 / 10 + erode * 4, my = (bb[3] - bb[1]) * 3 / 10 + erode * 4;
        int x0 = Math.max(0, bb[0] - mx), x1 = Math.min(w - 1, bb[2] + mx);
        int y0 = Math.max(0, bb[1] - my), y1 = Math.min(h - 1, bb[3] + my);
        double[] ref = medianRgb(r, g, b, dSheet, erode * 2, x0, y0, x1, y1, w);
        if (ref == null) ref = medianRgb(r, g, b, dSheet, erode, 0, 0, w - 1, h - 1, w);
        res.reference = ref;
        for (double c : ref) {
            if (c > 0.96) {
                res.warnings.add("La hoja está sobreexpuesta (quemada): los colores claros pierden precisión. Baja la exposición.");
                break;
            }
        }
        if (Math.min(ref[0], Math.min(ref[1], ref[2])) < 0.08) {
            res.warnings.add("Hay muy poca luz: la medición será ruidosa.");
        }
        checkUniformity(res, lum, sheet, dSheet, erode, w, h);

        // ---- 5. Corrección de luz y paso a Lab.
        double kr = opt.paperY / ref[0], kg = opt.paperY / ref[1], kb = opt.paperY / ref[2];
        int[] labels = new int[n];
        Arrays.fill(labels, -3);
        for (int i = 0; i < n; i++) if (sheet[i]) labels[i] = -2;
        int valid = 0, spec = 0;
        for (int i = 0; i < n; i++) {
            if (!sampleMask[i]) continue;
            labels[i] = -1;
            if (!core[i]) continue;
            if (Math.max(r[i], Math.max(g[i], b[i])) > 0.97f) { spec++; continue; } // reflejo
            labels[i] = 0;
            valid++;
        }
        res.specularFraction = valid + spec == 0 ? 0 : spec / (double) (valid + spec);
        if (res.specularFraction > 0.03) {
            res.warnings.add(String.format(Locale.US,
                    "Reflejos en el %.0f%% de la muestra: inclina un poco la luz o el teléfono.", 100 * res.specularFraction));
        }
        if (valid < 200) {
            res.error = "La muestra está casi toda quemada por reflejos. Cambia el ángulo de la luz.";
            return res;
        }
        int[] idx = new int[valid];
        float[] lab = new float[valid * 3];
        for (int i = 0, k = 0; i < n; i++) {
            if (labels[i] != 0) continue;
            double[] l = ColorMath.linearToLab(r[i] * kr, g[i] * kg, b[i] * kb);
            idx[k] = i;
            lab[3 * k] = (float) l[0];
            lab[3 * k + 1] = (float) l[1];
            lab[3 * k + 2] = (float) l[2];
            k++;
        }

        // ---- 6. Agrupar colores.
        List<double[]> centers = cluster(lab, valid);
        int k = centers.size();
        int[] assign = new int[valid];
        double[][] sum = new double[k][3];
        int[] cnt = new int[k];
        for (int p = 0; p < valid; p++) {
            int c = nearest(centers, lab[3 * p], lab[3 * p + 1], lab[3 * p + 2]);
            assign[p] = c;
            labels[idx[p]] = c;
            cnt[c]++;
            for (int d = 0; d < 3; d++) sum[c][d] += lab[3 * p + d];
        }
        // Color de cada grupo: sólo con píxeles "de núcleo" (todo su entorno de 5×5 es
        // del mismo grupo), para que el borde desenfocado de las piedras —mezcla con
        // la matriz— no tiña el resultado. Si el grupo es muy fino, se usan todos.
        double[][] coreSum = new double[k][3];
        int[] coreCnt = new int[k];
        for (int p = 0; p < valid; p++) {
            int i = idx[p], c = assign[p], x = i % w, y = i / w;
            boolean pure = x >= 2 && y >= 2 && x < w - 2 && y < h - 2;
            for (int dy = -2; pure && dy <= 2; dy++)
                for (int dx = -2; dx <= 2; dx++)
                    if (labels[i + dy * w + dx] != c) { pure = false; break; }
            if (!pure) continue;
            coreCnt[c]++;
            for (int d = 0; d < 3; d++) coreSum[c][d] += lab[3 * p + d];
        }
        double[][] mean = new double[k][];
        for (int c = 0; c < k; c++) {
            if (coreCnt[c] >= Math.max(30, cnt[c] / 10)) {
                mean[c] = new double[]{coreSum[c][0] / coreCnt[c], coreSum[c][1] / coreCnt[c], coreSum[c][2] / coreCnt[c]};
            } else {
                mean[c] = cnt[c] == 0 ? centers.get(c) : new double[]{sum[c][0] / cnt[c], sum[c][1] / cnt[c], sum[c][2] / cnt[c]};
            }
        }
        res.labels = labels;
        res.clusterArgb = new int[k];
        for (int c = 0; c < k; c++) res.clusterArgb[c] = ColorMath.labToArgb(mean[c]);

        // ---- 7. Matriz y componentes.
        Blobs blobs = new Blobs(labels, w, h, k);
        int base = 0;
        for (int c = 1; c < k; c++) if (blobs.largest[c] > blobs.largest[base]) base = c;
        res.baseCluster = base;

        Stjf.Sample s = new Stjf.Sample();
        if (k == 1) {
            s.lab = round(mean[0]);
        } else {
            s.base = new Stjf.Layer();
            s.base.lab = round(mean[base]);
            s.base.fraction = cnt[base] / (double) valid;
            int cid = 1;
            Integer[] order = new Integer[k];
            for (int c = 0; c < k; c++) order[c] = c;
            Arrays.sort(order, (a2, b2) -> Integer.compare(cnt[b2], cnt[a2]));
            for (int c : order) {
                if (c == base || cnt[c] == 0) continue;
                Stjf.Component comp = new Stjf.Component();
                comp.id = "c" + cid++;
                comp.lab = round(mean[c]);
                comp.label = ColorNames.describe(comp.lab);
                comp.fraction = cnt[c] / (double) valid;
                blobs.describe(c, comp, res.mmPerPx, opt.minParticleMm);
                s.components.add(comp);
            }
        }
        s.appearance = new Stjf.Appearance();
        if (!Double.isNaN(res.mmPerPx)) s.appearance.mmPerPx = res.mmPerPx;
        s.measurement = new Stjf.Measurement();
        s.measurement.shots = 1;
        s.measurement.reference = "paper-" + (res.paper == null ? "?" : res.paper);
        res.sample = s;

        // ---- 8. Textura: el mayor rectángulo completamente dentro de la muestra.
        int[] rect = largestRect(core, w, h);
        if (rect != null && rect[2] >= 24 && rect[3] >= 24) {
            res.texW = rect[2];
            res.texH = rect[3];
            res.texture = new int[rect[2] * rect[3]];
            for (int y = 0; y < rect[3]; y++) {
                for (int x = 0; x < rect[2]; x++) {
                    int i = (rect[1] + y) * w + rect[0] + x;
                    res.texture[y * rect[2] + x] = 0xFF000000
                            | (g8(r[i] * kr) << 16) | (g8(g[i] * kg) << 8) | g8(b[i] * kb);
                }
            }
        }
        return res;
    }

    /** Imagen de diagnóstico: grupos pintados con su color, hoja en celeste y el resto oscurecido. */
    public static int[] overlay(int[] argb, Result res) {
        int n = argb.length;
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            int c = argb[i];
            int lab = res.labels == null ? -3 : res.labels[i];
            if (lab >= 0) {
                out[i] = res.clusterArgb[lab];
            } else if (lab == -2) {
                out[i] = blend(c, 0xFF8FD3FF, 0.35);
            } else if (lab == -1) {
                out[i] = blend(c, 0xFFFF4FA3, 0.6);
            } else {
                out[i] = blend(c, 0xFF000000, 0.6);
            }
        }
        if (res.quad != null) drawQuad(out, res.width, res.height, res.quad, 0xFFFFD000);
        return out;
    }

    // ============================================================ hoja y escala

    static double otsu(float[] v) {
        int[] hist = new int[256];
        for (float x : v) hist[Math.min(255, Math.max(0, (int) (x * 255)))]++;
        long total = v.length;
        double sumAll = 0;
        for (int i = 0; i < 256; i++) sumAll += i * (double) hist[i];
        double sumB = 0, best = -1;
        long wB = 0;
        int thr = 128;
        for (int t = 0; t < 256; t++) {
            wB += hist[t];
            if (wB == 0) continue;
            long wF = total - wB;
            if (wF == 0) break;
            sumB += t * (double) hist[t];
            double mB = sumB / wB, mF = (sumAll - sumB) / wF;
            double between = (double) wB * wF * (mB - mF) * (mB - mF);
            if (between > best) { best = between; thr = t; }
        }
        return (thr + 0.5) / 255.0;
    }

    /** Componente 4‑conexa más grande. */
    static boolean[] largestComponent(boolean[] m, int w, int h) {
        int[] comp = new int[m.length];
        int[] queue = new int[m.length];
        int bestId = 0, bestSize = 0, id = 0;
        for (int s = 0; s < m.length; s++) {
            if (!m[s] || comp[s] != 0) continue;
            int size = flood(m, comp, queue, s, ++id, w, h, false);
            if (size > bestSize) { bestSize = size; bestId = id; }
        }
        boolean[] out = new boolean[m.length];
        for (int i = 0; i < m.length; i++) out[i] = bestId != 0 && comp[i] == bestId;
        return out;
    }

    /** Relleno por inundación; devuelve el tamaño. */
    static int flood(boolean[] m, int[] comp, int[] queue, int start, int id, int w, int h, boolean eight) {
        int head = 0, tail = 0;
        queue[tail++] = start;
        comp[start] = id;
        while (head < tail) {
            int p = queue[head++];
            int x = p % w, y = p / w;
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if ((dx == 0 && dy == 0) || (!eight && dx != 0 && dy != 0)) continue;
                    int nx = x + dx, ny = y + dy;
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                    int q = ny * w + nx;
                    if (m[q] && comp[q] == 0) { comp[q] = id; queue[tail++] = q; }
                }
            }
        }
        return tail;
    }

    /** Envolvente convexa del contorno simplificada a 4 vértices (sentido horario en pantalla). */
    static double[] fitQuad(boolean[] m, int w, int h) {
        List<double[]> pts = new ArrayList<>();
        for (int y = 0; y < h; y++) {
            int lo = -1, hi = -1;
            for (int x = 0; x < w; x++) {
                if (m[y * w + x]) { if (lo < 0) lo = x; hi = x; }
            }
            if (lo >= 0) {
                pts.add(new double[]{lo, y});
                if (hi != lo) pts.add(new double[]{hi + 1, y});
                // Bordes superior/inferior del píxel para no perder media fila.
                pts.add(new double[]{lo, y + 1});
                pts.add(new double[]{hi + 1, y + 1});
            }
        }
        List<double[]> hull = convexHull(pts);
        while (hull.size() > 4) {
            int worst = -1;
            double minArea = Double.MAX_VALUE;
            for (int i = 0; i < hull.size(); i++) {
                double[] a = hull.get((i - 1 + hull.size()) % hull.size()), p = hull.get(i), c = hull.get((i + 1) % hull.size());
                double area = Math.abs((p[0] - a[0]) * (c[1] - a[1]) - (c[0] - a[0]) * (p[1] - a[1])) / 2;
                if (area < minArea) { minArea = area; worst = i; }
            }
            hull.remove(worst);
        }
        double[] q = new double[8];
        for (int i = 0; i < hull.size() && i < 4; i++) {
            q[2 * i] = hull.get(i)[0];
            q[2 * i + 1] = hull.get(i)[1];
        }
        return q;
    }

    static List<double[]> convexHull(List<double[]> pts) {
        pts.sort((a, b) -> a[0] != b[0] ? Double.compare(a[0], b[0]) : Double.compare(a[1], b[1]));
        int n = pts.size();
        double[][] hull = new double[2 * n][];
        int k = 0;
        for (int i = 0; i < n; i++) {
            while (k >= 2 && cross(hull[k - 2], hull[k - 1], pts.get(i)) <= 0) k--;
            hull[k++] = pts.get(i);
        }
        for (int i = n - 2, t = k + 1; i >= 0; i--) {
            while (k >= t && cross(hull[k - 2], hull[k - 1], pts.get(i)) <= 0) k--;
            hull[k++] = pts.get(i);
        }
        List<double[]> out = new ArrayList<>();
        for (int i = 0; i < k - 1; i++) out.add(hull[i]);
        return out;
    }

    private static double cross(double[] o, double[] a, double[] b) {
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
    }

    static double side(double[] q, int i) {
        int j = (i + 1) % 4;
        return Math.hypot(q[2 * j] - q[2 * i], q[2 * j + 1] - q[2 * i + 1]);
    }

    static double shortSide(double[] q) {
        return Math.min((side(q, 0) + side(q, 2)) / 2, (side(q, 1) + side(q, 3)) / 2);
    }

    private void measureScale(Result res, double[] q, boolean cut) {
        double a = (side(q, 0) + side(q, 2)) / 2, b = (side(q, 1) + side(q, 3)) / 2;
        double lng = Math.max(a, b), sht = Math.min(a, b);
        double ratio = lng / sht;
        int best = 0;
        for (int i = 1; i < PAPER_MM.length; i++) {
            if (Math.abs(ratio - PAPER_MM[i][0] / PAPER_MM[i][1]) < Math.abs(ratio - PAPER_MM[best][0] / PAPER_MM[best][1]))
                best = i;
        }
        double expected = PAPER_MM[best][0] / PAPER_MM[best][1];
        res.paper = PAPER_NAMES[best];
        if (cut) {
            res.warnings.add("La hoja sale cortada en la foto: no puedo medir el tamaño de las piedras. Aléjate un poco.");
            return;
        }
        double persp = Math.max(Math.abs(side(q, 0) - side(q, 2)) / Math.max(side(q, 0), side(q, 2)),
                Math.abs(side(q, 1) - side(q, 3)) / Math.max(side(q, 1), side(q, 3)));
        if (Math.abs(ratio - expected) / expected > 0.08) {
            res.warnings.add(String.format(Locale.US,
                    "La hoja no tiene la proporción de una %s (%.2f vs %.2f): ¿está doblada o tapada? Los tamaños pueden tener error.",
                    res.paper, ratio, expected));
        }
        if (persp > 0.10) {
            res.warnings.add("La foto está inclinada: toma la foto desde arriba, paralela a la hoja, para medir bien los tamaños.");
        }
        res.mmPerPx = (PAPER_MM[best][0] / lng + PAPER_MM[best][1] / sht) / 2;
    }

    static boolean touchesBorder(boolean[] m, int w, int h) {
        int edge = 0;
        for (int x = 0; x < w; x++) {
            if (m[x]) edge++;
            if (m[(h - 1) * w + x]) edge++;
        }
        for (int y = 0; y < h; y++) {
            if (m[y * w]) edge++;
            if (m[y * w + w - 1]) edge++;
        }
        return edge > (w + h) / 50;
    }

    /** Píxeles dentro del cuadrilátero (por filas). */
    static boolean[] rasterQuad(double[] q, int w, int h) {
        boolean[] out = new boolean[w * h];
        for (int y = 0; y < h; y++) {
            double yc = y + 0.5, lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
            for (int i = 0; i < 4; i++) {
                double ax = q[2 * i], ay = q[2 * i + 1], bx = q[(2 * i + 2) % 8], by = q[(2 * i + 3) % 8];
                if ((ay <= yc && by > yc) || (by <= yc && ay > yc)) {
                    double x = ax + (yc - ay) * (bx - ax) / (by - ay);
                    lo = Math.min(lo, x);
                    hi = Math.max(hi, x);
                }
            }
            if (lo > hi) continue;
            int xs = Math.max(0, (int) Math.ceil(lo - 0.5)), xe = Math.min(w - 1, (int) Math.floor(hi - 0.5));
            for (int x = xs; x <= xe; x++) out[y * w + x] = true;
        }
        return out;
    }

    /** La mayor región no‑hoja que no toca el borde de la hoja (o la mayor, si todas lo tocan). */
    static boolean[] pickSample(boolean[] cand, boolean[] inside, int w, int h) {
        int n = cand.length;
        int[] comp = new int[n], queue = new int[n];
        int id = 0, best = 0, bestSize = 0, bestAny = 0, bestAnySize = 0;
        int[] dIn = distanceInside(inside, w, h);
        for (int s = 0; s < n; s++) {
            if (!cand[s] || comp[s] != 0) continue;
            int size = flood(cand, comp, queue, s, ++id, w, h, false);
            boolean edge = false;
            for (int t = 0; t < size; t++) if (dIn[queue[t]] <= 3) { edge = true; break; }
            if (size > bestAnySize) { bestAnySize = size; bestAny = id; }
            if (!edge && size > bestSize) { bestSize = size; best = id; }
        }
        int pick = bestSize > bestAnySize / 4 ? best : bestAny;
        boolean[] out = new boolean[n];
        for (int i = 0; i < n; i++) out[i] = pick != 0 && comp[i] == pick;
        return out;
    }

    /** Distancia (Manhattan, en píxeles) al píxel más cercano fuera de la máscara. */
    static int[] distanceInside(boolean[] m, int w, int h) {
        int n = m.length;
        int[] d = new int[n];
        int[] queue = new int[n];
        int head = 0, tail = 0;
        for (int i = 0; i < n; i++) {
            int x = i % w, y = i / w;
            if (!m[i]) { d[i] = 0; continue; }
            boolean border = x == 0 || y == 0 || x == w - 1 || y == h - 1
                    || !m[i - 1] || !m[i + 1] || !m[i - w] || !m[i + w];
            if (border) { d[i] = 1; queue[tail++] = i; } else d[i] = Integer.MAX_VALUE;
        }
        while (head < tail) {
            int p = queue[head++];
            int x = p % w, y = p / w;
            int[] nb = {x > 0 ? p - 1 : -1, x < w - 1 ? p + 1 : -1, y > 0 ? p - w : -1, y < h - 1 ? p + w : -1};
            for (int q : nb) {
                if (q >= 0 && d[q] > d[p] + 1) { d[q] = d[p] + 1; queue[tail++] = q; }
            }
        }
        return d;
    }

    static int[] bbox(boolean[] m, int w, int h) {
        int x0 = w, y0 = h, x1 = -1, y1 = -1;
        for (int i = 0; i < m.length; i++) {
            if (!m[i]) continue;
            int x = i % w, y = i / w;
            if (x < x0) x0 = x;
            if (x > x1) x1 = x;
            if (y < y0) y0 = y;
            if (y > y1) y1 = y;
        }
        return new int[]{x0, y0, x1, y1};
    }

    private static double[] medianRgb(float[] r, float[] g, float[] b, int[] dSheet, int minD,
                                      int x0, int y0, int x1, int y1, int w) {
        int total = 0;
        for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) if (dSheet[y * w + x] > minD) total++;
        if (total < 50) return null;
        int step = Math.max(1, total / 60000);
        int cap = total / step + 1;
        float[] rr = new float[cap], gg = new float[cap], bb2 = new float[cap];
        int k = 0, seen = 0;
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                int i = y * w + x;
                if (dSheet[i] <= minD) continue;
                if (seen++ % step != 0 || k >= cap) continue;
                rr[k] = r[i];
                gg[k] = g[i];
                bb2[k] = b[i];
                k++;
            }
        }
        return new double[]{median(rr, k), median(gg, k), median(bb2, k)};
    }

    private static double median(float[] v, int n) {
        float[] c = Arrays.copyOf(v, n);
        Arrays.sort(c);
        return c[n / 2];
    }

    private static void checkUniformity(Result res, float[] lum, boolean[] sheet, int[] dSheet, int erode, int w, int h) {
        double[] q = new double[4];
        int[] cnt = new int[4];
        for (int i = 0; i < lum.length; i++) {
            if (!sheet[i] || dSheet[i] <= erode) continue;
            int qi = (i % w < w / 2 ? 0 : 1) + (i / w < h / 2 ? 0 : 2);
            q[qi] += lum[i];
            cnt[qi]++;
        }
        double lo = Double.MAX_VALUE, hi = 0;
        for (int i = 0; i < 4; i++) {
            if (cnt[i] < 100) continue;
            double m = q[i] / cnt[i];
            lo = Math.min(lo, m);
            hi = Math.max(hi, m);
        }
        if (hi > 0 && lo < Double.MAX_VALUE && hi / lo > 1.5) {
            res.warnings.add("La luz es despareja sobre la hoja (sombra o foco cercano). Uso el blanco junto a la muestra, pero conviene una luz más pareja.");
        }
    }

    // ================================================================ colores

    /** k‑means++ en Lab (submuestreado) y fusión de grupos parecidos. */
    private List<double[]> cluster(float[] lab, int n) {
        int step = Math.max(1, n / 40000);
        int m = (n + step - 1) / step;
        double[][] pts = new double[m][];
        for (int i = 0, k = 0; i < n && k < m; i += step, k++)
            pts[k] = new double[]{lab[3 * i], lab[3 * i + 1], lab[3 * i + 2]};

        Random rnd = new Random(42);
        List<double[]> c = new ArrayList<>();
        c.add(pts[rnd.nextInt(m)].clone());
        double[] d2 = new double[m];
        while (c.size() < opt.maxClusters) {
            double tot = 0;
            for (int i = 0; i < m; i++) {
                double best = Double.MAX_VALUE;
                for (double[] cc : c) best = Math.min(best, sq(pts[i], cc));
                d2[i] = best;
                tot += best;
            }
            if (tot < 1e-6) break;
            double r = rnd.nextDouble() * tot;
            int pick = 0;
            for (double acc = 0; pick < m - 1; pick++) {
                acc += d2[pick];
                if (acc >= r) break;
            }
            c.add(pts[pick].clone());
        }
        double[][] st = lloyd(pts, c, 25);

        // Fundir grupos: (a) colores parecidos (ΔE2000 < mergeDe), (b) grupos que
        // se solapan, es decir, un mismo color partido por el ruido (distancia
        // menor que 2× su dispersión; típico en zonas oscuras), (c) grupos diminutos.
        while (c.size() > 1) {
            int bi = -1, bj = -1;
            double best = Double.MAX_VALUE;
            for (int i = 0; i < c.size(); i++) {
                for (int j = i + 1; j < c.size(); j++) {
                    double de = ColorMath.deltaE2000(c.get(i), c.get(j));
                    double overlap = Math.sqrt(sq(c.get(i), c.get(j))) / (2 * Math.max(1e-6, Math.max(st[1][i], st[1][j])));
                    boolean tiny = st[0][i] < opt.minFraction || st[0][j] < opt.minFraction;
                    // Puntaje < 1 = hay que fundir; los diminutos van primero.
                    double key = tiny ? de / 1000 : Math.min(de / opt.mergeDe, overlap);
                    if (key < best) { best = key; bi = i; bj = j; }
                }
            }
            if (best >= 1) break;
            double wi = st[0][bi], wj = st[0][bj], ws = Math.max(1e-9, wi + wj);
            double[] ci = c.get(bi), cj = c.get(bj);
            for (int d = 0; d < 3; d++) ci[d] = (ci[d] * wi + cj[d] * wj) / ws;
            c.remove(bj);
            st = lloyd(pts, c, 5);
        }
        return c;
    }

    /** Iteraciones de Lloyd; devuelve {fracción de puntos, dispersión RMS} de cada centro. */
    private static double[][] lloyd(double[][] pts, List<double[]> c, int iters) {
        int k = c.size();
        double[] frac = new double[k], rms = new double[k];
        int[] owner = new int[pts.length];
        for (int it = 0; it < iters; it++) {
            double[][] s = new double[k][3];
            int[] cnt = new int[k];
            for (int p = 0; p < pts.length; p++) {
                int b = 0;
                double best = Double.MAX_VALUE;
                for (int j = 0; j < k; j++) {
                    double d = sq(pts[p], c.get(j));
                    if (d < best) { best = d; b = j; }
                }
                owner[p] = b;
                cnt[b]++;
                for (int d = 0; d < 3; d++) s[b][d] += pts[p][d];
            }
            boolean moved = false;
            for (int j = 0; j < k; j++) {
                frac[j] = cnt[j] / (double) pts.length;
                if (cnt[j] == 0) continue;
                double[] cj = c.get(j);
                for (int d = 0; d < 3; d++) {
                    double v = s[j][d] / cnt[j];
                    if (Math.abs(v - cj[d]) > 1e-3) moved = true;
                    cj[d] = v;
                }
            }
            if (!moved) break;
        }
        int[] cnt = new int[k];
        for (int p = 0; p < pts.length; p++) {
            rms[owner[p]] += sq(pts[p], c.get(owner[p]));
            cnt[owner[p]]++;
        }
        for (int j = 0; j < k; j++) rms[j] = cnt[j] == 0 ? 0 : Math.sqrt(rms[j] / cnt[j]);
        return new double[][]{frac, rms};
    }

    private static int nearest(List<double[]> c, double l, double a, double b) {
        int best = 0;
        double bd = Double.MAX_VALUE;
        for (int j = 0; j < c.size(); j++) {
            double[] q = c.get(j);
            double d = (l - q[0]) * (l - q[0]) + (a - q[1]) * (a - q[1]) + (b - q[2]) * (b - q[2]);
            if (d < bd) { bd = d; best = j; }
        }
        return best;
    }

    private static double sq(double[] p, double[] q) {
        double a = p[0] - q[0], b = p[1] - q[1], c = p[2] - q[2];
        return a * a + b * b + c * c;
    }

    // ============================================================ partículas

    /** Manchas 8‑conexas por grupo: tamaños, si tocan el borde, forma. */
    static final class Blobs {
        final int[] largest;            // área de la mancha más grande por grupo
        final List<List<int[]>> perCluster = new ArrayList<>(); // {área, tocaBorde, perímetro, elongación×100}

        Blobs(int[] labels, int w, int h, int k) {
            largest = new int[k];
            for (int c = 0; c < k; c++) perCluster.add(new ArrayList<>());
            int n = labels.length;
            boolean[] seen = new boolean[n];
            int[] queue = new int[n];
            for (int s = 0; s < n; s++) {
                int c = labels[s];
                if (c < 0 || seen[s]) continue;
                int head = 0, tail = 0;
                queue[tail++] = s;
                seen[s] = true;
                boolean edge = false;
                int perim = 0;
                double sx = 0, sy = 0, sxx = 0, syy = 0, sxy = 0;
                while (head < tail) {
                    int p = queue[head++];
                    int x = p % w, y = p / w;
                    sx += x;
                    sy += y;
                    sxx += (double) x * x;
                    syy += (double) y * y;
                    sxy += (double) x * y;
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            if (dx == 0 && dy == 0) continue;
                            int nx = x + dx, ny = y + dy;
                            boolean four = dx == 0 || dy == 0;
                            if (nx < 0 || ny < 0 || nx >= w || ny >= h) {
                                if (four) { perim++; edge = true; }
                                continue;
                            }
                            int q = ny * w + nx;
                            int lq = labels[q];
                            if (lq == c) {
                                if (!seen[q]) { seen[q] = true; queue[tail++] = q; }
                            } else if (four) {
                                perim++;
                                if (lq < 0) edge = true;
                            }
                        }
                    }
                }
                int a = tail;
                double mxx = sxx / a - (sx / a) * (sx / a), myy = syy / a - (sy / a) * (sy / a);
                double mxy = sxy / a - (sx / a) * (sy / a);
                double tr = mxx + myy, det = mxx * myy - mxy * mxy;
                double disc = Math.sqrt(Math.max(0, tr * tr / 4 - det));
                double l1 = tr / 2 + disc, l2 = Math.max(1e-6, tr / 2 - disc);
                int elong = (int) Math.round(100 * Math.sqrt(l1 / l2));
                perCluster.get(c).add(new int[]{a, edge ? 1 : 0, perim, elong});
                if (a > largest[c]) largest[c] = a;
            }
        }

        /** Llena size_mm y shape del componente del grupo c. */
        void describe(int c, Stjf.Component comp, double mmPerPx, double minMm) {
            List<int[]> all = perCluster.get(c);
            boolean scale = !Double.isNaN(mmPerPx);
            double minArea = scale ? Math.max(3, Math.PI / 4 * Math.pow(minMm / mmPerPx, 2)) : 3;
            List<int[]> use = new ArrayList<>();
            for (int[] b : all) if (b[0] >= minArea && b[1] == 0) use.add(b);
            if (use.size() < 3) {
                use.clear();
                for (int[] b : all) if (b[0] >= minArea) use.add(b);
            }
            if (use.isEmpty()) return;

            // Forma (ponderada por área): vetas alargadas, angulosas o redondeadas.
            double wsum = 0, elong = 0, circ = 0;
            for (int[] b : use) {
                if (b[0] < 20) continue;
                double pc = b[2] * Math.PI / 4; // el perímetro en píxeles sobreestima ~4/π
                elong += b[0] * b[3] / 100.0;
                circ += b[0] * Math.min(1, 4 * Math.PI * b[0] / (pc * pc));
                wsum += b[0];
            }
            if (wsum > 0) {
                elong /= wsum;
                circ /= wsum;
                comp.shape = elong > 3.5 ? "vein" : (circ < 0.62 ? "angular" : "round");
            }
            if (!scale || "vein".equals(comp.shape)) return;

            // Granulometría: diámetro equivalente, percentiles ponderados por área (D10/D50/D90).
            use.sort((x, y) -> Integer.compare(x[0], y[0]));
            double total = 0;
            for (int[] b : use) total += b[0];
            double[] pct = {0.10, 0.50, 0.90};
            double[] val = new double[3];
            double acc = 0;
            int pi = 0;
            for (int[] b : use) {
                acc += b[0];
                double d = 2 * Math.sqrt(b[0] / Math.PI) * mmPerPx;
                while (pi < 3 && acc >= pct[pi] * total) val[pi++] = d;
            }
            while (pi < 3) val[pi++] = 2 * Math.sqrt(use.get(use.size() - 1)[0] / Math.PI) * mmPerPx;
            comp.size = new Stjf.Size(val[0], val[1], val[2]);
        }
    }

    // =============================================================== utilidades

    /** Mayor rectángulo de 1s (algoritmo del histograma). Devuelve {x, y, ancho, alto}. */
    static int[] largestRect(boolean[] m, int w, int h) {
        int[] hist = new int[w];
        int[] stack = new int[w + 1];
        long bestArea = 0;
        int[] best = null;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) hist[x] = m[y * w + x] ? hist[x] + 1 : 0;
            int top = 0;
            for (int x = 0; x <= w; x++) {
                int cur = x == w ? 0 : hist[x];
                while (top > 0 && hist[stack[top - 1]] >= cur) {
                    int hh = hist[stack[--top]];
                    int left = top == 0 ? 0 : stack[top - 1] + 1;
                    long area = (long) hh * (x - left);
                    // Preferir rectángulos no demasiado alargados para la textura.
                    int ww = x - left;
                    if (hh > 0 && ww > 0 && Math.max(ww, hh) <= 3 * Math.min(ww, hh) && area > bestArea) {
                        bestArea = area;
                        best = new int[]{left, y - hh + 1, ww, hh};
                    }
                }
                stack[top++] = x;
            }
        }
        return best;
    }

    private static int count(boolean[] m) {
        int c = 0;
        for (boolean v : m) if (v) c++;
        return c;
    }

    private static double[] round(double[] lab) {
        return new double[]{Math.round(lab[0] * 100) / 100.0, Math.round(lab[1] * 100) / 100.0,
                Math.round(lab[2] * 100) / 100.0};
    }

    private static int g8(double lin) {
        return (int) Math.round(ColorMath.toGamma(ColorMath.clamp01(lin)) * 255);
    }

    private static int blend(int c, int over, double t) {
        int r = (int) (((c >> 16) & 255) * (1 - t) + ((over >> 16) & 255) * t);
        int g = (int) (((c >> 8) & 255) * (1 - t) + ((over >> 8) & 255) * t);
        int b = (int) ((c & 255) * (1 - t) + (over & 255) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static void drawQuad(int[] img, int w, int h, double[] q, int color) {
        int thick = Math.max(2, Math.min(w, h) / 300);
        for (int i = 0; i < 4; i++) {
            double ax = q[2 * i], ay = q[2 * i + 1], bx = q[(2 * i + 2) % 8], by = q[(2 * i + 3) % 8];
            int steps = (int) Math.max(1, Math.hypot(bx - ax, by - ay));
            for (int s = 0; s <= steps; s++) {
                int x = (int) (ax + (bx - ax) * s / steps), y = (int) (ay + (by - ay) * s / steps);
                for (int dy = -thick; dy <= thick; dy++)
                    for (int dx = -thick; dx <= thick; dx++) {
                        int px = x + dx, py = y + dy;
                        if (px >= 0 && py >= 0 && px < w && py < h) img[py * w + px] = color;
                    }
            }
        }
    }
}
