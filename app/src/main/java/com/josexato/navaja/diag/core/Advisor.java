package com.josexato.navaja.diag.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A partir de lo que el teléfono dice de sí mismo (fabricante y modelo del
 * chip, placa, librerías del fabricante instaladas), deduce de quién es el
 * chip y qué librería de aceleración de IA le corresponde. Java puro.
 */
public final class Advisor {

    private Advisor() { }

    public enum Vendor { QUALCOMM, MEDIATEK, SAMSUNG, GOOGLE, UNISOC, HISILICON, DESCONOCIDO }

    /** Librerías del sistema que delatan el acelerador de cada fabricante (nombre de archivo en /vendor o /system, lib o lib64). */
    public static final String[][] VENDOR_LIBS = {
            {"Qualcomm QNN (NPU Hexagon)", "libQnnHtp.so", "libQnnSystem.so", "libQnnHtpPrepare.so"},
            {"Qualcomm SNPE", "libSNPE.so"},
            {"Qualcomm DSP (FastRPC)", "libcdsprpc.so", "libadsprpc.so"},
            {"MediaTek NeuroPilot", "libneuronusdk_adapter.mtk.so", "libneuron_adapter.so", "libneuropilot_jni.so"},
            {"Samsung ENN / Eden (NPU Exynos)", "libenn_public_api_cpp.so", "libeden_nn_on_system.so", "libeden_rt_stub.edensdk.samsung.so"},
            {"Google Edge TPU (Tensor)", "libedgetpu_client.google.so", "libedgetpu_util.so"},
            {"OpenCL (GPU)", "libOpenCL.so", "libOpenCL-pixel.so", "libOpenCL-car.so"},
            {"NNAPI (Android)", "libneuralnetworks.so"},
    };

    /** De quién es el chip, según las cadenas del sistema (cualquiera puede venir vacía o null). */
    public static Vendor vendor(String socManufacturer, String socModel, String hardware, String board) {
        String s = (nz(socManufacturer) + " " + nz(socModel) + " " + nz(hardware) + " " + nz(board)).toLowerCase(Locale.ROOT);
        if (s.contains("qualcomm") || s.contains("qti") || s.contains("qcom") || s.contains("snapdragon") || s.matches(".*\\b(sm|sdm|msm|apq)\\d{3,4}.*")
                || s.contains("kalama") || s.contains("pineapple") || s.contains("taro") || s.contains("lahaina"))
            return Vendor.QUALCOMM;
        if (s.contains("mediatek") || s.matches(".*\\bmt\\d{4}.*") || s.contains("dimensity") || s.contains("helio")) return Vendor.MEDIATEK;
        if (s.contains("tensor") || s.matches(".*\\bgs\\d{3}.*") || s.contains("zuma") || s.contains("whitechapel")) return Vendor.GOOGLE;
        if (s.contains("exynos") || s.contains("samsung") || s.contains("s5e") || s.contains("universal")) return Vendor.SAMSUNG;
        if (s.contains("unisoc") || s.contains("spreadtrum") || s.matches(".*\\bums\\d{3,4}.*")) return Vendor.UNISOC;
        if (s.contains("hisilicon") || s.contains("kirin")) return Vendor.HISILICON;
        return Vendor.DESCONOCIDO;
    }

    private static String nz(String s) { return s == null ? "" : s; }

    /**
     * Qué conviene para correr modelos de IA (TFLite / LiteRT) en este
     * teléfono, de lo más prometedor a lo menos: una línea por opción.
     *
     * @param found    nombres de {@link #VENDOR_LIBS} (columna 0) cuyas librerías están en el sistema
     * @param sdk      nivel de API de Android
     * @param hasOpenCl si hay librería de OpenCL (la que usa el delegado de GPU de TFLite)
     */
    public static List<String> advice(Vendor v, List<String> found, int sdk, boolean hasOpenCl) {
        List<String> out = new ArrayList<>();
        switch (v) {
            case QUALCOMM:
                out.add((has(found, "QNN") ? "✔ " : "• ") + "NPU: delegado QNN de Qualcomm (AI Engine Direct), vía LiteRT. "
                        + (has(found, "QNN") ? "El sistema trae las librerías QNN." : "No se ven las librerías QNN del sistema: habría que empaquetarlas en la app."));
                break;
            case MEDIATEK:
                out.add((has(found, "NeuroPilot") ? "✔ " : "• ") + "NPU: delegado NeuroPilot de MediaTek, vía LiteRT. "
                        + (has(found, "NeuroPilot") ? "El sistema trae NeuroPilot." : "No se ven las librerías de NeuroPilot en el sistema."));
                break;
            case SAMSUNG:
                out.add((has(found, "Samsung ENN") ? "✔ " : "• ") + "NPU: la librería de Samsung para Exynos (ENN). No tiene delegado público de TFLite tan directo: "
                        + "lo práctico es GPU o NNAPI.");
                break;
            case GOOGLE:
                out.add((has(found, "Edge TPU") ? "✔ " : "• ") + "NPU: la TPU de los Tensor se usa por NNAPI (en Pixel suele funcionar bien) o por LiteRT.");
                break;
            default:
                out.add("• NPU: no se reconoce el fabricante del chip; se puede probar NNAPI.");
        }
        out.add((hasOpenCl ? "✔ " : "✘ ") + "GPU: delegado de GPU de TFLite/LiteRT (usa OpenCL; si no hay, OpenGL ES 3.1). "
                + (hasOpenCl ? "Hay OpenCL en el sistema." : "No se ve una librería de OpenCL: irá por OpenGL, más lento, o no funcionará."));
        out.add((sdk >= 27 ? "✔ " : "✘ ") + "NNAPI de Android (genérica): disponible desde Android 8.1"
                + (sdk >= 35 ? "; desde Android 15 está marcada como obsoleta, así que conviene no depender de ella." : "."));
        out.add("✔ CPU con XNNPACK: siempre funciona; es la referencia.");
        return out;
    }

    private static boolean has(List<String> found, String part) {
        for (String f : found) if (f.contains(part)) return true;
        return false;
    }
}
