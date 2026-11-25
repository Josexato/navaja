package com.josexato.navaja.survey;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import com.josexato.navaja.ui.NavajaUi;
import com.josexato.navaja.ui.Palette;

import java.util.ArrayList;
import java.util.List;

/**
 * El plano en miniatura mientras se camina: recorrido, mediciones coloreadas por potencia, habitaciones marcadas y
 * dónde está el teléfono. Se ajusta solo a lo que hay dibujado; el eje y del plano va hacia arriba.
 */
final class SurveyMapView extends View {

    /** Lo que hay que dibujar, ya copiado: la pantalla no toca el registro mientras se sigue grabando. */
    static final class Scene {
        final List<List<double[]>> lines = new ArrayList<>();
        final List<List<double[]>> rooms = new ArrayList<>();
        /** {x, y, color}. */
        final List<double[]> dots = new ArrayList<>();
        double x, y, yawDeg;
        boolean here;
    }

    private Scene scene = new Scene();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    SurveyMapView(Context c) {
        super(c);
        setBackground(NavajaUi.shape(0xCC121519, Palette.BORDER, NavajaUi.dp(c, 10), NavajaUi.dp(c, 1)));
    }

    void show(Scene s) {
        scene = s;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        Scene s = scene;
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        List<List<double[]>> all = new ArrayList<>(s.lines);
        all.addAll(s.rooms);
        for (List<double[]> l : all) {
            for (double[] p : l) {
                minX = Math.min(minX, p[0]);
                maxX = Math.max(maxX, p[0]);
                minY = Math.min(minY, p[1]);
                maxY = Math.max(maxY, p[1]);
            }
        }
        if (s.here) {
            minX = Math.min(minX, s.x);
            maxX = Math.max(maxX, s.x);
            minY = Math.min(minY, s.y);
            maxY = Math.max(maxY, s.y);
        }
        if (minX > maxX) return;
        // Nunca menos de 2 m de lado: al empezar, el temblor de la posición no llena la pantalla.
        double span = Math.max(2.0, Math.max(maxX - minX, maxY - minY));
        double cx = (minX + maxX) / 2, cy = (minY + maxY) / 2;
        float pad = NavajaUi.dp(getContext(), 10);
        float w = getWidth() - 2 * pad, h = getHeight() - 2 * pad;
        double scale = Math.min(w, h) / span;
        float ox = pad + w / 2, oy = pad + h / 2;

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(NavajaUi.dp(getContext(), 2));
        paint.setColor(Palette.TEXT);
        for (List<double[]> room : s.rooms) draw(canvas, room, room.size() >= 3, cx, cy, scale, ox, oy);
        paint.setStrokeWidth(NavajaUi.dp(getContext(), 1.5f));
        paint.setColor(Palette.TEXT_3);
        for (List<double[]> line : s.lines) draw(canvas, line, false, cx, cy, scale, ox, oy);

        paint.setStyle(Paint.Style.FILL);
        float r = NavajaUi.dp(getContext(), 2.5f);
        for (double[] d : s.dots) {
            paint.setColor((int) d[2]);
            canvas.drawCircle(ox + (float) ((d[0] - cx) * scale), oy - (float) ((d[1] - cy) * scale), r, paint);
        }
        if (s.here) {
            float px = ox + (float) ((s.x - cx) * scale), py = oy - (float) ((s.y - cy) * scale);
            double a = Math.toRadians(s.yawDeg);
            float len = NavajaUi.dp(getContext(), 10);
            paint.setColor(Palette.ACCENT);
            canvas.drawCircle(px, py, NavajaUi.dp(getContext(), 4), paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(NavajaUi.dp(getContext(), 2));
            canvas.drawLine(px, py, px + (float) (Math.cos(a) * len), py - (float) (Math.sin(a) * len), paint);
        }
    }

    private void draw(Canvas canvas, List<double[]> pts, boolean closed, double cx, double cy, double scale,
                      float ox, float oy) {
        if (pts.size() < 2) return;
        path.reset();
        for (int i = 0; i < pts.size(); i++) {
            float x = ox + (float) ((pts.get(i)[0] - cx) * scale), y = oy - (float) ((pts.get(i)[1] - cy) * scale);
            if (i == 0) path.moveTo(x, y);
            else path.lineTo(x, y);
        }
        if (closed) path.close();
        canvas.drawPath(path, paint);
    }
}
