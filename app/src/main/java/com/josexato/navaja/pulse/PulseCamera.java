package com.josexato.navaja.pulse;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.util.Log;
import android.util.Range;
import android.util.Size;

import java.util.Collections;

/**
 * Cámara para el pulso: cuadros YUV a ~30 fps, tamaño cercano a 640×480,
 * linterna opcional (modo dedo) y bloqueo de exposición/balance de blancos a
 * los 2 s (si la cámara corrige la exposición mientras medimos, eso se ve
 * como un "latido" falso).
 */
final class PulseCamera {

    interface Listener {
        void onFrame(Image img, int sensorOrientation, boolean front);

        void onError(String msg);
    }

    private static final String TAG = "PulseCamera";

    private final Context ctx;
    private final Handler handler;
    private final Listener listener;
    private CameraDevice device;
    private CameraCaptureSession session;
    private ImageReader reader;
    private CaptureRequest.Builder builder;
    private int orientation;
    private boolean front;

    PulseCamera(Context ctx, Handler handler, Listener listener) {
        this.ctx = ctx;
        this.handler = handler;
        this.listener = listener;
    }

    @SuppressLint("MissingPermission") // el permiso se verifica antes de llamar
    void open(boolean wantFront, boolean torch) {
        CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
        try {
            String id = null;
            CameraCharacteristics ch = null;
            for (String c : cm.getCameraIdList()) {
                CameraCharacteristics cc = cm.getCameraCharacteristics(c);
                Integer facing = cc.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && (facing == CameraCharacteristics.LENS_FACING_FRONT) == wantFront) {
                    id = c;
                    ch = cc;
                    break;
                }
            }
            if (id == null) {
                listener.onError("No hay cámara " + (wantFront ? "frontal" : "trasera"));
                return;
            }
            Integer so = ch.get(CameraCharacteristics.SENSOR_ORIENTATION);
            orientation = so == null ? 90 : so;
            front = wantFront;
            Size size = pickSize(ch);
            Range<Integer> fps = pickFps(ch);
            reader = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.YUV_420_888, 3);
            reader.setOnImageAvailableListener(r -> {
                Image img = r.acquireLatestImage();
                if (img == null) return;
                try {
                    listener.onFrame(img, orientation, front);
                } finally {
                    img.close();
                }
            }, handler);
            cm.openCamera(id, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice d) {
                    device = d;
                    startSession(torch, fps);
                }

                @Override
                public void onDisconnected(CameraDevice d) {
                    d.close();
                    device = null;
                }

                @Override
                public void onError(CameraDevice d, int error) {
                    d.close();
                    device = null;
                    listener.onError("Error de cámara " + error);
                }
            }, handler);
        } catch (CameraAccessException | SecurityException | IllegalArgumentException e) {
            listener.onError("No se pudo abrir la cámara: " + e.getMessage());
        }
    }

    @SuppressWarnings("deprecation") // createCaptureSession(List…) es la vía disponible desde API 24
    private void startSession(boolean torch, Range<Integer> fps) {
        try {
            builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(reader.getSurface());
            builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            if (fps != null) builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps);
            builder.set(CaptureRequest.FLASH_MODE, torch ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
            device.createCaptureSession(Collections.singletonList(reader.getSurface()), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession s) {
                    session = s;
                    repeat();
                    // Tras estabilizarse, se fijan exposición y balance de blancos.
                    handler.postDelayed(() -> {
                        if (builder == null || session == null) return;
                        builder.set(CaptureRequest.CONTROL_AE_LOCK, true);
                        builder.set(CaptureRequest.CONTROL_AWB_LOCK, true);
                        repeat();
                    }, 2000);
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession s) {
                    listener.onError("La cámara no aceptó la configuración");
                }
            }, handler);
        } catch (CameraAccessException | IllegalStateException e) {
            listener.onError("No se pudo iniciar la cámara: " + e.getMessage());
        }
    }

    private void repeat() {
        try {
            session.setRepeatingRequest(builder.build(), null, handler);
        } catch (CameraAccessException | IllegalStateException e) {
            Log.w(TAG, "repeat", e);
        }
    }

    void close() {
        try {
            if (session != null) session.close();
        } catch (Exception ignored) {
            // ya cerrada
        }
        if (device != null) device.close();
        if (reader != null) reader.close();
        session = null;
        device = null;
        reader = null;
        builder = null;
    }

    /** El tamaño YUV más cercano a 640×480 (se procesa a la mitad). */
    private static Size pickSize(CameraCharacteristics ch) {
        StreamConfigurationMap map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size best = new Size(640, 480);
        if (map == null) return best;
        long target = 640L * 480;
        long bestD = Long.MAX_VALUE;
        for (Size s : map.getOutputSizes(ImageFormat.YUV_420_888)) {
            long d = Math.abs((long) s.getWidth() * s.getHeight() - target);
            if (s.getWidth() >= 320 && d < bestD) { bestD = d; best = s; }
        }
        return best;
    }

    /** Rango de fps fijo en 30 si existe (cuadros parejos), si no el más cercano. */
    private static Range<Integer> pickFps(CameraCharacteristics ch) {
        Range<Integer>[] rs = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        if (rs == null) return null;
        Range<Integer> best = null;
        for (Range<Integer> r : rs) {
            if (r.getUpper() < 24) continue;
            int score = Math.abs(r.getUpper() - 30) * 10 + (r.getUpper() - r.getLower());
            if (best == null || score < Math.abs(best.getUpper() - 30) * 10 + (best.getUpper() - best.getLower())) best = r;
        }
        return best;
    }
}
