package com.josexato.navaja.sign.core;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECFieldFp;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.EllipticCurve;
import java.util.Arrays;

/**
 * ECDSA P-256 (secp256r1) con SHA-256: claves públicas en formato
 * comprimido de 33 bytes, firmas en formato "crudo" r‖s de 64 bytes (como
 * ES256 de JOSE) y conversión desde/hacia DER, que es lo que entregan JCA y
 * el Android Keystore.
 */
public final class Ecdsa {

    private Ecdsa() { }

    static final BigInteger P = new BigInteger("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16);
    static final BigInteger A = P.subtract(BigInteger.valueOf(3));
    static final BigInteger B = new BigInteger("5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b", 16);
    static final BigInteger GX = new BigInteger("6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296", 16);
    static final BigInteger GY = new BigInteger("4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5", 16);
    static final BigInteger N = new BigInteger("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", 16);
    public static final ECParameterSpec P256 = new ECParameterSpec(
            new EllipticCurve(new ECFieldFp(P), A, B), new ECPoint(GX, GY), N, 1);

    public static byte[] compress(PublicKey pk) {
        ECPoint w = ((ECPublicKey) pk).getW();
        byte[] out = new byte[33];
        out[0] = (byte) (w.getAffineY().testBit(0) ? 3 : 2);
        put32(out, 1, w.getAffineX());
        return out;
    }

    public static PublicKey decompress(byte[] c) throws GeneralSecurityException {
        if (c.length != 33 || (c[0] != 2 && c[0] != 3)) throw new GeneralSecurityException("Clave pública comprimida inválida");
        BigInteger x = new BigInteger(1, Arrays.copyOfRange(c, 1, 33));
        if (x.compareTo(P) >= 0) throw new GeneralSecurityException("x fuera del campo");
        BigInteger rhs = x.pow(3).add(A.multiply(x)).add(B).mod(P);
        BigInteger y = rhs.modPow(P.add(BigInteger.ONE).shiftRight(2), P); // p ≡ 3 (mod 4)
        if (!y.multiply(y).mod(P).equals(rhs)) throw new GeneralSecurityException("El punto no está en la curva");
        if (y.testBit(0) != (c[0] == 3)) y = P.subtract(y);
        return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(x, y), P256));
    }

    /** Identificador corto de la clave: base64url de los primeros 12 bytes de SHA-256(clave comprimida). */
    public static String keyId(byte[] compressed) {
        return Bytes.b64url(Arrays.copyOf(Bytes.sha256(compressed), 12));
    }

    public static boolean verify(PublicKey pk, byte[] msg, byte[] raw) {
        try {
            Signature s = Signature.getInstance("SHA256withECDSA");
            s.initVerify(pk);
            s.update(msg);
            return s.verify(rawToDer(raw));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    public static byte[] derToRaw(byte[] der) {
        // SEQUENCE { INTEGER r, INTEGER s }
        int p = 0;
        if (der[p++] != 0x30) throw new IllegalArgumentException("DER inválido");
        int len = der[p++] & 0xFF;
        if (len == 0x81) p++;
        byte[] out = new byte[64];
        for (int k = 0; k < 2; k++) {
            if (der[p++] != 0x02) throw new IllegalArgumentException("DER inválido");
            int l = der[p++] & 0xFF;
            BigInteger v = new BigInteger(1, Arrays.copyOfRange(der, p, p + l));
            put32(out, 32 * k, v);
            p += l;
        }
        return out;
    }

    public static byte[] rawToDer(byte[] raw) {
        if (raw.length != 64) throw new IllegalArgumentException("La firma debe tener 64 bytes");
        byte[] r = new BigInteger(1, Arrays.copyOfRange(raw, 0, 32)).toByteArray();
        byte[] s = new BigInteger(1, Arrays.copyOfRange(raw, 32, 64)).toByteArray();
        int len = 2 + r.length + 2 + s.length;
        byte[] out = new byte[2 + len];
        out[0] = 0x30;
        out[1] = (byte) len;
        out[2] = 0x02;
        out[3] = (byte) r.length;
        System.arraycopy(r, 0, out, 4, r.length);
        out[4 + r.length] = 0x02;
        out[5 + r.length] = (byte) s.length;
        System.arraycopy(s, 0, out, 6 + r.length, s.length);
        return out;
    }

    private static void put32(byte[] out, int off, BigInteger v) {
        byte[] b = v.toByteArray();
        int n = Math.min(b.length, 32);
        System.arraycopy(b, b.length - n, out, off + 32 - n, n);
    }
}
