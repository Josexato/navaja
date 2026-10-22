package com.josexato.navaja.cellular;

import android.os.Build;
import android.telephony.CellIdentityGsm;
import android.telephony.CellIdentityLte;
import android.telephony.CellIdentityNr;
import android.telephony.CellIdentityWcdma;
import android.telephony.CellInfo;
import android.telephony.CellInfoGsm;
import android.telephony.CellInfoLte;
import android.telephony.CellInfoNr;
import android.telephony.CellInfoWcdma;
import android.telephony.CellSignalStrength;
import android.telephony.CellSignalStrengthGsm;
import android.telephony.CellSignalStrengthLte;
import android.telephony.CellSignalStrengthNr;
import android.telephony.SignalStrength;

import com.josexato.navaja.cellular.core.CellReading;
import com.josexato.navaja.cellular.core.SignalMetrics;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Traduce CellInfo/SignalStrength de Android al modelo puro {@link CellReading},
 * con los resguardos de nivel de API (minSdk 24). Lo que el módem no informa
 * queda como NA: nunca se rellena con valores supuestos.
 */
final class CellReader {

    private CellReader() { }

    static List<CellReading> read(List<CellInfo> infos) {
        List<CellReading> out = new ArrayList<>();
        if (infos == null) return out;
        for (CellInfo ci : infos) {
            CellReading r = null;
            if (ci instanceof CellInfoLte) r = lte((CellInfoLte) ci);
            else if (Build.VERSION.SDK_INT >= 29 && ci instanceof CellInfoNr) r = nr((CellInfoNr) ci);
            else if (ci instanceof CellInfoWcdma) r = wcdma((CellInfoWcdma) ci);
            else if (ci instanceof CellInfoGsm) r = gsm((CellInfoGsm) ci);
            if (r == null) continue;
            r.registered = ci.isRegistered();
            out.add(r);
        }
        // Servidoras primero, luego vecinas por señal.
        out.sort((a, b) -> {
            if (a.registered != b.registered) return a.registered ? -1 : 1;
            return Integer.compare(sortSignal(b), sortSignal(a));
        });
        return out;
    }

    private static int sortSignal(CellReading r) {
        if (CellReading.has(r.rsrp)) return r.rsrp;
        if (CellReading.has(r.rssi)) return r.rssi;
        return Integer.MIN_VALUE;
    }

    /**
     * 5G NSA: la portadora NR suele no aparecer como celda registrada, pero su
     * señal sí viene en SignalStrength. Devuelve una lectura NR sin
     * identificadores, o null si no hay.
     */
    static CellReading nsaFromSignal(SignalStrength ss) {
        if (ss == null || Build.VERSION.SDK_INT < 29) return null;
        for (CellSignalStrength s : ss.getCellSignalStrengths()) {
            if (s instanceof CellSignalStrengthNr) {
                CellSignalStrengthNr n = (CellSignalStrengthNr) s;
                CellReading r = new CellReading();
                r.rat = CellReading.Rat.NR;
                r.registered = true;
                r.rsrp = valid(n.getSsRsrp(), -156, -31);
                r.rsrq = valid(n.getSsRsrq(), -43, 20);
                r.sinr = valid(n.getSsSinr(), -23, 40);
                if (!CellReading.has(r.rsrp)) r.rsrp = valid(n.getCsiRsrp(), -156, -31);
                return CellReading.has(r.rsrp) ? r : null;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------- LTE

    private static CellReading lte(CellInfoLte info) {
        CellIdentityLte id = info.getCellIdentity();
        CellSignalStrengthLte s = info.getCellSignalStrength();
        CellReading r = new CellReading();
        r.rat = CellReading.Rat.LTE;
        int ci = id.getCi();
        r.cellId = ci == Integer.MAX_VALUE || ci < 0 ? CellReading.NA_LONG : ci;
        r.area = valid(id.getTac(), 0, 65535);
        r.pci = valid(id.getPci(), 0, 503);
        r.channel = valid(id.getEarfcn(), 0, 262143);
        plmn(r, id);
        if (Build.VERSION.SDK_INT >= 28) r.bandwidthKhz = valid(id.getBandwidth(), 1400, 20000);
        if (Build.VERSION.SDK_INT >= 30) for (int b : id.getBands()) r.reportedBands.add(b);
        if (Build.VERSION.SDK_INT >= 26) {
            r.rsrp = valid(s.getRsrp(), -140, -43);
            r.rsrq = valid(s.getRsrq(), -34, 3);
            r.sinr = SignalMetrics.normalizeRssnr(s.getRssnr());
            r.cqi = valid(s.getCqi(), 0, 15);
        } else {
            r.rsrp = valid(s.getDbm(), -140, -43);
        }
        if (Build.VERSION.SDK_INT >= 29) r.rssi = valid(s.getRssi(), -113, -51);
        r.timingAdvance = valid(s.getTimingAdvance(), 0, 1282);
        r.taMeters = SignalMetrics.lteTaMeters(r.timingAdvance);
        return r;
    }

    @SuppressWarnings("deprecation") // getMcc()/getMnc() int: única opción antes de API 28
    private static void plmn(CellReading r, CellIdentityLte id) {
        if (Build.VERSION.SDK_INT >= 28) {
            r.mcc = id.getMccString();
            r.mnc = id.getMncString();
            CharSequence op = id.getOperatorAlphaLong();
            r.operator = op == null ? null : op.toString();
        } else {
            int mcc = id.getMcc(), mnc = id.getMnc();
            if (mcc != Integer.MAX_VALUE) r.mcc = String.valueOf(mcc);
            if (mnc != Integer.MAX_VALUE) r.mnc = String.format(java.util.Locale.US, "%02d", mnc);
        }
    }

    // -------------------------------------------------------------------- NR

    @androidx.annotation.RequiresApi(29)
    private static CellReading nr(CellInfoNr info) {
        CellIdentityNr id = (CellIdentityNr) info.getCellIdentity();
        CellSignalStrengthNr s = (CellSignalStrengthNr) info.getCellSignalStrength();
        CellReading r = new CellReading();
        r.rat = CellReading.Rat.NR;
        long nci = id.getNci();
        r.cellId = nci == Long.MAX_VALUE || nci < 0 ? CellReading.NA_LONG : nci;
        r.area = valid(id.getTac(), 0, 16777215);
        r.pci = valid(id.getPci(), 0, 1007);
        r.channel = valid(id.getNrarfcn(), 0, 3279165);
        r.mcc = id.getMccString();
        r.mnc = id.getMncString();
        CharSequence op = id.getOperatorAlphaLong();
        r.operator = op == null ? null : op.toString();
        if (Build.VERSION.SDK_INT >= 30) for (int b : id.getBands()) r.reportedBands.add(b);
        r.rsrp = valid(s.getSsRsrp(), -156, -31);
        r.rsrq = valid(s.getSsRsrq(), -43, 20);
        r.sinr = valid(s.getSsSinr(), -23, 40);
        if (!CellReading.has(r.rsrp)) r.rsrp = valid(s.getCsiRsrp(), -156, -31);
        // TA de NR (Android 14+): en µs; distancia = t·c/2. Por reflexión para no
        // depender de la firma exacta del SDK.
        int taUs = nrTimingAdvanceMicros(s);
        if (CellReading.has(taUs)) {
            r.timingAdvance = taUs;
            r.taMeters = taUs * 1e-6 * 299_792_458.0 / 2;
        }
        return r;
    }

    private static int nrTimingAdvanceMicros(CellSignalStrengthNr s) {
        if (Build.VERSION.SDK_INT < 34) return CellReading.NA;
        try {
            Method m = s.getClass().getMethod("getTimingAdvanceMicros");
            int v = (Integer) m.invoke(s);
            return v >= 0 && v <= 1282 ? v : CellReading.NA;
        } catch (Exception e) {
            return CellReading.NA;
        }
    }

    // ------------------------------------------------------------- 2G / 3G

    @SuppressWarnings("deprecation")
    private static CellReading wcdma(CellInfoWcdma info) {
        CellIdentityWcdma id = info.getCellIdentity();
        CellReading r = new CellReading();
        r.rat = CellReading.Rat.WCDMA;
        int cid = id.getCid();
        r.cellId = cid == Integer.MAX_VALUE || cid < 0 ? CellReading.NA_LONG : cid;
        r.area = valid(id.getLac(), 0, 65535);
        r.pci = valid(id.getPsc(), 0, 511);
        r.channel = valid(id.getUarfcn(), 0, 16383);
        if (Build.VERSION.SDK_INT >= 28) {
            r.mcc = id.getMccString();
            r.mnc = id.getMncString();
        }
        r.rssi = valid(info.getCellSignalStrength().getDbm(), -120, -24);
        return r;
    }

    @SuppressWarnings("deprecation")
    private static CellReading gsm(CellInfoGsm info) {
        CellIdentityGsm id = info.getCellIdentity();
        CellSignalStrengthGsm s = info.getCellSignalStrength();
        CellReading r = new CellReading();
        r.rat = CellReading.Rat.GSM;
        int cid = id.getCid();
        r.cellId = cid == Integer.MAX_VALUE || cid < 0 ? CellReading.NA_LONG : cid;
        r.area = valid(id.getLac(), 0, 65535);
        r.pci = valid(id.getBsic(), 0, 63);
        r.channel = valid(id.getArfcn(), 0, 1023);
        if (Build.VERSION.SDK_INT >= 28) {
            r.mcc = id.getMccString();
            r.mnc = id.getMncString();
        }
        r.rssi = valid(s.getDbm(), -113, -51);
        if (Build.VERSION.SDK_INT >= 26) r.timingAdvance = valid(s.getTimingAdvance(), 0, 219);
        return r;
    }

    /** Valor dentro del rango físico, o NA (Android a veces entrega basura como 0 o MAX). */
    static int valid(int v, int lo, int hi) {
        return v == Integer.MAX_VALUE || v < lo || v > hi ? CellReading.NA : v;
    }
}
