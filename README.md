# Argentas — reconstrucción exacta + sincronización automática

La interfaz y la aplicación web de Argentas se conservan tal como fueron entregadas. El único agregado funcional es la sincronización automática entre dos teléfonos Android que estén en la misma red Wi‑Fi/hotspot.

## Cómo funciona

- Cada teléfono publica Argentas en la red local mediante Android Network Service Discovery (NSD/mDNS).
- Al encontrar otro Argentas, establece una conexión TCP local automáticamente.
- No hay códigos, botones de conexión ni pasos manuales.
- El encabezado muestra `CONECTADO` en verde o `DESCONECTADO` en rojo, encima del estado de Caja.
- Las ventas, gastos, cierres y demás datos persistidos se sincronizan entre ambos teléfonos.

La red/hotspot debe permitir comunicación entre los clientes. Si el hotspot tiene aislamiento de clientes (AP/client isolation), los teléfonos no podrán verse entre sí.

## Compilación

GitHub Actions genera el proyecto Android de Capacitor, instala el módulo nativo de descubrimiento local y compila `app-debug.apk`.
