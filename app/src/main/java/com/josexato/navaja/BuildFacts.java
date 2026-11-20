package com.josexato.navaja;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * El texto de la pantalla de inicio que dice qué versión es: cuándo se
 * terminó de escribir el código, algo que pasó hace 100 años y cuántas
 * veces se compiló ese día. Java puro, para poder probarlo.
 */
public final class BuildFacts {

    private BuildFacts() { }

    /** Hechos (año, mes, día, qué pasó), en orden de fecha. Se muestra el más reciente hasta el día de hace 100 años. */
    static final Object[][] FACTS = {
            {1926, 1, 26, "John Logie Baird mostró en Londres la primera televisión que funcionaba"},
            {1926, 3, 16, "Robert Goddard lanzó el primer cohete de combustible líquido"},
            {1926, 5, 12, "el dirigible Norge, con Amundsen y Nobile, sobrevoló el Polo Norte"},
            {1926, 8, 6, "Gertrude Ederle fue la primera mujer en cruzar a nado el canal de la Mancha"},
            {1926, 10, 14, "se publicó «Winnie-the-Pooh», de A. A. Milne"},
            {1926, 10, 31, "murió el mago Harry Houdini"},
            {1926, 11, 15, "empezó a transmitir la cadena de radio NBC en Estados Unidos"},
            {1926, 12, 3, "desapareció Agatha Christie; apareció once días después"},
            {1926, 12, 25, "Hirohito pasó a ser emperador de Japón"},
            {1927, 1, 7, "se inauguró el servicio telefónico entre Nueva York y Londres"},
            {1927, 1, 10, "se estrenó «Metrópolis», de Fritz Lang, en Berlín"},
            {1927, 5, 21, "Charles Lindbergh aterrizó en París tras cruzar solo el Atlántico sin escalas"},
            {1927, 9, 7, "Philo Farnsworth transmitió la primera imagen de televisión totalmente electrónica"},
            {1927, 10, 6, "se estrenó «El cantante de jazz», la primera gran película sonora"},
            {1928, 9, 28, "Alexander Fleming notó el moho que llevó a la penicilina"},
            {1928, 11, 18, "se estrenó «Steamboat Willie», el debut de Mickey Mouse"},
            {1929, 10, 24, "el Jueves Negro de Wall Street abrió la Gran Depresión"},
    };

    private static final String[] MONTHS = {"enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto", "septiembre", "octubre",
            "noviembre", "diciembre"};

    /** El hecho para el día {@code y-m-d} (ya restados los 100 años): "el 15 de noviembre de 1926 empezó…", o "" si no hay ninguno anterior. */
    static String fact(int y, int m, int d) {
        Object[] best = null;
        for (Object[] f : FACTS) {
            int fy = (Integer) f[0], fm = (Integer) f[1], fd = (Integer) f[2];
            if (fy < y || (fy == y && (fm < m || (fm == m && fd <= d)))) best = f;
        }
        if (best == null) return "";
        boolean same = (Integer) best[0] == y && (Integer) best[1] == m && (Integer) best[2] == d;
        return (same ? "justo ese día, el " : "el ") + best[2] + " de " + MONTHS[(Integer) best[1] - 1] + " de " + best[0] + ", " + best[3];
    }

    /** El texto completo para la versión {@code version}, con el código terminado en {@code codeTime} (ms) y {@code buildsToday} compilaciones ese día. */
    public static String line(String version, long codeTime, int buildsToday) {
        TimeZone lima = TimeZone.getTimeZone("America/Lima");
        SimpleDateFormat df = new SimpleDateFormat("d/M/yyyy HH:mm", Locale.ROOT);
        df.setTimeZone(lima);
        Calendar c = Calendar.getInstance(lima);
        c.setTime(new Date(codeTime));
        String f = fact(c.get(Calendar.YEAR) - 100, c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
        StringBuilder s = new StringBuilder("Versión ").append(version).append(" · código terminado el ").append(df.format(new Date(codeTime)))
                .append(" (hora de Lima).");
        if (!f.isEmpty()) s.append(" Hace 100 años: ").append(f).append('.');
        if (buildsToday > 1) s.append(" Y tú ya compilaste esto ").append(buildsToday).append(" veces hoy.");
        return s.toString();
    }
}
