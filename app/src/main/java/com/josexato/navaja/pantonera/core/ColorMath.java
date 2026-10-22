package com.josexato.navaja.pantonera.core;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Conversiones de color y diferencia ΔE2000.
 * Convención: sRGB 0–1 (con gamma), "lineal" 0–1 (sin gamma) y CIELAB con
 * blanco D65 / observador 2°, que es lo que usan sRGB y glTF.
 */
public final class ColorMath {

    private ColorMath() { }

    // Blanco de referencia D65.
    static final double XN = 0.95047, YN = 1.0, ZN = 1.08883;

    // --------------------------------------------------------- sRGB / lineal

    public static double toLinear(double c) {
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    public static double toGamma(double c) {
        return c <= 0.0031308 ? 12.92 * c : 1.055 * Math.pow(c, 1 / 2.4) - 0.055;
    }

    /** Tabla de 256 valores sRGB8 → lineal (rápida para imágenes). */
    public static final float[] LIN8 = new float[256];

    static {
        for (int i = 0; i < 256; i++) LIN8[i] = (float) toLinear(i / 255.0);
    }

    // --------------------------------------------------------- lineal ↔ Lab

    /** RGB lineal (0–1) → Lab D65. */
    public static double[] linearToLab(double r, double g, double b) {
        double x = 0.4124564 * r + 0.3575761 * g + 0.1804375 * b;
        double y = 0.2126729 * r + 0.7151522 * g + 0.0721750 * b;
        double z = 0.0193339 * r + 0.1191920 * g + 0.9503041 * b;
        double fx = f(x / XN), fy = f(y / YN), fz = f(z / ZN);
        return new double[]{116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz)};
    }

    /** Lab D65 → RGB lineal, sin recortar (puede salir de 0–1 = fuera de gama). */
    public static double[] labToLinear(double[] lab) {
        double fy = (lab[0] + 16) / 116, fx = fy + lab[1] / 500, fz = fy - lab[2] / 200;
        double x = XN * finv(fx), y = YN * finv(fy), z = ZN * finv(fz);
        return new double[]{
                3.2404542 * x - 1.5371385 * y - 0.4985314 * z,
                -0.9692660 * x + 1.8760108 * y + 0.0415560 * z,
                0.0556434 * x - 0.2040259 * y + 1.0572252 * z};
    }

    private static double f(double t) {
        return t > 216.0 / 24389 ? Math.cbrt(t) : (24389.0 / 27 * t + 16) / 116;
    }

    private static double finv(double t) {
        double t3 = t * t * t;
        return t3 > 216.0 / 24389 ? t3 : (116 * t - 16) / (24389.0 / 27);
    }

    public static double[] srgbToLab(double r, double g, double b) {
        return linearToLab(toLinear(r), toLinear(g), toLinear(b));
    }

    /** Lab → sRGB 8 bits recortado a la gama (0–255 por canal). */
    public static int[] labToSrgb8(double[] lab) {
        double[] lin = labToLinear(lab);
        int[] out = new int[3];
        for (int i = 0; i < 3; i++) {
            double c = toGamma(clamp01(lin[i]));
            out[i] = (int) Math.round(c * 255);
        }
        return out;
    }

    /** true si el color no se puede mostrar en sRGB sin recortarlo (tolerancia 0,5 %). */
    public static boolean outOfGamut(double[] lab) {
        for (double c : labToLinear(lab)) if (c < -0.005 || c > 1.005) return true;
        return false;
    }

    public static int labToArgb(double[] lab) {
        int[] c = labToSrgb8(lab);
        return 0xFF000000 | (c[0] << 16) | (c[1] << 8) | c[2];
    }

    public static String labToHex(double[] lab) {
        int[] c = labToSrgb8(lab);
        return String.format(Locale.US, "#%02X%02X%02X", c[0], c[1], c[2]);
    }

    static double clamp01(double v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }

    /** Croma y tono (en grados 0–360) de un Lab. */
    public static double chroma(double[] lab) { return Math.hypot(lab[1], lab[2]); }

    public static double hue(double[] lab) {
        double h = Math.toDegrees(Math.atan2(lab[2], lab[1]));
        return h < 0 ? h + 360 : h;
    }

    // ---------------------------------------------------------------- ΔE

    /** Distancia euclídea en Lab (ΔE76): barata, útil para agrupar píxeles. */
    public static double deltaE76(double[] p, double[] q) {
        double dl = p[0] - q[0], da = p[1] - q[1], db = p[2] - q[2];
        return Math.sqrt(dl * dl + da * da + db * db);
    }

    /** ΔE2000 (CIEDE2000, kL = kC = kH = 1), según Sharma, Wu y Dalal (2005). */
    public static double deltaE2000(double[] p, double[] q) {
        double l1 = p[0], a1 = p[1], b1 = p[2], l2 = q[0], a2 = q[1], b2 = q[2];
        double c1 = Math.hypot(a1, b1), c2 = Math.hypot(a2, b2);
        double cm = (c1 + c2) / 2, cm7 = Math.pow(cm, 7);
        double g = 0.5 * (1 - Math.sqrt(cm7 / (cm7 + Math.pow(25, 7))));
        double a1p = (1 + g) * a1, a2p = (1 + g) * a2;
        double c1p = Math.hypot(a1p, b1), c2p = Math.hypot(a2p, b2);
        double h1p = hueDeg(b1, a1p), h2p = hueDeg(b2, a2p);

        double dLp = l2 - l1, dCp = c2p - c1p;
        double dhp;
        if (c1p * c2p == 0) dhp = 0;
        else if (Math.abs(h2p - h1p) <= 180) dhp = h2p - h1p;
        else if (h2p - h1p > 180) dhp = h2p - h1p - 360;
        else dhp = h2p - h1p + 360;
        double dHp = 2 * Math.sqrt(c1p * c2p) * Math.sin(Math.toRadians(dhp / 2));

        double lmp = (l1 + l2) / 2, cmp = (c1p + c2p) / 2;
        double hmp;
        if (c1p * c2p == 0) hmp = h1p + h2p;
        else if (Math.abs(h1p - h2p) <= 180) hmp = (h1p + h2p) / 2;
        else if (h1p + h2p < 360) hmp = (h1p + h2p + 360) / 2;
        else hmp = (h1p + h2p - 360) / 2;

        double t = 1 - 0.17 * Math.cos(Math.toRadians(hmp - 30)) + 0.24 * Math.cos(Math.toRadians(2 * hmp))
                + 0.32 * Math.cos(Math.toRadians(3 * hmp + 6)) - 0.20 * Math.cos(Math.toRadians(4 * hmp - 63));
        double dTheta = 30 * Math.exp(-Math.pow((hmp - 275) / 25, 2));
        double cmp7 = Math.pow(cmp, 7);
        double rc = 2 * Math.sqrt(cmp7 / (cmp7 + Math.pow(25, 7)));
        double lm50 = (lmp - 50) * (lmp - 50);
        double sl = 1 + 0.015 * lm50 / Math.sqrt(20 + lm50);
        double sc = 1 + 0.045 * cmp;
        double sh = 1 + 0.015 * cmp * t;
        double rt = -Math.sin(Math.toRadians(2 * dTheta)) * rc;

        double xl = dLp / sl, xc = dCp / sc, xh = dHp / sh;
        return Math.sqrt(xl * xl + xc * xc + xh * xh + rt * xc * xh);
    }

    private static double hueDeg(double b, double a) {
        if (a == 0 && b == 0) return 0;
        double h = Math.toDegrees(Math.atan2(b, a));
        return h < 0 ? h + 360 : h;
    }

    /** Lectura humana de un ΔE2000. */
    public static String verdict(double de) {
        if (de < 1) return "no se distingue";
        if (de < 2) return "sólo con ojo entrenado";
        if (de < 3.5) return "se nota lado a lado";
        if (de < 5) return "diferencia clara";
        return "es otro color";
    }

    // ------------------------------------------------------- hex y nombres CSS

    /**
     * Lee "#RGB", "#RRGGBB" o un nombre de color CSS (como en SDJF) y lo
     * devuelve en Lab; null si no se reconoce.
     */
    public static double[] parseColor(String s) {
        if (s == null) return null;
        String t = s.trim().toLowerCase(Locale.ROOT);
        if (!t.startsWith("#")) {
            String hex = CSS.get(t.replace(" ", ""));
            if (hex == null) return null;
            t = hex;
        }
        String h = t.substring(1);
        if (h.length() == 3) {
            h = "" + h.charAt(0) + h.charAt(0) + h.charAt(1) + h.charAt(1) + h.charAt(2) + h.charAt(2);
        }
        if (h.length() != 6) return null;
        try {
            int v = Integer.parseInt(h, 16);
            return srgbToLab(((v >> 16) & 255) / 255.0, ((v >> 8) & 255) / 255.0, (v & 255) / 255.0);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Los 148 colores con nombre de CSS Color Module Level 4. */
    private static final Map<String, String> CSS = new HashMap<>();

    static {
        String[] t = ("aliceblue f0f8ff antiquewhite faebd7 aqua 00ffff aquamarine 7fffd4 azure f0ffff beige f5f5dc "
                + "bisque ffe4c4 black 000000 blanchedalmond ffebcd blue 0000ff blueviolet 8a2be2 brown a52a2a "
                + "burlywood deb887 cadetblue 5f9ea0 chartreuse 7fff00 chocolate d2691e coral ff7f50 "
                + "cornflowerblue 6495ed cornsilk fff8dc crimson dc143c cyan 00ffff darkblue 00008b darkcyan 008b8b "
                + "darkgoldenrod b8860b darkgray a9a9a9 darkgreen 006400 darkgrey a9a9a9 darkkhaki bdb76b "
                + "darkmagenta 8b008b darkolivegreen 556b2f darkorange ff8c00 darkorchid 9932cc darkred 8b0000 "
                + "darksalmon e9967a darkseagreen 8fbc8f darkslateblue 483d8b darkslategray 2f4f4f "
                + "darkslategrey 2f4f4f darkturquoise 00ced1 darkviolet 9400d3 deeppink ff1493 deepskyblue 00bfff "
                + "dimgray 696969 dimgrey 696969 dodgerblue 1e90ff firebrick b22222 floralwhite fffaf0 "
                + "forestgreen 228b22 fuchsia ff00ff gainsboro dcdcdc ghostwhite f8f8ff gold ffd700 "
                + "goldenrod daa520 gray 808080 green 008000 greenyellow adff2f grey 808080 honeydew f0fff0 "
                + "hotpink ff69b4 indianred cd5c5c indigo 4b0082 ivory fffff0 khaki f0e68c lavender e6e6fa "
                + "lavenderblush fff0f5 lawngreen 7cfc00 lemonchiffon fffacd lightblue add8e6 lightcoral f08080 "
                + "lightcyan e0ffff lightgoldenrodyellow fafad2 lightgray d3d3d3 lightgreen 90ee90 lightgrey d3d3d3 "
                + "lightpink ffb6c1 lightsalmon ffa07a lightseagreen 20b2aa lightskyblue 87cefa "
                + "lightslategray 778899 lightslategrey 778899 lightsteelblue b0c4de lightyellow ffffe0 lime 00ff00 "
                + "limegreen 32cd32 linen faf0e6 magenta ff00ff maroon 800000 mediumaquamarine 66cdaa "
                + "mediumblue 0000cd mediumorchid ba55d3 mediumpurple 9370db mediumseagreen 3cb371 "
                + "mediumslateblue 7b68ee mediumspringgreen 00fa9a mediumturquoise 48d1cc mediumvioletred c71585 "
                + "midnightblue 191970 mintcream f5fffa mistyrose ffe4e1 moccasin ffe4b5 navajowhite ffdead "
                + "navy 000080 oldlace fdf5e6 olive 808000 olivedrab 6b8e23 orange ffa500 orangered ff4500 "
                + "orchid da70d6 palegoldenrod eee8aa palegreen 98fb98 paleturquoise afeeee palevioletred db7093 "
                + "papayawhip ffefd5 peachpuff ffdab9 peru cd853f pink ffc0cb plum dda0dd powderblue b0e0e6 "
                + "purple 800080 rebeccapurple 663399 red ff0000 rosybrown bc8f8f royalblue 4169e1 "
                + "saddlebrown 8b4513 salmon fa8072 sandybrown f4a460 seagreen 2e8b57 seashell fff5ee "
                + "sienna a0522d silver c0c0c0 skyblue 87ceeb slateblue 6a5acd slategray 708090 slategrey 708090 "
                + "snow fffafa springgreen 00ff7f steelblue 4682b4 tan d2b48c teal 008080 thistle d8bfd8 "
                + "tomato ff6347 turquoise 40e0d0 violet ee82ee wheat f5deb3 white ffffff whitesmoke f5f5f5 "
                + "yellow ffff00 yellowgreen 9acd32").split(" ");
        for (int i = 0; i + 1 < t.length; i += 2) CSS.put(t[i], "#" + t[i + 1]);
    }
}
