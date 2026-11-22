# Seguir un escaneo en un servidor: contexto de video2mesh

El ZIP de los modos 1 y 2 del Escáner 3D trae `colmap/`, `poses.json` y las siluetas «para seguir en un
servidor» (ver [ESCANER_3D.md](ESCANER_3D.md)). Este documento resume lo aprendido en **video2mesh**, un
proyecto aparte que hace esa parte: fotogrametría densa con GPU. Sirve de contexto para quien programe la
conexión entre los dos. Nada de lo que sigue está integrado todavía en Navaja.

## Qué es video2mesh

Un programa en Python que convierte el video de un objeto en una malla con [COLMAP](https://colmap.github.io/) 4.2.1:

```
video ─► fotogramas nítidos ─► SfM (poses) ─► MVS denso (GPU) ─► recorte ─► Poisson ─► limpieza ─► OBJ / PLY / GLB
```

Corre en Windows con GPU NVIDIA y en la nube con la imagen Docker `ghcr.io/josexato/video2mesh:0.1.3`
(privada; base `colmap/colmap`, CUDA 12.9), probada en Runpod.

## Cómo se complementan

| | Navaja (en el teléfono) | video2mesh (en el servidor) |
|---|---|---|
| Posición de la cámara | KLT + ajuste de haces, o las marcas de la hoja | SfM de COLMAP con SIFT |
| Forma | Casco visual con siluetas | MVS denso + Poisson |
| Concavidades | No las ve | Sí |
| Escala | mm en los modos 2 y 3 | Unidades arbitrarias |
| Tiempo | Segundos a minutos | 22–32 min por video |

- **El servidor aporta lo que al teléfono le falta.** El casco visual no reproduce el hueco de una máscara;
  el MVS denso sí.
- **Con `colmap/` del ZIP el servidor puede saltarse el SfM** y correr sólo `image_undistorter` →
  `patch_match_stereo` → `stereo_fusion` → Poisson. Hoy video2mesh sólo acepta un video: falta un modo de
  entrada «modelo COLMAP ya hecho».
- **Resolución.** Navaja trabaja con 960 px de lado largo. Para MVS denso conviene mandar el video original
  o los cuadros clave a resolución completa: video2mesh usa 1600 px en calidad media y hasta 4096 en máxima.
- **Siluetas.** Las de Navaja sirven como máscaras de COLMAP (`ImageReader.mask_path`) y para recortar la
  nube densa alrededor del objeto.
- **Escala.** En el modo 2 las poses ya están en mm, así que la malla del servidor saldría con medidas
  reales, cosa que video2mesh por sí solo no puede dar.

## Prácticas que funcionaron

**Verificación**

- Dibujar la malla desde las posiciones reales de las cámaras y compararla con los fotogramas. Una cáscara
  delgada vista desde un ángulo cualquiera parece deforme aunque esté bien.
- Antes de culpar al SfM, mirar los puntos seguidos: error de reproyección y cuántos hay por tramo. Los
  acercamientos borrosos dan pocos (100–330 por cuadro en la máscara de prueba).
- No suponer que el objeto se movió durante la toma. Comprobarlo con los datos antes de recortar el video.

**Reconstrucción**

- Elegir el fotograma más nítido de cada tramo (varianza del Laplaciano).
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
- El metal pulido, lo transparente y lo liso sin textura salen como ruido también con MVS denso.

## Runpod

- Imagen fijada por versión, privada en GHCR, con la credencial de registro guardada en Runpod.
- Pod con `minCudaVersion` acorde a la imagen, volumen persistente en `/workspace`, puerto 22/tcp y la clave
  SSH pública registrada en la cuenta.
- Por SSH no se heredan las variables `ENV` del Dockerfile: usar rutas absolutas al entorno de Python y
  `/etc/profile.d`.
- El contenedor ve todos los núcleos del host (128–256), no su cuota. Hay que limitar los hilos con
  `RUNPOD_CPU_COUNT` o cgroups y fijar `OMP_NUM_THREADS`. Medido: SfM de 4 min 51 s a 2 min 27 s; Poisson de
  2 min 15 s a 8 s.
- Costo de referencia: RTX 4090 a 0,89 USD/h, unos 22 min por video de 80 fotogramas (0,35–0,40 USD). El
  paso denso es cerca del 84 % del tiempo, así que saltarse el SfM ahorra poco tiempo; la ventaja es que no
  falla.
- Borrar el Pod al terminar: detenido sigue cobrando el disco.
- Las credenciales las introduce la persona dueña de la cuenta; nunca van en un chat ni en el repositorio.
