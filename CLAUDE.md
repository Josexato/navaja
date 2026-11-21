# Notas para quien escriba código en Navaja

- **Hecho de hace 100 años:** en cada entrega (cada cambio que va a la rama principal) hay que cambiar `BuildFacts.FACT`
  (`app/src/main/java/com/josexato/navaja/BuildFacts.java`) por un hecho importante ocurrido cien años antes de ese día, o lo
  más cercano posible, con su fecha. Hay que comprobarlo en Wikipedia: la fecha tiene que constar en el texto del artículo. Lo elige quien escribe el código; se muestra en la pantalla de inicio junto con la
  versión, la fecha del código y las compilaciones del día, para reconocer la versión instalada.

## Antes de programar aceleración (GPU, NPU) o integrar un modelo

Primero buscar la documentación vigente del fabricante (Google LiteRT, Qualcomm, MediaTek…) y qué hay ya publicado: límites del delegado, operaciones soportadas, librerías en Maven y su licencia y peso. Recién después decidir qué programar. Resumirle al usuario lo encontrado, con las fuentes.
