package com.josexato.navaja.measure;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.util.Log;

import com.josexato.navaja.measure.core.XFeatTracks;

import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.gpu.CompatibilityList;
import org.tensorflow.lite.gpu.GpuDelegate;

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
 * modelos/LICENCIA-xfeat.txt), sin internet. Intenta la GPU y, si el
 * teléfono no la admite o falla, usa la CPU (XNNPACK). Anota cuál usó y
 * cuánto tarda, para el diagnóstico.
 */
final class XFeat implements Closeable {

    private static final String TAG = "XFeat";
    private static final String BACKBONE = "modelos/xfeat.tflite", GLUE = "modelos/lighterglue.tflite";
    private static final int N = XFeatTracks.N;

    private final Interpreter net, glue;
    private final GpuDelegate gpuNet, gpuGlue;
    /** "GPU" o "CPU (n hilos)", para cada modelo. */
    final String netDelegate, glueDelegate;

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
        Object[] a = open(c, BACKBONE, threads), b = open(c, GLUE, threads);
        net = (Interpreter) a[0];
        gpuNet = (GpuDelegate) a[1];
        netDelegate = (String) a[2];
        glue = (Interpreter) b[0];
        gpuGlue = (GpuDelegate) b[1];
        glueDelegate = (String) b[2];
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
    }

    private static FloatBuffer floats(int n) {
        return ByteBuffer.allocateDirect(4 * n).order(ByteOrder.nativeOrder()).asFloatBuffer();
    }

    /** {intérprete, delegado GPU o null, descripción}: primero con GPU; si no se puede, en CPU. */
    private static Object[] open(Context c, String asset, int threads) throws IOException {
        MappedByteBuffer model;
        try (AssetFileDescriptor fd = c.getAssets().openFd(asset);
             FileInputStream fis = new FileInputStream(fd.getFileDescriptor())) {
            model = fis.getChannel().map(FileChannel.MapMode.READ_ONLY, fd.getStartOffset(), fd.getDeclaredLength());
        }
        try {
            CompatibilityList cl = new CompatibilityList();
            if (cl.isDelegateSupportedOnThisDevice()) {
                GpuDelegate g = new GpuDelegate(cl.getBestOptionsForThisDevice());
                try {
                    Interpreter.Options o = new Interpreter.Options();
                    o.addDelegate(g);
                    o.setNumThreads(threads); // para las operaciones que la GPU no hace
                    return new Object[]{new Interpreter(model, o), g, "GPU"};
                } catch (RuntimeException e) {
                    g.close();
                    throw e;
                }
            }
        } catch (RuntimeException | LinkageError e) {
            Log.w(TAG, "GPU no disponible para " + asset, e);
        }
        Interpreter.Options o = new Interpreter.Options();
        o.setNumThreads(threads);
        return new Object[]{new Interpreter(model, o), null, "CPU (" + threads + " hilos)"};
    }

    /** Puntos y descriptores de un cuadro. */
    XFeatTracks.Frame detect(int frame, int[] argb, int w, int h) {
        long t0 = System.nanoTime();
        XFeatTracks.input(argb, w, h, image);
        in.rewind();
        in.put(image).rewind();
        Map<String, Object> ins = new HashMap<>();
        ins.put("args_0", in);
        Map<String, Object> outs = new HashMap<>();
        outDesc.rewind();
        outHeat.rewind();
        outRel.rewind();
        outs.put("output_0", outDesc);
        outs.put("output_1", outHeat);
        outs.put("output_2", outRel);
        net.runSignature(ins, outs);
        outDesc.rewind();
        outDesc.get(desc);
        outHeat.rewind();
        outHeat.get(heat);
        outRel.rewind();
        outRel.get(rel);
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
        glue.runSignature(ins, outs);
        outM0.rewind();
        outM0.get(m0);
        outM1.rewind();
        outM1.get(m1);
        outV.rewind();
        outV.get(v0);
        int[][] p = XFeatTracks.pairs(a, b, m0, m1, v0);
        matchMs += (System.nanoTime() - t0) / 1e6;
        matched++;
        return p;
    }

    /** Para el diagnóstico: dónde corrió y cuánto tardó. */
    String summary() {
        return String.format(Locale.ROOT, "XFeat en %s: %.0f ms por foto (%d fotos) · LighterGlue en %s: %.0f ms por par (%d pares)",
                netDelegate, detected == 0 ? 0 : detectMs / detected, detected, glueDelegate, matched == 0 ? 0 : matchMs / matched, matched);
    }

    @Override
    public void close() {
        net.close();
        glue.close();
        if (gpuNet != null) gpuNet.close();
        if (gpuGlue != null) gpuGlue.close();
    }
}
