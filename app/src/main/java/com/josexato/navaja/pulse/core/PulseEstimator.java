package com.josexato.navaja.pulse.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Frecuencia cardíaca a partir del color medio de una región de piel, cuadro
 * a cuadro.
 * <ul>
 * <li><b>Rostro</b> (sin contacto, rPPG): algoritmo POS de Wang, den Brinker,
 *     Stuijk y de Haan (2017), que proyecta el RGB normalizado en un plano
 *     ortogonal al tono de piel para cancelar cambios de brillo y reflejos.</li>
 * <li><b>Dedo</b> (yema sobre la cámara con flash, PPG de contacto): canal
 *     rojo invertido; además latidos individuales, RMSSD e índice de
 *     perfusión.</li>
 * </ul>
 * La señal se remuestrea a 30 Hz, se filtra en 0,7–3,5 Hz y la frecuencia se
 * toma del pico del espectro (con interpolación). La confianza sale de la
 * relación señal/ruido del espectro: si es baja, no se informa un valor.
 */
public final class PulseEstimator {

    public enum Mode { FACE, FINGER }


    public static final double FS = 30;           // Hz de remuestreo
    public static final double WINDOW_S = 10;     // segundos de análisis
    public static final double MIN_BPM = 42, MAX_BPM = 210;

    public static final class Result {
        /** Latidos por minuto, o NaN si no hay suficiente señal/confianza. */
        public double bpm = Double.NaN;
        /** 0–1: calidad de la lectura (SNR del espectro). */
        public double confidence;
        /** Señal filtrada de los últimos segundos (para dibujar), normalizada a ±1. */
        public double[] wave = new double[0];
        /** Dedo: RMSSD en ms (variabilidad entre latidos), NaN si no hay. */
        public double rmssdMs = Double.NaN;
        /** Dedo: índice de perfusión relativo (AC/DC en %), NaN si no hay. No es oxígeno en sangre. */
        public double perfusion = Double.NaN;
        /** Segundos de señal acumulada (para "midiendo… 6/10 s"). */
        public double seconds;
        /** Motivo si no hay lectura ("muévete menos", "más luz"…). */
        public String problem;
    }

    private final Mode mode;
    private final List<double[]> samples = new ArrayList<>(); // {t, r, g, b}

    public PulseEstimator(Mode mode) { this.mode = mode; }

    public Mode mode() { return mode; }

    public void reset() { samples.clear(); }

    /**
     * Agrega el color medio de la región (0–255) en el instante t (segundos).
     * Un salto brusco de brillo (movimiento, cambio de luz) reinicia la medición.
     */
    public boolean add(double t, double r, double g, double b) {
        if (!samples.isEmpty()) {
            double[] last = samples.get(samples.size() - 1);
            if (t <= last[0]) return false;
            double l0 = last[1] + last[2] + last[3], l1 = r + g + b;
            if (Math.abs(l1 - l0) / Math.max(1, l0) > 0.12 || t - last[0] > 1.0) {
                samples.clear(); // movimiento o pausa: la ventana ya no es continua
            }
        }
        samples.add(new double[]{t, r, g, b});
        // Se guarda un poco más que la ventana.
        while (samples.size() > 2 && t - samples.get(0)[0] > WINDOW_S + 1) samples.remove(0);
        return true;
    }

    public Result estimate() {
        Result res = new Result();
        int n0 = samples.size();
        if (n0 < 2) {
            res.problem = "midiendo…";
            return res;
        }
        double t0 = samples.get(0)[0], t1 = samples.get(n0 - 1)[0];
        res.seconds = t1 - t0;
        if (res.seconds < 5) {
            res.problem = "midiendo…";
            return res;
        }
        double start = Math.max(t0, t1 - WINDOW_S);
        int n = (int) Math.floor((t1 - start) * FS);
        double[][] rgb = resample(start, n);

        if (mode == Mode.FINGER) {
            double meanR = mean(rgb[0]);
            if (meanR < 60 || mean(rgb[1]) > 0.8 * meanR) {
                res.problem = "cubre la cámara y el flash con la yema del dedo";
                return res;
            }
            if (max(rgb[0]) > 252) {
                res.problem = "satura: apoya el dedo con menos presión";
            }
        }

        double[] raw = mode == Mode.FACE ? pos(rgb) : negate(rgb[0]);
        double[] sig = bandpass(detrend(raw), 0.7, 3.5);

        // Espectro 42–210 lpm con paso de 0,25 lpm.
        double best = -1, bestF = 0, total = 0;
        int steps = (int) ((MAX_BPM - MIN_BPM) / 0.25) + 1;
        double[] pw = new double[steps];
        for (int k = 0; k < steps; k++) {
            double f = (MIN_BPM + 0.25 * k) / 60;
            pw[k] = power(sig, f);
            total += pw[k];
            if (pw[k] > best) { best = pw[k]; bestF = f; }
        }
        int kb = (int) Math.round((bestF * 60 - MIN_BPM) / 0.25);
        double peakBpm = bestF * 60;
        if (kb > 0 && kb < steps - 1) {
            double a = pw[kb - 1], b = pw[kb], c = pw[kb + 1], den = a - 2 * b + c;
            if (den != 0) peakBpm += 0.25 * 0.5 * (a - c) / den;
        }
        // SNR: potencia en ±6 lpm del pico (y su armónico) frente al resto.
        double in = 0;
        for (int k = 0; k < steps; k++) {
            double bpm = MIN_BPM + 0.25 * k;
            if (Math.abs(bpm - peakBpm) <= 6 || Math.abs(bpm - 2 * peakBpm) <= 6) in += pw[k];
        }
        double snr = in / Math.max(1e-12, total - in);
        res.confidence = Math.max(0, Math.min(1, (snr - 0.25) / 1.0));

        // Onda para la vista: últimos 6 s, normalizada.
        int m = Math.min(sig.length, (int) (6 * FS));
        double[] wv = Arrays.copyOfRange(sig, sig.length - m, sig.length);
        double amp = 1e-9;
        for (double v : wv) amp = Math.max(amp, Math.abs(v));
        for (int i = 0; i < wv.length; i++) wv[i] /= amp;
        res.wave = wv;

        if (res.confidence >= 0.2) res.bpm = peakBpm;
        else if (res.problem == null) res.problem = mode == Mode.FACE
                ? "señal débil: más luz, quieto y de frente" : "señal débil: no muevas el dedo";

        if (mode == Mode.FINGER && !Double.isNaN(res.bpm)) {
            beats(sig, peakBpm, res);
            double dc = mean(rgb[0]);
            double ac = percentile(rgb[0], 0.95) - percentile(rgb[0], 0.05);
            res.perfusion = 100 * ac / Math.max(1, dc);
        }
        return res;
    }

    // ---------------------------------------------------------------- POS

    /** Plane‑Orthogonal‑to‑Skin con ventanas deslizantes de 1,6 s y solapamiento. */
    static double[] pos(double[][] rgb) {
        int n = rgb[0].length, l = (int) Math.round(1.6 * FS);
        double[] h = new double[n];
        if (n < l) return h;
        for (int s = 0; s + l <= n; s++) {
            double mr = 0, mg = 0, mb = 0;
            for (int i = s; i < s + l; i++) {
                mr += rgb[0][i];
                mg += rgb[1][i];
                mb += rgb[2][i];
            }
            mr /= l;
            mg /= l;
            mb /= l;
            double[] s1 = new double[l], s2 = new double[l];
            for (int i = 0; i < l; i++) {
                double r = rgb[0][s + i] / mr, g = rgb[1][s + i] / mg, b = rgb[2][s + i] / mb;
                s1[i] = g - b;
                s2[i] = -2 * r + g + b;
            }
            double alpha = std(s1) / Math.max(1e-12, std(s2));
            double[] hh = new double[l];
            double hm = 0;
            for (int i = 0; i < l; i++) {
                hh[i] = s1[i] + alpha * s2[i];
                hm += hh[i];
            }
            hm /= l;
            for (int i = 0; i < l; i++) h[s + i] += hh[i] - hm;
        }
        return h;
    }

    // ------------------------------------------------------------ latidos

    /** Picos de la onda filtrada → intervalos → RMSSD (sólo modo dedo). */
    static void beats(double[] sig, double bpm, Result res) {
        int minGap = (int) (FS * 60 / bpm * 0.6);
        List<Double> peaks = new ArrayList<>();
        int last = -minGap;
        // Los bordes de la ventana quedan deformados por el filtro: se ignoran 0,5 s a cada lado.
        int edge = (int) (0.5 * FS);
        for (int i = Math.max(1, edge); i < sig.length - Math.max(1, edge); i++) {
            if (sig[i] > 0 && sig[i] >= sig[i - 1] && sig[i] > sig[i + 1] && i - last >= minGap) {
                // Interpolación parabólica de la posición del pico.
                double a = sig[i - 1], b = sig[i], c = sig[i + 1], den = a - 2 * b + c;
                double off = den == 0 ? 0 : 0.5 * (a - c) / den;
                peaks.add(i + off);
                last = i;
            }
        }
        if (peaks.size() < 5) return;
        List<Double> ibi = new ArrayList<>();
        double expected = 60_000 / bpm;
        for (int i = 1; i < peaks.size(); i++) {
            double ms = (peaks.get(i) - peaks.get(i - 1)) / FS * 1000;
            if (ms > 0.6 * expected && ms < 1.5 * expected) ibi.add(ms); // descarta latidos perdidos/dobles
        }
        if (ibi.size() < 4) return;
        double ss = 0;
        for (int i = 1; i < ibi.size(); i++) {
            double d = ibi.get(i) - ibi.get(i - 1);
            ss += d * d;
        }
        res.rmssdMs = Math.sqrt(ss / (ibi.size() - 1));
    }

    // ---------------------------------------------------------- utilidades

    private double[][] resample(double start, int n) {
        double[][] out = new double[3][n];
        int j = 0;
        for (int i = 0; i < n; i++) {
            double t = start + i / FS;
            while (j < samples.size() - 2 && samples.get(j + 1)[0] < t) j++;
            double[] a = samples.get(j), b = samples.get(Math.min(j + 1, samples.size() - 1));
            double u = b[0] == a[0] ? 0 : Math.max(0, Math.min(1, (t - a[0]) / (b[0] - a[0])));
            for (int c = 0; c < 3; c++) out[c][i] = a[c + 1] + u * (b[c + 1] - a[c + 1]);
        }
        return out;
    }

    /** Quita la tendencia lenta restando una media móvil de 1,5 s. */
    static double[] detrend(double[] x) {
        int n = x.length, half = (int) (0.75 * FS);
        double[] out = new double[n];
        double[] cum = new double[n + 1];
        for (int i = 0; i < n; i++) cum[i + 1] = cum[i] + x[i];
        for (int i = 0; i < n; i++) {
            int a = Math.max(0, i - half), b = Math.min(n, i + half + 1);
            out[i] = x[i] - (cum[b] - cum[a]) / (b - a);
        }
        return out;
    }

    /** Pasabanda Butterworth de 2º orden (biquads paso alto + paso bajo), ida y vuelta (sin desfase). */
    static double[] bandpass(double[] x, double lo, double hi) {
        double[] y = biquad(x, lo, true);
        y = biquad(y, hi, false);
        reverse(y);
        y = biquad(y, lo, true);
        y = biquad(y, hi, false);
        reverse(y);
        return y;
    }

    private static double[] biquad(double[] x, double fc, boolean high) {
        double w0 = 2 * Math.PI * fc / FS, cos = Math.cos(w0), al = Math.sin(w0) / (2 * Math.sqrt(0.5));
        double b0, b1, b2;
        if (high) { b0 = (1 + cos) / 2; b1 = -(1 + cos); b2 = (1 + cos) / 2; }
        else { b0 = (1 - cos) / 2; b1 = 1 - cos; b2 = (1 - cos) / 2; }
        double a0 = 1 + al, a1 = -2 * cos, a2 = 1 - al;
        double[] y = new double[x.length];
        double x1 = 0, x2 = 0, y1 = 0, y2 = 0;
        for (int i = 0; i < x.length; i++) {
            double v = (b0 * x[i] + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2) / a0;
            x2 = x1;
            x1 = x[i];
            y2 = y1;
            y1 = v;
            y[i] = v;
        }
        return y;
    }

    /** Potencia de la componente de frecuencia f (Hz), con ventana de Hann. */
    static double power(double[] x, double f) {
        double re = 0, im = 0, w = 2 * Math.PI * f / FS;
        int n = x.length;
        for (int i = 0; i < n; i++) {
            double win = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (n - 1));
            re += win * x[i] * Math.cos(w * i);
            im -= win * x[i] * Math.sin(w * i);
        }
        return re * re + im * im;
    }

    private static void reverse(double[] a) {
        for (int i = 0, j = a.length - 1; i < j; i++, j--) {
            double t = a[i];
            a[i] = a[j];
            a[j] = t;
        }
    }

    private static double[] negate(double[] a) {
        double[] o = new double[a.length];
        for (int i = 0; i < a.length; i++) o[i] = -a[i];
        return o;
    }

    static double mean(double[] a) {
        double s = 0;
        for (double v : a) s += v;
        return a.length == 0 ? 0 : s / a.length;
    }

    private static double max(double[] a) {
        double m = -Double.MAX_VALUE;
        for (double v : a) m = Math.max(m, v);
        return m;
    }

    static double std(double[] a) {
        double m = mean(a), s = 0;
        for (double v : a) s += (v - m) * (v - m);
        return Math.sqrt(s / Math.max(1, a.length));
    }

    private static double percentile(double[] a, double p) {
        double[] c = a.clone();
        Arrays.sort(c);
        return c[(int) Math.min(c.length - 1, Math.max(0, Math.round(p * (c.length - 1))))];
    }
}
