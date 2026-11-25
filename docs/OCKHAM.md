# Ockham: Navaja con servidor propio

Estado: **diseño, sin código**. Este documento reúne lo investigado el 25/11/2026 y la arquitectura propuesta, con las
decisiones que siguen abiertas. Las fuentes van al final.

## Qué es

- **Navaja** es la app tal como funciona hoy: todo ocurre en el teléfono y nada sale de él.
- **Ockham** es un interruptor en la pantalla principal de Navaja. Encendido, la app puede mandar trabajo pesado a un
  **servidor personal** del dueño del teléfono. Ese servidor guarda las capturas en una base de datos pequeña y lanza el
  procesamiento en plataformas de pods de IA (Runpod y parecidas).
- La estructura del servidor es **estándar y abierta**: cualquiera puede leer qué hace, montarlo igual y comprobar que
  cumple el protocolo.

El nombre viene de la navaja de Ockham: no agregar nada que no haga falta. Es también la regla de diseño, y por ella
la primera versión quedó bastante más chica que el primer borrador (ver «Cómo se llegó a este diseño»).

## Las preguntas y la respuesta corta

| Pregunta | Respuesta |
|---|---|
| ¿Cómo se comunica la app con el servidor? | HTTPS y JSON, con un protocolo pequeño descrito en OpenAPI: emparejar, subir archivos, crear trabajos, consultar su estado, bajar resultados. |
| ¿Cómo se activa? | En la app, con el interruptor y un código QR que muestra el servidor. El servidor es chico y está siempre prendido; lo caro (la GPU) se enciende sólo mientras dura un trabajo. |
| ¿Qué capacidades necesita el servidor? | Recibir archivos grandes con reanudación, una cola de trabajos que sobreviva a un reinicio, una base de datos (SQLite), una bóveda para las llaves de las plataformas de IA, un adaptador hacia esas plataformas y un registro de todo lo que hizo. |
| ¿Con qué seguridad? | TLS con certificado público, un credencial por teléfono que se puede revocar, llaves de IA que nunca llegan al teléfono, y recetas fijas: el teléfono elige qué receta correr, no puede mandar código. |
| ¿Es un n8n? | Toma sus ideas, pero no lo usa como puerta de entrada. Más adelante n8n puede conectarse **detrás** de Ockham como un adaptador más. |

## Por qué no poner n8n directamente

Fue la primera idea y se descartó como puerta de entrada por cuatro razones comprobadas en su documentación:

1. **No es código abierto.** Su licencia («Sustainable Use License») permite el uso propio y personal, pero limita la
   distribución. Sirve para uno mismo; no sirve como base de un estándar que cualquiera pueda montar y auditar.
2. **Archivos grandes.** El cuerpo de un pedido está limitado a 16 MiB por omisión (200 MiB en formularios) y los
   binarios se guardan en memoria salvo que se configure el disco. Una captura con video pesa cientos de MB.
3. **Trabajos largos.** Si el webhook espera a que el flujo termine, los proxies cortan a los 60–100 segundos. La
   solución habitual es justo la que se propone aquí: responder de inmediato con un identificador y consultar después.
4. **Un solo secreto.** La autenticación del webhook es una cabecera o contraseña compartida: no hay un credencial por
   teléfono que se pueda revocar por separado.

Lo que sí se copia de n8n y parecidos (Windmill, Kestra, Temporal):

- Una dirección estable por flujo de trabajo («receta»).
- Asíncrono por omisión: se devuelve un identificador y el estado se consulta aparte.
- Un registro guardado de cada ejecución, que permite retomar después de una caída.
- Una bóveda de credenciales cifrada con una llave que no está en la base de datos.
- Archivos siempre a disco, nunca en memoria ni dentro del JSON.

## Piezas

```
 Teléfono (Navaja)                Servidor personal (Ockham)                  Plataforma de IA
 ┌──────────────────┐   HTTPS   ┌────────────────────────────────┐   HTTPS   ┌──────────────────┐
 │ interruptor      │──────────▶│ Caddy (TLS)                    │──────────▶│ Runpod serverless│
 │ emparejar (QR)   │           │  └ API Ockham                  │  pedido   │                  │
 │ subir captura    │           │      ├ dispositivos            │           │ el pod baja la   │
 │ ver trabajos     │◀──────────│      ├ subidas (tus, a disco)  │◀──────────│ entrada y sube el│
 │ bajar resultado  │           │      ├ trabajos + cola         │ enlaces de│ resultado        │
 └──────────────────┘           │      ├ recetas (archivos)      │ un trabajo└──────────────────┘
                                │      ├ bóveda de llaves        │
                                │      └ registro de auditoría   │
                                │  SQLite + carpeta de archivos  │
                                └────────────────────────────────┘
```

- **Un solo proceso** sirve la API y ejecuta la cola. SQLite en modo WAL alcanza para un servidor de un solo dueño.
- **Un solo dueño, un solo espacio.** Todos los teléfonos emparejados ven lo mismo. El identificador del dispositivo
  sirve para revocar y para el registro, no para separar datos.
- **Sin almacén de objetos aparte.** Los archivos van a una carpeta. MinIO, la opción habitual, quedó archivado y sin
  mantenimiento en abril de 2026; no se justifica otro servicio.
- **Caddy** delante, porque saca y renueva solo el certificado de Let's Encrypt.
- Todo se levanta con un `docker compose up`.

## Recetas: lo que se parece a n8n

Una **receta** es un archivo en el servidor que describe un trabajo:

- nombre y descripción (lo que la app muestra);
- qué entradas pide (por ejemplo «una captura de Navaja») y qué parámetros acepta, con su esquema JSON;
- qué adaptador la ejecuta y con qué configuración (por ejemplo, el *endpoint* de Runpod que corre video2mesh, con la
  imagen fijada por su huella);
- **qué archivos de salida produce**, con nombre y tamaño máximo;
- límites: tiempo máximo y presupuesto.

Dos reglas que sostienen la seguridad:

1. **Las recetas sólo se cambian en el servidor** (son archivos). La API que mira a internet no puede crear ni
   modificar recetas. Un teléfono robado puede pedir trabajos, pero no puede hacer que el servidor ejecute otra cosa.
2. **Los parámetros se validan** contra el esquema de la receta antes de aceptar el trabajo, y nunca se pegan dentro
   de una dirección ni de una cabecera.

La app pregunta `GET /v1/recipes` y muestra las recetas como opciones. Agregar una capacidad nueva es agregar un
archivo en el servidor; la app no cambia.

### Adaptadores

Todos cumplen la misma interfaz: `enviar → id`, `estado(id)`, `cancelar(id)`. Los resultados no pasan por el
adaptador: los sube el propio trabajo (ver «Cómo viajan los archivos»).

| Adaptador | Etapa | Para qué |
|---|---|---|
| `local` | 1 | Tareas livianas en el propio servidor. Sirve para probar todo sin GPU. |
| `runpod-serverless` | 2 | El caso principal: GPU que arranca desde cero y se cobra por segundo. |
| `webhook` | después | n8n, Node-RED o cualquier servicio HTTP: dirección fija en la receta, sin redirecciones y con un secreto por trabajo en la respuesta. |
| `replicate`, `runpod-pod` | si hacen falta | Replicate sigue el mismo patrón. Los pods que se prenden y apagan a mano piden otra interfaz (crear, esperar, apagar, destruir) y siguen cobrando el disco apagados. |

Lo que la documentación de Runpod obliga a tener en cuenta:

- `POST /run` devuelve un id; los estados son `IN_QUEUE`, `IN_PROGRESS`, `COMPLETED`, `FAILED`, `CANCELLED`,
  `TIMED_OUT`.
- **Reintentar `/run` puede crear un segundo trabajo cobrado**, y no hay llave de idempotencia.
- El tiempo máximo de ejecución es de **10 minutos por omisión**: la receta tiene que mandar su propio límite
  (`executionTimeout`) y el tiempo de vida del pedido (`ttl`).
- El resultado que guarda Runpod se borra a los 30 minutos y el pedido admite pocos MB (10 según su guía; confirmar al
  implementar). Por eso los archivos nunca viajan dentro del pedido.
- Se puede crear una llave **restringida a un solo endpoint**.
- Con cero trabajadores activos no se paga en reposo; a cambio hay arranque en frío. Se cobra por segundo desde que el
  trabajador arranca hasta que se detiene, incluida la carga del modelo.

## Cómo se activa

Hay tres «activar» distintos:

1. **Activar Ockham en la app.** Se enciende el interruptor. Si no hay servidor emparejado, la app abre la cámara para
   leer el QR del servidor. Apagado, ninguna parte de Ockham corre.
2. **Tener el servidor disponible.** El servidor es liviano (no usa GPU) y queda siempre prendido: un VPS barato o un
   mini PC en casa. No se «despierta» desde la app.
3. **Encender la GPU.** Lo hace el servidor, sólo cuando hay un trabajo, y se apaga sola al terminar.

## Emparejar un teléfono

1. En el servidor, el dueño corre `ockham emparejar`. Sale un QR con la dirección del servidor y un **código de un
   solo uso** (128 bits al azar) que vence en 10 minutos.
2. El QR sólo se lee **desde el interruptor de Ockham**, nunca desde otro lector de la app. La app muestra la
   dirección y pide confirmar, y **no reemplaza en silencio** un servidor ya emparejado.
3. La app canjea el código por el credencial del dispositivo. El código queda gastado.
4. El dueño ve la lista de dispositivos en el servidor y puede **revocar** uno; desde ese momento sus pedidos se
   rechazan.

El QR nunca lleva un secreto duradero. (Nextcloud pone la contraseña de aplicación dentro del QR; aquí se evita.)

## Seguridad

### Transporte

- **Recomendado: HTTPS público con Caddy y Let's Encrypt.** Es TLS estándar, ningún tercero ve el contenido y funciona
  con datos móviles sin instalar nada más. Pide un dominio y los puertos 80/443 abiertos.
- **Para después: Tailscale/WireGuard.** No expone ningún puerto, pero el teléfono necesita la VPN activa y los pods de
  IA no pueden alcanzar el servidor para bajar y subir archivos.
- **No recomendado: Cloudflare Tunnel.** Es cómodo, pero el tráfico se descifra en Cloudflare: contradice la idea de un
  servidor auditable de punta a punta.
- **Sin fijar certificados y sin certificados propios en la primera versión.** Google desaconseja el *pinning* en apps
  Android, y aceptar una huella que viene en el QR le permitiría a un QR malicioso saltarse la validación.

### Quién es el que llama

Cada pedido lleva el credencial del dispositivo. Se evaluaron tres formas:

| Forma | A favor | En contra |
|---|---|---|
| **Token por dispositivo** (RFC 6750) | Lo más simple de programar y de auditar. | Si alguien lo copia, lo puede reusar hasta que se revoque. |
| **TLS mutuo** | La llave no sale del teléfono. | Se rompe detrás de proxies que terminan TLS; Android lo documenta poco. |
| **Pedidos firmados** (RFC 9421) | La llave no sale del teléfono y atraviesa proxies. | Más código en los dos lados y más formas de equivocarse. |

**Decisión:** token por dispositivo, 256 bits al azar. El servidor guarda sólo su hash; el teléfono lo guarda cifrado
con una llave del Keystore. Si más adelante se quiere firma con llave del teléfono, se vuelve a emparejar: con pocos
teléfonos cuesta un minuto y evita cargar ahora con código que no se usa.

### Qué puede hacer un teléfono

- Subir archivos, pedir trabajos de las recetas existentes, ver y bajar resultados, borrar.
- **No puede:** cambiar recetas, ver ni cambiar llaves, emparejar ni revocar otros teléfonos.
- La administración no pasa por la API de internet: se hace entrando al servidor.

### Teléfono robado

El cifrado con Keystore no protege un teléfono desbloqueado. Lo que limita el daño:

- **Borrado en dos tiempos:** lo que la app borra va a una papelera del servidor durante unos días.
- **Presupuesto bajo por omisión**, definido en el servidor, no en la app.
- **Revocar** exige entrar al servidor. Queda como decisión abierta si conviene además un «código del dueño» en papel
  para revocar desde cualquier navegador.

### Las llaves de las plataformas de IA

- Viven sólo en el servidor, en una bóveda cifrada cuya llave está fuera de la base de datos.
- Nunca viajan al teléfono ni aparecen en respuestas o registros.
- **Lo que la bóveda protege y lo que no:** protege una copia de respaldo o una base de datos filtrada. No protege
  contra quien toma el servidor entero, porque la llave está en el mismo disco.

### Límites contra el abuso y contra la cuenta sorpresa

- Presupuesto diario y por trabajo, **en segundos de GPU** multiplicados por la tarifa configurada. Runpod no entrega
  el costo por trabajo; sí el tiempo de ejecución.
- Máximo de trabajos simultáneos y tiempo máximo por receta.
- Tope de tamaño por archivo y de espacio total.
- Límite de intentos en el emparejamiento.
- En Runpod, además, el propio tope de gasto de la cuenta.

### Cómo viajan los archivos

El servidor le pasa al pod **enlaces de un solo trabajo**: uno para bajar la entrada y uno por cada salida que la
receta declara. Hay **un solo camino** para los resultados: el pod los sube al servidor; la respuesta de la plataforma
sólo trae hashes y datos chicos.

Esos enlaces son la parte más delicada del diseño, porque son una vía de escritura abierta a internet. Reglas:

- Llevan un secreto al azar, valen sólo para ese trabajo y **dejan de valer cuando el trabajo termina**.
- El nombre del archivo lo pone **el servidor**, tomado de la receta; lo que mande el pod no se usa como ruta.
- Cada salida tiene tamaño máximo y se puede completar una sola vez.
- El programa del pod reintenta la subida si el servidor no responde, y borra la entrada al terminar.

**Lo que hay que decir sin rodeos:** la plataforma de IA ve las capturas sin cifrar, y el registro de auditoría termina
en la puerta de la plataforma. Ockham permite auditar el servidor propio, no a Runpod.

### El servidor comprometido

Las capturas están sin cifrar en el disco del servidor. Quien toma el VPS las ve. Es el mismo riesgo de cualquier
servicio autoalojado; se reduce con retención corta (las entradas se borran solas a los pocos días) y no se elimina.

### Registro de auditoría

Cada acción agrega una línea a un archivo aparte de la base de datos: qué dispositivo, qué receta, con qué
parámetros, el hash de la entrada y de la salida, a qué plataforma se mandó, cuánto tardó y cuántos segundos de GPU
usó. Borrar un trabajo borra sus archivos, no su línea: la línea sólo guarda hashes, no contenido.

### Qué significa «auditable» aquí

1. El protocolo está escrito (OpenAPI y esquemas JSON) y versionado.
2. El servidor es código abierto y pequeño.
3. Una **prueba de conformidad**, escrita junto con el servidor, que cualquiera corre contra una instalación para
   comprobar que cumple el protocolo.
4. Sin telemetría: el servidor sólo habla con el teléfono y con las plataformas que el dueño configuró.

## El protocolo, en resumen

Todas las rutas llevan el prefijo `/v1`. Una versión nueva sólo agrega campos; lo que rompe compatibilidad va a `/v2`.
Los errores usan el formato estándar de problemas HTTP (RFC 9457), con un texto que la app le puede mostrar al usuario.

| Operación | Para qué |
|---|---|
| `GET /.well-known/ockham` | Versiones del protocolo y estado del servidor (incluido disco lleno). No pide credencial. |
| `POST /v1/pair` | Canjea el código del QR por el credencial del dispositivo. |
| `GET /v1/recipes` | Lista de recetas con el esquema de sus parámetros. |
| `POST /v1/uploads` + `PATCH`/`HEAD` | Subida reanudable con el protocolo **tus 1.0**. El SHA-256 del archivo completo va en los metadatos de la subida. Una subida sin usar caduca. |
| `PUT /v1/jobs/{id}` | Crea un trabajo que nombra la receta, las subidas y los parámetros. El id lo genera la app (UUID): repetir el mismo pedido no crea otro trabajo; repetirlo con otro contenido devuelve conflicto (409). |
| `GET /v1/jobs/{id}` | Estado, avance, segundos de GPU usados, motivo del fallo y lista de resultados con su hash. |
| `POST /v1/jobs/{id}/cancel` | Cancela. |
| `GET /v1/jobs/{id}/files/{nombre}` | Baja un resultado; admite reanudar. |
| `GET /v1/jobs` | Historial. |
| `DELETE /v1/jobs/{id}` | Manda el trabajo y sus archivos a la papelera. |

### Estados de un trabajo

| Estado | Qué significa | En Runpod |
|---|---|---|
| `queued` | Aceptado, esperando turno en el servidor. | — |
| `submitting` | Se está enviando a la plataforma. | — |
| `starting` | La plataforma lo tiene; todavía no corre (cola o arranque en frío). | `IN_QUEUE` |
| `running` | Corriendo. | `IN_PROGRESS` |
| `collecting` | Terminó; se comprueban los archivos que subió. | `COMPLETED` |
| `cancelling` | Se pidió cancelar; puede seguir cobrando hasta que la plataforma confirme. | — |
| `succeeded`, `failed`, `cancelled`, `timed_out` | Finales. | `COMPLETED`, `FAILED`, `CANCELLED`, `TIMED_OUT` |

### Lo que pasa cuando algo se cae

- **Antes de llamar a la plataforma** el servidor anota el trabajo como `submitting`. Si se cae entre mandar el pedido
  y leer la respuesta, al volver **no lo reenvía**: lo marca como fallido con causa desconocida y lo deja para
  revisar. Es la única forma de no pagar dos veces, porque Runpod no ofrece idempotencia.
- **Al arrancar**, el servidor repasa todos los trabajos no terminados: consulta su estado en la plataforma y da por
  fallidos los `local` que quedaron a medias.
- **Si el teléfono pierde un resultado**, lo vuelve a bajar: el servidor lo conserva hasta que venza la retención.

### Otras decisiones

- **Subidas con tus 1.0**, no con el borrador del IETF (todavía cambia) ni con S3 (agrega otro servicio).
- **Enterarse de que terminó:** la app consulta el estado cuando está abierta. Un teléfono dormido no tiene dirección a
  la que avisarle. Más adelante, aviso opcional con ntfy/UnifiedPush, que no depende de Google.
- **Retención:** las entradas se borran solas a los pocos días; los resultados, cuando el dueño lo defina.
- **Respaldo:** base de datos, carpeta de archivos y llave de la bóveda. Sin la llave, la bóveda no se recupera.

## En el teléfono

- Un paquete `ockham/` con su núcleo `ockham/core` (protocolo, estados, reintentos) probado sin Android, como el resto
  de Navaja.
- Navaja **ya declara el permiso de internet**, así que el interruptor no agrega permisos.
- **Cliente HTTP:** el cliente de la plataforma (`HttpsURLConnection`) no acepta el método `PATCH` que usa tus. Hay dos
  salidas: mandar `POST` con la cabecera `X-HTTP-Method-Override`, que el protocolo tus contempla, o sumar la librería
  OkHttp. Se decide al programar la etapa 1, probando contra el servidor.
- Las subidas largas van en un servicio en primer plano con notificación. En Android 14 o más, lo indicado es un
  trabajo de transferencia iniciado por el usuario. El tope de 6 horas por día del tipo `dataSync` aplica a apps que
  apuntan a Android 15; Navaja hoy apunta a Android 14.

## Etapas

| Etapa | Servidor | App | Se prueba con |
|---|---|---|---|
| 1 | Protocolo escrito, emparejar, subidas, cola con recuperación, adaptador `local`, registro, prueba de conformidad. | Interruptor, emparejar por QR, subir un escaneo, ver el trabajo, bajar el resultado. | Una receta que devuelve lo mismo que recibe. Sin GPU ni gasto. |
| 2 | Adaptador `runpod-serverless`, bóveda, presupuestos, enlaces de un trabajo. | Elegir receta y parámetros. | video2mesh con una captura real. |
| 3 | Lo que el uso pida: adaptador `webhook` (n8n), aviso con ntfy, modo VPN, firma con llave del teléfono. | — | — |

## Decisiones abiertas

1. **Dónde vive el servidor:** VPS (siempre alcanzable, unos dólares al mes) o equipo en casa (gratis, pero depende de
   la conexión de casa y de abrir puertos). Para empezar se recomienda el VPS.
2. **Dominio:** hace falta uno para el certificado público.
3. **Lenguaje del servidor:** se recomienda Python (FastAPI) con SQLite, porque es el mismo lenguaje de video2mesh y es
   fácil de leer para quien audite. PHP 8 también puede.
4. **Repositorio:** el servidor y el protocolo deberían ir en un repositorio propio y público, separado de Navaja.
5. **Revocar sin entrar al servidor:** si hace falta un «código del dueño» para revocar desde un navegador.
6. **Cuánto se conservan** las capturas y los resultados en el servidor.

## Cómo se llegó a este diseño

1. **Primera idea: n8n como servidor.** Se descartó como puerta de entrada por licencia, tamaño de archivos, trabajos
   largos y credencial único. Quedaron sus ideas.
2. **Segunda pasada: servidor propio completo.** Cuatro adaptadores, llave pública por teléfono para firmar más
   adelante, datos separados por dispositivo, cuotas por dispositivo, certificados propios con huella en el QR.
3. **Tercera pasada: revisión adversaria independiente.** Encontró cuatro fallas serias y varias sobras:
   - el servidor podía pagar dos veces un trabajo si se caía en el momento de enviarlo;
   - había dos caminos contradictorios para recoger los resultados y ninguno sobrevivía a una caída de 40 minutos;
   - el enlace de subida que se le da al pod era una vía de escritura sin control (nombres de archivo, tamaño, vigencia);
   - el cliente HTTP de Android no acepta el método que usa el protocolo de subida.
4. **Lo que se quitó** por no hacer falta todavía: la llave pública guardada «por si acaso», tres de los cuatro
   adaptadores, la separación de datos por dispositivo y la huella del certificado en el QR.
5. **Lo que se agregó** porque sí hacía falta: recuperación al arrancar, estados que calzan con los de la plataforma,
   papelera, versión en las rutas, formato de errores, retención y respaldo.

## Fuentes

n8n y parecidos
- https://docs.n8n.io/integrations/builtin/core-nodes/n8n-nodes-base.webhook/
- https://docs.n8n.io/hosting/scaling/binary-data/
- https://docs.n8n.io/hosting/scaling/queue-mode/
- https://docs.n8n.io/hosting/configuration/configuration-examples/encryption-key/
- https://github.com/n8n-io/n8n/blob/master/LICENSE.md
- https://www.windmill.dev/docs/core_concepts/webhooks
- https://kestra.io/docs/workflow-components/triggers/webhook-trigger
- https://github.com/node-red/node-red/blob/master/LICENSE

Plataformas de IA
- https://docs.runpod.io/serverless/endpoints/send-requests
- https://docs.runpod.io/serverless/endpoints/job-states
- https://docs.runpod.io/serverless/endpoints/endpoint-configurations
- https://docs.runpod.io/serverless/pricing
- https://docs.runpod.io/get-started/api-keys
- https://docs.runpod.io/api-reference-v2/migrate-from-v1
- https://replicate.com/docs/reference/http

Seguridad
- https://developer.android.com/privacy-and-security/security-config
- https://developer.android.com/privacy-and-security/security-ssl
- https://developer.android.com/privacy-and-security/keystore
- https://caddyserver.com/docs/automatic-https
- https://docs.nextcloud.com/server/latest/developer_manual/client_apis/LoginFlow/index.html
- https://docs.syncthing.net/dev/device-ids.html
- https://contributors.home-assistant.io/core/internals/auth-api/
- https://www.rfc-editor.org/rfc/rfc6750
- https://www.rfc-editor.org/rfc/rfc9421.html
- https://www.rfc-editor.org/rfc/rfc9457.html

Subidas y trabajos largos
- https://tus.io/protocols/resumable-upload
- https://datatracker.ietf.org/doc/draft-ietf-httpbis-resumable-upload/
- https://github.com/minio/minio
- https://developer.android.com/develop/background-work/background-tasks/uidt
- https://developer.android.com/develop/background-work/services/fgs/timeout
- https://www.sqlite.org/wal.html
- https://docs.ntfy.sh/subscribe/phone/
