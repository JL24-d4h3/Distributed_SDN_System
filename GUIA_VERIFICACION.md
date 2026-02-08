# Guía de Verificación — SDN Mobile Agent

Guía paso a paso para verificar que la app funciona correctamente
con tu teléfono físico y el controlador SDN.

---

## Requisitos previos

| Componente | Estado requerido |
|-----------|-----------------|
| App Android | Compilada sin errores (✓ BUILD SUCCESSFUL) |
| Teléfono | Conectado por USB con depuración USB activa |
| Mosquitto | Corriendo en tu laptop (`puerto 1883`) |
| Controlador SDN | Corriendo en tu laptop (`puerto 8081`) |
| Red WiFi | Teléfono y laptop en la **misma red LAN** |

---

## Paso 0: Verificar que el teléfono está conectado

En la terminal de tu laptop:

```bash
adb devices
```

Debe mostrar algo como:
```
List of devices attached
XXXXXXXXXXXXXXX    device
```

Si dice `unauthorized`, desbloquea tu teléfono y acepta el diálogo
de depuración USB. Si no aparece nada:
1. Desconecta y reconecta el cable USB
2. Tira la barra de notificaciones → toca "Conexión USB" → selecciona **Transferencia de archivos**
3. Ejecuta: `adb kill-server && adb start-server && adb devices`

---

## Paso 1: Obtener la IP de tu laptop

```bash
# En Linux:
ip addr show | grep "inet " | grep -v 127.0.0.1

# Ejemplo de salida:
#   inet 192.168.18.5/24 brd 192.168.18.255 ...
# Tu IP es: 192.168.18.5
```

Anota tu IP, la necesitarás en la configuración de la app.

---

## Paso 2: Iniciar Mosquitto (broker MQTT)

En una terminal:

```bash
mosquitto -v -p 1883
```

Deberías ver:
```
1738985600: mosquitto version X.X.X starting
1738985600: Opening ipv4 listen socket on port 1883.
```

> **Nota:** Si Mosquitto ya corre como servicio, no necesitas esto.
> Verifica con: `systemctl status mosquitto`

---

## Paso 3: Iniciar el Controlador SDN

En otra terminal:

```bash
cd /ruta/a/SDN_controller_app
./gradlew bootRun
```

Espera hasta ver:
```
Started SdnControllerApplication in X.XX seconds
```

Verifica que responde:
```bash
curl http://localhost:8081/devices
# Debería devolver: [] (lista vacía)
```

---

## Paso 4: Instalar y abrir la app

1. En Android Studio, selecciona tu teléfono **Xiaomi 24049RN28L** en el dropdown de dispositivos (arriba)
2. Presiona **Run ▶** (Shift+F10)
3. Espera a que se instale y abra en tu teléfono
4. **Acepta TODOS los permisos** cuando aparezcan:
   - Ubicación → Permitir mientras se usa la app
   - Bluetooth → Permitir
   - Dispositivos cercanos → Permitir

---

## Paso 5: Configurar la app

1. Abre la pestaña **Config** (ícono de engranaje, última pestaña)
2. Llena los campos:
   - **IP del Broker:** `{TU_IP_LAPTOP}` (ej: `192.168.18.5`)
   - **Puerto MQTT:** `1883`
   - **Puerto REST API:** `8081`
   - **Nombre del Dispositivo:** (déjalo como está o pon lo que quieras)
3. Presiona **"Guardar Configuración"**
4. Presiona **"Conectar"**

**Verificación:** La tarjeta de abajo debe cambiar a **verde** con texto **"Conectado al broker MQTT"**.

En la terminal de Mosquitto deberías ver:
```
New connection from 192.168.18.XX on port 1883.
New client connected from 192.168.18.XX as android_XXXXXXXXXX
```

---

## Paso 6: Verificar auto-registro (MQTT)

Abre **otra terminal** y suscríbete a los tópicos del dispositivo:

```bash
mosquitto_sub -h localhost -t "dispositivo/+/registro" -t "dispositivo/+/metrics" -t "dispositivo/+/comando" -v
```

**Deberías ver inmediatamente** el mensaje de registro:
```json
dispositivo/A2:XX:XX:XX:XX:XX/registro {"mac":"A2:XX:XX:XX:XX:XX","name":"Xiaomi 24049RN28L","deviceType":"PHONE","ipAddress":"192.168.18.XX"}
```

**Cada 30 segundos** verás las métricas:
```json
dispositivo/A2:XX:XX:XX:XX:XX/metrics {"mac":"A2:XX:XX:XX:XX:XX","rssi":-45,"technology":"idle","batteryLevel":85,"ipAddress":"192.168.18.XX"}
```

> **Anota la MAC** que aparece (ej: `A2:XX:XX:XX:XX:XX`), la necesitarás para enviar comandos.

---

## Paso 7: Verificar Dashboard

1. Ve a la pestaña **Dashboard** (primera pestaña)
2. Verifica que muestra:
   - **MQTT:** Conectado (verde)
   - **Radio Activa:** Inactivo
   - **Bluetooth LE:** Inactivo
3. La MAC y la IP de tu teléfono deben aparecer correctamente

---

## Paso 8: Probar recepción de comandos — PREPARE_BT

En otra terminal, envía un comando. **Reemplaza `{MAC}` con la MAC que anotaste:**

```bash
mosquitto_pub -h localhost -t "dispositivo/{MAC}/comando" -m '{
  "sessionId": "test001",
  "action": "PREPARE_BT",
  "reason": "Prueba: activar Bluetooth"
}'
```

**Verificación en la app:**
1. Pestaña **Dashboard:** Radio Activa → **"Bluetooth LE"** (verde)
2. BLE: **"Escaneando..."** o **"Anunciando..."**
3. Pestaña **Log:** Debe aparecer la entrada:
   ```
   [HH:mm:ss] [test001] PREPARE_BT: Prueba: activar Bluetooth
   [HH:mm:ss] ✓ Bluetooth activado (estándar) - scan + advertising
   ```

> **Nota:** Si tu teléfono soporta BLE Coded PHY, dirá "(Coded PHY)" en vez de "(estándar)".

---

## Paso 9: Probar SWITCH_WIFI

```bash
mosquitto_pub -h localhost -t "dispositivo/{MAC}/comando" -m '{
  "sessionId": "test001",
  "action": "SWITCH_WIFI",
  "ssid": "NombreDeTuWiFi",
  "password": "tuContraseña",
  "reason": "Prueba: cambiar a WiFi datos"
}'
```

> Usa el nombre y contraseña de una red WiFi real a tu alcance.

**Verificación:**
1. Dashboard: Radio Activa → **"WiFi Datos"**
2. Log: `✓ Conectado a WiFi de datos: NombreDeTuWiFi`
3. En Android, puede aparecer un diálogo del sistema preguntando si quieres conectarte — acepta

---

## Paso 10: Probar RELEASE_RADIO

```bash
mosquitto_pub -h localhost -t "dispositivo/{MAC}/comando" -m '{
  "sessionId": "test001",
  "action": "RELEASE_RADIO",
  "reason": "Prueba: liberar radios"
}'
```

**Verificación:**
1. Dashboard: Radio Activa → **"Inactivo"**
2. BLE: **"Inactivo"**
3. Log: `✓ Radios liberadas (BT off, WiFi datos off)`

---

## Paso 11: Probar flujo de búsqueda (REST API)

> **Requiere:** Controlador SDN corriendo en `:8081`

1. Ve a la pestaña **Buscar** (segunda pestaña)
2. Escribe una consulta: `algoritmo de dijkstra`
3. Presiona **"Buscar"**

**Verificación:**
- Aparece un spinner mientras carga
- Resultado: `Sesión creada: {sessionId}`
- En la terminal de Mosquitto, el controlador debería publicar
  un comando PREPARE_BT al dispositivo
- El Log muestra: `Sesión solicitada: {sessionId} - query: "algoritmo de dijkstra"`

**Si no tienes el controlador corriendo**, verás:
```
Error: failed to connect to /192.168.18.5 (port 8081)
```
Esto es normal — la búsqueda necesita al controlador.

---

## Paso 12: Probar confirmación de entrega

Si la búsqueda creó una sesión:

1. En **Dashboard** o **Buscar**, presiona **"Confirmar Entrega"**
2. Log: `✓ Entrega confirmada: {sessionId}`
3. El controlador enviará `RELEASE_RADIO` automáticamente

---

## Paso 13: Verificar en el controlador

```bash
# Ver dispositivos registrados
curl http://localhost:8081/devices | python3 -m json.tool

# Ver dispositivos en línea
curl http://localhost:8081/devices/online | python3 -m json.tool

# Ver sesiones activas
curl http://localhost:8081/sessions/active | python3 -m json.tool
```

---

## Paso 14: Verificar telemetría continua

Deja la app conectada y observa la terminal de `mosquitto_sub`.
Cada 30 segundos debe llegar un mensaje de métricas:

```json
dispositivo/{MAC}/metrics {
  "mac": "A2:XX:XX:XX:XX:XX",
  "rssi": -42,
  "technology": "bluetooth",
  "batteryLevel": 78,
  "ipAddress": "192.168.18.50"
}
```

Verifica que:
- `rssi` cambia según la señal WiFi
- `batteryLevel` refleja la batería real
- `technology` cambia cuando envías comandos (idle → bluetooth → wifi)

---

## Checklist final

| # | Prueba | Resultado esperado | ✓/✗ |
|---|--------|-------------------|-----|
| 1 | App se instala en teléfono | Abre sin crashes | |
| 2 | Permisos aceptados | Todos concedidos | |
| 3 | Conectar MQTT | Tarjeta verde "Conectado" | |
| 4 | Auto-registro | JSON en tópico `/registro` | |
| 5 | Telemetría c/30s | JSON en tópico `/metrics` | |
| 6 | PREPARE_BT | BLE se activa, Dashboard muestra "Bluetooth LE" | |
| 7 | SWITCH_WIFI | WiFi datos conecta, Dashboard muestra "WiFi Datos" | |
| 8 | RELEASE_RADIO | Radios liberadas, Dashboard muestra "Inactivo" | |
| 9 | Búsqueda REST | Sesión creada en controlador | |
| 10 | Confirmar entrega | Controlador recibe confirmación | |
| 11 | Log de comandos | Todos los eventos registrados con timestamp | |
| 12 | Desconectar MQTT | Tarjeta roja "Desconectado" | |

---

## Troubleshooting

### "No se puede conectar al broker"
- Verifica que Mosquitto está corriendo: `systemctl status mosquitto`
- Verifica que el firewall no bloquea el puerto:
  ```bash
  sudo ufw allow 1883
  sudo ufw allow 8081
  ```
- Verifica que ambos dispositivos están en la misma red WiFi
- Haz ping desde la laptop al teléfono (y viceversa)

### "Error en búsqueda REST"
- Verifica que el controlador Spring Boot está corriendo
- Prueba desde la laptop: `curl http://localhost:8081/devices`
- Verifica que la IP y puerto en Config son correctos

### "Bluetooth no se activa"
- Verifica que Bluetooth está encendido en Ajustes del teléfono
- Verifica que los permisos de Bluetooth fueron aceptados
- Mira en `Ajustes > Apps > SDN Mobile Agent > Permisos`

### "La app crashea"
- En Android Studio: **Logcat** (pestaña abajo) → filtra por `org.sdn.sdn_mobile_agent`
- Busca líneas en rojo con `FATAL EXCEPTION` o `E/`
