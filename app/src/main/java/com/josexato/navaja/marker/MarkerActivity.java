package com.josexato.navaja.marker;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.josexato.navaja.marker.core.ExifGps;
import com.josexato.navaja.marker.core.KmzWriter;
import com.josexato.navaja.marker.core.Waypoint;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Marcador de ubicaciones: guarda waypoints con el GPS, toma fotos con la
 * ubicación escrita en su EXIF y exporta todo a KMZ (con las fotos dentro)
 * o GPX. Los datos quedan en el teléfono hasta que tú los compartes.
 */
public class MarkerActivity extends Activity {

    private static final String TAG = "Marcador";
    private static final int REQ_LOC = 1, REQ_CAM = 2, REQ_PHOTO = 10;
    /** Un fix más viejo que esto ya no representa dónde estás. */
    private static final long STALE_MS = 30_000;
    /** Lado mayor de las fotos dentro del KMZ "liviano". */
    private static final int KMZ_PHOTO_SIDE = 1600;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private LocationManager lm;
    private Location fix;
    private File dir, photoDir, dataFile;
    private final List<Waypoint> points = new ArrayList<>();
    private File pendingPhoto;

    private TextView gpsView;
    private LinearLayout list;

    private final LocationListener listener = loc -> {
        // GPS siempre gana; la posición por red sólo si no hay GPS reciente o es más precisa.
        boolean gps = LocationManager.GPS_PROVIDER.equals(loc.getProvider());
        if (fix == null || gps || System.currentTimeMillis() - fix.getTime() > 10_000
                || (loc.hasAccuracy() && fix.hasAccuracy() && loc.getAccuracy() < fix.getAccuracy())) {
            fix = loc;
        }
        renderGps();
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            renderGps();
            ui.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        File base = getExternalFilesDir(null);
        if (base == null) base = getFilesDir();
        dir = new File(base, "marcador");
        photoDir = new File(dir, "fotos");
        //noinspection ResultOfMethodCallIgnored
        photoDir.mkdirs();
        dataFile = new File(dir, "waypoints.json");
        if (b != null && b.getString("pending") != null) pendingPhoto = new File(b.getString("pending"));
        load();
        buildUi();
        if (!hasLoc()) requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOC);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (pendingPhoto != null) out.putString("pending", pendingPhoto.getAbsolutePath());
    }

    @Override
    protected void onResume() {
        super.onResume();
        startGps();
        ui.post(tick);
    }

    @Override
    protected void onPause() {
        stopGps();
        ui.removeCallbacks(tick);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        work.shutdown();
        super.onDestroy();
    }

    // ================================================================== GPS

    private boolean hasLoc() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void startGps() {
        if (!hasLoc() || lm == null) return;
        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER))
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, listener, Looper.getMainLooper());
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000, 0, listener, Looper.getMainLooper());
            Location last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (fix == null && last != null) fix = last;
        } catch (SecurityException | IllegalArgumentException e) {
            Log.w(TAG, "GPS", e);
        }
    }

    private void stopGps() {
        try {
            lm.removeUpdates(listener);
        } catch (Exception ignored) {
            // nada que detener
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == REQ_LOC && hasLoc()) startGps();
        if (code == REQ_CAM) {
            if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) takePhoto();
            else toast("Sin permiso de cámara.");
        }
        renderGps();
    }

    private boolean locationOn() {
        if (lm == null) return false;
        if (Build.VERSION.SDK_INT >= 28) return lm.isLocationEnabled();
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER);
    }

    private long fixAgeMs() { return fix == null ? Long.MAX_VALUE : System.currentTimeMillis() - fix.getTime(); }

    // ============================================================ acciones

    /** Verifica que haya ubicación usable; si es mala pregunta antes de seguir. */
    private void withFix(String what, Runnable action) {
        if (!hasLoc()) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOC);
            return;
        }
        if (!locationOn()) {
            new AlertDialog.Builder(this).setMessage("La ubicación del teléfono está apagada.")
                    .setPositiveButton("Abrir ajustes", (d, w) -> startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)))
                    .setNegativeButton("Cancelar", null).show();
            return;
        }
        if (fix == null) {
            toast("Esperando señal GPS… (al aire libre tarda unos segundos)");
            return;
        }
        boolean stale = fixAgeMs() > STALE_MS;
        boolean rough = fix.hasAccuracy() && fix.getAccuracy() > 30;
        if (stale || rough) {
            String why = stale ? "La última posición tiene " + fixAgeMs() / 1000 + " s." : "";
            if (rough) why += (why.isEmpty() ? "" : " ") + String.format(Locale.US, "La precisión es de ±%.0f m.", fix.getAccuracy());
            new AlertDialog.Builder(this).setTitle("Ubicación poco precisa")
                    .setMessage(why + "\n\n¿" + what + " igual, o esperar a que mejore?")
                    .setPositiveButton("Usar igual", (d, w) -> action.run())
                    .setNegativeButton("Esperar", null).show();
            return;
        }
        action.run();
    }

    private void savePoint() {
        withFix("Guardar el punto", () -> {
            Location at = fix;
            editDialog("Nuevo punto", "Punto " + (points.size() + 1), "", (name, note) -> {
                Waypoint w = fromFix(at);
                w.name = name;
                w.note = note;
                points.add(0, w);
                persist();
                render();
            });
        });
    }

    private void takePhoto() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAM);
            return;
        }
        withFix("Tomar la foto", () -> {
            pendingPhoto = new File(photoDir, "foto_" + stamp() + ".jpg");
            try {
                Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", pendingPhoto);
                Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, uri)
                        .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivityForResult(i, REQ_PHOTO);
            } catch (ActivityNotFoundException | IllegalArgumentException e) {
                toast("No se pudo abrir la cámara: " + e.getMessage());
            }
        });
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req != REQ_PHOTO) return;
        File f = pendingPhoto;
        pendingPhoto = null;
        if (result != RESULT_OK || f == null || f.length() == 0) {
            if (f != null && f.exists() && f.length() == 0 && !f.delete()) Log.w(TAG, "no se borró " + f);
            return;
        }
        // La posición al volver de la cámara es la del lugar de la foto. (Hay fix:
        // withFix() lo exigió antes de abrir la cámara.)
        Location at = fix;
        if (at == null) {
            toast("Foto guardada en la carpeta, pero sin ubicación: no había señal GPS.");
            return;
        }
        // Se fija la ubicación ya, aunque el diálogo de nombre se cancele.
        Waypoint w = fromFix(at);
        w.name = "Foto " + (countPhotos() + 1);
        w.photo = f.getName();
        writeExif(f, w);
        points.add(0, w);
        persist();
        render();
        editDialog("Foto con ubicación", w.name, "", (name, note) -> {
            w.name = name;
            w.note = note;
            writeExif(f, w); // actualiza la descripción con el nombre elegido
            persist();
            render();
        });
    }

    /** Escribe la ubicación (y el nombre) en el EXIF del JPEG. */
    private void writeExif(File f, Waypoint w) {
        try {
            ExifInterface ex = new ExifInterface(f.getAbsolutePath());
            ex.setAttribute(ExifInterface.TAG_GPS_LATITUDE, ExifGps.dms(w.lat));
            ex.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, ExifGps.latRef(w.lat));
            ex.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, ExifGps.dms(w.lon));
            ex.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, ExifGps.lonRef(w.lon));
            if (!Double.isNaN(w.altM)) {
                ex.setAttribute(ExifInterface.TAG_GPS_ALTITUDE, ExifGps.altitude(w.altM));
                ex.setAttribute(ExifInterface.TAG_GPS_ALTITUDE_REF, ExifGps.altitudeRef(w.altM));
            }
            String[] dt = ExifGps.dateTime(w.timeMs);
            ex.setAttribute(ExifInterface.TAG_GPS_DATESTAMP, dt[0]);
            ex.setAttribute(ExifInterface.TAG_GPS_TIMESTAMP, dt[1]);
            ex.setAttribute(ExifInterface.TAG_GPS_PROCESSING_METHOD, "GPS");
            if (w.name != null) ex.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, w.name);
            ex.saveAttributes();
        } catch (IOException e) {
            Log.w(TAG, "EXIF", e);
            toast("No se pudo escribir la ubicación en la foto (el punto sí quedó guardado).");
        }
    }

    private Waypoint fromFix(Location at) {
        Waypoint w = new Waypoint();
        w.id = "w" + System.currentTimeMillis();
        w.timeMs = System.currentTimeMillis();
        w.lat = at.getLatitude();
        w.lon = at.getLongitude();
        if (at.hasAltitude()) w.altM = at.getAltitude();
        if (at.hasAccuracy()) w.accM = at.getAccuracy();
        return w;
    }

    private int countPhotos() {
        int n = 0;
        for (Waypoint w : points) if (w.hasPhoto()) n++;
        return n;
    }

    // ============================================================ exportar

    private void exportMenu() {
        if (points.isEmpty()) {
            toast("Aún no hay puntos guardados.");
            return;
        }
        String[] items = {"KMZ con fotos (Google Earth, My Maps) — fotos reducidas",
                "KMZ con fotos en calidad original", "GPX (apps de GPS, sin fotos)"};
        new AlertDialog.Builder(this).setTitle("Exportar " + points.size() + " puntos")
                .setItems(items, (d, which) -> {
                    if (which == 2) exportGpx();
                    else exportKmz(which == 0);
                }).show();
    }

    private void exportKmz(boolean reduced) {
        toast("Generando KMZ…");
        List<Waypoint> copy = new ArrayList<>(points);
        work.submit(() -> {
            try {
                Map<String, byte[]> photos = new HashMap<>();
                for (Waypoint w : copy) {
                    if (!w.hasPhoto()) continue;
                    File f = new File(photoDir, w.photo);
                    if (!f.exists()) continue;
                    photos.put(w.photo, reduced ? shrink(f) : readBytes(f));
                }
                String title = "Navaja · puntos " + new SimpleDateFormat("dd/MM/yyyy", Locale.US).format(new Date());
                byte[] kmz = KmzWriter.kmz(title, copy, photos);
                File out = writeExport("puntos_" + stamp() + ".kmz", kmz);
                ui.post(() -> share(out, "application/vnd.google-earth.kmz"));
            } catch (Throwable t) {
                Log.e(TAG, "kmz", t);
                ui.post(() -> toast("No se pudo generar el KMZ: " + t.getMessage()));
            }
        });
    }

    private void exportGpx() {
        String gpx = KmzWriter.gpx("Navaja · puntos", points);
        share(writeExport("puntos_" + stamp() + ".gpx", gpx.getBytes(StandardCharsets.UTF_8)), "application/gpx+xml");
    }

    /** Foto con la rotación del EXIF aplicada y el lado mayor reducido, en JPEG 85. */
    private byte[] shrink(File f) throws IOException {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= KMZ_PHOTO_SIDE) sample *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        Bitmap bmp = BitmapFactory.decodeFile(f.getAbsolutePath(), d);
        if (bmp == null) return readBytes(f);
        Matrix m = new Matrix();
        float k = (float) KMZ_PHOTO_SIDE / Math.max(bmp.getWidth(), bmp.getHeight());
        if (k < 1) m.postScale(k, k);
        int rot = rotation(f);
        if (rot != 0) m.postRotate(rot);
        Bitmap out = m.isIdentity() ? bmp : Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        out.compress(Bitmap.CompressFormat.JPEG, 85, bos);
        if (out != bmp) out.recycle();
        bmp.recycle();
        return bos.toByteArray();
    }

    private static int rotation(File f) {
        try {
            int or = new ExifInterface(f.getAbsolutePath()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            if (or == ExifInterface.ORIENTATION_ROTATE_90) return 90;
            if (or == ExifInterface.ORIENTATION_ROTATE_180) return 180;
            if (or == ExifInterface.ORIENTATION_ROTATE_270) return 270;
        } catch (IOException ignored) {
            // sin EXIF: sin rotación
        }
        return 0;
    }

    // ========================================================= persistencia

    private void load() {
        if (!dataFile.exists()) return;
        try {
            points.clear();
            points.addAll(Waypoint.fromJson(new String(readBytes(dataFile), StandardCharsets.UTF_8)));
        } catch (Exception e) {
            Log.e(TAG, "waypoints.json", e);
            toast("No se pudo leer la lista de puntos: " + e.getMessage());
        }
    }

    private void persist() {
        File tmp = new File(dir, "waypoints.json.tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(Waypoint.toJson(points).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            toast("No se pudo guardar: " + e.getMessage());
            return;
        }
        if (!tmp.renameTo(dataFile)) toast("No se pudo guardar la lista de puntos.");
    }

    // ================================================================== UI

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF15171A);
        root.setPadding(dp(16), dp(16), dp(16), dp(8));
        TextView title = text("Marcador de ubicaciones", 20, 0xFFFFFFFF);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);
        gpsView = text("", 13, 0xFFB9C4CF);
        gpsView.setTypeface(Typeface.MONOSPACE);
        gpsView.setPadding(0, dp(6), 0, dp(8));
        root.addView(gpsView);

        LinearLayout row = new LinearLayout(this);
        row.addView(button("📍 Guardar punto", v -> savePoint()));
        row.addView(button("📷 Foto", v -> takePhoto()));
        row.addView(button("⤴ KMZ", v -> exportMenu()));
        for (int i = 0; i < row.getChildCount(); i++) row.getChildAt(i).setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(row);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(this);
        sv.addView(list);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);
        render();
    }

    private void renderGps() {
        if (gpsView == null) return;
        if (!hasLoc()) { gpsView.setText("Sin permiso de ubicación."); return; }
        if (!locationOn()) { gpsView.setText("La ubicación del teléfono está apagada."); return; }
        if (fix == null) { gpsView.setText("Buscando GPS…"); return; }
        long age = fixAgeMs() / 1000;
        StringBuilder sb = new StringBuilder(String.format(Locale.US, "%.6f, %.6f", fix.getLatitude(), fix.getLongitude()));
        if (fix.hasAltitude()) sb.append(String.format(Locale.US, " · %.0f m", fix.getAltitude()));
        sb.append('\n');
        if (fix.hasAccuracy()) sb.append(String.format(Locale.US, "±%.0f m", fix.getAccuracy()));
        sb.append(" · ").append(LocationManager.GPS_PROVIDER.equals(fix.getProvider()) ? "GPS" : "red");
        sb.append(" · hace ").append(age).append(" s");
        gpsView.setText(sb.toString());
        boolean good = fix.hasAccuracy() && fix.getAccuracy() <= 15 && age < 10;
        gpsView.setTextColor(good ? 0xFF6EE7A0 : age > 30 ? 0xFFFF8A7E : 0xFFFFD166);
    }

    private void render() {
        list.removeAllViews();
        if (points.isEmpty()) {
            list.addView(text("\nGuarda puntos con «📍 Guardar punto» o toma fotos con «📷 Foto»: cada foto lleva la "
                    + "ubicación en su EXIF. Después exporta todo como KMZ (las fotos van dentro y se ven en el globo "
                    + "de cada punto en Google Earth o My Maps) o como GPX.", 13, 0x99FFFFFF));
            return;
        }
        SimpleDateFormat f = new SimpleDateFormat("dd/MM HH:mm", Locale.US);
        for (Waypoint w : points) {
            LinearLayout card = new LinearLayout(this);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setPadding(dp(10), dp(10), dp(10), dp(10));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(8);
            card.setLayoutParams(lp);
            GradientDrawable g = new GradientDrawable();
            g.setColor(0xFF1E232B);
            g.setCornerRadius(dp(12));
            g.setStroke(1, 0xFF333B46);
            card.setBackground(g);

            if (w.hasPhoto()) {
                ImageView iv = new ImageView(this);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setLayoutParams(new LinearLayout.LayoutParams(dp(56), dp(56)));
                card.addView(iv);
                File pf = new File(photoDir, w.photo);
                work.submit(() -> {
                    Bitmap t = thumb(pf);
                    if (t != null) ui.post(() -> iv.setImageBitmap(t));
                });
            } else {
                TextView pin = text("📍", 26, 0xFFFFFFFF);
                pin.setGravity(Gravity.CENTER);
                card.addView(pin, new LinearLayout.LayoutParams(dp(56), dp(56)));
            }
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(dp(12), 0, 0, 0);
            TextView name = text(w.name, 15, 0xFFFFFFFF);
            name.setTypeface(Typeface.DEFAULT_BOLD);
            col.addView(name);
            String sub = String.format(Locale.US, "%.6f, %.6f%s · %s", w.lat, w.lon,
                    Double.isNaN(w.accM) ? "" : String.format(Locale.US, " · ±%.0f m", w.accM), f.format(new Date(w.timeMs)));
            col.addView(text(sub, 12, 0xFF9FB0C3));
            if (w.note != null && !w.note.isEmpty()) col.addView(text(w.note, 12, 0xFF7E8A99));
            card.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
            card.setOnClickListener(v -> pointMenu(w));
            list.addView(card);
        }
    }

    private void pointMenu(Waypoint w) {
        List<String> items = new ArrayList<>();
        items.add("Ver en mapa");
        if (w.hasPhoto()) { items.add("Ver foto"); items.add("Compartir foto"); }
        items.add("Editar nombre y nota");
        items.add("Borrar");
        new AlertDialog.Builder(this).setTitle(w.name)
                .setItems(items.toArray(new String[0]), (d, which) -> {
                    String it = items.get(which);
                    switch (it) {
                        case "Ver en mapa": openMap(w); break;
                        case "Ver foto": showPhoto(w); break;
                        case "Compartir foto": share(new File(photoDir, w.photo), "image/jpeg"); break;
                        case "Editar nombre y nota":
                            editDialog("Editar", w.name, w.note == null ? "" : w.note, (n, note) -> {
                                w.name = n;
                                w.note = note;
                                persist();
                                render();
                            });
                            break;
                        default: confirmDelete(w);
                    }
                }).show();
    }

    private void confirmDelete(Waypoint w) {
        new AlertDialog.Builder(this).setMessage("¿Borrar «" + w.name + "»" + (w.hasPhoto() ? " y su foto" : "") + "?")
                .setPositiveButton("Borrar", (d, x) -> {
                    if (w.hasPhoto() && !new File(photoDir, w.photo).delete()) Log.w(TAG, "foto no borrada");
                    points.remove(w);
                    persist();
                    render();
                })
                .setNegativeButton("Cancelar", null).show();
    }

    private void showPhoto(Waypoint w) {
        ImageView iv = new ImageView(this);
        iv.setAdjustViewBounds(true);
        File pf = new File(photoDir, w.photo);
        work.submit(() -> {
            try {
                byte[] small = shrink(pf);
                Bitmap b = BitmapFactory.decodeByteArray(small, 0, small.length);
                ui.post(() -> iv.setImageBitmap(b));
            } catch (IOException e) {
                ui.post(() -> toast("No se pudo abrir la foto."));
            }
        });
        new AlertDialog.Builder(this).setTitle(w.name).setView(iv).setPositiveButton("Cerrar", null).show();
    }

    private void openMap(Waypoint w) {
        Uri uri = Uri.parse(String.format(Locale.US, "geo:%.7f,%.7f?q=%.7f,%.7f(%s)", w.lat, w.lon, w.lat, w.lon, Uri.encode(w.name)));
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException e) {
            toast("No hay una app de mapas instalada.");
        }
    }

    private interface OnEdited { void done(String name, String note); }

    private void editDialog(String title, String name, String note, OnEdited cb) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        EditText n = new EditText(this);
        n.setHint("Nombre");
        n.setText(name);
        n.setSingleLine(true);
        n.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        n.setSelectAllOnFocus(true);
        EditText d = new EditText(this);
        d.setHint("Nota (opcional)");
        d.setText(note);
        d.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        box.addView(n);
        box.addView(d);
        new AlertDialog.Builder(this).setTitle(title).setView(box)
                .setPositiveButton("Guardar", (x, y) -> {
                    String nm = n.getText().toString().trim();
                    cb.done(nm.isEmpty() ? name : nm, d.getText().toString().trim());
                })
                .setNegativeButton("Cancelar", null)
                .setCancelable(false)
                .show();
    }

    // ============================================================ utilidades

    private Bitmap thumb(File f) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        if (o.outWidth <= 0) return null;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = Math.max(1, Math.min(o.outWidth, o.outHeight) / 160);
        Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath(), d);
        int rot = rotation(f);
        if (b == null || rot == 0) return b;
        Matrix m = new Matrix();
        m.postRotate(rot);
        return Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
    }

    private File writeExport(String name, byte[] data) {
        File out = new File(dir, "export");
        //noinspection ResultOfMethodCallIgnored
        out.mkdirs();
        File f = new File(out, name);
        try (OutputStream os = new FileOutputStream(f)) {
            os.write(data);
            return f;
        } catch (IOException e) {
            ui.post(() -> toast("No se pudo escribir " + name));
            return null;
        }
    }

    private void share(File f, String mime) {
        if (f == null || !f.exists()) return;
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
            Intent i = new Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "Compartir " + f.getName()));
        } catch (Exception e) {
            toast("Guardado en " + f.getAbsolutePath());
        }
    }

    private static byte[] readBytes(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private static String stamp() { return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()); }

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
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

}
