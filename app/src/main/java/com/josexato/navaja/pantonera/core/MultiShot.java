package com.josexato.navaja.pantonera.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Combina varias mediciones de la misma muestra (idealmente con luces
 * distintas). Tras corregir con la hoja blanca, un color "normal" debería dar
 * casi lo mismo con cualquier luz; si varía mucho es metamerismo (o una foto
 * con problemas), y se avisa.
 */
public final class MultiShot {

    private MultiShot() { }

    /** ΔE2000 entre tomas por encima del cual se avisa de posible metamerismo. */
    public static final double SPREAD_WARN = 2.0;

    public static final class Combined {
        public Stjf.Sample sample;
        public double spreadDe;           // máximo ΔE2000 de la matriz de cada toma vs la mediana
        public final List<String> warnings = new ArrayList<>();
    }

    public static Combined combine(List<Stjf.Sample> shots) {
        Combined out = new Combined();
        if (shots.isEmpty()) return out;
        if (shots.size() == 1) {
            out.sample = shots.get(0);
            return out;
        }

        // Matriz: mediana por canal.
        List<double[]> bases = new ArrayList<>();
        List<Double> baseFr = new ArrayList<>();
        for (Stjf.Sample s : shots) {
            bases.add(s.baseLab());
            baseFr.add(s.base != null && s.base.fraction != null ? s.base.fraction : 1.0);
        }
        double[] base = medianLab(bases);
        double spread = 0;
        for (double[] b : bases) spread = Math.max(spread, ColorMath.deltaE2000(b, base));
        out.spreadDe = spread;
        if (spread > SPREAD_WARN) {
            out.warnings.add(String.format(Locale.US,
                    "El color cambia entre tomas (hasta ΔE %.1f): puede verse distinto con otra luz (metamerismo) "
                            + "o alguna foto tuvo reflejos o sombra.", spread));
        }

        // Componentes: se toma como guía la toma con más componentes y se
        // empareja cada uno con el más parecido de las demás (ΔE < 12).
        Stjf.Sample anchor = shots.get(0);
        for (Stjf.Sample s : shots) if (s.components.size() > anchor.components.size()) anchor = s;

        Stjf.Sample res = new Stjf.Sample();
        Double mm = medianMm(shots);
        if (mm != null) {
            res.appearance = new Stjf.Appearance();
            res.appearance.mmPerPx = mm;
        }
        if (anchor.components.isEmpty()) {
            res.lab = round(base);
        } else {
            res.base = new Stjf.Layer();
            res.base.lab = round(base);
            res.base.fraction = median(baseFr);
            for (Stjf.Component ac : anchor.components) {
                List<double[]> labs = new ArrayList<>();
                List<Double> fr = new ArrayList<>(), p10 = new ArrayList<>(), p50 = new ArrayList<>(), p90 = new ArrayList<>();
                for (Stjf.Sample s : shots) {
                    Stjf.Component best = null;
                    double bd = 12;
                    for (Stjf.Component c : s.components) {
                        double de = ColorMath.deltaE2000(c.resolvedLab(), ac.resolvedLab());
                        if (de < bd) { bd = de; best = c; }
                    }
                    if (best == null) continue;
                    labs.add(best.resolvedLab());
                    if (best.fraction != null) fr.add(best.fraction);
                    if (best.size != null) {
                        if (best.size.p10 != null) p10.add(best.size.p10);
                        if (best.size.p50 != null) p50.add(best.size.p50);
                        if (best.size.p90 != null) p90.add(best.size.p90);
                    }
                }
                Stjf.Component c = new Stjf.Component();
                c.id = ac.id;
                c.lab = round(medianLab(labs));
                c.label = ColorNames.describe(c.lab);
                c.fraction = fr.isEmpty() ? ac.fraction : median(fr);
                c.shape = ac.shape;
                if (!p50.isEmpty()) c.size = new Stjf.Size(median(p10), median(p50), median(p90));
                res.components.add(c);
            }
        }
        res.measurement = new Stjf.Measurement();
        res.measurement.shots = shots.size();
        res.measurement.spreadDe = spread;
        res.measurement.reference = anchor.measurement != null ? anchor.measurement.reference : null;
        out.sample = res;
        return out;
    }

    private static Double medianMm(List<Stjf.Sample> shots) {
        List<Double> v = new ArrayList<>();
        for (Stjf.Sample s : shots) if (s.appearance != null && s.appearance.mmPerPx != null) v.add(s.appearance.mmPerPx);
        return v.isEmpty() ? null : median(v);
    }

    static double[] medianLab(List<double[]> labs) {
        double[] out = new double[3];
        for (int d = 0; d < 3; d++) {
            double[] v = new double[labs.size()];
            for (int i = 0; i < v.length; i++) v[i] = labs.get(i)[d];
            out[d] = median(v);
        }
        return out;
    }

    private static Double median(List<Double> v) {
        if (v.isEmpty()) return null;
        double[] a = new double[v.size()];
        for (int i = 0; i < a.length; i++) a[i] = v.get(i);
        return median(a);
    }

    private static double median(double[] v) {
        double[] a = v.clone();
        Arrays.sort(a);
        int n = a.length;
        return n % 2 == 1 ? a[n / 2] : (a[n / 2 - 1] + a[n / 2]) / 2;
    }

    private static double[] round(double[] lab) {
        return new double[]{Math.round(lab[0] * 100) / 100.0, Math.round(lab[1] * 100) / 100.0,
                Math.round(lab[2] * 100) / 100.0};
    }
}
