package com.josexato.navaja.sign.core.storage;

import java.io.IOException;

/**
 * Destino de respaldo del historial de firmas. El estándar no impone
 * ninguno: cualquier lugar donde se pueda escribir un archivo sirve
 * (carpeta local, USB, Google Drive/OneDrive/Dropbox vía el selector de
 * archivos, WebDAV/Nextcloud/NAS, S3/Huawei OBS/MinIO/R2, FTP…).
 * Sólo se suben resúmenes y metadatos públicos, salvo que el usuario elija
 * incluir los privados.
 */
public interface Backend {

    /** Descripción legible del destino (sin contraseñas). */
    String describe();

    /** Escribe (o reemplaza) {@code path}, relativo a la raíz configurada; usa "/" como separador. */
    void put(String path, byte[] data, String contentType) throws IOException;
}
