package com.josexato.navaja.pantonera.core;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/** Codificador PNG RGB de 8 bits en Java puro (para texturas sintéticas y tests). */
public final class Png {

    private Png() { }

    public static byte[] encode(int[] argb, int w, int h) {
        byte[] raw = new byte[h * (1 + 3 * w)];
        int k = 0;
        for (int y = 0; y < h; y++) {
            raw[k++] = 0; // filtro "None"
            for (int x = 0; x < w; x++) {
                int c = argb[y * w + x];
                raw[k++] = (byte) (c >> 16);
                raw[k++] = (byte) (c >> 8);
                raw[k++] = (byte) c;
            }
        }
        Deflater def = new Deflater(Deflater.BEST_COMPRESSION);
        def.setInput(raw);
        def.finish();
        ByteArrayOutputStream z = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        while (!def.finished()) z.write(buf, 0, def.deflate(buf));
        def.end();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'}, 0, 8);
        byte[] ihdr = new byte[13];
        putInt(ihdr, 0, w);
        putInt(ihdr, 4, h);
        ihdr[8] = 8;  // bits por canal
        ihdr[9] = 2;  // RGB
        chunk(out, "IHDR", ihdr);
        chunk(out, "IDAT", z.toByteArray());
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
        byte[] len = new byte[4];
        putInt(len, 0, data.length);
        out.write(len, 0, 4);
        byte[] t = type.getBytes(StandardCharsets.US_ASCII);
        out.write(t, 0, 4);
        out.write(data, 0, data.length);
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(data);
        byte[] c = new byte[4];
        putInt(c, 0, (int) crc.getValue());
        out.write(c, 0, 4);
    }

    private static void putInt(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 24);
        b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8);
        b[off + 3] = (byte) v;
    }
}
