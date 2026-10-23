package com.josexato.navaja.ui;

/**
 * Colores de Navaja (Java puro: lo usan las pantallas y también los núcleos
 * que devuelven un color de estado). Un solo acento, el ámbar del inicio; los
 * estados usan un semáforo propio que nunca se reemplaza por el acento,
 * porque ahí el color ES el dato (RSRP, ΔE, nivel…).
 *
 * <p>Los contrastes de texto sobre fondo se verifican en PaletteTest (WCAG AA).
 */
public final class Palette {

    private Palette() { }

    // Superficies (de más profunda a más elevada).
    public static final int BG = 0xFF121519;
    public static final int SURFACE = 0xFF1B2028;
    public static final int SURFACE_ALT = 0xFF232A35;
    public static final int BORDER = 0xFF2E3744;

    // Marca.
    public static final int ACCENT = 0xFFE0913F;       // ámbar Navaja (igual que el inicio en modo oscuro)
    public static final int ACCENT_SOFT = 0x2EE0913F;  // fondo de chip activo
    public static final int ON_ACCENT = 0xFF1A1206;    // texto sobre ámbar

    // Texto.
    public static final int TEXT = 0xFFF0F3F7;
    public static final int TEXT_2 = 0xFFA3AFBF;       // etiquetas, datos secundarios
    public static final int TEXT_3 = 0xFF8492A5;       // notas tenues (≥ 4,5:1 sobre SURFACE)

    // Semáforo (el mismo en Red móvil, nivel, pantonera, afinador…).
    public static final int OK = 0xFF6EE7A0;
    public static final int GOOD = 0xFF5BD3E6;
    public static final int WARN = 0xFFFFD166;
    public static final int BAD = 0xFFFF8A7E;
    public static final int UNKNOWN = 0xFF8492A5;
    /** Norte de la brújula (rojo clásico de las brújulas). */
    public static final int NORTH = 0xFFFF5A4E;

    public static int withAlpha(int argb, int alpha) {
        return (alpha << 24) | (argb & 0xFFFFFF);
    }

    // --------------------------------------------------- contraste WCAG 2.1

    /** Luminancia relativa (WCAG 2.1) de un color opaco. */
    public static double luminance(int argb) {
        double r = channel((argb >> 16) & 255), g = channel((argb >> 8) & 255), b = channel(argb & 255);
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    private static double channel(int c8) {
        double c = c8 / 255.0;
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    /** Relación de contraste entre dos colores opacos (1–21). */
    public static double contrast(int a, int b) {
        double la = luminance(a), lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }
}
