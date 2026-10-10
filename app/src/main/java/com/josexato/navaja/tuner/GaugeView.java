package com.josexato.navaja.tuner;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Aguja de afinación: arco de -50 a +50 cents con zona verde de ±5. */
final class GaugeView extends View {

    static final double IN_TUNE_CENTS = 5;

    private double cents = Double.NaN;
    private double shown = 0;          // posición animada de la aguja
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();

    GaugeView(Context c) {
        super(c);
    }

    /** NaN = sin señal (aguja en reposo, atenuada). */
    void setCents(double c) {
        cents = c;
        invalidate();
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        setMeasuredDimension(w, (int) (w * 0.58f));
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h * 0.95f, r = Math.min(w * 0.45f, h * 0.88f);
        oval.set(cx - r, cy - r, cx + r, cy + r);
        // El arco va de 180+30 a 360-30 grados: ±50 cents = ±60°.
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(r * 0.06f);
        p.setColor(0x33FFFFFF);
        c.drawArc(oval, 210, 120, false, p);
        p.setColor(0xFF2E7D32);
        c.drawArc(oval, (float) (270 - IN_TUNE_CENTS * 1.2), (float) (2 * IN_TUNE_CENTS * 1.2), false, p);

        p.setStrokeWidth(r * 0.012f);
        p.setColor(0xAAFFFFFF);
        for (int k = -50; k <= 50; k += 10) {
            double a = Math.toRadians(270 + k * 1.2);
            float l = k % 50 == 0 ? 0.16f : 0.09f;
            c.drawLine(cx + (float) Math.cos(a) * r * (1 - l), cy + (float) Math.sin(a) * r * (1 - l),
                    cx + (float) Math.cos(a) * r, cy + (float) Math.sin(a) * r, p);
        }
        p.setStyle(Paint.Style.FILL);
        p.setTextSize(r * 0.09f);
        p.setTextAlign(Paint.Align.CENTER);
        p.setColor(0xAAFFFFFF);
        c.drawText("♭ -50", cx - r * 0.78f, cy - r * 0.05f, p);
        c.drawText("+50 ♯", cx + r * 0.78f, cy - r * 0.05f, p);

        boolean has = !Double.isNaN(cents);
        double target = has ? Math.max(-55, Math.min(55, cents)) : 0;
        shown += (target - shown) * 0.35; // suavizado visual
        boolean ok = has && Math.abs(cents) <= IN_TUNE_CENTS;
        double a = Math.toRadians(270 + shown * 1.2);
        p.setStrokeWidth(r * 0.025f);
        p.setStyle(Paint.Style.STROKE);
        p.setColor(!has ? 0x55FFFFFF : ok ? 0xFF4CAF50 : Math.abs(cents) < 15 ? 0xFFFFC107 : 0xFFF44336);
        c.drawLine(cx, cy, cx + (float) Math.cos(a) * r * 0.92f, cy + (float) Math.sin(a) * r * 0.92f, p);
        p.setStyle(Paint.Style.FILL);
        c.drawCircle(cx, cy, r * 0.05f, p);
        if (has && Math.abs(target - shown) > 0.3) postInvalidateOnAnimation();
    }

    static int colorFor(double cents) {
        if (Double.isNaN(cents)) return Color.GRAY;
        double a = Math.abs(cents);
        return a <= IN_TUNE_CENTS ? 0xFF4CAF50 : a < 15 ? 0xFFFFC107 : 0xFFF44336;
    }
}
