# Navaja como etapa de captura: contexto de video2mesh

Este documento fija el reparto de trabajo entre el Escáner 3D de Navaja y lo que viene después, y resume lo
aprendido en **video2mesh**, un proyecto aparte de fotogrametría densa con GPU. Es contexto para quien
programe en cualquiera de los dos lados. Nada de lo que sigue está integrado todavía en Navaja.

## El enfoque

- **Navaja es la etapa de captura y preprocesamiento.** Su objetivo es entregar la mejor información
  posible del objeto: buenas imágenes, posiciones de cámara fiables, el objeto ya separado del fondo y la
  escala.
- **Lo que viene después lo hace otra aplicación**, que recibe lo que Navaja exporta y se conecta a un
  servidor con GPU (Runpod): malla densa, modelos generativos y el low poly para papercraft. Esa aplicación
  todavía no existe.
- **No se trata de hacer todo en el teléfono ni de dejar de exportar el video.** Se procesa en el teléfono
  lo que es óptimo procesar ahí, y el resto se entrega en las mejores condiciones.

El criterio para decidir dónde va cada cosa:

| Va en el teléfono cuando… | Va en el servidor cuando… |
|---|---|
| depende de los sensores o de la cámara | necesita una GPU grande |
| el resultado permite avisar al usuario para que repita la toma | tarda minutos u horas |
| es barato y le ahorra al servidor un paso que puede fallar | necesita la imagen a resolución completa |

## Qué es video2mesh

Un programa en Python que convierte el video de un objeto en una malla con [COLMAP](https://colmap.github.io/) 4.2.1:

```
video ─► fotogramas nítidos ─► SfM (poses) ─► MVS denso (GPU) ─► recorte ─► Poisson ─► limpieza ─► OBJ / PLY / GLB
```

Corre en Windows con GPU NVIDIA y en la nube con la imagen Docker `ghcr.io/josexato/video2mesh:0.1.3`
(privada; base `colmap/colmap`, CUDA 12.9), probada en Runpod. Tarda 22–32 min por video de 80 fotogramas.

## Reparto propuesto

Es una propuesta de partida, hecha desde el lado de video2mesh. La columna «Navaja hoy» sale de
[ESCANER_3D.md](ESCANER_3D.md); hay que confirmarla contra el código.

| Paso | Dónde | Navaja hoy | Por qué |
|---|---|---|---|
| Elegir los cuadros más nítidos | Teléfono | Sí (modo 2: el más nítido cada ~2,5°) | Barato; evita subir cuadros movidos |
| Avisar de tramos con pocos puntos o cortes | Teléfono | En el informe, después de procesar | El usuario puede repetir la toma en el momento |
| Posición de la cámara y calibración | Teléfono | Sí (KLT + ajuste de haces, o las marcas de la hoja) | En el servidor el SfM tarda 2–5 min y a veces falla |
| Escala real | Teléfono | Sí en los modos 2 y 3 | El servidor no tiene cómo obtenerla |
| Plano de apoyo y ubicación del objeto | Teléfono | Sí (RANSAC + caja del objeto) | Le dice al servidor qué zona reconstruir |
| Siluetas del objeto | Teléfono | Sí (MagicTouch + consenso) | Sirven de máscaras y para recortar la nube densa |
| Nube densa y malla con concavidades | Servidor | No (casco visual) | PatchMatch en GPU: es el 84 % del tiempo de cómputo |
| Modelos generativos de imagen a 3D | Servidor | No | TRELLIS.2 y Pixal3D necesitan una GPU grande |
| Low poly para papercraft | Servidor | No es objetivo del escaneo | Parte de la malla o de las fotos ya procesadas |

## Qué debería exportar Navaja

El ZIP de los modos 1 y 2 ya trae `colmap/`, `poses.json`, las siluetas, las fotos clave, `sensores.csv` y el
video si se grabó con Navaja. Lo que falta revisar para que sea la mejor entrada del servidor:

- **Resolución.** Navaja trabaja con 960 px de lado largo. El MVS denso usa 1600 px en calidad media y hasta
  4096 en máxima. Hace falta exportar los cuadros clave a resolución completa, o el video original con la
  marca de tiempo de cada cuadro clave, y que las poses valgan para esa resolución.
- **Formato de `colmap/`.** Que el servidor pueda correr directamente `image_undistorter` →
  `patch_match_stereo` → `stereo_fusion` sin rehacer el SfM. Hoy video2mesh sólo acepta un video: del otro
  lado falta un modo de entrada «modelo COLMAP ya hecho».
- **Siluetas** alineadas con cada cuadro exportado, utilizables como `ImageReader.mask_path` de COLMAP.
- **Escala y orientación:** unidad (mm o arbitraria), plano de apoyo y eje vertical.
- **Fotos para los modelos generativos:** una o varias vistas frontales bien iluminadas, con el objeto
  recortado. Es lo que más influye en el resultado de esa ruta.
- **Indicadores de calidad** de la toma, para que la segunda aplicación decida qué ruta usar.

## Avisos de calidad al terminar la toma

Lo que en video2mesh se descubría tarde, después de 20 minutos de cómputo, y que el teléfono puede avisar
antes de enviar nada:

- Tramos con pocos puntos seguidos. En la máscara de prueba, los acercamientos borrosos daban 100–330
  puntos por cuadro.
- Cortes del seguimiento por movimiento brusco.
- Cobertura incompleta: huecos de ángulo y falta de una vuelta a otra altura.
- Superficie sin textura, brillante o transparente: sale como ruido con cualquier método.
- Objeto pequeño dentro del cuadro.

## Lecciones de video2mesh

**Verificación**

- Para saber si un modelo es fiel, dibujarlo desde las posiciones reales de las cámaras y compararlo con los
  fotogramas. Una cáscara delgada vista desde un ángulo cualquiera parece deforme aunque esté bien.
- Antes de culpar a la estimación de cámaras, mirar el error de reproyección y cuántos puntos hay por tramo.
- No suponer que el objeto se movió durante la toma. Comprobarlo con los datos antes de recortar el video.
- No cerrar huecos de un modelo sin preguntar: pueden ser funcionales, como las ranuras para ver de una
  máscara.

**Reconstrucción**

- Modelo de cámara `SIMPLE_RADIAL` para teléfonos y `SIMPLE_PINHOLE` para renders o grabaciones de pantalla.
  `OPENCV` fue inestable con campo de visión estrecho.
- Si el objeto gira frente a una cámara fija, como al grabar la pantalla del visor de Navaja, hay que
  enmascarar el fondo. Si no, las cámaras colapsan porque el fondo no se mueve.
- Ubicar el objeto en el punto al que miran todas las cámaras. Si hay acercamientos que lo descentran, usar
  «lo más cercano a la cámara en el centro del cuadro».
- Quitar el plano de apoyo con RANSAC y un margen adaptativo, y filtrar los puntos atípicos antes de Poisson.
- Tras simplificar la malla, transferir el color desde el vértice original más cercano.
- Guardar toda transformación que se aplique a la malla, para poder volver a las coordenadas de las cámaras.
- Hacer el flujo reanudable: saltar los pasos cuya salida ya existe.

## Runpod (para la segunda aplicación)

- Imagen fijada por versión, privada en GHCR, con la credencial de registro guardada en Runpod.
- Pod con `minCudaVersion` acorde a la imagen, volumen persistente en `/workspace`, puerto 22/tcp y la clave
  SSH pública registrada en la cuenta.
- Por SSH no se heredan las variables `ENV` del Dockerfile: usar rutas absolutas al entorno de Python y
  `/etc/profile.d`.
- El contenedor ve todos los núcleos del host (128–256), no su cuota. Hay que limitar los hilos con
  `RUNPOD_CPU_COUNT` o cgroups y fijar `OMP_NUM_THREADS`. Medido: SfM de 4 min 51 s a 2 min 27 s; Poisson de
  2 min 15 s a 8 s.
- Costo de referencia: RTX 4090 a 0,89 USD/h, unos 22 min por video de 80 fotogramas (0,35–0,40 USD). El
  paso denso es cerca del 84 % del tiempo, así que recibir las poses de Navaja ahorra poco tiempo; la
  ventaja es que el SfM no falla.
- Borrar el Pod al terminar: detenido sigue cobrando el disco.
- Las credenciales las introduce la persona dueña de la cuenta; nunca van en un chat ni en el repositorio.
