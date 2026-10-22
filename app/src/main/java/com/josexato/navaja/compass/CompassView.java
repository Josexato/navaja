package com.josexato.navaja.compass;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.view.View;

import com.josexato.navaja.compass.core.Angles;

import java.util.Locale;

/** Rosa de los vientos que gira con el rumbo; flecha opcional hacia un destino. */
final class CompassView extends View {

    private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG), label = new Paint(Paint.ANTI_ALIAS_FLAG),
            big = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    private double heading = Double.NaN;
    private double target = Double.NaN; // rumbo al destino (mismo norte que heading)
    private String sub = "";

    CompassView(Context c) {
        super(c);
        tick.setColor(0xFFB9C4CF);
        tick.setStrokeCap(Paint.Cap.ROUND);
        label.setColor(0xFFE6EDF3);
        label.setTextAlign(Paint.Align.CENTER);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        big.setColor(Color.WHITE);
        big.setTextAlign(Paint.Align.CENTER);
        big.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
    }

    void set(double heading, double target, String sub) {
        this.heading = heading;
        this.target = target;
        this.sub = sub == null ? "" : sub;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float cx = w / 2, r = Math.min(w, h * 0.78f) / 2 - dp(14), cy = r + dp(70);
        float d = getResources().getDisplayMetrics().density;

        // Lectura grande arriba.
        big.setTextSize(42 * d);
        String head = Double.isNaN(heading) ? "—" : String.format(Locale.US, "%.0f° %s", heading, Angles.cardinal(heading));
        c.drawText(head, cx, dp(46), big);
        label.setTextSize(13 * d);
        label.setColor(0xFF9FB0C3);
        c.drawText(sub, cx, dp(66), label);

        // Marca fija (línea de fe).
        fill.setColor(0xFFFF5A4E);
        path.reset();
        path.moveTo(cx, cy - r - dp(4));
        path.lineTo(cx - dp(9), cy - r - dp(18));
        path.lineTo(cx + dp(9), cy - r - dp(18));
        path.close();
        c.drawPath(path, fill);

        c.save();
        c.rotate((float) (Double.isNaN(heading) ? 0 : -heading), cx, cy);
        fill.setColor(0xFF1E232B);
        c.drawCircle(cx, cy, r, fill);
        for (int deg = 0; deg < 360; deg += 5) {
            boolean major = deg % 30 == 0;
            tick.setStrokeWidth(major ? 3 * d : 1.5f * d);
            tick.setColor(deg == 0 ? 0xFFFF5A4E : major ? 0xFFE6EDF3 : 0xFF7E8A99);
            float len = major ? 16 * d : deg % 10 == 0 ? 10 * d : 6 * d;
            double a = Math.toRadians(deg);
            float sx = (float) Math.sin(a), sy = (float) -Math.cos(a);
            c.drawLine(cx + sx * r, cy + sy * r, cx + sx * (r - len), cy + sy * (r - len), tick);
            if (major) {
                String t = deg == 0 ? "N" : deg == 90 ? "E" : deg == 180 ? "S" : deg == 270 ? "O" : Integer.toString(deg);
                boolean cardinal = deg % 90 == 0;
                label.setTextSize((cardinal ? 22 : 13) * d);
                label.setColor(deg == 0 ? 0xFFFF5A4E : cardinal ? Color.WHITE : 0xFFB9C4CF);
                float tr = r - (cardinal ? 44 : 36) * d;
                c.save();
                c.rotate(deg, cx, cy);
                c.drawText(t, cx, cy - tr + label.getTextSize() / 3, label);
                c.restore();
            }
        }
        // Flecha hacia el destino (gira con la rosa: apunta a su rumbo real).
        if (!Double.isNaN(target)) {
            c.save();
            c.rotate((float) target, cx, cy);
            fill.setColor(0xFF6EE7A0);
            path.reset();
            path.moveTo(cx, cy - r + dp(22));
            path.lineTo(cx - dp(14), cy - r + dp(50));
            path.lineTo(cx + dp(14), cy - r + dp(50));
            path.close();
            c.drawPath(path, fill);
            tick.setColor(0xFF6EE7A0);
            tick.setStrokeWidth(4 * d);
            c.drawLine(cx, cy - r + dp(48), cx, cy, tick);
            c.restore();
        }
        c.restore();
        fill.setColor(0xFFE6EDF3);
        c.drawCircle(cx, cy, dp(5), fill);
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }
}
