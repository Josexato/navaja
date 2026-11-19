package com.josexato.navaja.measure.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * La cámara del modo "Hoja": focal (px), punto principal y distorsión
 * radial (k1, k2) sobre coordenadas normalizadas:
 * x_imagen = x·(1 + k1·r² + k2·r⁴), con r² = x² + y² sin distorsión.
 * Píxeles en la convención de Navaja (centro del píxel en 0).
 *
 * <p>Si el video no trae los datos de la cámara (uno elegido de la
 * galería), se estiman con la propia hoja: la focal y k1 que mejor
 * explican las esquinas de las marcas vistas en muchos cuadros.
 */
public final class Lens {

    public double f, cx, cy, k1, k2;
    /** De dónde salió: "cámara" (Camera2), "hoja" (estimada) o "supuesta". */
    public String source = "supuesta";

    public Lens(double f, double cx, double cy) {
        this.f = f;
        this.cx = cx;
        this.cy = cy;
    }

    public Lens copy() {
        Lens l = new Lens(f, cx, cy);
        l.k1 = k1;
        l.k2 = k2;
        l.source = source;
        return l;
    }

    /** Coordenadas normalizadas sin distorsión del píxel (u, v). */
    public double[] normalize(double u, double v) {
        double xd = (u - cx) / f, yd = (v - cy) / f;
        if (k1 == 0 && k2 == 0) return new double[]{xd, yd};
        double x = xd, y = yd;
        for (int it = 0; it < 8; it++) {
            double r2 = x * x + y * y, g = 1 + k1 * r2 + k2 * r2 * r2;
            x = xd / g;
            y = yd / g;
        }
        return new double[]{x, y};
    }

    /** Píxel de un punto en coordenadas de la cámara (con distorsión). */
    public double[] project(double[] xc) {
        double x = xc[0] / xc[2], y = xc[1] / xc[2], r2 = x * x + y * y, g = 1 + k1 * r2 + k2 * r2 * r2;
        return new double[]{f * x * g + cx, f * y * g + cy};
    }

    /**
     * Imagen sin distorsión (misma focal y tamaño): cada píxel toma el gris
     * o color de donde lo puso el lente. Con ella, el resto del proceso
     * (seguimiento, siluetas, tallado, textura) puede seguir suponiendo una
     * cámara ideal. Devuelve la misma imagen si no hay distorsión.
     */
    public int[] undistort(int[] argb, int w, int h) {
        if (k1 == 0 && k2 == 0) return argb;
        int[] out = new int[argb.length];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                double nx = (x - cx) / f, ny = (y - cy) / f, r2 = nx * nx + ny * ny, g = 1 + k1 * r2 + k2 * r2 * r2;
                double u = f * nx * g + cx, v = f * ny * g + cy;
                out[y * w + x] = u < 0 || v < 0 || u > w - 1 || v > h - 1 ? 0xFF000000 : Texture.bilinear(argb, w, h, u, v);
            }
        return out;
    }

    // ------------------------------------------------------------ estimación con la hoja

    /** Marcas mínimas en un cuadro para usarlo (ubicar la cámara y estimar el lente). */
    public static final int MIN_MARKERS = 4;

    /**
     * Focal y k1 que mejor explican las marcas de {@code frames} (cada uno,
     * las marcas de un cuadro), partiendo de {@code init}: búsqueda por
     * coordenadas (sección áurea) sobre la mediana del error de las
     * esquinas. Usa a lo sumo 40 cuadros repartidos. Devuelve un lente
     * nuevo con {@code source = "hoja"}, o null si hay menos de 8 cuadros.
     */
    public static Lens estimate(List<List<Markers.Marker>> frames, Lens init) {
        List<List<Markers.Marker>> use = new ArrayList<>();
        for (List<Markers.Marker> ms : frames) if (ms.size() >= MIN_MARKERS) use.add(ms);
        if (use.size() < 8) return null;
        int step = Math.max(1, use.size() / 40);
        List<List<Markers.Marker>> sample = new ArrayList<>();
        for (int i = 0; i < use.size(); i += step) sample.add(use.get(i));
        Lens l = init.copy();
        l.k2 = 0;
        for (int round = 0; round < 3; round++) {
            double span = round == 0 ? 0.35 : round == 1 ? 0.1 : 0.03;
            final Lens cur = l;
            double f = golden(v -> { Lens t = cur.copy(); t.f = v; return cost(sample, t); }, cur.f * (1 - span), cur.f * (1 + span));
            l.f = f;
            double kspan = round == 0 ? 0.4 : round == 1 ? 0.12 : 0.04;
            final Lens cur2 = l;
            l.k1 = golden(v -> { Lens t = cur2.copy(); t.k1 = v; return cost(sample, t); }, cur2.k1 - kspan, cur2.k1 + kspan);
        }
        l.source = "hoja";
        return l;
    }

    /** Mediana del error (px) de las esquinas de las marcas, ubicando la cámara en cada cuadro con el lente {@code l}. */
    public static double cost(List<List<Markers.Marker>> frames, Lens l) {
        double[] r = new double[frames.size()];
        int n = 0;
        for (List<Markers.Marker> ms : frames) {
            SheetPose.Fix fx = SheetPose.solve(ms, l);
            if (fx != null) r[n++] = fx.rms;
        }
        if (n == 0) return 1e9;
        r = Arrays.copyOf(r, n);
        Arrays.sort(r);
        return r[n / 2];
    }

    interface Fn { double at(double v); }

    /** Mínimo de {@code fn} en [a, b] por sección áurea (24 evaluaciones). */
    static double golden(Fn fn, double a, double b) {
        double g = (Math.sqrt(5) - 1) / 2, c = b - g * (b - a), d = a + g * (b - a), fc = fn.at(c), fd = fn.at(d);
        for (int it = 0; it < 22; it++) {
            if (fc < fd) { b = d; d = c; fd = fc; c = b - g * (b - a); fc = fn.at(c); }
            else { a = c; c = d; fc = fd; d = a + g * (b - a); fd = fn.at(d); }
        }
        return (a + b) / 2;
    }
}
