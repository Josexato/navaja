package com.josexato.navaja.diag;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.FeatureInfo;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.net.Uri;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.SizeF;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.josexato.navaja.BuildConfig;
import com.josexato.navaja.diag.core.Advisor;
import com.josexato.navaja.measure.AiBench;
import com.josexato.navaja.ui.NavajaUi;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Diagnóstico del teléfono: lee qué hardware tiene (chip, núcleos, memoria,
 * tarjeta gráfica, chip de IA, cámaras, sensores, pantalla) y qué librerías
 * de aceleración trae el sistema, y sugiere cuál conviene para correr
 * modelos de IA. No es una prueba de rendimiento general: lo único que se
 * mide (a pedido) es cuánto tardan los modelos del escáner 3D en la CPU, la
 * GPU y la NPU. No pide permisos y nada sale del teléfono si no se comparte.
 */
public class DiagActivity extends Activity {

    private static final String TAG = "Diag";
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    /** El informe: secciones en orden, cada una con sus líneas. */
    private final Map<String, List<String>> report = new LinkedHashMap<>();
    private LinearLayout content;
    private TextView benchText;
    private Button benchButton;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        NavajaUi.window(this);
        NavajaUi.Screen scr = NavajaUi.screen(this, "Diagnóstico del teléfono", "Qué hardware tiene y qué librerías de IA le sirven",
                NavajaUi.headerAction(this, "Compartir", v -> share()), true);
        content = scr.content;
        content.addView(NavajaUi.text(this, "Leyendo el hardware…", NavajaUi.Style.BODY));
        setContentView(scr.root);
        work.execute(() -> {
            try {
                collect();
            } catch (RuntimeException e) {
                Log.w(TAG, "leer", e);
                section("Error").add("No se pudo leer todo: " + e);
            }
            ui.post(this::show);
        });
    }

    @Override
    protected void onDestroy() {
        work.shutdownNow();
        super.onDestroy();
    }

    private List<String> section(String name) {
        List<String> l = report.get(name);
        if (l == null) {
            l = new ArrayList<>();
            report.put(name, l);
        }
        return l;
    }

    // ============================================================ lectura

    private void collect() {
        List<String> s = section("Equipo");
        s.add("Marca y modelo: " + Build.MANUFACTURER + " " + Build.MODEL + " (" + Build.DEVICE + ")");
        s.add("Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ") · parche de seguridad " + Build.VERSION.SECURITY_PATCH);
        String socMan = Build.VERSION.SDK_INT >= 31 ? Build.SOC_MANUFACTURER : "", socModel = Build.VERSION.SDK_INT >= 31 ? Build.SOC_MODEL : "";
        s.add("Chip (SoC): " + (socMan.isEmpty() && socModel.isEmpty() ? "el sistema no lo informa (Android menor a 12)" : socMan + " " + socModel));
        s.add("Placa: " + Build.BOARD + " · hardware: " + Build.HARDWARE);
        s.add("Arquitecturas (ABI): " + String.join(", ", Build.SUPPORTED_ABIS));

        cpu(section("Procesador (CPU)"));
        memory(section("Memoria y almacenamiento"));
        boolean openCl = gpu(section("Tarjeta gráfica (GPU)"));

        // Librerías de aceleración que trae el sistema
        List<String> ai = section("Chip de IA (NPU) y librerías del sistema");
        List<String> found = new ArrayList<>();
        for (String[] lib : Advisor.VENDOR_LIBS) {
            List<String> where = new ArrayList<>();
            for (int k = 1; k < lib.length; k++) {
                String p = findLib(lib[k]);
                if (p != null) where.add(p);
            }
            if (!where.isEmpty()) found.add(lib[0]);
            ai.add((where.isEmpty() ? "✘ " : "✔ ") + lib[0] + (where.isEmpty() ? ": no se ve en el sistema" : ": " + String.join(", ", where)));
        }
        Advisor.Vendor v = Advisor.vendor(socMan, socModel, Build.HARDWARE, Build.BOARD);
        List<String> rec = section("Qué librería conviene para IA");
        rec.add("Fabricante del chip: " + v + ".");
        rec.addAll(Advisor.advice(v, found, Build.VERSION.SDK_INT, openCl));
        rec.add("Navaja usa hoy TFLite 2.16 con CPU (XNNPACK), GPU y NNAPI; abajo se puede medir cuál rinde más en este teléfono.");

        display(section("Pantalla"));
        cameras(section("Cámaras"));
        sensors(section("Sensores"));
        section("Navaja").add("Versión " + BuildConfig.VERSION_NAME + " (commit " + BuildConfig.COMMIT + ")");
    }

    private void cpu(List<String> s) {
        int n = Runtime.getRuntime().availableProcessors();
        s.add("Núcleos: " + n);
        // Frecuencia máxima de cada núcleo: agrupa los núcleos en "grandes" y "chicos"
        Map<String, Integer> groups = new LinkedHashMap<>();
        for (int c = 0; c < n; c++) {
            String f = readLine("/sys/devices/system/cpu/cpu" + c + "/cpufreq/cpuinfo_max_freq");
            String key = f == null ? "frecuencia no informada" : String.format(Locale.ROOT, "%.2f GHz", Long.parseLong(f.trim()) / 1e6);
            Integer k = groups.get(key);
            groups.put(key, k == null ? 1 : k + 1);
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : groups.entrySet()) sb.append(sb.length() > 0 ? " + " : "").append(e.getValue()).append(" × ").append(e.getKey());
        s.add("Grupos de núcleos: " + sb);
        // /proc/cpuinfo: nombre del chip (si lo trae), instrucciones y tipo de núcleo
        Set<String> parts = new LinkedHashSet<>();
        String hardware = null, features = null;
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/cpuinfo"))) {
            for (String line; (line = r.readLine()) != null; ) {
                int i = line.indexOf(':');
                if (i < 0) continue;
                String k = line.substring(0, i).trim(), val = line.substring(i + 1).trim();
                if (k.equals("Hardware")) hardware = val;
                else if (k.equals("Features") && features == null) features = val;
                else if (k.equals("CPU part")) parts.add(val);
            }
        } catch (IOException | RuntimeException e) {
            s.add("/proc/cpuinfo no se pudo leer");
        }
        if (hardware != null) s.add("Hardware según el núcleo: " + hardware);
        if (!parts.isEmpty()) s.add("Tipos de núcleo (CPU part): " + String.join(", ", parts));
        if (features != null) {
            List<String> has = new ArrayList<>();
            for (String f : new String[]{"asimd", "asimdhp", "asimddp", "fphp", "sve", "sve2", "i8mm", "bf16", "aes", "sha2", "neon"})
                if ((" " + features + " ").contains(" " + f + " ")) has.add(f);
            s.add("Instrucciones útiles para IA: " + (has.isEmpty() ? "ninguna reconocida" : String.join(", ", has))
                    + " (asimd = NEON; asimddp = producto punto; fphp/asimdhp = media precisión; i8mm = matrices de 8 bits)");
        }
    }

    private void memory(List<String> s) {
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        s.add(String.format(Locale.ROOT, "RAM: %.1f GB en total · %.1f GB libres ahora", mi.totalMem / 1e9, mi.availMem / 1e9));
        s.add("Memoria por app: " + am.getMemoryClass() + " MB normal · " + am.getLargeMemoryClass() + " MB con «largeHeap» (Navaja lo usa)");
        try {
            StatFs fs = new StatFs(getFilesDir().getPath());
            s.add(String.format(Locale.ROOT, "Almacenamiento interno: %.0f GB en total · %.0f GB libres", fs.getTotalBytes() / 1e9, fs.getAvailableBytes() / 1e9));
        } catch (RuntimeException e) {
            s.add("Almacenamiento: no se pudo leer");
        }
    }

    /** Lee la tarjeta gráfica con un contexto OpenGL fuera de pantalla; devuelve si hay OpenCL. */
    private boolean gpu(List<String> s) {
        EGLDisplay dpy = EGL14.EGL_NO_DISPLAY;
        EGLContext ctx = EGL14.EGL_NO_CONTEXT;
        EGLSurface surf = EGL14.EGL_NO_SURFACE;
        try {
            dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] ver = new int[2];
            if (!EGL14.eglInitialize(dpy, ver, 0, ver, 1)) throw new RuntimeException("EGL no inicia");
            EGLConfig[] cfg = new EGLConfig[1];
            int[] num = new int[1];
            EGL14.eglChooseConfig(dpy, new int[]{EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_NONE}, 0, cfg, 0, 1, num, 0);
            if (num[0] == 0) throw new RuntimeException("sin configuración EGL");
            // Se pide ES 3 y, si no hay, ES 2: la versión que responda dice hasta dónde llega
            for (int es : new int[]{3, 2}) {
                ctx = EGL14.eglCreateContext(dpy, cfg[0], EGL14.EGL_NO_CONTEXT, new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, es, EGL14.EGL_NONE}, 0);
                if (ctx != null && ctx != EGL14.EGL_NO_CONTEXT) break;
            }
            surf = EGL14.eglCreatePbufferSurface(dpy, cfg[0], new int[]{EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE}, 0);
            EGL14.eglMakeCurrent(dpy, surf, surf, ctx);
            s.add("Modelo: " + GLES20.glGetString(GLES20.GL_RENDERER) + " (" + GLES20.glGetString(GLES20.GL_VENDOR) + ")");
            s.add("OpenGL: " + GLES20.glGetString(GLES20.GL_VERSION));
            String ext = GLES20.glGetString(GLES20.GL_EXTENSIONS);
            if (ext != null) {
                int n = ext.trim().split("\\s+").length;
                s.add("Extensiones de OpenGL: " + n + (ext.contains("GL_EXT_color_buffer_half_float") ? " · media precisión en texturas" : "")
                        + (ext.contains("GL_EXT_color_buffer_float") ? " · precisión completa en texturas" : ""));
            }
        } catch (RuntimeException e) {
            s.add("No se pudo abrir OpenGL para leer la tarjeta gráfica: " + e.getMessage());
        } finally {
            if (dpy != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                if (surf != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, surf);
                if (ctx != null && ctx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(dpy, ctx);
                EGL14.eglTerminate(dpy);
            }
        }
        // Vulkan y OpenCL
        String vk = "no informado";
        for (FeatureInfo f : getPackageManager().getSystemAvailableFeatures())
            if (PackageManager.FEATURE_VULKAN_HARDWARE_VERSION.equals(f.name))
                vk = (f.version >> 22) + "." + ((f.version >> 12) & 0x3ff) + "." + (f.version & 0xfff);
        s.add("Vulkan: " + vk);
        String cl = findLib("libOpenCL.so");
        if (cl == null) cl = findLib("libOpenCL-pixel.so");
        s.add("OpenCL (lo que usa la GPU de TFLite): " + (cl == null ? "no se ve la librería" : cl));
        return cl != null;
    }

    private void display(List<String> s) {
        DisplayMetrics m = getResources().getDisplayMetrics();
        float hz = getWindowManager().getDefaultDisplay().getRefreshRate();
        s.add(String.format(Locale.ROOT, "%d × %d px · %d dpi · %.0f Hz ahora", m.widthPixels, m.heightPixels, m.densityDpi, hz));
    }

    private void cameras(List<String> s) {
        try {
            CameraManager cm = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            for (String id : cm.getCameraIdList()) {
                CameraCharacteristics ch = cm.getCameraCharacteristics(id);
                Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
                float[] fl = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                SizeF ss = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
                android.util.Size px = ch.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
                Integer level = ch.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
                int phys = Build.VERSION.SDK_INT >= 28 ? ch.getPhysicalCameraIds().size() : 0;
                s.add(String.format(Locale.ROOT, "Cámara %s (%s): %s · focal %s mm · sensor %s · nivel %s%s", id,
                        facing == null ? "?" : facing == CameraCharacteristics.LENS_FACING_BACK ? "trasera" : facing == CameraCharacteristics.LENS_FACING_FRONT
                                ? "frontal" : "externa",
                        px == null ? "?" : String.format(Locale.ROOT, "%.1f MP", px.getWidth() * (double) px.getHeight() / 1e6),
                        fl == null || fl.length == 0 ? "?" : String.format(Locale.ROOT, "%.2f", fl[0]),
                        ss == null ? "?" : String.format(Locale.ROOT, "%.1f × %.1f mm", ss.getWidth(), ss.getHeight()),
                        level == null ? "?" : level == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY ? "LEGACY"
                                : level == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED ? "LIMITED"
                                : level == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL ? "FULL" : level == 3 ? "LEVEL_3" : String.valueOf(level),
                        phys > 0 ? " · agrupa " + phys + " cámaras físicas" : ""));
            }
        } catch (Exception e) {
            s.add("No se pudieron leer las cámaras: " + e.getMessage());
        }
    }

    private void sensors(List<String> s) {
        SensorManager sm = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        List<Sensor> all = sm.getSensorList(Sensor.TYPE_ALL);
        s.add("Sensores: " + all.size());
        int[] types = {Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE, Sensor.TYPE_MAGNETIC_FIELD, Sensor.TYPE_GRAVITY, Sensor.TYPE_GAME_ROTATION_VECTOR,
                Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_PRESSURE, Sensor.TYPE_LIGHT, Sensor.TYPE_PROXIMITY};
        String[] names = {"Acelerómetro", "Giroscopio", "Magnetómetro", "Gravedad", "Rotación (juego)", "Rotación", "Barómetro", "Luz", "Proximidad"};
        for (int k = 0; k < types.length; k++) {
            Sensor x = sm.getDefaultSensor(types[k]);
            s.add((x == null ? "✘ " : "✔ ") + names[k] + (x == null ? "" : ": " + x.getName() + " (" + x.getVendor() + ")"));
        }
    }

    /** Ruta de la librería {@code name} en las carpetas del sistema, o null. */
    private static String findLib(String name) {
        for (String dir : new String[]{"/vendor/lib64", "/system/lib64", "/system/vendor/lib64", "/odm/lib64", "/system_ext/lib64", "/product/lib64",
                "/vendor/lib64/egl", "/vendor/lib", "/system/lib", "/odm/lib", "/apex/com.android.neuralnetworks/lib64"}) {
            try {
                if (new File(dir, name).exists()) return dir + "/" + name;
            } catch (SecurityException e) {
                // carpeta no legible: se sigue con la próxima
            }
        }
        return null;
    }

    private static String readLine(String path) {
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            return r.readLine();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    // ============================================================ pantalla

    private void show() {
        content.removeAllViews();
        for (Map.Entry<String, List<String>> e : report.entrySet()) {
            LinearLayout card = NavajaUi.card(this);
            card.addView(NavajaUi.text(this, e.getKey(), NavajaUi.Style.LABEL));
            card.addView(NavajaUi.text(this, String.join("\n", e.getValue()), NavajaUi.Style.BODY));
            content.addView(card);
            if (e.getKey().startsWith("Qué librería")) {
                // La única medición: los modelos del escáner 3D en cada acelerador
                LinearLayout bc = NavajaUi.card(this);
                bc.addView(NavajaUi.text(this, "Prueba real de aceleradores", NavajaUi.Style.LABEL));
                benchText = NavajaUi.text(this, "Carga los dos modelos de IA del escáner 3D (XFeat y LighterGlue) en la CPU, la tarjeta gráfica y el chip de IA, "
                        + "y mide cuánto tarda cada uno. Toma unos segundos. Si la app se cierra durante la prueba, ábrela de nuevo: "
                        + "el acelerador que falló queda anotado y no se vuelve a probar.", NavajaUi.Style.SECONDARY);
                bc.addView(benchText);
                benchButton = NavajaUi.primary(this, "Probar aceleradores", v -> bench());
                bc.addView(NavajaUi.buttonRow(this, benchButton));
                content.addView(bc);
            }
        }
        content.addView(NavajaUi.buttonRow(this, NavajaUi.primary(this, "Copiar el informe", v -> copy()),
                NavajaUi.secondary(this, "Compartir como archivo", v -> share())));
        content.addView(NavajaUi.text(this, "El informe es texto con una explicación al principio, listo para pegarlo en un asistente de IA y "
                + "preguntarle qué librería conviene.", NavajaUi.Style.SECONDARY));
        content.addView(NavajaUi.text(this, "No se pide ningún permiso y nada sale del teléfono si no lo compartes.", NavajaUi.Style.MUTED));
    }

    private void bench() {
        benchButton.setEnabled(false);
        benchText.setText("Probando CPU, tarjeta gráfica y chip de IA…");
        work.execute(() -> {
            String out;
            try {
                out = AiBench.run(this).replace(" · ", "\n");
            } catch (IOException | RuntimeException | LinkageError e) {
                Log.w(TAG, "prueba", e);
                out = "No se pudo hacer la prueba: " + e;
            }
            String res = out;
            ui.post(() -> {
                List<String> s = section("Prueba real de aceleradores");
                s.clear();
                s.add(res);
                benchText.setText(res);
                benchButton.setEnabled(true);
                benchButton.setText("Probar de nuevo");
            });
        });
    }

    /** El informe como texto (Markdown simple). */
    private String text() {
        StringBuilder s = new StringBuilder("# Diagnóstico del teléfono — Navaja\n\n");
        s.append(new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(new Date())).append("\n\n");
        // Contexto para quien lo lea sin conocer la app (por ejemplo, un asistente de IA)
        s.append("Contexto para quien lea esto: es el informe de hardware de un teléfono Android, generado por la app Navaja con lo que ")
                .append("el sistema deja leer a una app sin permisos (\"no se ve\" no siempre significa que no exista). Navaja corre dos ")
                .append("modelos TFLite en el teléfono (XFeat, una red convolucional de 512×960, y LighterGlue, un transformer con 1024 puntos ")
                .append("por imagen) con TFLite 2.16 y los delegados XNNPACK (CPU), GPU y NNAPI. La pregunta es qué librería o delegado de ")
                .append("aceleración conviene para este chip y qué habría que cambiar para usarlo. Si está la sección \"Prueba real de ")
                .append("aceleradores\", son tiempos medidos en este teléfono con esos dos modelos.\n");
        for (Map.Entry<String, List<String>> e : report.entrySet()) {
            s.append("\n## ").append(e.getKey()).append("\n\n");
            for (String l : e.getValue()) for (String line : l.split("\n")) s.append("- ").append(line).append('\n');
        }
        return s.toString();
    }

    private void copy() {
        if (report.isEmpty()) return;
        android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(android.content.ClipData.newPlainText("Diagnóstico del teléfono", text()));
        Toast.makeText(this, "Informe copiado: pégalo donde quieras", Toast.LENGTH_SHORT).show();
    }

    private void share() {
        if (report.isEmpty()) return;
        try {
            File dir = new File(getExternalFilesDir(null), "diagnostico");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File f = new File(dir, "diagnostico_" + Build.MODEL.replaceAll("[^A-Za-z0-9]+", "_") + ".md");
            try (FileOutputStream fo = new FileOutputStream(f)) {
                fo.write(text().getBytes(StandardCharsets.UTF_8));
            }
            Uri u = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
            startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/markdown").putExtra(Intent.EXTRA_STREAM, u)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Compartir el diagnóstico"));
        } catch (IOException | RuntimeException e) {
            Toast.makeText(this, "No se pudo compartir: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
