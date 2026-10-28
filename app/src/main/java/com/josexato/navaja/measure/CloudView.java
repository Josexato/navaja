package com.josexato.navaja.measure;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.Build;
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

    /** Qué se muestra: la nube original, la nube limpia ({@link com.josexato.navaja.measure.core.Cloud}) o el modelo (malla). */
    static final int ORIGINAL = 0, CLEAN = 1, MODEL = 2;
    private int mode = ORIGINAL;
    /** Nube limpia en los ejes de la caja: puntos, colores y normales. */
    private double[][] cleanPts, cleanN;
    private int[] cleanCols;
    /** Modelo en los ejes de la caja: vértices, triángulos, UV por esquina (px de la textura) y textura (o null: gris sombreado). */
    private double[][] meshV;
    private int[] meshTri;
    private float[] meshUV;
    private Bitmap tex;
    private Paint texPaint;
    private String message;

    void setMode(int m) {
        mode = m;
        // Antes de Android 10 el dibujo acelerado no acepta drawVertices
        if (m == MODEL && Build.VERSION.SDK_INT < 29) setLayerType(LAYER_TYPE_SOFTWARE, null);
        invalidate();
    }

    int mode() { return mode; }

    /** Texto en lugar del dibujo (p. ej. "Calculando…"); null para dibujar. */
    void setMessage(String m) {
        message = m;
        invalidate();
    }

    boolean hasClean() { return cleanPts != null; }

    boolean hasModel() { return meshV != null; }

    /** La nube limpia (coordenadas del modelo). */
    void setClean(double[][] points, int[] cols, double[][] normals) {
        cleanPts = new double[points.length][];
        cleanN = new double[points.length][];
        cleanCols = cols;
        for (int i = 0; i < points.length; i++) {
            cleanPts[i] = toBox(Lin.sub(points[i], box.center));
            cleanN[i] = normals[i] == null ? null : toBox(normals[i]);
        }
        invalidate();
    }

    /** El modelo (vértices en coordenadas del modelo); {@code uv01}: 6 por triángulo en [0, 1] con v hacia arriba, o null sin textura. */
    void setModel(double[][] verts, int[] tri, float[] uv01, Bitmap texture) {
        meshV = new double[verts.length][];
        for (int v = 0; v < verts.length; v++) meshV[v] = toBox(Lin.sub(verts[v], box.center));
        meshTri = tri;
        tex = texture;
        if (texture != null && uv01 != null) {
            meshUV = new float[uv01.length];
            for (int k = 0; k < uv01.length; k += 2) {
                meshUV[k] = uv01[k] * texture.getWidth();
                meshUV[k + 1] = (1 - uv01[k + 1]) * texture.getHeight();
            }
            texPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            texPaint.setShader(new BitmapShader(texture, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        } else {
            meshUV = null;
            texPaint = null;
        }
        invalidate();
    }

    /** Un vector en los ejes de la caja (ancho, alto hacia arriba en pantalla, profundidad). */
    private double[] toBox(double[] d) {
        return new double[]{Lin.dot(d, box.axisW), -Lin.dot(d, box.axisH), Lin.dot(d, box.axisD)};
    }

    private final float[] corner = new float[16];

    void set(double[][] points, int[] cols, Measure.Box b) {
        box = b;
        cleanPts = null;
        meshV = null;
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
        if (message != null) {
            p.setColor(Palette.TEXT);
            p.setTextSize(15 * d);
            p.setTextAlign(Paint.Align.CENTER);
            c.drawText(message, ox, oy, p);
            p.setTextAlign(Paint.Align.LEFT);
            return;
        }
        if (mode == CLEAN && cleanPts != null) drawClean(c, cy, syw, cp, sp, sc, ox, oy, d);
        else if (mode == MODEL && meshV != null) drawModel(c, cy, syw, cp, sp, sc, ox, oy);
        // Primero el entorno (atenuado), luego el objeto
        for (int pass = 0; pass < 2 && mode == ORIGINAL; pass++) {
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
        // Zona elegida (sólo en la nube original): en proyección ortográfica la esfera se ve como un círculo del mismo radio
        if (mode == ORIGINAL && picked >= 0 && !Float.isNaN(sx[picked])) {
            p.setStyle(Paint.Style.STROKE);
            p.setColor(Palette.TEXT);
            p.setStrokeWidth(1.5f * d);
            c.drawCircle(sx[picked], sy[picked], (float) (zoneR * sc), p);
            p.setStyle(Paint.Style.FILL);
            c.drawCircle(sx[picked], sy[picked], 4 * d, p);
        }
    }

    /** Nube limpia: sólo el objeto, cada punto aclarado según cuánto mira su normal hacia quien mira (da volumen). */
    private void drawClean(Canvas c, double cy, double syw, double cp, double sp, double sc, float ox, float oy, float d) {
        p.setStrokeWidth(3.2f * d);
        p.setStrokeCap(Paint.Cap.ROUND);
        Integer[] order = depthOrder(cleanPts, cy, syw, cp, sp);
        for (int i : order) {
            double[] q = cleanPts[i];
            double x = cy * q[0] + syw * q[2], z = -syw * q[0] + cy * q[2], y = cp * q[1] - sp * z;
            float px = (float) (ox + x * sc), py = (float) (oy + y * sc);
            double light = 1;
            if (cleanN[i] != null) light = 0.45 + 0.55 * Math.abs(viewZ(cleanN[i], cy, syw, cp, sp));
            p.setColor(shade(cleanCols[i], light));
            c.drawPoint(px, py, p);
        }
        p.setStrokeCap(Paint.Cap.BUTT);
    }

    /** Modelo: triángulos de atrás hacia adelante; con textura, la de las fotos; sin ella, gris con luz según su inclinación. */
    private void drawModel(Canvas c, double cy, double syw, double cp, double sp, double sc, float ox, float oy) {
        int nt = meshTri.length / 3;
        double[] depth = new double[nt];
        float[] px = new float[meshV.length], py = new float[meshV.length];
        double[] vz = new double[meshV.length];
        for (int v = 0; v < meshV.length; v++) {
            double[] q = meshV[v];
            double x = cy * q[0] + syw * q[2], z = -syw * q[0] + cy * q[2], y = cp * q[1] - sp * z;
            px[v] = (float) (ox + x * sc);
            py[v] = (float) (oy + y * sc);
            vz[v] = sp * q[1] + cp * z;
        }
        Integer[] order = new Integer[nt];
        for (int t = 0; t < nt; t++) {
            order[t] = t;
            depth[t] = vz[meshTri[3 * t]] + vz[meshTri[3 * t + 1]] + vz[meshTri[3 * t + 2]];
        }
        Arrays.sort(order, (a, b) -> Double.compare(depth[a], depth[b]));
        float[] verts = new float[6 * nt], texs = meshUV == null ? null : new float[6 * nt];
        int[] cols = meshUV == null ? new int[3 * nt] : null;
        int k = 0;
        for (int t : order) {
            for (int e = 0; e < 3; e++) {
                int v = meshTri[3 * t + e];
                verts[2 * (3 * k + e)] = px[v];
                verts[2 * (3 * k + e) + 1] = py[v];
                if (texs != null) {
                    texs[2 * (3 * k + e)] = meshUV[6 * t + 2 * e];
                    texs[2 * (3 * k + e) + 1] = meshUV[6 * t + 2 * e + 1];
                }
            }
            if (cols != null) {
                double[] a = meshV[meshTri[3 * t]], b = meshV[meshTri[3 * t + 1]], q = meshV[meshTri[3 * t + 2]];
                double[] n = Lin.cross(Lin.sub(b, a), Lin.sub(q, a));
                double l = Lin.norm(n);
                double light = l > 0 ? 0.3 + 0.7 * Math.abs(viewZ(Lin.scale(n, 1 / l), cy, syw, cp, sp)) : 0.5;
                int col = shade(0xFFB8C4D0, light);
                cols[3 * k] = cols[3 * k + 1] = cols[3 * k + 2] = col;
            }
            k++;
        }
        Paint paint = texPaint != null ? texPaint : p;
        c.drawVertices(Canvas.VertexMode.TRIANGLES, verts.length, verts, 0, texs, 0, cols, 0, null, 0, 0, paint);
    }

    /** Componente hacia quien mira de un vector en los ejes de la caja. */
    private static double viewZ(double[] n, double cy, double syw, double cp, double sp) {
        double z = -syw * n[0] + cy * n[2];
        return sp * n[1] + cp * z;
    }

    /** Orden de dibujo de atrás hacia adelante. */
    private static Integer[] depthOrder(double[][] q, double cy, double syw, double cp, double sp) {
        Integer[] o = new Integer[q.length];
        double[] z = new double[q.length];
        for (int i = 0; i < q.length; i++) {
            o[i] = i;
            z[i] = viewZ(q[i], cy, syw, cp, sp);
        }
        Arrays.sort(o, (a, b) -> Double.compare(z[a], z[b]));
        return o;
    }

    private static int shade(int col, double f) {
        int r = (int) Math.min(255, ((col >> 16) & 255) * f), g = (int) Math.min(255, ((col >> 8) & 255) * f), b = (int) Math.min(255, (col & 255) * f);
        return 0xFF000000 | r << 16 | g << 8 | b;
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
                // Un toque (sin arrastrar) elige el centro de la zona (sobre la nube original, que es la que se reprocesa)
                if (!moved && mode == ORIGINAL) pick(e.getX(), e.getY());
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
