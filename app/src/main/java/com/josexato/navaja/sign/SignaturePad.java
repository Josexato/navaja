package com.josexato.navaja.sign;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

import com.josexato.navaja.sign.core.Stamper;

import java.util.ArrayList;
import java.util.List;

/**
 * Lienzo de firma: blanco, con línea base, grosor según la presión del
 * lápiz (S Pen y similares). En modo "sólo lápiz" ignora los dedos y la
 * palma, así se puede apoyar la mano al firmar.
 */
final class SignaturePad extends View {

    static final float ASPECT = 2.5f;

    private final List<float[]> strokes = new ArrayList<>();
    private final List<Float> cur = new ArrayList<>();
    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG), guide = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean stylusOnly, sawStylus, drawing;
    private int color = 0xFF14307A;
    Runnable onChange;

    SignaturePad(Context c) {
        super(c);
        ink.setStyle(Paint.Style.STROKE);
        ink.setStrokeCap(Paint.Cap.ROUND);
        guide.setColor(0xFFB8C0CC);
        guide.setStrokeWidth(2);
    }

    void setStylusOnly(boolean b) { stylusOnly = b; }

    boolean sawStylus() { return sawStylus; }

    void setColor(int argb) { color = argb; invalidate(); }

    void clear() {
        strokes.clear();
        cur.clear();
        invalidate();
        if (onChange != null) onChange.run();
    }

    boolean isEmpty() { return strokes.isEmpty(); }

    Stamper.Ink ink() { return new Stamper.Ink(new ArrayList<>(strokes), ASPECT); }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        int h = Math.round(w / ASPECT);
        int maxH = MeasureSpec.getSize(hSpec);
        if (MeasureSpec.getMode(hSpec) != MeasureSpec.UNSPECIFIED && h > maxH) {
            h = maxH;
            w = Math.round(h * ASPECT);
        }
        setMeasuredDimension(w, h);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int tool = e.getToolType(0);
        boolean stylus = tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER;
        if (stylus) sawStylus = true;
        if (stylusOnly && !stylus) return true; // dedo o palma: se ignora
        float w = getWidth(), h = getHeight();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                cur.clear();
                drawing = true;
                add(e.getX(), e.getY(), pressure(e, -1, stylus), w, h);
                break;
            case MotionEvent.ACTION_MOVE:
                if (!drawing) break;
                for (int i = 0; i < e.getHistorySize(); i++) add(e.getHistoricalX(i), e.getHistoricalY(i), pressure(e, i, stylus), w, h);
                add(e.getX(), e.getY(), pressure(e, -1, stylus), w, h);
                break;
            case MotionEvent.ACTION_UP:
                if (!drawing) break;
                add(e.getX(), e.getY(), pressure(e, -1, stylus), w, h);
                finish();
                performClick();
                break;
            case MotionEvent.ACTION_CANCEL:
                finish();
                break;
            default:
                break;
        }
        invalidate();
        return true;
    }

    @Override
    public boolean performClick() { return super.performClick(); }

    /** Con dedo la "presión" del sensor es ruido: se usa un valor medio fijo. */
    private static float pressure(MotionEvent e, int hist, boolean stylus) {
        if (!stylus) return 0.55f;
        float p = hist < 0 ? e.getPressure() : e.getHistoricalPressure(hist);
        return Math.max(0.05f, Math.min(1f, p));
    }

    private void add(float x, float y, float p, float w, float h) {
        x = Math.max(0, Math.min(1, x / w));
        y = Math.max(0, Math.min(1, y / h));
        int n = cur.size();
        // Se descartan puntos casi iguales (menos peso en el PDF, mismo trazo).
        if (n >= 3 && Math.abs(cur.get(n - 3) - x) < 0.002f && Math.abs(cur.get(n - 2) - y) < 0.004f) return;
        cur.add(x);
        cur.add(y);
        cur.add(p);
    }

    private void finish() {
        drawing = false;
        if (cur.isEmpty()) return;
        float[] s = new float[cur.size()];
        for (int i = 0; i < s.length; i++) s[i] = cur.get(i);
        strokes.add(s);
        cur.clear();
        if (onChange != null) onChange.run();
    }

    @Override
    protected void onDraw(Canvas c) {
        c.drawColor(Color.WHITE);
        float w = getWidth(), h = getHeight();
        c.drawLine(w * 0.06f, h * 0.78f, w * 0.94f, h * 0.78f, guide);
        ink.setColor(color);
        for (float[] s : strokes) drawStroke(c, s, w, h);
        // Trazo en curso, directo desde la lista (sin asignar memoria en onDraw).
        float base = h * 0.022f;
        for (int i = 3; i + 2 < cur.size(); i += 3) {
            ink.setStrokeWidth(base * (0.4f + 1.2f * (cur.get(i - 1) + cur.get(i + 2)) / 2));
            c.drawLine(cur.get(i - 3) * w, cur.get(i - 2) * h, cur.get(i) * w, cur.get(i + 1) * h, ink);
        }
    }

    private void drawStroke(Canvas c, float[] s, float w, float h) {
        float base = h * 0.022f;
        int n = s.length / 3;
        if (n == 1) {
            ink.setStrokeWidth(base * (0.4f + 1.2f * s[2]));
            c.drawPoint(s[0] * w, s[1] * h, ink);
            return;
        }
        for (int i = 1; i < n; i++) {
            ink.setStrokeWidth(base * (0.4f + 1.2f * (s[3 * i - 1] + s[3 * i + 2]) / 2));
            c.drawLine(s[3 * i - 3] * w, s[3 * i - 2] * h, s[3 * i] * w, s[3 * i + 1] * h, ink);
        }
    }
}
