package com.josexato.navaja.ui;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Sistema de diseño de Navaja, programático y sin dependencias: cabecera
 * común, tarjetas, métricas, botones, chips y pestañas con los colores de
 * {@link Palette}. Todas las pantallas nativas lo usan.
 */
public final class NavajaUi {

    private NavajaUi() { }

    public static final int RADIUS_CARD = 12, RADIUS_CONTROL = 10, RADIUS_CHIP = 16;

    public static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    /** Barras del sistema del mismo color que el fondo. */
    public static void window(Activity a) {
        a.getWindow().setStatusBarColor(Palette.BG);
        a.getWindow().setNavigationBarColor(Palette.BG);
    }

    // ---------------------------------------------------------------- texto

    public enum Style { TITLE, SUBTITLE, BODY, SECONDARY, MUTED, LABEL, MONO }

    public static TextView text(Context c, CharSequence s, Style st) {
        TextView t = new TextView(c);
        t.setText(s);
        switch (st) {
            case TITLE: t.setTextSize(17); t.setTextColor(Palette.TEXT); t.setTypeface(Typeface.DEFAULT_BOLD); break;
            case SUBTITLE: t.setTextSize(15); t.setTextColor(Palette.TEXT); t.setTypeface(Typeface.DEFAULT_BOLD); break;
            case SECONDARY: t.setTextSize(12); t.setTextColor(Palette.TEXT_2); break;
            case MUTED: t.setTextSize(12); t.setTextColor(Palette.TEXT_3); break;
            case LABEL:
                t.setTextSize(10);
                t.setTextColor(Palette.TEXT_2);
                t.setTypeface(Typeface.DEFAULT_BOLD);
                t.setLetterSpacing(0.08f);
                t.setAllCaps(true);
                break;
            case MONO: t.setTextSize(13); t.setTextColor(Palette.TEXT_2); t.setTypeface(Typeface.MONOSPACE); break;
            default: t.setTextSize(14); t.setTextColor(Palette.TEXT);
        }
        return t;
    }

    // --------------------------------------------------------- estructura

    /**
     * Pantalla estándar: fondo, cabecera (← título · subtítulo · acción) y un
     * contenedor vertical con margen donde va el contenido.
     */
    public static final class Screen {
        public final LinearLayout root, content;
        public final TextView title, subtitle;

        Screen(LinearLayout root, LinearLayout content, TextView title, TextView subtitle) {
            this.root = root;
            this.content = content;
            this.title = title;
            this.subtitle = subtitle;
        }

        public void setSubtitle(CharSequence s) {
            subtitle.setText(s);
            subtitle.setVisibility(s == null || s.length() == 0 ? View.GONE : View.VISIBLE);
        }
    }

    public static Screen screen(Activity a, String title, String subtitle, View action) {
        return screen(a, title, subtitle, action, false);
    }

    /** Igual, con el contenido dentro de un ScrollView vertical si {@code scroll}. */
    public static Screen screen(Activity a, String title, String subtitle, View action, boolean scroll) {
        window(a);
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Palette.BG);
        HeaderViews h = header(a, title, subtitle, action);
        root.addView(h.bar);
        LinearLayout content = new LinearLayout(a);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(a, 16), dp(a, 4), dp(a, 16), dp(a, 8));
        if (scroll) {
            android.widget.ScrollView sv = new android.widget.ScrollView(a);
            sv.setFillViewport(true);
            sv.addView(content, new ViewGroup.LayoutParams(-1, -2));
            root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
        } else {
            root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1f));
        }
        return new Screen(root, content, h.title, h.subtitle);
    }

    /** Sólo la barra de cabecera, para pantallas con un diseño propio (p. ej. el escáner 3D). */
    public static LinearLayout headerBar(Activity a, String title, String subtitle, View action) {
        window(a);
        return header(a, title, subtitle, action).bar;
    }

    static final class HeaderViews {
        LinearLayout bar;
        TextView title, subtitle;
    }

    @SuppressWarnings("deprecation") // onBackPressed: misma vía que el botón Atrás del sistema
    static HeaderViews header(Activity a, String title, String subtitle, View action) {
        HeaderViews h = new HeaderViews();
        LinearLayout bar = new LinearLayout(a);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(a, 6), dp(a, 8), dp(a, 12), dp(a, 8));
        bar.setBackgroundColor(Palette.BG);

        TextView back = new TextView(a);
        back.setText("←");
        back.setContentDescription("Volver");
        back.setTextColor(Palette.ACCENT);
        back.setTextSize(22);
        back.setTypeface(Typeface.DEFAULT_BOLD);
        back.setGravity(Gravity.CENTER);
        back.setBackground(ripple(Color.TRANSPARENT, Palette.withAlpha(Palette.ACCENT, 0x40), dp(a, 20)));
        back.setOnClickListener(v -> a.onBackPressed());
        bar.addView(back, new LinearLayout.LayoutParams(dp(a, 44), dp(a, 44)));

        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(a, 6), 0, dp(a, 8), 0);
        h.title = text(a, title, Style.TITLE);
        col.addView(h.title);
        h.subtitle = text(a, subtitle == null ? "" : subtitle, Style.MONO);
        h.subtitle.setTextSize(11);
        h.subtitle.setVisibility(subtitle == null || subtitle.isEmpty() ? View.GONE : View.VISIBLE);
        col.addView(h.subtitle);
        bar.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        if (action != null) bar.addView(action);
        h.bar = bar;
        return h;
    }

    /** Tarjeta: superficie con borde fino y esquinas de 12 dp. */
    public static LinearLayout card(Context c) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        int p = dp(c, 12);
        card.setPadding(p, p, p, p);
        card.setBackground(shape(Palette.SURFACE, Palette.BORDER, dp(c, RADIUS_CARD), dp(c, 1)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(c, 8);
        card.setLayoutParams(lp);
        return card;
    }

    /** Fondo de tarjeta tocable (con ripple). */
    public static Drawable cardBackground(Context c) {
        return ripple(Palette.SURFACE, Palette.withAlpha(Palette.ACCENT, 0x30), dp(c, RADIUS_CARD), Palette.BORDER, dp(c, 1));
    }

    // ------------------------------------------------------------ métricas

    /** Métrica: etiqueta en mayúsculas, valor grande y una línea de estado. */
    public static final class Metric {
        public final LinearLayout view;
        private final TextView value, note;
        private final GradientDrawable bg;
        private final Context ctx;

        Metric(Context c, String label) {
            ctx = c;
            view = new LinearLayout(c);
            view.setOrientation(LinearLayout.VERTICAL);
            int p = dp(c, 10);
            view.setPadding(p, dp(c, 8), p, dp(c, 8));
            bg = shape(Palette.SURFACE_ALT, Palette.BORDER, dp(c, RADIUS_CONTROL), dp(c, 1));
            view.setBackground(bg);
            view.addView(text(c, label, Style.LABEL));
            value = text(c, "—", Style.BODY);
            value.setTextSize(20);
            value.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            view.addView(value);
            note = text(c, "", Style.SECONDARY);
            note.setTextSize(11);
            note.setVisibility(View.GONE);
            view.addView(note);
        }

        /**
         * @param status color de estado (Palette.OK/WARN/BAD…) o 0 para neutro.
         *               Con estado, el valor y el borde toman ese color.
         */
        public Metric set(String v, String noteText, int status) {
            value.setText(v);
            value.setTextColor(status == 0 ? Palette.TEXT : status);
            bg.setStroke(dp(ctx, status == 0 ? 1 : 2), status == 0 ? Palette.BORDER : status);
            note.setText(noteText == null ? "" : noteText);
            note.setTextColor(status == 0 ? Palette.TEXT_2 : status);
            note.setVisibility(noteText == null || noteText.isEmpty() ? View.GONE : View.VISIBLE);
            return this;
        }
    }

    public static Metric metric(Context c, String label) { return new Metric(c, label); }

    /** Fila de métricas de igual ancho con separación de 6 dp. */
    public static LinearLayout metricRow(Context c, Metric... ms) {
        LinearLayout row = new LinearLayout(c);
        for (int i = 0; i < ms.length; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            if (i > 0) lp.leftMargin = dp(c, 6);
            row.addView(ms[i].view, lp);
        }
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.topMargin = dp(c, 6);
        row.setLayoutParams(rlp);
        return row;
    }

    // ------------------------------------------------------------- botones

    public static Button primary(Context c, String s, View.OnClickListener l) {
        Button b = baseButton(c, s, l);
        b.setTextColor(Palette.ON_ACCENT);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackground(ripple(Palette.ACCENT, 0x40000000, dp(c, RADIUS_CONTROL)));
        return b;
    }

    public static Button secondary(Context c, String s, View.OnClickListener l) {
        Button b = baseButton(c, s, l);
        b.setTextColor(Palette.TEXT);
        b.setBackground(ripple(Palette.SURFACE_ALT, Palette.withAlpha(Palette.ACCENT, 0x40), dp(c, RADIUS_CONTROL),
                Palette.BORDER, dp(c, 1)));
        return b;
    }

    /** Botón pequeño para la cabecera (texto ámbar, sin fondo). */
    public static Button headerAction(Context c, String s, View.OnClickListener l) {
        Button b = baseButton(c, s, l);
        b.setTextColor(Palette.ACCENT);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackground(ripple(Color.TRANSPARENT, Palette.withAlpha(Palette.ACCENT, 0x40), dp(c, RADIUS_CONTROL)));
        return b;
    }

    private static Button baseButton(Context c, String s, View.OnClickListener l) {
        Button b = new Button(c);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setStateListAnimator(null); // sin la sombra de Material: borde fino en su lugar
        b.setMinHeight(dp(c, 44));
        b.setMinimumHeight(dp(c, 44));
        b.setPadding(dp(c, 12), dp(c, 8), dp(c, 12), dp(c, 8));
        b.setOnClickListener(l);
        return b;
    }

    /** Fila de botones de igual ancho con separación de 6 dp. */
    public static LinearLayout buttonRow(Context c, View... vs) {
        LinearLayout row = new LinearLayout(c);
        for (int i = 0; i < vs.length; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            if (i > 0) lp.leftMargin = dp(c, 6);
            row.addView(vs[i], lp);
        }
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.topMargin = dp(c, 6);
        row.setLayoutParams(rlp);
        return row;
    }

    // --------------------------------------------------------- chips/pestañas

    public static TextView chip(Context c, String s, boolean active, View.OnClickListener l) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(13);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(c, 14), dp(c, 8), dp(c, 14), dp(c, 8));
        t.setOnClickListener(l);
        setChip(t, active);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = dp(c, 6);
        t.setLayoutParams(lp);
        return t;
    }

    public static void setChip(TextView t, boolean active) {
        Context c = t.getContext();
        t.setTextColor(active ? Palette.ACCENT : Palette.TEXT_2);
        t.setTypeface(active ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        t.setBackground(active
                ? ripple(Palette.ACCENT_SOFT, Palette.withAlpha(Palette.ACCENT, 0x40), dp(c, RADIUS_CHIP), Palette.ACCENT, dp(c, 1))
                : ripple(Palette.SURFACE_ALT, Palette.withAlpha(Palette.ACCENT, 0x40), dp(c, RADIUS_CHIP), Palette.BORDER, dp(c, 1)));
        t.setSelected(active);
    }

    /** Pestañas segmentadas; devuelve los chips para marcar la activa con setChip. */
    public static TextView[] tabs(LinearLayout into, String[] labels, int active, java.util.function.IntConsumer onPick) {
        Context c = into.getContext();
        TextView[] out = new TextView[labels.length];
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            TextView t = chip(c, labels[i], i == active, null);
            t.setOnClickListener(v -> {
                for (int k = 0; k < out.length; k++) setChip(out[k], k == idx);
                onPick.accept(idx);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            if (i > 0) lp.leftMargin = dp(c, 6);
            into.addView(t, lp);
            out[i] = t;
        }
        return out;
    }

    // ----------------------------------------------------------- drawables

    public static GradientDrawable shape(int fill, int stroke, int radius, int strokeW) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(radius);
        if (strokeW > 0) d.setStroke(strokeW, stroke);
        return d;
    }

    public static Drawable ripple(int fill, int rippleColor, int radius) {
        return ripple(fill, rippleColor, radius, 0, 0);
    }

    public static Drawable ripple(int fill, int rippleColor, int radius, int stroke, int strokeW) {
        GradientDrawable content = shape(fill, stroke, radius, strokeW);
        GradientDrawable mask = shape(Color.WHITE, 0, radius, 0);
        // Deshabilitado: el mismo fondo con menos opacidad.
        GradientDrawable off = shape(Palette.withAlpha(fill == Color.TRANSPARENT ? 0 : fill, 0x66), stroke, radius, strokeW);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{-android.R.attr.state_enabled}, off);
        states.addState(new int[]{}, content);
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), states, mask);
    }

    /** Reemplaza márgenes de un hijo ya agregado (para ajustar sin rehacer). */
    public static void topMargin(View v, int dpTop) {
        ViewGroup.LayoutParams p = v.getLayoutParams();
        if (p instanceof ViewGroup.MarginLayoutParams) ((ViewGroup.MarginLayoutParams) p).topMargin = dp(v.getContext(), dpTop);
    }
}
