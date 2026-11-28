# Ockham en Navaja

**Ockham** es el servidor que le da a Navaja lo que un teléfono no puede hacer solo. El servidor, su protocolo y su
arquitectura viven en su propio repositorio: **https://github.com/Josexato/ockham**

Aquí queda sólo la parte que corre dentro de la app.

## Cómo se usa

1. En el inicio, encender el interruptor **Ockham**. Apagado, Navaja no se conecta a ningún servidor y la tarjeta de
   Ockham no existe.
2. Abrir la tarjeta **Ockham**, escribir la dirección del servidor y entrar. La primera vez, con el correo y el
   código de invitación que entrega quien administra el servidor, se elige la contraseña.
3. La pantalla muestra el perfil (rol, trabajos y minutos de GPU por día) y las recetas que el servidor te habilitó.
4. **Elegir la receta y sus parámetros.** El formulario sale de lo que el servidor declara para cada receta; lo que
   se deja «por omisión» no se manda y lo decide el servidor. Un valor fuera de rango se avisa antes de subir nada.
5. **Elegir un archivo y enviarlo:** Navaja lo sube por partes y pide el trabajo. Mientras corre se ve su estado y
   se puede cancelar. Un trabajo largo (video a malla tarda unos 25 minutos) sigue en el servidor aunque se cierre
   Navaja: al volver a abrir Ockham, se retoma.
6. **El resultado:** el veredicto (lo elegido, por qué, con qué supuestos y qué alternativas) y un botón por cada
   archivo para guardarlo en el teléfono.

## Cómo está armado

| Clase | Qué hace |
|---|---|
| `ockham/core/OckhamClient` | Todo lo que la app le dice al servidor: conectar, entrar, renovar la sesión, perfil, recetas, subir y trabajos. Sin Android. |
| `ockham/core/Http` | El transporte. La app usa el cliente HTTP de la plataforma, sin librerías agregadas. |
| `ockham/core/Session` | La dirección del servidor y la sesión, como texto para guardar. |
| `ockham/core/Form` | El formulario de una receta, a partir del esquema de sus parámetros. |
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
- **Repetir un pedido no lo duplica:** el identificador del trabajo lo genera la app, y lo guarda antes de pedir
  para poder retomarlo.
- **Un archivo bajado se comprueba** contra la huella que el servidor guardó; si no coincide, se borra y se avisa.
  Sólo se baja de la carpeta propia.
- **La app no conoce ninguna receta de antemano:** muestra las que el servidor ofrece.
- **La sesión no va a los respaldos** de Android: está cifrada con una llave de ese teléfono y no serviría en otro.

## Cómo se probó

- `OckhamClientTest` y `FormTest`: 17 pruebas contra un servidor simulado en memoria (sesiones que vencen, tokens
  que rotan, subida que se corta, descarga con huella, cancelación, formulario con el esquema de video2mesh).
- `tools/ockham/OckhamHarness`: el mismo cliente, con su transporte real, contra un servidor Ockham levantado en
  Docker con un Runpod simulado: invitación, subida de 13 MB, trabajo local, descarga del resultado, trabajo en
  Runpod con formulario y cancelación de otro.

**Sin probar:** la pantalla nueva en un teléfono (formulario, seguimiento, guardar archivos). Compila, pero no se
ejecutó. Tampoco nada contra Runpod real.

## Lo que falta

- Mandar un escaneo directo desde «Mis escaneos», sin pasar por el selector de archivos.
- Que la subida siga con la app en segundo plano (hoy hay que dejar la pantalla abierta mientras sube).
- Avisar cuando un trabajo largo termina (hoy hay que volver a abrir Ockham para verlo).
- Ver la malla dentro de Navaja en vez de guardarla y abrirla con otra app.
