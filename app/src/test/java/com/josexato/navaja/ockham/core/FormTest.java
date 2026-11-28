package com.josexato.navaja.ockham.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.josexato.navaja.pantonera.core.Json;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/** El formulario de una receta, con el esquema de video2mesh tal como lo manda el servidor. */
public class FormTest {

    private static final String SCHEMA = "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{"
            + "\"fotogramas\":{\"type\":\"integer\",\"minimum\":20,\"maximum\":200,\"description\":\"Cuántos fotogramas.\"},"
            + "\"fin\":{\"type\":\"number\",\"minimum\":0,\"maximum\":3600},"
            + "\"calidad\":{\"enum\":[\"baja\",\"media\",\"alta\",\"maxima\"]},"
            + "\"quitar_plano\":{\"type\":\"boolean\"},"
            + "\"nota\":{\"type\":\"string\",\"maxLength\":5},"
            + "\"raro\":{\"type\":\"array\"}}}";

    @SuppressWarnings("unchecked")
    private static List<Form.Field> fields() throws Exception {
        return Form.of((Map<String, Object>) Json.parse(SCHEMA));
    }

    private static Map<String, String> raw(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    private static String bad(String... kv) throws Exception {
        try {
            Form.values(fields(), raw(kv));
        } catch (OckhamException e) {
            return e.getMessage();
        }
        fail("se esperaba un error");
        return null;
    }

    @Test
    public void readsTheFieldsItKnowsHowToAsk() throws Exception {
        List<Form.Field> f = fields();
        assertEquals(5, f.size()); // «raro» es una lista: no se sabe preguntar, se saltea
        assertEquals(Form.Kind.INTEGER, f.get(0).kind);
        assertEquals("Cuántos fotogramas.", f.get(0).description);
        assertEquals(Form.Kind.CHOICE, f.get(2).kind);
        assertEquals(4, f.get(2).options.size());
        assertEquals("quitar plano", f.get(3).label());
        assertTrue(Form.of(null).isEmpty());
    }

    @Test
    public void emptyFieldsAreNotSentAndNumbersKeepTheirType() throws Exception {
        Map<String, Object> v = Form.values(fields(), raw("fotogramas", " 120 ", "fin", "72,5", "calidad", "", "quitar_plano", "true", "nota", ""));
        assertEquals("{\"fotogramas\":120,\"fin\":72.5,\"quitar_plano\":true}", Json.writeCompact(v));
        assertTrue(Form.values(fields(), raw("quitar_plano", "")).isEmpty());
    }

    @Test
    public void mistakesAreExplainedBeforeUploading() throws Exception {
        assertEquals("«fotogramas» no puede ser menor que 20.", bad("fotogramas", "5"));
        assertEquals("«fotogramas» no puede ser mayor que 200.", bad("fotogramas", "500"));
        assertEquals("«fotogramas» tiene que ser un número entero.", bad("fotogramas", "80.5"));
        assertEquals("«fin» tiene que ser un número.", bad("fin", "un minuto"));
        assertEquals("«calidad» no es una de las opciones.", bad("calidad", "ultra"));
        assertEquals("«nota» admite hasta 5 caracteres.", bad("nota", "demasiado"));
    }
}
