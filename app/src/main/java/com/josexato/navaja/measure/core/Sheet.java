package com.josexato.navaja.measure.core;

/**
 * La hoja impresa del modo "Hoja" (A4, al 100 %): marcas cuadradas tipo
 * ArUco 4×4 de ID único repartidas por el borde y el centro libre para el
 * objeto. Es la única fuente de la geometría: la usan el PDF, el detector y
 * el cálculo de la pose.
 *
 * <p>Coordenadas de la hoja en mm: origen en el centro de la página, X hacia
 * la derecha, Y hacia el borde de arriba y Z saliendo del papel (hacia
 * arriba si la hoja está sobre la mesa).
 *
 * <p>Cada marca: 6×6 celdas de {@link #CELL} mm; el anillo exterior es
 * negro y las 4×4 interiores son los bits del código (1 = negro), por
 * filas desde la esquina de arriba a la izquierda, con la hoja derecha.
 */
public final class Sheet {

    private Sheet() { }

    /** Página A4 (mm). */
    public static final double PAGE_W = 210, PAGE_H = 297;
    /** Lado de una marca y de una celda (mm). */
    public static final double SIDE = 30, CELL = SIDE / 6;

    /**
     * Códigos de 16 bits (fila mayor, bit más alto = arriba a la izquierda).
     * Entre dos marcas cualesquiera, con cualquier giro, difieren en al menos
     * 6 bits, y cada una difiere de sus propias rotaciones en al menos 6: se
     * corrigen hasta 2 bits mal leídos sin confundir marca ni orientación.
     */
    public static final int[] CODES = {
            0x001F, 0x00E3, 0x016C, 0x02D4, 0x0389, 0x0586, 0x0777, 0x09B5, 0x0C79, 0x0F1A,
            0x14AD, 0x15DB, 0x1947, 0x1ABB, 0x1FE1, 0x29FA, 0x2FDD, 0x37BE, 0x535D, 0x7A8D};

    /**
     * Centro de cada marca en mm desde la esquina de arriba a la izquierda de
     * la página (x a la derecha, y hacia abajo). IDs en el sentido del reloj:
     * fila de arriba (0–4), lado derecho (5–8), fila de abajo (9–13, de
     * derecha a izquierda) y lado izquierdo (14–17, de abajo hacia arriba).
     */
    static final double[][] PAGE_CENTERS = layout();

    public static int count() { return PAGE_CENTERS.length; }

    private static double[][] layout() {
        double m = 10 + SIDE / 2; // 1 cm de margen para la impresora
        double[] xs = new double[5];
        for (int k = 0; k < 5; k++) xs[k] = m + k * (PAGE_W - 2 * m) / 4;
        double[] ys = new double[4];
        for (int k = 0; k < 4; k++) ys[k] = m + (k + 1) * (PAGE_H - 2 * m) / 5;
        double[][] c = new double[18][];
        int i = 0;
        for (int k = 0; k < 5; k++) c[i++] = new double[]{xs[k], m};
        for (int k = 0; k < 4; k++) c[i++] = new double[]{PAGE_W - m, ys[k]};
        for (int k = 4; k >= 0; k--) c[i++] = new double[]{xs[k], PAGE_H - m};
        for (int k = 3; k >= 0; k--) c[i++] = new double[]{m, ys[k]};
        return c;
    }

    /** Página (mm, y hacia abajo) → hoja (mm, origen al centro, Y hacia arriba). */
    public static double[] fromPage(double x, double y) { return new double[]{x - PAGE_W / 2, PAGE_H / 2 - y}; }

    /**
     * Las 4 esquinas de la marca {@code id} en la hoja (mm, Z = 0), en el
     * orden en que se ven con la hoja derecha: arriba-izquierda,
     * arriba-derecha, abajo-derecha, abajo-izquierda.
     */
    public static double[][] corners(int id) {
        double[] c = PAGE_CENTERS[id];
        double h = SIDE / 2;
        double[][] page = {{c[0] - h, c[1] - h}, {c[0] + h, c[1] - h}, {c[0] + h, c[1] + h}, {c[0] - h, c[1] + h}};
        double[][] out = new double[4][];
        for (int k = 0; k < 4; k++) {
            double[] s = fromPage(page[k][0], page[k][1]);
            out[k] = new double[]{s[0], s[1], 0};
        }
        return out;
    }

    /** Zona libre para el objeto (mm de la hoja): {xMin, xMax, yMin, yMax}, entre las marcas con 1 cm de aire. */
    public static double[] freeArea() {
        double in = 10 + SIDE + 10;
        return new double[]{-PAGE_W / 2 + in, PAGE_W / 2 - in, -PAGE_H / 2 + in, PAGE_H / 2 - in};
    }

    /** ¿Es negro el punto (x, y) de la página (mm, y hacia abajo)? Sólo las marcas (para dibujar la hoja en pruebas). */
    public static boolean blackAtPage(double x, double y) {
        for (int id = 0; id < PAGE_CENTERS.length; id++) {
            double[] c = PAGE_CENTERS[id];
            double u = (x - (c[0] - SIDE / 2)) / CELL, v = (y - (c[1] - SIDE / 2)) / CELL;
            if (u < 0 || v < 0 || u >= 6 || v >= 6) continue;
            int col = (int) u, row = (int) v;
            if (row == 0 || col == 0 || row == 5 || col == 5) return true;
            return bit(CODES[id], row - 1, col - 1);
        }
        return false;
    }

    /** Bit (fila, columna) de las 4×4 interiores de un código: true = negro. */
    public static boolean bit(int code, int row, int col) { return ((code >> (15 - (row * 4 + col))) & 1) != 0; }
}
