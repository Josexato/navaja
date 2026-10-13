package com.josexato.navaja.pantonera.core;

import java.util.List;
import java.util.Random;

/**
 * Textura sintética a partir de la composición STJF: fondo con el color de la
 * matriz y "piedras" con el color, fracción de área, granulometría y forma de
 * cada componente. Sirve para ver (y exportar a glTF) muestras escritas a mano
 * que no tienen foto. La textura es repetible sin costuras (las piedras que
 * salen por un borde entran por el opuesto).
 */
public final class TextureSynth {

    private TextureSynth() { }

    public static final class Texture {
        public final int[] argb;
        public final int size;
        public final double mmPerPx;

        Texture(int[] argb, int size, double mmPerPx) {
            this.argb = argb;
            this.size = size;
            this.mmPerPx = mmPerPx;
        }
    }

    /** Tamaño por defecto de las piedras sin granulometría (mm). */
    static final double DEFAULT_P50 = 4;

    public static Texture render(Stjf.Sample s, int size, long seed) {
        List<Stjf.Part> parts = s.signature();
        double maxP90 = 0;
        for (Stjf.Part p : parts) {
            if (p.isBase) continue;
            maxP90 = Math.max(maxP90, p90(p.size));
        }
        // El cuadro mide ~12 piedras grandes de lado (mín. 60 mm, máx. 400 mm).
        double tileMm = maxP90 > 0 ? Math.min(400, Math.max(60, 12 * maxP90)) : 100;
        double mmPerPx = tileMm / size;
        int n = size * size;
        double[][] lab = new double[n][];
        Stjf.Part base = parts.isEmpty() ? null : parts.get(0);
        double[] baseLab = base != null && base.isBase ? base.lab : new double[]{80, 0, 0};
        for (int i = 0; i < n; i++) lab[i] = baseLab;
        boolean[] taken = new boolean[n];
        Random rnd = new Random(seed);

        // Primero los componentes grandes, para que los chicos rellenen.
        parts.sort((a, b) -> Double.compare(p50(b.size), p50(a.size)));
        for (Stjf.Part p : parts) {
            if (p.isBase || p.fraction <= 0) continue;
            int target = (int) Math.round(p.fraction * n), painted = 0;
            double mu = Math.log(p50(p.size)), sigma = sigma(p.size);
            for (int tries = 0; painted < target && tries < 200000; tries++) {
                double dMm = Math.exp(mu + sigma * rnd.nextGaussian());
                double rPx = Math.max(0.8, dMm / mmPerPx / 2);
                int cx = rnd.nextInt(size), cy = rnd.nextInt(size);
                painted += stone(lab, taken, size, cx, cy, rPx, p.lab, rnd, "angular".equals(shapeOf(s, p)));
            }
        }
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            double[] l = lab[i];
            double jitter = rnd.nextGaussian() * 1.2; // grano fino para que no se vea "plano"
            out[i] = ColorMath.labToArgb(new double[]{l[0] + jitter, l[1], l[2]});
        }
        return new Texture(out, size, mmPerPx);
    }

    /** Pinta una piedra (polígono irregular) y devuelve cuántos píxeles nuevos cubrió. */
    private static int stone(double[][] lab, boolean[] taken, int size, int cx, int cy, double r,
                             double[] color, Random rnd, boolean angular) {
        int verts = angular ? 5 + rnd.nextInt(3) : 12;
        double[] rad = new double[verts];
        double rot = rnd.nextDouble() * Math.PI * 2;
        for (int i = 0; i < verts; i++) rad[i] = r * (angular ? 0.75 + 0.5 * rnd.nextDouble() : 0.9 + 0.2 * rnd.nextDouble());
        int painted = 0, ri = (int) Math.ceil(r * 1.3);
        for (int dy = -ri; dy <= ri; dy++) {
            for (int dx = -ri; dx <= ri; dx++) {
                double ang = Math.atan2(dy, dx) - rot;
                ang = ((ang % (2 * Math.PI)) + 2 * Math.PI) % (2 * Math.PI);
                double pos = ang / (2 * Math.PI) * verts;
                int i0 = (int) pos % verts, i1 = (i0 + 1) % verts;
                double t = pos - Math.floor(pos);
                // Interpolación lineal entre vértices → bordes rectos (aprox.).
                double lim = rad[i0] * (1 - t) + rad[i1] * t;
                if (dx * dx + dy * dy > lim * lim) continue;
                int x = Math.floorMod(cx + dx, size), y = Math.floorMod(cy + dy, size);
                int k = y * size + x;
                if (taken[k]) continue; // la piedra queda "detrás" de las ya puestas
                taken[k] = true;
                lab[k] = color;
                painted++;
            }
        }
        return painted;
    }

    private static String shapeOf(Stjf.Sample s, Stjf.Part p) {
        for (Stjf.Component c : s.components) if (c.name().equals(p.name)) return c.shape;
        return null;
    }

    static double p50(Stjf.Size z) {
        if (z == null) return DEFAULT_P50;
        if (z.p50 != null) return z.p50;
        if (z.p10 != null && z.p90 != null) return Math.sqrt(z.p10 * z.p90);
        return DEFAULT_P50;
    }

    static double p90(Stjf.Size z) {
        if (z != null && z.p90 != null) return z.p90;
        return p50(z) * Math.exp(1.2816 * sigma(z));
    }

    /** σ de una log‑normal con esos percentiles (o 0,35 si no hay datos). */
    static double sigma(Stjf.Size z) {
        if (z != null && z.p10 != null && z.p90 != null && z.p90 > z.p10)
            return Math.log(z.p90 / z.p10) / (2 * 1.2816);
        return 0.35;
    }
}
