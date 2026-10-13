package com.josexato.navaja.lan.core;

/**
 * Comprueba un puerto TCP de un equipo. Se abstrae para poder probar el
 * barrido con equipos simulados, sin abrir sockets reales.
 */
public interface HostProbe {

    enum State {
        OPEN,       // el puerto acepta la conexión
        REFUSED,    // RST: hay un equipo, pero el puerto está cerrado
        TIMEOUT,    // sin respuesta: probablemente no hay nadie (o filtrado)
        UNREACHABLE // la red no tiene ruta a esa IP
    }

    /** Intenta conectar a ip:port con el timeout dado (ms). */
    State probe(String ip, int port, int timeoutMs);
}
