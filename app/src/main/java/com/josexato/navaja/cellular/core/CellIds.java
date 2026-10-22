package com.josexato.navaja.cellular.core;

/**
 * Descomposición de identificadores de celda.
 * <ul>
 * <li>LTE: ECI de 28 bits = eNB ID (20 bits altos) + Cell ID (8 bits bajos). Fijo por norma.</li>
 * <li>NR: NCI de 36 bits = gNB ID (22 a 32 bits, <b>lo elige el operador</b>) + Cell ID
 *     (el resto). No hay una regla única: por eso el largo es un parámetro.</li>
 * </ul>
 */
public final class CellIds {

    private CellIds() { }

    /** Largo de gNB ID por defecto (muchos operadores usan 24; se puede cambiar en la app). */
    public static final int DEFAULT_GNB_BITS = 24;

    public static long enb(long eci) { return eci >> 8; }

    public static int lteSector(long eci) { return (int) (eci & 0xFF); }

    public static long gnb(long nci, int gnbBits) { return nci >> (36 - checkBits(gnbBits)); }

    public static long nrSector(long nci, int gnbBits) {
        int cellBits = 36 - checkBits(gnbBits);
        return nci & ((1L << cellBits) - 1);
    }

    static int checkBits(int b) {
        if (b < 22 || b > 32) throw new IllegalArgumentException("gNB ID: 22 a 32 bits");
        return b;
    }
}
