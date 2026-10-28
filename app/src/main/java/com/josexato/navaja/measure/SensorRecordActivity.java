package com.josexato.navaja.measure;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import android.util.Size;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.josexato.navaja.ui.NavajaUi;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

/**
 * Grabación del video para medir, con los sensores al mismo tiempo: la
 * orientación del celular (vector de rotación de juego: giroscopio +
 * acelerómetro, sin brújula) y la gravedad, con la hora de cada muestra en
 * el mismo reloj que los cuadros del video. Con la app de cámara del
 * sistema no se puede: mientras graba, Navaja queda en segundo plano y
 * Android no le entrega el giroscopio ni el acelerómetro.
 *
 * <p>Devuelve (RESULT_OK) los extras {@link #EXTRA_VIDEO} y
 * {@link #EXTRA_SENSORS}: rutas del .mp4 y de su .sensores.csv (formato en
 * {@link com.josexato.navaja.measure.core.Imu}). Si la cámara no abre,
 * devuelve RESULT_CANCELED con {@link #EXTRA_ERROR} y la pantalla de medir
 * usa la app de cámara del sistema.
 */
public class SensorRecordActivity extends Activity implements SensorEventListener {

    static final String EXTRA_VIDEO = "video", EXTRA_SENSORS = "sensores", EXTRA_ERROR = "error";
    private static final String TAG = "NavajaGrabar";
    private static final int REQ_CAMERA = 1;
    /** Duración máxima (s), como el límite que se pedía a la app de cámara. */
    private static final int MAX_SECONDS = 30;

    private TextureView preview;
    private TextView status;
    private Button button;
    private HandlerThread thread;
    private Handler cam;
    private final Handler ui = new Handler();
    private CameraDevice device;
    private CameraCaptureSession session;
    private MediaRecorder recorder;
    private Size size;
    private int sensorOrientation;
    private boolean realtimeClock;
    private File video, sensors;
    private volatile boolean recording;
    private volatile long firstFrameNs;
    private long startedMs;
    private SensorManager sm;
    private final StringBuilder samples = new StringBuilder();
    private final Object lock = new Object();
    private boolean asked;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        preview = new TextureView(this);
        root.addView(preview, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = NavajaUi.dp(this, 16);
        bar.setPadding(pad, pad, pad, pad);
        bar.setBackgroundColor(0x99000000);
        status = NavajaUi.text(this, "Abriendo la cámara…", NavajaUi.Style.BODY);
        status.setTextColor(Color.WHITE);
        status.setGravity(Gravity.CENTER);
        bar.addView(status);
        button = NavajaUi.primary(this, "Grabar", v -> toggle());
        button.setEnabled(false);
        bar.addView(NavajaUi.buttonRow(this, button, NavajaUi.secondary(this, "Cancelar", v -> cancel())));
        root.addView(bar, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        setContentView(root);
        sm = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        File dir = new File(getExternalFilesDir(null), "medidas");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        long now = System.currentTimeMillis();
        video = new File(dir, "video_" + now + ".mp4");
        sensors = new File(dir, "video_" + now + ".sensores.csv");
    }

    @Override
    protected void onResume() {
        super.onResume();
        thread = new HandlerThread("grabar");
        thread.start();
        cam = new Handler(thread.getLooper());
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            // Al volver del diálogo se pasa otra vez por aquí: se pide una sola vez
            if (!asked) {
                asked = true;
                requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
            }
            return;
        }
        startSensors();
        whenPreviewReady(this::openCamera);
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        if (req != REQ_CAMERA) return;
        // Con permiso, onResume (que viene a continuación) abre la cámara
        if (res.length == 0 || res[0] != PackageManager.PERMISSION_GRANTED) fail("Sin permiso de cámara");
    }

    @Override
    protected void onPause() {
        // Si se sale grabando (llamada, botón de inicio), se termina el video y se entrega lo grabado
        if (recording) stopRecording(true);
        sm.unregisterListener(this);
        closeCamera();
        if (thread != null) thread.quitSafely();
        super.onPause();
    }

    // ============================================================ sensores

    private void startSensors() {
        Sensor rot = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        if (rot == null) rot = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        Sensor grav = sm.getDefaultSensor(Sensor.TYPE_GRAVITY);
        if (grav == null) grav = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (rot != null) sm.registerListener(this, rot, SensorManager.SENSOR_DELAY_GAME);
        if (grav != null) sm.registerListener(this, grav, SensorManager.SENSOR_DELAY_GAME);
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        if (!recording) return;
        int type = e.sensor.getType();
        synchronized (lock) {
            if (type == Sensor.TYPE_GAME_ROTATION_VECTOR || type == Sensor.TYPE_ROTATION_VECTOR) {
                float[] q = new float[4];
                SensorManager.getQuaternionFromVector(q, e.values); // w, x, y, z
                samples.append(String.format(Locale.ROOT, "r,%d,%.6f,%.6f,%.6f,%.6f\n", e.timestamp, q[1], q[2], q[3], q[0]));
            } else {
                samples.append(String.format(Locale.ROOT, "g,%d,%.4f,%.4f,%.4f\n", e.timestamp, e.values[0], e.values[1], e.values[2]));
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor s, int accuracy) { }

    // ============================================================ cámara

    private void whenPreviewReady(Runnable r) {
        if (preview.isAvailable()) { r.run(); return; }
        preview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture s, int w, int h) { r.run(); }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture s, int w, int h) { }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture s) { return true; }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture s) { }
        });
    }

    private void openCamera() {
        CameraManager cm = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            String id = null;
            for (String c : cm.getCameraIdList()) {
                Integer facing = cm.getCameraCharacteristics(c).get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) { id = c; break; }
            }
            if (id == null) { fail("No hay cámara trasera"); return; }
            CameraCharacteristics ch = cm.getCameraCharacteristics(id);
            StreamConfigurationMap map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Integer so = ch.get(CameraCharacteristics.SENSOR_ORIENTATION);
            sensorOrientation = so == null ? 90 : so;
            Integer src = ch.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE);
            realtimeClock = src != null && src == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME;
            size = pickSize(map == null ? null : map.getOutputSizes(MediaRecorder.class));
            if (size == null) { fail("La cámara no ofrece tamaños de video"); return; }
            // Vista previa con la proporción del video (vertical), sin estirar
            ui.post(() -> {
                int w = ((View) preview.getParent()).getWidth();
                preview.setLayoutParams(new FrameLayout.LayoutParams(w, w * size.getWidth() / size.getHeight(), Gravity.TOP));
            });
            //noinspection MissingPermission (se pidió arriba)
            cm.openCamera(id, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice d) { device = d; startSession(); }
                @Override public void onDisconnected(CameraDevice d) { d.close(); device = null; }
                @Override public void onError(CameraDevice d, int error) { d.close(); device = null; fail("Error de cámara " + error); }
            }, cam);
        } catch (CameraAccessException | SecurityException | IllegalArgumentException e) {
            fail("No se pudo abrir la cámara: " + e.getMessage());
        }
    }

    /** 16:9 de hasta 1920 px de ancho (el análisis usa ~960 px: más es sólo peso), el más grande. */
    static Size pickSize(Size[] sizes) {
        if (sizes == null || sizes.length == 0) return null;
        Size best = null;
        for (Size s : sizes) {
            if (s.getWidth() > 1920 || Math.abs(s.getWidth() * 9 - s.getHeight() * 16) > s.getWidth()) continue;
            if (best == null || s.getWidth() * s.getHeight() > best.getWidth() * best.getHeight()) best = s;
        }
        if (best != null) return best;
        for (Size s : sizes) if (s.getWidth() <= 1920 && (best == null || s.getWidth() > best.getWidth())) best = s;
        return best != null ? best : sizes[0];
    }

    private void startSession() {
        try {
            recorder = new MediaRecorder();
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setOutputFile(video.getAbsolutePath());
            recorder.setVideoEncodingBitRate(12_000_000);
            recorder.setVideoFrameRate(30);
            recorder.setVideoSize(size.getWidth(), size.getHeight());
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            // Pantalla fija en vertical: el video sale derecho en vertical (Imu supone ese giro)
            recorder.setOrientationHint(sensorOrientation);
            recorder.setMaxDuration(MAX_SECONDS * 1000);
            recorder.setOnInfoListener((mr, what, extra) -> {
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) ui.post(() -> stopRecording(false));
            });
            recorder.prepare();
            SurfaceTexture st = preview.getSurfaceTexture();
            st.setDefaultBufferSize(size.getWidth(), size.getHeight());
            Surface pv = new Surface(st), rec = recorder.getSurface();
            device.createCaptureSession(Arrays.asList(pv, rec), new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession s) {
                    session = s;
                    try {
                        CaptureRequest.Builder rb = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                        rb.addTarget(pv);
                        rb.addTarget(rec);
                        rb.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
                        s.setRepeatingRequest(rb.build(), new CameraCaptureSession.CaptureCallback() {
                            @Override public void onCaptureCompleted(CameraCaptureSession s2, CaptureRequest r, TotalCaptureResult res) {
                                if (!recording || firstFrameNs != 0) return;
                                Long ts = res.get(CaptureResult.SENSOR_TIMESTAMP);
                                if (ts == null) return;
                                // Al reloj de los sensores (desde el arranque, incluyendo reposo)
                                firstFrameNs = realtimeClock ? ts : ts + (SystemClock.elapsedRealtimeNanos() - System.nanoTime());
                            }
                        }, cam);
                    } catch (CameraAccessException | IllegalStateException e) {
                        fail("No se pudo iniciar la vista: " + e.getMessage());
                        return;
                    }
                    ui.post(() -> {
                        status.setText("Rodea el objeto despacio, con el celular vertical. Máximo " + MAX_SECONDS + " s.");
                        button.setEnabled(true);
                    });
                }

                @Override public void onConfigureFailed(CameraCaptureSession s) { fail("La cámara no aceptó grabar"); }
            }, cam);
        } catch (IOException | RuntimeException | CameraAccessException e) {
            fail("No se pudo preparar la grabación: " + e.getMessage());
        }
    }

    private void closeCamera() {
        if (session != null) { session.close(); session = null; }
        if (device != null) { device.close(); device = null; }
        if (recorder != null) { recorder.release(); recorder = null; }
    }

    // ============================================================ grabar

    private void toggle() {
        if (!recording) {
            try {
                synchronized (lock) { samples.setLength(0); }
                firstFrameNs = 0;
                recorder.start();
                recording = true;
                startedMs = SystemClock.elapsedRealtime();
                button.setText("Detener");
                tick();
            } catch (RuntimeException e) {
                fail("No se pudo empezar a grabar: " + e.getMessage());
            }
        } else stopRecording(false);
    }

    private void tick() {
        if (!recording) return;
        long s = (SystemClock.elapsedRealtime() - startedMs) / 1000;
        status.setText(String.format(Locale.ROOT, "● Grabando %d s de %d · rodea el objeto despacio", s, MAX_SECONDS));
        ui.postDelayed(this::tick, 500);
    }

    /** Termina el video y entrega el video y los sensores. */
    private void stopRecording(boolean quiet) {
        if (!recording) return;
        recording = false;
        try {
            recorder.stop();
        } catch (RuntimeException e) {
            // Video demasiado corto o sin cuadros: no hay nada que entregar
            Log.w(TAG, "detener", e);
            //noinspection ResultOfMethodCallIgnored
            video.delete();
            if (!quiet) fail("El video quedó vacío: graba al menos unos segundos");
            return;
        }
        String header;
        synchronized (lock) {
            long start = firstFrameNs != 0 ? firstFrameNs : SystemClock.elapsedRealtimeNanos();
            header = "#navaja-sensores-1\n#inicio_video_ns=" + start + "\n#reloj_camara=" + (realtimeClock ? "realtime" : "monotonico")
                    + "\n#orientacion_sensor=" + sensorOrientation + "\n#video=" + size.getWidth() + "x" + size.getHeight() + "\n";
            try (FileOutputStream fo = new FileOutputStream(sensors)) {
                fo.write(header.getBytes(StandardCharsets.UTF_8));
                fo.write(samples.toString().getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                Log.w(TAG, "sensores", e);
            }
        }
        setResult(RESULT_OK, new Intent().putExtra(EXTRA_VIDEO, video.getAbsolutePath())
                .putExtra(EXTRA_SENSORS, sensors.exists() ? sensors.getAbsolutePath() : null));
        finish();
    }

    private void cancel() {
        if (recording) {
            recording = false;
            try { recorder.stop(); } catch (RuntimeException ignored) { }
        }
        //noinspection ResultOfMethodCallIgnored
        video.delete();
        setResult(RESULT_CANCELED);
        finish();
    }

    private void fail(String msg) {
        Log.w(TAG, msg);
        ui.post(() -> {
            setResult(RESULT_CANCELED, new Intent().putExtra(EXTRA_ERROR, msg));
            finish();
        });
    }
}
