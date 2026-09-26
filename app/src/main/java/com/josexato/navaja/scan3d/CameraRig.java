package com.josexato.navaja.scan3d;

import androidx.annotation.RequiresApi;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Size;
import android.util.SizeF;

import com.josexato.navaja.scan3d.core.Undistorter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * Cámaras traseras con Camera2. En equipos con cámara lógica multi-lente (los
 * Galaxy Ultra la tienen: gran angular + ultra gran angular + teleobjetivos)
 * se pueden abrir varias cámaras FÍSICAS a la vez dentro de la misma sesión;
 * cada una da su propio flujo YUV y se calibra por separado con el tapete.
 *
 * Si el equipo no acepta la combinación, se cae a la cámara lógica sola.
 */
@RequiresApi(api = 30)
final class CameraRig {

    interface Listener {
        /** Foto completa pedida con {@link #requestFrames()}. Hilo de la cámara. */
        void onFrame(Frame f);

        /** Vista previa a media resolución de la primera cámara. Hilo de la cámara. */
        void onPreview(int[] argb, int w, int h);

        void onInfo(String msg);

        void onError(String msg);
    }

    static final class Frame {
        final int cam;
        final int w, h;
        final int[] argb;
        final long timestampMs;

        Frame(int cam, int w, int h, int[] argb) {
            this.cam = cam; this.w = w; this.h = h; this.argb = argb;
            this.timestampMs = SystemClock.elapsedRealtime();
        }
    }

    static final class CamInfo {
        String logicalId;
        String physicalId;   // null = cámara lógica
        String label;
        Size size;           // tamaño del flujo de análisis
        double fMetaPx;      // focal en píxeles del flujo según metadatos (NaN si no hay)
        int sensorOrientation;
        double equiv35 = Double.NaN;  // focal equivalente 35 mm
        double hfovDeg = Double.NaN;
        /** Intrínsecos en píxeles del flujo y LENS_DISTORTION (null si no hay datos). */
        double[] intr;
        float[] distK;
        /** Corrección por software; se construye sólo para las cámaras abiertas (mapas grandes). */
        Undistorter undistorter;

        boolean hasDistortion() { return distK != null; }

        String key() { return physicalId == null ? "L" + logicalId : physicalId; }

        @Override
        public String toString() { return label; }
    }

    private static final int TARGET_W = 1280, TARGET_H = 960;

    private final Context ctx;
    private final Listener listener;
    private final CameraManager manager;
    private HandlerThread thread;
    private Handler handler;
    private CameraDevice device;
    private CameraCaptureSession session;
    private final List<ImageReader> readers = new ArrayList<>();
    private List<CamInfo> active = new ArrayList<>();
    private volatile boolean[] wantFrame = new boolean[0];
    private long lastPreviewMs = 0;
    private boolean locked = false;
    private boolean hqDistortion = false;
    /** null = aún no se sabe; true = el HAL entrega la imagen ya rectificada. */
    private volatile Boolean halCorrected = null;

    CameraRig(Context ctx, Listener listener) {
        this.ctx = ctx;
        this.listener = listener;
        this.manager = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
    }

    /** Cámara lógica trasera + cada cámara física que expone (si las hay). */
    List<CamInfo> enumerate() throws CameraAccessException {
        String best = null;
        int bestPhys = -1;
        for (String id : manager.getCameraIdList()) {
            CameraCharacteristics c = manager.getCameraCharacteristics(id);
            Integer facing = c.get(CameraCharacteristics.LENS_FACING);
            if (facing == null || facing != CameraCharacteristics.LENS_FACING_BACK) continue;
            int phys = c.getPhysicalCameraIds().size();
            if (phys > bestPhys) { bestPhys = phys; best = id; }
        }
        List<CamInfo> out = new ArrayList<>();
        if (best == null) return out;
        CameraCharacteristics lc = manager.getCameraCharacteristics(best);
        CamInfo logical = info(best, null, lc);
        logical.label = "Lógica " + best + " (automática, " + describe(logical, logical) + ")";
        out.add(logical);
        List<CamInfo> phys = new ArrayList<>();
        for (String pid : lc.getPhysicalCameraIds()) {
            CamInfo ci = info(best, pid, manager.getCameraCharacteristics(pid));
            if (ci.size != null) phys.add(ci);
        }
        // Ordenadas de gran angular a teleobjetivo, con el zoom relativo a la lógica (1x).
        phys.sort((a, b) -> Double.compare(a.equiv35, b.equiv35));
        for (CamInfo ci : phys) ci.label = "Física " + ci.physicalId + " · " + describe(ci, logical);
        out.addAll(phys);
        return out;
    }

    /**
     * Distancia mínima (cm) para que el tapete (279 mm + margen) quepa en el
     * ancho de la imagen. Los teleobjetivos necesitan alejarse mucho.
     */
    static double minMatDistanceCm(CamInfo ci) {
        if (Double.isNaN(ci.hfovDeg)) return Double.NaN;
        return 32.0 / (2 * Math.tan(Math.toRadians(ci.hfovDeg) / 2));
    }

    private static String describe(CamInfo ci, CamInfo ref) {
        if (Double.isNaN(ci.equiv35)) return "?";
        String zoom = Double.isNaN(ref.equiv35) ? "" : String.format(Locale.US, "%.1f× · ", ci.equiv35 / ref.equiv35);
        return String.format(Locale.US, "%s%.0f mm eq · %.0f° · tapete desde %.0f cm%s",
                zoom, ci.equiv35, ci.hfovDeg, minMatDistanceCm(ci), ci.hasDistortion() ? " · corrige distorsión" : "");
    }

    private CamInfo info(String logical, String physical, CameraCharacteristics c) {
        CamInfo ci = new CamInfo();
        ci.logicalId = logical;
        ci.physicalId = physical;
        StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        ci.size = map == null ? null : pickSize(map.getOutputSizes(ImageFormat.YUV_420_888));
        Integer so = c.get(CameraCharacteristics.SENSOR_ORIENTATION);
        ci.sensorOrientation = so == null ? 90 : so;
        ci.fMetaPx = Double.NaN;
        float[] fl = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        SizeF phys = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
        Size pixArr = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
        Rect act = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        if (ci.size != null && fl != null && fl.length > 0 && phys != null && pixArr != null && act != null) {
            double fActive = fl[0] / phys.getWidth() * pixArr.getWidth();
            double aw = act.width(), ah = act.height();
            double ow = ci.size.getWidth(), oh = ci.size.getHeight();
            // El flujo es la zona activa recortada al aspecto de salida y escalada.
            double scale = (ow / oh >= aw / ah) ? ow / aw : oh / ah;
            ci.fMetaPx = fActive * scale;
            double diag = Math.hypot(phys.getWidth(), phys.getHeight());
            ci.equiv35 = fl[0] * 43.27 / diag;
            ci.hfovDeg = Math.toDegrees(2 * Math.atan(phys.getWidth() / (2 * fl[0])));
            // Intrínsecos + distorsión publicados (relativos a la zona activa):
            // se llevan al tamaño del flujo (recorte centrado + escala).
            float[] in = c.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION);
            float[] dist = c.get(CameraCharacteristics.LENS_DISTORTION);
            if (in != null && in.length >= 4 && in[0] > 0) {
                double offX = (aw - ow / scale) / 2, offY = (ah - oh / scale) / 2;
                double fx = in[0] * scale, fy = in[1] * scale;
                double cx = (in[2] - offX) * scale, cy = (in[3] - offY) * scale;
                ci.fMetaPx = (fx + fy) / 2;
                ci.intr = new double[]{fx, fy, cx, cy};
                if (dist != null && dist.length >= 5 && cornerShift(ci.intr, dist, ow, oh) > 0.75) ci.distK = dist;
            }
        }
        return ci;
    }

    private static void buildUndistorter(CamInfo ci) {
        if (ci.hasDistortion() && ci.undistorter == null)
            ci.undistorter = new Undistorter(ci.size.getWidth(), ci.size.getHeight(),
                    ci.intr[0], ci.intr[1], ci.intr[2], ci.intr[3], ci.distK);
    }

    /** Desplazamiento (px) que produce la distorsión en la esquina de la imagen. */
    private static double cornerShift(double[] in, float[] k, double w, double h) {
        double x = (0 - in[2]) / in[0], y = (0 - in[3]) / in[1];
        double r2 = x * x + y * y;
        double rad = 1 + r2 * (k[0] + r2 * (k[1] + r2 * k[2]));
        double xc = x * rad + 2 * k[3] * x * y + k[4] * (r2 + 2 * x * x);
        double yc = y * rad + k[3] * (r2 + 2 * y * y) + 2 * k[4] * x * y;
        return Math.hypot((xc - x) * in[0], (yc - y) * in[1]);
    }

    /** 4:3 más cercano a 1280x960 (suficiente detalle y rápido de procesar). */
    private static Size pickSize(Size[] sizes) {
        if (sizes == null) return null;
        Size best = null;
        double bestScore = Double.MAX_VALUE;
        for (Size s : sizes) {
            double aspect = s.getWidth() / (double) s.getHeight();
            double score = Math.abs(aspect - 4.0 / 3) * 10000
                    + Math.abs(s.getWidth() * s.getHeight() - TARGET_W * TARGET_H) / 1000.0;
            if (score < bestScore) { bestScore = score; best = s; }
        }
        return best;
    }

    List<CamInfo> activeCameras() { return active; }

    /** Abre las cámaras elegidas (todas de la misma lógica). */
    void open(List<CamInfo> selection) throws CameraAccessException, SecurityException {
        close();
        thread = new HandlerThread("camara");
        thread.start();
        handler = new Handler(thread.getLooper());
        active = new ArrayList<>(selection);
        for (CamInfo ci : active) buildUndistorter(ci);
        wantFrame = new boolean[active.size()];
        final String logicalId = active.get(0).logicalId;
        int[] dm = manager.getCameraCharacteristics(logicalId)
                .get(CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES);
        hqDistortion = false;
        if (dm != null) for (int m : dm) if (m == CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY) hqDistortion = true;
        manager.openCamera(logicalId, new CameraDevice.StateCallback() {
            @Override
            public void onOpened(CameraDevice cameraDevice) {
                device = cameraDevice;
                try {
                    startSession(true);
                } catch (Exception e) {
                    listener.onError("No se pudo iniciar la sesión: " + e);
                }
            }

            @Override
            public void onDisconnected(CameraDevice cameraDevice) {
                cameraDevice.close();
                device = null;
            }

            @Override
            public void onError(CameraDevice cameraDevice, int error) {
                cameraDevice.close();
                device = null;
                listener.onError("Error de cámara " + error);
            }
        }, handler);
    }

    private void startSession(boolean allowFallback) throws CameraAccessException {
        for (ImageReader r : readers) r.close();
        readers.clear();
        List<OutputConfiguration> outs = new ArrayList<>();
        for (int i = 0; i < active.size(); i++) {
            CamInfo ci = active.get(i);
            ImageReader r = ImageReader.newInstance(ci.size.getWidth(), ci.size.getHeight(), ImageFormat.YUV_420_888, 3);
            final int camIdx = i;
            r.setOnImageAvailableListener(reader -> onImage(reader, camIdx), handler);
            readers.add(r);
            OutputConfiguration oc = new OutputConfiguration(r.getSurface());
            if (ci.physicalId != null) oc.setPhysicalCameraId(ci.physicalId);
            outs.add(oc);
        }
        Executor ex = handler::post;
        SessionConfiguration cfg = new SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outs, ex,
                new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(CameraCaptureSession s) {
                        session = s;
                        try {
                            repeat();
                            listener.onInfo("Cámaras activas: " + active);
                        } catch (Exception e) {
                            listener.onError("Error al iniciar la vista previa: " + e);
                        }
                    }

                    @Override
                    public void onConfigureFailed(CameraCaptureSession s) {
                        fallback("la sesión fue rechazada");
                    }
                });
        boolean supported = true;
        try {
            supported = device.isSessionConfigurationSupported(cfg);
        } catch (UnsupportedOperationException | IllegalArgumentException ignored) {
            // El fabricante no implementa la consulta: se intenta igual.
        }
        boolean logicalOnly = active.size() == 1 && active.get(0).physicalId == null;
        if (!supported && allowFallback && !logicalOnly) {
            fallback("el equipo no admite esta combinación de cámaras");
            return;
        }
        device.createCaptureSession(cfg);
    }

    private void fallback(String why) {
        if (active.size() == 1 && active.get(0).physicalId == null) {
            listener.onError("No se pudo configurar la cámara (" + why + ").");
            return;
        }
        listener.onInfo("Multicámara no disponible (" + why + "). Uso sólo la cámara lógica.");
        try {
            List<CamInfo> all = enumerate();
            active = new ArrayList<>();
            active.add(all.get(0));
            buildUndistorter(all.get(0));
            wantFrame = new boolean[1];
            startSession(false);
        } catch (Exception e) {
            listener.onError("Fallo al volver a la cámara lógica: " + e);
        }
    }

    private void repeat() throws CameraAccessException {
        if (session == null) return;
        CaptureRequest.Builder b = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
        for (ImageReader r : readers) b.addTarget(r.getSurface());
        b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
        // Corrección de distorsión: el modelo pinhole asume imagen rectificada.
        if (hqDistortion)
            b.set(CaptureRequest.DISTORTION_CORRECTION_MODE, CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY);
        if (locked) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
            b.set(CaptureRequest.CONTROL_AE_LOCK, true);
            b.set(CaptureRequest.CONTROL_AWB_LOCK, true);
        } else {
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        }
        // En trípode el OIS sobra y mueve el centro óptico entre fotos.
        b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF);
        session.setRepeatingRequest(b.build(), new CameraCaptureSession.CaptureCallback() {
            @Override
            public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest r, TotalCaptureResult res) {
                if (halCorrected != null) return;
                Integer dm = res.get(CaptureResult.DISTORTION_CORRECTION_MODE);
                halCorrected = dm != null && dm != CaptureResult.DISTORTION_CORRECTION_MODE_OFF;
                StringBuilder sb = new StringBuilder("Distorsión: ");
                sb.append(halCorrected ? "la corrige el sistema (modo " + dm + ")" : "el sistema no la corrige");
                for (CamInfo ci : active)
                    if (!halCorrected) sb.append(ci.undistorter != null
                            ? "; cámara " + (ci.physicalId == null ? "lógica" : ci.physicalId) + " corregida por software"
                            : "; cámara " + (ci.physicalId == null ? "lógica" : ci.physicalId) + " sin datos de distorsión");
                listener.onInfo(sb.toString());
            }
        }, handler);
    }

    /**
     * Fija enfoque, exposición y balance de blancos. Es clave: la silueta se
     * saca comparando con la foto de fondo, que debe tener la misma exposición.
     */
    void lock3A() {
        handler.post(() -> {
            try {
                if (session == null) return;
                CaptureRequest.Builder b = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                for (ImageReader r : readers) b.addTarget(r.getSurface());
                b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
                b.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
                session.capture(b.build(), null, handler);
                locked = true;
                repeat();
            } catch (Exception e) {
                listener.onError("No se pudo fijar la exposición: " + e);
            }
        });
    }

    void unlock3A() {
        handler.post(() -> {
            try {
                locked = false;
                repeat();
            } catch (Exception e) {
                listener.onError("No se pudo liberar la exposición: " + e);
            }
        });
    }

    /** Pide la próxima imagen completa de cada cámara activa. */
    void requestFrames() {
        boolean[] w = new boolean[active.size()];
        java.util.Arrays.fill(w, true);
        wantFrame = w;
    }

    private void onImage(ImageReader reader, int cam) {
        Image img = reader.acquireLatestImage();
        if (img == null) return;
        try {
            boolean[] want = wantFrame;
            if (cam < want.length && want[cam]) {
                want[cam] = false;
                int[] argb = YuvUtil.toArgb(img, 1);
                Undistorter u = active.get(cam).undistorter;
                if (!Boolean.TRUE.equals(halCorrected) && u != null && u.w == img.getWidth() && u.h == img.getHeight())
                    argb = u.apply(argb);
                listener.onFrame(new Frame(cam, img.getWidth(), img.getHeight(), argb));
            } else if (cam == 0) {
                long now = SystemClock.elapsedRealtime();
                if (now - lastPreviewMs >= 120) {
                    lastPreviewMs = now;
                    listener.onPreview(YuvUtil.toArgb(img, 2), img.getWidth() / 2, img.getHeight() / 2);
                }
            }
        } finally {
            img.close();
        }
    }

    void close() {
        halCorrected = null;
        try {
            if (session != null) session.close();
        } catch (Exception ignored) {
        }
        session = null;
        if (device != null) device.close();
        device = null;
        for (ImageReader r : readers) r.close();
        readers.clear();
        if (thread != null) thread.quitSafely();
        thread = null;
        locked = false;
    }
}
