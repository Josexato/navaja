package com.josexato.navaja.pantonera.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Compara muestras. Para una pintura lisa es el ΔE2000; para un compuesto
 * (terrazzo) se separan tres puntajes: el fondo (ΔE2000), la mezcla de
 * colores (Earth Mover's Distance con ΔE2000 como coste entre colores) y el
 * tamaño de las piedras (diferencia de p50 en escala logarítmica).
 */
public final class Matcher {

    private Matcher() { }

    /** Por debajo de esto, dos candidatos se consideran "empatados". */
    public static final double TIE_DE = 1.0;

    public static final class Score {
        public final Stjf.Sample candidate;
        public final double baseDe;        // ΔE2000 entre matrices
        public final double mixDe;         // EMD en unidades de ΔE2000
        public final double sizeRatio;     // p50 candidato / p50 muestra (NaN si no hay datos)
        public final double fractionDiff;  // |%piedras muestra − %piedras candidato| (0–1)
        public final double total;         // puntaje para ordenar (menor = mejor)

        Score(Stjf.Sample c, double baseDe, double mixDe, double sizeRatio, double fractionDiff) {
            this.candidate = c;
            this.baseDe = baseDe;
            this.mixDe = mixDe;
            this.sizeRatio = sizeRatio;
            this.fractionDiff = fractionDiff;
            // ×2 de tamaño ≈ 2,8 ΔE; se satura en ×3 para que un dato ruidoso no domine.
            double sizePenalty = Double.isNaN(sizeRatio) ? 0 : 3 * Math.min(Math.abs(Math.log(sizeRatio)), Math.log(3));
            this.total = Math.max(mixDe, 0.5 * baseDe + 0.5 * mixDe) + sizePenalty;
        }

        public String verdict() { return ColorMath.verdict(total); }
    }

    public static Score compare(Stjf.Sample sample, Stjf.Sample candidate) {
        List<Stjf.Part> a = sample.signature(), b = candidate.signature();
        double[] la = sample.baseLab(), lb = candidate.baseLab();
        double baseDe = la != null && lb != null ? ColorMath.deltaE2000(la, lb) : Double.NaN;
        double mix = emd(a, b);
        if (Double.isNaN(baseDe)) baseDe = mix;
        return new Score(candidate, baseDe, mix, sizeRatio(a, b), Math.abs(stoneFraction(a) - stoneFraction(b)));
    }

    /** Ranking de la biblioteca (sin la propia muestra si tiene el mismo id). */
    public static List<Score> rank(Stjf.Sample sample, List<Stjf.Sample> library) {
        List<Score> out = new ArrayList<>();
        for (Stjf.Sample c : library) {
            if (c == sample) continue;
            if (c.signature().isEmpty()) continue;
            out.add(compare(sample, c));
        }
        out.sort((x, y) -> Double.compare(x.total, y.total));
        return out;
    }

    /** true si los dos primeros del ranking son prácticamente equivalentes. */
    public static boolean nearTie(List<Score> ranked) {
        return ranked.size() >= 2 && ranked.get(1).total - ranked.get(0).total < TIE_DE;
    }

    private static double stoneFraction(List<Stjf.Part> p) {
        double f = 0;
        for (Stjf.Part x : p) if (!x.isBase) f += x.fraction;
        return f;
    }

    /**
     * Razón de tamaños: tamaño típico de las piedras del candidato / el de la
     * muestra, donde el tamaño típico es la media geométrica de los p50
     * ponderada por fracción de área. No empareja piedras por color, así que
     * no depende de que las dos mediciones hayan agrupado los colores igual.
     */
    static double sizeRatio(List<Stjf.Part> a, List<Stjf.Part> b) {
        double sa = typicalSize(a), sb = typicalSize(b);
        return Double.isNaN(sa) || Double.isNaN(sb) ? Double.NaN : sb / sa;
    }

    static double typicalSize(List<Stjf.Part> parts) {
        double sw = 0, sl = 0;
        for (Stjf.Part p : parts) {
            if (p.isBase || p.size == null || p.size.p50 == null || p.size.p50 <= 0) continue;
            sw += p.fraction;
            sl += p.fraction * Math.log(p.size.p50);
        }
        return sw > 0 ? Math.exp(sl / sw) : Double.NaN;
    }

    /**
     * Earth Mover's Distance entre dos composiciones (fracciones que suman 1),
     * resuelta exacta como flujo de coste mínimo (caminos más cortos sucesivos
     * con Bellman‑Ford; las composiciones tienen pocas partes).
     */
    public static double emd(List<Stjf.Part> a, List<Stjf.Part> b) {
        int n = a.size(), m = b.size();
        if (n == 0 || m == 0) return Double.NaN;
        double[][] cost = new double[n][m];
        for (int i = 0; i < n; i++)
            for (int j = 0; j < m; j++) cost[i][j] = ColorMath.deltaE2000(a.get(i).lab, b.get(j).lab);
        double[] supply = new double[n], demand = new double[m];
        for (int i = 0; i < n; i++) supply[i] = a.get(i).fraction;
        for (int j = 0; j < m; j++) demand[j] = b.get(j).fraction;
        return transport(supply, demand, cost);
    }

    /** Problema de transporte balanceado; devuelve el coste total mínimo. */
    static double transport(double[] supply, double[] demand, double[][] cost) {
        int n = supply.length, m = demand.length;
        // Nodos: 0 = fuente, 1..n orígenes, n+1..n+m destinos, n+m+1 = sumidero.
        int nodes = n + m + 2, src = 0, snk = n + m + 1;
        double[][] cap = new double[nodes][nodes];
        double[][] w = new double[nodes][nodes];
        for (int i = 0; i < n; i++) cap[src][1 + i] = supply[i];
        for (int j = 0; j < m; j++) cap[1 + n + j][snk] = demand[j];
        for (int i = 0; i < n; i++)
            for (int j = 0; j < m; j++) {
                cap[1 + i][1 + n + j] = Double.MAX_VALUE;
                w[1 + i][1 + n + j] = cost[i][j];
                w[1 + n + j][1 + i] = -cost[i][j];
            }
        double[][] flow = new double[nodes][nodes];
        double total = 0, eps = 1e-12;
        for (int iter = 0; iter < 4 * nodes * nodes; iter++) {
            double[] dist = new double[nodes];
            int[] prev = new int[nodes];
            Arrays.fill(dist, Double.MAX_VALUE);
            Arrays.fill(prev, -1);
            dist[src] = 0;
            for (int k = 0; k < nodes; k++) {
                boolean changed = false;
                for (int u = 0; u < nodes; u++) {
                    if (dist[u] == Double.MAX_VALUE) continue;
                    for (int v = 0; v < nodes; v++) {
                        double residual = cap[u][v] - flow[u][v];
                        if (residual <= eps) continue;
                        double nd = dist[u] + w[u][v];
                        if (nd < dist[v] - 1e-12) { dist[v] = nd; prev[v] = u; changed = true; }
                    }
                }
                if (!changed) break;
            }
            if (prev[snk] < 0) break;
            double push = Double.MAX_VALUE;
            for (int v = snk; v != src; v = prev[v]) push = Math.min(push, cap[prev[v]][v] - flow[prev[v]][v]);
            if (push <= eps) break;
            for (int v = snk; v != src; v = prev[v]) {
                int u = prev[v];
                flow[u][v] += push;
                flow[v][u] -= push; // el arco inverso gana capacidad residual = flujo enviado
            }
            total += push * dist[snk];
        }
        return total;
    }
}
