package com.josexato.navaja.lan;

import com.josexato.navaja.lan.core.HostProbe;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.PortUnreachableException;
import java.net.Socket;
import java.net.SocketTimeoutException;

/** HostProbe real: intenta un connect() TCP y traduce el resultado. */
final class SocketProbe implements HostProbe {

    @Override
    public State probe(String ip, int port, int timeoutMs) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(ip, port), timeoutMs);
            return State.OPEN;
        } catch (SocketTimeoutException e) {
            return State.TIMEOUT;
        } catch (ConnectException e) {
            // "Connection refused" = hay equipo, puerto cerrado. Otros = sin ruta.
            String m = e.getMessage();
            return m != null && m.toLowerCase().contains("refused") ? State.REFUSED : State.UNREACHABLE;
        } catch (NoRouteToHostException | PortUnreachableException e) {
            return State.UNREACHABLE;
        } catch (IOException e) {
            return State.TIMEOUT;
        }
    }
}
