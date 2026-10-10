package com.josexato.navaja.tuner.core;

import java.util.Arrays;

/**
 * Estabiliza la lectura: mediana de las últimas estimaciones (descarta saltos
 * de octava puntuales al pulsar la cuerda) y reinicia tras un silencio.
 */
public final class PitchTracker {

    private final double[] hist;
    private int n = 0, head = 0;
    private long lastPitchMs = Long.MIN_VALUE / 2;
    private final long resetAfterMs;

    public PitchTracker(int size, long resetAfterMs) {
        this.hist = new double[size];
        this.resetAfterMs = resetAfterMs;
    }

    /** Añade una estimación (NaN = sin tono) y devuelve la frecuencia estable o NaN. */
    public double push(double f, long nowMs) {
        if (Double.isNaN(f)) {
            if (nowMs - lastPitchMs > resetAfterMs) n = 0;
            return n == 0 ? Double.NaN : median();
        }
        // Un cambio grande (otra cuerda) reinicia el historial para responder rápido.
        if (n > 0 && Math.abs(1200 * Math.log(f / median()) / Math.log(2)) > 150) n = 0;
        hist[head] = f;
        head = (head + 1) % hist.length;
        if (n < hist.length) n++;
        lastPitchMs = nowMs;
        return median();
    }

    private double median() {
        double[] a = new double[n];
        for (int i = 0; i < n; i++) a[i] = hist[(head - 1 - i + hist.length) % hist.length];
        Arrays.sort(a);
        return n % 2 == 1 ? a[n / 2] : 0.5 * (a[n / 2 - 1] + a[n / 2]);
    }
}
