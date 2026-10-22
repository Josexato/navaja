package com.josexato.navaja.compass.core;

/**
 * Ángulos de brújula: normalización, filtro circular (sin saltos en 359°→0°),
 * puntos cardinales en español y rumbo/distancia entre dos coordenadas.
 */
public final class Angles {

    private Angles() { }

    static final double EARTH_R = 6_371_008.8;

    /** Lleva cualquier ángulo a [0, 360). */
    public static double norm360(double deg) {
        double d = deg % 360;
        return d < 0 ? d + 360 : d;
    }

    /** Diferencia más corta a − b en (−180, 180]. */
    public static double diff(double a, double b) {
        double d = norm360(a - b);
        return d > 180 ? d - 360 : d;
    }

    /** Rosa de 16 rumbos (O = oeste). */
    static final String[] CARDINAL = {"N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
            "S", "SSO", "SO", "OSO", "O", "ONO", "NO", "NNO"};

    public static String cardinal(double deg) {
        return CARDINAL[(int) Math.floor(norm360(deg) / 22.5 + 0.5) % 16];
    }

    /**
     * Suavizado exponencial de un ángulo sobre el círculo (promedia seno y
     * coseno): pasar de 359° a 1° no hace dar la vuelta a la aguja.
     */
    public static final class CircularFilter {
        private final double alpha;
        private double s, c;
        private boolean init;

        /** alpha: 0–1, cuánto pesa la medición nueva (más chico = más suave). */
        public CircularFilter(double alpha) { this.alpha = alpha; }

        public double update(double deg) {
            double r = Math.toRadians(deg);
            if (!init) {
                s = Math.sin(r);
                c = Math.cos(r);
                init = true;
            } else {
                s += alpha * (Math.sin(r) - s);
                c += alpha * (Math.cos(r) - c);
            }
            return value();
        }

        public double value() { return norm360(Math.toDegrees(Math.atan2(s, c))); }

        /** 0–1: qué tan estable está (1 = las lecturas recientes coinciden). */
        public double stability() { return Math.min(1, Math.hypot(s, c)); }

        public void reset() { init = false; }
    }

    /** Rumbo inicial (gran círculo) desde el punto 1 al 2, en grados desde el norte verdadero. */
    public static double bearing(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2), dl = Math.toRadians(lon2 - lon1);
        double y = Math.sin(dl) * Math.cos(p2);
        double x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
        return norm360(Math.toDegrees(Math.atan2(y, x)));
    }

    /** Distancia por haversine (metros). */
    public static double distance(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dp = p2 - p1, dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * EARTH_R * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    /** "850 m" o "12,4 km". */
    public static String formatDistance(double m) {
        if (m < 1000) return Math.round(m) + " m";
        return String.format(java.util.Locale.US, "%.1f km", m / 1000).replace('.', ',');
    }
}
