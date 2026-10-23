package com.josexato.navaja.compass;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.GeomagneticField;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.josexato.navaja.compass.core.Angles;
import com.josexato.navaja.compass.core.Tilt;
import com.josexato.navaja.marker.core.Waypoint;
import com.josexato.navaja.ui.NavajaUi;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Brújula (norte verdadero con la declinación del modelo WMM que trae
 * Android, aviso de interferencia y de calibración, "ir a un punto" del
 * Marcador) y nivel de burbuja (plano o de canto, calibración en dos
 * posiciones, grados / % / mm por metro).
 */
public class CompassActivity extends Activity implements SensorEventListener {

    private static final int REQ_LOC = 1;

    private SensorManager sm;
    private Sensor rotation, accel, magnet, gravity;
    private LocationManager lm;
    private SharedPreferences prefs;

    // Estado de la brújula.
    private final float[] rot = new float[9], remapped = new float[9], orient = new float[3];
    private final float[] accelV = new float[3], magV = new float[3], gravV = new float[3];
    private boolean haveAccel, haveMag, haveGrav;
    private final Angles.CircularFilter headingFilter = new Angles.CircularFilter(0.15);
    private double magneticHeading = Double.NaN;
    private int compassAccuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH;
    private float fieldUt = Float.NaN;
    private boolean trueNorth = true;
    private Location location;
    private Waypoint target;
    private boolean hold;
    private double heldHeading = Double.NaN;

    // Estado del nivel.
    private final float[] g = new float[3];
    private boolean gInit;
    private double offX, offY;
    private final Tilt.Calibration cal = new Tilt.Calibration();
    private int unit; // 0 = grados, 1 = %, 2 = mm/m
    private boolean wasLevel;

    private boolean compassMode = true;
    private CompassView compassView;
    private LevelView levelView;
    private TextView info;
    private TextView tabCompass, tabLevel;
    private Button b1, b2, b3;
    private long lastUi;

    private final LocationListener locListener = loc -> location = loc;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sm = (SensorManager) getSystemService(SENSOR_SERVICE);
        lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        prefs = getSharedPreferences("brujula", MODE_PRIVATE);
        offX = prefs.getFloat("offX", 0);
        offY = prefs.getFloat("offY", 0);
        unit = prefs.getInt("unit", 0);
        trueNorth = prefs.getBoolean("trueNorth", true);
        rotation = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        magnet = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        gravity = sm.getDefaultSensor(Sensor.TYPE_GRAVITY);
        buildUi();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOC);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        int rate = SensorManager.SENSOR_DELAY_GAME;
        if (rotation != null) sm.registerListener(this, rotation, rate);
        if (accel != null) sm.registerListener(this, accel, rate);
        if (magnet != null) sm.registerListener(this, magnet, rate);
        if (gravity != null) sm.registerListener(this, gravity, rate);
        startLocation();
    }

    @Override
    protected void onPause() {
        sm.unregisterListener(this);
        try {
            lm.removeUpdates(locListener);
        } catch (Exception ignored) {
            // nada que detener
        }
        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        super.onRequestPermissionsResult(code, p, r);
        startLocation();
    }

    private void startLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
        try {
            for (String prov : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                Location last = lm.getLastKnownLocation(prov);
                if (last != null && (location == null || last.getTime() > location.getTime())) location = last;
                if (lm.isProviderEnabled(prov)) lm.requestLocationUpdates(prov, 2000, 0, locListener, Looper.getMainLooper());
            }
        } catch (SecurityException | IllegalArgumentException ignored) {
            // sin ubicación: se usa el norte magnético
        }
    }

    // ============================================================= sensores

    @Override
    public void onSensorChanged(SensorEvent e) {
        switch (e.sensor.getType()) {
            case Sensor.TYPE_ROTATION_VECTOR:
                SensorManager.getRotationMatrixFromVector(rot, e.values);
                updateHeading();
                break;
            case Sensor.TYPE_ACCELEROMETER:
                System.arraycopy(e.values, 0, accelV, 0, 3);
                haveAccel = true;
                if (!haveGrav) lowPassGravity(e.values);
                if (rotation == null && haveMag && SensorManager.getRotationMatrix(rot, null, accelV, magV)) updateHeading();
                break;
            case Sensor.TYPE_GRAVITY:
                System.arraycopy(e.values, 0, gravV, 0, 3);
                haveGrav = true;
                lowPassGravity(e.values);
                break;
            case Sensor.TYPE_MAGNETIC_FIELD:
                System.arraycopy(e.values, 0, magV, 0, 3);
                haveMag = true;
                fieldUt = (float) Math.sqrt(magV[0] * magV[0] + magV[1] * magV[1] + magV[2] * magV[2]);
                break;
            default:
                break;
        }
        long now = System.currentTimeMillis();
        if (now - lastUi > 50) {
            lastUi = now;
            render();
        }
    }

    @Override
    public void onAccuracyChanged(Sensor s, int accuracy) {
        if (s.getType() == Sensor.TYPE_MAGNETIC_FIELD || s.getType() == Sensor.TYPE_ROTATION_VECTOR) compassAccuracy = accuracy;
    }

    private void lowPassGravity(float[] v) {
        float a = haveGrav ? 0.5f : 0.12f; // el sensor de gravedad ya viene filtrado
        if (!gInit) {
            System.arraycopy(v, 0, g, 0, 3);
            gInit = true;
        }
        for (int i = 0; i < 3; i++) g[i] += a * (v[i] - g[i]);
    }

    private void updateHeading() {
        // Si el teléfono está más vertical que horizontal, el rumbo es hacia donde
        // apunta la cámara trasera (se reasignan los ejes).
        float[] m = rot;
        if (gInit && Math.abs(g[2]) < Math.abs(g[1])) {
            SensorManager.remapCoordinateSystem(rot, SensorManager.AXIS_X, SensorManager.AXIS_Z, remapped);
            m = remapped;
        }
        SensorManager.getOrientation(m, orient);
        magneticHeading = headingFilter.update(Angles.norm360(Math.toDegrees(orient[0])));
    }

    // ============================================================ brújula

    private GeomagneticField geo() {
        Location l = location;
        if (l == null) return null;
        return new GeomagneticField((float) l.getLatitude(), (float) l.getLongitude(),
                (float) (l.hasAltitude() ? l.getAltitude() : 0), System.currentTimeMillis());
    }

    private void renderCompass() {
        GeomagneticField gf = geo();
        boolean useTrue = trueNorth && gf != null;
        double decl = gf == null ? 0 : gf.getDeclination();
        double heading = Double.isNaN(magneticHeading) ? Double.NaN : Angles.norm360(magneticHeading + (useTrue ? decl : 0));
        if (hold) heading = heldHeading;

        double targetBearing = Double.NaN;
        String targetLine = null;
        if (target != null) {
            if (location == null) {
                targetLine = "🎯 " + target.name + ": esperando tu ubicación…";
            } else {
                double brgTrue = Angles.bearing(location.getLatitude(), location.getLongitude(), target.lat, target.lon);
                targetBearing = Angles.norm360(brgTrue - (useTrue ? 0 : decl));
                double dist = Angles.distance(location.getLatitude(), location.getLongitude(), target.lat, target.lon);
                targetLine = String.format(Locale.US, "🎯 %s: %s a %.0f° %s", target.name, Angles.formatDistance(dist),
                        targetBearing, Angles.cardinal(targetBearing));
                if (!Double.isNaN(heading)) {
                    double turn = Angles.diff(targetBearing, heading);
                    targetLine += Math.abs(turn) < 5 ? " · ¡de frente!"
                            : String.format(Locale.US, " · gira %.0f° a la %s", Math.abs(turn), turn > 0 ? "derecha" : "izquierda");
                }
            }
        }
        String sub = (hold ? "FIJADO · " : "") + (useTrue ? "norte verdadero" : "norte magnético")
                + (Double.isNaN(heading) ? "" : String.format(Locale.US, " · contrarrumbo %.0f°", Angles.norm360(heading + 180)));
        compassView.set(heading, targetBearing, sub);

        StringBuilder sb = new StringBuilder();
        if (targetLine != null) sb.append(targetLine).append('\n');
        if (gf != null) sb.append(String.format(Locale.US, "Declinación magnética aquí: %.1f° %s", Math.abs(decl), decl >= 0 ? "E" : "O"));
        else sb.append("Sin ubicación: se muestra el norte magnético (falta la declinación).");
        if (!Float.isNaN(fieldUt)) {
            float expected = gf == null ? Float.NaN : gf.getFieldStrength() / 1000f;
            sb.append(String.format(Locale.US, "\nCampo magnético: %.0f µT", fieldUt));
            if (!Float.isNaN(expected)) sb.append(String.format(Locale.US, " (esperado %.0f)", expected));
            boolean odd = !Float.isNaN(expected) ? Math.abs(fieldUt - expected) > 0.25f * expected : fieldUt < 20 || fieldUt > 70;
            if (odd) sb.append("\n⚠ Interferencia: aléjate de metales, imanes, autos o cables.");
        }
        if (compassAccuracy <= SensorManager.SENSOR_STATUS_ACCURACY_LOW)
            sb.append("\n⚠ Brújula poco precisa: mueve el teléfono dibujando un 8 en el aire.");
        if (rotation == null && magnet == null) sb.append("\nEste teléfono no tiene magnetómetro.");
        info.setText(sb.toString());
    }

    private void pickTarget() {
        File f = new File(getExternalFilesDir(null), "marcador/waypoints.json");
        List<Waypoint> pts = new ArrayList<>();
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            pts.addAll(Waypoint.fromJson(new String(out.toByteArray(), StandardCharsets.UTF_8)));
        } catch (Exception ignored) {
            // sin puntos
        }
        if (pts.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("Ir a un punto")
                    .setMessage("No hay puntos guardados. Guárdalos con el Marcador (📍) y aparecerán aquí.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        List<String> names = new ArrayList<>();
        for (Waypoint w : pts) {
            String extra = location == null ? "" : " · " + Angles.formatDistance(
                    Angles.distance(location.getLatitude(), location.getLongitude(), w.lat, w.lon));
            names.add(w.name + extra);
        }
        if (target != null) names.add("✕ Quitar destino");
        new AlertDialog.Builder(this).setTitle("Ir a un punto del Marcador")
                .setItems(names.toArray(new String[0]), (d, i) -> target = i < pts.size() ? pts.get(i) : null)
                .show();
    }

    // =============================================================== nivel

    private Tilt.Reading rawLevel() { return Tilt.read(g[0], g[1], g[2], 0, 0); }

    private void renderLevel() {
        if (!gInit) {
            info.setText("Sin acelerómetro.");
            return;
        }
        Tilt.Reading r = Tilt.read(g[0], g[1], g[2], offX, offY);
        String big, small;
        if (r.mode == Tilt.Mode.FLAT) {
            big = fmt(r.total());
            small = "↔ " + fmt(r.x) + "   ↕ " + fmt(r.y);
        } else {
            big = fmt(Math.abs(r.edge));
            small = r.edgeBase == 0 || r.edgeBase == 180 ? "de canto · vertical" : "de canto · apaisado";
        }
        if (r.moving) small += " · quieto…";
        levelView.set(r, big, small);
        boolean level = r.total() < 0.1 && !r.moving;
        if (level && !wasLevel) levelView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        wasLevel = level;

        StringBuilder sb = new StringBuilder();
        sb.append(r.mode == Tilt.Mode.FLAT ? "Apoyado plano: la burbuja va hacia el lado más alto."
                : "De canto: apoya un borde del teléfono sobre la superficie.");
        if (cal.step() == 1) sb.append("\n➊ Listo. Ahora gira el teléfono 180° en el mismo lugar y pulsa «Calibrar» otra vez.");
        else if (offX != 0 || offY != 0)
            sb.append(String.format(Locale.US, "\nCalibrado (corrección %.2f° / %.2f°).", offX, offY));
        else sb.append("\nSin calibrar: usa «Calibrar» para más precisión (no hace falta una mesa nivelada).");
        info.setText(sb.toString());
    }

    private String fmt(double deg) {
        switch (unit) {
            case 1: return String.format(Locale.US, "%.1f %%", Tilt.percent(deg));
            case 2: return String.format(Locale.US, "%.0f mm/m", Tilt.mmPerMeter(deg));
            default: return String.format(Locale.US, "%.1f°", deg);
        }
    }

    private void calibrate() {
        Tilt.Reading raw = rawLevel();
        if (raw.mode != Tilt.Mode.FLAT || raw.moving) {
            toast("Apoya el teléfono plano y quieto sobre la superficie.");
            return;
        }
        if (cal.step() == 0) {
            cal.first(raw.x, raw.y);
        } else {
            double[] off = cal.second(raw.x, raw.y);
            offX = off[0];
            offY = off[1];
            prefs.edit().putFloat("offX", (float) offX).putFloat("offY", (float) offY).apply();
            toast("Nivel calibrado.");
        }
        render();
    }

    // ================================================================== UI

    private void buildUi() {
        NavajaUi.Screen sc = NavajaUi.screen(this, "Brújula y nivel", "WMM · sensores del teléfono", null);
        LinearLayout root = sc.content;

        LinearLayout tabs = new LinearLayout(this);
        TextView[] t = NavajaUi.tabs(tabs, new String[]{"🧭 Brújula", "📐 Nivel"},
                prefs.getBoolean("compassMode", true) ? 0 : 1, i -> setMode(i == 0));
        tabCompass = t[0];
        tabLevel = t[1];
        root.addView(tabs);

        FrameLayout stage = new FrameLayout(this);
        compassView = new CompassView(this);
        levelView = new LevelView(this);
        stage.addView(compassView, new FrameLayout.LayoutParams(-1, -1));
        stage.addView(levelView, new FrameLayout.LayoutParams(-1, -1));
        root.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1f));

        info = NavajaUi.text(this, "", NavajaUi.Style.SECONDARY);
        info.setTextSize(13);
        info.setPadding(dp(4), dp(6), dp(4), dp(2));
        root.addView(info);

        b1 = NavajaUi.secondary(this, "", v -> onAction(1));
        b2 = NavajaUi.secondary(this, "", v -> onAction(2));
        b3 = NavajaUi.secondary(this, "", v -> onAction(3));
        root.addView(NavajaUi.buttonRow(this, b1, b2, b3));
        setContentView(sc.root);
        setMode(prefs.getBoolean("compassMode", true));
    }

    private void setMode(boolean compass) {
        compassMode = compass;
        prefs.edit().putBoolean("compassMode", compass).apply();
        compassView.setVisibility(compass ? View.VISIBLE : View.GONE);
        levelView.setVisibility(compass ? View.GONE : View.VISIBLE);
        NavajaUi.setChip(tabCompass, compass);
        NavajaUi.setChip(tabLevel, !compass);
        updateButtons();
        render();
    }

    private void updateButtons() {
        if (compassMode) {
            b1.setText(trueNorth ? "Norte: verdadero" : "Norte: magnético");
            b2.setText("🎯 Ir a punto");
            b3.setText(hold ? "▶ Soltar" : "⏸ Fijar rumbo");
        } else {
            b1.setText(cal.step() == 1 ? "Calibrar ➋" : "Calibrar");
            b2.setText("Reiniciar");
            b3.setText(unit == 0 ? "Unidad: °" : unit == 1 ? "Unidad: %" : "Unidad: mm/m");
        }
    }

    private void onAction(int which) {
        if (compassMode) {
            if (which == 1) {
                trueNorth = !trueNorth;
                prefs.edit().putBoolean("trueNorth", trueNorth).apply();
            } else if (which == 2) {
                pickTarget();
            } else {
                hold = !hold;
                if (hold) {
                    GeomagneticField gf = geo();
                    heldHeading = Angles.norm360(magneticHeading + (trueNorth && gf != null ? gf.getDeclination() : 0));
                }
            }
        } else {
            if (which == 1) {
                calibrate();
            } else if (which == 2) {
                offX = offY = 0;
                prefs.edit().remove("offX").remove("offY").apply();
                toast("Calibración borrada.");
            } else {
                unit = (unit + 1) % 3;
                prefs.edit().putInt("unit", unit).apply();
            }
        }
        updateButtons();
        render();
    }

    private void render() {
        if (info == null) return;
        if (compassMode) renderCompass();
        else renderLevel();
    }

    // ============================================================ utilidades

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }


}
