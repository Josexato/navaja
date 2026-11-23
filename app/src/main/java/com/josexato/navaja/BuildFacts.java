package com.josexato.navaja;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * El texto de la pantalla de inicio que dice qué versión es: cuándo se
 * terminó de escribir el código, algo que pasó hace 100 años (elegido en
 * cada entrega, ver {@link #FACT}) y cuántas
 * veces se compiló ese día. Java puro, para poder probarlo.
 */
public final class BuildFacts {

    private BuildFacts() { }

    /**
     * Un hecho importante de hace 100 años, elegido a mano por quien escribe
     * el código: <b>se cambia en cada entrega</b> (cada cambio que se sube a
     * la rama principal), por uno cercano a la fecha de ese día cien años
     * atrás, comprobado en Wikipedia (la fecha tiene que constar en el texto del
     * artículo). Así el texto del inicio distingue una versión de otra de un vistazo.
     */
    static final String FACT = "el 23 de noviembre de 1926 nació en Misisipi R. L. Burnside, cantante y guitarrista de blues";

    /** El texto completo para la versión {@code version}, con el código terminado en {@code codeTime} (ms) y {@code buildsToday} compilaciones ese día. */
    public static String line(String version, long codeTime, int buildsToday) {
        TimeZone lima = TimeZone.getTimeZone("America/Lima");
        SimpleDateFormat df = new SimpleDateFormat("d/M/yyyy HH:mm", Locale.ROOT);
        df.setTimeZone(lima);
        String f = FACT;
        StringBuilder s = new StringBuilder("Versión ").append(version).append(" · código terminado el ").append(df.format(new Date(codeTime)))
                .append(" (hora de Lima).");
        if (!f.isEmpty()) s.append(" Hace 100 años: ").append(f).append('.');
        if (buildsToday > 1) s.append(" Y tú ya compilaste esto ").append(buildsToday).append(" veces hoy.");
        return s.toString();
    }
}
