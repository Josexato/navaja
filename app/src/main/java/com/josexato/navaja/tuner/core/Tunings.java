package com.josexato.navaja.tuner.core;

import java.util.Locale;

/** Notas, cents y afinaciones de los instrumentos soportados. */
public final class Tunings {

    private Tunings() {}

    private static final String[] NAMES = {"Do", "Do#", "Re", "Re#", "Mi", "Fa", "Fa#", "Sol", "Sol#", "La", "La#", "Si"};
    private static final String[] LETTERS = {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};

    public static final class Instrument {
        public final String name;
        /** Notas MIDI de las cuerdas, de la más grave a la más aguda. */
        public final int[] strings;
        /** Etiqueta de cada cuerda (número de cuerda tradicional). */
        public final String[] labels;
        public final double minFreq, maxFreq;

        Instrument(String name, int[] strings, String[] labels, double minFreq, double maxFreq) {
            this.name = name; this.strings = strings; this.labels = labels;
            this.minFreq = minFreq; this.maxFreq = maxFreq;
        }
    }

    // Guitarra estándar E2 A2 D3 G3 B3 E4; cuerdas numeradas de la 6ª (grave) a la 1ª.
    public static final Instrument GUITAR = new Instrument("Guitarra",
            new int[]{40, 45, 50, 55, 59, 64}, new String[]{"6ª", "5ª", "4ª", "3ª", "2ª", "1ª"}, 60, 1000);
    public static final Instrument GUITAR_DROP_D = new Instrument("Guitarra Drop D",
            new int[]{38, 45, 50, 55, 59, 64}, new String[]{"6ª", "5ª", "4ª", "3ª", "2ª", "1ª"}, 60, 1000);
    // Violín G3 D4 A4 E5; cuerdas IV (Sol) a I (Mi).
    public static final Instrument VIOLIN = new Instrument("Violín",
            new int[]{55, 62, 69, 76}, new String[]{"IV", "III", "II", "I"}, 150, 2000);

    public static final Instrument[] ALL = {GUITAR, GUITAR_DROP_D, VIOLIN};

    /** Frecuencia de una nota MIDI con La4 (MIDI 69) = a4 Hz, temperamento igual. */
    public static double freq(int midi, double a4) {
        return a4 * Math.pow(2, (midi - 69) / 12.0);
    }

    /** Desviación en cents de f respecto a la nota MIDI dada. */
    public static double cents(double f, int midi, double a4) {
        return 1200 * Math.log(f / freq(midi, a4)) / Math.log(2);
    }

    /** Nota MIDI cromática más cercana a f. */
    public static int nearestMidi(double f, double a4) {
        return (int) Math.round(69 + 12 * Math.log(f / a4) / Math.log(2));
    }

    /** Índice de la cuerda más cercana (en cents) a f. */
    public static int nearestString(Instrument ins, double f, double a4) {
        int best = 0;
        double bd = Double.MAX_VALUE;
        for (int i = 0; i < ins.strings.length; i++) {
            double d = Math.abs(cents(f, ins.strings[i], a4));
            if (d < bd) { bd = d; best = i; }
        }
        return best;
    }

    /** "La4" (nombre latino + octava científica). */
    public static String name(int midi) {
        return NAMES[Math.floorMod(midi, 12)] + (Math.floorDiv(midi, 12) - 1);
    }

    /** "A4" (nombre anglosajón + octava). */
    public static String letter(int midi) {
        return LETTERS[Math.floorMod(midi, 12)] + (Math.floorDiv(midi, 12) - 1);
    }

    public static String describeString(Instrument ins, int i, double a4) {
        int m = ins.strings[i];
        return String.format(Locale.US, "%s %s (%s) %.2f Hz", ins.labels[i], name(m), letter(m), freq(m, a4));
    }
}
