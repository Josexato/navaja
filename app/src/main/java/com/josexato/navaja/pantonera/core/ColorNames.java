package com.josexato.navaja.pantonera.core;

/**
 * Nombre descriptivo en español a partir de Lab ("gris cálido claro",
 * "verde oliva oscuro"). Es sólo una etiqueta orientativa: la identidad del
 * color es su Lab, y dos colores casi iguales pueden caer a ambos lados de
 * una frontera y recibir nombres distintos.
 */
public final class ColorNames {

    private ColorNames() { }

    public static String describe(double[] lab) {
        double l = lab[0], c = ColorMath.chroma(lab), h = ColorMath.hue(lab);

        if (c < 6) return neutral(l, c, h);

        String base;
        boolean warm = h >= 40 && h < 115;
        if (warm && l >= 72 && c < 22) base = l >= 86 && c < 14 ? "crema" : "beige";
        else if (h >= 40 && h < 100 && l < 52 && c < 55) base = c < 18 ? "marrón grisáceo" : "marrón";
        else if (h >= 95 && h < 125 && l < 60) base = "oliva";
        else base = hueName(h, l);

        String mod = "";
        if (!base.equals("crema") && !base.equals("beige")) {
            if (c < 18) mod = " apagado";
            else if (c > 65) mod = " intenso";
        }
        String light = l >= 75 ? " claro" : (l < 38 ? " oscuro" : "");
        if (base.equals("crema")) light = "";
        return base + mod + light;
    }

    private static String neutral(double l, double c, double h) {
        String n;
        if (l >= 92) n = "blanco";
        else if (l >= 80) n = "gris muy claro";
        else if (l >= 64) n = "gris claro";
        else if (l >= 45) n = "gris";
        else if (l >= 25) n = "gris oscuro";
        else n = "negro";
        if (c >= 2.5) {
            if (h >= 20 && h < 120) n += " cálido";
            else if (h >= 180 && h < 300) n += " frío";
        }
        return n;
    }

    /** Tonos en el ángulo h de CIELAB (no coincide con el círculo HSV). */
    private static String hueName(double h, double l) {
        if (h < 20 || h >= 350) return l > 65 ? "rosa" : "carmín";
        if (h < 45) return l > 70 ? "salmón" : "rojo";
        if (h < 70) return l > 72 ? "durazno" : (l < 50 ? "terracota" : "naranja");
        if (h < 95) return l < 60 ? "ocre" : "amarillo anaranjado";
        if (h < 115) return "amarillo";
        if (h < 150) return "verde amarillento";
        if (h < 190) return "verde";
        if (h < 230) return "verde azulado";
        if (h < 270) return l < 45 ? "azul petróleo" : "celeste";
        if (h < 310) return "azul";
        if (h < 335) return "violeta";
        return "fucsia";
    }
}
