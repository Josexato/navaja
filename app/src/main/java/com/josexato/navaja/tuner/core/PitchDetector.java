package com.josexato.navaja.tuner.core;

/**
 * Detector de frecuencia fundamental YIN (de Cheveigné y Kawahara, 2002).
 *
 * Sobre una ventana de audio calcula la función diferencia normalizada
 * d'(tau); el primer mínimo por debajo del umbral es el período. YIN evita
 * casi todos los errores de octava típicos de la autocorrelación, lo que
 * importa en la guitarra: sus cuerdas graves tienen armónicos más fuertes
 * que la fundamental.
 */
public final class PitchDetector {

    /** Resultado de una ventana. freq = NaN si no hay tono claro. */
    public static final class Result {
        public double freq = Double.NaN;
        /** 0..1: 1 = periódico perfecto (1 - d' en el mínimo). */
        public double clarity;
        /** Nivel RMS de la ventana (señal normalizada a ±1). */
        public double rms;

        public boolean pitched() { return !Double.isNaN(freq); }
    }

    private final int sampleRate;
    private final int window;
    private final int tauMin, tauMax;
    private final double threshold;
    private final double minRms;
    private final float[] diff;

    /**
     * @param sampleRate Hz
     * @param window     muestras analizadas (debe cubrir >= 2 períodos de la nota más grave)
     * @param minFreq    frecuencia más grave buscada (Hz)
     * @param maxFreq    frecuencia más aguda buscada (Hz)
     */
    public PitchDetector(int sampleRate, int window, double minFreq, double maxFreq) {
        this.sampleRate = sampleRate;
        this.window = window;
        this.tauMin = Math.max(2, (int) Math.floor(sampleRate / maxFreq));
        this.tauMax = Math.min(window / 2, (int) Math.ceil(sampleRate / minFreq));
        this.threshold = 0.12;
        this.minRms = 0.004;
        this.diff = new float[tauMax + 2];
    }

    /** Muestras necesarias por llamada: ventana + retardo máximo. */
    public int bufferSize() { return window + tauMax + 1; }

    public Result detect(float[] x) {
        Result r = new Result();
        double e = 0;
        for (int i = 0; i < window; i++) e += x[i] * x[i];
        r.rms = Math.sqrt(e / window);
        if (r.rms < minRms) return r;

        // 1-2) Función diferencia y su normalización acumulada (CMNDF).
        diff[0] = 1;
        double running = 0;
        for (int tau = 1; tau <= tauMax; tau++) {
            double s = 0;
            for (int j = 0; j < window; j++) {
                double d = x[j] - x[j + tau];
                s += d * d;
            }
            running += s;
            diff[tau] = running == 0 ? 1 : (float) (s * tau / running);
        }

        // 3) Umbral absoluto: primer tau bajo el umbral, bajando hasta su mínimo local.
        int best = -1;
        for (int tau = tauMin; tau <= tauMax; tau++) {
            if (diff[tau] < threshold) {
                while (tau + 1 <= tauMax && diff[tau + 1] < diff[tau]) tau++;
                best = tau;
                break;
            }
        }
        if (best < 0) {
            // Sin cruce del umbral: el mínimo global sirve si es razonablemente claro.
            float min = Float.MAX_VALUE;
            for (int tau = tauMin; tau <= tauMax; tau++)
                if (diff[tau] < min) { min = diff[tau]; best = tau; }
            if (min > 0.35f) return r;
        }
        r.clarity = 1 - diff[best];

        // 4) Interpolación parabólica para resolución sub-muestra (precisión < 1 cent).
        double t = best;
        if (best > 1 && best < tauMax) {
            double a = diff[best - 1], b = diff[best], c = diff[best + 1];
            double den = a - 2 * b + c;
            if (den > 0) t = best + 0.5 * (a - c) / den;
        }
        r.freq = sampleRate / t;
        return r;
    }
}
