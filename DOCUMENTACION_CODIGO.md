# Documentación del Código — SDN Mobile Agent

Guía detallada de cada archivo, clase y función de la aplicación Android.

---

## Índice

1. [Entry Points](#1-entry-points)
2. [Capa de Datos (Data Layer)](#2-capa-de-datos)
3. [Capa de Servicios (Service Layer)](#3-capa-de-servicios)
4. [Capa de Presentación (UI Layer)](#4-capa-de-presentación)
5. [ViewModel](#5-viewmodel)
6. [Configuración del Build](#6-configuración-del-build)

---

## 1. Entry Points

### `MainActivity.kt`

**Tipo:** `ComponentActivity` (no AppCompatActivity, para Compose nativo)

| Función | Descripción |
|---------|-------------|
| `onCreate()` | Punto de entrada. Habilita edge-to-edge, inicializa ViewModel, solicita permisos y monta la UI Compose con `setContent {}`. |
| `requestRequiredPermissions()` | Construye una lista de permisos según la versión de Android y lanza el diálogo de solicitud para los que faltan. |
| `permissionLauncher` | `ActivityResultLauncher` registrado para manejar el resultado de la solicitud de múltiples permisos. |

**Permisos solicitados:**
- `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` — Requieridos para BLE scan y WiFi
- `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE` — Control de WiFi
- `ACCESS_NETWORK_STATE`, `CHANGE_NETWORK_STATE` — Control de red
- `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE` — BLE (API 31+)
- `BLUETOOTH`, `BLUETOOTH_ADMIN` — BLE legacy (API < 31)
- `POST_NOTIFICATIONS` — Notificaciones (API 33+)

### `SDNApplication.kt`

**Tipo:** `Application`

Clase Application de Android. Registrada en `AndroidManifest.xml` con `android:name=".SDNApplication"`. Se usa como punto de inicialización global de la app.

---

## 2. Capa de Datos

### 2.1 Modelos (`data/model/`)

#### `Command.kt`
```kotlin
data class Command(
    val sessionId: String,  // ID de la sesión asociada
    val action: String,     // "PREPARE_BT" | "SWITCH_WIFI" | "RELEASE_RADIO"
    val ssid: String?,      // Nombre WiFi (solo para SWITCH_WIFI)
    val password: String?,  // Contraseña WiFi (solo para SWITCH_WIFI)
    val reason: String?     // Razón legible del comando
)
```
Deserializado desde JSON recibido en `dispositivo/{MAC}/comando`.

#### `DeviceInfo.kt`
```kotlin
data class DeviceInfo(
    val mac: String,        // MAC WiFi del celular
    val name: String,       // Nombre del dispositivo (ej: "Samsung Galaxy S10")
    val deviceType: String, // Siempre "PHONE"
    val ipAddress: String   // IP en la LAN
)
```
Se serializa a JSON y publica en `dispositivo/{MAC}/registro` al conectar.

#### `Metrics.kt`
```kotlin
data class Metrics(
    val mac: String,        // MAC del dispositivo
    val rssi: Int,          // RSSI WiFi en dBm
    val technology: String, // Radio activa ("wifi", "bluetooth", etc.)
    val batteryLevel: Int,  // Batería en porcentaje (0-100)
    val ipAddress: String   // IP actual
)
```
Se serializa y publica en `dispositivo/{MAC}/metrics` cada 30 segundos.

#### `SessionModels.kt`

**SessionRequest** — Cuerpo del POST `/sessions/request`:
```kotlin
data class SessionRequest(
    val originMac: String,           // MAC del dispositivo solicitante
    val accessNodeMac: String?,      // MAC del nodo de acceso más cercano (opcional)
    val query: String,               // Consulta del usuario
    val expectedContentType: String  // "text", "image", etc.
)
```

**RequestSession** — Respuesta del controlador:
```kotlin
data class RequestSession(
    val sessionId: String,  // ID único de la sesión
    val originMac: String?, // MAC del solicitante
    val status: String?,    // Estado de la sesión
    val query: String?      // Consulta original
)
```

---

### 2.2 MQTT (`data/mqtt/MqttManager.kt`)

**Dependencia:** Eclipse Paho MQTT Client 1.2.5

| Propiedad/Función | Tipo | Descripción |
|-------------------|------|-------------|
| `isConnected` | `StateFlow<Boolean>` | Estado de conexión MQTT en tiempo real |
| `onCommandReceived` | `((Command) -> Unit)?` | Callback invocado al recibir comando del controlador |
| `onConnectionChanged` | `((Boolean) -> Unit)?` | Callback al cambiar estado de conexión |
| `connect(brokerUrl, mac)` | `fun` | Conecta al broker (ej: `tcp://192.168.18.1:1883`) |
| `disconnect()` | `fun` | Desconecta y libera recursos |
| `publishMetrics(metrics)` | `fun` | Publica telemetría (QoS 0) |
| `publishRegistration(deviceInfo)` | `fun` | Publica registro (QoS 1, una vez) |

**Configuración de conexión:**
- `isCleanSession = true`
- `connectionTimeout = 10s`
- `keepAliveInterval = 60s`
- `isAutomaticReconnect = true` — Reconecta automáticamente si se pierde la conexión

**Tópicos:**
- Suscripción: `dispositivo/{MAC}/comando` (QoS 1)
- Publicación métricas: `dispositivo/{MAC}/metrics` (QoS 0)
- Publicación registro: `dispositivo/{MAC}/registro` (QoS 1)

---

### 2.3 REST API (`data/remote/SdnApi.kt`)

**Dependencia:** Retrofit 2.11.0 + OkHttp 4.12.0 + Gson Converter

#### Interface `SdnApiService`

| Endpoint | Método | Descripción |
|----------|--------|-------------|
| `POST /sessions/request` | `requestSession(body)` | Crea sesión de contenido |
| `POST /sessions/{id}/delivered` | `confirmDelivery(id)` | Confirma entrega |
| `GET /devices` | `getDevices()` | Lista todos los dispositivos |
| `GET /devices/{mac}` | `getDevice(mac)` | Obtiene un dispositivo |
| `GET /devices/online` | `getOnlineDevices()` | Lista dispositivos en línea |
| `GET /sessions/{id}` | `getSession(id)` | Obtiene una sesión |
| `GET /sessions/active` | `getActiveSessions()` | Lista sesiones activas |
| `GET /sessions/device/{mac}` | `getDeviceSessions(mac)` | Sesiones de un dispositivo |

#### Object `SdnApi`

Singleton que gestiona la instancia de Retrofit.

| Función | Descripción |
|---------|-------------|
| `initialize(baseUrl)` | Configura Retrofit con la URL base (ej: `http://192.168.18.1:8081/`). Crea OkHttpClient con logging interceptor y timeouts de 15s. |
| `getService()` | Retorna la instancia de `SdnApiService`. Lanza excepción si no se ha llamado `initialize()`. |

---

### 2.4 Preferencias (`data/preferences/AppPreferences.kt`)

**Dependencia:** DataStore Preferences 1.1.1

| Propiedad | Tipo | Default | Descripción |
|-----------|------|---------|-------------|
| `brokerIp` | `Flow<String>` | `"192.168.18.1"` | IP del broker MQTT |
| `brokerPort` | `Flow<Int>` | `1883` | Puerto MQTT |
| `deviceName` | `Flow<String>` | `Build.MODEL` | Nombre del dispositivo |
| `restPort` | `Flow<Int>` | `8081` | Puerto REST API |

| Función | Descripción |
|---------|-------------|
| `saveBrokerIp(ip)` | Guarda IP del broker |
| `saveBrokerPort(port)` | Guarda puerto MQTT |
| `saveDeviceName(name)` | Guarda nombre dispositivo |
| `saveRestPort(port)` | Guarda puerto REST |

Los datos persisten entre reinicios de la app usando DataStore.

---

## 3. Capa de Servicios

### 3.1 BleManager (`service/BleManager.kt`)

Gestiona todas las operaciones Bluetooth Low Energy.

#### Constantes

| Constante | Valor | Uso |
|-----------|-------|-----|
| `SERVICE_UUID` | `4fafc201-...` | UUID del servicio BLE de nodos ESP32/LILYGO |
| `TX_CHAR_UUID` | `beb5483e-...` | Característica TX (lectura desde nodo) |
| `RX_CHAR_UUID` | `6e400002-...` | Característica RX (escritura hacia nodo) |

#### Propiedades observables

| Propiedad | Tipo | Descripción |
|-----------|------|-------------|
| `discoveredDevices` | `StateFlow<List<ScanResult>>` | Dispositivos BLE encontrados |
| `bleState` | `StateFlow<String>` | Estado: "idle", "scanning", "advertising", "connected", "error" |
| `supportsCodedPhy` | `Boolean` | `true` si el hardware soporta BLE 5.0 Coded PHY |
| `isBluetoothEnabled` | `Boolean` | `true` si BT está habilitado |

#### Funciones

| Función | Descripción |
|---------|-------------|
| `startScan()` | Inicia scan BLE filtrando por `SERVICE_UUID`. Usa Coded PHY si disponible. |
| `stopScan()` | Detiene el scan BLE activo. |
| `startAdvertising()` | Inicia BLE advertising con `SERVICE_UUID` para ser descubierto por nodos. |
| `stopAdvertising()` | Detiene el advertising. |
| `connectToDevice(device, callback)` | Conecta GATT a un nodo. Usa Coded PHY si disponible. Descubre servicios automáticamente. |
| `sendData(data)` | Envía bytes al nodo por la característica RX. |
| `disconnectGatt()` | Desconecta y cierra la conexión GATT. |
| `stopAll()` | Detiene scan + advertising + GATT. Limpia estado. |

**BLE 5.0 Coded PHY (Long Range):**
- Se verifica con `bluetoothAdapter.isLeCodedPhySupported` (API 26+)
- Si soportado, el scan usa `ScanSettings.PHY_LE_CODED`
- Conexiones GATT usan `BluetoothDevice.PHY_LE_CODED_MASK`
- Proporciona ~4x más alcance que BLE estándar

---

### 3.2 WifiController (`service/WifiController.kt`)

Controla las conexiones WiFi de datos.

#### Propiedades observables

| Propiedad | Tipo | Descripción |
|-----------|------|-------------|
| `wifiState` | `StateFlow<String>` | "disconnected", "data_wifi", "unavailable" |
| `dataWifiConnected` | `StateFlow<Boolean>` | `true` si hay WiFi de datos activo |

#### Funciones

| Función | Descripción |
|---------|-------------|
| `connectToWifi(ssid, password, callback)` | Conecta a red WiFi de datos. API 29+: `WifiNetworkSpecifier`. API < 29: `WifiConfiguration` legacy. Ejecuta `bindProcessToNetwork()` para enrutar tráfico. |
| `disconnectDataWifi()` | Desconecta WiFi de datos. Desregistra callback y libera binding de red. Mantiene WiFi de control (MQTT). |
| `getCurrentRssi()` | Retorna RSSI en dBm de la conexión WiFi actual. `-100` si error. |
| `getCurrentIp()` | Retorna IP del dispositivo en formato `"x.x.x.x"`. `"0.0.0.0"` si error. |
| `isWifiConnected()` | Verifica si hay conexión WiFi activa (control o datos). |

**Separación WiFi control vs datos:**
La app mantiene dos conexiones WiFi conceptualmente separadas:
- **WiFi de control**: la conexión normal del celular (para MQTT)
- **WiFi de datos**: red temporal indicada por el controlador (para transferir contenido)

`bindProcessToNetwork()` enruta el tráfico de la app por la red de datos cuando está conectada.

---

### 3.3 TelemetryCollector (`service/TelemetryCollector.kt`)

Recopila métricas del dispositivo para enviar por MQTT.

| Función | Descripción |
|---------|-------------|
| `collect(mac, activeRadio)` | Retorna un objeto `Metrics` con: RSSI WiFi, nivel de batería (`BatteryManager`), IP actual, radio activa. Se llama cada 30 segundos desde el ViewModel. |

---

### 3.4 CommandHandler (`service/CommandHandler.kt`)

Procesa los comandos del controlador SDN.

| Función | Descripción |
|---------|-------------|
| `handle(command)` | Despacha el comando al handler correspondiente según `command.action`. |
| `handlePrepareBt(command)` | Verifica que BT esté habilitado, inicia advertising + scan. Informa si usa Coded PHY. |
| `handleSwitchWifi(command)` | Extrae `ssid` y `password` del comando, llama a `wifiController.connectToWifi()`. |
| `handleReleaseRadio(command)` | Detiene BLE (`stopAll()`) y desconecta WiFi datos. Cambia radio a "idle". |

---

## 4. Capa de Presentación

### 4.1 Tema (`ui/theme/`)

| Archivo | Contenido |
|---------|-----------|
| `Color.kt` | Paleta de colores: SdnBlue, SdnGreen, SdnRed + esquema Material 3 |
| `Type.kt` | Tipografía: bodyLarge (16sp), titleLarge (22sp bold), labelSmall (11sp) |
| `Theme.kt` | Tema Material 3 con soporte de colores dinámicos (Android 12+) y modo oscuro |

### 4.2 Pantallas (`ui/screens/`)

#### `DashboardScreen.kt`

Pantalla principal que muestra el estado del agente en tiempo real.

**Componentes:**
- **StatusCard MQTT** — Estado de conexión, MAC, IP
- **StatusCard Radio** — Radio activa, estado WiFi datos, RSSI
- **StatusCard BLE** — Estado BLE, soporte Coded PHY
- **Card Sesión Activa** — Muestra sesión actual con botón "Confirmar Entrega"

**Composable auxiliar:** `StatusCard(title, icon, isActive, statusText, details)` — Tarjeta reutilizable con indicador de estado.

#### `SearchScreen.kt`

Pantalla de búsqueda de contenido.

**Flujo:**
1. Usuario escribe query en `OutlinedTextField`
2. Presiona "Buscar" → `viewModel.requestSession(query)`
3. Se muestra `CircularProgressIndicator` durante la carga
4. Resultado aparece en tarjeta secundaria
5. Si hay sesión activa, aparece botón "Confirmar Entrega"

**Validaciones:**
- Botón deshabilitado si no hay conexión MQTT
- Botón deshabilitado si query está vacío
- Muestra advertencia si no está conectado

#### `LogScreen.kt`

Historial de comandos y eventos.

**Características:**
- `LazyColumn` con todos los eventos (más reciente arriba)
- Colores por tipo: verde (✓ éxito), rojo (✗ error / ⚠ warning), gris (info)
- Fuente monoespaciada para legibilidad
- Botón "Limpiar" en la barra superior
- Estado vacío con mensaje informativo

#### `ConfigScreen.kt`

Pantalla de configuración de la app.

**Campos:**
- IP del Broker MQTT (`OutlinedTextField` con teclado URI)
- Puerto MQTT (`KeyboardType.Number`)
- Puerto REST API (`KeyboardType.Number`)
- Nombre del Dispositivo (texto libre)

**Acciones:**
- "Guardar Configuración" — Persiste en DataStore
- "Conectar" — Guarda config + inicializa REST + conecta MQTT
- "Desconectar" — Desconecta MQTT

**Información mostrada:**
- MAC del dispositivo
- Soporte BLE Coded PHY
- Estado de conexión (tarjeta color verde/rojo)

### 4.3 Navegación (`ui/navigation/AppNavigation.kt`)

**Estructura:**
- `NavigationBar` (bottom) con 4 ítems: Dashboard, Buscar, Log, Config
- `NavHost` con `Navigation Compose`
- `saveState` y `restoreState` al navegar entre pestañas
- `launchSingleTop` evita instancias duplicadas

**Rutas:**
| Ruta | Pantalla | Ícono |
|------|----------|-------|
| `dashboard` | DashboardScreen | Dashboard |
| `search` | SearchScreen | Search |
| `log` | LogScreen | List |
| `config` | ConfigScreen | Settings |

---

## 5. ViewModel

### `MainViewModel.kt`

**Tipo:** `AndroidViewModel` (acceso a Application context)

#### StateFlows expuestos

| Flow | Tipo | Descripción |
|------|------|-------------|
| `activeRadio` | `StateFlow<String>` | Radio activa: "idle", "bluetooth", "wifi" |
| `commandLog` | `StateFlow<List<String>>` | Log de eventos con timestamp |
| `currentSession` | `StateFlow<RequestSession?>` | Sesión activa o null |
| `searchResult` | `StateFlow<String>` | Texto resultado de búsqueda |
| `isLoading` | `StateFlow<Boolean>` | Operación de red en curso |
| `errorMessage` | `StateFlow<String?>` | Último error |
| `deviceMac` | `StateFlow<String>` | MAC del dispositivo |

#### Funciones principales

| Función | Descripción |
|---------|-------------|
| `connectMqtt(ip, port)` | Conecta al broker MQTT. Al conectar: registra dispositivo + inicia telemetría. |
| `disconnectMqtt()` | Desconecta MQTT + detiene telemetría. |
| `initRestApi(ip, port)` | Configura Retrofit con la URL del controlador. |
| `requestSession(query)` | `POST /sessions/request` — Crea sesión en IO dispatcher. |
| `confirmDelivery()` | `POST /sessions/{id}/delivered` — Confirma entrega en IO dispatcher. |
| `clearLog()` | Limpia el historial de comandos. |
| `clearError()` | Limpia el mensaje de error. |

#### Funciones internas

| Función | Descripción |
|---------|-------------|
| `registerDevice()` | Publica `DeviceInfo` en `dispositivo/{MAC}/registro`. Se llama una vez al conectar MQTT. |
| `startTelemetry()` | Inicia coroutine que publica métricas cada 30 segundos. |
| `stopTelemetry()` | Cancela la coroutine de telemetría. |
| `getDeviceMac()` | Obtiene MAC WiFi real (API < 10) o genera pseudo-MAC estable con `ANDROID_ID`. |
| `addLog(message)` | Agrega entrada con formato `[HH:mm:ss] mensaje` al inicio de la lista. |

#### Lifecycle

- **init**: Configura CommandHandler, registra callback MQTT, obtiene MAC
- **onCleared**: Detiene telemetría, cancela observers, detiene BLE, desconecta MQTT

---

## 6. Configuración del Build

### `libs.versions.toml`

Define todas las versiones y dependencias en un catálogo centralizado:
- **Core:** AndroidX Core KTX, AppCompat
- **Compose:** BOM, UI, Material 3, Material Icons, Activity, Navigation, Lifecycle
- **Network:** Retrofit, Gson Converter, OkHttp Logging
- **MQTT:** Eclipse Paho Client
- **Async:** Kotlin Coroutines Android
- **Storage:** DataStore Preferences
- **Plugins:** AGP, Kotlin Android, Kotlin Compose

### `build.gradle.kts` (app)

**Plugins:**
- `com.android.application` — Plugin Android
- `org.jetbrains.kotlin.android` — Compilación Kotlin
- `org.jetbrains.kotlin.plugin.compose` — Compiler Compose (integrado en Kotlin 2.0+)

**Configuración Android:**
- `compileSdk = 35`, `minSdk = 26`, `targetSdk = 35`
- `buildFeatures { compose = true }` — Habilita Compose
- `jvmTarget = "11"` — Compatibilidad JVM

### `AndroidManifest.xml`

**Permisos clave:**
- `INTERNET` + `usesCleartextTraffic="true"` — HTTP sin HTTPS (prototipo)
- `BLUETOOTH_*` con `maxSdkVersion="30"` para los legacy
- `ACCESS_FINE_LOCATION` — Requerido para BLE scan
- `CHANGE_NETWORK_STATE` — Requerido para `WifiNetworkSpecifier`

**Features:**
- `android.hardware.bluetooth_le` con `required="false"` — BLE no obligatorio
