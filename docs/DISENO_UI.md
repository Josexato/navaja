# Sistema de diseño de Navaja

Todas las pantallas nativas usan `com.josexato.navaja.ui`:

- `Palette`: colores, en Java puro.
- `NavajaUi`: vistas, construidas por código, sin XML ni librerías.

Para una pantalla nueva no se definen colores ni botones propios: se usan estos dos.

## Colores (`Palette`)

| Token | Valor | Uso |
|---|---|---|
| `BG` | `#121519` | Fondo de pantalla y cabecera |
| `SURFACE` | `#1B2028` | Tarjetas |
| `SURFACE_ALT` | `#232A35` | Métricas, botones secundarios, chips |
| `BORDER` | `#2E3744` | Borde fino de 1 dp (en vez de sombras) |
| `ACCENT` | `#E0913F` | **Único acento**: el ámbar del inicio. Acciones, selección, marca |
| `ON_ACCENT` | `#1A1206` | Texto sobre un botón ámbar |
| `TEXT` / `TEXT_2` / `TEXT_3` | `#F0F3F7` / `#A3AFBF` / `#8492A5` | Texto principal, secundario y tenue |
| `OK` / `GOOD` / `WARN` / `BAD` | `#6EE7A0` / `#5BD3E6` / `#FFD166` / `#FF8A7E` | Semáforo de estados |
| `UNKNOWN` | `#8492A5` | Sin dato |
| `NORTH` | `#FF5A4E` | Norte de la brújula |

**Regla:** el ámbar nunca reemplaza al semáforo. Cuando el color es el dato, se usan
los colores de estado. Pasa en RSRP/SINR, ΔE de la pantonera, el nivel, la afinación
y la precisión del GPS.

`PaletteTest` verifica que todo texto (incluidos los estados y el ámbar) tenga un
contraste de al menos **4,5:1** (WCAG AA) sobre las tres superficies.

## Componentes (`NavajaUi`)

| Componente | Uso |
|---|---|
| `screen(activity, título, subtítulo, acción[, scroll])` | Pantalla estándar: fondo, cabecera (← · título · subtítulo técnico · acción) y contenedor de contenido. El subtítulo se puede cambiar en vivo |
| `headerBar(...)` | Sólo la cabecera, para pantallas con un diseño propio (escáner 3D en horizontal) |
| `card(ctx)` / `cardBackground(ctx)` | Tarjeta normal / tocable (con efecto ripple) |
| `metric(ctx, etiqueta)` + `.set(valor, nota, estado)` | Métrica: etiqueta en mayúsculas, valor grande monoespaciado y nota. Con un color de estado, el valor y el borde toman ese color |
| `metricRow(...)`, `buttonRow(...)` | Filas de igual ancho con separación de 6 dp |
| `primary` / `secondary` / `headerAction` | Botón ámbar (una sola acción principal por pantalla) / secundario con borde / acción de cabecera |
| `chip` / `setChip` / `tabs` | Selección (cuerda, instrumento, acabado) y pestañas segmentadas |
| `text(ctx, s, Style)` | TITLE, SUBTITLE, BODY, SECONDARY, MUTED, LABEL, MONO |

## Medidas

- Radios: tarjetas 12 dp, controles 10 dp, chips 16 dp.
- Margen lateral de la pantalla: 16 dp.
- Altura táctil mínima: 44 dp.

## Diálogos

Las pantallas usan el tema oscuro de Material, así que los `AlertDialog` también
son oscuros. Su texto debe ir en `TEXT` / `TEXT_2`. Antes había texto oscuro sobre
diálogo oscuro en Red local, la Pantonera y Red móvil; ya está corregido.

## Inicio (WebView)

`assets/index.html` sigue el tema del sistema. En modo oscuro usa el mismo ámbar
`#E0913F`; en modo claro, `#C46A1A`. Las pantallas nativas son siempre oscuras,
pensadas para trabajo de campo.
