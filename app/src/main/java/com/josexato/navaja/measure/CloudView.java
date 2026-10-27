package com.josexato.navaja.measure;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

import com.josexato.navaja.measure.core.Lin;
import com.josexato.navaja.measure.core.Measure;
import com.josexato.navaja.ui.Palette;

import java.util.Arrays;

/**
 * Nube de puntos girable con el dedo: el objeto en sus colores reales, el
 * resto atenuado y la caja medida en naranja. Proyección ortográfica en los
 * ejes de la caja (alto hacia arriba, de frente al principio). Un toque
 * sobre el objeto elige el centro de una zona para reprocesarla.
 */
final class CloudView extends View {

    private double[][] pts = new double[0][];
    private int[] colors = new int[0];
    private boolean[] obj = new boolean[0];
    private Measure.Box box;
    private double yaw = 0.35, pitch = 0.15, zoom = 1;
    private float lx, ly;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** Posición en pantalla de cada punto en el último dibujo (NaN si quedó fuera). */
    private float[] sx = new float[0], sy = new float[0];
    /** Punto elegido con un toque (centro de la zona) y radio de la zona, en unidades del modelo. */
    private int picked = -1;
    private double zoneR;
    private float downX, downY;
    private boolean moved;
    private Runnable onPick;

    CloudView(Context c) { super(c); }

    private final float[] corner = new float[16];

    void set(double[][] points, int[] cols, Measure.Box b) {
        box = b;
        pts = new double[points.length][];
        colors = cols;
        obj = new boolean[points.length];
        for (int i : b.objectPoints) obj[i] = true;
        sx = new float[points.length];
        sy = new float[points.length];
        Arrays.fill(sx, Float.NaN);
        if (picked >= points.length) picked = -1;
        // Coordenadas en los ejes de la caja, centradas en ella
        for (int i = 0; i < points.length; i++) {
            double[] d = Lin.sub(points[i], b.center);
            pts[i] = new double[]{Lin.dot(d, b.axisW), -Lin.dot(d, b.axisH), Lin.dot(d, b.axisD)};
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        c.drawColor(Palette.SURFACE);
        if (box == null) return;
        double cy = Math.cos(yaw), syw = Math.sin(yaw), cp = Math.cos(pitch), sp = Math.sin(pitch);
        double extent = Math.max(box.alto, Math.max(box.ancho, box.prof)) * 1.6;
        double sc = Math.min(getWidth(), getHeight()) / extent * zoom;
        float ox = getWidth() / 2f, oy = getHeight() / 2f;
        float d = getResources().getDisplayMetrics().density;
        // Primero el entorno (atenuado), luego el objeto
        for (int pass = 0; pass < 2; pass++) {
            p.setStrokeWidth((pass == 0 ? 1.6f : 2.6f) * d);
            for (int i = 0; i < pts.length; i++) {
                if (obj[i] != (pass == 1)) continue;
                double[] q = pts[i];
                double x = cy * q[0] + syw * q[2], z = -syw * q[0] + cy * q[2];
                double y = cp * q[1] - sp * z;
                float px = (float) (ox + x * sc), py = (float) (oy + y * sc);
                sx[i] = Float.NaN;
                if (px < 0 || py < 0 || px > getWidth() || py > getHeight()) continue;
                sx[i] = px;
                sy[i] = py;
                int col = colors[i];
                p.setColor(pass == 0 ? Palette.withAlpha(col | 0xFF000000, 0x55) : col | 0xFF000000);
                c.drawPoint(px, py, p);
            }
        }
        // Caja: esquina i = bits (alto, ancho, prof)
        for (int i = 0; i < 8; i++) {
            double qx = ((i >> 1 & 1) * 2 - 1) * box.ancho / 2, qy = -((i >> 2 & 1) * 2 - 1) * box.alto / 2;
            double qz = ((i & 1) * 2 - 1) * box.prof / 2;
            double x = cy * qx + syw * qz, z = -syw * qx + cy * qz, y = cp * qy - sp * z;
            corner[2 * i] = (float) (ox + x * sc);
            corner[2 * i + 1] = (float) (oy + y * sc);
        }
        p.setColor(Palette.ACCENT);
        p.setStrokeWidth(2 * d);
        for (int i = 0; i < 8; i++)
            for (int j = i + 1; j < 8; j++) {
                int diff = i ^ j;
                if (diff == 1 || diff == 2 || diff == 4) c.drawLine(corner[2 * i], corner[2 * i + 1], corner[2 * j], corner[2 * j + 1], p);
            }
        // Zona elegida: en proyección ortográfica la esfera se ve como un círculo del mismo radio
        if (picked >= 0 && !Float.isNaN(sx[picked])) {
            p.setStyle(Paint.Style.STROKE);
            p.setColor(Palette.TEXT);
            p.setStrokeWidth(1.5f * d);
            c.drawCircle(sx[picked], sy[picked], (float) (zoneR * sc), p);
            p.setStyle(Paint.Style.FILL);
            c.drawCircle(sx[picked], sy[picked], 4 * d, p);
        }
    }

    /** Llama a {@code r} cuando el usuario elige un punto del objeto con un toque. */
    void setOnPick(Runnable r) { onPick = r; }

    /** Índice del punto elegido (en la nube completa), o −1. */
    int picked() { return picked; }

    /** Marca el punto {@code i} como elegido (sin avisar), p. ej. al volver de un reproceso. */
    void select(int i) {
        picked = i >= 0 && i < pts.length && obj[i] ? i : -1;
        invalidate();
    }

    void setZoneRadius(double r) {
        zoneR = r;
        invalidate();
    }

    /** El punto del objeto más cercano al toque (a menos de 32 dp), si hay. */
    private void pick(float x, float y) {
        float lim = 32 * getResources().getDisplayMetrics().density, best = lim * lim;
        int bi = -1;
        for (int i = 0; i < sx.length; i++) {
            if (!obj[i] || Float.isNaN(sx[i])) continue;
            float dx = sx[i] - x, dy = sy[i] - y, d2 = dx * dx + dy * dy;
            if (d2 < best) { best = d2; bi = i; }
        }
        if (bi < 0) return;
        picked = bi;
        invalidate();
        if (onPick != null) onPick.run();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                lx = downX = e.getX();
                ly = downY = e.getY();
                moved = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (Math.hypot(e.getX() - downX, e.getY() - downY) > 10 * getResources().getDisplayMetrics().density) moved = true;
                if (!moved) return true;
                yaw += (e.getX() - lx) * 0.01;
                pitch = Math.max(-1.4, Math.min(1.4, pitch + (e.getY() - ly) * 0.01));
                lx = e.getX();
                ly = e.getY();
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                // Un toque (sin arrastrar) elige el centro de la zona
                if (!moved) pick(e.getX(), e.getY());
                performClick();
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean performClick() { return super.performClick(); }

    void zoom(double f) {
        zoom = Math.max(0.3, Math.min(5, zoom * f));
        invalidate();
    }
}
