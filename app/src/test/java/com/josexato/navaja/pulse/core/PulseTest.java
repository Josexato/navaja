package com.josexato.navaja.pulse.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.Test;

/**
 * Pulso con señales sintéticas: piel con una onda de pulso diminuta (0,3 %),
 * ruido de sensor, variaciones de brillo y cuadros a ritmo irregular, como
 * entrega una cámara real.
 */
public class PulseTest {

    /** Onda de pulso con forma realista (fundamental + armónico), período variable. */
    static double pulseAt(double t, List<Double> beats) {
        // Fase dentro del latido actual.
        for (int i = 1; i < beats.size(); i++) {
            if (t < beats.get(i)) {
                double ph = (t - beats.get(i - 1)) / (beats.get(i) - beats.get(i - 1));
                return Math.sin(2 * Math.PI * ph) + 0.35 * Math.sin(4 * Math.PI * ph + 0.8);
            }
        }
        return 0;
    }

    static List<Double> beatTimes(double bpm, double hrvMs, double seconds, long seed) {
        Random rnd = new Random(seed);
        List<Double> b = new ArrayList<>();
        double t = 0;
        b.add(t);
        while (t < seconds + 2) {
            double rr = 60 / bpm + hrvMs / 1000 * Math.sin(2 * Math.PI * t / 4.0) + rnd.nextGaussian() * hrvMs / 3000;
            t += rr;
            b.add(t);
        }
        return b;
    }

    /** Rostro: firma rPPG en RGB + cambios comunes de brillo (que POS debe cancelar) + ruido. */
    static PulseEstimator.Result face(double bpm, double noise, double flicker, long seed) {
        PulseEstimator pe = new PulseEstimator(PulseEstimator.Mode.FACE);
        List<Double> beats = beatTimes(bpm, 20, 14, seed);
        Random rnd = new Random(seed + 1);
        double t = 0;
        double[] dc = {175, 118, 96};
        double[] sig = {0.33, 0.77, 0.53}; // firma relativa del pulso en la piel (R, G, B)
        while (t < 12) {
            double p = pulseAt(t, beats) * 0.003;
            double light = 1 + flicker * Math.sin(2 * Math.PI * 0.25 * t) + flicker * 0.5 * Math.sin(2 * Math.PI * 0.07 * t);
            double r = dc[0] * light * (1 + sig[0] * p) + rnd.nextGaussian() * noise;
            double g = dc[1] * light * (1 + sig[1] * p) + rnd.nextGaussian() * noise;
            double b = dc[2] * light * (1 + sig[2] * p) + rnd.nextGaussian() * noise;
            pe.add(t, r, g, b);
            t += 1 / 30.0 + rnd.nextGaussian() * 0.003; // cuadros con jitter
        }
        return pe.estimate();
    }

    @Test
    public void faceHeartRateAtSeveralRates() {
        for (double bpm : new double[]{58, 72, 95, 128}) {
            PulseEstimator.Result r = face(bpm, 0.15, 0.01, (long) bpm);
            assertTrue(bpm + " lpm: " + r.problem, !Double.isNaN(r.bpm));
            assertEquals("lpm", bpm, r.bpm, 2.5);
            assertTrue("confianza " + r.confidence, r.confidence > 0.5);
        }
    }

    @Test
    public void posCancelsBrightnessChanges() {
        // Brillo que oscila 5 % (más de 15 veces la señal del pulso): POS lo cancela.
        PulseEstimator.Result r = face(80, 0.1, 0.05, 3);
        assertEquals(80, r.bpm, 2.5);
    }

    @Test
    public void noiseOnlyGivesNoReading() {
        PulseEstimator pe = new PulseEstimator(PulseEstimator.Mode.FACE);
        Random rnd = new Random(9);
        for (double t = 0; t < 12; t += 1 / 30.0)
            pe.add(t, 170 + rnd.nextGaussian() * 0.6, 120 + rnd.nextGaussian() * 0.6, 100 + rnd.nextGaussian() * 0.6);
        PulseEstimator.Result r = pe.estimate();
        assertTrue("debía no informar, dio " + r.bpm + " conf " + r.confidence, Double.isNaN(r.bpm));
        assertTrue(r.problem != null);
    }

    @Test
    public void needsFiveSecondsAndResetsOnMotion() {
        PulseEstimator pe = new PulseEstimator(PulseEstimator.Mode.FACE);
        for (double t = 0; t < 3; t += 1 / 30.0) pe.add(t, 170, 120, 100);
        assertTrue(Double.isNaN(pe.estimate().bpm));
        assertEquals("midiendo…", pe.estimate().problem);
        // Salto brusco de brillo (se movió / cambió la luz): empieza de nuevo.
        pe.add(3.05, 220, 170, 150);
        assertEquals(0, pe.estimate().seconds, 1e-9);
    }

    /**
     * Onda de pulso de forma fija después de cada latido (sístole + onda
     * dicrota), como la real: no se estira ni se encoge con el intervalo.
     */
    static double pulseShape(double t, List<Double> beats) {
        double v = 0;
        for (double b : beats) {
            double d = t - b;
            if (d < -0.5 || d > 1.0) continue;
            v += Math.exp(-0.5 * Math.pow((d - 0.12) / 0.07, 2)) + 0.4 * Math.exp(-0.5 * Math.pow((d - 0.36) / 0.07, 2));
        }
        return v;
    }

    @Test
    public void fingerRateHrvAndPerfusion() {
        double bpm = 66, hrv = 45;
        List<Double> beats = beatTimes(bpm, hrv, 14, 21);
        PulseEstimator pe = new PulseEstimator(PulseEstimator.Mode.FINGER);
        Random rnd = new Random(22);
        for (double t = 0; t < 12; t += 1 / 30.0) {
            double p = pulseShape(t, beats);
            // Con el flash a través del dedo: rojo alto, más sangre = menos luz.
            pe.add(t, 205 - 5 * p + rnd.nextGaussian() * 0.3, 40 + rnd.nextGaussian() * 0.3, 20);
        }
        PulseEstimator.Result r = pe.estimate();
        assertEquals(bpm, r.bpm, 2);
        assertTrue(r.confidence > 0.6);
        // RMSSD de referencia de la serie generada.
        double ss = 0;
        int n = 0;
        for (int i = 2; i < beats.size() && beats.get(i) < 12; i++) {
            double d = (beats.get(i) - beats.get(i - 1)) - (beats.get(i - 1) - beats.get(i - 2));
            ss += d * d * 1e6;
            n++;
        }
        double ref = Math.sqrt(ss / n);
        assertTrue("RMSSD " + r.rmssdMs + " vs " + ref, Math.abs(r.rmssdMs - ref) < Math.max(12, 0.4 * ref));
        assertEquals(100 * 5.0 * 1.0 / 205, r.perfusion, 1.2); // ~2,4 % (AC/DC)
    }

    @Test
    public void fingerNotCoveringTheCamera() {
        PulseEstimator pe = new PulseEstimator(PulseEstimator.Mode.FINGER);
        for (double t = 0; t < 8; t += 1 / 30.0) pe.add(t, 120, 115, 110); // escena normal, no un dedo
        PulseEstimator.Result r = pe.estimate();
        assertTrue(Double.isNaN(r.bpm));
        assertTrue(r.problem.contains("dedo"));
    }

    @Test
    public void evmAmplifiesOnlyThePulsingRegion() {
        int w = 40, h = 30;
        Evm evm = new Evm(w, h);
        evm.alpha = 60;
        int[] small = new int[w * h];
        double f = 1.2; // 72 lpm
        for (int fr = 0; fr < 150; fr++) {
            double t = fr / 30.0;
            double p = Math.sin(2 * Math.PI * f * t) * 0.8; // menos de 1 nivel de 255: invisible a simple vista
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    boolean skin = x >= 10 && x < 30 && y >= 8 && y < 22;
                    int r = 170 + (skin ? (int) Math.round(p) : 0);
                    small[y * w + x] = 0xFF000000 | (r << 16) | (120 << 8) | 100;
                }
            }
            evm.update(small, 1 / 30.0);
        }
        // Al cabo de 5 s: dentro de la piel la variación amplificada es grande; fuera, nula.
        double in = 0, out = 0;
        for (int fr = 0; fr < 30; fr++) {
            double t = (150 + fr) / 30.0;
            double p = Math.sin(2 * Math.PI * f * t) * 0.8;
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++) {
                    boolean skin = x >= 10 && x < 30 && y >= 8 && y < 22;
                    int r = 170 + (skin ? (int) Math.round(p) : 0);
                    small[y * w + x] = 0xFF000000 | (r << 16) | (120 << 8) | 100;
                }
            evm.update(small, 1 / 30.0);
            in = Math.max(in, Math.abs(evm.deltaAt(20, 15)[1]));
            out = Math.max(out, Math.abs(evm.deltaAt(2, 2)[1]));
        }
        assertTrue("dentro " + in, in * 255 > 8);   // >8 niveles tras amplificar
        assertEquals(0, out, 1e-6);

        // apply(): la imagen grande cambia sólo donde hay pulso.
        int fw = 160, fh = 120;
        int[] full = new int[fw * fh], dst = new int[fw * fh];
        java.util.Arrays.fill(full, 0xFFAA7864);
        evm.apply(full, fw, fh, dst);
        assertEquals(full[5 * fw + 5], dst[5 * fw + 5]);
        assertTrue(full[60 * fw + 80] != dst[60 * fw + 80]);
    }

    @Test
    public void downsampleAverages() {
        int[] src = {0xFF000000, 0xFFFFFFFF, 0xFF000000, 0xFFFFFFFF};
        int[] dst = new int[1];
        Evm.downsample(src, 2, 2, dst, 1, 1);
        assertEquals(127, (dst[0] >> 16) & 255);
    }
}
