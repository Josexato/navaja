package com.josexato.navaja.pulse;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;

import com.josexato.navaja.ui.Palette;

/** Vistas de Pulso: imagen magnificada con las zonas medidas, y la curva del pulso. */
final class PulseViews {

    private PulseViews() { }

    /** Imagen procesada (ya derecha) escalada para llenar la vista, con las regiones de medición. */
    static final class Preview extends View {
        private Bitmap bmp;
        private Rect[] rois = new Rect[0];
        private boolean guide;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG), img = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final RectF dst = new RectF(), tmp = new RectF();

        Preview(Context c) {
            super(c);
            paint.setStyle(Paint.Style.STROKE);
        }

        void set(Bitmap b, Rect[] rois, boolean guide) {
            bmp = b;
            this.rois = rois == null ? new Rect[0] : rois;
            this.guide = guide;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            c.drawColor(Palette.BG);
            if (bmp == null) return;
            float s = Math.min((float) getWidth() / bmp.getWidth(), (float) getHeight() / bmp.getHeight());
            float w = bmp.getWidth() * s, h = bmp.getHeight() * s;
            dst.set((getWidth() - w) / 2, (getHeight() - h) / 2, (getWidth() + w) / 2, (getHeight() + h) / 2);
            c.drawBitmap(bmp, null, dst, img);
            float d = getResources().getDisplayMetrics().density;
            paint.setStrokeWidth(2 * d);
            paint.setColor(Palette.ACCENT);
            for (Rect r : rois) {
                tmp.set(dst.left + r.left * s, dst.top + r.top * s, dst.left + r.right * s, dst.top + r.bottom * s);
                c.drawRoundRect(tmp, 6 * d, 6 * d, paint);
            }
            if (guide) {
                // Óvalo guía cuando no se detecta una cara.
                paint.setColor(Palette.withAlpha(Palette.TEXT, 0x99));
                tmp.set(dst.centerX() - w * 0.28f, dst.centerY() - h * 0.30f, dst.centerX() + w * 0.28f, dst.centerY() + h * 0.30f);
                c.drawOval(tmp, paint);
            }
        }
    }

    /** Curva del pulso (últimos segundos), normalizada a ±1. */
    static final class Wave extends View {
        private double[] wave = new double[0];
        private int color = Palette.ACCENT;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        Wave(Context c) {
            super(c);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeJoin(Paint.Join.ROUND);
        }

        void set(double[] w, int color) {
            wave = w == null ? new double[0] : w;
            this.color = color;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float d = getResources().getDisplayMetrics().density;
            p.setColor(Palette.BORDER);
            p.setStrokeWidth(1 * d);
            c.drawLine(0, getHeight() / 2f, getWidth(), getHeight() / 2f, p);
            if (wave.length < 2) return;
            path.reset();
            float mid = getHeight() / 2f, amp = getHeight() * 0.42f;
            for (int i = 0; i < wave.length; i++) {
                float x = (float) i / (wave.length - 1) * getWidth(), y = mid - (float) wave[i] * amp;
                if (i == 0) path.moveTo(x, y);
                else path.lineTo(x, y);
            }
            p.setColor(color);
            p.setStrokeWidth(2.5f * d);
            c.drawPath(path, p);
        }
    }
}
