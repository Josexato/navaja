package com.josexato.navaja.survey.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Escribe un plano en DXF R12 (AC1009), el formato de intercambio más simple: lo abren QGIS, LibreCAD, FreeCAD y
 * AutoCAD. Sólo usa lo que R12 trae: capas con color, polilíneas, puntos y textos. Las unidades son metros.
 *
 * <p>El texto va en ASCII: R12 no define cómo codificar tildes y cada programa las lee distinto.
 */
public final class Dxf {

    /** Colores del índice de AutoCAD. */
    public static final int RED = 1, YELLOW = 2, GREEN = 3, CYAN = 4, BLUE = 5, MAGENTA = 6, WHITE = 7, GRAY = 8,
            ORANGE = 30;

    private final Map<String, Integer> layers = new LinkedHashMap<>();
    private final List<String> entities = new ArrayList<>();
    private double minX = Double.NaN, minY, maxX, maxY;

    /** Declara una capa con su color; los nombres sólo admiten letras, números y guion bajo. */
    public Dxf layer(String name, int color) {
        layers.put(clean(name), color);
        return this;
    }

    public Dxf polyline(String layer, List<double[]> pts, boolean closed) {
        if (pts.size() < 2) return this;
        StringBuilder sb = new StringBuilder();
        pair(sb, 0, "POLYLINE");
        pair(sb, 8, use(layer));
        pair(sb, 66, "1");
        pair(sb, 70, closed ? "1" : "0");
        for (double[] p : pts) {
            pair(sb, 0, "VERTEX");
            pair(sb, 8, use(layer));
            pair(sb, 10, num(p[0]));
            pair(sb, 20, num(p[1]));
            pair(sb, 30, "0.0");
            grow(p[0], p[1]);
        }
        pair(sb, 0, "SEQEND");
        pair(sb, 8, use(layer));
        entities.add(sb.toString());
        return this;
    }

    public Dxf point(String layer, double x, double y) {
        StringBuilder sb = new StringBuilder();
        pair(sb, 0, "POINT");
        pair(sb, 8, use(layer));
        pair(sb, 10, num(x));
        pair(sb, 20, num(y));
        pair(sb, 30, "0.0");
        entities.add(sb.toString());
        grow(x, y);
        return this;
    }

    public Dxf circle(String layer, double x, double y, double r) {
        StringBuilder sb = new StringBuilder();
        pair(sb, 0, "CIRCLE");
        pair(sb, 8, use(layer));
        pair(sb, 10, num(x));
        pair(sb, 20, num(y));
        pair(sb, 30, "0.0");
        pair(sb, 40, num(r));
        entities.add(sb.toString());
        grow(x - r, y - r);
        grow(x + r, y + r);
        return this;
    }

    /** Texto con la esquina inferior izquierda en (x, y); height es la altura de la letra en metros. */
    public Dxf text(String layer, double x, double y, double height, String text) {
        StringBuilder sb = new StringBuilder();
        pair(sb, 0, "TEXT");
        pair(sb, 8, use(layer));
        pair(sb, 10, num(x));
        pair(sb, 20, num(y));
        pair(sb, 30, "0.0");
        pair(sb, 40, num(height));
        pair(sb, 1, ascii(text));
        entities.add(sb.toString());
        grow(x, y);
        return this;
    }

    public int entityCount() {
        return entities.size();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        pair(sb, 0, "SECTION");
        pair(sb, 2, "HEADER");
        pair(sb, 9, "$ACADVER");
        pair(sb, 1, "AC1009");
        if (!Double.isNaN(minX)) {
            pair(sb, 9, "$EXTMIN");
            pair(sb, 10, num(minX));
            pair(sb, 20, num(minY));
            pair(sb, 30, "0.0");
            pair(sb, 9, "$EXTMAX");
            pair(sb, 10, num(maxX));
            pair(sb, 20, num(maxY));
            pair(sb, 30, "0.0");
        }
        pair(sb, 0, "ENDSEC");

        pair(sb, 0, "SECTION");
        pair(sb, 2, "TABLES");
        pair(sb, 0, "TABLE");
        pair(sb, 2, "LTYPE");
        pair(sb, 70, "1");
        pair(sb, 0, "LTYPE");
        pair(sb, 2, "CONTINUOUS");
        pair(sb, 70, "0");
        pair(sb, 3, "Solid line");
        pair(sb, 72, "65");
        pair(sb, 73, "0");
        pair(sb, 40, "0.0");
        pair(sb, 0, "ENDTAB");
        pair(sb, 0, "TABLE");
        pair(sb, 2, "LAYER");
        pair(sb, 70, Integer.toString(layers.size() + 1));
        layerRow(sb, "0", WHITE);
        for (Map.Entry<String, Integer> e : layers.entrySet()) layerRow(sb, e.getKey(), e.getValue());
        pair(sb, 0, "ENDTAB");
        pair(sb, 0, "ENDSEC");

        pair(sb, 0, "SECTION");
        pair(sb, 2, "ENTITIES");
        for (String e : entities) sb.append(e);
        pair(sb, 0, "ENDSEC");
        pair(sb, 0, "EOF");
        return sb.toString();
    }

    private static void layerRow(StringBuilder sb, String name, int color) {
        pair(sb, 0, "LAYER");
        pair(sb, 2, name);
        pair(sb, 70, "0");
        pair(sb, 62, Integer.toString(color));
        pair(sb, 6, "CONTINUOUS");
    }

    /** Una capa usada sin declarar se agrega en blanco, para que el archivo siga siendo coherente. */
    private String use(String layer) {
        String c = clean(layer);
        if (!layers.containsKey(c)) layers.put(c, WHITE);
        return c;
    }

    private void grow(double x, double y) {
        if (Double.isNaN(minX)) {
            minX = maxX = x;
            minY = maxY = y;
            return;
        }
        minX = Math.min(minX, x);
        minY = Math.min(minY, y);
        maxX = Math.max(maxX, x);
        maxY = Math.max(maxY, y);
    }

    private static void pair(StringBuilder sb, int code, String value) {
        sb.append(code).append('\n').append(value).append('\n');
    }

    private static String num(double v) {
        return String.format(Locale.US, "%.4f", v);
    }

    static String clean(String s) {
        StringBuilder sb = new StringBuilder();
        for (char ch : ascii(s).toUpperCase(Locale.US).toCharArray()) {
            sb.append((ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9') || ch == '_' ? ch : '_');
        }
        return sb.length() == 0 ? "0" : sb.toString();
    }

    /** Sin tildes ni saltos de línea: un valor de DXF ocupa exactamente una línea. */
    static String ascii(String s) {
        String from = "áéíóúüñÁÉÍÓÚÜÑ²³·", to = "aeiouunAEIOUUN23.";
        StringBuilder sb = new StringBuilder();
        for (char ch : s.toCharArray()) {
            int i = from.indexOf(ch);
            if (i >= 0) sb.append(to.charAt(i));
            else if (ch >= 32 && ch < 127) sb.append(ch);
            else sb.append(' ');
        }
        return sb.toString().trim();
    }
}
