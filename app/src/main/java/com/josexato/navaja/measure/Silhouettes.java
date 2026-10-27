package com.josexato.navaja.measure;

import android.content.Context;
import android.content.res.AssetFileDescriptor;

import com.josexato.navaja.measure.core.SilhouetteIO;

import org.tensorflow.lite.Interpreter;

import java.io.Closeable;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

/**
 * Silueta del objeto en un cuadro con el modelo MagicTouch (MediaPipe,
 * Apache 2.0), que viene dentro del APK: corre en el teléfono, sin internet,
 * y la imagen no sale de ahí.
 */
final class Silhouettes implements Closeable {

    private static final String MODEL = "modelos/magic_touch.tflite";

    private final Interpreter tflite;
    private final FloatBuffer in, out;

    Silhouettes(Context c) throws IOException {
        MappedByteBuffer model;
        try (AssetFileDescriptor fd = c.getAssets().openFd(MODEL);
             FileInputStream fis = new FileInputStream(fd.getFileDescriptor())) {
            model = fis.getChannel().map(FileChannel.MapMode.READ_ONLY, fd.getStartOffset(), fd.getDeclaredLength());
        }
        Interpreter.Options o = new Interpreter.Options();
        o.setNumThreads(Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors())));
        tflite = new Interpreter(model, o);
        int n = SilhouetteIO.SIZE * SilhouetteIO.SIZE;
        in = ByteBuffer.allocateDirect(4 * n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        out = ByteBuffer.allocateDirect(4 * n).order(ByteOrder.nativeOrder()).asFloatBuffer();
    }

    /**
     * Silueta de {@code mw}×{@code mh} del objeto que contiene los puntos
     * {@code seeds} (x, y intercalados, en píxeles del cuadro), o null si no
     * hay puntos o la silueta no los contiene.
     */
    byte[] segment(int[] argb, int w, int h, float[] seeds, int mw, int mh) {
        double[] tap = SilhouetteIO.median(seeds);
        if (tap == null) return null;
        SilhouetteIO.input(argb, w, h, tap[0], tap[1], in);
        out.rewind();
        tflite.run(in, out);
        out.rewind();
        return SilhouetteIO.mask(out, mw, mh, seeds, w, h);
    }

    @Override
    public void close() { tflite.close(); }
}
