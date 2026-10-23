package com.josexato.navaja.pulse;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.PointF;
import android.graphics.Rect;
import android.media.FaceDetector;
import android.media.Image;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.josexato.navaja.pulse.core.Evm;
import com.josexato.navaja.pulse.core.PulseEstimator;
import com.josexato.navaja.ui.NavajaUi;
import com.josexato.navaja.ui.Palette;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pulso: magnificación de video euleriana en vivo (la piel "late" a la vista)
 * y frecuencia cardíaca por cámara, sin contacto (rostro, rPPG con POS) o con
 * la yema del dedo sobre la cámara y el flash (PPG). No es un dispositivo
 * médico, no guarda video y no interpreta emociones ni "mentiras".
 */
public class PulseActivity extends Activity implements PulseCamera.Listener {

    private static final int REQ_CAM = 1;
    private static final int[] GAINS = {0, 30, 60, 120};

    private final Handler ui = new Handler(Looper.getMainLooper());
    private HandlerThread thread;
    private Handler work;
    private PulseCamera camera;

    // Estado (lo toca el hilo de trabajo; los cambios desde la UI van por work.post).
    private boolean fingerMode;
    private boolean frontCamera = true;
    private int gainIdx = 2;
    private PulseEstimator estimator = new PulseEstimator(PulseEstimator.Mode.FACE);
    private Evm evm;
    private int[] yuvRgb, upright, out, small;
    private int fw, fh;
    private long lastTs;
    private int frames;
    private Rect[] rois = new Rect[0];
    private long roiTime;
    private final Bitmap[] bitmaps = new Bitmap[2];
    private int bmpIdx;

    // UI.
    private PulseViews.Preview preview;
    private PulseViews.Wave wave;
    private NavajaUi.Metric mBpm, mConf, mHrv, mPerf;
    private LinearLayout fingerRow, faceControls;
    private TextView hint;
    private TextView[] gainChips;
    private TextView camChip;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAM);
            return;
        }
        start();
    }

    @Override
    protected void onPause() {
        stop();
        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        super.onRequestPermissionsResult(code, p, r);
        if (r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED) start();
        else hint.setText("Sin permiso de cámara.");
    }

    private void start() {
        if (thread != null) return;
        thread = new HandlerThread("pulso");
        thread.start();
        work = new Handler(thread.getLooper());
        camera = new PulseCamera(this, work, this);
        boolean front = !fingerMode && frontCamera;
        work.post(() -> {
            resetMeasurement();
            camera.open(front, fingerMode);
        });
    }

    private void stop() {
        if (thread == null) return;
        PulseCamera c = camera;
        work.post(c::close);
        thread.quitSafely();
        thread = null;
        work = null;
        camera = null;
    }

    private void restart() {
        stop();
        start();
    }

    private void resetMeasurement() {
        estimator = new PulseEstimator(fingerMode ? PulseEstimator.Mode.FINGER : PulseEstimator.Mode.FACE);
        evm = null;
        lastTs = 0;
        frames = 0;
        rois = new Rect[0];
    }

    // ========================================================== procesamiento

    private final Object lock = new Object();

    @Override
    public void onFrame(Image img, int sensorOrientation, boolean front) {
        // Al cambiar de modo, el hilo anterior puede estar terminando un cuadro.
        synchronized (lock) {
            process(img, sensorOrientation, front);
        }
    }

    private void process(Image img, int sensorOrientation, boolean front) {
        int w = img.getWidth() / 2, h = img.getHeight() / 2;
        if (yuvRgb == null || yuvRgb.length != w * h) yuvRgb = new int[w * h];
        toArgbHalf(img, yuvRgb);
        // Girar a vertical (y espejo en la frontal, como un espejo real).
        boolean swap = sensorOrientation == 90 || sensorOrientation == 270;
        int uw = swap ? h : w, uh = swap ? w : h;
        if (upright == null || upright.length != uw * uh) {
            upright = new int[uw * uh];
            out = new int[uw * uh];
            bitmaps[0] = Bitmap.createBitmap(uw, uh, Bitmap.Config.ARGB_8888);
            bitmaps[1] = Bitmap.createBitmap(uw, uh, Bitmap.Config.ARGB_8888);
            evm = null;
        }
        rotate(yuvRgb, w, h, sensorOrientation, front, upright);
        fw = uw;
        fh = uh;

        double t = img.getTimestamp() / 1e9;
        double dt = lastTs == 0 ? 1 / 30.0 : Math.max(1e-3, t - lastTs / 1e9);
        lastTs = img.getTimestamp();
        frames++;

        // Región de medición.
        if (fingerMode) {
            rois = new Rect[]{new Rect(fw / 4, fh / 4, fw * 3 / 4, fh * 3 / 4)};
        } else if (frames % 15 == 1 || System.currentTimeMillis() - roiTime > 2000) {
            Rect[] found = detectFace();
            if (found != null) {
                rois = found;
                roiTime = System.currentTimeMillis();
            } else if (System.currentTimeMillis() - roiTime > 2000) {
                rois = new Rect[0];
            }
        }
        Rect[] measure = rois.length > 0 ? rois
                : new Rect[]{new Rect(fw * 36 / 100, fh * 26 / 100, fw * 64 / 100, fh * 36 / 100)}; // frente dentro del óvalo
        double[] m = meanSkin(measure, !fingerMode);
        if (m != null) estimator.add(t, m[0], m[1], m[2]);

        // Magnificación euleriana (sólo rostro).
        int gain = fingerMode ? 0 : GAINS[gainIdx];
        int[] shown = upright;
        if (gain > 0) {
            int ew = 30, eh = Math.max(1, Math.round(30f * fh / fw));
            if (evm == null || evm.width() != ew || evm.height() != eh) {
                evm = new Evm(ew, eh);
                small = new int[ew * eh];
            }
            evm.alpha = gain;
            Evm.downsample(upright, fw, fh, small, ew, eh);
            evm.update(small, dt);
            evm.apply(upright, fw, fh, out);
            shown = out;
        }
        Bitmap bmp = bitmaps[bmpIdx];
        bmpIdx ^= 1;
        bmp.setPixels(shown, 0, fw, 0, 0, fw, fh);
        final Rect[] r = rois;
        final boolean guide = !fingerMode && rois.length == 0;
        ui.post(() -> preview.set(bmp, r, guide));

        if (frames % 15 == 0) {
            PulseEstimator.Result res = estimator.estimate();
            ui.post(() -> showResult(res));
        }
    }

    @Override
    public void onError(String msg) {
        ui.post(() -> hint.setText(msg));
    }

    /** Cara con android.media.FaceDetector → frente y mejillas. null si no hay cara. */
    private Rect[] detectFace() {
        int dw = 160, dh = Math.round(160f * fh / fw);
        if (dw % 2 == 1) dw++;
        int[] tiny = new int[dw * dh];
        Evm.downsample(upright, fw, fh, tiny, dw, dh);
        Bitmap b = Bitmap.createBitmap(tiny, dw, dh, Bitmap.Config.RGB_565);
        FaceDetector.Face[] faces = new FaceDetector.Face[1];
        int n = new FaceDetector(dw, dh, 1).findFaces(b, faces);
        b.recycle();
        if (n == 0 || faces[0] == null || faces[0].confidence() < 0.3f) return null;
        PointF mid = new PointF();
        faces[0].getMidPoint(mid);
        float s = (float) fw / dw, cx = mid.x * s, cy = mid.y * s, ed = faces[0].eyesDistance() * s;
        if (ed < 12) return null; // cara demasiado lejos
        return new Rect[]{
                clip(cx - 0.7f * ed, cy - 1.05f * ed, cx + 0.7f * ed, cy - 0.45f * ed),   // frente
                clip(cx - 0.95f * ed, cy + 0.35f * ed, cx - 0.30f * ed, cy + 0.95f * ed), // mejilla
                clip(cx + 0.30f * ed, cy + 0.35f * ed, cx + 0.95f * ed, cy + 0.95f * ed), // mejilla
        };
    }

    private Rect clip(float l, float t, float r, float b) {
        return new Rect(Math.max(0, (int) l), Math.max(0, (int) t), Math.min(fw, (int) r), Math.min(fh, (int) b));
    }

    /** Color medio de las regiones; con {@code skinOnly} descarta lo que no parece piel (pelo, fondo, ojos). */
    private double[] meanSkin(Rect[] rs, boolean skinOnly) {
        long sr = 0, sg = 0, sb = 0;
        int n = 0;
        for (Rect rc : rs) {
            for (int y = rc.top; y < rc.bottom; y++) {
                for (int x = rc.left; x < rc.right; x++) {
                    int c = upright[y * fw + x];
                    int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
                    if (skinOnly) {
                        int cb = 128 + (-43 * r - 85 * g + 128 * b) / 256, cr = 128 + (128 * r - 107 * g - 21 * b) / 256;
                        if (cb < 77 || cb > 127 || cr < 133 || cr > 173) continue;
                    }
                    sr += r;
                    sg += g;
                    sb += b;
                    n++;
                }
            }
        }
        if (n < 30) return null;
        return new double[]{sr / (double) n, sg / (double) n, sb / (double) n};
    }

    /** YUV_420_888 → ARGB a la mitad de resolución (BT.601), sin copiar planos completos. */
    private static void toArgbHalf(Image img, int[] dst) {
        int w = img.getWidth(), h = img.getHeight(), ow = w / 2, oh = h / 2;
        Image.Plane[] p = img.getPlanes();
        ByteBuffer y = p[0].getBuffer(), u = p[1].getBuffer(), v = p[2].getBuffer();
        int yRow = p[0].getRowStride(), yPix = p[0].getPixelStride();
        int uRow = p[1].getRowStride(), uPix = p[1].getPixelStride();
        int vRow = p[2].getRowStride(), vPix = p[2].getPixelStride();
        for (int oy = 0; oy < oh; oy++) {
            int sy = oy * 2;
            int yBase = sy * yRow, uBase = oy * uRow, vBase = oy * vRow;
            for (int ox = 0; ox < ow; ox++) {
                int yy = (y.get(yBase + ox * 2 * yPix) & 255) - 16;
                int uu = (u.get(uBase + ox * uPix) & 255) - 128;
                int vv = (v.get(vBase + ox * vPix) & 255) - 128;
                if (yy < 0) yy = 0;
                int y1 = 1192 * yy;
                int r = (y1 + 1634 * vv) >> 10, g = (y1 - 833 * vv - 400 * uu) >> 10, b = (y1 + 2066 * uu) >> 10;
                r = r < 0 ? 0 : Math.min(r, 255);
                g = g < 0 ? 0 : Math.min(g, 255);
                b = b < 0 ? 0 : Math.min(b, 255);
                dst[oy * ow + ox] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
    }

    /** Rota la imagen del sensor a vertical; en la cámara frontal además la refleja. */
    static void rotate(int[] src, int w, int h, int deg, boolean mirror, int[] dst) {
        boolean swap = deg == 90 || deg == 270;
        int dw = swap ? h : w;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int nx, ny;
                switch (deg) {
                    case 90: nx = h - 1 - y; ny = x; break;
                    case 180: nx = w - 1 - x; ny = h - 1 - y; break;
                    case 270: nx = y; ny = w - 1 - x; break;
                    default: nx = x; ny = y;
                }
                if (mirror) nx = dw - 1 - nx;
                dst[ny * dw + nx] = src[y * w + x];
            }
        }
    }

    // ================================================================== UI

    private void showResult(PulseEstimator.Result r) {
        int status = Double.isNaN(r.bpm) ? 0 : r.confidence >= 0.6 ? Palette.OK : r.confidence >= 0.35 ? Palette.WARN : 0;
        mBpm.set(Double.isNaN(r.bpm) ? "—" : String.format(Locale.US, "%.0f", r.bpm), "lpm", status);
        mConf.set(String.format(Locale.US, "%.0f %%", 100 * r.confidence),
                r.seconds < 10 ? String.format(Locale.US, "%.0f / 10 s", Math.min(10, r.seconds)) : null, status);
        if (fingerMode) {
            mHrv.set(Double.isNaN(r.rmssdMs) ? "—" : String.format(Locale.US, "%.0f ms", r.rmssdMs), "variabilidad (RMSSD)", 0);
            mPerf.set(Double.isNaN(r.perfusion) ? "—" : String.format(Locale.US, "%.1f %%", r.perfusion), "no es oxígeno", 0);
        }
        wave.set(r.wave, status == 0 ? Palette.TEXT_3 : status);
        hint.setText(r.problem == null ? baseHint() : r.problem);
    }

    private String baseHint() {
        return fingerMode
                ? "Cubre suavemente la cámara trasera y el flash con la yema del dedo y quédate quieto."
                : "De frente, quieto y con buena luz pareja (sin luces que parpadeen). Mide sólo a quien sepa y acepte.";
    }

    private void buildUi() {
        NavajaUi.Screen sc = NavajaUi.screen(this, "Pulso", "no es un dispositivo médico · no guarda video", null);
        LinearLayout root = sc.content;

        LinearLayout tabs = new LinearLayout(this);
        NavajaUi.tabs(tabs, new String[]{"🙂 Rostro", "☝ Dedo"}, 0, i -> {
            fingerMode = i == 1;
            faceControls.setVisibility(fingerMode ? View.GONE : View.VISIBLE);
            fingerRow.setVisibility(fingerMode ? View.VISIBLE : View.GONE);
            hint.setText(baseHint());
            restart();
        });
        root.addView(tabs);

        preview = new PulseViews.Preview(this);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, 0, 1f);
        plp.topMargin = NavajaUi.dp(this, 8);
        root.addView(preview, plp);

        mBpm = NavajaUi.metric(this, "Pulso");
        mConf = NavajaUi.metric(this, "Confianza");
        root.addView(NavajaUi.metricRow(this, mBpm, mConf));
        mBpm.set("—", "lpm", 0);
        mConf.set("—", null, 0);
        mHrv = NavajaUi.metric(this, "RMSSD");
        mPerf = NavajaUi.metric(this, "Perfusión");
        fingerRow = NavajaUi.metricRow(this, mHrv, mPerf);
        fingerRow.setVisibility(View.GONE);
        root.addView(fingerRow);
        mHrv.set("—", null, 0);
        mPerf.set("—", null, 0);

        wave = new PulseViews.Wave(this);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(-1, NavajaUi.dp(this, 64));
        wlp.topMargin = NavajaUi.dp(this, 6);
        root.addView(wave, wlp);

        faceControls = new LinearLayout(this);
        List<String> labels = new ArrayList<>();
        for (int g : GAINS) labels.add(g == 0 ? "Sin amplificar" : "×" + g);
        gainChips = new TextView[GAINS.length];
        for (int i = 0; i < GAINS.length; i++) {
            final int idx = i;
            gainChips[i] = NavajaUi.chip(this, labels.get(i), i == gainIdx, v -> {
                gainIdx = idx;
                for (int k = 0; k < gainChips.length; k++) NavajaUi.setChip(gainChips[k], k == idx);
            });
            faceControls.addView(gainChips[i]);
        }
        camChip = NavajaUi.chip(this, "Cámara: frontal", false, v -> {
            frontCamera = !frontCamera;
            camChip.setText(frontCamera ? "Cámara: frontal" : "Cámara: trasera");
            restart();
        });
        faceControls.addView(camChip);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(faceControls);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-1, -2);
        hlp.topMargin = NavajaUi.dp(this, 6);
        root.addView(hs, hlp);

        hint = NavajaUi.text(this, baseHint(), NavajaUi.Style.SECONDARY);
        hint.setPadding(0, NavajaUi.dp(this, 6), 0, 0);
        root.addView(hint);
        TextView legal = NavajaUi.text(this, "Orientativo: no reemplaza un oxímetro ni un control médico. El pulso cambia con "
                + "nervios, café o ejercicio; no indica si alguien miente.", NavajaUi.Style.MUTED);
        legal.setTextSize(11);
        legal.setPadding(0, NavajaUi.dp(this, 4), 0, 0);
        root.addView(legal);
        setContentView(sc.root);
    }
}
