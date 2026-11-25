package com.josexato.navaja.survey;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.hardware.GeomagneticField;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.telephony.CellInfo;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.google.ar.core.Anchor;
import com.google.ar.core.ArCoreApk;
import com.google.ar.core.Camera;
import com.google.ar.core.Config;
import com.google.ar.core.Coordinates2d;
import com.google.ar.core.Frame;
import com.google.ar.core.HitResult;
import com.google.ar.core.Plane;
import com.google.ar.core.Pose;
import com.google.ar.core.Session;
import com.google.ar.core.Trackable;
import com.google.ar.core.TrackingState;
import com.google.ar.core.exceptions.UnavailableException;
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException;
import com.josexato.navaja.cellular.CellReader;
import com.josexato.navaja.cellular.core.CellReading;
import com.josexato.navaja.cellular.core.SignalMetrics;
import com.josexato.navaja.survey.core.Room;
import com.josexato.navaja.survey.core.SurveyExport;
import com.josexato.navaja.survey.core.SurveyLog;
import com.josexato.navaja.survey.core.Track;
import com.josexato.navaja.ui.NavajaUi;
import com.josexato.navaja.ui.Palette;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Levantamiento: la persona camina con el teléfono y Navaja guarda el recorrido en metros (ARCore), la señal celular
 * en cada punto y, marcando las esquinas del piso, el plano de la habitación con su área y altura. Todo ocurre en el
 * teléfono. Al terminar deja un ZIP con el registro completo, el informe, las mediciones (CSV), el plano (DXF) y, si
 * hubo GPS y rumbo, el mapa (GeoJSON).
 */
public final class SurveyActivity extends Activity
        implements GLSurfaceView.Renderer, SensorEventListener, LocationListener {

    private static final String TAG = "Levantamiento";
    private static final int REQ_PERMS = 41;
    /** Cada cuánto se guarda una posición, se revisan los planos, se prueba la mira y se pide la señal. */
    private static final long POSE_MS = 200, PLANES_MS = 1000, AIM_MS = 150, CELLS_MS = 1000, UI_MS = 500;
    private static final long PRESSURE_MS = 2000, NORTH_MS = 5000;
    /** Un punto GPS peor que esto no sirve para ubicar un recorrido de interiores. */
    private static final float MAX_GPS_ACC_M = 30;
    /** La mira no marca esquinas más lejos que esto. */
    private static final float MAX_AIM_M = 10;

    // ------------------------------------------------------------- pantalla
    private GLSurfaceView glView;
    private TextView status;
    private SurveyMapView map;
    private AimView aim;
    private Button recBtn, cornerBtn, undoBtn, roomBtn;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Executor mainExec = ui::post;

    // ----------------------------------------------------------------- ARCore
    private Session session;
    private boolean installRequested, geometryDirty = true;
    private int viewW, viewH, texId, program, aPos, aTex;
    private final FloatBuffer quad = floats(new float[]{-1, -1, 1, -1, -1, 1, 1, 1});
    private final FloatBuffer quadTex = floats(new float[8]);
    private Anchor anchor;
    private Pose base = Pose.IDENTITY;
    private long lastPose, lastPlanes, lastAim;
    private final Map<String, double[]> loggedPlanes = new HashMap<>();
    /** Altura del piso en el mundo de ARCore, para marcar esquinas donde el plano detectado no llega. */
    private float floorWorldY = Float.NaN;
    private volatile boolean aimOk, cornerAsked, newOrigin;
    private final float[] aimPoint = new float[3];

    // --------------------------------------------------------------- registro
    private final Object lock = new Object();
    private SurveyLog log = new SurveyLog();
    private volatile boolean recording;
    private long startElapsed;
    private volatile String trackText = "Abriendo la cámara…";
    private volatile boolean tracking;
    private volatile double curX, curY, curZ, curYaw;
    /** Hacia dónde mira la cámara (en el mundo de ARCore), para comparar con la brújula. */
    private volatile double camHoriz;

    // ---------------------------------------------------------------- sensores
    private TelephonyManager baseTm;
    private final List<TelephonyManager> sims = new ArrayList<>();
    private SensorManager sensors;
    private LocationManager locations;
    private long lastPressure, lastNorth;
    private double northSin, northCos;
    private int northN;
    private volatile int servingRsrp = CellReading.NA;
    private volatile String servingRat = "";
    private final float[] rot = new float[9];

    // =============================================================== ciclo de vida

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        baseTm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        sensors = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        locations = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        build();
        askPermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!has(Manifest.permission.CAMERA)) {
            trackText = "Hace falta el permiso de cámara.";
            return;
        }
        if (!openSession()) return;
        try {
            session.resume();
        } catch (Exception e) {
            trackText = "La cámara no está disponible: " + e.getMessage();
            session.close();
            session = null;
            return;
        }
        glView.onResume();
        startSensors();
        ui.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(tick);
        ui.removeCallbacks(cellPoll);
        stopSensors();
        if (session != null) {
            glView.onPause();
            session.pause();
        }
    }

    @Override
    protected void onDestroy() {
        if (session != null) {
            session.close();
            session = null;
        }
        super.onDestroy();
    }

    /** Crea la sesión de ARCore la primera vez; pide instalar «Servicios de Google Play para RA» si falta. */
    private boolean openSession() {
        if (session != null) return true;
        try {
            if (ArCoreApk.getInstance().requestInstall(this, !installRequested)
                    == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                installRequested = true;
                return false; // vuelve a onResume cuando termine la instalación
            }
            Session s = new Session(this);
            Config c = new Config(s);
            c.setPlaneFindingMode(Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL);
            c.setFocusMode(Config.FocusMode.AUTO);
            s.configure(c);
            session = s;
            return true;
        } catch (UnavailableUserDeclinedInstallationException e) {
            fatal("El levantamiento necesita «Servicios de Google Play para RA». Sin instalarlo no puede seguir la posición.");
        } catch (UnavailableException e) {
            fatal("Este teléfono no puede usar ARCore (" + e.getClass().getSimpleName() + "), y sin él no hay posición al caminar.");
        } catch (Exception e) {
            fatal("No se pudo iniciar ARCore: " + e);
        }
        return false;
    }

    private void fatal(String msg) {
        new AlertDialog.Builder(this).setTitle("Levantamiento").setMessage(msg)
                .setPositiveButton("Cerrar", (d, w) -> finish()).setCancelable(false).show();
    }

    // ================================================================= permisos

    private boolean has(String perm) {
        return checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    private void askPermissions() {
        List<String> need = new ArrayList<>();
        for (String p : new String[]{Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.READ_PHONE_STATE}) {
            if (!has(p)) need.add(p);
        }
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), REQ_PERMS);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (!has(Manifest.permission.CAMERA)) {
            fatal("Sin la cámara no se puede seguir la posición.");
        }
        // Con el permiso recién dado, Android vuelve a llamar a onResume y ahí se abre la sesión.
    }

    // ================================================================ pantalla

    private void build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Palette.BG);
        root.addView(NavajaUi.headerBar(this, "Levantamiento", "recorrido · señal · plano", null));

        FrameLayout stage = new FrameLayout(this);
        glView = new GLSurfaceView(this);
        glView.setPreserveEGLContextOnPause(true);
        glView.setEGLContextClientVersion(2);
        glView.setRenderer(this);
        glView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        stage.addView(glView, new FrameLayout.LayoutParams(-1, -1));

        aim = new AimView(this);
        int a = NavajaUi.dp(this, 56);
        stage.addView(aim, new FrameLayout.LayoutParams(a, a, Gravity.CENTER));

        status = NavajaUi.text(this, "", NavajaUi.Style.SECONDARY);
        status.setTextColor(Palette.TEXT);
        status.setBackground(NavajaUi.shape(0xCC121519, Palette.BORDER, NavajaUi.dp(this, 10), NavajaUi.dp(this, 1)));
        int p = NavajaUi.dp(this, 8);
        status.setPadding(p, p, p, p);
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        sl.setMargins(p, p, p, p);
        stage.addView(status, sl);

        map = new SurveyMapView(this);
        int m = NavajaUi.dp(this, 150);
        FrameLayout.LayoutParams ml = new FrameLayout.LayoutParams(m, m, Gravity.BOTTOM | Gravity.END);
        ml.setMargins(p, p, p, p);
        stage.addView(map, ml);
        root.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setPadding(NavajaUi.dp(this, 16), NavajaUi.dp(this, 8), NavajaUi.dp(this, 16), NavajaUi.dp(this, 12));
        recBtn = NavajaUi.primary(this, "● Empezar", v -> toggle());
        bar.addView(recBtn);
        cornerBtn = NavajaUi.secondary(this, "＋ Esquina", v -> cornerAsked = true);
        undoBtn = NavajaUi.secondary(this, "↶ Deshacer", v -> undoCorner());
        roomBtn = NavajaUi.secondary(this, "Cerrar habitación", v -> closeRoom());
        LinearLayout row = NavajaUi.buttonRow(this, cornerBtn, undoBtn, roomBtn);
        NavajaUi.topMargin(row, 8);
        bar.addView(row);
        TextView hint = NavajaUi.text(this, "Para el plano: apunta la mira a cada esquina del piso, en orden, y toca "
                + "«＋ Esquina». Apunta una vez al techo para la altura.", NavajaUi.Style.MUTED);
        NavajaUi.topMargin(hint, 8);
        bar.addView(hint);
        root.addView(bar);
        setContentView(root);
        buttons();
    }

    private void buttons() {
        recBtn.setText(recording ? "■ Terminar y guardar" : "● Empezar");
        for (Button b : new Button[]{cornerBtn, undoBtn, roomBtn}) {
            b.setEnabled(recording);
            b.setAlpha(recording ? 1f : 0.45f);
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    /** La mira del centro: verde cuando apunta a un punto del piso que se puede marcar. */
    private static final class AimView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean ok;

        AimView(Context c) {
            super(c);
            paint.setStyle(Paint.Style.STROKE);
        }

        void set(boolean ok) {
            if (this.ok == ok) return;
            this.ok = ok;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, r = getWidth() * 0.32f;
            paint.setStrokeWidth(NavajaUi.dp(getContext(), 2));
            paint.setColor(ok ? Palette.OK : 0xCCFFFFFF);
            c.drawCircle(cx, cy, r, paint);
            c.drawLine(cx - r * 1.4f, cy, cx - r * 0.4f, cy, paint);
            c.drawLine(cx + r * 0.4f, cy, cx + r * 1.4f, cy, paint);
            c.drawLine(cx, cy - r * 1.4f, cx, cy - r * 0.4f, paint);
            c.drawLine(cx, cy + r * 0.4f, cx, cy + r * 1.4f, paint);
        }
    }

    // ============================================================== grabación

    private long now() {
        return SystemClock.elapsedRealtime() - startElapsed;
    }

    private void toggle() {
        if (recording) {
            finishSurvey();
            return;
        }
        if (session == null) {
            toast("La cámara todavía no está lista.");
            return;
        }
        if (!tracking) {
            toast("Espera a que diga «Siguiendo»: mueve el teléfono despacio.");
            return;
        }
        synchronized (lock) {
            log = new SurveyLog();
            startElapsed = SystemClock.elapsedRealtime();
            log.start(System.currentTimeMillis(), Build.MANUFACTURER + " " + Build.MODEL + " · Android "
                    + Build.VERSION.RELEASE);
            loggedPlanes.clear();
            newOrigin = true; // el origen se pone en el próximo cuadro, donde esté el teléfono
            northSin = northCos = 0;
            northN = 0;
            lastPose = lastPlanes = lastPressure = lastNorth = 0;
            recording = true;
        }
        loadSims();
        ui.post(cellPoll);
        buttons();
    }

    private void undoCorner() {
        boolean done;
        synchronized (lock) {
            done = log.undoCorner(now());
        }
        if (!done) toast("No hay esquinas que deshacer.");
    }

    private void closeRoom() {
        synchronized (lock) {
            Room r = log.openRoom();
            if (r == null || !r.closed()) {
                toast("Una habitación necesita al menos tres esquinas.");
                return;
            }
            log.closeRoom(now());
            toast(String.format(Locale.US, "%s: %.2f m²", r.name, r.area()));
        }
    }

    private void finishSurvey() {
        recording = false;
        ui.removeCallbacks(cellPoll);
        buttons();
        final SurveyLog done;
        final String report;
        File zip = null;
        String error = null;
        synchronized (lock) { // un cuadro en curso todavía puede estar escribiendo
            done = log;
            logNorth(done, now());
            report = SurveyExport.report(done);
            try {
                zip = save(done, report);
            } catch (Exception e) {
                Log.w(TAG, "guardar", e);
                error = e.toString();
            }
        }
        ScrollView sv = new ScrollView(this);
        TextView tv = NavajaUi.text(this, error == null ? report : "No se pudo guardar: " + error + "\n\n" + report,
                NavajaUi.Style.MONO);
        int p = NavajaUi.dp(this, 16);
        tv.setPadding(p, p, p, p);
        tv.setTextIsSelectable(true);
        sv.addView(tv);
        final File out = zip;
        AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle("Levantamiento guardado").setView(sv)
                .setNegativeButton("Cerrar", null);
        if (out != null) b.setPositiveButton("Compartir ZIP", (d, w) -> share(out));
        b.show();
    }

    private File save(SurveyLog done, String report) throws Exception {
        File dir = new File(getExternalFilesDir(null), "levantamientos");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File zip = new File(dir, "levantamiento_" + stamp + ".zip");
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(zip))) {
            entry(z, "informe.md", report);
            entry(z, "registro.txt", done.text());
            entry(z, "mediciones.csv", SurveyExport.csv(done));
            entry(z, "plano.dxf", SurveyExport.plan(done).toString());
            String geo = SurveyExport.geoJson(done);
            if (geo != null) entry(z, "mapa.geojson", geo);
        }
        return zip;
    }

    private static void entry(ZipOutputStream z, String name, String text) throws Exception {
        z.putNextEntry(new ZipEntry(name));
        z.write(text.getBytes(StandardCharsets.UTF_8));
        z.closeEntry();
    }

    private void share(File f) {
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
            Intent i = new Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "Compartir " + f.getName()));
        } catch (Exception e) {
            toast("Guardado en " + f.getAbsolutePath());
        }
    }

    // ================================================== lo que se ve cada medio segundo

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            render();
            ui.postDelayed(this, UI_MS);
        }
    };

    private void render() {
        aim.set(aimOk);
        StringBuilder sb = new StringBuilder(trackText);
        SurveyMapView.Scene scene = new SurveyMapView.Scene();
        scene.here = tracking;
        scene.x = curX;
        scene.y = curY;
        scene.yawDeg = curYaw;
        if (recording) {
            synchronized (lock) {
                List<SurveyLog.Point> pts = log.points();
                sb.append(String.format(Locale.US, "\n%.1f m · %d s · %d mediciones", log.track.length(),
                        log.durationMs() / 1000, pts.size()));
                double fz = log.floorZ(), cz = log.ceilingZ();
                sb.append('\n').append(Double.isNaN(fz) ? "Piso: sin detectar" : Double.isNaN(cz)
                        ? "Altura: apunta al techo" : String.format(Locale.US, "Altura %.2f m", cz - fz));
                Room open = log.openRoom();
                if (open != null) {
                    sb.append(String.format(Locale.US, "\n%s: %d esquinas", open.name, open.corners.size()));
                    if (open.closed()) sb.append(String.format(Locale.US, " · %.2f m²", open.area()));
                }
                scene.lines.addAll(log.track.lines(0.05));
                for (Room r : log.rooms) scene.rooms.add(new ArrayList<>(r.corners));
                for (SurveyLog.Point pt : pts) {
                    scene.dots.add(new double[]{pt.x, pt.y, SignalMetrics.rsrp(pt.reading.cell.rsrp).color});
                }
            }
        }
        if (CellReading.has(servingRsrp)) {
            sb.append("\nRSRP ").append(servingRsrp).append(" dBm").append(servingRat.isEmpty() ? "" : " · " + servingRat);
        }
        status.setText(sb);
        map.show(scene);
    }

    // =================================================================== señal

    @SuppressWarnings("MissingPermission")
    private void loadSims() {
        sims.clear();
        if (has(Manifest.permission.READ_PHONE_STATE)) {
            try {
                SubscriptionManager sm = (SubscriptionManager) getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
                List<SubscriptionInfo> l = sm == null ? null : sm.getActiveSubscriptionInfoList();
                if (l != null) {
                    for (SubscriptionInfo si : l) sims.add(baseTm.createForSubscriptionId(si.getSubscriptionId()));
                    synchronized (lock) {
                        log.note(now(), "SIM activas: " + l.size());
                    }
                }
            } catch (SecurityException e) {
                Log.w(TAG, "SIMs", e);
            }
        }
        if (sims.isEmpty()) sims.add(baseTm);
    }

    private final Runnable cellPoll = new Runnable() {
        @Override
        public void run() {
            if (!recording) return;
            for (int i = 0; i < sims.size(); i++) requestCells(i, sims.get(i));
            ui.postDelayed(this, CELLS_MS);
        }
    };

    @SuppressWarnings("MissingPermission")
    private void requestCells(final int sim, final TelephonyManager tm) {
        if (!has(Manifest.permission.ACCESS_FINE_LOCATION)) return;
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                tm.requestCellInfoUpdate(mainExec, new TelephonyManager.CellInfoCallback() {
                    @Override
                    public void onCellInfo(List<CellInfo> list) { onCells(sim, list); }

                    @Override
                    public void onError(int errorCode, Throwable detail) { onCells(sim, cached(tm)); }
                });
            } else {
                onCells(sim, cached(tm));
            }
        } catch (SecurityException e) {
            Log.w(TAG, "celdas", e);
        }
    }

    @SuppressWarnings("MissingPermission")
    private static List<CellInfo> cached(TelephonyManager tm) {
        try {
            return tm.getAllCellInfo();
        } catch (SecurityException e) {
            return null;
        }
    }

    private void onCells(int sim, List<CellInfo> list) {
        if (!recording) return;
        long elapsed = SystemClock.elapsedRealtime(), t = elapsed - startElapsed;
        List<CellReading> cells = CellReader.read(list);
        synchronized (lock) {
            for (CellReading c : cells) {
                long age = c.measuredAtMs == CellReading.NA_LONG ? -1 : Math.max(0, elapsed - c.measuredAtMs);
                log.cell(t, sim, age, c);
            }
        }
        if (sim == 0) {
            for (CellReading c : cells) {
                if (!c.registered) continue;
                servingRsrp = c.rsrp;
                servingRat = c.rat == null ? "" : c.rat.label;
                break;
            }
        }
    }

    // ================================================== presión, brújula y GPS

    @SuppressWarnings("MissingPermission")
    private void startSensors() {
        Sensor pressure = sensors.getDefaultSensor(Sensor.TYPE_PRESSURE);
        if (pressure != null) sensors.registerListener(this, pressure, SensorManager.SENSOR_DELAY_NORMAL);
        Sensor rv = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        if (rv != null) sensors.registerListener(this, rv, SensorManager.SENSOR_DELAY_UI);
        if (has(Manifest.permission.ACCESS_FINE_LOCATION)) {
            try {
                if (locations.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    locations.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, this);
                }
            } catch (Exception e) {
                Log.w(TAG, "GPS", e);
            }
        }
    }

    private void stopSensors() {
        sensors.unregisterListener(this);
        try {
            locations.removeUpdates(this);
        } catch (Exception ignored) {
            // sin permiso no había nada registrado
        }
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        if (!recording) return;
        long t = now();
        if (e.sensor.getType() == Sensor.TYPE_PRESSURE) {
            if (t - lastPressure < PRESSURE_MS) return;
            lastPressure = t;
            synchronized (lock) {
                log.pressure(t, e.values[0]);
            }
            return;
        }
        if (e.sensor.getType() != Sensor.TYPE_ROTATION_VECTOR || !tracking) return;
        // Rumbo de la cámara según la brújula: la cámara mira por −Z del teléfono. La matriz lleva del teléfono al
        // mundo (X este, Y norte, Z arriba).
        SensorManager.getRotationMatrixFromVector(rot, e.values);
        double east = -rot[2], north = -rot[5];
        if (Math.hypot(east, north) < 0.5 || camHoriz < 0.5) return; // mirando al piso o al techo no hay rumbo
        double bearing = Math.toDegrees(Math.atan2(east, north));
        // Una dirección con ángulo φ en el plano tiene rumbo = norte + 90 − φ; de ahí sale el rumbo del eje +y.
        double n = Math.toRadians(bearing - 90 + curYaw);
        northSin += Math.sin(n);
        northCos += Math.cos(n);
        northN++;
        if (t - lastNorth >= NORTH_MS) {
            lastNorth = t;
            synchronized (lock) {
                logNorth(log, t);
            }
        }
    }

    /** Rumbo promedio del eje +y, corregido al norte geográfico cuando se sabe dónde se está. */
    private void logNorth(SurveyLog l, long t) {
        if (northN == 0) return;
        double deg = Math.toDegrees(Math.atan2(northSin, northCos));
        if (!Double.isNaN(l.lat)) {
            deg += new GeomagneticField((float) l.lat, (float) l.lon, 0, System.currentTimeMillis()).getDeclination();
        }
        l.north(t, ((deg % 360) + 360) % 360);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    @Override
    public void onLocationChanged(Location loc) {
        if (!recording || !tracking || !loc.hasAccuracy() || loc.getAccuracy() > MAX_GPS_ACC_M) return;
        synchronized (lock) {
            log.geo(now(), loc.getLatitude(), loc.getLongitude(), loc.getAccuracy(), curX, curY);
        }
    }

    @Override
    public void onStatusChanged(String provider, int s, Bundle extras) { }

    @Override
    public void onProviderEnabled(String provider) { }

    @Override
    public void onProviderDisabled(String provider) { }

    // ====================================================== cámara y posición (hilo GL)

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        texId = t[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, shader(GLES20.GL_VERTEX_SHADER,
                "attribute vec4 aPos; attribute vec2 aTex; varying vec2 vTex;"
                        + "void main() { gl_Position = aPos; vTex = aTex; }"));
        GLES20.glAttachShader(program, shader(GLES20.GL_FRAGMENT_SHADER,
                "#extension GL_OES_EGL_image_external : require\n"
                        + "precision mediump float; varying vec2 vTex; uniform samplerExternalOES uTex;"
                        + "void main() { gl_FragColor = texture2D(uTex, vTex); }"));
        GLES20.glLinkProgram(program);
        aPos = GLES20.glGetAttribLocation(program, "aPos");
        aTex = GLES20.glGetAttribLocation(program, "aTex");
        geometryDirty = true;
    }

    private static int shader(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        return s;
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int w, int h) {
        GLES20.glViewport(0, 0, w, h);
        viewW = w;
        viewH = h;
        geometryDirty = true;
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        Session s = session;
        if (s == null || viewW == 0) return;
        try {
            if (geometryDirty) {
                //noinspection deprecation
                s.setDisplayGeometry(getWindowManager().getDefaultDisplay().getRotation(), viewW, viewH);
                geometryDirty = false;
            }
            s.setCameraTextureName(texId);
            Frame frame = s.update();
            drawCamera(frame);
            follow(s, frame);
        } catch (Throwable t) {
            // Un cuadro que falla no debe cerrar la pantalla: se avisa y se sigue con el próximo.
            Log.w(TAG, "cuadro", t);
            trackText = "Error de ARCore: " + t.getClass().getSimpleName();
            tracking = false;
        }
    }

    private void drawCamera(Frame frame) {
        if (frame.hasDisplayGeometryChanged() || quadTex.get(0) == 0 && quadTex.get(7) == 0) {
            quad.position(0);
            quadTex.position(0);
            frame.transformCoordinates2d(Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, quad,
                    Coordinates2d.TEXTURE_NORMALIZED, quadTex);
        }
        if (frame.getTimestamp() == 0) return; // todavía no llegó ninguna imagen
        quad.position(0);
        quadTex.position(0);
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId);
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad);
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, quadTex);
        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glEnableVertexAttribArray(aTex);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(aPos);
        GLES20.glDisableVertexAttribArray(aTex);
    }

    /** Lee la posición del cuadro, la guarda si se está grabando, revisa los planos y prueba la mira. */
    private void follow(Session s, Frame frame) {
        Camera cam = frame.getCamera();
        boolean ok = cam.getTrackingState() == TrackingState.TRACKING;
        String reason = ok ? "" : cam.getTrackingState() == TrackingState.STOPPED ? "STOPPED"
                : cam.getTrackingFailureReason().name();
        tracking = ok;
        trackText = ok ? "Siguiendo" : spanish(reason);
        long elapsed = SystemClock.elapsedRealtime(), t = elapsed - startElapsed;
        Pose world = cam.getPose();

        if (ok) {
            if (newOrigin) {
                // El origen del plano: donde está el teléfono al empezar, con los ejes del mundo (Y es la vertical).
                if (anchor != null) anchor.detach();
                base = Pose.makeTranslation(world.tx(), world.ty(), world.tz());
                anchor = s.createAnchor(base);
                newOrigin = false;
            }
            if (anchor != null && anchor.getTrackingState() == TrackingState.TRACKING) base = anchor.getPose();
            Pose rel = base.inverse().compose(world);
            double[] p = Track.plan(rel.tx(), rel.ty(), rel.tz());
            float[] z = rel.getZAxis(); // la cámara mira por −Z
            curX = p[0];
            curY = p[1];
            curZ = p[2];
            camHoriz = Math.hypot(z[0], z[2]);
            if (camHoriz > 0.2) curYaw = Math.toDegrees(Math.atan2(z[2], -z[0]));
        }

        if (recording && !newOrigin && elapsed - lastPose >= POSE_MS) {
            lastPose = elapsed;
            synchronized (lock) {
                log.pose(t, curX, curY, curZ, curYaw, ok, reason);
            }
        }
        if (ok && elapsed - lastPlanes >= PLANES_MS) {
            lastPlanes = elapsed;
            planes(s, t);
        }
        if (elapsed - lastAim >= AIM_MS || cornerAsked) {
            lastAim = elapsed;
            aimOk = ok && aim(frame, world);
            if (cornerAsked) {
                cornerAsked = false;
                if (!recording) return;
                if (!aimOk) {
                    ui.post(() -> toast("Apunta la mira al piso hasta que se ponga verde."));
                    return;
                }
                float[] q = base.inverse().transformPoint(aimPoint);
                double[] c = Track.plan(q[0], q[1], q[2]);
                synchronized (lock) {
                    log.corner(t, c[0], c[1], c[2]);
                }
            }
        }
    }

    /**
     * El punto del piso al que apunta la mira (en el mundo de ARCore), en {@link #aimPoint}. Primero lo que ARCore
     * encuentra sobre un plano de piso; si el plano detectado no llega hasta ahí (pasa en las esquinas), el cruce de
     * la línea de la mira con la altura del piso ya conocida.
     */
    private boolean aim(Frame frame, Pose world) {
        float best = Float.NaN;
        for (HitResult hit : frame.hitTest(viewW / 2f, viewH / 2f)) {
            Trackable tr = hit.getTrackable();
            if (!(tr instanceof Plane) || ((Plane) tr).getType() != Plane.Type.HORIZONTAL_UPWARD_FACING) continue;
            if (hit.getDistance() > MAX_AIM_M) continue;
            Pose hp = hit.getHitPose();
            // Entre varios planos hacia arriba (piso y una mesa), el más bajo es el piso.
            if (Float.isNaN(best) || hp.ty() < best) {
                best = hp.ty();
                aimPoint[0] = hp.tx();
                aimPoint[1] = hp.ty();
                aimPoint[2] = hp.tz();
            }
        }
        if (!Float.isNaN(best)) return true;
        if (Float.isNaN(floorWorldY)) return false;
        float[] z = world.getZAxis();
        float dy = -z[1];
        if (dy > -0.1f) return false; // la mira no baja lo suficiente
        float d = (floorWorldY - world.ty()) / dy;
        if (d <= 0 || d > MAX_AIM_M) return false;
        aimPoint[0] = world.tx() - z[0] * d;
        aimPoint[1] = floorWorldY;
        aimPoint[2] = world.tz() - z[2] * d;
        return true;
    }

    /** Guarda los planos que ARCore tiene detectados cuando cambian de tamaño o de altura. */
    private void planes(Session s, long t) {
        float floor = Float.NaN;
        for (Plane pl : s.getAllTrackables(Plane.class)) {
            if (pl.getTrackingState() != TrackingState.TRACKING || pl.getSubsumedBy() != null) continue;
            double area = area(pl.getPolygon());
            Pose center = pl.getCenterPose();
            if (pl.getType() == Plane.Type.HORIZONTAL_UPWARD_FACING && area >= SurveyLog.MIN_PLANE_M2
                    && (Float.isNaN(floor) || center.ty() < floor)) {
                floor = center.ty();
            }
            if (!recording) continue;
            Pose rel = base.inverse().compose(center);
            double[] p = Track.plan(rel.tx(), rel.ty(), rel.tz());
            String id = "p" + Integer.toHexString(pl.hashCode());
            double[] last = loggedPlanes.get(id);
            if (last != null && Math.abs(last[0] - area) < 0.1 * last[0] && Math.abs(last[1] - p[2]) < 0.02) continue;
            loggedPlanes.put(id, new double[]{area, p[2]});
            char type = pl.getType() == Plane.Type.HORIZONTAL_UPWARD_FACING ? SurveyLog.FLOOR
                    : pl.getType() == Plane.Type.HORIZONTAL_DOWNWARD_FACING ? SurveyLog.CEILING : SurveyLog.WALL;
            synchronized (lock) {
                log.plane(t, id, type, p[2], p[0], p[1], area);
            }
        }
        floorWorldY = floor;
    }

    /** Área del contorno de un plano (pares x, z en el sistema del propio plano). */
    private static double area(FloatBuffer polygon) {
        if (polygon == null) return 0;
        int n = polygon.limit() / 2;
        double sum = 0;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            sum += polygon.get(2 * i) * (double) polygon.get(2 * j + 1) - polygon.get(2 * j) * (double) polygon.get(2 * i + 1);
        }
        return Math.abs(sum) / 2;
    }

    private static String spanish(String reason) {
        switch (reason) {
            case "INSUFFICIENT_LIGHT": return "Poca luz";
            case "EXCESSIVE_MOTION": return "Movimiento muy rápido: más despacio";
            case "INSUFFICIENT_FEATURES": return "Pocas texturas: apunta a una zona con detalles";
            case "CAMERA_UNAVAILABLE": return "Otra app está usando la cámara";
            case "BAD_STATE": return "ARCore tuvo un error interno";
            case "STOPPED": return "Seguimiento detenido";
            default: return "Iniciando: mueve el teléfono despacio";
        }
    }

    private static FloatBuffer floats(float[] v) {
        FloatBuffer b = ByteBuffer.allocateDirect(v.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        b.put(v);
        b.position(0);
        return b;
    }
}
