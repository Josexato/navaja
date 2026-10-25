package com.josexato.navaja.sign.core;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Codificador QR (ISO/IEC 18004) en Java puro, sólo modo byte: versión
 * automática (1–40), Reed-Solomon, intercalado de bloques y la máscara de
 * menor penalización. Sigue el algoritmo de referencia de Nayuki.
 * Los tests lo verifican decodificando con ZXing.
 */
public final class QrCode {

    public enum Ecc {
        L(1), M(0), Q(3), H(2);
        final int formatBits;

        Ecc(int f) { formatBits = f; }
    }

    public final int version, size, mask;
    public final Ecc ecc;
    private final boolean[][] modules;   // [y][x], true = oscuro
    private final boolean[][] function;

    public boolean dark(int x, int y) { return modules[y][x]; }

    public static QrCode encode(String text, Ecc ecc) {
        return encode(text.getBytes(StandardCharsets.UTF_8), ecc);
    }

    public static QrCode encode(byte[] data, Ecc ecc) {
        int ver = 1;
        for (; ; ver++) {
            if (ver > 40) throw new IllegalArgumentException("Demasiados datos para un QR (" + data.length + " bytes)");
            int bits = 4 + (ver <= 9 ? 8 : 16) + data.length * 8;
            if (bits <= numDataCodewords(ver, ecc) * 8) break;
        }
        int cap = numDataCodewords(ver, ecc) * 8;
        Bits bb = new Bits();
        bb.add(0x4, 4);
        bb.add(data.length, ver <= 9 ? 8 : 16);
        for (byte b : data) bb.add(b & 0xFF, 8);
        bb.add(0, Math.min(4, cap - bb.len));
        bb.add(0, (8 - bb.len % 8) % 8);
        for (int pad = 0xEC; bb.len < cap; pad ^= 0xEC ^ 0x11) bb.add(pad, 8);
        return new QrCode(ver, ecc, bb.toBytes());
    }

    private QrCode(int ver, Ecc ecc, byte[] dataCodewords) {
        version = ver;
        this.ecc = ecc;
        size = ver * 4 + 17;
        modules = new boolean[size][size];
        function = new boolean[size][size];
        drawFunctionPatterns();
        drawCodewords(addEccAndInterleave(dataCodewords));
        int best = 0;
        long minPenalty = Long.MAX_VALUE;
        for (int m = 0; m < 8; m++) {
            applyMask(m);
            drawFormatBits(m);
            long p = penalty();
            if (p < minPenalty) { best = m; minPenalty = p; }
            applyMask(m); // XOR: deshace
        }
        mask = best;
        applyMask(best);
        drawFormatBits(best);
    }

    // --------------------------------------------------------------- patrones

    private void drawFunctionPatterns() {
        for (int i = 0; i < size; i++) {
            setF(6, i, i % 2 == 0);
            setF(i, 6, i % 2 == 0);
        }
        finder(3, 3);
        finder(size - 4, 3);
        finder(3, size - 4);
        int[] al = alignmentPositions();
        int n = al.length;
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++)
                if (!(i == 0 && j == 0 || i == 0 && j == n - 1 || i == n - 1 && j == 0)) alignment(al[i], al[j]);
        drawFormatBits(0);
        if (version >= 7) {
            int rem = version;
            for (int i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
            int bits = version << 12 | rem;
            for (int i = 0; i < 18; i++) {
                boolean b = ((bits >>> i) & 1) != 0;
                int a = size - 11 + i % 3, c = i / 3;
                setF(a, c, b);
                setF(c, a, b);
            }
        }
    }

    private void finder(int x, int y) {
        for (int dy = -4; dy <= 4; dy++)
            for (int dx = -4; dx <= 4; dx++) {
                int d = Math.max(Math.abs(dx), Math.abs(dy)), xx = x + dx, yy = y + dy;
                if (xx >= 0 && xx < size && yy >= 0 && yy < size) setF(xx, yy, d != 2 && d != 4);
            }
    }

    private void alignment(int x, int y) {
        for (int dy = -2; dy <= 2; dy++)
            for (int dx = -2; dx <= 2; dx++) setF(x + dx, y + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
    }

    private int[] alignmentPositions() {
        if (version == 1) return new int[0];
        int num = version / 7 + 2;
        int step = version == 32 ? 26 : (version * 4 + num * 2 + 1) / (num * 2 - 2) * 2;
        int[] r = new int[num];
        r[0] = 6;
        for (int i = num - 1, pos = size - 7; i >= 1; i--, pos -= step) r[i] = pos;
        return r;
    }

    private void drawFormatBits(int msk) {
        int data = ecc.formatBits << 3 | msk;
        int rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        int bits = (data << 10 | rem) ^ 0x5412;
        for (int i = 0; i <= 5; i++) setF(8, i, bit(bits, i));
        setF(8, 7, bit(bits, 6));
        setF(8, 8, bit(bits, 7));
        setF(7, 8, bit(bits, 8));
        for (int i = 9; i < 15; i++) setF(14 - i, 8, bit(bits, i));
        for (int i = 0; i < 8; i++) setF(size - 1 - i, 8, bit(bits, i));
        for (int i = 8; i < 15; i++) setF(8, size - 15 + i, bit(bits, i));
        setF(8, size - 8, true);
    }

    private static boolean bit(int x, int i) { return ((x >>> i) & 1) != 0; }

    private void setF(int x, int y, boolean dark) {
        modules[y][x] = dark;
        function[y][x] = true;
    }

    // ------------------------------------------------------------- datos y RS

    private byte[] addEccAndInterleave(byte[] data) {
        int blocks = NUM_BLOCKS[ecc.ordinal()][version], eccLen = ECC_PER_BLOCK[ecc.ordinal()][version];
        int raw = numRawDataModules(version) / 8;
        int numShort = blocks - raw % blocks, shortLen = raw / blocks;
        byte[][] bl = new byte[blocks][];
        byte[] div = rsDivisor(eccLen);
        for (int i = 0, k = 0; i < blocks; i++) {
            int len = shortLen - eccLen + (i < numShort ? 0 : 1);
            byte[] dat = new byte[len];
            System.arraycopy(data, k, dat, 0, len);
            k += len;
            byte[] e = rsRemainder(dat, div);
            byte[] b = new byte[shortLen + 1];
            // Los bloques cortos llevan un hueco en la posición shortLen-eccLen (se salta al intercalar).
            System.arraycopy(dat, 0, b, 0, len);
            System.arraycopy(e, 0, b, b.length - eccLen, eccLen);
            bl[i] = b;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(raw);
        for (int i = 0; i < shortLen + 1; i++)
            for (int j = 0; j < blocks; j++)
                if (i != shortLen - eccLen || j >= numShort) out.write(bl[j][i]);
        return out.toByteArray();
    }

    private static byte[] rsDivisor(int degree) {
        byte[] r = new byte[degree];
        r[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < r.length; j++) {
                r[j] = (byte) gfMul(r[j] & 0xFF, root);
                if (j + 1 < r.length) r[j] ^= r[j + 1];
            }
            root = gfMul(root, 0x02);
        }
        return r;
    }

    private static byte[] rsRemainder(byte[] data, byte[] div) {
        byte[] r = new byte[div.length];
        for (byte b : data) {
            int f = (b ^ r[0]) & 0xFF;
            System.arraycopy(r, 1, r, 0, r.length - 1);
            r[r.length - 1] = 0;
            for (int i = 0; i < r.length; i++) r[i] ^= (byte) gfMul(div[i] & 0xFF, f);
        }
        return r;
    }

    private static int gfMul(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z;
    }

    private void drawCodewords(byte[] data) {
        int i = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < size; vert++)
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean up = ((right + 1) & 2) == 0;
                    int y = up ? size - 1 - vert : vert;
                    if (!function[y][x] && i < data.length * 8) {
                        modules[y][x] = bit(data[i >>> 3], 7 - (i & 7));
                        i++;
                    }
                }
        }
    }

    private void applyMask(int m) {
        for (int y = 0; y < size; y++)
            for (int x = 0; x < size; x++) {
                if (function[y][x]) continue;
                boolean inv;
                switch (m) {
                    case 0: inv = (x + y) % 2 == 0; break;
                    case 1: inv = y % 2 == 0; break;
                    case 2: inv = x % 3 == 0; break;
                    case 3: inv = (x + y) % 3 == 0; break;
                    case 4: inv = (x / 3 + y / 2) % 2 == 0; break;
                    case 5: inv = x * y % 2 + x * y % 3 == 0; break;
                    case 6: inv = (x * y % 2 + x * y % 3) % 2 == 0; break;
                    default: inv = ((x + y) % 2 + x * y % 3) % 2 == 0;
                }
                modules[y][x] ^= inv;
            }
    }

    /** Penalización estándar (reglas 1–4); sólo elige la máscara, cualquier máscara produce un QR válido. */
    private long penalty() {
        long p = 0;
        for (int pass = 0; pass < 2; pass++) {
            for (int a = 0; a < size; a++) {
                int run = 0;
                boolean prev = false;
                int window = 0; // últimos 11 módulos como bits, para el patrón 1:1:3:1:1
                for (int b = 0; b < size; b++) {
                    boolean c = pass == 0 ? modules[a][b] : modules[b][a];
                    if (b > 0 && c == prev) {
                        run++;
                        if (run == 5) p += 3;
                        else if (run > 5) p++;
                    } else {
                        run = 1;
                        prev = c;
                    }
                    window = ((window << 1) | (c ? 1 : 0)) & 0x7FF;
                    if (b >= 10 && (window == 0x5D || window == 0x5D0)) p += 40;
                }
            }
        }
        for (int y = 0; y < size - 1; y++)
            for (int x = 0; x < size - 1; x++) {
                boolean c = modules[y][x];
                if (c == modules[y][x + 1] && c == modules[y + 1][x] && c == modules[y + 1][x + 1]) p += 3;
            }
        int darkCount = 0;
        for (boolean[] row : modules) for (boolean c : row) if (c) darkCount++;
        int total = size * size;
        int k = (Math.abs(darkCount * 20 - total * 10) + total - 1) / total - 1;
        return p + Math.max(0, k) * 10L;
    }

    // ------------------------------------------------------------- capacidad

    static int numRawDataModules(int ver) {
        int r = (16 * ver + 128) * ver + 64;
        if (ver >= 2) {
            int n = ver / 7 + 2;
            r -= (25 * n - 10) * n - 55;
            if (ver >= 7) r -= 36;
        }
        return r;
    }

    static int numDataCodewords(int ver, Ecc e) {
        return numRawDataModules(ver) / 8 - ECC_PER_BLOCK[e.ordinal()][ver] * NUM_BLOCKS[e.ordinal()][ver];
    }

    private static final class Bits {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        int cur, len;

        void add(int val, int n) {
            for (int i = n - 1; i >= 0; i--) {
                cur = cur << 1 | ((val >>> i) & 1);
                len++;
                if (len % 8 == 0) { out.write(cur); cur = 0; }
            }
        }

        byte[] toBytes() { return out.toByteArray(); }
    }

    // Orden de filas: L, M, Q, H (igual que Ecc.ordinal()).
    private static final byte[][] ECC_PER_BLOCK = {
        {-1, 7, 10, 15, 20, 26, 18, 20, 24, 30, 18, 20, 24, 26, 30, 22, 24, 28, 30, 28, 28, 28, 28, 30, 30, 26, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30},
        {-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26, 26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28},
        {-1, 13, 22, 18, 26, 18, 24, 18, 22, 20, 24, 28, 26, 24, 20, 30, 24, 28, 28, 26, 30, 28, 30, 30, 30, 30, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30},
        {-1, 17, 28, 22, 16, 22, 28, 26, 26, 24, 28, 24, 28, 22, 24, 24, 30, 28, 28, 26, 28, 30, 24, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30},
    };
    private static final byte[][] NUM_BLOCKS = {
        {-1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4, 4, 4, 4, 4, 6, 6, 6, 6, 7, 8, 8, 9, 9, 10, 12, 12, 12, 13, 14, 15, 16, 17, 18, 19, 19, 20, 21, 22, 24, 25},
        {-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16, 17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49},
        {-1, 1, 1, 2, 2, 4, 4, 6, 6, 8, 8, 8, 10, 12, 16, 12, 17, 16, 18, 21, 20, 23, 23, 25, 27, 29, 34, 34, 35, 38, 40, 43, 45, 48, 51, 53, 56, 59, 62, 65, 68},
        {-1, 1, 1, 2, 4, 4, 4, 5, 6, 8, 8, 11, 11, 16, 16, 18, 16, 19, 21, 25, 25, 25, 34, 30, 32, 35, 37, 40, 42, 45, 48, 51, 54, 57, 60, 63, 66, 70, 74, 77, 81},
    };
}
