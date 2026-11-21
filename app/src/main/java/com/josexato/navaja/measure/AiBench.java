package com.josexato.navaja.measure;

import android.content.Context;

import java.io.IOException;

/** Prueba de los aceleradores de IA con los modelos del escáner (XFeat y LighterGlue), para el diagnóstico del teléfono. */
public final class AiBench {

    private AiBench() { }

    /**
     * Carga los dos modelos probando CPU, GPU, NNAPI y la NPU de Qualcomm y
     * devuelve cómo le fue a cada uno ("XFeat: CPU 58 ms, GPU 12 ms, NNAPI no
     * sirve (…) → GPU · …").
     * Tarda unos segundos; no llamar en el hilo de la interfaz.
     */
    public static String run(Context c) throws IOException {
        try (XFeat x = new XFeat(c)) {
            return x.choiceNote;
        }
    }
}
