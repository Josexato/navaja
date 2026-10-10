package com.josexato.navaja.tuner.core;

import java.util.Arrays;

/**
 * Postproceso de las estimaciones cuadro a cuadro (~21 ms cada una):
 *
 *  - Ignora el ataque: los primeros cuadros tras el silencio (el golpe de la
 *    púa o del arco tiene muchos armónicos y ruido).
 *  - Una nota nueva sólo se acepta cuando varios cuadros recientes coinciden
 *    entre sí (votación en una ventana corta). Un salto aislado a un armónico
 *    no la cambia, y dos cuerdas sonando a la vez no la bloquean.
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
    private static final double ONSET_RATIO = 1.6;      // subida de nivel que cuenta como ataque
    private static final long ONSET_WINDOW_MS = 500;    // tras un ataque se admite cambiar de nota
    private static final long GONE_MS = 300;            // o si la nota actual lleva esto sin oírse
    private static final double MIN_ONSET_RMS = 0.01;   // por debajo, una subida es ruido

    private final Response resp;
    private final long resetAfterMs;
    private final double[] hist;
    private int n = 0, head = 0;
    private final double[] pending;
    private final long[] pendingMs;
    private int pn = 0;
    private int onset = 0;
    private double stable = Double.NaN;
    private long lastPitchMs = Long.MIN_VALUE / 2;
    private long lastOnsetMs = Long.MIN_VALUE / 2, lastSeenStableMs = Long.MIN_VALUE / 2;
    private double floor = Double.NaN, level = Double.NaN;

    public PitchTracker(Response resp, long resetAfterMs) {
        this.resp = resp;
        this.resetAfterMs = resetAfterMs;
        this.hist = new double[resp.median];
        this.pending = new double[resp.confirm * 4];
        this.pendingMs = new long[resp.confirm * 4];
    }

    /** Añade una estimación sin información de nivel (sin compuerta de ataque). */
    public double push(double f, long nowMs) {
        return push(f, Double.NaN, nowMs);
    }

    /**
     * Añade una estimación (NaN = sin tono) con el nivel RMS del cuadro y
     * devuelve la frecuencia estable o NaN.
     *
     * Con el nivel se detectan los ATAQUES (subida brusca): una nota distinta
     * sólo reemplaza a la actual poco después de un ataque o cuando la actual
     * ya no se oye. Así una cuerda que vibra por simpatía de fondo no le quita
     * el sitio a la que se acaba de pulsar mientras ésta se apaga.
     */
    public double push(double f, double rms, long nowMs) {
        boolean gated = !Double.isNaN(rms);
        if (gated) {
            // Piso de ruido: sólo con cuadros SIN nota (si no, seguiría a la nota que se apaga).
            if (Double.isNaN(f)) floor = Double.isNaN(floor) ? rms : Math.min(floor * 1.01, rms);
            // Ataque = subida relativa brusca del nivel (también si otra cuerda sigue sonando).
            if (!Double.isNaN(level) && rms > ONSET_RATIO * level && rms > MIN_ONSET_RMS) lastOnsetMs = nowMs;
            level = Double.isNaN(level) ? rms : level + 0.3 * (rms - level);
        }
        if (Double.isNaN(f)) {
            // Un cuadro dudoso suelto no borra la confirmación en curso; un silencio largo sí.
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
            lastSeenStableMs = nowMs;
            add(f);
            stable = median();
            return stable;
        }
        // Posible nota nueva (o un armónico/cuerda simpática): se acepta cuando, entre
        // los cuadros recientes, al menos "need" coinciden con éste (mayoría, no
        // necesariamente seguidos: con dos cuerdas sonando los cuadros alternan).
        if (pn == pending.length) {
            System.arraycopy(pending, 1, pending, 0, pn - 1);
            System.arraycopy(pendingMs, 1, pendingMs, 0, pn - 1);
            pn--;
        }
        pending[pn] = f;
        pendingMs[pn++] = nowMs;
        int need = Double.isNaN(stable) ? resp.confirm
                : isHarmonicJump(f, stable) ? resp.confirm * 2 : resp.confirm;
        double[] agree = new double[pn];
        int k = 0;
        for (int i = 0; i < pn; i++)
            if (nowMs - pendingMs[i] <= windowMs() && Math.abs(cents(pending[i], f)) <= CONSISTENT_CENTS)
                agree[k++] = pending[i];
        boolean recentOnset = nowMs - lastOnsetMs <= ONSET_WINDOW_MS;
        boolean allowed = !gated
                // 1ª nota: con ataque, o claramente por encima del ruido (arco que entra suave).
                || (Double.isNaN(stable) ? recentOnset || (!Double.isNaN(floor) && rms > 6 * floor)
                // Cambio de nota: tras un ataque o cuando la actual ya no se oye.
                : recentOnset || nowMs - lastSeenStableMs >= GONE_MS);
        if (k >= need && allowed) {
            lastSeenStableMs = nowMs;
            n = 0;
            for (int i = 0; i < k; i++) add(agree[i]);
            pn = 0;
            stable = median();
        }
        return stable;
    }

    /** Ventana de la votación: ~2.5 veces lo que tardan los cuadros necesarios. */
    private long windowMs() { return Math.max(150, resp.confirm * 2 * 21L * 5 / 4); }

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
