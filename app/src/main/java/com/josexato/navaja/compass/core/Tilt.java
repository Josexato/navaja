package com.josexato.navaja.compass.core;

/**
 * Nivel a partir del vector de gravedad del acelerómetro, en coordenadas del
 * teléfono (x a la derecha, y hacia arriba de la pantalla, z saliendo de la
 * pantalla). En reposo boca arriba se mide (0, 0, +9,81).
 *
 * <ul>
 * <li><b>Apoyado plano</b>: dos inclinaciones (izquierda‑derecha y
 *     adelante‑atrás) y una burbuja en 2D.</li>
 * <li><b>De canto</b> (parado sobre un borde, vertical u horizontal): el
 *     ángulo del borde respecto de la horizontal, como un nivel de barra.</li>
 * </ul>
 */
public final class Tilt {

    private Tilt() { }

    public static final double G = 9.80665;

    public enum Mode { FLAT, EDGE }

    public static final class Reading {
        public final Mode mode;
        /** FLAT: inclinación izquierda‑derecha (+ = lado derecho más alto), grados. */
        public final double x;
        /** FLAT: inclinación adelante‑atrás (+ = borde superior más alto), grados. EDGE: 0. */
        public final double y;
        /** EDGE: desvío del borde respecto del nivel más cercano (0°/90°/180°/270°), grados. */
        public final double edge;
        /** EDGE: a qué nivel está más cerca (0 = de pie normal, 90/270 = apaisado, 180 = cabeza abajo). */
        public final int edgeBase;
        /** true si la aceleración total no es ~g: el teléfono se está moviendo. */
        public final boolean moving;

        Reading(Mode mode, double x, double y, double edge, int edgeBase, boolean moving) {
            this.mode = mode;
            this.x = x;
            this.y = y;
            this.edge = edge;
            this.edgeBase = edgeBase;
            this.moving = moving;
        }

        /** Inclinación total (grados), para el semáforo. */
        public double total() {
            return mode == Mode.FLAT ? Math.toDegrees(Math.acos(Math.cos(Math.toRadians(x)) * Math.cos(Math.toRadians(y))))
                    : Math.abs(edge);
        }
    }

    /**
     * Interpreta la gravedad. Con más de 45° respecto de la horizontal se
     * considera "de canto"; si no, "plano". offsetX/offsetY: calibración del
     * modo plano (grados que se restan).
     */
    public static Reading read(double gx, double gy, double gz, double offsetX, double offsetY) {
        double mag = Math.sqrt(gx * gx + gy * gy + gz * gz);
        boolean moving = Math.abs(mag - G) > 0.08 * G;
        double fromFlat = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, Math.abs(gz) / Math.max(1e-9, mag)))));
        if (fromFlat <= 45) {
            double sz = gz >= 0 ? 1 : -1; // boca abajo también sirve
            double x = Math.toDegrees(Math.atan2(gx, sz * gz)) - offsetX;
            double y = Math.toDegrees(Math.atan2(gy, sz * gz)) - offsetY;
            return new Reading(Mode.FLAT, x, y, 0, 0, moving);
        }
        // De canto: ángulo de la gravedad en el plano de la pantalla.
        // De pie normal (borde inferior abajo) la gravedad medida apunta a +y → 0°.
        double a = Math.toDegrees(Math.atan2(gx, gy)); // 0 = de pie; 90 = sobre el borde izquierdo; 270 = sobre el derecho
        int base = (int) (Math.round(Angles.norm360(a) / 90.0) % 4) * 90;
        double edge = Angles.diff(a, base);
        return new Reading(Mode.EDGE, 0, 0, edge, base, moving);
    }

    /**
     * Calibración en dos posiciones: se mide, se gira el teléfono 180° sobre
     * el mismo lugar y se vuelve a medir. La pendiente real de la superficie
     * cambia de signo al girar y el error del sensor no: error = promedio.
     * Así no hace falta una superficie perfectamente nivelada.
     */
    public static final class Calibration {
        private double x1, y1;
        private int step;

        /** Paso actual: 0 = sin empezar, 1 = esperando la segunda medición. */
        public int step() { return step; }

        public void first(double rawX, double rawY) {
            x1 = rawX;
            y1 = rawY;
            step = 1;
        }

        /** Devuelve {offsetX, offsetY}. */
        public double[] second(double rawX, double rawY) {
            if (step != 1) throw new IllegalStateException("falta la primera medición");
            step = 0;
            // Girado 180°: los ejes x e y del teléfono quedan invertidos respecto de la superficie.
            return new double[]{(x1 + rawX) / 2, (y1 + rawY) / 2};
        }

        /** Pendiente de la superficie medida durante la calibración (grados). */
        public static double[] surface(double x1, double y1, double x2, double y2) {
            return new double[]{(x1 - x2) / 2, (y1 - y2) / 2};
        }
    }

    /** Pendiente en % (para obras: 1° ≈ 1,75 %). */
    public static double percent(double deg) { return Math.tan(Math.toRadians(deg)) * 100; }

    /** Desnivel en mm por metro. */
    public static double mmPerMeter(double deg) { return Math.tan(Math.toRadians(deg)) * 1000; }
}
