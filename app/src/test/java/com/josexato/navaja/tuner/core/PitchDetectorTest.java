package com.josexato.navaja.tuner.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Random;

import org.junit.Test;

/** Señales sintéticas: senos, cuerdas pulsadas con fundamental débil, ruido y silencio. */
public class PitchDetectorTest {

    private static final int SR = 48000;

    private static PitchDetector detectorFor(Tunings.Instrument ins) {
        return new PitchDetector(SR, 2048, ins.minFreq, ins.maxFreq);
    }

    /** Cuerda pulsada: armónicos 1..8 con amplitudes dadas, decaimiento y algo de ruido. */
    private static float[] pluck(double f, double[] harm, int n, double noise, long seed) {
        Random rnd = new Random(seed);
        float[] x = new float[n];
        for (int i = 0; i < n; i++) {
            double t = i / (double) SR, s = 0;
            for (int k = 0; k < harm.length; k++)
                s += harm[k] * Math.sin(2 * Math.PI * f * (k + 1) * t + k * 0.7);
            x[i] = (float) (0.25 * s * Math.exp(-t * 1.5) + noise * rnd.nextGaussian());
        }
        return x;
    }

    @Test
    public void sinesForEveryStringWithinOneCent() {
        for (Tunings.Instrument ins : Tunings.ALL) {
            PitchDetector d = detectorFor(ins);
            for (int midi : ins.strings) {
                double f = Tunings.freq(midi, 440);
                float[] x = pluck(f, new double[]{1}, d.bufferSize(), 0, 1);
                PitchDetector.Result r = d.detect(x);
                assertTrue(ins.name + " " + Tunings.name(midi) + " sin tono", r.pitched());
                assertEquals(ins.name + " " + Tunings.name(midi), 0, Tunings.cents(r.freq, midi, 440), 1.0);
            }
        }
    }

    @Test
    public void guitarLowStringsWithWeakFundamentalNoOctaveError() {
        // En la 6ª y 5ª cuerda el 2º y 3er armónico dominan: error típico de octava.
        double[] harm = {0.25, 1.0, 0.8, 0.5, 0.35, 0.2, 0.12, 0.08};
        PitchDetector d = detectorFor(Tunings.GUITAR);
        for (int midi : new int[]{40, 45, 38}) {
            double f = Tunings.freq(midi, 440);
            PitchDetector.Result r = d.detect(pluck(f, harm, d.bufferSize(), 0.01, midi));
            assertTrue(r.pitched());
            assertEquals(Tunings.name(midi), 0, Tunings.cents(r.freq, midi, 440), 2.0);
        }
    }

    @Test
    public void detectsDetuningAndNearestString() {
        PitchDetector d = detectorFor(Tunings.GUITAR);
        // 3ª cuerda (Sol3) 12 cents alta.
        double f = Tunings.freq(55, 440) * Math.pow(2, 12 / 1200.0);
        PitchDetector.Result r = d.detect(pluck(f, new double[]{1, 0.6, 0.4, 0.2}, d.bufferSize(), 0.005, 3));
        assertEquals(12, Tunings.cents(r.freq, 55, 440), 1.0);
        assertEquals(3, Tunings.nearestString(Tunings.GUITAR, r.freq, 440));
    }

    @Test
    public void violinSawtoothAndReference442() {
        PitchDetector d = detectorFor(Tunings.VIOLIN);
        double[] saw = new double[12];
        for (int k = 0; k < saw.length; k++) saw[k] = 1.0 / (k + 1); // arco: espectro tipo diente de sierra
        for (int midi : Tunings.VIOLIN.strings) {
            double f = Tunings.freq(midi, 442);
            PitchDetector.Result r = d.detect(pluck(f, saw, d.bufferSize(), 0.01, midi));
            assertEquals(Tunings.name(midi), 0, Tunings.cents(r.freq, midi, 442), 1.5);
        }
    }

    @Test
    public void silenceAndNoiseHaveNoPitch() {
        PitchDetector d = detectorFor(Tunings.GUITAR);
        assertFalse(d.detect(new float[d.bufferSize()]).pitched());
        Random rnd = new Random(7);
        float[] noise = new float[d.bufferSize()];
        for (int i = 0; i < noise.length; i++) noise[i] = (float) (0.2 * rnd.nextGaussian());
        assertFalse("el ruido blanco no debe dar nota", d.detect(noise).pitched());
    }

    @Test
    public void noteNames() {
        assertEquals("La4", Tunings.name(69));
        assertEquals("E2", Tunings.letter(40));
        assertEquals("Mi5", Tunings.name(76));
    }

    @Test
    public void trackerSkipsAttackAndRejectsHarmonicGlitches() {
        PitchTracker t = new PitchTracker(PitchTracker.Response.NORMAL, 600);
        long ms = 0;
        // Ataque: los primeros cuadros (onsetSkip=3) se ignoran aunque traigan nota.
        for (int i = 0; i < 3; i++) assertTrue(Double.isNaN(t.push(220, ms += 21)));
        // Confirmación: hacen falta 4 cuadros coherentes.
        double out = Double.NaN;
        for (int i = 0; i < 4; i++) out = t.push(110 + 0.1 * i, ms += 21);
        assertEquals(110.15, out, 0.2);
        // Armónicos sueltos (x2, x3) intercalados: la nota no cambia.
        double[] seq = {220.3, 110.1, 330.2, 110.0, 220.1, 220.2, 110.2, 330.0, 110.1};
        for (double f : seq) out = t.push(f, ms += 21);
        assertEquals(110.1, out, 0.3);
        // Un armónico sostenido necesita el doble de confirmación (8 cuadros).
        for (int i = 0; i < 7; i++) assertEquals(110.1, t.push(220.0, ms += 21), 0.3);
        assertEquals(220.0, t.push(220.0, ms += 21), 0.1);
        // Cambio de cuerda real (La3 -> Sol3, razón no armónica): 4 cuadros.
        for (int i = 0; i < 3; i++) assertEquals(220.0, t.push(196.0, ms += 21), 0.1);
        assertEquals(196.0, t.push(196.0, ms += 21), 0.1);
        // Silencio largo: se reinicia.
        assertTrue(Double.isNaN(t.push(Double.NaN, ms += 1000)));
    }

    @Test
    public void bandFilterAttenuatesHarmonicsOfLockedString() {
        double f = Tunings.freq(50, 440); // Re3
        double g1 = gain(BandFilter.forString(SR, f), f);
        double g2 = gain(BandFilter.forString(SR, f), 2 * f);
        double g3 = gain(BandFilter.forString(SR, f), 3 * f);
        assertTrue("fundamental casi intacta: " + g1, g1 > 0.85);
        assertTrue("2º armónico -10 dB o más: " + g2, g2 < 0.32);
        assertTrue("3er armónico -20 dB o más: " + g3, g3 < 0.1);
        // Banda de instrumento: deja pasar todas las cuerdas de la guitarra.
        for (int m : Tunings.GUITAR.strings)
            assertTrue(gain(BandFilter.forInstrument(SR, Tunings.GUITAR, 440), Tunings.freq(m, 440)) > 0.7);
    }

    /** Cuerda pulsada "vista" por un micrófono de teléfono: fundamental débil que decae antes. */
    private static float[] phoneMicPluck(double f, int n, long seed) {
        Random rnd = new Random(seed);
        double[] harm = {0.1, 1.0, 0.9, 0.6, 0.4, 0.25, 0.15, 0.1};
        float[] x = new float[n];
        double a = Math.exp(-2 * Math.PI * 150 / SR), y1 = 0, x1 = 0, y2 = 0, x2 = 0;
        for (int i = 0; i < n; i++) {
            double t = i / (double) SR, s = 0;
            for (int k = 0; k < harm.length; k++)
                s += harm[k] * Math.exp(-t * (k == 0 ? 4 : 1 + k * 0.8))
                        * Math.sin(2 * Math.PI * f * (k + 1) * t * (1 + 0.0004 * (k + 1) * (k + 1)) + k);
            double in = 0.3 * s + 0.01 * rnd.nextGaussian();
            double o1 = a * (y1 + in - x1); x1 = in; y1 = o1;
            double o2 = a * (y2 + o1 - x2); x2 = o1; y2 = o2;
            x[i] = (float) o2;
        }
        return x;
    }

    @Test
    public void fullChainNeverReportsAHarmonicOnPhoneMic() {
        // Con el YIN clásico, Re3 daba ~13 % de cuadros en el armónico con esta señal.
        for (Tunings.Instrument ins : Tunings.ALL) {
            double[] targets = new double[ins.strings.length];
            for (int i = 0; i < targets.length; i++) targets[i] = Tunings.freq(ins.strings[i], 440);
            for (int midi : ins.strings) {
                PitchDetector d = detectorFor(ins);
                d.setTargets(targets);
                BandFilter bf = BandFilter.forInstrument(SR, ins, 440);
                PitchTracker tr = new PitchTracker(PitchTracker.Response.NORMAL, 600);
                float[] all = phoneMicPluck(Tunings.freq(midi, 440), SR * 2, midi);
                for (int i = 0; i < all.length; i++) all[i] = bf.process(all[i]);
                int wrong = 0, shown = 0;
                for (int off = 0; off + d.bufferSize() < all.length; off += 1024) {
                    PitchDetector.Result r = d.detect(java.util.Arrays.copyOfRange(all, off, off + d.bufferSize()));
                    double f = tr.push(r.pitched() && r.clarity > 0.7 ? r.freq : Double.NaN, off * 1000L / SR);
                    if (Double.isNaN(f)) continue;
                    shown++;
                    if (Math.abs(Tunings.cents(f, midi, 440)) > 50) wrong++;
                }
                assertEquals(ins.name + " " + Tunings.name(midi) + " cuadros en nota equivocada", 0, wrong);
                assertTrue(ins.name + " " + Tunings.name(midi) + " casi siempre con lectura", shown > 70);
            }
        }
    }

    private static double gain(BandFilter bf, double f) {
        double in = 0, out = 0;
        for (int i = 0; i < SR; i++) {
            float x = (float) Math.sin(2 * Math.PI * f * i / SR);
            float y = bf.process(x);
            if (i > SR / 2) { in += x * x; out += y * y; }
        }
        return Math.sqrt(out / in);
    }
}
