package com.josexato.navaja.pulse.core;

/**
 * Magnificación de video euleriana (Wu et al., MIT 2012), variante IIR para
 * tiempo real: en una versión reducida de la imagen (equivale a un nivel de
 * la pirámide gaussiana) cada píxel pasa por un pasabanda temporal en la
 * banda del pulso; esa variación se amplifica y se suma a la imagen a
 * resolución completa (interpolada). Así se ve "latir" la piel.
 *
 * <p>Se trabaja en YIQ: la crominancia (I, Q) lleva casi toda la señal del
 * pulso; la luminancia se amplifica menos para no exagerar el ruido.
 */
public final class Evm {

    private final int w, h;
    private final float[] lowA, lowB; // dos pasabajos de primer orden por píxel y canal (Y, I, Q)
    private final float[] delta;      // pasabanda amplificado (3 canales) en la grilla reducida
    private boolean init;
    public double fLow = 0.75, fHigh = 2.5;   // Hz (45–150 lpm)
    public double alpha = 60;                 // ganancia sobre la crominancia
    public double lumaGain = 0.25;            // fracción de la ganancia aplicada a la luminancia

    /** @param w, h tamaño de la grilla reducida (p. ej. 40×30). */
    public Evm(int w, int h) {
        this.w = w;
        this.h = h;
        lowA = new float[w * h * 3];
        lowB = new float[w * h * 3];
        delta = new float[w * h * 3];
    }

    public int width() { return w; }

    public int height() { return h; }

    public void reset() { init = false; }

    /**
     * Agrega un cuadro reducido (ARGB, w×h) tomado {@code dt} segundos después
     * del anterior y actualiza la señal amplificada.
     */
    public void update(int[] small, double dt) {
        double aHi = init ? 1 - Math.exp(-2 * Math.PI * fHigh * dt) : 1;
        double aLo = init ? 1 - Math.exp(-2 * Math.PI * fLow * dt) : 1;
        double gain = alpha;
        for (int i = 0, k = 0; i < w * h; i++, k += 3) {
            int c = small[i];
            float r = ((c >> 16) & 255) / 255f, g = ((c >> 8) & 255) / 255f, b = (c & 255) / 255f;
            float y = 0.299f * r + 0.587f * g + 0.114f * b;
            float ii = 0.596f * r - 0.274f * g - 0.322f * b;
            float q = 0.211f * r - 0.523f * g + 0.312f * b;
            for (int ch = 0; ch < 3; ch++) {
                float v = ch == 0 ? y : ch == 1 ? ii : q;
                // Pasabanda = pasabajo(fHigh) − pasabajo(fLow).
                lowA[k + ch] += (float) (aHi * (v - lowA[k + ch]));
                lowB[k + ch] += (float) (aLo * (v - lowB[k + ch]));
                double g0 = ch == 0 ? gain * lumaGain : gain;
                delta[k + ch] = (float) (g0 * (lowA[k + ch] - lowB[k + ch]));
            }
        }
        init = true;
    }

    /** Variación amplificada (Y, I, Q) del píxel reducido (x, y); para pruebas y la vista. */
    public float[] deltaAt(int x, int y) {
        int k = (y * w + x) * 3;
        return new float[]{delta[k], delta[k + 1], delta[k + 2]};
    }

    /**
     * Suma la variación amplificada (interpolada bilinealmente) a un cuadro de
     * resolución completa {@code full} (ARGB, fw×fh) y escribe en {@code out}.
     */
    public void apply(int[] full, int fw, int fh, int[] out) {
        float sx = (float) w / fw, sy = (float) h / fh;
        for (int py = 0; py < fh; py++) {
            float gy = (py + 0.5f) * sy - 0.5f;
            int y0 = clamp((int) Math.floor(gy), 0, h - 1), y1 = Math.min(h - 1, y0 + 1);
            float ty = Math.max(0, Math.min(1, gy - y0));
            for (int px = 0; px < fw; px++) {
                float gx = (px + 0.5f) * sx - 0.5f;
                int x0 = clamp((int) Math.floor(gx), 0, w - 1), x1 = Math.min(w - 1, x0 + 1);
                float tx = Math.max(0, Math.min(1, gx - x0));
                int k00 = (y0 * w + x0) * 3, k01 = (y0 * w + x1) * 3, k10 = (y1 * w + x0) * 3, k11 = (y1 * w + x1) * 3;
                float dY = lerp2(delta[k00], delta[k01], delta[k10], delta[k11], tx, ty);
                float dI = lerp2(delta[k00 + 1], delta[k01 + 1], delta[k10 + 1], delta[k11 + 1], tx, ty);
                float dQ = lerp2(delta[k00 + 2], delta[k01 + 2], delta[k10 + 2], delta[k11 + 2], tx, ty);
                // YIQ → RGB de la variación, sumada al píxel original.
                float dr = dY + 0.956f * dI + 0.621f * dQ;
                float dg = dY - 0.272f * dI - 0.647f * dQ;
                float db = dY - 1.106f * dI + 1.703f * dQ;
                int i = py * fw + px, c = full[i];
                int r = clamp((int) (((c >> 16) & 255) + dr * 255), 0, 255);
                int g = clamp((int) (((c >> 8) & 255) + dg * 255), 0, 255);
                int b = clamp((int) ((c & 255) + db * 255), 0, 255);
                out[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
    }

    /** Reduce una imagen ARGB promediando bloques (filtro espacial pasabajo). */
    public static void downsample(int[] src, int sw, int sh, int[] dst, int dw, int dh) {
        for (int y = 0; y < dh; y++) {
            int ys = y * sh / dh, ye = Math.max(ys + 1, (y + 1) * sh / dh);
            for (int x = 0; x < dw; x++) {
                int xs = x * sw / dw, xe = Math.max(xs + 1, (x + 1) * sw / dw);
                long r = 0, g = 0, b = 0;
                int n = 0;
                for (int yy = ys; yy < ye; yy++) {
                    for (int xx = xs; xx < xe; xx++) {
                        int c = src[yy * sw + xx];
                        r += (c >> 16) & 255;
                        g += (c >> 8) & 255;
                        b += c & 255;
                        n++;
                    }
                }
                dst[y * dw + x] = 0xFF000000 | ((int) (r / n) << 16) | ((int) (g / n) << 8) | (int) (b / n);
            }
        }
    }

    private static float lerp2(float a, float b, float c, float d, float tx, float ty) {
        float top = a + (b - a) * tx, bot = c + (d - c) * tx;
        return top + (bot - top) * ty;
    }

    private static int clamp(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }
}
