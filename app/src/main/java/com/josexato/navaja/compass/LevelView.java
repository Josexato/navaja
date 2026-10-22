package com.josexato.navaja.compass;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

import com.josexato.navaja.compass.core.Tilt;

/**
 * Nivel de burbuja: plano (burbuja 2D en un círculo, ±10°) o de canto (tubo
 * horizontal, ±10°). En modo canto el dibujo gira para quedar derecho para
 * quien lo mira, sostenga el teléfono vertical o apaisado.
 */
final class LevelView extends View {

    static final double RANGE = 10; // grados al borde del dibujo

    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG),
            text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tube = new RectF(), bubble = new RectF(); // reutilizados en cada cuadro
    private Tilt.Reading reading;
    private String big = "", small = "";

    LevelView(Context c) {
        super(c);
        stroke.setStyle(Paint.Style.STROKE);
        text.setColor(Color.WHITE);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.DEFAULT_BOLD);
    }

    void set(Tilt.Reading r, String big, String small) {
        reading = r;
        this.big = big;
        this.small = small;
        invalidate();
    }

    static int color(double total) {
        if (total < 0.3) return 0xFF6EE7A0;
        if (total < 1.5) return 0xFFFFD166;
        return 0xFFFF8A7E;
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), d = getResources().getDisplayMetrics().density;
        float cx = w / 2, cy = h / 2;
        if (reading == null) return;
        int col = color(reading.total());

        c.save();
        if (reading.mode == Tilt.Mode.EDGE) c.rotate(reading.edgeBase, cx, cy);
        boolean sideways = reading.mode == Tilt.Mode.EDGE && (reading.edgeBase == 90 || reading.edgeBase == 270);
        float span = sideways ? h : w; // largo útil en la orientación en que se mira

        text.setTextSize(44 * d);
        text.setColor(col);
        float textY = reading.mode == Tilt.Mode.FLAT ? dp(56) : cy - dp(70);
        c.drawText(big, cx, textY, text);
        text.setTextSize(14 * d);
        text.setColor(0xFFB9C4CF);
        c.drawText(small, cx, textY + dp(24), text);

        if (reading.mode == Tilt.Mode.FLAT) {
            float r = Math.min(w, h - dp(90)) / 2 - dp(16);
            float ccy = dp(90) + r;
            fill.setColor(0xFF1E232B);
            c.drawCircle(cx, ccy, r, fill);
            stroke.setColor(0xFF4A5563);
            stroke.setStrokeWidth(1.5f * d);
            c.drawCircle(cx, ccy, r * 0.5f, stroke);
            c.drawLine(cx - r, ccy, cx + r, ccy, stroke);
            c.drawLine(cx, ccy - r, cx, ccy + r, stroke);
            stroke.setColor(0xFF6EE7A0);
            c.drawCircle(cx, ccy, (r - dp(14)) * (float) (1 / RANGE) + dp(14), stroke); // burbuja dentro = menos de 1°
            // La burbuja va hacia el lado más alto.
            double bx = clamp(reading.x / RANGE), by = clamp(reading.y / RANGE);
            double len = Math.hypot(bx, by);
            if (len > 1) { bx /= len; by /= len; }
            fill.setColor(col);
            c.drawCircle(cx + (float) (bx * (r - dp(14))), ccy - (float) (by * (r - dp(14))), dp(14), fill);
        } else {
            float tw = span * 0.85f, th = dp(46);
            tube.set(cx - tw / 2, cy - th / 2, cx + tw / 2, cy + th / 2);
            fill.setColor(0xFF1E232B);
            c.drawRoundRect(tube, th / 2, th / 2, fill);
            stroke.setColor(0xFF4A5563);
            stroke.setStrokeWidth(2 * d);
            c.drawRoundRect(tube, th / 2, th / 2, stroke);
            float mark = (float) (tw / 2 / RANGE);
            stroke.setColor(0xFF6EE7A0);
            c.drawLine(cx - mark - dp(18), tube.top, cx - mark - dp(18), tube.bottom, stroke);
            c.drawLine(cx + mark + dp(18), tube.top, cx + mark + dp(18), tube.bottom, stroke);
            double bx = clamp(reading.edge / RANGE);
            fill.setColor(col);
            float bw = dp(34);
            float px = cx + (float) (bx * (tw / 2 - bw));
            bubble.set(px - bw, cy - th / 2 + dp(6), px + bw, cy + th / 2 - dp(6));
            c.drawRoundRect(bubble, dp(16), dp(16), fill);
        }
        c.restore();
    }

    private static double clamp(double v) { return Math.max(-1, Math.min(1, v)); }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }
}
