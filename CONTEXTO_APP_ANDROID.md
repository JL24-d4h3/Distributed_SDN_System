# ═══════════════════════════════════════════════════════════════
#  CONTEXTO PARA LA APP ANDROID SDN
# ═══════════════════════════════════════════════════════════════
#
#  Este archivo es un PROMPT DE CONTEXTO.
#
#  Cuando crees el proyecto Android en IntelliJ/Android Studio
#  y abras una nueva conversación con Copilot, pega el contenido
#  de este archivo como primer mensaje para que tenga TODO el
#  contexto del controlador SDN y sepa exactamente qué construir.
#
#  Uso:
#  1. Crea el proyecto Android (ver instrucciones al final)
#  2. Abre chat con Copilot en ese proyecto
#  3. Pega esto como primer mensaje
#  4. Luego pide: "Implementa la app según este contexto"
#
# ═══════════════════════════════════════════════════════════════

## CONTEXTO DEL PROYECTO

Soy un tesista de ingeniería que está construyendo una red SDN
(Software Defined Networking) para entrega de contenido a
dispositivos móviles usando Bluetooth y WiFi.

Ya tengo un **Controlador SDN** funcionando en Spring Boot 4 (Kotlin)
que se comunica por MQTT con los dispositivos. Ahora necesito la
**app Android** que actúa como agente del plano de datos en el celular.

## ARQUITECTURA EXISTENTE

```
┌──────────────┐     MQTT      ┌──────────────┐     MQTT      ┌────────────┐
│  App Android │ ◄────────────► │  Mosquitto   │ ◄────────────► │ Controller │
│  (AGENTE)    │                │  Broker      │                │ Spring Boot│
│              │     REST       │  :1883 LAN   │                │ :8081      │
│              │ ──────────────►│              │                │            │
└──────────────┘                └──────────────┘                └────────────┘
```

### Parámetros de conexión
- Broker MQTT: `tcp://{IP_LAPTOP}:1883` (configurable)
- REST API: `http://{IP_LAPTOP}:8081`
- No hay autenticación (prototipo de tesis)

## CONTRATO MQTT (lo que la app debe implementar)

### 1. Suscribirse a comandos del controlador
**Tópico:** `dispositivo/{MAC_DEL_CELULAR}/comando`
**QoS:** 1

**Formato de comando (JSON recibido):**
```json
{
    "sessionId": "abc12345",
    "action": "PREPARE_BT",
    "ssid": "SDN_HIGH_SPEED",
    "password": "sdn_secure_pass",
    "reason": "Sesión abc12345: activar BT para enviar solicitud"
}
```

**Acciones que la app debe manejar:**

| Acción | Qué debe hacer la app |
|--------|----------------------|
| `PREPARE_BT` | Activar Bluetooth. Si el hardware soporta BLE 5.0 Coded PHY, iniciar scan/advertising con `PHY_LE_CODED`. Si no, usar BLE estándar. |
| `SWITCH_WIFI` | Conectar a la red WiFi indicada en `ssid`/`password`. Usar `WifiNetworkSpecifier` (Android 10+) o `WifiManager`. |
| `RELEASE_RADIO` | Apagar Bluetooth y/o desconectar WiFi de datos. Mantener WiFi de control (para MQTT). |

### 2. Publicar telemetría periódica
**Tópico:** `dispositivo/{MAC_DEL_CELULAR}/metrics`
**Intervalo:** cada 30 segundos
**QoS:** 0

**Formato (JSON a publicar):**
```json
{
    "mac": "AA:BB:CC:DD:EE:FF",
    "rssi": -45,
    "technology": "wifi",
    "batteryLevel": 85,
    "ipAddress": "192.168.18.50"
}
```

Donde:
- `mac`: MAC WiFi del celular (o identificador único)
- `rssi`: RSSI de la conexión WiFi actual (WifiManager.getConnectionInfo().rssi)
- `technology`: radio activa ("wifi", "bluetooth", "wifi+bluetooth", "ble_coded_phy")
- `batteryLevel`: porcentaje de batería (BatteryManager)
- `ipAddress`: IP del celular en la LAN

### 3. Auto-registro al conectar
**Tópico:** `dispositivo/{MAC_DEL_CELULAR}/registro`
**QoS:** 1 (una sola vez al conectar)

**Formato:**
```json
{
    "mac": "AA:BB:CC:DD:EE:FF",
    "name": "Samsung Galaxy S10",
    "deviceType": "PHONE",
    "ipAddress": "192.168.18.50"
}
```

## REST API DEL CONTROLADOR (la app puede usarla)

### Iniciar sesión de solicitud
```
POST http://{IP}:8081/sessions/request
{
    "originMac": "AA:BB:CC:DD:EE:FF",
    "accessNodeMac": "11:22:33:44:55:66",   // opcional
    "query": "algoritmo de dijkstra",
    "expectedContentType": "text"
}
→ Respuesta: RequestSession con sessionId
```

### Confirmar entrega
```
POST http://{IP}:8081/sessions/{sessionId}/delivered
→ El controlador envía RELEASE_RADIO por MQTT
```

### Consultar dispositivos
```
GET http://{IP}:8081/devices
GET http://{IP}:8081/devices/{mac}
GET http://{IP}:8081/devices/online
```

### Consultar sesiones
```
GET http://{IP}:8081/sessions/{sessionId}
GET http://{IP}:8081/sessions/active
GET http://{IP}:8081/sessions/device/{mac}
```

## FLUJO COMPLETO QUE LA APP DEBE SOPORTAR

```
Usuario presiona "Buscar"
       │
       ▼
[1] App envía POST /sessions/request
    con originMac y query
       │
       ▼
[2] Controlador responde con sessionId
    y publica PREPARE_BT por MQTT
       │
       ▼
[3] App recibe PREPARE_BT → enciende Bluetooth
    → Inicia BLE advertising o scan
    → Envía datos por BLE al nodo más cercano
       │
       ▼
[4] (La solicitud viaja por la red...)
       │
       ▼
[5] Controlador publica SWITCH_WIFI (si respuesta es pesada)
    o PREPARE_BT (si respuesta es ligera)
       │
       ▼
[6] App recibe SWITCH_WIFI → conecta a WiFi de datos
    → Recibe la respuesta por WiFi
    → Muestra contenido al usuario
       │
       ▼
[7] App envía POST /sessions/{id}/delivered
       │
       ▼
[8] Controlador publica RELEASE_RADIO
       │
       ▼
[9] App recibe RELEASE_RADIO → apaga BT y WiFi de datos
```

## REQUERIMIENTOS TÉCNICOS DE LA APP

### BLE 5.0 Coded PHY (Long Range)
Si el celular soporta BLE 5.0 Coded PHY (Samsung S10+, Pixel 3+):

```kotlin
// Verificar soporte
val adapter = BluetoothAdapter.getDefaultAdapter()
val supportsCodedPhy = adapter.isLeCodedPhySupported // Android 8.0+

// Scan con Coded PHY
val scanSettings = ScanSettings.Builder()
    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
    .setPhy(ScanSettings.PHY_LE_CODED)  // ★ LONG RANGE
    .build()

bluetoothLeScanner.startScan(filters, scanSettings, scanCallback)

// Conectar con Coded PHY
device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE,
    BluetoothDevice.PHY_LE_CODED_MASK)  // ★ LONG RANGE
```

### MQTT en Android
Usar la librería Eclipse Paho para Android:
```kotlin
// build.gradle.kts
implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
implementation("org.eclipse.paho:org.eclipse.paho.android.service:1.1.1")
```

O HiveMQ MQTT Client (más moderno):
```kotlin
implementation("com.hivemq:hivemq-mqtt-client:1.3.3")
```

### Permisos Android necesarios
```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.CHANGE_WIFI_STATE" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.BLUETOOTH" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
<uses-permission android:name="android.permission.BLUETOOTH_ADVERTISE" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.CHANGE_NETWORK_STATE" />
```

### Stack tecnológico sugerido
- **Kotlin** (no Java)
- **Jetpack Compose** para UI
- **MQTT**: HiveMQ Client o Eclipse Paho
- **HTTP**: Retrofit o Ktor Client
- **DI**: Hilt o Koin
- **Min SDK**: 26 (Android 8.0, para BLE 5.0)
- **Target SDK**: 34+

### Pantallas sugeridas
1. **Configuración**: IP del broker, puerto, nombre del dispositivo
2. **Dashboard**: Estado de conexión MQTT, radio activa, RSSI, sesiones
3. **Log de comandos**: Historial de comandos recibidos del controlador
4. **Búsqueda**: Campo de texto para hacer solicitudes (query)

## DATOS DEL CONTROLADOR EXISTENTE

- Spring Boot 4.0.2, Kotlin 2.2.21
- Base de datos H2 (dispositivos + sesiones)
- Jackson para JSON
- Spring Integration MQTT (suscribe a `dispositivo/+/metrics` y `dispositivo/+/registro`)
- Publica a `dispositivo/{MAC}/comando`
- Puerto REST: 8081
- Mosquitto: 1883, sin autenticación, LAN

## NODOS DE ACCESO (FUTURO)

La app también debería poder comunicarse por BLE con nodos de acceso
(ESP32 o LILYGO T-Lora C6) que actúan como puntos de relay.
Estos nodos exponen un servicio BLE con UUIDs:
- Servicio: `4fafc201-1fb5-459e-8fcc-c5c9c331914b`
- TX: `beb5483e-36e1-4688-b7f5-ea07361b26a8`
- RX: `6e400002-b5a3-f393-e0a9-e50e24dcca9e`
