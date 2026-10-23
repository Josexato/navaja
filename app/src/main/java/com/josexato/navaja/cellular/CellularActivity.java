package com.josexato.navaja.cellular;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.telephony.CellInfo;
import android.telephony.PhoneStateListener;
import android.telephony.SignalStrength;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyDisplayInfo;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.josexato.navaja.cellular.core.CellIds;
import com.josexato.navaja.cellular.core.CellReading;
import com.josexato.navaja.cellular.core.DriveLog;
import com.josexato.navaja.cellular.core.SignalMetrics;
import com.josexato.navaja.cellular.core.TowerLocator;
import com.josexato.navaja.ui.NavajaUi;
import com.josexato.navaja.ui.Palette;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Red móvil · modo ingeniería: celda servidora, señal (RSRP/RSRQ/SINR/CQI),
 * banda y canal, distancia por TA, celdas vecinas, 5G NSA, Dual SIM; registro
 * de recorrido con GPS (CSV/KML) y estimación local de la posición de las
 * torres. Sólo APIs públicas de Android; nada sale del teléfono.
 */
public class CellularActivity extends Activity {

    private static final String TAG = "RedMovil";
    private static final int REQ_PERMS = 1;
    private static final long REFRESH_MS = 2000;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Executor mainExec = r -> ui.post(r);
    private TelephonyManager baseTm, tm;
    private SubscriptionManager subs;
    private LocationManager lm;
    private SharedPreferences prefs;

    private final List<SubscriptionInfo> sims = new ArrayList<>();
    private int simIndex;
    private List<CellReading> cells = new ArrayList<>();
    private String displayType; // "5G NSA", "LTE‑A"… (TelephonyDisplayInfo)
    private Object displayListener;
    private boolean running;

    private final DriveLog log = new DriveLog();
    private boolean recording;
    private Location lastFix;
    private final LocationListener locListener = loc -> lastFix = loc;

    private TextView headView, statusView;
    private NavajaUi.Screen screen;
    private LinearLayout simRow, content;
    private Button recBtn, gnbBtn;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        baseTm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        tm = baseTm;
        subs = (SubscriptionManager) getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
        lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        prefs = getSharedPreferences("redmovil", MODE_PRIVATE);
        log.gnbBits = prefs.getInt("gnbBits", CellIds.DEFAULT_GNB_BITS);
        buildUi();
        askPermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (hasLocationPerm()) start();
    }

    @Override
    protected void onPause() {
        // El registro sigue sólo con la pantalla abierta (sin servicio en segundo plano).
        if (!recording) stop();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        stop();
        stopGps();
        super.onDestroy();
    }

    // ============================================================ permisos

    private boolean hasLocationPerm() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasPhonePerm() {
        return checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED;
    }

    private void askPermissions() {
        List<String> need = new ArrayList<>();
        if (!hasLocationPerm()) {
            need.add(Manifest.permission.ACCESS_FINE_LOCATION);
            need.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (!hasPhonePerm()) need.add(Manifest.permission.READ_PHONE_STATE);
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), REQ_PERMS);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (hasLocationPerm()) start();
        else render();
    }

    // ============================================================== lectura

    private void start() {
        if (running) return;
        running = true;
        loadSims();
        listenDisplayInfo();
        ui.post(poll);
    }

    private void stop() {
        running = false;
        ui.removeCallbacks(poll);
        unlistenDisplayInfo();
    }

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            requestCells();
            ui.postDelayed(this, REFRESH_MS);
        }
    };

    @SuppressWarnings("MissingPermission") // se verifica hasLocationPerm() antes
    private void requestCells() {
        if (!hasLocationPerm() || tm == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                tm.requestCellInfoUpdate(mainExec, new TelephonyManager.CellInfoCallback() {
                    @Override
                    public void onCellInfo(List<CellInfo> list) { onCells(list); }

                    @Override
                    public void onError(int errorCode, Throwable detail) {
                        // Límite de frecuencia u otro error: se usa lo último que tenga el sistema.
                        onCells(safeAllCellInfo());
                    }
                });
            } else {
                onCells(safeAllCellInfo());
            }
        } catch (SecurityException e) {
            setStatus("Sin permiso para leer las celdas.");
        }
    }

    @SuppressWarnings("MissingPermission")
    private List<CellInfo> safeAllCellInfo() {
        try {
            return tm.getAllCellInfo();
        } catch (SecurityException e) {
            return null;
        }
    }

    private void onCells(List<CellInfo> list) {
        List<CellReading> read = CellReader.read(list);
        SignalStrength ss = Build.VERSION.SDK_INT >= 28 ? tm.getSignalStrength() : null;
        CellReading nsa = CellReader.nsaFromSignal(ss);
        boolean nrRegistered = false;
        for (CellReading c : read) if (c.registered && c.rat == CellReading.Rat.NR) nrRegistered = true;
        if (nsa != null && !nrRegistered) {
            // Portadora 5G NSA: va después de la servidora LTE.
            int pos = 0;
            while (pos < read.size() && read.get(pos).registered) pos++;
            read.add(pos, nsa);
        }
        cells = read;
        if (recording) recordPoint();
        render();
    }

    // ------------------------------------------------------------- SIMs

    @SuppressWarnings("MissingPermission")
    private void loadSims() {
        sims.clear();
        if (subs != null && hasPhonePerm()) {
            try {
                List<SubscriptionInfo> l = subs.getActiveSubscriptionInfoList();
                if (l != null) sims.addAll(l);
            } catch (SecurityException e) {
                Log.w(TAG, "SIMs", e);
            }
        }
        if (simIndex >= sims.size()) simIndex = 0;
        selectSim(simIndex);
    }

    private void selectSim(int i) {
        simIndex = i;
        tm = sims.isEmpty() ? baseTm : baseTm.createForSubscriptionId(sims.get(i).getSubscriptionId());
        unlistenDisplayInfo();
        if (running) listenDisplayInfo();
        renderSims();
    }

    // ------------------------------------------- tipo de red mostrado (5G NSA)

    @SuppressWarnings({"deprecation", "MissingPermission"})
    private void listenDisplayInfo() {
        displayType = null;
        if (Build.VERSION.SDK_INT < 30 || !hasPhonePerm()) return;
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                DisplayCb cb = new DisplayCb();
                tm.registerTelephonyCallback(mainExec, cb);
                displayListener = cb;
            } else {
                PhoneStateListener l = new PhoneStateListener() {
                    @Override
                    public void onDisplayInfoChanged(TelephonyDisplayInfo info) {
                        if (Build.VERSION.SDK_INT >= 30) onDisplayInfo(info);
                    }
                };
                tm.listen(l, PhoneStateListener.LISTEN_DISPLAY_INFO_CHANGED);
                displayListener = l;
            }
        } catch (SecurityException e) {
            Log.w(TAG, "display info", e);
        }
    }

    @SuppressWarnings("deprecation")
    private void unlistenDisplayInfo() {
        if (displayListener == null || tm == null) return;
        if (Build.VERSION.SDK_INT >= 31 && displayListener instanceof TelephonyCallback) {
            tm.unregisterTelephonyCallback((TelephonyCallback) displayListener);
        } else if (displayListener instanceof PhoneStateListener) {
            tm.listen((PhoneStateListener) displayListener, PhoneStateListener.LISTEN_NONE);
        }
        displayListener = null;
    }

    @androidx.annotation.RequiresApi(31)
    private final class DisplayCb extends TelephonyCallback implements TelephonyCallback.DisplayInfoListener {
        @Override
        public void onDisplayInfoChanged(TelephonyDisplayInfo info) { onDisplayInfo(info); }
    }

    @androidx.annotation.RequiresApi(30)
    @SuppressWarnings("InlinedApi") // NR_ADVANCED es de API 31, pero la constante se copia al compilar
    private void onDisplayInfo(TelephonyDisplayInfo info) {
        switch (info.getOverrideNetworkType()) {
            case TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA: displayType = "LTE‑CA"; break;
            case TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_ADVANCED_PRO: displayType = "LTE‑A Pro"; break;
            case TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA: displayType = "5G NSA"; break;
            case TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED: displayType = "5G+ (NR avanzado)"; break;
            default: displayType = null;
        }
        render();
    }

    // ============================================================ registro

    private void toggleRecording() {
        if (recording) {
            recording = false;
            stopGps();
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            recBtn.setText("● Grabar recorrido");
            setStatus("Registro detenido: " + log.points.size() + " puntos.");
            return;
        }
        if (!hasLocationPerm()) {
            askPermissions();
            return;
        }
        if (!locationEnabled()) {
            askLocationOn();
            return;
        }
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, locListener, Looper.getMainLooper());
        } catch (SecurityException | IllegalArgumentException e) {
            toast("No se pudo usar el GPS: " + e.getMessage());
            return;
        }
        recording = true;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        recBtn.setText("■ Detener");
        setStatus("Grabando… Para medir distancias (TA) usa datos móviles (p. ej. un video): el TA sólo existe con conexión activa.");
    }

    private void stopGps() {
        try {
            lm.removeUpdates(locListener);
        } catch (Exception ignored) {
            // nada que detener
        }
    }

    private void recordPoint() {
        CellReading serving = null;
        for (CellReading c : cells) if (c.registered && c.cellId != CellReading.NA_LONG) { serving = c; break; }
        Location fix = lastFix;
        if (serving == null || fix == null || System.currentTimeMillis() - fix.getTime() > 5000) return;
        log.add(new DriveLog.Point(System.currentTimeMillis(), fix.getLatitude(), fix.getLongitude(),
                fix.hasAccuracy() ? fix.getAccuracy() : 999, serving));
    }

    private boolean locationEnabled() {
        if (lm == null) return false;
        if (Build.VERSION.SDK_INT >= 28) return lm.isLocationEnabled();
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
    }

    private void askLocationOn() {
        new AlertDialog.Builder(this)
                .setTitle("Ubicación desactivada")
                .setMessage("Android exige la ubicación del sistema encendida para leer las celdas y para el GPS. "
                        + "Navaja no envía tu ubicación a ningún lado.")
                .setPositiveButton("Abrir ajustes", (d, w) -> startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)))
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void exportMenu() {
        if (log.points.isEmpty()) {
            toast("No hay puntos grabados todavía.");
            return;
        }
        String[] items = {"CSV (planilla)", "KML (Google Earth / QGIS)"};
        new AlertDialog.Builder(this)
                .setTitle("Exportar recorrido (" + log.points.size() + " puntos)")
                .setItems(items, (d, w) -> {
                    String stamp = new SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(new Date());
                    if (w == 0) share(write("recorrido_" + stamp + ".csv", log.csv()), "text/csv");
                    else share(write("recorrido_" + stamp + ".kml", log.kml("Navaja · recorrido " + stamp)),
                            "application/vnd.google-earth.kml+xml");
                })
                .show();
    }

    private void showTowers() {
        Map<String, TowerLocator.Estimate> towers = log.towers();
        Map<String, Integer> counts = log.taCounts();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(12), dp(20), dp(8));
        if (counts.isEmpty()) {
            box.addView(text("Aún no hay mediciones con Timing Advance.\n\n1. Pulsa «Grabar recorrido».\n"
                    + "2. Usa datos móviles mientras te mueves (el TA sólo existe con conexión activa).\n"
                    + "3. Muévete alrededor de la zona, no sólo en línea recta: con " + TowerLocator.MIN_OBS
                    + "+ mediciones desde lugares distintos se estima dónde está cada torre.", 13, Palette.TEXT));
        }
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            TowerLocator.Estimate t = towers.get(e.getKey());
            TextView name = text(e.getKey(), 14, Palette.TEXT);
            name.setTypeface(Typeface.DEFAULT_BOLD);
            name.setPadding(0, dp(10), 0, 0);
            box.addView(name);
            if (t == null) {
                box.addView(text(e.getValue() + " mediciones con TA · faltan datos o movimiento para estimar", 12, Palette.TEXT_2));
                continue;
            }
            box.addView(text(String.format(Locale.US, "%.6f, %.6f · ±%.0f m · %d mediciones%s",
                    t.lat, t.lon, t.uncertaintyM, t.used,
                    t.ambiguous ? "\n⚠ Recorrido casi en línea recta: puede estar del otro lado de la calle (solución espejo)." : ""),
                    12, Palette.TEXT_2));
            Button map = button("Ver en mapa", v -> openMap(t.lat, t.lon, e.getKey()));
            box.addView(map);
        }
        new AlertDialog.Builder(this)
                .setTitle("Torres estimadas")
                .setView(wrapScroll(box))
                .setPositiveButton("Cerrar", null)
                .show();
    }

    private void openMap(double lat, double lon, String label) {
        Uri uri = Uri.parse(String.format(Locale.US, "geo:%.6f,%.6f?q=%.6f,%.6f(%s)", lat, lon, lat, lon, Uri.encode(label)));
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Exception e) {
            toast("No hay una app de mapas instalada.");
        }
    }

    private File write(String name, String text) {
        File dir = new File(getExternalFilesDir(null), "redmovil");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File f = new File(dir, name);
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            return f;
        } catch (IOException e) {
            toast("No se pudo escribir: " + e.getMessage());
            return null;
        }
    }

    private void share(File f, String mime) {
        if (f == null) return;
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
            Intent i = new Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "Compartir " + f.getName()));
        } catch (Exception e) {
            toast("Guardado en " + f.getAbsolutePath());
        }
    }

    private void cycleGnbBits() {
        log.gnbBits = log.gnbBits >= 32 ? 22 : log.gnbBits + 1;
        prefs.edit().putInt("gnbBits", log.gnbBits).apply();
        gnbBtn.setText("gNB: " + log.gnbBits + " bits");
        render();
    }

    // ================================================================= UI

    private void buildUi() {
        NavajaUi.Screen sc = NavajaUi.screen(this, "Red móvil", "modo ingeniería · LTE / 5G NR",
                NavajaUi.headerAction(this, "⤴", v -> exportMenu()));
        screen = sc;
        LinearLayout root = sc.content;

        simRow = new LinearLayout(this);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.addView(simRow);
        root.addView(hs);

        headView = text("", 13, Palette.TEXT_2);
        headView.setTypeface(Typeface.MONOSPACE);
        headView.setPadding(0, dp(6), 0, dp(6));
        root.addView(headView);

        recBtn = NavajaUi.primary(this, "● Grabar recorrido", v -> toggleRecording());
        root.addView(NavajaUi.buttonRow(this, recBtn, NavajaUi.secondary(this, "🗼 Torres", v -> showTowers())));

        statusView = text("", 12, Palette.TEXT_3);
        statusView.setPadding(0, dp(6), 0, dp(4));
        root.addView(statusView);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(this);
        sv.addView(content);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));

        gnbBtn = button("gNB: " + log.gnbBits + " bits", v -> cycleGnbBits());
        LinearLayout foot = new LinearLayout(this);
        foot.setGravity(Gravity.CENTER_VERTICAL);
        TextView note = text("Sólo APIs públicas: CA, canales físicos y menús del fabricante (*#0011#) no están disponibles para apps.",
                11, Palette.TEXT_3);
        foot.addView(note, new LinearLayout.LayoutParams(0, -2, 1f));
        foot.addView(gnbBtn);
        root.addView(foot);
        setContentView(sc.root);
        render();
    }

    private void renderSims() {
        simRow.removeAllViews();
        if (sims.size() < 2) return;
        for (int i = 0; i < sims.size(); i++) {
            SubscriptionInfo s = sims.get(i);
            final int idx = i;
            CharSequence name = s.getCarrierName();
            Button b = button("SIM " + (s.getSimSlotIndex() + 1) + (name == null ? "" : " · " + name), v -> selectSim(idx));
            b.setAlpha(i == simIndex ? 1f : 0.55f);
            simRow.addView(b);
        }
    }

    private void render() {
        if (content == null) return;
        content.removeAllViews();
        if (!hasLocationPerm()) {
            headView.setText("");
            content.addView(text("\nAndroid exige el permiso de ubicación para leer las celdas de radio. Navaja no "
                    + "guarda ni envía tu ubicación (salvo que grabes un recorrido, que queda en el teléfono).", 13, Palette.TEXT_2));
            content.addView(button("Dar permiso", v -> askPermissions()));
            return;
        }
        if (!locationEnabled()) {
            content.addView(text("\nLa ubicación del sistema está apagada: sin ella Android no entrega las celdas.", 13, Palette.WARN));
            content.addView(button("Activar ubicación", v -> askLocationOn()));
        }

        CellReading serving = null;
        for (CellReading c : cells) if (c.registered && c.cellId != CellReading.NA_LONG) { serving = c; break; }
        renderHead(serving);

        if (cells.isEmpty()) {
            content.addView(text("\nEsperando datos del módem…", 13, Palette.TEXT_3));
            return;
        }
        List<CellReading> neighbors = new ArrayList<>();
        for (CellReading c : cells) {
            if (c.registered) content.addView(servingCard(c));
            else neighbors.add(c);
        }
        if (!neighbors.isEmpty()) {
            TextView h = text("Celdas vecinas (" + neighbors.size() + ")", 15, Palette.TEXT);
            h.setTypeface(Typeface.DEFAULT_BOLD);
            h.setPadding(0, dp(14), 0, dp(4));
            content.addView(h);
            for (CellReading n : neighbors) content.addView(neighborRow(n));
        }
        if (recording) {
            StringBuilder sb = new StringBuilder(String.format(Locale.US, "Grabando · %d puntos", log.points.size()));
            for (Map.Entry<String, Integer> e : log.taCounts().entrySet())
                sb.append("\n").append(e.getKey()).append(": ").append(e.getValue()).append(" con TA");
            setStatus(sb.toString());
        }
    }

    private void renderHead(CellReading serving) {
        StringBuilder sb = new StringBuilder();
        String op = serving != null && serving.operator != null ? serving.operator : tm.getNetworkOperatorName();
        sb.append(op == null || op.isEmpty() ? "Operador desconocido" : op);
        String plmn = serving != null && serving.plmn() != null ? serving.plmn() : formatPlmn(tm.getNetworkOperator());
        if (plmn != null) sb.append("  ·  ").append(plmn);
        if (tm.isNetworkRoaming()) sb.append("  ·  ROAMING");
        sb.append('\n');
        String rat = serving == null ? "sin servicio" : serving.rat.label;
        sb.append("Red: ").append(rat);
        if (displayType != null) sb.append("  ·  Indicador: ").append(displayType);
        headView.setText(sb.toString());
    }

    private static String formatPlmn(String mccMnc) {
        if (mccMnc == null || mccMnc.length() < 5) return null;
        return mccMnc.substring(0, 3) + "-" + mccMnc.substring(3);
    }

    private View servingCard(CellReading c) {
        LinearLayout card = card();
        boolean nsa = c.rat == CellReading.Rat.NR && c.cellId == CellReading.NA_LONG;
        TextView h = text((nsa ? "Portadora 5G NSA" : "Celda servidora · " + c.rat.label), 15, Palette.TEXT);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(h);

        StringBuilder id = new StringBuilder();
        if (c.rat == CellReading.Rat.LTE && c.cellId != CellReading.NA_LONG) {
            id.append(String.format(Locale.US, "eNB %d  ·  Sector %d  ·  PCI %s\nECI %d  ·  TAC %s",
                    CellIds.enb(c.cellId), CellIds.lteSector(c.cellId), n(c.pci), c.cellId, n(c.area)));
        } else if (c.rat == CellReading.Rat.NR && c.cellId != CellReading.NA_LONG) {
            id.append(String.format(Locale.US, "gNB %d  ·  Celda %d (con %d bits)  ·  PCI %s\nNCI %d  ·  TAC %s",
                    CellIds.gnb(c.cellId, log.gnbBits), CellIds.nrSector(c.cellId, log.gnbBits), log.gnbBits,
                    n(c.pci), c.cellId, n(c.area)));
        } else if (nsa) {
            id.append("Android no expone la identidad de la celda NR en NSA; sólo su señal.");
        } else {
            id.append(String.format(Locale.US, "CID %s  ·  LAC %s  ·  %s %s",
                    c.cellId == CellReading.NA_LONG ? "—" : Long.toString(c.cellId), n(c.area),
                    c.rat == CellReading.Rat.WCDMA ? "PSC" : "BSIC", n(c.pci)));
        }
        String band = c.bandLabel();
        if (band != null || CellReading.has(c.channel)) {
            id.append("\nBanda: ").append(band == null ? "—" : band);
            if (CellReading.has(c.channel)) id.append("  ·  ").append(channelName(c)).append(' ').append(c.channel);
        }
        if (CellReading.has(c.bandwidthKhz)) id.append(String.format(Locale.US, "  ·  %.1f MHz de ancho", c.bandwidthKhz / 1000.0));
        if (!Double.isNaN(c.taMeters)) {
            id.append(String.format(Locale.US, "\nDistancia a la antena: ~%.0f m (TA %d)", c.taMeters, c.timingAdvance));
        } else if (c.rat == CellReading.Rat.LTE) {
            id.append("\nDistancia: sin TA (sólo con conexión de datos activa)");
        }
        TextView idv = text(id.toString(), 13, Palette.TEXT_2);
        idv.setTypeface(Typeface.MONOSPACE);
        idv.setPadding(0, dp(4), 0, dp(8));
        card.addView(idv);

        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(2);
        if (c.rat == CellReading.Rat.LTE || c.rat == CellReading.Rat.NR) {
            grid.addView(metric(c.rat == CellReading.Rat.NR ? "SS‑RSRP" : "RSRP", c.rsrp, "dBm", SignalMetrics.rsrp(c.rsrp), null));
            grid.addView(metric(c.rat == CellReading.Rat.NR ? "SS‑RSRQ" : "RSRQ", c.rsrq, "dB", SignalMetrics.rsrq(c.rsrq), null));
            grid.addView(metric("SINR", c.sinr, "dB", SignalMetrics.sinr(c.sinr), null));
            if (c.rat == CellReading.Rat.LTE)
                grid.addView(metric("CQI", c.cqi, "", SignalMetrics.cqi(c.cqi), SignalMetrics.cqiModulation(c.cqi)));
        } else {
            grid.addView(metric("Señal", c.rssi, "dBm", SignalMetrics.Level.UNKNOWN, null));
        }
        card.addView(grid);
        return card;
    }

    private static String channelName(CellReading c) {
        switch (c.rat) {
            case LTE: return "EARFCN";
            case NR: return "NR‑ARFCN";
            case WCDMA: return "UARFCN";
            default: return "ARFCN";
        }
    }

    private View metric(String name, int value, String unit, SignalMetrics.Level lvl, String extra) {
        NavajaUi.Metric m = NavajaUi.metric(this, name);
        String v = CellReading.has(value) ? value + (unit.isEmpty() ? "" : " " + unit) : "—";
        // El color es el dato (semáforo), no el acento de la marca.
        m.set(v, lvl.label + (extra == null ? "" : " · " + extra), lvl == SignalMetrics.Level.UNKNOWN ? 0 : lvl.color);
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED, 1f),
                GridLayout.spec(GridLayout.UNDEFINED, 1f));
        lp.width = 0;
        lp.setMargins(dp(3), dp(3), dp(3), dp(3));
        m.view.setLayoutParams(lp);
        return m.view;
    }

    private View neighborRow(CellReading c) {
        LinearLayout r = new LinearLayout(this);
        r.setPadding(dp(10), dp(6), dp(10), dp(6));
        r.setGravity(Gravity.CENTER_VERTICAL);
        View dot = new View(this);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        int signal = CellReading.has(c.rsrp) ? c.rsrp : c.rssi;
        g.setColor(CellReading.has(c.rsrp) ? SignalMetrics.rsrp(c.rsrp).color : SignalMetrics.Level.UNKNOWN.color);
        dot.setBackground(g);
        r.addView(dot, new LinearLayout.LayoutParams(dp(10), dp(10)));
        String band = c.bandLabel();
        String s = String.format(Locale.US, "%s  PCI %s  ·  %s %s%s  ·  %s dBm",
                c.rat == null ? "" : c.rat.name(), n(c.pci), channelName(c), n(c.channel),
                band == null ? "" : "  ·  " + band.split(" · ")[0], CellReading.has(signal) ? signal : "—");
        TextView t = text(s, 12, Palette.TEXT_2);
        t.setTypeface(Typeface.MONOSPACE);
        t.setPadding(dp(8), 0, 0, 0);
        r.addView(t);
        return r;
    }

    // ============================================================ utilidades

    private static String n(int v) { return CellReading.has(v) ? Integer.toString(v) : "—"; }

    private LinearLayout card() {
        return NavajaUi.card(this);
    }

    private void setStatus(String s) { statusView.setText(s); }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private Button button(String s, View.OnClickListener l) {
        return NavajaUi.secondary(this, s, l);
    }


    private ScrollView wrapScroll(View v) {
        ScrollView s = new ScrollView(this);
        s.addView(v, new ViewGroup.LayoutParams(-1, -2));
        return s;
    }
}
