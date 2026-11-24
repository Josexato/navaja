# Alinear dos escaneos del mismo objeto (prueba para «Agregar otra pose»)

Herramienta de escritorio, **no forma parte de la app**. Toma dos ZIP del modo 2 (video sobre la hoja con marcas) del mismo
objeto en dos poses y calcula el giro y el desplazamiento que llevan el escaneo A sobre el B.

Estado: **validado con un objeto realmente volteado** (un torito de cerámica de 12 cm, parado en una toma y acostado en otra),
con `alinear_objeto.py`. `alinear.py`, que empareja las fotos enteras, sólo sirve si el objeto casi no cambió de posición.

## Qué hace

1. `XfHarness` (Java, usa el código de la app): rehace el escaneo de cada ZIP en el PC con las fotos del ZIP: marcas de la
   hoja → poses, XFeat + LighterGlue → pistas, triangulación, medida y siluetas. Deja `obj3d.txt`: cada punto 3D con pista
   XFeat, si es del objeto y qué punto clave es en cada foto.
2. `xfpy.py`: corre los modelos TFLite (XFeat y LighterGlue) en el PC entre los pasos de Java.
3. `alinear.py`: empareja con LighterGlue unas 12 fotos de A contra 12 de B, se queda con los pares cuyos dos puntos son
   **del objeto** (las marcas de la hoja son idénticas en los dos videos y darían «sin movimiento»), y ajusta un movimiento
   rígido con RANSAC (pares coherentes a menos de 4 mm). Informa pares, coherentes, error, giro y traslación.

## Cómo se usa

Requiere JDK 17 y Python 3.11 con `ai-edge-litert`, `numpy`, `pillow` y `torch` (sólo para una comprobación de `xfpy.py bb`).
Desde la raíz del repositorio, con cada ZIP descomprimido en su carpeta (`zipA/`, `zipB/`) y una carpeta de trabajo vacía
por escaneo (`wA/`, `wB/`):

```bash
M=app/src/main/java/com/josexato/navaja
javac -nowarn -sourcepath "" -d out $M/measure/core/*.java $M/scan3d/core/*.java tools/alinear/XfHarness.java tools/alinear/HojaTracks.java
T=app/src/main/assets/modelos   # xfeat.tflite y lighterglue.tflite

for X in A B; do
  java -cp out com.josexato.navaja.measure.core.XfHarness in  zip$X w$X      # imágenes para XFeat
  python -I tools/alinear/xfpy.py bb   w$X $T zip$X                          # XFeat
  java -cp out com.josexato.navaja.measure.core.XfHarness sel zip$X w$X      # puntos y descriptores
  python -I tools/alinear/xfpy.py glue w$X $T                                # LighterGlue entre fotos seguidas (ATRAS=n: con las n anteriores)
  SIL=1 java -Xmx3g -cp out com.josexato.navaja.measure.core.XfHarness fin zip$X w$X 2   # reconstrucción → wX/obj3d.txt
done
python -I tools/alinear/alinear.py wA wB $T 12 1
```

`alinear.py` guarda la matriz 3×4 `[R | t]` (x_B = R·x_A + t, en mm) junto a las carpetas de trabajo.

## Objeto volteado: `alinear_objeto.py`

Con el objeto en otra posición, emparejar las fotos enteras no sirve: el fondo y la hoja son distintos en cada toma y confunden al
emparejador, y el objeto se ve girado en la imagen (XFeat no reconoce nada a 90° o más). Lo que funcionó:

1. `recortar.py <zip> <salida> <giro> [cada n]`: deja de cada foto sólo el objeto (fondo gris fuera de la silueta de
   `colmap/mascaras/`), recortado, ampliado al tamaño de la foto y girado. Para A con giro 0; para B con 0, 90, 180 y 270.
2. Sobre cada carpeta de recortes: `XfHarness in`, `xfpy.py bb` y `XfHarness sel` (como arriba), y copiar su `rect.txt` a la
   carpeta de trabajo.
3. `alinear_objeto.py <trabajo A> <trabajo B> <recortes A> 0:<rec B 0>,90:<…>,180:<…>,270:<…> <modelos> <ancho,alto> [cada n]`:
   empareja cada recorte de A con cada uno de B, lleva los puntos a la foto original, les da el punto 3D del punto clave
   original más cercano (a menos de 3 px) y ajusta el movimiento rígido con RANSAC sobre los cuatro giros juntos.
4. `comprobar.py <trabajo A> <trabajo B> <matriz>`: comprobación independiente de los pares (distancia entre las dos nubes).

Resultado con el torito (11 fotos de A × 10 de B × 4 giros, 99 s en el PC):

- Fotos enteras (`alinear.py`): 6 pares coherentes de 138. No sirve.
- Sólo el objeto: hasta 132 pares por par de fotos (antes 16); 34 pares 3D coherentes de 224, error mediano 1,7 mm. Los cuatro
  giros aportan (19, 8, 5 y 10 coherentes por separado).
- El movimiento hallado es un giro de 87,5° que inclina el objeto 84° respecto de la vertical: parado → acostado.
- Comprobación: llevando la nube de A sobre la de B, cada punto queda a 2,9 mm (mediana) del más cercano de B; sin alinear,
  12,8 mm; con giros al azar, entre 7 y 18 mm. La caja coincide en 1 mm en ancho y largo, y el costado sobre el que se
  apoya el objeto acostado cae en z ≈ 0 (1 % de los puntos a −2,9 mm, 2 % por debajo de −2 mm).

Ya está en la app (`PoseJoin` y `JoinActivity`, «Unir con otra pose…» en Mis escaneos). `JoinHarness` corre esa misma lógica en el
PC, por pasos, con los ZIP descomprimidos:

```bash
J="java -cp out com.josexato.navaja.measure.core.JoinHarness"   # compilar también tools/alinear/JoinHarness.java
$J cut zipA zipB w 12 && python -I tools/alinear/xfpy.py bb w $T zipA && $J sel zipA zipB w 12 \
  && python -I tools/alinear/unir.py w $T && $J fit zipA zipB w 12
```

Con el torito: parado → acostado 57 coherentes de 500 (2,0 mm, 89°; nubes 4,2 mm contra 14,1); parado → acostado sobre el otro
lado 82 de 488 (2,3 mm; nubes 2,6 contra 16,5); con un objeto distinto, 9 pares y sin ajuste. **Cuidado con la simetría:** entre
las dos tomas acostadas (costados opuestos) salen 143 pares coherentes con un giro falso de 152° (el compuesto por la toma parada
da 174°): un costado se empareja con el opuesto. La comprobación de las nubes no mejora (7,3 contra 6,8 mm) y por eso se rechaza.

**Un solo volumen** (`PoseJoin.carve`, botón «Tallar el volumen»): con `TALLAR=1`, `JoinHarness fit` talla con las siluetas de las dos
tomas, escribe `union.stl`, `solo_a.stl` y `solo_b.stl` en la carpeta de trabajo y dice qué parte de cada silueta cubre el volumen.
`CRECER=n` cambia cuántos px se ensanchan las siluetas de A. Con el torito (parado → acostado): 383 cm³ con las dos contra 467 y
457 cm³ de cada toma sola; cubre el 96–97 % de las siluetas (peor foto 88 %) y el volumen que cae fuera de ellas baja de 21–23 % a
7–10 %. Sin ensanchar, 356 cm³; con 6 px, 403.

Falta: afinar la unión con las propias siluetas (hoy sólo con los pares de puntos) y darle textura al volumen unido.

## Lo que se sabe

- Con pocos pares el resultado no es de fiar: en la misma prueba, 12 fotos por escaneo dieron 59 pares coherentes y un giro de
  0,5° (lo correcto era 0°), y 6 fotos dieron 11 pares y 5°. Conviene exigir varias decenas de pares coherentes.

- XFeat + LighterGlue reconoce los mismos puntos con el objeto girado en la imagen hasta ~45° (79 % de pares correctos); a
  60° baja a 53 % y a 90° o 180° no reconoce nada. Para un objeto volteado hay que probar las fotos de B giradas 0°, 90°,
  180° y 270° y quedarse con la que más pares dé. **Eso todavía no está en `alinear.py`.**
- **Emparejar con más cuadros anteriores no da más puntos.** Probado con tres escaneos reales (83 a 113 fotos) y 2, 4, 6 y 8
  cuadros hacia atrás (`ATRAS=8` en `xfpy.py glue` y el último argumento de `XfHarness fin`): los puntos de XFeat bajan
  entre 3 % y 23 % al pasar de 2 a 8, el error de reproyección sube un poco (1,16 → 1,27 px en el peor caso) y las medidas
  no cambian. Las pistas se funden entre sí y aparecen muchas más uniones contradictorias. `LOOK_BACK` se queda en 2.
- **Una segunda pasada de XFeat sobre el objeto ampliado sí da más puntos** (es `ZoomPass`, ya en la app). `XfHarness inz` y
  `selz` recortan cada foto alrededor de la caja del objeto (`caja.txt`, que deja `fin` con `SIL=1`), la amplían y sacan otros
  1024 puntos ahí. Con `ZOOM=<carpeta> ZOOM_MODO=app`, `fin` suma sus pistas como la app: al final, con las poses ya fijas.
  Resultado: alcancía de 14 cm, 41 de 87 fotos ampliadas 1,5× → objeto de 640 a 968 puntos; objeto de 20 cm, 40 de 113
  fotos → de 10 788 a 13 449; un objeto que llenaba la foto, nada que ampliar. Los ZIP traen las fotos a 960 px, así que aquí
  el recorte se amplía sin detalle nuevo; en la app sale del video original. La caja tiene que ser la de después de las
  siluetas: con la de los puntos solos (149 × 150 mm para la alcancía) no había nada que ampliar.
- Variables de `XfHarness fin` usadas en las pruebas: `SIL=1` corrige las siluetas, `CONSENSO=1` las compara entre sí,
  `ROMPER=0.35` estropea una parte a propósito, `PLANO=0` no usa que la hoja es plana, `CASCO=archivo` guarda los vóxeles.
