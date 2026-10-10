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
        this.minRms = 0.004;
        this.diff = new float[tauMax + 2];
    }

    /** Muestras necesarias por llamada: ventana + retardo máximo. */
    public int bufferSize() { return window + tauMax + 1; }

    /**
     * Mínimos de d' por encima de esto no se consideran periódicos. Es alto a
     * propósito: con varias cuerdas sonando a la vez (la 6ª con la 5ª y 4ª aún
     * vibrando) la nota real da d' ~0.45-0.55. El filtrado fino lo hace la
     * coherencia entre cuadros en PitchTracker.
     */
    private static final float MAX_CANDIDATE = 0.6f;
    /** Penalización máxima por estar lejos de cualquier cuerda. */
    private static final double PRIOR_WEIGHT = 0.25;
    /** Leve preferencia por períodos cortos: evita caer en subarmónicos (2P, 3P). */
    private static final double TAU_BIAS = 0.01;

    private double[] targets;

    /**
     * Frecuencias esperadas (cuerdas del instrumento, o una sola si está fija).
     * Los candidatos a más de 3 semitonos de todas ellas se penalizan; null = sin preferencia.
     */
    public void setTargets(double[] freqs) { targets = freqs; }

    private double prior(double f) {
        if (targets == null) return 0;
        double minCents = Double.MAX_VALUE;
        for (double ft : targets) minCents = Math.min(minCents, Math.abs(1200 * Math.log(f / ft) / Math.log(2)));
        return PRIOR_WEIGHT * Math.min(1, minCents / 300);
    }

    /** Posición sub-muestra del mínimo por parábola. */
    private double refine(int tau) {
        if (tau <= 1 || tau >= tauMax) return tau;
        double a = diff[tau - 1], b = diff[tau], c = diff[tau + 1];
        double den = a - 2 * b + c;
        return den > 0 ? tau + 0.5 * (a - c) / den : tau;
    }

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

        // 3) Candidatos = todos los mínimos locales claros de d'. El YIN clásico se
        //    queda con el PRIMERO bajo el umbral, y con un micrófono de teléfono
        //    (fundamental grave atenuada) ese suele ser un armónico. Aquí gana el
        //    más periódico, penalizando los que caen lejos de las cuerdas.
        int best = -1;
        double bestScore = Double.MAX_VALUE;
        for (int tau = tauMin; tau <= tauMax; tau++) {
            float v = diff[tau];
            if (v >= MAX_CANDIDATE || v > diff[tau - 1] || (tau < tauMax && v > diff[tau + 1])) continue;
            double fc = sampleRate / refine(tau);
            double score = v + prior(fc) + TAU_BIAS * Math.log(tau / (double) tauMin);
            if (score < bestScore) { bestScore = score; best = tau; }
        }
        if (best < 0) return r;
        r.clarity = 1 - diff[best];

        // 4) Interpolación parabólica para resolución sub-muestra (precisión < 1 cent).
        double t = refine(best);
        r.freq = sampleRate / t;
        return r;
    }
}
