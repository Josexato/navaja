package com.josexato.navaja.sign;

import android.app.KeyguardManager;
import android.content.Context;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyInfo;
import android.security.keystore.KeyProperties;
import android.security.keystore.UserNotAuthenticatedException;

import com.josexato.navaja.sign.core.Ecdsa;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.spec.ECGenParameterSpec;

/**
 * Clave de firma en el Android Keystore: EC P-256, generada dentro del
 * hardware seguro (StrongBox si existe, si no el TEE) y NO exportable. Si
 * el teléfono tiene bloqueo de pantalla, cada firma exige huella/rostro o
 * PIN (la clave queda habilitada 30 s tras autenticar).
 */
final class Keys {

    private Keys() { }

    static final String PREFIX = "navaja_ssjf_", FIRST = PREFIX + "1";
    /** Clave activa (las anteriores quedan en el Keystore para reconocer tus firmas viejas). */
    static volatile String alias = FIRST;
    static final int AUTH_SECONDS = 30;

    static KeyStore store() throws GeneralSecurityException {
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            return ks;
        } catch (java.io.IOException e) {
            throw new GeneralSecurityException(e);
        }
    }

    static boolean exists() {
        try {
            return store().containsAlias(alias);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    static boolean deviceSecure(Context c) {
        KeyguardManager km = (KeyguardManager) c.getSystemService(Context.KEYGUARD_SERVICE);
        return km != null && km.isDeviceSecure();
    }

    /** Crea la clave (si ya existe no hace nada). Devuelve true si quedó en StrongBox. */
    static boolean create(Context c) throws GeneralSecurityException {
        if (exists()) return false;
        return create(c, alias);
    }

    static boolean create(Context c, String name) throws GeneralSecurityException {
        boolean auth = deviceSecure(c);
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                generate(name, auth, true);
                return true;
            } catch (GeneralSecurityException | RuntimeException noStrongBox) {
                // StrongBoxUnavailableException u otra: se usa el TEE
            }
        }
        generate(name, auth, false);
        return false;
    }

    /** Siguiente nombre libre: navaja_ssjf_2, _3… */
    static String nextAlias() throws GeneralSecurityException {
        KeyStore ks = store();
        int n = 1;
        while (ks.containsAlias(PREFIX + n)) n++;
        return PREFIX + n;
    }

    @SuppressWarnings("deprecation") // setUserAuthenticationValidityDurationSeconds: la vía en API < 30
    private static void generate(String name, boolean auth, boolean strongBox) throws GeneralSecurityException {
        KeyGenParameterSpec.Builder b = new KeyGenParameterSpec.Builder(name, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256);
        if (auth) {
            b.setUserAuthenticationRequired(true);
            if (Build.VERSION.SDK_INT >= 30) {
                b.setUserAuthenticationParameters(AUTH_SECONDS,
                        KeyProperties.AUTH_BIOMETRIC_STRONG | KeyProperties.AUTH_DEVICE_CREDENTIAL);
            } else {
                b.setUserAuthenticationValidityDurationSeconds(AUTH_SECONDS);
            }
            // Si cambias el bloqueo de pantalla la clave sigue valiendo (perderla sería perder la identidad).
            b.setInvalidatedByBiometricEnrollment(false);
        }
        if (strongBox && Build.VERSION.SDK_INT >= 28) b.setIsStrongBoxBacked(true);
        KeyPairGenerator g = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
        g.initialize(b.build());
        g.generateKeyPair();
    }

    static byte[] publicKey() throws GeneralSecurityException { return publicKey(alias); }

    static byte[] publicKey(String name) throws GeneralSecurityException {
        Certificate cert = store().getCertificate(name);
        if (cert == null) throw new GeneralSecurityException("No hay clave de firma");
        PublicKey pk = cert.getPublicKey();
        return Ecdsa.compress(pk);
    }

    /** Firma SHA256withECDSA (DER). Lanza UserNotAuthenticatedException si hace falta autenticar. */
    static byte[] sign(byte[] msg) throws GeneralSecurityException { return sign(alias, msg); }

    static byte[] sign(String name, byte[] msg) throws GeneralSecurityException {
        PrivateKey sk = (PrivateKey) store().getKey(name, null);
        if (sk == null) throw new GeneralSecurityException("No hay clave de firma");
        Signature s = Signature.getInstance("SHA256withECDSA");
        s.initSign(sk);
        s.update(msg);
        return s.sign();
    }

    static boolean needsAuth(Throwable t) {
        for (; t != null; t = t.getCause()) if (t instanceof UserNotAuthenticatedException) return true;
        return false;
    }

    /** Descripción del almacenamiento de la clave (para mostrar). */
    @SuppressWarnings("deprecation") // isInsideSecureHardware: única vía en API < 31
    static String protection() {
        try {
            PrivateKey sk = (PrivateKey) store().getKey(alias, null);
            KeyInfo info = KeyFactory.getInstance(sk.getAlgorithm(), "AndroidKeyStore").getKeySpec(sk, KeyInfo.class);
            String where;
            if (Build.VERSION.SDK_INT >= 31) {
                int lvl = info.getSecurityLevel();
                where = lvl == KeyProperties.SECURITY_LEVEL_STRONGBOX ? "StrongBox (chip seguro)"
                        : lvl == KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT ? "TEE (hardware seguro)" : "software";
            } else {
                where = info.isInsideSecureHardware() ? "hardware seguro" : "software";
            }
            return where + (info.isUserAuthenticationRequired() ? " · pide huella/PIN" : " · sin bloqueo de pantalla");
        } catch (Exception e) {
            return "desconocida";
        }
    }
}
