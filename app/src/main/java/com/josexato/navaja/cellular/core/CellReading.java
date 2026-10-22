package com.josexato.navaja.cellular.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Una celda vista por el módem en un instante (servidora o vecina). Valores
 * numéricos ausentes = {@link #NA} (el mismo Integer.MAX_VALUE que usa
 * Android en CellInfo.UNAVAILABLE), nunca un número inventado.
 */
public final class CellReading {

    public static final int NA = Integer.MAX_VALUE;
    public static final long NA_LONG = Long.MAX_VALUE;

    public enum Rat {
        GSM("2G GSM"), WCDMA("3G WCDMA"), LTE("4G LTE"), NR("5G NR");

        public final String label;

        Rat(String label) { this.label = label; }
    }

    public Rat rat;
    public boolean registered;
    public String mcc, mnc, operator;
    /** LTE: ECI (28 bits); NR: NCI (36 bits); GSM/WCDMA: CID. */
    public long cellId = NA_LONG;
    /** TAC (LTE/NR) o LAC (GSM/WCDMA). */
    public int area = NA;
    /** PCI (LTE/NR), PSC (WCDMA), BSIC (GSM). */
    public int pci = NA;
    /** EARFCN, NR‑ARFCN, UARFCN o ARFCN. */
    public int channel = NA;
    /** Bandas informadas por Android (API 30+); vacío si no las da. */
    public final List<Integer> reportedBands = new ArrayList<>();
    /** dBm / dB. RSRP/SS‑RSRP, RSRQ, SINR/RSSNR; RSSI o dBm para 2G/3G. */
    public int rsrp = NA, rsrq = NA, sinr = NA, rssi = NA;
    public int cqi = NA;
    public int timingAdvance = NA;
    /** Distancia por TA en metros (LTE por unidades de TA; NR por µs en API 34+); NaN si no hay. */
    public double taMeters = Double.NaN;
    /** Ancho de banda de la portadora en kHz (LTE, API 28+). */
    public int bandwidthKhz = NA;

    public static boolean has(int v) { return v != NA; }

    public String plmn() {
        return mcc == null || mnc == null ? null : mcc + "-" + mnc;
    }

    /** Banda a mostrar: la que informa Android o, si no, la deducida del canal. */
    public String bandLabel() {
        if (!has(channel) && reportedBands.isEmpty()) return null;
        BandCatalog.Info info = !has(channel) ? null
                : rat == Rat.LTE ? BandCatalog.lte(channel)
                : rat == Rat.NR ? BandCatalog.nr(channel) : null;
        if (!reportedBands.isEmpty()) {
            int b = reportedBands.get(0);
            String name = rat == Rat.NR ? BandCatalog.nrName(b) : BandCatalog.lteName(b);
            String p = rat == Rat.NR ? "n" : "B";
            String f = info == null ? "" : String.format(Locale.US, " · %.1f MHz", info.dlMhz);
            return p + b + (name == null ? "" : " (" + name + ")") + f;
        }
        return info == null ? null : info.label;
    }

    /** Clave estable para agrupar mediciones de la misma estación base. */
    public String siteKey(int gnbBits) {
        if (cellId == NA_LONG) return null;
        if (rat == Rat.LTE) return "LTE " + plmn() + " eNB " + CellIds.enb(cellId);
        if (rat == Rat.NR) return "NR " + plmn() + " gNB " + CellIds.gnb(cellId, gnbBits);
        return rat.name() + " " + plmn() + " " + area + "/" + cellId;
    }
}
