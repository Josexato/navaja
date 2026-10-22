package com.josexato.navaja.marker.core;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Valores EXIF de GPS (formato de la norma EXIF 2.3: grados, minutos y
 * segundos como racionales "num/den"). Java puro para poder probarlo; la
 * escritura en el JPEG la hace ExifInterface de Android.
 */
public final class ExifGps {

    private ExifGps() { }

    /** Grados decimales (valor absoluto) → "gg/1,mm/1,ssss/1000". */
    public static String dms(double deg) {
        double a = Math.abs(deg);
        int d = (int) a;
        double minF = (a - d) * 60;
        int m = (int) minF;
        long sMilli = Math.round((minF - m) * 60 * 1000);
        // Redondeo que llega a 60 s o 60 min: se acarrea.
        if (sMilli >= 60_000) { sMilli -= 60_000; m++; }
        if (m >= 60) { m -= 60; d++; }
        return d + "/1," + m + "/1," + sMilli + "/1000";
    }

    /** "gg/1,mm/1,ss/1000" → grados decimales (positivos). */
    public static double parseDms(String s) {
        String[] p = s.split(",");
        return rational(p[0]) + rational(p[1]) / 60 + rational(p[2]) / 3600;
    }

    static double rational(String r) {
        String[] f = r.trim().split("/");
        double num = Double.parseDouble(f[0]);
        return f.length > 1 ? num / Double.parseDouble(f[1]) : num;
    }

    public static String latRef(double lat) { return lat < 0 ? "S" : "N"; }

    public static String lonRef(double lon) { return lon < 0 ? "W" : "E"; }

    /** Altitud absoluta en décimas de metro: "12345/10". */
    public static String altitude(double altM) {
        return Math.round(Math.abs(altM) * 10) + "/10";
    }

    /** 0 = sobre el nivel de referencia, 1 = bajo él. */
    public static String altitudeRef(double altM) { return altM < 0 ? "1" : "0"; }

    /** Fecha y hora UTC del GPS: {"yyyy:MM:dd", "HH/1,mm/1,ss/1"}. */
    public static String[] dateTime(long timeMs) {
        SimpleDateFormat d = new SimpleDateFormat("yyyy:MM:dd", Locale.US);
        SimpleDateFormat h = new SimpleDateFormat("H/1,m/1,s/1", Locale.US);
        d.setTimeZone(TimeZone.getTimeZone("UTC"));
        h.setTimeZone(TimeZone.getTimeZone("UTC"));
        Date t = new Date(timeMs);
        return new String[]{d.format(t), h.format(t)};
    }
}
