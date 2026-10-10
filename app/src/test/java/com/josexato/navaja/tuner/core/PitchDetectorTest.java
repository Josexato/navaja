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
    public void namesAndTrackerMedian() {
        assertEquals("La4", Tunings.name(69));
        assertEquals("E2", Tunings.letter(40));
        assertEquals("Mi5", Tunings.name(76));
        PitchTracker t = new PitchTracker(5, 400);
        t.push(110, 0);
        t.push(110.2, 50);
        t.push(220, 100);      // salto de octava puntual: reinicia (otra nota)
        double m = t.push(220.1, 150);
        assertEquals(220.05, m, 0.01);
        assertTrue(Double.isNaN(t.push(Double.NaN, 1000))); // silencio largo: se reinicia
    }
}
