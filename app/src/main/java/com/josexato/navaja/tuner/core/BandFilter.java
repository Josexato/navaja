package com.josexato.navaja.tuner.core;

/**
 * Pasabanda Butterworth de 4º orden (pasa-altos + pasa-bajos, cada uno con
 * 2 biquads RBJ) aplicado en tiempo real, muestra a muestra.
 *
 * Con el rango del instrumento quita el zumbido grave y los armónicos altos.
 * Con una cuerda fija se estrecha alrededor de su fundamental: el 2º armónico
 * baja ~12 dB y el 3º ~26 dB, que es lo que confunde al detector.
 */
public final class BandFilter {

    /** Factores Q de un Butterworth de 4º orden repartido en dos secciones. */
    private static final double[] Q4 = {0.5412, 1.3066};

    private final Biquad[] stages;
    public final double low, high;

    public BandFilter(int sampleRate, double low, double high) {
        this.low = low;
        this.high = high;
        stages = new Biquad[]{
                Biquad.highPass(sampleRate, low, Q4[0]), Biquad.highPass(sampleRate, low, Q4[1]),
                Biquad.lowPass(sampleRate, high, Q4[0]), Biquad.lowPass(sampleRate, high, Q4[1])};
    }

    /** Banda para todo el instrumento (detección automática de cuerda). */
    public static BandFilter forInstrument(int sr, Tunings.Instrument ins, double a4) {
        double lo = Tunings.freq(ins.strings[0], a4), hi = Tunings.freq(ins.strings[ins.strings.length - 1], a4);
        return new BandFilter(sr, lo * 0.7, Math.min(sr * 0.45, hi * 2.5));
    }

    /** Banda estrecha para una cuerda fija (deja ±media octava de margen para afinarla). */
    public static BandFilter forString(int sr, double f) {
        return new BandFilter(sr, f * 0.7, Math.min(sr * 0.45, f * 1.4));
    }

    public float process(float x) {
        double y = x;
        for (Biquad b : stages) y = b.process(y);
        return (float) y;
    }

    /** Biquad en forma directa I (coeficientes de Robert Bristow-Johnson). */
    static final class Biquad {
        final double b0, b1, b2, a1, a2;
        double x1, x2, y1, y2;

        Biquad(double b0, double b1, double b2, double a0, double a1, double a2) {
            this.b0 = b0 / a0; this.b1 = b1 / a0; this.b2 = b2 / a0;
            this.a1 = a1 / a0; this.a2 = a2 / a0;
        }

        static Biquad lowPass(int sr, double fc, double q) {
            double w = 2 * Math.PI * fc / sr, c = Math.cos(w), al = Math.sin(w) / (2 * q);
            return new Biquad((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + al, -2 * c, 1 - al);
        }

        static Biquad highPass(int sr, double fc, double q) {
            double w = 2 * Math.PI * fc / sr, c = Math.cos(w), al = Math.sin(w) / (2 * q);
            return new Biquad((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + al, -2 * c, 1 - al);
        }

        double process(double x) {
            double y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1; x1 = x; y2 = y1; y1 = y;
            return y;
        }
    }
}
