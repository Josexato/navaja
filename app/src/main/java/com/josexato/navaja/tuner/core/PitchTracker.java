package com.josexato.navaja.tuner.core;

import java.util.Arrays;

/**
 * Postproceso de las estimaciones cuadro a cuadro (~21 ms cada una):
 *
 *  - Ignora el ataque: los primeros cuadros tras el silencio (el golpe de la
 *    púa o del arco tiene muchos armónicos y ruido).
 *  - Una nota nueva sólo se acepta cuando varios cuadros SEGUIDOS coinciden
 *    entre sí (confirmación). Un salto aislado a un armónico no la cambia.
 *  - Los saltos a razones armónicas (x2, x3, /2…) exigen el doble de
 *    confirmación: son casi siempre errores, no un cambio de cuerda.
 *  - Mientras la nota es estable, la salida es la mediana de las últimas N.
 */
public final class PitchTracker {

    /** Perfiles de respuesta: más estable = más lento pero más preciso. */
    public enum Response {
        RAPIDA(5, 2, 1), NORMAL(7, 4, 3), ESTABLE(11, 6, 5);

        final int median, confirm, onsetSkip;

        Response(int median, int confirm, int onsetSkip) {
            this.median = median; this.confirm = confirm; this.onsetSkip = onsetSkip;
        }
    }

    private static final double SAME_NOTE_CENTS = 60;   // dentro de esto, es la misma nota
    private static final double CONSISTENT_CENTS = 35;  // coherencia exigida al confirmar

    private final Response resp;
    private final long resetAfterMs;
    private final double[] hist;
    private int n = 0, head = 0;
    private final double[] pending;
    private int pn = 0;
    private int onset = 0;
    private double stable = Double.NaN;
    private long lastPitchMs = Long.MIN_VALUE / 2;

    public PitchTracker(Response resp, long resetAfterMs) {
        this.resp = resp;
        this.resetAfterMs = resetAfterMs;
        this.hist = new double[resp.median];
        this.pending = new double[resp.confirm * 2];
    }

    /** Añade una estimación (NaN = sin tono) y devuelve la frecuencia estable o NaN. */
    public double push(double f, long nowMs) {
        if (Double.isNaN(f)) {
            pn = 0;
            if (nowMs - lastPitchMs > resetAfterMs) reset();
            return stable;
        }
        lastPitchMs = nowMs;
        if (Double.isNaN(stable) && onset < resp.onsetSkip) {
            onset++;
            return stable;
        }
        if (!Double.isNaN(stable) && Math.abs(cents(f, stable)) < SAME_NOTE_CENTS) {
            pn = 0;
            add(f);
            stable = median();
            return stable;
        }
        // Posible nota nueva (o un armónico suelto): acumular y exigir coherencia.
        if (pn > 0 && Math.abs(cents(f, pending[pn - 1])) > CONSISTENT_CENTS) pn = 0;
        if (pn < pending.length) pending[pn++] = f;
        int need = Double.isNaN(stable) ? resp.confirm
                : isHarmonicJump(f, stable) ? resp.confirm * 2 : resp.confirm;
        if (pn >= need) {
            n = 0;
            for (int i = 0; i < pn; i++) add(pending[i]);
            pn = 0;
            stable = median();
        }
        return stable;
    }

    public void reset() {
        n = 0;
        pn = 0;
        onset = 0;
        stable = Double.NaN;
    }

    /** ¿f está a una razón armónica (2, 3, 4, 1/2, 1/3, 3/2) de la nota estable? */
    static boolean isHarmonicJump(double f, double ref) {
        double[] ratios = {2, 3, 4, 0.5, 1 / 3.0, 1.5, 2 / 3.0};
        for (double r : ratios) if (Math.abs(cents(f, ref * r)) < 40) return true;
        return false;
    }

    private static double cents(double a, double b) {
        return 1200 * Math.log(a / b) / Math.log(2);
    }

    private void add(double f) {
        hist[head] = f;
        head = (head + 1) % hist.length;
        if (n < hist.length) n++;
    }

    private double median() {
        double[] a = new double[n];
        for (int i = 0; i < n; i++) a[i] = hist[(head - 1 - i + hist.length) % hist.length];
        Arrays.sort(a);
        return n % 2 == 1 ? a[n / 2] : 0.5 * (a[n / 2 - 1] + a[n / 2]);
    }
}
