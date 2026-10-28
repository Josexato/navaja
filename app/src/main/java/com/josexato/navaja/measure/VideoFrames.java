package com.josexato.navaja.measure;

import android.content.Context;
import android.graphics.Rect;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Lee un video cuadro a cuadro con el decodificador del teléfono
 * (MediaCodec), toma {@code fps} cuadros por segundo, los reduce a
 * {@code longSide} píxeles de lado mayor y los entrega ya rotados como se
 * vieron al grabar (ARGB).
 */
final class VideoFrames {

    private VideoFrames() { }

    interface Sink {
        /** @return false para cancelar */
        boolean frame(int[] argb, int w, int h, long timeUs, long durationUs);
    }

    static void read(Context ctx, Uri uri, double fps, int longSide, Sink sink) throws IOException {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec codec = null;
        try {
            ex.setDataSource(ctx, uri, null);
            int track = -1;
            MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    track = i;
                    fmt = f;
                    break;
                }
            }
            if (track < 0) throw new IOException("El archivo no tiene video");
            ex.selectTrack(track);
            int rotation = fmt.containsKey(MediaFormat.KEY_ROTATION) ? fmt.getInteger(MediaFormat.KEY_ROTATION) : 0;
            long duration = fmt.containsKey(MediaFormat.KEY_DURATION) ? fmt.getLong(MediaFormat.KEY_DURATION) : 0;
            codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
            fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            codec.configure(fmt, null, null, 0);
            codec.start();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inDone = false;
            long next = 0, step = Math.round(1e6 / fps);
            while (true) {
                if (!inDone) {
                    int ii = codec.dequeueInputBuffer(10_000);
                    if (ii >= 0) {
                        ByteBuffer b = codec.getInputBuffer(ii);
                        int n = b == null ? -1 : ex.readSampleData(b, 0);
                        if (n < 0) {
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inDone = true;
                        } else {
                            codec.queueInputBuffer(ii, 0, n, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }
                int oi = codec.dequeueOutputBuffer(info, 10_000);
                if (oi >= 0) {
                    boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    // Grilla fija de 1/fps (con ±1/4 de tolerancia): da fps cuadros por segundo sea el video de 24, 30,
                    // 60 o 120 cuadros por segundo (antes, "50 ms después del último" daba 20 por segundo a 60 o 120)
                    if (info.size > 0 && info.presentationTimeUs >= next - step / 4) {
                        Image img = codec.getOutputImage(oi);
                        if (img != null) {
                            int[][] dims = new int[1][];
                            int[] argb = toArgb(img, longSide, rotation, dims);
                            img.close();
                            while (next <= info.presentationTimeUs + step / 4) next += step;
                            if (!sink.frame(argb, dims[0][0], dims[0][1], info.presentationTimeUs, duration)) {
                                codec.releaseOutputBuffer(oi, false);
                                return;
                            }
                        }
                    }
                    codec.releaseOutputBuffer(oi, false);
                    if (eos) break;
                }
            }
        } finally {
            if (codec != null) {
                try {
                    codec.stop();
                } catch (IllegalStateException ignored) {
                    // ya detenido
                }
                codec.release();
            }
            ex.release();
        }
    }

    /** YUV 4:2:0 → ARGB reducido (salto entero) y rotado. */
    private static int[] toArgb(Image img, int longSide, int rotation, int[][] outDims) {
        Rect crop = img.getCropRect();
        int iw = crop.width(), ih = crop.height();
        int s = Math.max(1, (int) Math.ceil(Math.max(iw, ih) / (double) longSide));
        int w = iw / s, h = ih / s;
        Image.Plane[] pl = img.getPlanes();
        ByteBuffer yb = pl[0].getBuffer(), ub = pl[1].getBuffer(), vb = pl[2].getBuffer();
        int yr = pl[0].getRowStride(), yp = pl[0].getPixelStride();
        int ur = pl[1].getRowStride(), up = pl[1].getPixelStride();
        int vr = pl[2].getRowStride(), vp = pl[2].getPixelStride();
        int[] src = new int[w * h];
        for (int y = 0; y < h; y++) {
            int sy = crop.top + y * s;
            for (int x = 0; x < w; x++) {
                int sx = crop.left + x * s;
                int yy = yb.get(sy * yr + sx * yp) & 255;
                int u = (ub.get((sy / 2) * ur + (sx / 2) * up) & 255) - 128;
                int v = (vb.get((sy / 2) * vr + (sx / 2) * vp) & 255) - 128;
                int r = clamp(yy + (int) (1.402f * v)), g = clamp(yy - (int) (0.344f * u + 0.714f * v)), b = clamp(yy + (int) (1.772f * u));
                src[y * w + x] = 0xFF000000 | r << 16 | g << 8 | b;
            }
        }
        rotation = ((rotation % 360) + 360) % 360;
        if (rotation == 0) {
            outDims[0] = new int[]{w, h};
            return src;
        }
        int ow = rotation == 180 ? w : h, oh = rotation == 180 ? h : w;
        int[] dst = new int[w * h];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int dx, dy;
                if (rotation == 90) { dx = h - 1 - y; dy = x; }
                else if (rotation == 180) { dx = w - 1 - x; dy = h - 1 - y; }
                else { dx = y; dy = w - 1 - x; }
                dst[dy * ow + dx] = src[y * w + x];
            }
        outDims[0] = new int[]{ow, oh};
        return dst;
    }

    private static int clamp(int v) { return v < 0 ? 0 : Math.min(255, v); }
}
