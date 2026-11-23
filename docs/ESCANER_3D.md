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
- La app vuelve a leer las fotos clave con **XFeat** (puntos y descriptores aprendidos) y empareja cada una con las **dos anteriores** con **LighterGlue**. Se queda con los pares que respetan la geometría de las poses de la hoja y los une en pistas, que se suman a las de KLT. Las pistas se arman uniendo primero los pares más confiables (fotos más cercanas, mayor confianza), y una unión que pondría dos puntos de la misma foto en una pista no se hace. Si una observación de una pista no calza (> 2 px), se quita ella sola y se vuelve a triangular, sin perder el punto. Comparado con emparejar sólo con la anterior: 25–50 % más puntos y 12–18 % menos ruido de altura; LighterGlue tarda el doble (~30 s en CPU para 90 fotos). Al empezar, la app prueba en el teléfono la CPU, la GPU (tarjeta gráfica) y la NPU (chip de IA, por NNAPI) con una entrada de prueba, descarta la que falla o no da lo mismo que la CPU y usa la más rápida; el informe dice cuál quedó y cuánto tardó cada una. Si un acelerador cuelga la app, la próxima vez no se prueba.
- **La hoja es plana:** un punto que, puesto en Z = 0, sigue calzando con lo que ven las fotos (≤ 2 px) es de la hoja o de la mesa y queda exactamente en el plano, también en la nube densa. Así la hoja no sale con alturas distintas. Esos puntos, junto con las esquinas de las marcas, sirven además para afinar la posición del celular en cada foto clave (3 vueltas de reajuste y triangulación). Con los videos de prueba las cámaras se corrigen ~1 mm y el ruido de altura de los puntos baja 5–15 %. Las medidas cambian poco: el ruido que queda viene sobre todo de puntos vistos desde fotos muy juntas.
- **Segunda pasada sobre el objeto ampliado:** en la pasada normal XFeat reparte sus 1024 puntos por toda la foto, hoja incluida. Cuando las siluetas ya dicen dónde está el objeto, en las fotos donde se ve chico (se puede ampliar 1,3× o más) la app lo recorta del video a toda su resolución, lo amplía y saca otros 1024 puntos ahí. Los que se confirman en 3 fotos o más se suman a la nube; las medidas no cambian. Se hace sólo si hay 6 fotos ampliables o más. En las pruebas dio +51 % de puntos del objeto en una alcancía de 14 cm y +25 % en un objeto de 20 cm; si el objeto ya llena las fotos, no hace nada. El informe dice cuántas fotos amplió, cuántos puntos sumó y cuánto tardó.
- **El objeto está sobre la hoja:** sólo cuenta como objeto lo que sobresale sobre la hoja (con 30 mm de margen); lo de afuera es el fondo.
- **Siluetas corregidas con la hoja:** de la silueta de la IA se borra lo que no puede ser el objeto (píxeles cuyo rayo no pasa por el espacio holgado del objeto sobre la hoja, como la pared del fondo). Para tallar el volumen, además, se agregan las partes que la IA dejó afuera alrededor de los puntos 3D confiables del objeto (puntos que ya caen dentro de la silueta en al menos la mitad de las fotos): un tallo, un costado. En las pruebas, el volumen de la alcancía pasó de 134 × 68 × 50 a 132 × 70 × 78 mm (real 140 × 70 × 80) y el farol, que antes no tenía volumen, ahora lo tiene.
- **Celular horizontal:** la pantalla de grabación guarda el video como vertical; con los sensores (o, en un video de la galería, con la hoja: dónde queda «arriba» en los primeros segundos) la app sabe cómo se sostuvo el celular y gira los cuadros para que fotos y texturas salgan derechas. No gires el celular a mitad de la toma.
- **Siluetas comparadas entre sí:** con las siluetas se arma un volumen por votación (lo que está dentro de la mayoría) y se proyecta en cada foto: es el contorno que predicen las demás. Una silueta que se parece a esa predicción mucho menos que el resto es un error de la IA (se desbordó o cortó un pedazo) y se reemplaza por la predicción; si la predicción no es buena, sólo se descarta, y si más de la mitad no calza no se toca nada. El informe dice cuántas no calzaron. Con **Depuración**, el ZIP trae `depuracion/siluetas_ia/` con las siluetas tal como las dio la IA.
- **Fotos clave por ángulo:** como la hoja ubica el celular en todos los cuadros, las fotos clave se eligen una cada ~2,5° de cambio de punto de vista (la más nítida de cada paso), no cada cierto tiempo. Una pausa no gasta fotos repetidas y un tramo rápido usa todos los cuadros que tenga. El informe dice cuánto de la vuelta se recorrió, entre qué alturas estuvo la cámara y los huecos (más de 10° entre dos fotos seguidas), con el segundo del video.
- **Volumen sólo con contornos:** en este modo el volumen se talla únicamente con la silueta del objeto en cada foto. No se usa el paso de «espacio libre» del modo 1 (quitar lo que queda delante de los puntos 3D): con puntos ruidosos y superficies lisas dejaba el objeto hundido (una calabaza blanca pasó de 509 a 679 cm³ y quedó redonda). A cambio, las concavidades no se ven.
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
6. **Generar 3D** abre el modelo en el visor de Navaja, con medidas, volumen y peso PLA. Los archivos (`modelo.obj`, `.ply`, `.stl` y `.glb`) quedan en `Android/data/com.josexato.navaja/files/scans/`.

**Ajustes de la pantalla:**
- **Modo**: DISCO (el ángulo se lee del anillo) o LIBRE (el ángulo se deduce con momentos de Hu).
- **Umbral silueta**, **Resolución 3D**, **Esperar escena quieta** y **Sentido** (en LIBRE).
- **Cámaras**: qué cámaras físicas se usan.
- **Diagnóstico de cámaras**: guarda un JSON con lo que reporta el teléfono.

---

## Mis escaneos

En el selector del Escáner 3D, **Mis escaneos** lista lo que hay en la carpeta de guardado, sin abrir el explorador de archivos:

- Arriba: la carpeta, cuántos escaneos hay, cuánto ocupan, cuántos tienen su video y cuántos se guardaron con depuración.
- Por escaneo: fecha y hora, modo, **versión de Navaja** con que se hizo, medidas por puntos y volumen, si el video está guardado (tamaño, duración, de dónde salió y cuándo se grabó), sensores, cuadros ubicados por la hoja, en qué corrió la IA, si tiene depuración y el tamaño del ZIP. Botones **Ver informe** y **Compartir ZIP**.
- Videos sueltos: los grabados que no tienen un escaneo guardado con su nombre.
- Los escaneos del modo 3 (base giratoria), con fecha, tamaño y si tienen modelo y log.

Desde esta versión, junto a cada ZIP se guarda una ficha `medida_….json` (versión, modo, depuración, origen y fecha del video, y el informe completo): con ella la lista no necesita abrir el ZIP. Los escaneos anteriores se listan leyendo el informe que está al principio de su ZIP; de ellos no se sabe si tienen depuración ni de dónde salió el video.

## Archivos del ZIP (modos 1 y 2)

| Archivo | Qué es |
|---|---|
| `informe.md` | Informe paso a paso: versión de Navaja, video, seguimiento, fotos usadas, sensores, hoja (modo 2), puntos, medidas, puntos clave |
| `nube_objeto.ply` / `nube_completa.ply` | Puntos del objeto limpios (con normales) / todos los puntos |
| `volumen.stl` | Volumen tallado con las siluetas, en mm, Z hacia arriba |
| `modelo_base.obj` / `modelo_lowpoly.obj` (+ `.mtl`, `textura_*.jpg`) | Malla simplificada a ~3000 y ~400 triángulos con textura de las fotos, para Blender |
| `modelo_base.glb` / `modelo_lowpoly.glb` | Los mismos dos modelos en GLB (glTF binario): un solo archivo con la malla y la textura adentro, en metros |
| `fotos/` | Los cuadros clave usados |
| `colmap/`, `poses.json` | La reconstrucción en formato COLMAP y en JSON, más las siluetas, para seguir en un servidor |
| `sensores.csv`, video | Si se grabó con la pantalla de Navaja |
| `depuracion/` | Con «Depuración» marcada |

## Agregar otra pose (unir dos escaneos)

Cada toma deja sin ver la parte sobre la que el objeto está apoyado. Si se escanea el mismo objeto dos veces sobre la hoja, en dos posiciones (por ejemplo parado y acostado), **Mis escaneos → «Unir con otra pose…»** halla el giro y el desplazamiento que llevan un escaneo sobre el otro. Por ahora alinea las dos tomas, dice qué tan fiable salió y deja compartir la nube unida (PLY, en mm); tallar un solo volumen con las dos es el paso siguiente.

- De 12 fotos de cada escaneo se deja sólo el objeto (fondo gris fuera de la silueta, recortado y ampliado): el fondo y la hoja son distintos en cada toma y confunden al emparejador.
- Las fotos del segundo escaneo se prueban además giradas 90°, 180° y 270°, porque XFeat no reconoce nada con la imagen girada 90° o más.
- Cada par de puntos emparejados se pasa a un par de puntos 3D y con todos se ajusta un movimiento rígido (RANSAC, pares coherentes a menos de 4 mm). Hacen falta 20 pares coherentes.
- Dos comprobaciones que no usan los pares: las dos nubes tienen que acercarse (a menos del 60 % de la distancia sin girar) y el objeto movido no puede quedar hundido bajo la hoja.
- **Objetos simétricos:** si las dos tomas muestran costados opuestos (acostado sobre un lado y luego sobre el otro), un costado se empareja con el otro y sale un giro coherente pero falso. La comprobación de las nubes lo detecta y la unión se rechaza. Conviene que las dos poses compartan partes visibles.
- Probado con un torito de cerámica de 12 cm: parado → acostado, 57 pares coherentes de 500, error de 2,0 mm, giro de 89°; las nubes quedan a 4,2 mm (14,1 sin girar). Con un objeto distinto no salen pares (9) y no se une.

## Versión

Cada informe empieza con la versión que lo generó, por ejemplo «Generado por Navaja 1.0.42 (compilación 42 del CI, commit abc1234)». El número coincide con la Release «Build 42» de GitHub.
