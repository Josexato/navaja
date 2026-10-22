# Red móvil · Modo ingeniería — Especificación v1.1

**Módulo:** `com.josexato.navaja.cellular` · **Estado:** implementado (fases 1–3)

Revisión de la propuesta v1.0. Corrige rangos de bandas y el paso del Timing
Advance, quita la "triangulación" sin datos de torres y documenta las
limitaciones reales de las APIs públicas de Android.

## 1. Principios

1. **Todo en el teléfono.** Sin OpenCellID, Mozilla Location ni servicios de Google.
2. **Sólo APIs públicas.** Lo que Android reserva a apps del sistema no se promete (§7).
3. **Nunca inventar valores.** Lo que el módem no informa se muestra como «—».
4. **Núcleo en Java puro** (`cellular/core`) con pruebas en la JVM.

## 2. Permisos

| Permiso | Para qué | Cuándo se pide |
|---|---|---|
| `ACCESS_FINE_LOCATION` (+ `COARSE`) | Android lo exige para `getAllCellInfo()`; GPS del registro | Al abrir «Red móvil» |
| `READ_PHONE_STATE` | Dual SIM (`SubscriptionManager`) e indicador 5G NSA (`TelephonyDisplayInfo`) | Al abrir «Red móvil» (opcional) |

Además, **la ubicación del sistema debe estar encendida**: con el permiso pero
la ubicación apagada, `getAllCellInfo()` devuelve una lista vacía. La app lo
detecta y ofrece abrir los ajustes.

## 3. Arquitectura

```
cellular/
├── CellularActivity.java   ← UI, permisos, Dual SIM, sondeo cada 2 s, GPS, exportación
├── CellReader.java         ← CellInfo / SignalStrength de Android → CellReading (resguardos de API)
└── core/                   ← Java puro, probado en la JVM (CellularTest)
    ├── CellReading.java    ← una celda (servidora o vecina); ausente = NA
    ├── BandCatalog.java    ← canal → frecuencia y banda (fórmulas 3GPP)
    ├── CellIds.java        ← eNB/sector (LTE) y gNB/celda (NR, largo configurable)
    ├── SignalMetrics.java  ← semáforo RSRP/RSRQ/SINR/CQI, normalización RSSNR, distancia TA
    ├── DriveLog.java       ← registro con GPS → CSV / KML
    └── TowerLocator.java   ← multilateración local de la torre (GPS + TA)
```

## 4. Fórmulas

### 4.1 Identificadores

- **LTE:** ECI (28 bits) = eNB ID (`ECI >> 8`, 20 bits) + celda (`ECI & 0xFF`). Ej.: 3657987 → eNB 14289, sector 3.
- **NR:** NCI (36 bits) = gNB ID (**22–32 bits, lo configura el operador**) + celda (el resto).
  No hay regla única: la app trae 24 bits por defecto y el botón «gNB: N bits» lo cambia (se recuerda).

### 4.2 Timing Advance

- **LTE:** 1 unidad = 16·Ts, Ts = 1/30,72 MHz → distancia = TA × c·16·Ts/2 = **TA × 78,07 m**
  (la v1.0 decía 78,12 m por redondear Ts antes de multiplicar). Rango 0–1282.
- **TA = 0 es válido** (menos de ~78 m). "Desconocido" es `CellInfo.UNAVAILABLE` (Integer.MAX_VALUE).
- Sólo existe **con conexión activa** (RRC conectado); en reposo Android entrega UNAVAILABLE.
- **NR:** Android 14+ expone `getTimingAdvanceMicros()`; distancia = t·c/2 (≈150 m por µs). Se lee por reflexión.

### 4.3 Bandas

En lugar de una lista elegida a mano (la v1.0 era europea: B20, «Dividendo Digital», `214‑07`),
se calcula cualquier banda con las tablas de 3GPP:

- **LTE** (TS 36.101 tabla 5.7.3‑1): `F_DL = F_DL_low + 0,1·(EARFCN − N_offs‑DL)`.
  Incluye B1–B8, B12–B14, B17–B21, B25, B26, B28–B30, B32, B38–B43, B46, B48, B66, B71.
- **NR** (TS 38.104 §5.4.2.1, ráster global):
  - 0–599 999: ΔF 5 kHz → `F = 0,005·N`
  - 600 000–2 016 666: ΔF 15 kHz → `F = 3000 + 0,015·(N − 600 000)`
  - 2 016 667–3 279 165: ΔF 60 kHz → `F = 24 250,08 + 0,06·(N − 2 016 667)`
  - La banda sale del rango de bajada (tablas 5.2‑1/5.2‑2).

Correcciones respecto de v1.0:

| | v1.0 | Correcto |
|---|---|---|
| B20 | EARFCN 6150–6599 | **6150–6449** (791–821 MHz) |
| B28 | 758–788 MHz | **758–803 MHz** (EARFCN 9210–9659) |
| n28 | NR‑ARFCN 140 000–160 000 | **151 600–160 600** |

**Bandas superpuestas** (B4/B66, n1/n66, n77/n78…): el canal no alcanza. Si Android informa la
banda (`getBands()`, API 30+) manda esa; si no, se muestran las candidatas con la de uso más
extendido primero (n78 antes que n77, n28 antes que n14).

### 4.4 Semáforo

| Métrica | Excelente | Buena | Regular | Crítica |
|---|---|---|---|---|
| RSRP (dBm) | > −85 | −85…−95 | −95…−110 | < −110 |
| RSRQ (dB) | > −8 | −8…−11 | −11…−15 | < −15 |
| SINR (dB) | > 15 | 10…15 | 0…10 | < 0 |
| CQI | 13–15 | 10–12 | 7–9 | 1–6 |

CQI → modulación aproximada (tabla de 256QAM): ≥12 256QAM, ≥8 64QAM, ≥5 16QAM, si no QPSK.

**RSSNR:** según el fabricante viene en dB (−20…30) o en décimas de dB (−200…300). Se normaliza a dB.

## 5. 5G NSA

En NSA (casi todo el 5G desplegado) Android normalmente **no** reporta la celda NR como
registrada. Se combinan:

1. `TelephonyDisplayInfo.getOverrideNetworkType()` (API 30+) → indicador «5G NSA», «5G+», «LTE‑CA».
2. `SignalStrength.getCellSignalStrengths()` → SS‑RSRP/RSRQ/SINR de la portadora NR, que se muestra
   como «Portadora 5G NSA» (sin identificadores: Android no los expone en NSA).
3. Celdas `CellInfoNr` no registradas en `getAllCellInfo()` → aparecen como vecinas.

## 6. Registro de recorrido y torres estimadas

- **Registro:** con «Grabar recorrido», cada 2 s se guarda la celda servidora + la posición GPS
  (se descartan puntos con precisión peor que 50 m). La pantalla queda encendida; no hay servicio en
  segundo plano.
- **Exportación:** CSV (planilla) y KML (Google Earth/QGIS) con los puntos coloreados por RSRP y las
  torres estimadas. Archivos en `Android/data/com.josexato.navaja/files/redmovil/`.
- **Torre estimada:** para cada eNB/gNB con ≥ 4 mediciones con TA desde lugares distintos,
  multilateración por mínimos cuadrados ponderados (σ² = (paso_TA/2)²/3 + precisión_GPS²), con
  búsqueda en grilla + Gauss‑Newton. Si el recorrido es casi recto se marca **ambigua** (hay una
  solución espejo al otro lado). Reemplaza la «triangulación» de la v1.0, que no era posible sin
  coordenadas de torres.

## 7. Lo que Android no permite a una app normal

- Agregación de portadoras (CA) y canales físicos: `PhysicalChannelConfig` requiere
  `READ_PRECISE_PHONE_STATE`, reservado al sistema.
- Menús del fabricante (Samsung `*#0011#`, MTK/Qualcomm Field Test): son apps del sistema.
- Identidad de la celda NR en NSA (ver §5).
- Frecuencia de actualización: `requestCellInfoUpdate()` está limitada por el sistema; la app pide
  cada 2 s y usa lo último disponible si Android la frena.

## 8. Pruebas (`CellularTest`, JVM, sin hardware)

Bordes de bandas LTE (incluidos los corregidos), ráster NR y bandas superpuestas, prioridad de
`getBands()`, eNB/sector, gNB con distintos largos, TA (0 válido, ausente = NaN), semáforo,
normalización de RSSNR, torre estimada con un recorrido sintético en L (error < 60 m), recorrido
recto marcado como ambiguo, datos insuficientes, GPS impreciso descartado y formato CSV/KML.
