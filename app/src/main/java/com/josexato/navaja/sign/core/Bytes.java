package com.josexato.navaja.sign.core;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Resúmenes (SHA-256/MD5), hexadecimal y base64url sin relleno. */
public final class Bytes {

    private Bytes() { }

    public static byte[] sha256(byte[] b) { return digest("SHA-256", b, 0, b.length); }

    /** SHA-256 de los primeros {@code n} bytes (la "versión" de un PDF con actualizaciones incrementales). */
    public static byte[] sha256(byte[] b, int n) { return digest("SHA-256", b, 0, n); }

    public static byte[] md5(byte[] b) { return digest("MD5", b, 0, b.length); }

    private static byte[] digest(String alg, byte[] b, int off, int len) {
        try {
            MessageDigest md = MessageDigest.getInstance(alg);
            md.update(b, off, len);
            return md.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // SHA-256 y MD5 existen en toda JVM y en Android
        }
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    public static String hex(byte[] b) {
        char[] c = new char[b.length * 2];
        for (int i = 0; i < b.length; i++) {
            c[2 * i] = HEX[(b[i] >> 4) & 15];
            c[2 * i + 1] = HEX[b[i] & 15];
        }
        return new String(c);
    }

    public static byte[] unhex(String s) {
        if (s.length() % 2 != 0) throw new IllegalArgumentException("hex de largo impar");
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) {
            int hi = Character.digit(s.charAt(2 * i), 16), lo = Character.digit(s.charAt(2 * i + 1), 16);
            if (hi < 0 || lo < 0) throw new IllegalArgumentException("hex inválido");
            b[i] = (byte) (hi << 4 | lo);
        }
        return b;
    }

    public static boolean isHex(String s, int len) {
        if (s == null || s.length() != len) return false;
        for (int i = 0; i < len; i++) if (Character.digit(s.charAt(i), 16) < 0 || Character.isUpperCase(s.charAt(i))) return false;
        return true;
    }

    // java.util.Base64 es API 26+ (minSdk 24) y android.util.Base64 no existe en los tests: base64 propio.
    private static final String STD = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    private static final String URL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

    public static String b64url(byte[] b) { return enc(b, URL, false); }

    public static byte[] unb64url(String s) { return dec(s, URL); }

    public static String b64(byte[] b) { return enc(b, STD, true); }

    public static byte[] unb64(String s) { return dec(s, STD); }

    private static String enc(byte[] b, String abc, boolean pad) {
        StringBuilder sb = new StringBuilder((b.length + 2) / 3 * 4);
        for (int i = 0; i < b.length; i += 3) {
            int n = (b[i] & 255) << 16 | (i + 1 < b.length ? (b[i + 1] & 255) << 8 : 0) | (i + 2 < b.length ? b[i + 2] & 255 : 0);
            int chars = Math.min(4, (b.length - i) * 4 / 3 + 1);
            for (int k = 0; k < 4; k++) {
                if (k < chars) sb.append(abc.charAt(n >> (18 - 6 * k) & 63));
                else if (pad) sb.append('=');
            }
        }
        return sb.toString();
    }

    private static byte[] dec(String s, String abc) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(s.length() * 3 / 4);
        int acc = 0, bits = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '=' || c == '\n' || c == '\r' || c == ' ') continue;
            int v = abc.indexOf(c);
            if (v < 0) throw new IllegalArgumentException("base64 inválido: '" + c + "'");
            acc = acc << 6 | v;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                o.write(acc >> bits & 255);
            }
        }
        return o.toByteArray();
    }

    public static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) o.write(p, 0, p.length);
        return o.toByteArray();
    }

    /** Busca {@code needle} en {@code hay} desde {@code from}; -1 si no está. */
    public static int indexOf(byte[] hay, byte[] needle, int from) {
        outer:
        for (int i = Math.max(0, from); i <= hay.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) if (hay[i + j] != needle[j]) continue outer;
            return i;
        }
        return -1;
    }

    public static int lastIndexOf(byte[] hay, byte[] needle, int from) {
        outer:
        for (int i = Math.min(from, hay.length - needle.length); i >= 0; i--) {
            for (int j = 0; j < needle.length; j++) if (hay[i + j] != needle[j]) continue outer;
            return i;
        }
        return -1;
    }
}
