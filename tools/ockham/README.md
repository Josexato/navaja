# Probar el cliente de Ockham contra un servidor real

`OckhamHarness` corre el cliente de Navaja (`app/.../ockham/core`), con su transporte real, contra un servidor Ockham:
lo mismo que hará el teléfono, desde una PC.

```sh
M=app/src/main/java/com/josexato/navaja
javac -d /tmp/ockh $M/pantonera/core/Json.java $M/ockham/core/*.java tools/ockham/OckhamHarness.java
java -cp /tmp/ockh OckhamHarness https://TU-SERVIDOR correo@ejemplo.com CODIGO-DE-INVITACION UNA-CONTRASEÑA-NUEVA
```

El código de invitación sale de `despliegue/invitar.sh` en el servidor (repositorio `ockham`). El arnés crea un
usuario real: conviene usar un correo de prueba y borrarlo después desde el panel.

Contra un servidor de desarrollo con certificado propio, hay que pasarle a Java ese certificado con
`-Djavax.net.ssl.trustStore=...`. El cliente no tiene ninguna opción para saltarse la comprobación del certificado.
