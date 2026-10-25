package com.josexato.navaja.sign;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import com.josexato.navaja.sign.core.Stamper;
import com.josexato.navaja.ui.Palette;

/**
 * Página del PDF (renderizada con PdfRenderer) con el recuadro del sello
 * encima: tocar lo ubica, arrastrar lo mueve y la esquina inferior derecha
 * cambia el tamaño. Las coordenadas se guardan como fracción de la página
 * visible (u, v = esquina superior izquierda; w = ancho).
 */
final class PageView extends View {

    /** Ancho mínimo del sello en puntos PDF: deja el QR en ~22 mm, legible al imprimir. */
    static final double MIN_STAMP_PT = 62 * Stamper.ASPECT;

    private Bitmap page;
    private double pageWpt = 595, pageHpt = 842;
    double u = 0.5, v = 0.8, w = 0.45;
    private Stamper.Ink ink;
    private int inkColor = 0xFF14307A;
    private final Paint img = new Paint(Paint.FILTER_BITMAP_FLAG), p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF dst = new RectF(), box = new RectF();
    private int mode; // 0 nada, 1 mover, 2 redimensionar
    private float lastX, lastY;
    Runnable onChange;

    PageView(Context c) { super(c); }

    void setPage(Bitmap b, double wPt, double hPt) {
        page = b;
        pageWpt = wPt;
        pageHpt = hPt;
        w = Math.max(w, minW());
        clampBox();
        invalidate();
    }

    void setInk(Stamper.Ink i, int color) {
        ink = i;
        inkColor = color;
        invalidate();
    }

    private double minW() { return Math.min(1, MIN_STAMP_PT / pageWpt); }

    private double hFrac() { return w * pageWpt / Stamper.ASPECT / pageHpt; }

    private void clampBox() {
        double maxW = Math.max(minW(), 1 - 2 * Stamper.PRINT_MARGIN / pageWpt);
        w = Math.max(minW(), Math.min(maxW, w));
        // Igual que Stamper: margen de impresión, para que lo que ves sea exactamente lo que queda en el PDF.
        double mx = Stamper.margin(pageWpt, w * pageWpt) / pageWpt;
        double my = Stamper.margin(pageHpt, hFrac() * pageHpt) / pageHpt;
        u = Math.max(mx, Math.min(1 - w - mx, u));
        v = Math.max(my, Math.min(1 - hFrac() - my, v));
    }

    @Override
    protected void onDraw(Canvas c) {
        c.drawColor(Palette.BG);
        if (page == null) return;
        float s = Math.min(getWidth() / (float) page.getWidth(), getHeight() / (float) page.getHeight());
        float bw = page.getWidth() * s, bh = page.getHeight() * s;
        dst.set((getWidth() - bw) / 2, (getHeight() - bh) / 2, (getWidth() + bw) / 2, (getHeight() + bh) / 2);
        c.drawBitmap(page, null, dst, img);
        box.set(dst.left + (float) u * bw, dst.top + (float) v * bh, dst.left + (float) (u + w) * bw,
                dst.top + (float) (v + hFrac()) * bh);
        // Fondo y borde del sello.
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x40E0913F);
        c.drawRect(box, p);
        float H = box.height();
        // QR (sólo una referencia visual).
        float q = H, qx = box.right - q;
        p.setColor(0xFF000000);
        float m = q / 29f;
        for (int y = 0; y < 25; y++)
            for (int x = 0; x < 25; x++)
                if (finder(x, y) || ((x * 7 + y * 13 + x * y) % 3 == 0 && !nearFinder(x, y)))
                    c.drawRect(qx + (x + 2) * m, box.top + (y + 2) * m, qx + (x + 3) * m, box.top + (y + 3) * m, p);
        // Firma.
        float sigW = Math.min(H * 2.2f, box.width() - q - H * 0.1f), sigH = H * (1 - 0.24f);
        if (ink != null && !ink.strokes.isEmpty()) {
            float hh = Math.min(sigW / ink.aspect, sigH), oy = box.top + (sigH - hh) / 2;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setColor(inkColor);
            for (float[] st : ink.strokes) {
                int n = st.length / 3;
                for (int i = 1; i < n; i++) {
                    p.setStrokeWidth(Math.max(1, hh * 0.022f * (0.4f + 1.2f * st[3 * i + 2])));
                    c.drawLine(box.left + st[3 * i - 3] * hh * ink.aspect, oy + st[3 * i - 2] * hh,
                            box.left + st[3 * i] * hh * ink.aspect, oy + st[3 * i + 1] * hh, p);
                }
            }
        } else {
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xCC5A6472);
            p.setTextSize(Math.max(10, H * 0.16f));
            c.drawText("Tu firma aquí", box.left + H * 0.1f, box.top + sigH * 0.6f, p);
        }
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF5A6472);
        c.drawRect(box.left, box.top + sigH + H * 0.06f, box.left + sigW * 0.6f, box.top + sigH + H * 0.1f, p);
        c.drawRect(box.left, box.top + sigH + H * 0.15f, box.left + sigW * 0.9f, box.top + sigH + H * 0.19f, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2 * getResources().getDisplayMetrics().density);
        p.setColor(Palette.ACCENT);
        c.drawRect(box, p);
        // Asa de tamaño.
        p.setStyle(Paint.Style.FILL);
        c.drawCircle(box.right, box.bottom, 9 * getResources().getDisplayMetrics().density, p);
    }

    private static boolean finder(int x, int y) {
        for (int[] o : new int[][]{{0, 0}, {18, 0}, {0, 18}}) {
            int dx = x - o[0], dy = y - o[1];
            if (dx >= 0 && dx < 7 && dy >= 0 && dy < 7) {
                int d = Math.max(Math.abs(dx - 3), Math.abs(dy - 3));
                return d != 2;
            }
        }
        return false;
    }

    private static boolean nearFinder(int x, int y) {
        return (x < 8 && y < 8) || (x > 16 && y < 8) || (x < 8 && y > 16);
    }

    @Override
    public boolean performClick() { return super.performClick(); }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (page == null || dst.width() <= 0) return false;
        float x = e.getX(), y = e.getY();
        float handle = 28 * getResources().getDisplayMetrics().density;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                if (Math.hypot(x - box.right, y - box.bottom) < handle) mode = 2;
                else if (box.contains(x, y)) mode = 1;
                else {
                    // Tocar fuera: el sello se centra ahí.
                    u = (x - dst.left) / dst.width() - w / 2;
                    v = (y - dst.top) / dst.height() - hFrac() / 2;
                    mode = 1;
                    clampBox();
                }
                lastX = x;
                lastY = y;
                break;
            case MotionEvent.ACTION_MOVE:
                if (mode == 1) {
                    u += (x - lastX) / dst.width();
                    v += (y - lastY) / dst.height();
                } else if (mode == 2) {
                    w = (x - dst.left) / dst.width() - u;
                }
                lastX = x;
                lastY = y;
                clampBox();
                break;
            default:
                if (mode != 0 && e.getActionMasked() == MotionEvent.ACTION_UP) performClick();
                mode = 0;
                if (onChange != null) onChange.run();
        }
        invalidate();
        return true;
    }
}
