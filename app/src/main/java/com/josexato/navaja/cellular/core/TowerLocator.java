package com.josexato.navaja.cellular.core;

import java.util.List;

/**
 * Estima la posición de una estación base a partir de varias mediciones
 * (posición GPS del teléfono, distancia por Timing Advance) tomadas mientras
 * uno se mueve: multilateración por mínimos cuadrados ponderados, todo en el
 * teléfono y sin bases de datos externas.
 *
 * <p>Modelo: d_i ≈ |torre − p_i|, con error σ_i² = (paso_TA/2)²/3 + precisión_GPS².
 * Se resuelve en un plano local (este/norte en metros alrededor del centroide):
 * búsqueda en grilla para no caer en un mínimo local y luego Gauss‑Newton.
 */
public final class TowerLocator {

    private TowerLocator() { }

    /** Una medición: dónde estaba el teléfono y a qué distancia decía el TA. */
    public static final class Obs {
        public final double lat, lon, gpsAccM, distM, stepM;

        public Obs(double lat, double lon, double gpsAccM, double distM, double stepM) {
            this.lat = lat;
            this.lon = lon;
            this.gpsAccM = gpsAccM;
            this.distM = distM;
            this.stepM = stepM;
        }
    }

    public static final class Estimate {
        public final double lat, lon;
        /** Radio de incertidumbre aproximado (1σ, metros). */
        public final double uncertaintyM;
        /** Error cuadrático medio de las distancias (metros). */
        public final double rmsM;
        public final int used;
        /** true si las mediciones están casi en línea recta: hay una solución espejo. */
        public final boolean ambiguous;

        Estimate(double lat, double lon, double uncertaintyM, double rmsM, int used, boolean ambiguous) {
            this.lat = lat;
            this.lon = lon;
            this.uncertaintyM = uncertaintyM;
            this.rmsM = rmsM;
            this.used = used;
            this.ambiguous = ambiguous;
        }
    }

    static final double EARTH_R = 6_371_000.0;

    /** Mínimo de mediciones y de dispersión del recorrido para intentar estimar. */
    public static final int MIN_OBS = 4;
    public static final double MIN_SPREAD_M = 60;

    /** null si no hay datos suficientes (pocas mediciones o todas en el mismo lugar). */
    public static Estimate locate(List<Obs> obs) {
        int n = obs.size();
        if (n < MIN_OBS) return null;
        double lat0 = 0, lon0 = 0;
        for (Obs o : obs) {
            lat0 += o.lat;
            lon0 += o.lon;
        }
        lat0 /= n;
        lon0 /= n;
        double kx = Math.toRadians(1) * EARTH_R * Math.cos(Math.toRadians(lat0)), ky = Math.toRadians(1) * EARTH_R;
        double[] x = new double[n], y = new double[n], d = new double[n], w = new double[n];
        double maxD = 0;
        for (int i = 0; i < n; i++) {
            Obs o = obs.get(i);
            x[i] = (o.lon - lon0) * kx;
            y[i] = (o.lat - lat0) * ky;
            d[i] = o.distM;
            double sTa = o.stepM / 2;
            w[i] = 1 / (sTa * sTa / 3 + o.gpsAccM * o.gpsAccM + 1);
            maxD = Math.max(maxD, d[i]);
        }

        // Geometría del recorrido: ejes principales de las posiciones.
        double sxx = 0, syy = 0, sxy = 0;
        for (int i = 0; i < n; i++) {
            sxx += x[i] * x[i];
            syy += y[i] * y[i];
            sxy += x[i] * y[i];
        }
        sxx /= n;
        syy /= n;
        sxy /= n;
        double tr = sxx + syy, disc = Math.sqrt(Math.max(0, tr * tr / 4 - (sxx * syy - sxy * sxy)));
        double major = Math.sqrt(tr / 2 + disc), minor = Math.sqrt(Math.max(0, tr / 2 - disc));
        if (major < MIN_SPREAD_M / 2) return null;
        boolean ambiguous = minor < 0.15 * major || minor < 20;

        // Grilla gruesa que cubre todas las posiciones posibles.
        double span = maxD + 2 * major + 200;
        double bx = 0, by = 0, best = Double.MAX_VALUE;
        int g = 80;
        for (int a = 0; a <= g; a++) {
            for (int b = 0; b <= g; b++) {
                double px = -span + 2 * span * a / g, py = -span + 2 * span * b / g;
                double c = cost(px, py, x, y, d, w);
                if (c < best) { best = c; bx = px; by = py; }
            }
        }

        // Gauss‑Newton sobre r_i = |p − x_i| − d_i.
        double[] cov = null;
        for (int it = 0; it < 30; it++) {
            double a11 = 0, a12 = 0, a22 = 0, g1 = 0, g2 = 0;
            for (int i = 0; i < n; i++) {
                double dx = bx - x[i], dy = by - y[i], r = Math.max(1e-3, Math.hypot(dx, dy));
                double jx = dx / r, jy = dy / r, res = r - d[i];
                a11 += w[i] * jx * jx;
                a12 += w[i] * jx * jy;
                a22 += w[i] * jy * jy;
                g1 += w[i] * jx * res;
                g2 += w[i] * jy * res;
            }
            double det = a11 * a22 - a12 * a12;
            if (Math.abs(det) < 1e-18) break;
            double sx = (a22 * g1 - a12 * g2) / det, sy = (a11 * g2 - a12 * g1) / det;
            bx -= sx;
            by -= sy;
            cov = new double[]{a22 / det, a11 / det};
            if (Math.hypot(sx, sy) < 0.01) break;
        }

        double ss = 0;
        for (int i = 0; i < n; i++) {
            double res = Math.hypot(bx - x[i], by - y[i]) - d[i];
            ss += res * res;
        }
        double rms = Math.sqrt(ss / n);
        double unc = cov == null ? Double.NaN : Math.sqrt(cov[0] + cov[1]);
        double lat = lat0 + by / ky, lon = lon0 + bx / kx;
        return new Estimate(lat, lon, unc, rms, n, ambiguous);
    }

    private static double cost(double px, double py, double[] x, double[] y, double[] d, double[] w) {
        double c = 0;
        for (int i = 0; i < x.length; i++) {
            double r = Math.hypot(px - x[i], py - y[i]) - d[i];
            c += w[i] * r * r;
        }
        return c;
    }

    /** Distancia entre dos puntos geográficos (haversine, metros). */
    public static double distanceM(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dp = p2 - p1, dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * EARTH_R * Math.asin(Math.min(1, Math.sqrt(a)));
    }
}
