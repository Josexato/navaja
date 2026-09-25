# Navaja ◆

App **Android** navaja suiza de visualización: abre **GAG · SDJF · SVG · STL · OBJ · PDF** en un solo lugar, y además **escanea objetos en 3D** con la cámara. Todo corre en el dispositivo con las librerías empaquetadas en el APK: **funciona sin internet**. El visor no pide permisos; la cámara sólo se pide al entrar en el escáner.

## ✨ Qué abre

| Formato | Cómo lo muestra |
|---|---|
| `.gag` / `.sdjf` (AlmaGag) | Vista previa rápida del diagrama (nodos, conexiones, colores, flechas, iconos embebidos) + resumen del archivo. El render oficial sigue siendo el SVG que emite el motor AlmaGag. |
| `.svg` | Render fiel con pan y zoom (ideal para los SVG generados por AlmaGag). |
| `.stl` / `.obj` | Vista 3D con órbita táctil y panel de pieza para impresión 3D: triángulos, dimensiones en mm, volumen, área y peso estimado en PLA. |
| `.pdf` | Visor paginado con zoom y carga perezosa de páginas. |

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
app/src/main/java/.../scan3d/            ← escáner 3D (Camera2, PDF, UI); scan3d/core = algoritmos en Java puro
app/src/test/java/.../scan3d/core/       ← prueba de extremo a extremo con escena sintética (./gradlew :app:testDebugUnitTest)
.github/workflows/build.yml      ← CI: compila y publica el APK en Releases
```

El mismo `index.html` funciona como página web estática en cualquier navegador (solo cambia que el selector de archivos es el del navegador).
