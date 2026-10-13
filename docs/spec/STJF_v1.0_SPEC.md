# STJF v1.0 - Especificación del Estándar

**Simple Texture JSON Format v1.0**

Hermano de [SDJF](https://github.com/Josexato/AlmaGag) (Simple Diagram JSON Format):
misma filosofía —JSON simple, casi todo opcional, lo que falta se calcula—, pero
para describir **colores, texturas y acabados** de materiales: una pintura lisa,
una cerámica o un terrazzo con piedras de varios colores y tamaños.

## Introducción

Un archivo `.stjf` es una lista de **muestras**. Cada muestra es una
**composición**: un fondo (la *matriz*) y, si corresponde, **componentes**
(piedras, escamas, vetas) con su color, la fracción del área que ocupan y su
granulometría. Una pintura lisa es el caso simple: un solo color que ocupa el
100 %.

Principios:

1. **La identidad del color es su Lab**, no su nombre. Dos marcas pueden llamar
   distinto al mismo color (y al revés). Los nombres son etiquetas.
2. **Todo es opcional salvo `id`.** Lo que no se escribe, la app lo mide desde
   la foto (como el auto‑layout de SDJF cuando faltan `x`/`y`).
3. **Exportable a formatos abiertos sin perder lo importante**: glTF 2.0
   (aspecto, para 3D) y GPL (paleta de colores). Ver §7.

## Archivo mínimo

```json
{
  "samples": [
    { "id": "a101", "label": "Blanco hueso", "color": "#E3DACB" }
  ]
}
```

## Ejemplo completo

```json
{
  "stjf": "1.0",
  "samples": [
    {
      "id": "tz-204",
      "label": "Terrazzo gris con mármol blanco",
      "brand": "Proveedor X",
      "code": "TZ-204",
      "finish": "polished",
      "base": { "lab": [62.0, 0.4, 3.1], "fraction": 0.55 },
      "components": [
        { "id": "marmol", "label": "Mármol blanco", "lab": [90.1, 0.2, 2.0], "fraction": 0.25,
          "size_mm": { "p10": 3, "p50": 8, "p90": 15 }, "shape": "angular" },
        { "id": "negra", "label": "Piedra negra", "color": "#2B2A2C", "fraction": 0.12,
          "size_mm": { "p50": 5 } },
        { "id": "vidrio", "label": "Vidrio verde", "color": "#4F7A5A", "fraction": 0.08 }
      ],
      "appearance": { "texture": "tz-204.jpg", "roughness": 0.15, "mm_per_px": 0.1 },
      "measurement": { "shots": 3, "spread_de": 0.8, "reference": "paper-A4", "date": "2026-10-13T10:30" }
    }
  ]
}
```

Más ejemplos en [`docs/examples/`](../examples/).

## 1. Raíz

| Propiedad | Tipo | Requerido | Default | Descripción |
|-----------|------|-----------|---------|-------------|
| `stjf` | string | No | `"1.0"` | Versión del formato |
| `samples` | array | ✅ Sí | - | Lista de muestras |

## 2. Muestra (`samples[]`)

| Propiedad | Tipo | Requerido | Default | Descripción |
|-----------|------|-----------|---------|-------------|
| `id` | string | ✅ Sí | - | Único en el archivo. Sólo `A-Z a-z 0-9 - _` (válido como nombre en todos los formatos de exportación) |
| `label` | string | No | `id` | Nombre visible |
| `brand` | string | No | - | Marca o proveedor |
| `code` | string | No | - | Código comercial |
| `finish` | string | No | `matte` | `matte`, `satin`, `gloss`, `polished` |
| `color` | string | No | auto | Color CSS (`"beige"`) o hex (`"#E3DACB"`, `"#fff"`). Atajo para muestras lisas. `hex` se acepta como sinónimo |
| `lab` | [L, a, b] | No | auto | Color exacto en CIELAB (D65, 2°). **Gana sobre `color`** |
| `base` | objeto | No | = la muestra | La matriz (fondo): `color`/`lab` + `fraction` |
| `components` | array | No | `[]` | Piedras, escamas, vetas (§3) |
| `appearance` | objeto | No | - | Aspecto para render/exportación (§4) |
| `measurement` | objeto | No | - | Cómo se midió (§5); lo llena la app |

Las claves desconocidas se **conservan** al reescribir el archivo y se ignoran
al leerlo (útil para datos propios, p. ej. `"x-proveedor": {...}`).

## 3. Componente (`components[]`)

| Propiedad | Tipo | Requerido | Default | Descripción |
|-----------|------|-----------|---------|-------------|
| `id` | string | ✅ Sí | - | Único dentro de la muestra |
| `label` | string | No | `id` | "Mármol blanco" |
| `color` / `lab` | - | Sí (uno) | - | Igual que en la muestra |
| `fraction` | float | No | auto (§6) | Parte del área visible, 0–1 |
| `size_mm` | {p10, p50, p90} | No | - | Granulometría: diámetro equivalente en mm. Basta con `p50` |
| `shape` | string | No | `round` | `round`, `angular`, `flake`, `vein` |

**Granulometría.** `p10`, `p50` y `p90` son percentiles del diámetro
equivalente (el de un círculo de igual área), **ponderados por área**, como los
D10/D50/D90 de las normas de tamaño de partícula. `p50` es el tamaño típico;
`p10` y `p90`, las piedras chicas y las grandes. Debe cumplirse
`p10 ≤ p50 ≤ p90`. Las vetas (`vein`) no llevan granulometría.

## 4. Apariencia (`appearance`)

Pensada para mapear 1:1 a un material PBR de glTF 2.0.

| Propiedad | Tipo | Default | Descripción |
|-----------|------|---------|-------------|
| `texture` | string | - | Imagen (JPEG/PNG) junto al `.stjf`: **color base corregido de luz** (albedo), sin sombras ni reflejos. No una foto cruda |
| `mm_per_px` | float | - | Escala de la textura: mm reales por píxel. Permite repetirla a tamaño real |
| `roughness` | float 0–1 | según `finish` | 0 = espejo, 1 = totalmente mate (misma escala que glTF) |
| `metallic` | float 0–1 | `0` | Piedra y pintura no son metal |
| `normal_map` | string | - | Reservado: relieve (mapa de normales) |

`roughness` por defecto según `finish`: `matte` 0.9 · `satin` 0.6 · `gloss` 0.3 · `polished` 0.1.

## 5. Medición (`measurement`)

| Propiedad | Tipo | Descripción |
|-----------|------|-------------|
| `shots` | int | Fotos combinadas (idealmente con luces distintas) |
| `spread_de` | float | Máximo ΔE2000 de la matriz entre fotos. > 2 sugiere metamerismo |
| `reference` | string | Referencia usada, p. ej. `paper-A4`, `paper-Carta` |
| `date` | string | Fecha ISO 8601 |

## 6. Reglas de resolución

Como la "Prioridad de Resolución" de SDJF:

1. **`lab` gana sobre `color`** (igual que `width` explícito gana sobre `hp`).
2. **Color de la matriz:** `base.lab` → `base.color` → `lab` → `color` de la muestra.
3. **Fracciones:** la matriz y los componentes sin `fraction` se reparten en
   partes iguales lo que falta para llegar a 1. Una muestra sin componentes es
   100 % matriz.
4. **Si la suma supera 1**, se normaliza (aviso, no error).
5. **Un campo ausente no penaliza** al comparar: simplemente no se usa.
6. **Versiones:** un lector 1.x lee cualquier `1.y` ignorando lo que no conoce.

## 7. Exportación a formatos abiertos

### glTF 2.0 (Khronos) — aspecto, para 3D

Cada muestra es un material PBR sobre un cuadrado de 1 m × 1 m (plano XZ, normal +Y).

| STJF | glTF 2.0 |
|------|----------|
| `id` | `materials[].name`, `meshes[].name` |
| `label` | `nodes[].name` |
| `appearance.texture` | `images[]` + `textures[]` → `pbrMetallicRoughness.baseColorTexture` (sampler REPEAT) |
| `appearance.mm_per_px` | `KHR_texture_transform.scale` = 1000 mm / (ancho_px × mm_per_px) |
| sin textura, compuesta | textura **sintética** generada desde la composición (§8) |
| sin textura, lisa | `baseColorFactor` = Lab → RGB lineal (recortado a la gama sRGB) |
| `roughness` (o el de `finish`) | `roughnessFactor` |
| `metallic` | `metallicFactor` |
| la muestra completa | `materials[].extras.stjf` (permite volver a importar sin pérdida) |

Navaja exporta `.glb` (binario, todo en un archivo). Los archivos generados
pasan el validador oficial de Khronos (`gltf-validator`) sin errores ni avisos.

### GPL (paleta de GIMP, Inkscape, Krita, Scribus) — sólo colores

```text
GIMP Palette
Name: Mi pantonera
Columns: 4
#
227 218 203	a101 Blanco hueso
153 149 144	tz-204 matriz
229 226 223	tz-204 Mármol blanco
```

Las muestras compuestas se separan en una línea por componente (la fracción y
el tamaño no caben en GPL).

## 8. Textura sintética

Para muestras sin foto (p. ej. escritas a mano a partir de un catálogo), se
genera una textura repetible sin costuras: fondo con el color de la matriz y
piedras con el color, la fracción de área, la granulometría (log‑normal
ajustada a p10/p50/p90) y la forma (`angular` → polígonos) de cada componente.

## 9. Comparación de muestras

| Puntaje | Cálculo |
|---------|---------|
| Fondo | ΔE2000 entre matrices |
| Mezcla | Earth Mover's Distance entre composiciones, con ΔE2000 como coste |
| Piedras | razón de `p50` entre componentes emparejados por color |
| % piedras | diferencia de la fracción total de componentes |

Lectura del ΔE2000: < 1 no se distingue · 1–2 sólo con ojo entrenado ·
2–3,5 se nota lado a lado · 3,5–5 diferencia clara · > 5 es otro color.

## 10. Validación

| Nivel | Caso |
|-------|------|
| Error | JSON inválido, falta `samples`, falta `id`, `id` repetido o con caracteres no permitidos |
| Error | `lab` fuera de rango (L* 0–100, a*/b* ±128), muestra o componente sin color |
| Error | `fraction`, `roughness` o `metallic` fuera de 0–1, `size_mm` ≤ 0 o desordenado |
| Aviso | fracciones que suman > 1, `finish`/`shape` desconocido, versión distinta de 1.x |

Al importar, Navaja rechaza archivos con errores y muestra en qué muestra está
cada problema.
