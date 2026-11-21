package com.josexato.navaja.measure;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetFileDescriptor;
import android.os.Build;
import android.util.Log;

import com.josexato.navaja.BuildConfig;
import com.josexato.navaja.diag.core.Advisor;
import com.josexato.navaja.measure.core.XFeatTracks;
import com.qualcomm.qti.QnnDelegate;

import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.gpu.CompatibilityList;
import org.tensorflow.lite.gpu.GpuDelegate;
import org.tensorflow.lite.nnapi.NnApiDelegate;

import java.io.Closeable;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * XFeat (puntos y descriptores) y LighterGlue (emparejador) en el teléfono,
 * con TFLite: Apache-2.0, vienen dentro del APK (ver
 * modelos/LICENCIA-xfeat.txt), sin internet. Al cargar prueba en el
 * teléfono la CPU (XNNPACK), la GPU, NNAPI y, en chips Qualcomm, la NPU con
 * el delegado QNN, y se queda con la más rápida que dé lo mismo que la CPU
 * (ver {@link Engine}). Anota cuál usó,
 * cuánto tarda y cómo le fue a cada una, para el diagnóstico.
 */
final class XFeat implements Closeable {

    private static final String TAG = "XFeat";
    private static final String BACKBONE = "modelos/xfeat.tflite", GLUE = "modelos/lighterglue.tflite";
    private static final int N = XFeatTracks.N;
    /** La NPU de Qualcomm con su delegado QNN (AI Engine Direct). */
    private static final String QNN = "NPU Qualcomm";
    private static final int MAX_ERROR = 900;

    private final Interpreter net, glue;
    private final AutoCloseable accNet, accGlue;
    /** Dónde corre cada modelo: "GPU", "NNAPI", "NPU Qualcomm (QNN)" o "CPU (n hilos)". */
    final String netDelegate, glueDelegate;
    /** Cómo se eligió: el tiempo de cada opción probada en este teléfono (para el diagnóstico). */
    final String choiceNote;

    private final float[] image = new float[XFeatTracks.IN_W * XFeatTracks.IN_H];
    private final FloatBuffer in, outDesc, outHeat, outRel, gk0, gk1, gd0, gd1, outV;
    private final IntBuffer outM0, outM1;
    private final float[] desc, heat, rel, v0;
    private final int[] m0, m1;
    /** Tiempos acumulados (ms) y cantidades, para el diagnóstico. */
    double detectMs, matchMs;
    int detected, matched;

    XFeat(Context c) throws IOException {
        int threads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors()));
        int gw = XFeatTracks.IN_W / 8, gh = XFeatTracks.IN_H / 8;
        in = floats(image.length);
        desc = new float[gh * gw * XFeatTracks.DIM];
        heat = new float[image.length];
        rel = new float[gh * gw];
        outDesc = floats(desc.length);
        outHeat = floats(heat.length);
        outRel = floats(rel.length);
        gk0 = floats(2 * N);
        gk1 = floats(2 * N);
        gd0 = floats(XFeatTracks.DIM * N);
        gd1 = floats(XFeatTracks.DIM * N);
        outV = floats(N);
        outM0 = ByteBuffer.allocateDirect(4 * N).order(ByteOrder.nativeOrder()).asIntBuffer();
        outM1 = ByteBuffer.allocateDirect(4 * N).order(ByteOrder.nativeOrder()).asIntBuffer();
        v0 = new float[N];
        m0 = new int[N];
        m1 = new int[N];
        // Entradas de prueba: una imagen con textura y dos juegos de puntos que son los mismos en otro orden
        // (así el emparejador tiene pares verdaderos y se puede comparar lo que da cada acelerador con la CPU)
        java.util.Random rnd = new java.util.Random(1);
        for (int i = 0; i < image.length; i++) {
            int x = i % XFeatTracks.IN_W, y = i / XFeatTracks.IN_W;
            image[i] = ((x / 16 + y / 16) % 2 == 0 ? 60 : 190) + rnd.nextInt(30);
        }
        in.put(image).rewind();
        float[] k = new float[2 * N], d = new float[XFeatTracks.DIM * N], k2 = new float[2 * N], d2 = new float[XFeatTracks.DIM * N];
        for (int i = 0; i < N; i++) {
            k[2 * i] = rnd.nextFloat() - 0.5f;
            k[2 * i + 1] = 1.6f * (rnd.nextFloat() - 0.5f);
            double nn = 0;
            for (int j = 0; j < XFeatTracks.DIM; j++) { d[i * XFeatTracks.DIM + j] = (float) rnd.nextGaussian(); nn += d[i * XFeatTracks.DIM + j] * d[i * XFeatTracks.DIM + j]; }
            for (int j = 0; j < XFeatTracks.DIM; j++) d[i * XFeatTracks.DIM + j] /= (float) Math.sqrt(nn);
        }
        for (int i = 0; i < N; i++) {
            int o = (i * 7 + 3) % N; // el punto i de la segunda foto es el o de la primera, corrido un poco
            k2[2 * i] = k[2 * o] + 0.01f;
            k2[2 * i + 1] = k[2 * o + 1] + 0.01f;
            System.arraycopy(d, o * XFeatTracks.DIM, d2, i * XFeatTracks.DIM, XFeatTracks.DIM);
        }
        gk0.put(k).rewind();
        gk1.put(k2).rewind();
        gd0.put(d).rewind();
        gd1.put(d2).rewind();
        StringBuilder note = new StringBuilder();
        // XFeat: se compara el mapa de puntos; LighterGlue: los pares que elige
        Engine a = Engine.pick(c, BACKBONE, "XFeat", threads, it -> { runNet(it); return heat.clone(); },
                (ref, got) -> { double s = 0; for (int i = 0; i < ref.length; i++) s += Math.abs(ref[i] - got[i]); return s / ref.length < 0.02; }, note);
        note.append(" · ");
        Engine b = Engine.pick(c, GLUE, "LighterGlue", threads, it -> { runGlue(it); float[] o = new float[N]; for (int i = 0; i < N; i++) o[i] = m0[i]; return o; },
                (ref, got) -> { int same = 0; for (int i = 0; i < ref.length; i++) if (ref[i] == got[i]) same++; return same >= 0.9 * ref.length; }, note);
        net = a.it;
        accNet = a.delegate;
        netDelegate = a.name;
        glue = b.it;
        accGlue = b.delegate;
        glueDelegate = b.name;
        choiceNote = note.toString();
    }

    private static FloatBuffer floats(int n) {
        return ByteBuffer.allocateDirect(4 * n).order(ByteOrder.nativeOrder()).asFloatBuffer();
    }

    private interface Runner { float[] run(Interpreter it); }

    private interface Same { boolean ok(float[] cpu, float[] got); }

    /**
     * Un modelo cargado en un acelerador. {@link #pick} prueba en este
     * teléfono la CPU (con 4 hilos y con todos los núcleos), la GPU, NNAPI y,
     * en chips Qualcomm, la NPU con el delegado QNN (sus librerías vienen en
     * el APK); mide cuánto tarda cada una con una entrada de prueba, descarta
     * la que falla o no da lo mismo que la CPU, y se queda con la más rápida.
     *
     * <p>NNAPI no es "la NPU": es la interfaz genérica de Android (obsoleta
     * desde Android 15) y en muchos teléfonos termina corriendo en la CPU;
     * si tarda lo mismo que la CPU, se avisa.
     *
     * <p>La GPU se prueba aunque el teléfono no figure en la lista de
     * compatibles de TFLite (la lista es vieja). Como un controlador malo
     * puede colgar la app en vez de dar un error, antes de probar cada
     * acelerador se anota "probando"; si al volver a abrir sigue anotado,
     * es que colgó y no se prueba más en esta versión de la app.
     */
    private static final class Engine {
        Interpreter it;
        AutoCloseable delegate;
        String name;
        double ms;

        static Engine pick(Context c, String asset, String label, int threads, Runner run, Same same, StringBuilder note) throws IOException {
            MappedByteBuffer model;
            try (AssetFileDescriptor fd = c.getAssets().openFd(asset);
                 FileInputStream fis = new FileInputStream(fd.getFileDescriptor())) {
                model = fis.getChannel().map(FileChannel.MapMode.READ_ONLY, fd.getStartOffset(), fd.getDeclaredLength());
            }
            SharedPreferences sp = c.getSharedPreferences("xfeat_aceleradores", Context.MODE_PRIVATE);
            String ver = BuildConfig.VERSION_CODE + ":" + asset + ":";
            Engine cpu = new Engine();
            Interpreter.Options o = new Interpreter.Options();
            o.setNumThreads(threads);
            cpu.it = new Interpreter(model, o);
            cpu.name = "CPU (" + threads + " hilos)";
            float[] ref = run.run(cpu.it);
            cpu.ms = time(cpu.it, run);
            note.append(label).append(": CPU ").append(Math.round(cpu.ms)).append(" ms");
            Engine best = cpu;
            // Con todos los núcleos: en chips donde todos son rápidos puede ganarle a 4 hilos
            int cores = Runtime.getRuntime().availableProcessors();
            if (cores > threads) {
                Engine all = new Engine();
                Interpreter.Options oa = new Interpreter.Options();
                oa.setNumThreads(cores);
                all.it = new Interpreter(model, oa);
                all.name = "CPU (" + cores + " hilos)";
                run.run(all.it);
                all.ms = time(all.it, run);
                note.append(" (").append(Math.round(all.ms)).append(" ms con ").append(cores).append(" hilos)");
                if (all.ms < 0.85 * best.ms) best = all;
                else all.close();
            }
            boolean qualcomm = Advisor.vendor(Build.VERSION.SDK_INT >= 31 ? Build.SOC_MANUFACTURER : "", Build.VERSION.SDK_INT >= 31 ? Build.SOC_MODEL : "",
                    Build.HARDWARE, Build.BOARD) == Advisor.Vendor.QUALCOMM;
            for (String kind : new String[]{"GPU", "NNAPI", QNN}) {
                if (kind.equals(QNN) && !qualcomm) continue; // el delegado QNN sólo existe para chips Qualcomm
                String key = ver + kind, state = sp.getString(key, "");
                if (state.equals("probando")) {
                    sp.edit().putString(key, "colgó").apply();
                    state = "colgó";
                }
                if (state.equals("colgó") || state.startsWith("falla")) {
                    note.append(", ").append(kind).append(state.equals("colgó") ? " colgó la app antes (no se usa)" : " no sirve (" + state.substring(5).trim() + ")");
                    continue;
                }
                sp.edit().putString(key, "probando").commit();
                Engine e = new Engine();
                String fail = null;
                try {
                    Interpreter.Options op = new Interpreter.Options();
                    op.setNumThreads(threads); // para las operaciones que el acelerador no hace
                    if (kind.equals("GPU")) {
                        CompatibilityList cl = new CompatibilityList();
                        GpuDelegate g = cl.isDelegateSupportedOnThisDevice() ? new GpuDelegate(cl.getBestOptionsForThisDevice()) : new GpuDelegate();
                        e.delegate = g;
                        op.addDelegate(g);
                        e.name = "GPU";
                    } else if (kind.equals(QNN)) {
                        if (!QnnDelegate.checkCapability(QnnDelegate.Capability.HTP_RUNTIME_FP16))
                            throw new UnsupportedOperationException("este chip no corre modelos de punto flotante en la NPU (HTP FP16)");
                        QnnDelegate.Options qo = new QnnDelegate.Options();
                        qo.setBackendType(QnnDelegate.Options.BackendType.HTP_BACKEND);
                        qo.setHtpPrecision(QnnDelegate.Options.HtpPrecision.HTP_PRECISION_FP16); // los modelos son de punto flotante, sin cuantizar
                        qo.setHtpPerformanceMode(QnnDelegate.Options.HtpPerformanceMode.HTP_PERFORMANCE_BURST);
                        // La parte que corre dentro del chip (libQnnHtpV..Skel.so) se carga desde la carpeta de librerías de la app
                        qo.setSkelLibraryDir(c.getApplicationInfo().nativeLibraryDir);
                        qo.setLogLevel(QnnDelegate.Options.LogLevel.LOG_LEVEL_WARN);
                        QnnDelegate q = new QnnDelegate(qo);
                        e.delegate = q;
                        op.addDelegate(q);
                        e.name = "NPU Qualcomm (QNN)";
                    } else {
                        NnApiDelegate.Options no = new NnApiDelegate.Options();
                        no.setAllowFp16(true);
                        no.setUseNnapiCpu(false); // sin la "CPU de NNAPI": si no hay chip de IA, que no simule uno
                        no.setExecutionPreference(NnApiDelegate.Options.EXECUTION_PREFERENCE_SUSTAINED_SPEED);
                        NnApiDelegate n = new NnApiDelegate(no);
                        e.delegate = n;
                        op.addDelegate(n);
                        e.name = "NNAPI";
                    }
                    e.it = new Interpreter(model, op);
                    float[] got = run.run(e.it);
                    boolean finite = true;
                    for (float v : got) if (Float.isNaN(v) || Float.isInfinite(v)) { finite = false; break; }
                    if (!finite || !same.ok(ref, got)) fail = "no da lo mismo que la CPU";
                    else e.ms = time(e.it, run);
                } catch (RuntimeException | LinkageError ex) {
                    Log.w(TAG, kind + " no disponible para " + asset, ex);
                    String m = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                    // Entero (hasta un tope): el mensaje trae la lista de operaciones que el acelerador no acepta
                    fail = m.length() > MAX_ERROR ? m.substring(0, MAX_ERROR) + "…" : m;
                }
                if (fail != null) {
                    e.close();
                    sp.edit().putString(key, "falla " + fail.replace('\n', ' ')).apply();
                    note.append(", ").append(kind).append(" no sirve (").append(fail.replace('\n', ' ')).append(")");
                    continue;
                }
                sp.edit().putString(key, "ok").apply();
                note.append(", ").append(kind).append(' ').append(Math.round(e.ms)).append(" ms");
                // Mismo tiempo que la CPU: lo más probable es que no esté usando el acelerador
                if (!kind.equals("GPU") && Math.abs(e.ms - cpu.ms) < 0.1 * cpu.ms) note.append(" (igual que la CPU: probablemente no acelera nada)");
                // Se cambia sólo si gana con claridad (15 %)
                if (e.ms < 0.85 * best.ms) {
                    if (best != cpu) best.close();
                    best = e;
                } else e.close();
            }
            if (best != cpu) cpu.close();
            // Lo que haya quedado de la prueba en las salidas no importa: cada uso las vuelve a llenar
            note.append(" → ").append(best.name);
            return best;
        }

        /** Tiempo (ms) de una corrida: la menor de dos, después de la primera (que incluye preparar el acelerador). */
        private static double time(Interpreter it, Runner run) {
            double best = Double.MAX_VALUE;
            for (int k = 0; k < 2; k++) {
                long t0 = System.nanoTime();
                run.run(it);
                best = Math.min(best, (System.nanoTime() - t0) / 1e6);
            }
            return best;
        }

        void close() {
            try {
                if (it != null) it.close();
                if (delegate != null) delegate.close();
            } catch (Exception ex) {
                Log.w(TAG, "cerrar", ex);
            }
        }
    }

    /** Corre XFeat sobre lo que hay en {@code in}; deja las salidas en desc, heat y rel. */
    private void runNet(Interpreter it) {
        in.rewind();
        Map<String, Object> ins = new HashMap<>();
        ins.put("args_0", in);
        Map<String, Object> outs = new HashMap<>();
        outDesc.rewind();
        outHeat.rewind();
        outRel.rewind();
        outs.put("output_0", outDesc);
        outs.put("output_1", outHeat);
        outs.put("output_2", outRel);
        it.runSignature(ins, outs);
        outDesc.rewind();
        outDesc.get(desc);
        outHeat.rewind();
        outHeat.get(heat);
        outRel.rewind();
        outRel.get(rel);
    }

    /** Corre LighterGlue sobre lo que hay en gk0, gk1, gd0 y gd1; deja las salidas en m0, m1 y v0. */
    private void runGlue(Interpreter it) {
        gk0.rewind();
        gk1.rewind();
        gd0.rewind();
        gd1.rewind();
        Map<String, Object> ins = new HashMap<>();
        ins.put("args_0", gk0);
        ins.put("args_1", gk1);
        ins.put("args_2", gd0);
        ins.put("args_3", gd1);
        Map<String, Object> outs = new HashMap<>();
        outM0.rewind();
        outM1.rewind();
        outV.rewind();
        outs.put("output_0", outM0);
        outs.put("output_1", outM1);
        outs.put("output_2", outV);
        it.runSignature(ins, outs);
        outM0.rewind();
        outM0.get(m0);
        outM1.rewind();
        outM1.get(m1);
        outV.rewind();
        outV.get(v0);
    }

    /** Puntos y descriptores de un cuadro. */
    XFeatTracks.Frame detect(int frame, int[] argb, int w, int h) {
        long t0 = System.nanoTime();
        XFeatTracks.input(argb, w, h, image);
        in.rewind();
        in.put(image).rewind();
        runNet(net);
        XFeatTracks.Frame f = XFeatTracks.select(frame, desc, heat, rel, argb, w, h);
        detectMs += (System.nanoTime() - t0) / 1e6;
        detected++;
        return f;
    }

    /** Pares {i, j} entre los puntos de a y b. */
    int[][] match(XFeatTracks.Frame a, XFeatTracks.Frame b) {
        long t0 = System.nanoTime();
        gk0.rewind();
        gk0.put(a.glueXY).rewind();
        gk1.rewind();
        gk1.put(b.glueXY).rewind();
        gd0.rewind();
        gd0.put(a.desc).rewind();
        gd1.rewind();
        gd1.put(b.desc).rewind();
        runGlue(glue);
        int[][] p = XFeatTracks.pairs(a, b, m0, m1, v0);
        matchMs += (System.nanoTime() - t0) / 1e6;
        matched++;
        return p;
    }

    /** Para el diagnóstico: dónde corrió, cuánto tardó y cómo se eligió. */
    String summary() {
        return String.format(Locale.ROOT, "XFeat en %s: %.0f ms por foto (%d fotos) · LighterGlue en %s: %.0f ms por par (%d pares) · "
                        + "elegidos probando en este teléfono (%s)",
                netDelegate, detected == 0 ? 0 : detectMs / detected, detected, glueDelegate, matched == 0 ? 0 : matchMs / matched, matched, choiceNote);
    }

    @Override
    public void close() {
        try {
            net.close();
            glue.close();
            if (accNet != null) accNet.close();
            if (accGlue != null) accGlue.close();
        } catch (Exception e) {
            Log.w(TAG, "cerrar", e);
        }
    }
}
