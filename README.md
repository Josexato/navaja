# Navaja ◆

App **Android** navaja suiza de visualización: abre **GAG · SDJF · SVG · STL · OBJ · PDF** en un solo lugar, y además **escanea objetos en 3D** con la cámara. Todo corre en el dispositivo con las librerías empaquetadas en el APK: **funciona sin internet**. También trae un **afinador de guitarra y violín**. El visor no pide permisos; la cámara y el micrófono sólo se piden al entrar en el escáner o en el afinador.

## ✨ Qué abre

| Formato | Cómo lo muestra |
|---|---|
| `.gag` / `.sdjf` (AlmaGag) | Vista previa rápida del diagrama (nodos, conexiones, colores, flechas, iconos embebidos) + resumen del archivo. El render oficial sigue siendo el SVG que emite el motor AlmaGag. |
| `.svg` | Render fiel con pan y zoom (ideal para los SVG generados por AlmaGag). |
| `.stl` / `.obj` | Vista 3D con órbita táctil y panel de pieza para impresión 3D: triángulos, dimensiones en mm, volumen, área y peso estimado en PLA. |
| `.pdf` | Visor paginado con zoom y carga perezosa de páginas. |

## 🎻 Afinador (botón «Afinador»)

Afina **guitarra** (estándar E2 A2 D3 G3 B3 E4 y Drop D) y **violín** (G3 D4 A4 E5) con el micrófono del teléfono.

- Reconoce sola la cuerda que suena; tocando una cuerda en pantalla queda fija (útil si está muy desafinada).
- Aguja de −50 a +50 cents: verde = afinada (±5 cents), con la indicación «tensa» o «afloja».
- La4 ajustable de 415 a 466 Hz (p. ej. 442 Hz de orquesta) y **tono de referencia** por cuerda para afinar de oído.
- Detección con YIN más tres defensas contra los armónicos: **filtro pasabanda** del rango del instrumento (más estrecho con una cuerda fija: −12 dB en el 2º armónico, −26 dB en el 3º), elección del mínimo más periódico **cercano a las cuerdas** (no el primero) y **seguimiento estable**: ignora el golpe inicial, confirma por votación entre cuadros (el doble para saltos de octava) y sólo cambia de cuerda tras un **ataque** nuevo o cuando la anterior deja de oírse, así una cuerda que vibra por simpatía no tapa a la recién pulsada.
- Botón **Respuesta: Rápida / Normal / Estable** para elegir entre velocidad y estabilidad de la aguja.
- Probado con señales sintéticas que imitan el micrófono del teléfono (fundamental grave atenuada, cuerdas sonando a la vez): 0 lecturas en el armónico en guitarra, Drop D y violín. Validado además con una grabación real de guitarra: los cambios de cuerda mostrada bajaron de 76 a 17 en 15 s.
- El audio se procesa en el teléfono y no se guarda ni se envía.

## 📷 Escáner 3D (botón «Escanear 3D», Android 11+)

Genera un modelo 3D a partir de fotos tomadas cada segundo mientras giras el objeto sobre un **escenario impreso**, y lo abre en el visor 3D de Navaja (con medidas, volumen y peso PLA). Pensado para Galaxy S26 Ultra; puede usar varias cámaras físicas a la vez.

1. **Imprimir escenario (PDF)** al 100 % (A4 o Carta; la barra debe medir 100 mm). Hoja 1 = tapete fijo con 4 marcadores; hoja 2 = disco giratorio con anillo codificado (recortar y pegar en cartón; una chincheta en el centro fija el eje).
2. Teléfono **en trípode**, 35–50 cm, 20–40° de elevación, viendo los 4 marcadores.
3. **Foto de fondo** (disco puesto, **sin objeto**): fija exposición/enfoque y calibra cada cámara.
4. Objeto en el centro → **Iniciar captura**: gira el disco de a poco; dispara con la escena quieta (máx. 1 foto/s) y se detiene al completar la vuelta.
5. Opcional: **nueva pasada** a otra altura (mejora la parte superior).
6. **Generar 3D** → se abre en Navaja (material «Color del escaneo»). Archivos `modelo.obj/.ply/.stl` + `log.txt` en `Android/data/com.josexato.navaja/files/scans/`.

| Paso | Técnica |
|---|---|
| Pose de cámara | 4 marcadores → homografía → autocalibración de focal |
| Ángulo (modo DISCO) | anillo con secuencia‑m de 63 bits por correlación circular (~0,1° en pruebas, tolera oclusión) |
| Ángulo (modo LIBRE) | **momentos de Hu**: detectan la vuelta completa → giro uniforme |
| Silueta | resta de fondo compensada en rotación; descarta manos/fotos anómalas |
| 3D | casco visual por vóxeles → Surface Nets → suavizado Taubin → color por vértice |
| Cámaras | Camera2 con cámaras físicas de la lógica; si el HAL no acepta la combinación, usa la lógica |
| Diagnóstico | botón «Diagnóstico de cámaras»: JSON con IDs físicos, focales, RAW10/12/16, DEPTH16, OIS y combinaciones simultáneas aceptadas |

Límites: el casco visual no reproduce concavidades; objetos blancos sobre papel blanco o sombras fuertes empeoran la silueta; el color es aproximado (sin oclusión).

## 📦 Descargar el APK

Cada push a `main` compila el APK con GitHub Actions y lo publica en [**Releases**](../../releases). Descarga el `app-release.apk` más reciente e instálalo (Android 7.0+; hay que permitir "instalar apps desconocidas" porque va firmado con clave debug).

## 🛠 Compilar localmente

```bash
./gradlew assembleRelease
# APK en app/build/outputs/apk/release/app-release.apk
```

Requiere JDK 17 y el SDK de Android (compileSdk 34).

## 🧩 Estructura

```
app/src/main/assets/index.html   ← toda la app (HTML + CSS + JS)
app/src/main/assets/lib/         ← three.js 0.147 + loaders, pdf.js 3.11 legacy (offline)
app/src/main/java/.../MainActivity.java  ← WebView + selector de archivos + puente al escáner
app/src/main/java/.../tuner/             ← afinador (micrófono, aguja, tono); tuner/core = YIN y afinaciones
app/src/main/java/.../scan3d/            ← escáner 3D (Camera2, PDF, UI); scan3d/core = algoritmos en Java puro
app/src/test/java/.../scan3d/core/       ← prueba de extremo a extremo con escena sintética (./gradlew :app:testDebugUnitTest)
.github/workflows/build.yml      ← CI: compila y publica el APK en Releases
```

El mismo `index.html` funciona como página web estática en cualquier navegador (solo cambia que el selector de archivos es el del navegador).
