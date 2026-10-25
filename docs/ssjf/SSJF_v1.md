# SSJF v1 — Simple Signature JSON Format

**Estado:** borrador 1.1 ([cambios](#10-historial-de-versiones)) · **Implementación de referencia:** Navaja
(`app/src/main/java/com/josexato/navaja/sign/core/`) · **Verificador
independiente:** [`verificar_ssjf.py`](verificar_ssjf.py)

Las palabras DEBE, NO DEBE, DEBERÍA y PUEDE se usan como en RFC 2119.

## 1. Objetivo

Una firma manuscrita sobre un PDF acompañada de un QR que permite a
**cualquiera** comprobar, sin servicios centrales:

1. qué bytes exactos se firmaron (integridad),
2. que los firmó el dueño de una clave (autoría),
3. entre qué momentos ocurrió (anclas de tiempo),

y al **firmante** responder "¿firmé esto?" mediante un historial encadenado.
Ningún dato del documento sale del dispositivo: a servicios externos sólo
viajan resúmenes SHA-256.

## 2. Criptografía

- Resumen: SHA-256, en hexadecimal en minúsculas (64 caracteres).
- Firma: ECDSA sobre P-256 (secp256r1) con SHA-256 (JOSE "ES256").
  Formato de firma: **r‖s crudo de 64 bytes** (cada entero, big-endian, 32 bytes).
- Clave pública: punto **comprimido SEC1 de 33 bytes** (`02|03 ‖ x`).
- Identificador de clave `kid` = base64url sin relleno de los **primeros 12
  bytes** de SHA-256(clave comprimida) → 16 caracteres.
- base64url: RFC 4648 §5, sin `=`.

## 3. Contenido del QR

```
[URL "#"] "SSJF1" ";h=" h0 ";n=" n0 ";t=" t [";b=" altura "-" hash32] [";r=" revisión] ";k=" kid ";s=" firma
```

| Campo | Obligatorio | Formato |
|---|---|---|
| `h` | sí | SHA-256 hex del documento antes de firmar |
| `n` | sí | largo en bytes de ese documento (decimal) |
| `t` | sí | segundos Unix UTC (decimal), declarado por el firmante |
| `b` | no | altura decimal del último bloque Bitcoin conocido al firmar, `-`, y los primeros 32 caracteres hex de su hash |
| `r` | v1.1: sí | revisión del formato (decimal). `2` = el anexo DEBE llevar `seal_sig` (§4). Ausente = 1.0 |
| `k` | sí | `kid` (§2) |
| `s` | sí | firma (§2) en base64url |

- Los campos DEBEN aparecer en ese orden y no hay otros en v1.x.
- **Mensaje firmado** = bytes UTF-8 desde `SSJF1` hasta, sin incluir, `;s=`.
- El prefijo URL es OPCIONAL y no está firmado. DEBERÍA apuntar a una guía de
  verificación; los datos van en el fragmento (`#…`), que los navegadores no
  envían al servidor.
- Un verificador DEBE rechazar un QR cuyo texto no se reproduzca exactamente
  a partir de los campos interpretados.
- El QR DEBERÍA codificarse en modo byte con corrección M y con un tamaño
  impreso de al menos 22 mm.

## 4. PDF firmado

Sea `D0` el archivo recibido (`n0` bytes, `h0` = SHA-256(D0)). Un firmante DEBE:

1. **No modificar D0.** Todo se agrega con actualizaciones incrementales
   (ISO 32000-1 §7.5.6), de modo que `D0` es prefijo exacto del resultado.
2. **Actualización 1 — sello visible**: firma manuscrita, el QR y,
   RECOMENDADO, una línea con la URL de la guía. Resultado `D1`
   (`n1` bytes, `h1` = SHA-256(D1)).
3. **Firma del sello** (v1.1, OBLIGATORIA con `r=2`): con la misma clave,
   firmar los bytes UTF-8 de

   ```
   SSJF1-SEAL;h0=<h0>;h1=<h1>;n1=<n1>;k=<kid>
   ```

   y guardar la firma (r‖s, base64url) en el anexo como `seal_sig`. El QR
   firma `D0`; esta segunda firma avala `D1` (la firma visible, el QR y
   cualquier otro cambio de esa actualización). No hay circularidad: se
   guarda fuera de `D1`.
4. **Actualización 2 — anexo**: un stream sin filtro con diccionario
   `<< /Type /SSJF /V 1 /Length … >>`, precedido en su línea anterior por el
   comentario PDF `%SSJF-anexo-v1`, y referenciado desde el catálogo en el
   array `/SSJF`. El contenido es JSON UTF-8:

```json
{
  "ssjf": 1,
  "qr": "<texto completo del QR>",
  "h0": "<hex>", "n0": 123,
  "h1": "<hex>", "n1": 456,
  "pk": "<clave pública comprimida, base64url>",
  "seal_sig": "<firma del sello, base64url>",
  "btc": {"height": 921345, "hash": "<hash completo del bloque>"},
  "rfc3161": {"url": "https://freetsa.org/tsr", "tsr": "<TimeStampResp DER, base64>"},
  "opentimestamps": {"calendar": "https://…", "ots": "<archivo .ots, base64>"},
  "guia": "<URL>"
}
```

`btc`, `rfc3161` y `opentimestamps` son OPCIONALES. Los sellos de tiempo se
calculan sobre **h1** (el documento ya sellado): `h1` no puede ir en el QR
porque el QR forma parte de `D1`.

El anexo NO DEBE contener datos personales (nombre de archivo, ubicación,
comentarios).

Varias firmas se encadenan: la segunda firma toma como `D0` el archivo final de
la primera.

## 5. Verificación

Para cada anexo encontrado en el archivo `F` (buscando `%SSJF-anexo-v1`):

1. Interpretar el QR (§3). `qr.h == anexo.h0` y `qr.n == anexo.n0`.
2. **Documento**: SHA-256(F[0:n0]) == h0. Si no → *no es el documento firmado*.
3. **Sello**: SHA-256(F[0:n1]) == h1 y el anexo está después de `n1`. Si no → *sello alterado*.
   Además, con la clave del paso 4: si hay `seal_sig`, DEBE verificar sobre
   `SSJF1-SEAL;…` (si no → *sello alterado*: alguien reutilizó el QR con otra
   `D1`). Si falta y el QR tiene `r=2` → *sello alterado* (el anexo fue
   reemplazado). Si falta y el QR no tiene `r` (firma 1.0), el verificador
   DEBE advertir que sólo `D0` está garantizado: lo agregado al sellar
   podría contener otros cambios.
4. **Firma**: obtener la clave (de un llavero confiable o de `anexo.pk` si
   `kid` coincide) y verificar `s`. Si falla → *firma falsa*.
5. **Dueño**: la clave sólo identifica a alguien si se conoce por otra vía.
6. **Cambios posteriores**: el anexo termina en el `%%EOF` de su
   actualización (`fin`). Si `len(F) > fin` y lo que sigue no es otra firma
   SSJF cuyo `n0 == fin`, hay **cambios sin firmar**: la firma vale sólo para
   `F[0:n1]`, que el verificador DEBERÍA ofrecer extraer.
7. **Tiempo**: no antes del bloque `b` (el hash de un bloque es
   impredecible); no después de la hora del sello RFC 3161 y de la
   atestación Bitcoin de OpenTimestamps sobre `h1`.

Con sólo el QR (papel) se pueden hacer los pasos 1, 4 y 5, y comparar `h`
con un archivo candidato.

Un verificador DEBE distinguir tres resultados: **verificada**, **inválida** y
**válida con advertencias / no comprobada** (cambios posteriores, firma 1.0 sin
sello firmado, clave de dueño desconocido o firma que no pudo comprobarse). Un
resultado no comprobado NUNCA se informa como éxito.

## 6. Historial del firmante

JSON Lines, sólo-agregar. Cada línea es un objeto con, en este orden:

```
v, type ("firma" | "sello"), seq (1, 2, …), prev, t, <campos públicos>, [commit], [priv, salt]
```

- `prev` = SHA-256 hex de la **forma canónica pública** de la entrada anterior
  (64 ceros para la primera).
- **Forma canónica pública** = el objeto sin `priv` ni `salt`, serializado
  como JSON compacto (sin espacios), claves en el orden escrito, sin escapar
  caracteres no ASCII, sólo enteros como números. (Python:
  `json.dumps(o, separators=(",",":"), ensure_ascii=False)`.)
- Campos públicos de `firma`: `h0, n0, h1, n1, h2, n2, qr, pk, btc, rfc3161, ots`
  (`h2`/`n2` = archivo final con el anexo).
- `priv` = objeto de textos (p. ej. `file`, `comment`, `lat`, `lon`, `acc_m`,
  `page`), sin caracteres de control salvo `\n`.
- `salt` = 16 bytes aleatorios en base64url.
  `commit` = SHA-256 hex de (`salt` ‖ JSON compacto de `priv`).
- Una entrada `sello` agrega pruebas obtenidas después, sin tocar la firma:
  `ref` (seq de la firma), `h1`, `ots` (archivo .ots ya anclado, base64),
  `btc_height`, `btc_hash` y `btc_time` (bloque comprobado). Se agrega cuando el
  calendario OpenTimestamps incluyó el compromiso en un bloque y la raíz Merkle
  que resulta de la prueba coincide con la del bloque real (en el orden de bytes
  interno; los exploradores la muestran invertida).

La copia **pública** (sin `priv` ni `salt`) verifica su cadena sola; quien
tenga la copia completa puede demostrar los datos privados revelando `priv` y
`salt`. Los respaldos PUEDEN ir a cualquier almacenamiento (archivo local,
WebDAV, S3/OBS, FTP, correo…): el formato no depende del destino.

## 7. Cambio de clave

La clave anterior autoriza a la nueva y la nueva prueba que existe:

```
posesión     SSJF1-POP;newpk=<pk nueva>;t=<unix>                                  firmado con la NUEVA
autorización SSJF1-ROT;oldpk=<pk vieja>;newpk=<pk nueva>;t=<unix>;pop=<t posesión>;r=<motivo>
                                                                                  firmado con la VIEJA
```

Claves comprimidas y firmas r‖s en base64url (§2); `r` = `[a-z0-9-]{1,32}`
(`rotacion`, `telefono-nuevo`…). La declaración JSON (`ssjf_cambio_clave: 1`) y
la entrada `clave` del historial llevan: `old_kid, old_pk, new_kid, new_pk, ct
(= t), pop_t, reason, sig_old, sig_new`.

- Quien confía en la clave vieja PUEDE confiar en la nueva si ambas firmas son válidas.
- Las firmas hechas con la clave vieja siguen siendo del mismo firmante.
- Una firma con la clave vieja cuyo `t` es posterior al cambio DEBERÍA
  marcarse como sospechosa (alguien podría estar usando el dispositivo anterior).
- Para pasar a otro dispositivo (claves no exportables): el nuevo envía la
  línea de posesión con `;s=<firma>` añadido (una *solicitud*), el anterior la
  autoriza y deja de firmar, y el historial completo se importa en el nuevo.

## 8. Seguridad y límites

- La clave privada DEBERÍA vivir en hardware (Android Keystore / StrongBox,
  Secure Enclave) y exigir autenticación del usuario por firma.
- Perder la clave no invalida firmas pasadas; para cambiarla, ver §7.
- `t` lo declara el firmante; las anclas `b`, RFC 3161 y OpenTimestamps lo acotan.
- Lectores PDF que reescriben el archivo completo ("Guardar como") destruyen
  la propiedad de prefijo: verifica siempre el archivo recibido, no una copia
  re-guardada.
- SSJF no reemplaza a PAdES/firma electrónica calificada; es una prueba
  técnica abierta y autoverificable.

## 9. Trabajos relacionados

PAdES / firmas PDF (ISO 32000 §12.8), RFC 3161, OpenTimestamps, Certificate
Transparency (RFC 9162), Sigstore/Rekor, certificados QR firmados (EU DCC).

## 10. Historial de versiones

- **1.1** — Firma del sello (`seal_sig`) sobre `h1` y campo `r=2` en el QR que
  la hace obligatoria; tres resultados de verificación. Motivo: en 1.0 sólo
  `D0` estaba firmado, y alguien podía reutilizar un QR legítimo con otra
  actualización visible (otra apariencia o contenido) y otro anexo.
- **1.0** — Versión inicial.
