package com.josexato.navaja.sign;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import com.josexato.navaja.pantonera.core.Json;
import com.josexato.navaja.sign.core.Bytes;
import com.josexato.navaja.sign.core.Ledger;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Almacenamiento local de la firma: historial (JSONL en el almacenamiento
 * interno de la app, excluido del respaldo automático de Google), copias de
 * los PDF firmados y la configuración del respaldo, cuyas contraseñas se
 * cifran con una clave AES del Keystore.
 */
final class Store {

    private final Context ctx;
    final File dir, ledgerFile, pdfDir;

    Store(Context c) {
        ctx = c.getApplicationContext();
        dir = new File(ctx.getFilesDir(), "firmas");
        pdfDir = new File(dir, "pdf");
        //noinspection ResultOfMethodCallIgnored
        pdfDir.mkdirs();
        ledgerFile = new File(dir, "historial.jsonl");
    }

    // -------------------------------------------------------------- historial

    synchronized Ledger loadLedger() throws IOException {
        if (!ledgerFile.exists()) return new Ledger();
        try {
            return Ledger.parse(new String(read(ledgerFile), StandardCharsets.UTF_8));
        } catch (Json.ParseException e) {
            throw new IOException("Historial ilegible: " + e.getMessage());
        }
    }

    /** Escritura atómica (archivo temporal + rename): un corte de energía no deja el historial a medias. */
    synchronized void saveLedger(Ledger l) throws IOException {
        writeAtomic(ledgerFile, l.toJsonl(true).getBytes(StandardCharsets.UTF_8));
    }

    File pdfCopy(long seq) { return new File(pdfDir, String.format(java.util.Locale.US, "%06d.pdf", seq)); }

    static void writeAtomic(File f, byte[] data) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream o = new FileOutputStream(tmp)) {
            o.write(data);
            o.getFD().sync();
        }
        if (!tmp.renameTo(f)) throw new IOException("No se pudo reemplazar " + f.getName());
    }

    static byte[] read(File f) throws IOException {
        // java.nio.file es API 26+ (minSdk 24).
        return com.josexato.navaja.sign.core.Http.readAll(new java.io.FileInputStream(f), Integer.MAX_VALUE);
    }

    // --------------------------------------------------------- configuración

    private SharedPreferences prefs() { return ctx.getSharedPreferences("ssjf", Context.MODE_PRIVATE); }

    /** Configuración del respaldo (tipo, URL, usuario, contraseña…), descifrada. */
    @SuppressWarnings("unchecked")
    Map<String, String> backupConfig() {
        Map<String, String> out = new LinkedHashMap<>();
        String enc = prefs().getString("backup", null);
        if (enc == null) return out;
        try {
            Object o = Json.parse(new String(decrypt(Bytes.unb64(enc)), StandardCharsets.UTF_8));
            for (Map.Entry<String, Object> e : ((Map<String, Object>) o).entrySet()) out.put(e.getKey(), String.valueOf(e.getValue()));
        } catch (Exception e) {
            // Clave del Keystore perdida (p. ej. restauración): se vuelve a configurar.
        }
        return out;
    }

    void saveBackupConfig(Map<String, String> cfg) throws GeneralSecurityException {
        byte[] plain = Json.writeCompact(new LinkedHashMap<String, Object>(cfg)).getBytes(StandardCharsets.UTF_8);
        prefs().edit().putString("backup", Bytes.b64(encrypt(plain))).apply();
    }

    String pref(String k, String def) { return prefs().getString(k, def); }

    void setPref(String k, String v) { prefs().edit().putString(k, v).apply(); }

    private static final String CFG_ALIAS = "navaja_ssjf_cfg";

    private static Key cfgKey() throws GeneralSecurityException {
        KeyStore ks = Keys.store();
        if (!ks.containsAlias(CFG_ALIAS)) {
            KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            g.init(new KeyGenParameterSpec.Builder(CFG_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).build());
            g.generateKey();
        }
        return ks.getKey(CFG_ALIAS, null);
    }

    private static byte[] encrypt(byte[] plain) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, cfgKey());
        return Bytes.concat(c.getIV(), c.doFinal(plain));
    }

    private static byte[] decrypt(byte[] blob) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, cfgKey(), new GCMParameterSpec(128, Arrays.copyOf(blob, 12)));
        return c.doFinal(blob, 12, blob.length - 12);
    }
}
