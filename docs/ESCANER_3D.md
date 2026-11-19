# Escáner 3D de Navaja — guía de los modos de captura

En la pantalla de inicio hay una sola tarjeta, **«Escáner 3D: medir y modelar objetos»**. Abre un selector con los tres modos de captura. Todo se calcula en el teléfono, sin internet.

| | 1 · Video alrededor del objeto | 2 · Video sobre la hoja con marcas *(experimental)* | 3 · Fotos con base giratoria y trípode |
|---|---|---|---|
| **Cómo se captura** | Grabas 10–30 s rodeando el objeto | Grabas 15–30 s rodeando el objeto, puesto sobre una hoja A4 impresa | El celular queda fijo en un trípode y tú giras el objeto sobre un disco impreso |
| **Qué hay que imprimir** | Nada | 1 hoja A4 con 18 marcas en el borde | Tapete con 4 marcadores + disco con anillo codificado |
| **Escala** | Proporciones; cm si escribes una medida conocida | **mm** directo (la hoja da la escala) | **mm** directo (el tapete da la escala) |
| **De dónde sale la posición del celular** | Fotogrametría (SfM) del propio video | Las marcas de la hoja, en cada cuadro | Los 4 marcadores del tapete + ángulo del disco |
| **Puntos del objeto** | Seguimiento KLT | KLT + **XFeat + LighterGlue** (IA en el teléfono) | No usa puntos: sólo siluetas |
| **Tamaño del objeto** | Cualquiera (también en una pared) | Base de hasta ~11 × 19 cm (recuadro central de la hoja) | Lo que quepa en el disco |
| **Android** | 7+ | 7+ | 11+ (Camera2, varias cámaras) |
| **Resultado** | Pantalla de medidas + ZIP | Pantalla de medidas + ZIP (en mm) | Modelo en el visor 3D de Navaja |
| **Archivos** | Informe, nubes PLY, volumen STL, OBJ para Blender con textura, fotos, COLMAP | Lo mismo | `modelo.obj/.ply/.stl` + `log.txt` |
| **Cuándo conviene** | Lo más rápido; objetos grandes, fijos o colgados | Medidas en mm sin regla y con más puntos sobre el objeto | Pieza chica para reproducir o imprimir, con tiempo para armar |

Ninguno de los tres funciona bien con objetos transparentes, espejados o totalmente lisos, porque no tienen dibujo que seguir.

---

## 1 · Video alrededor del objeto

**Dónde:** Escáner 3D → *1 · Video alrededor del objeto* (código: `measure/MeasureActivity`).

**Cómo filmar**
1. **Grabar video**: abre la pantalla de grabación de Navaja, que guarda también la orientación del celular y la gravedad (`sensores.csv`). Bloquea un solo lente y desactiva la estabilización digital. Con **Elegir un video** se usa uno de la galería, sin sensores.
2. Rodea el objeto despacio, en arco de 60–180°, sin saltos. Mientras rodeas, sube y baja un poco el celular.
3. Empieza con el celular derecho, que se vea bastante mesa o pared alrededor y que algo de la escena tenga textura.

**Pantalla de resultado**
- Alto, ancho y profundidad, apoyado en una pared o sobre una mesa.
- **Una medida que conozcas** → **Calcular en cm**.
- Visor con pestañas **Nube original / Nube limpia / Modelo** (el modelo con la textura de las fotos).
- **Segunda opinión: siluetas**: una IA (MagicTouch) recorta el objeto en cada foto y talla su volumen. Muestra ✓ si coincide con la medida por puntos (≤ 8 %) y ⚠ si no. Desde ahí se comparte el STL.
- **Más puntos en una zona**: tocas la nube, eliges el tamaño de la zona y **Reprocesar la zona**.
- **Guardar (ZIP)**, **Compartir nube (PLY)**, **Otro video**. Con **Depuración** marcada, el ZIP agrega cada foto con la silueta y los puntos clave dibujados.

**Cómo funciona:** seguimiento KLT → SfM con ajuste de haces → cierre de bucle entre tramos → plano de apoyo por RANSAC → caja del objeto → siluetas y casco visual → malla (Surface Nets) → simplificación QEM y textura UV. El detalle técnico está en el README, sección «Escáner 3D».

## 2 · Video sobre la hoja con marcas *(experimental)*

**Dónde:** Escáner 3D → *2 · Video sobre la hoja con marcas* (código: `measure/SheetActivity` → `MeasureActivity` en modo hoja).

1. **Abrir la hoja (PDF)** e imprimirla en A4 al **100 %**, sin «ajustar a la página». Comprueba con una regla que la barra mida 100 mm.
2. Pon la hoja sobre la mesa y el objeto en el **recuadro central** (110 × 197 mm).
3. **Grabar y escanear**: graba 15–30 s rodeando el objeto, con la hoja entera o casi entera en la imagen. **Escanear un video** usa uno ya grabado: si viene de la galería, la focal se estima con la hoja y el informe lo advierte.

**Qué cambia respecto del modo 1**
- La posición del celular en cada cuadro sale de las marcas de la hoja: 18 marcas tipo ArUco 4×4, cada una con su número. No sale de la fotogrametría, así que no hay tramos cortados ni medida conocida que escribir: **todo está en mm**.
- La app vuelve a leer las fotos clave con **XFeat** (puntos y descriptores aprendidos) y empareja cada una con la anterior con **LighterGlue**. Se queda con los pares que respetan la geometría de las poses de la hoja y los une en pistas, que se suman a las de KLT. Corre en la GPU si el teléfono la admite y si no en la CPU.
- La pantalla de resultado y el ZIP son los del modo 1. La tarjeta «Escala desde la hoja» dice cuántos cuadros ubicó la hoja y cuántos puntos 3D salieron de KLT y cuántos de XFeat.
- Informe: la sección **«Hoja impresa»** trae el lente usado, los cuadros ubicados, el error de las esquinas y XFeat (dónde corrió, ms por foto y por par, pares que pasaron la geometría y pistas).

**Diagnóstico** (al final de la pantalla): **Probar marcas** sólo detecta las marcas en un video, sin escanear el objeto. Guarda un ZIP con las marcas dibujadas. Sirve cuando el escaneo dice que la hoja casi no se vio.

**Resultados de prueba:** con una alcancía de 140 × 70 × 80 mm (alto × ancho × largo), sólo KLT dio 136 × 56 × 65 y KLT + XFeat 140 × 58 × 80, con unas 6 veces más puntos sobre el objeto.

## 3 · Fotos con base giratoria y trípode

**Dónde:** Escáner 3D → *3 · Fotos con base giratoria y trípode* (código: `scan3d/ScanActivity`; Android 11+).

1. **Imprimir escenario (PDF)** al 100 %. Hoja 1 = tapete con 4 marcadores. Hoja 2 = disco con anillo codificado: se recorta, se pega en cartón y se fija con una chincheta en el centro.
2. Celular **en trípode**, a 35–50 cm y 20–40° de elevación, viendo los 4 marcadores.
3. **Foto de fondo**, con el disco puesto y **sin objeto**: fija la exposición y el enfoque y calibra cada cámara.
4. Objeto al centro → **Iniciar captura**. Gira el disco de a poco: la app toma como máximo 1 foto por segundo, con la escena quieta, y se detiene al completar la vuelta.
5. Opcional: una **nueva pasada** a otra altura mejora la parte de arriba.
6. **Generar 3D** abre el modelo en el visor de Navaja, con medidas, volumen y peso PLA. Los archivos quedan en `Android/data/com.josexato.navaja/files/scans/`.

**Ajustes de la pantalla:**
- **Modo**: DISCO (el ángulo se lee del anillo) o LIBRE (el ángulo se deduce con momentos de Hu).
- **Umbral silueta**, **Resolución 3D**, **Esperar escena quieta** y **Sentido** (en LIBRE).
- **Cámaras**: qué cámaras físicas se usan.
- **Diagnóstico de cámaras**: guarda un JSON con lo que reporta el teléfono.

---

## Archivos del ZIP (modos 1 y 2)

| Archivo | Qué es |
|---|---|
| `informe.md` | Informe paso a paso: versión de Navaja, video, seguimiento, fotos usadas, sensores, hoja (modo 2), puntos, medidas, puntos clave |
| `nube_objeto.ply` / `nube_completa.ply` | Puntos del objeto limpios (con normales) / todos los puntos |
| `volumen.stl` | Volumen tallado con las siluetas, en mm, Z hacia arriba |
| `modelo_base.obj` / `modelo_lowpoly.obj` (+ `.mtl`, `textura_*.jpg`) | Malla simplificada a ~3000 y ~400 triángulos con textura de las fotos, para Blender |
| `fotos/` | Los cuadros clave usados |
| `colmap/`, `poses.json` | La reconstrucción en formato COLMAP y en JSON, más las siluetas, para seguir en un servidor |
| `sensores.csv`, video | Si se grabó con la pantalla de Navaja |
| `depuracion/` | Con «Depuración» marcada |

## Versión

Cada informe empieza con la versión que lo generó, por ejemplo «Generado por Navaja 1.0.42 (compilación 42 del CI, commit abc1234)». El número coincide con la Release «Build 42» de GitHub.
