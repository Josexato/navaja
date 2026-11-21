package com.josexato.navaja.diag.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class AdvisorTest {

    @Test
    public void reconoceElFabricanteDelChip() {
        assertEquals(Advisor.Vendor.QUALCOMM, Advisor.vendor("QTI", "SM8650", "qcom", "pineapple"));
        assertEquals(Advisor.Vendor.QUALCOMM, Advisor.vendor("", "", "qcom", "kalama"));
        assertEquals(Advisor.Vendor.MEDIATEK, Advisor.vendor("Mediatek", "MT6989", "mt6989", "k6989v1_64"));
        assertEquals(Advisor.Vendor.SAMSUNG, Advisor.vendor("Samsung", "s5e9945", "s5e9945", "s5e9945"));
        // Un Samsung con Snapdragon es Qualcomm: manda el chip, no la marca del teléfono
        assertEquals(Advisor.Vendor.QUALCOMM, Advisor.vendor("QTI", "SM8750", "qcom", "sun"));
        assertEquals(Advisor.Vendor.GOOGLE, Advisor.vendor("Google", "Tensor G4", "zumapro", "husky"));
        assertEquals(Advisor.Vendor.DESCONOCIDO, Advisor.vendor(null, null, "ranchu", "goldfish"));
    }

    @Test
    public void aconsejaSegunLoQueHay() {
        List<String> a = Advisor.advice(Advisor.Vendor.QUALCOMM, Arrays.asList("Qualcomm QNN (NPU Hexagon)", "OpenCL (GPU)"), 35, true);
        assertTrue(a.get(0), a.get(0).startsWith("✔ NPU: delegado QNN"));
        assertTrue(a.get(1), a.get(1).startsWith("✔ GPU"));
        assertTrue(a.get(2), a.get(2).contains("obsoleta"));
        List<String> b = Advisor.advice(Advisor.Vendor.MEDIATEK, Collections.<String>emptyList(), 30, false);
        assertTrue(b.get(0), b.get(0).startsWith("• NPU: delegado NeuroPilot"));
        assertTrue(b.get(1), b.get(1).startsWith("✘ GPU"));
        assertTrue(b.get(b.size() - 1).contains("CPU"));
    }
}
