package com.josexato.navaja.cellular.core;

/**
 * Evaluación de la señal (semáforo) y distancia por Timing Advance.
 * Umbrales habituales de campo para LTE/NR; son una guía de lectura, no un
 * límite normativo.
 */
public final class SignalMetrics {

    private SignalMetrics() { }

    public enum Level {
        EXCELLENT("Excelente", 0xFF6EE7A0), GOOD("Buena", 0xFF5BD3E6), FAIR("Regular", 0xFFFFD166),
        POOR("Crítica", 0xFFFF8A7E), UNKNOWN("—", 0xFF7E8A99);

        public final String label;
        public final int color;

        Level(String label, int color) {
            this.label = label;
            this.color = color;
        }
    }

    /** RSRP (dBm): > −85 excelente, −85…−95 buena, −95…−110 regular, < −110 crítica. */
    public static Level rsrp(int dbm) {
        if (!CellReading.has(dbm)) return Level.UNKNOWN;
        if (dbm > -85) return Level.EXCELLENT;
        if (dbm >= -95) return Level.GOOD;
        if (dbm >= -110) return Level.FAIR;
        return Level.POOR;
    }

    /** RSRQ (dB): > −8, −8…−11, −11…−15, < −15. */
    public static Level rsrq(int db) {
        if (!CellReading.has(db)) return Level.UNKNOWN;
        if (db > -8) return Level.EXCELLENT;
        if (db >= -11) return Level.GOOD;
        if (db >= -15) return Level.FAIR;
        return Level.POOR;
    }

    /** SINR (dB): > 15, 10…15, 0…10, < 0. */
    public static Level sinr(int db) {
        if (!CellReading.has(db)) return Level.UNKNOWN;
        if (db > 15) return Level.EXCELLENT;
        if (db >= 10) return Level.GOOD;
        if (db >= 0) return Level.FAIR;
        return Level.POOR;
    }

    /** CQI 1–15 y la modulación aproximada que permite (tabla CQI de 256QAM). */
    public static Level cqi(int cqi) {
        if (!CellReading.has(cqi) || cqi < 0 || cqi > 15) return Level.UNKNOWN;
        if (cqi >= 13) return Level.EXCELLENT;
        if (cqi >= 10) return Level.GOOD;
        if (cqi >= 7) return Level.FAIR;
        return Level.POOR;
    }

    public static String cqiModulation(int cqi) {
        if (!CellReading.has(cqi) || cqi < 1 || cqi > 15) return null;
        if (cqi >= 12) return "256QAM";
        if (cqi >= 8) return "64QAM";
        if (cqi >= 5) return "16QAM";
        return "QPSK";
    }

    /**
     * RSSNR de Android: según el fabricante viene en dB (−20…30) o en décimas
     * de dB (−200…300). Valores fuera del rango en dB se toman como décimas.
     */
    public static int normalizeRssnr(int v) {
        if (!CellReading.has(v)) return v;
        if (v >= -20 && v <= 30) return v;
        if (v >= -200 && v <= 300) return Math.round(v / 10f);
        return CellReading.NA;
    }

    /** Metros por unidad de TA en LTE: c·16·Ts/2, con Ts = 1/30,72 MHz. */
    public static final double LTE_TA_METERS = 299_792_458.0 * 16 / 30_720_000.0 / 2;

    /**
     * Distancia estimada a la antena por TA de LTE (0–1282). TA = 0 es válido
     * (menos de ~78 m); ausente = NaN.
     */
    public static double lteTaMeters(int ta) {
        if (!CellReading.has(ta) || ta < 0 || ta > 1282) return Double.NaN;
        return ta * LTE_TA_METERS;
    }
}
