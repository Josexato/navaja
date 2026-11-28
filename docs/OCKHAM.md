# Ockham en Navaja

**Ockham** es el servidor que le da a Navaja lo que un teléfono no puede hacer solo. El servidor, su protocolo y su
arquitectura viven en su propio repositorio: **https://github.com/Josexato/ockham**

Aquí queda sólo la parte que corre dentro de la app.

## Cómo se usa

1. En el inicio, encender el interruptor **Ockham**. Apagado, Navaja no se conecta a ningún servidor y la tarjeta de
   Ockham no existe.
2. Abrir la tarjeta **Ockham**, escribir la dirección del servidor y entrar. La primera vez, con el correo y el
   código de invitación que entrega quien administra el servidor, se elige la contraseña.
3. La pantalla muestra el perfil (rol, límite de trabajos y recetas habilitadas).
4. **Elegir un archivo y enviarlo:** Navaja lo sube por partes, le pide al servidor la receta de prueba `eco` y
   muestra el veredicto: lo elegido, por qué, con qué supuestos y qué alternativas se consideraron.

## Cómo está armado

| Clase | Qué hace |
|---|---|
| `ockham/core/OckhamClient` | Todo lo que la app le dice al servidor: conectar, entrar, renovar la sesión, perfil, recetas, subir y trabajos. Sin Android. |
| `ockham/core/Http` | El transporte. La app usa el cliente HTTP de la plataforma, sin librerías agregadas. |
| `ockham/core/Session` | La dirección del servidor y la sesión, como texto para guardar. |
| `ockham/core/Verdict` | El resultado de un trabajo con sus cuatro partes. |
| `ockham/Vault` | Guarda la sesión cifrada con una llave del Keystore de Android. |
| `ockham/OckhamActivity` | La pantalla. |

Decisiones que el código sostiene:

- **Sólo HTTPS.** La dirección se limpia y se rechaza si no es `https://` (salvo `localhost`, para desarrollo). No se
  siguen redirecciones: llevarían el credencial a otra dirección.
- **La sesión se renueva sola:** antes de un pedido si le queda menos de un minuto, y una vez más si el servidor
  responde 401. Si el servidor rechaza la renovación, la sesión se olvida y hay que entrar de nuevo.
- **Subida por partes de 6 MB**, sin cargar el archivo en memoria. Si una parte falla, se le pregunta al servidor
  hasta dónde llegó y se sigue desde ahí.
- **El método de las partes:** el protocolo de subida usa `PATCH`, que el cliente HTTP de Android no admite. La app
  manda `POST` con la cabecera `X-HTTP-Method-Override: PATCH` y el servidor lo convierte.
- **Repetir un pedido no lo duplica:** el identificador del trabajo lo genera la app.
- **La sesión no va a los respaldos** de Android: está cifrada con una llave de ese teléfono y no serviría en otro.

## Cómo se probó

- `OckhamClientTest`: 12 pruebas contra un servidor simulado en memoria (sesiones que vencen, tokens que rotan,
  subida que se corta a la mitad, errores explicados en español).
- `tools/ockham/OckhamHarness`: el mismo cliente, con su transporte real, contra un servidor Ockham levantado en
  Docker. Hace el recorrido completo: invitación, perfil, subida de 13 MB en tres partes, trabajo y veredicto.
- El interruptor del inicio, en un navegador, con el lado nativo simulado.

**Sin probar:** la pantalla en un teléfono real y el guardado cifrado de la sesión. Compilan, pero no se ejecutaron.

## Lo que falta

- Mandar un escaneo directo desde «Mis escaneos», sin pasar por el selector de archivos.
- Que la subida siga con la app en segundo plano (hoy hay que dejar la pantalla abierta).
- Retomar un trabajo pedido si se cierra la app antes de que termine.
- Bajar los archivos de resultado al teléfono.
- Las recetas reales, cuando el servidor las tenga.
