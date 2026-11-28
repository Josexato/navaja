package com.josexato.navaja.ockham;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import com.josexato.navaja.ockham.core.Session;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Dónde guarda Navaja lo de Ockham: si el interruptor está encendido y la sesión con el servidor. La sesión lleva el
 * token de renovación, así que se guarda cifrada con una llave del Keystore de Android, que no sale del teléfono.
 */
final class Vault {

    private static final String TAG = "Ockham", PREFS = "ockham", ALIAS = "navaja_ockham";

    private Vault() { }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Si Ockham está encendido. Apagado, Navaja no se conecta a ningún servidor. */
    static boolean enabled(Context c) {
        return prefs(c).getBoolean("encendido", false);
    }

    static void setEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean("encendido", on).apply();
    }

    /** El trabajo que se pidió y todavía no se vio terminar, para retomarlo al volver a abrir la pantalla. */
    static String pendingJob(Context c) {
        return prefs(c).getString("trabajo", "");
    }

    static void setPendingJob(Context c, String id) {
        prefs(c).edit().putString("trabajo", id == null ? "" : id).apply();
    }

    /** La sesión guardada, o una vacía si no hay o si no se pudo descifrar. */
    static Session load(Context c) {
        String blob = prefs(c).getString("sesion", "");
        if (blob.isEmpty()) return new Session();
        try {
            byte[] raw = Base64.decode(blob, Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Arrays.copyOf(raw, 12)));
            return Session.fromText(new String(cipher.doFinal(raw, 12, raw.length - 12), StandardCharsets.UTF_8));
        } catch (GeneralSecurityException | RuntimeException e) {
            // Pasa al restaurar la app en otro teléfono: la llave no viaja. Se empieza de nuevo.
            Log.w(TAG, "no se pudo leer la sesión guardada", e);
            return new Session();
        }
    }

    static void save(Context c, Session s) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = cipher.getIV(), enc = cipher.doFinal(s.toText().getBytes(StandardCharsets.UTF_8));
            byte[] raw = Arrays.copyOf(iv, iv.length + enc.length);
            System.arraycopy(enc, 0, raw, iv.length, enc.length);
            prefs(c).edit().putString("sesion", Base64.encodeToString(raw, Base64.NO_WRAP)).apply();
        } catch (GeneralSecurityException | RuntimeException e) {
            Log.w(TAG, "no se pudo guardar la sesión", e);
        }
    }

    private static Key key() throws GeneralSecurityException {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        try {
            ks.load(null);
        } catch (java.io.IOException e) {
            throw new GeneralSecurityException(e);
        }
        if (!ks.containsAlias(ALIAS)) {
            KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            g.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).build());
            g.generateKey();
        }
        return ks.getKey(ALIAS, null);
    }
}
