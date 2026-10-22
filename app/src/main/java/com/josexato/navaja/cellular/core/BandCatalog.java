package com.josexato.navaja.cellular.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Canal → frecuencia y banda, con las fórmulas de 3GPP (no una lista corta
 * elegida a mano, así sirve en cualquier país):
 * <ul>
 * <li>LTE: TS 36.101 tabla 5.7.3‑1, F_DL = F_DL_low + 0,1·(EARFCN − N_offs‑DL).</li>
 * <li>5G NR: TS 38.104 §5.4.2.1 (ráster global NR‑ARFCN → frecuencia) y
 *     tablas 5.2‑1/5.2‑2 (rango de bajada de cada banda).</li>
 * </ul>
 * Varias bandas se superponen (B4/B66, n77/n78, n1/n66…): el canal solo no
 * alcanza, por eso se devuelven todas las candidatas, la de uso más
 * extendido primero. Si Android informa la banda (getBands(), API 30+), manda esa.
 */
public final class BandCatalog {

    private BandCatalog() { }

    /** Banda LTE: número, F_DL_low (MHz), N_offs‑DL, último EARFCN, nombre corto. */
    static final class Lte {
        final int band, nOffs, nMax;
        final double fLow;
        final String name;

        Lte(int band, double fLow, int nOffs, int nMax, String name) {
            this.band = band;
            this.fLow = fLow;
            this.nOffs = nOffs;
            this.nMax = nMax;
            this.name = name;
        }
    }

    static final Lte[] LTE = {
            new Lte(1, 2110, 0, 599, "2100"),
            new Lte(2, 1930, 600, 1199, "1900 PCS"),
            new Lte(3, 1805, 1200, 1949, "1800"),
            new Lte(4, 2110, 1950, 2399, "AWS‑1 1700/2100"),
            new Lte(5, 869, 2400, 2649, "850"),
            new Lte(7, 2620, 2750, 3449, "2600"),
            new Lte(8, 925, 3450, 3799, "900"),
            new Lte(12, 729, 5010, 5179, "700 a"),
            new Lte(13, 746, 5180, 5279, "700 c"),
            new Lte(14, 758, 5280, 5379, "700 PS"),
            new Lte(17, 734, 5730, 5849, "700 b"),
            new Lte(18, 860, 5850, 5999, "800 Japón"),
            new Lte(19, 875, 6000, 6149, "800 Japón"),
            new Lte(20, 791, 6150, 6449, "800 DD"),
            new Lte(21, 1495.9, 6450, 6599, "1500"),
            new Lte(25, 1930, 8040, 8689, "1900+"),
            new Lte(26, 859, 8690, 9039, "850+"),
            new Lte(28, 758, 9210, 9659, "700 APT"),
            new Lte(29, 717, 9660, 9769, "700 SDL"),
            new Lte(30, 2350, 9770, 9869, "2300 WCS"),
            new Lte(32, 1452, 9920, 10359, "1500 SDL"),
            new Lte(38, 2570, 37750, 38249, "2600 TDD"),
            new Lte(39, 1880, 38250, 38649, "1900 TDD"),
            new Lte(40, 2300, 38650, 39649, "2300 TDD"),
            new Lte(41, 2496, 39650, 41589, "2500 TDD"),
            new Lte(42, 3400, 41590, 43589, "3500 TDD"),
            new Lte(43, 3600, 43590, 45589, "3700 TDD"),
            new Lte(46, 5150, 46790, 54539, "5 GHz LAA"),
            new Lte(48, 3550, 55240, 56739, "3500 CBRS"),
            new Lte(66, 2110, 66436, 67335, "AWS‑3 1700/2100"),
            new Lte(71, 617, 68586, 68935, "600"),
    };

    /** Banda NR: número, rango de bajada (MHz), nombre corto. */
    static final class Nr {
        final int band;
        final double fLow, fHigh;
        final String name;

        Nr(int band, double fLow, double fHigh, String name) {
            this.band = band;
            this.fLow = fLow;
            this.fHigh = fHigh;
            this.name = name;
        }
    }

    static final Nr[] NR = {
            new Nr(1, 2110, 2170, "2100"), new Nr(2, 1930, 1990, "1900 PCS"), new Nr(3, 1805, 1880, "1800"),
            new Nr(5, 869, 894, "850"), new Nr(7, 2620, 2690, "2600"), new Nr(8, 925, 960, "900"),
            new Nr(12, 729, 746, "700 a"), new Nr(14, 758, 768, "700 PS"), new Nr(20, 791, 821, "800 DD"),
            new Nr(25, 1930, 1995, "1900+"), new Nr(26, 859, 894, "850+"), new Nr(28, 758, 803, "700 APT"),
            new Nr(29, 717, 728, "700 SDL"), new Nr(30, 2350, 2360, "2300 WCS"), new Nr(38, 2570, 2620, "2600 TDD"),
            new Nr(40, 2300, 2400, "2300 TDD"), new Nr(41, 2496, 2690, "2500 TDD"), new Nr(48, 3550, 3700, "3500 CBRS"),
            new Nr(66, 2110, 2200, "AWS‑3"), new Nr(70, 1995, 2020, "AWS‑4"), new Nr(71, 617, 652, "600"),
            new Nr(75, 1432, 1517, "1500 SDL"), new Nr(77, 3300, 4200, "3,3–4,2 GHz C"),
            new Nr(78, 3300, 3800, "3,5 GHz C"), new Nr(79, 4400, 5000, "4,7 GHz"),
            new Nr(257, 26500, 29500, "28 GHz mmW"), new Nr(258, 24250, 27500, "26 GHz mmW"),
            new Nr(260, 37000, 40000, "39 GHz mmW"), new Nr(261, 27500, 28350, "28 GHz mmW"),
    };

    /** Resultado: frecuencia de bajada y bandas candidatas (la más específica primero). */
    public static final class Info {
        public final double dlMhz;
        public final List<Integer> bands;
        public final String label;

        Info(double dlMhz, List<Integer> bands, String label) {
            this.dlMhz = dlMhz;
            this.bands = bands;
            this.label = label;
        }

        public Integer band() { return bands.isEmpty() ? null : bands.get(0); }
    }

    // ------------------------------------------------------------------- LTE

    /** EARFCN de bajada → frecuencia y banda; null si el canal no es de ninguna banda conocida. */
    public static Info lte(int earfcn) {
        List<Integer> bands = new ArrayList<>();
        double f = Double.NaN;
        String name = null;
        for (Lte b : LTE) {
            if (earfcn < b.nOffs || earfcn > b.nMax) continue;
            if (bands.isEmpty()) {
                f = b.fLow + 0.1 * (earfcn - b.nOffs);
                name = b.name;
            }
            bands.add(b.band);
        }
        if (bands.isEmpty()) return null;
        return new Info(f, bands, String.format(Locale.US, "B%d (%s) · %.1f MHz", bands.get(0), name, f));
    }

    // -------------------------------------------------------------------- NR

    /** NR‑ARFCN → frecuencia (MHz), ráster global de TS 38.104 §5.4.2.1. */
    public static double nrArfcnToMhz(int n) {
        if (n < 0) return Double.NaN;
        if (n < 600000) return 0.005 * n;                              // ΔF 5 kHz
        if (n < 2016667) return 3000 + 0.015 * (n - 600000);           // ΔF 15 kHz
        if (n <= 3279165) return 24250.08 + 0.060 * (n - 2016667);     // ΔF 60 kHz
        return Double.NaN;
    }

    /**
     * Orden de preferencia cuando un canal cae en varias bandas: primero las
     * de uso más extendido (n78 antes que n77, n28 antes que n14 de EE. UU.…).
     */
    static final int[] NR_PREFERENCE = {78, 28, 1, 3, 7, 5, 8, 41, 77, 20, 2, 66, 71, 40, 38, 79, 12, 25, 26, 48,
            14, 29, 30, 70, 75, 258, 257, 261, 260};

    static int rank(int band) {
        for (int i = 0; i < NR_PREFERENCE.length; i++) if (NR_PREFERENCE[i] == band) return i;
        return NR_PREFERENCE.length;
    }

    /** NR‑ARFCN de bajada → frecuencia y bandas candidatas (la de uso más extendido primero). */
    public static Info nr(int arfcn) {
        double f = nrArfcnToMhz(arfcn);
        if (Double.isNaN(f)) return null;
        List<Nr> hits = new ArrayList<>();
        for (Nr b : NR) if (f >= b.fLow - 1e-6 && f <= b.fHigh + 1e-6) hits.add(b);
        if (hits.isEmpty()) return null;
        hits.sort((a, b) -> Integer.compare(rank(a.band), rank(b.band)));
        List<Integer> bands = new ArrayList<>();
        for (Nr b : hits) bands.add(b.band);
        Nr top = hits.get(0);
        return new Info(f, bands, String.format(Locale.US, "n%d (%s) · %.2f MHz", top.band, top.name, f));
    }

    /** Nombre corto de una banda informada por Android (getBands()). */
    public static String lteName(int band) {
        for (Lte b : LTE) if (b.band == band) return b.name;
        return null;
    }

    public static String nrName(int band) {
        for (Nr b : NR) if (b.band == band) return b.name;
        return null;
    }
}
