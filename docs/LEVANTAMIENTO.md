# Levantamiento: recorrido con señal y plano de la habitación

Estado: **primera versión, sin probar en un teléfono**. El núcleo (recorrido, habitación, archivos) tiene pruebas
automáticas y los archivos de ejemplo se abrieron con ezdxf y con GDAL, la librería que usa QGIS. La pantalla con
ARCore compila, pero se escribió con la documentación y falta verla funcionar. Todo ocurre en el teléfono, sin
servidor: es parte de Navaja, no de Ockham.

## Cómo se usa

1. Abrir **Levantamiento** desde el inicio y dar los permisos (cámara, ubicación y teléfono).
2. Mover el teléfono despacio hasta que diga «Siguiendo» y tocar **● Empezar**. Ese punto es el origen del plano.
3. Caminar con la cámara mirando hacia adelante. El mapa chico muestra el recorrido y las mediciones de señal
   coloreadas por potencia.
4. Para el plano: apuntar la mira a cada esquina del piso, en orden, y tocar **＋ Esquina** cuando esté verde.
   **Cerrar habitación** termina una y permite empezar otra. Apuntar una vez al techo da la altura.
5. **■ Terminar y guardar** muestra el informe y deja un ZIP para compartir.

El ZIP trae:

| Archivo | Qué es |
|---|---|
| `informe.md` | Resumen: distancia, tiempo con seguimiento, señal, habitaciones. |
| `mediciones.csv` | Una fila por medición de la celda servidora, con `x_m`, `y_m` en metros. |
| `plano.dxf` | Recorrido, mediciones por nivel de señal (una capa por nivel) y habitaciones con sus medidas. |
| `mapa.geojson` | Lo mismo en latitud y longitud; sólo si hubo un punto GPS y rumbo de brújula. |
| `registro.txt` | Todo lo que entregó el teléfono, línea por línea. Con él se rehacen los demás archivos. |

## Qué hace

La persona camina con el teléfono mientras la cámara mira hacia adelante. Navaja guarda:

1. **El recorrido**, en metros, con una lectura de señal celular (4G/5G) en cada punto.
2. **El plano de la habitación**: contorno marcando las esquinas, área y altura del techo.

Los dos salen en el mismo sistema de coordenadas (metros desde el punto de partida), así que el recorrido cae dentro
del plano.

## Qué dice la documentación (investigado el 25/11/2026)

### Posición al caminar: ARCore

- **Librería:** `com.google.ar:core:1.57.0` (la más reciente en el Maven de Google). La guía oficial todavía muestra
  la 1.33.0, que está vieja.
- **Requisitos:** Android 7.0 y un teléfono certificado. Se declara como **opcional** en el manifiesto
  (`com.google.ar.core` = `optional`) para que Navaja siga instalándose en teléfonos sin ARCore.
- **Antes de abrir la sesión:** `ArCoreApk.checkAvailability()` puede responder «comprobando» mientras consulta la red;
  `requestInstall()` instala o actualiza «Servicios de Google Play para RA».
- **Qué entrega:** la pose de la cámara en **metros**, con +Y hacia arriba. Las coordenadas del mundo pueden moverse
  entre cuadros cuando ARCore corrige su mapa, por eso las posiciones se guardan **relativas a un ancla** puesta al
  empezar.
- **Estados:** siguiendo, en pausa o detenido, con el motivo: poca luz, movimiento excesivo, pocas texturas, cámara
  no disponible. La app lo muestra para que la persona corrija.
- **Planos:** horizontales hacia arriba (piso), horizontales hacia abajo (techo) y verticales (paredes). La altura de
  la habitación es la distancia entre el piso y el techo detectados.
- **Marcar una esquina:** un «hit test» devuelve el punto del piso al que apunta el centro de la pantalla.
- **Exige OpenGL:** ARCore no entrega cuadros si no tiene una textura de cámara en un contexto GL. La pantalla del
  levantamiento dibuja la cámara con un `GLSurfaceView`.
- **Sin internet:** el seguimiento ocurre en el teléfono. La documentación no lo afirma con esas palabras; lo que sí
  documenta como dependiente de la red es la comprobación de disponibilidad, las anclas en la nube y el modo
  geoespacial, que aquí no se usan.
- **Precisión:** Google no publica cifras. Un estudio revisado (Sensors, 2022, un solo teléfono) midió una deriva del
  orden de centímetros por segundo de recorrido en estos sistemas. Hay que medirla en el teléfono real.
- **Grabar y reproducir:** ARCore puede grabar una sesión en MP4 (video y sensores) y reproducirla después como si
  fuera en vivo. Queda como herramienta para depurar más adelante.

### Señal celular

Navaja ya lee 4G y 5G en la herramienta «Red móvil» (`cellular/`): potencia (RSRP), calidad (RSRQ), relación señal
/ruido (SINR), celda, banda y vecinas. El levantamiento reutiliza ese lector. Lo que la documentación advierte:

- `getAllCellInfo()` devuelve datos **en caché** que pueden no estar al día; cada lectura trae su marca de tiempo.
- `requestCellInfoUpdate()` (Android 10+) pide una lectura fresca, pero «las actualizaciones están limitadas y no se
  garantizan». No hay un número publicado: hay que medir cada cuánto llega una lectura nueva en el teléfono.
- En 5G NSA el tipo de red sigue siendo LTE y la portadora 5G suele venir sin identificadores.
- Con dos SIM se lee cada una por separado (`createForSubscriptionId`).
- El levantamiento tiene que quedar **en pantalla**: en segundo plano Android recorta la ubicación a unas pocas
  lecturas por hora y no deja iniciar la cámara.

### Otros sensores

- **Barómetro:** sirve para detectar cambios de piso por diferencia de presión, no para la altura absoluta.
- **Wi-Fi:** una app en pantalla puede escanear 4 veces cada 2 minutos; es poco para un mapa fino. Queda fuera de la
  primera versión.
- **GPS:** un punto fijo tomado al aire libre o en la entrada, junto con el rumbo, permite ubicar el recorrido en el
  mapa real.

### Lo que ya existe

- Las apps de medición de señal en interiores que se encontraron (G-NetTrack Pro, TEMS Pocket, WalkTest) piden
  **cargar un plano y marcar a mano** dónde está uno en cada giro.
- La posición automática con realidad aumentada existe para Wi-Fi: Ekahau (sólo iPhone) y NetLens (Android).
- **No se encontró** una app Android de señal celular con posición automática por ARCore. Es ausencia de resultados en
  unas pocas búsquedas, no una prueba de que no exista.

### Formatos

- **DXF** para el plano: versión R12 con `POLYLINE`/`VERTEX`/`SEQEND` y `TEXT`. Es la forma más simple y no necesita
  las tablas que exigen las versiones nuevas. Lo abren QGIS, LibreCAD y FreeCAD.
- **CSV** para las mediciones, con `x_m`, `y_m` en metros locales. QGIS lo carga como capa de puntos sin sistema de
  referencia y lo superpone al DXF.
- **GeoJSON** sólo admite latitud y longitud (RFC 7946). Se genera únicamente cuando el recorrido está ubicado con un
  punto GPS y un rumbo; si no, no se inventan coordenadas.

## Diseño

- `survey/core/`: lógica sin Android, con pruebas.
  - `Track`: muestras de posición → recorrido en planta, distancia, tramos sin seguimiento.
  - `Room`: esquinas → contorno, área y perímetro; piso y techo → altura.
  - `SurveyLog`: une cada lectura de señal con la posición de ese instante (interpolada).
  - `Dxf`, `SurveyCsv`, `SurveyGeoJson`: escritura de archivos.
- `survey/SurveyActivity`: sesión de ARCore, cámara en pantalla, lector de señal, botones para marcar esquinas, y
  guardado en ZIP.

Ejes del plano: `x` = X de ARCore, `y` = −Z de ARCore, de modo que el plano se ve desde arriba sin espejar.

## Lo que falta comprobar en el teléfono

1. Que ARCore abra, muestre la cámara y siga la posición.
2. **La deriva:** caminar un circuito y volver al punto de partida; cuánto se separa el final del inicio.
3. **Cada cuánto llega una medición nueva de señal** (lo dice el informe). De eso depende qué tan fino sale el mapa.
4. Que la mira marque bien las esquinas y que el techo se detecte para la altura.
5. La brújula en interiores: el rumbo es un promedio y puede salir torcido cerca de metal.

## Pendiente

- Mapa en pantalla grande al terminar, y guardado en la carpeta de escaneos.
- Detección de cambio de piso con el barómetro (la presión ya se graba).
- 5G NSA: la portadora 5G que viene sin identificadores todavía no se registra.
- Corregir la deriva al cerrar un circuito.

## Fuentes

- https://dl.google.com/dl/android/maven2/com/google/ar/core/maven-metadata.xml
- https://developers.google.com/ar/develop/java/enable-arcore
- https://developers.google.com/ar/devices
- https://developers.google.com/ar/reference/java/com/google/ar/core/Pose
- https://developers.google.com/ar/reference/java/com/google/ar/core/Plane.Type
- https://developers.google.com/ar/reference/java/com/google/ar/core/TrackingFailureReason
- https://developers.google.com/ar/reference/java/com/google/ar/core/exceptions/TextureNotSetException
- https://developers.google.com/ar/develop/recording-and-playback
- https://pmc.ncbi.nlm.nih.gov/articles/PMC9785098
- https://developer.android.com/reference/android/telephony/TelephonyManager
- https://developer.android.com/reference/android/telephony/TelephonyDisplayInfo
- https://developer.android.com/develop/sensors-and-location/location/background
- https://developer.android.com/develop/connectivity/wifi/wifi-scan
- https://developer.android.com/reference/android/hardware/SensorManager
- https://ezdxf.readthedocs.io/en/stable/dxfinternals/filestructure.html
- https://www.rfc-editor.org/rfc/rfc7946.txt
- https://docs.qgis.org/testing/en/docs/user_manual/working_with_projections/working_with_projections.html
