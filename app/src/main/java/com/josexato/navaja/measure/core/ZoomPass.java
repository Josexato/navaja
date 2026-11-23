package com.josexato.navaja.measure.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Segunda pasada de XFeat sobre el objeto ampliado (modo hoja). En la pasada
 * normal los 1024 puntos de cada foto se reparten por toda la imagen, hoja
 * incluida; aquí cada foto se recorta alrededor del objeto, se amplía al
 * tamaño de la foto y se sacan otros 1024 puntos ahí. Sólo se hace en las
 * fotos donde el objeto se ve chico (aumento de al menos {@link #MIN_ZOOM}).
 *
 * <p>La caja del objeto sale de una primera reconstrucción. Java puro.
 */
public final class ZoomPass {

    private ZoomPass() { }

    /** Aumento mínimo para que valga la pena, y máximo (más allá sólo se agranda sin detalle nuevo). */
    public static final double MIN_ZOOM = 1.3, MAX_ZOOM = 3;
    /** Margen del recorte alrededor de la caja proyectada. */
    public static final double MARGIN = 1.15;
    /** La caja: percentiles de los puntos del objeto (deja fuera los sueltos) más un margen en mm. */
    public static final double LOW = 0.05, PAD_MM = 12;
    public static final int MIN_POINTS = 30;

    /** Caja {mín, máx} (mm, ejes de la hoja) de los puntos {@code idx}; null si son muy pocos. El piso no baja de la hoja (z = 0). */
    public static double[][] box(double[][] points, int[] idx) {
        if (idx == null || idx.length < MIN_POINTS) return null;
        double[][] box = new double[2][3];
        double[] v = new double[idx.length];
        for (int c = 0; c < 3; c++) {
            for (int i = 0; i < idx.length; i++) v[i] = points[idx[i]][c];
            Arrays.sort(v);
            box[0][c] = v[(int) (LOW * (v.length - 1))] - PAD_MM;
            box[1][c] = v[(int) ((1 - LOW) * (v.length - 1))] + PAD_MM;
        }
        box[0][2] = Math.max(box[0][2], 0);
        return box;
    }

    /**
     * Recorte {x, y, ancho, alto} (px de la foto de {@code w}×{@code h}) que
     * contiene la caja, con la proporción de la foto. null si la caja no
     * queda delante de la cámara o si el objeto ya llena la foto.
     */
    public static double[] rect(double[][] box, Geometry.Pose pose, Lens lens, int w, int h) {
        double x0 = Double.MAX_VALUE, y0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, y1 = -Double.MAX_VALUE;
        double[] xc = new double[3];
        for (int k = 0; k < 8; k++) {
            double x = box[k & 1][0], y = box[(k >> 1) & 1][1], z = box[(k >> 2) & 1][2];
            for (int i = 0; i < 3; i++) xc[i] = pose.r[3 * i] * x + pose.r[3 * i + 1] * y + pose.r[3 * i + 2] * z + pose.t[i];
            if (xc[2] <= 0) return null;
            double[] p = lens.project(xc);
            x0 = Math.min(x0, p[0]);
            x1 = Math.max(x1, p[0]);
            y0 = Math.min(y0, p[1]);
            y1 = Math.max(y1, p[1]);
        }
        double rw = (x1 - x0) * MARGIN, rh = (y1 - y0) * MARGIN;
        if (rw <= 0 || rh <= 0) return null;
        double zoom = Math.min(MAX_ZOOM, Math.min(w / rw, h / rh));
        if (zoom < MIN_ZOOM) return null;
        rw = w / zoom;
        rh = h / zoom;
        double rx = Math.max(0, Math.min(w - rw, (x0 + x1) / 2 - rw / 2)), ry = Math.max(0, Math.min(h - rh, (y0 + y1) / 2 - rh / 2));
        return new double[]{rx, ry, rw, rh};
    }

    /**
     * El recorte ampliado a {@code w}×{@code h} (bilineal). {@code src} es el
     * mismo cuadro con más resolución ({@code sw}×{@code sh}, la del video) o
     * la misma; {@code rect} está en px de la foto de {@code w}×{@code h}.
     */
    public static int[] crop(int[] src, int sw, int sh, double[] rect, int w, int h) {
        int[] out = new int[w * h];
        double kx = sw / (double) w, ky = sh / (double) h;
        for (int y = 0; y < h; y++) {
            double sy = (rect[1] + (y + 0.5) * rect[3] / h) * ky - 0.5;
            int iy = (int) Math.max(0, Math.min(sh - 2, Math.floor(sy)));
            double fy = Math.max(0, Math.min(1, sy - iy));
            for (int x = 0; x < w; x++) {
                double sx = (rect[0] + (x + 0.5) * rect[2] / w) * kx - 0.5;
                int ix = (int) Math.max(0, Math.min(sw - 2, Math.floor(sx)));
                double fx = Math.max(0, Math.min(1, sx - ix));
                int a = src[iy * sw + ix], b = src[iy * sw + ix + 1], c = src[(iy + 1) * sw + ix], d = src[(iy + 1) * sw + ix + 1], v = 0;
                for (int s = 0; s < 24; s += 8)
                    v |= (int) Math.round((((a >> s) & 255) * (1 - fx) + ((b >> s) & 255) * fx) * (1 - fy) + (((c >> s) & 255) * (1 - fx) + ((d >> s) & 255) * fx) * fy) << s;
                out[y * w + x] = 0xff000000 | v;
            }
        }
        return out;
    }

    /** Pasa los puntos de {@code f} (hallados en el recorte ampliado) a px de la foto entera. La posición para el emparejador no cambia. */
    public static void toPhoto(XFeatTracks.Frame f, double[] rect, int w, int h) {
        for (int k = 0; k < f.n; k++) {
            f.x[k] = (float) (rect[0] + (f.x[k] + 0.5) * rect[2] / w - 0.5);
            f.y[k] = (float) (rect[1] + (f.y[k] + 0.5) * rect[3] / h - 0.5);
        }
    }

    /**
     * Suma a la reconstrucción los puntos de las pistas de la segunda pasada:
     * las poses ya se conocen, así que cada pista sólo se triangula (como los
     * puntos densos), con el lente de la hoja. Se aceptan con 3 fotos o más,
     * error menor a 2 px y al menos 2° de ángulo entre vistas. Devuelve
     * cuántos agregó.
     */
    public static int add(Sfm.Result r, List<Sfm.Track> tracks, Lens lens) {
        List<double[]> pts = new ArrayList<>(Arrays.asList(r.points));
        List<Integer> cols = new ArrayList<>(), obsN = new ArrayList<>();
        for (int i = 0; i < r.points.length; i++) { cols.add(r.colors[i]); obsN.add(r.nObs[i]); }
        int added = 0;
        List<Geometry.Pose> ps = new ArrayList<>();
        List<double[]> ob = new ArrayList<>(), px = new ArrayList<>();
        for (Sfm.Track t : tracks) {
            ps.clear();
            ob.clear();
            px.clear();
            for (int c = 0; c < r.camFrames.size(); c++) {
                int fr = r.camFrames.get(c);
                if (!t.visible(fr)) continue;
                ps.add(r.poses.get(c));
                ob.add(lens.normalize(t.x(fr), t.y(fr)));
                px.add(new double[]{t.x(fr), t.y(fr)});
            }
            if (ps.size() < 3) continue;
            Geometry.Pose[] pa = ps.toArray(new Geometry.Pose[0]);
            double[][] oa = ob.toArray(new double[0][]);
            double[] x = Geometry.triangulate(pa, oa);
            if (x == null) continue;
            if (r.sheetPlane) {
                double[] s = SheetPlane.snap(pa, oa, x, lens.f);
                if (s != null) x = s;
            }
            boolean ok = true;
            for (int i = 0; i < pa.length && ok; i++) {
                double[] xc = pa[i].apply(x);
                if (xc[2] <= 0) { ok = false; break; }
                double[] p = lens.project(xc);
                ok = Math.hypot(p[0] - px.get(i)[0], p[1] - px.get(i)[1]) < 2;
            }
            if (!ok || SheetScan.parallax(x, pa) < 2) continue;
            pts.add(x);
            cols.add(t.color);
            obsN.add(pa.length);
            added++;
        }
        r.points = pts.toArray(new double[0][]);
        r.colors = new int[cols.size()];
        r.nObs = new int[cols.size()];
        for (int i = 0; i < cols.size(); i++) { r.colors[i] = cols.get(i); r.nObs[i] = obsN.get(i); }
        return added;
    }
}
