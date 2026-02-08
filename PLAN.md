# Plan de Implementación — SDN Mobile Agent

## 1. Resumen del Proyecto

**Aplicación Android** que actúa como agente del plano de datos en una red SDN (Software Defined Networking) para entrega de contenido a dispositivos móviles usando Bluetooth LE y WiFi.

Se comunica con un **Controlador SDN** (Spring Boot + Kotlin) a través de:
- **MQTT** (Mosquitto, puerto 1883): recibir comandos, enviar telemetría y registro
- **REST API** (puerto 8081): solicitar sesiones de contenido y confirmar entregas

---

## 2. Arquitectura Implementada

```
┌─────────────────────────────────────────────────────────────────┐
│                     SDN Mobile Agent (Android)                  │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────────┐  │
│  │  MainActivity │  │  MainViewModel│  │  AppNavigation       │  │
│  │  (Entry Point)│─►│  (Orchestrator│─►│  (4 Screens)         │  │
│  └──────────────┘  │   + State)    │  │  Dashboard|Search|   │  │
│                     └──────┬───────┘  │  Log|Config          │  │
│                            │          └──────────────────────┘  │
│               ┌────────────┼────────────────┐                   │
│               │            │                │                   │
│  ┌────────────▼──┐ ┌──────▼───────┐ ┌──────▼───────┐          │
│  │  MqttManager  │ │  SdnApi      │ │CommandHandler │          │
│  │  (Paho MQTT)  │ │  (Retrofit)  │ │(Despacho)    │          │
│  └───────────────┘ └──────────────┘ └──────┬───────┘          │
│                                            │                   │
│                              ┌─────────────┼─────────────┐     │
│                              │             │             │     │
│                    ┌─────────▼──┐ ┌────────▼───┐ ┌──────▼──┐  │
│                    │ BleManager │ │WifiControl.│ │Telemetry│  │
│                    │ (BLE 5.0)  │ │(WiFi Data) │ │Collector│  │
│                    └────────────┘ └────────────┘ └─────────┘  │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
         │                    │                    │
    MQTT (1883)          REST (8081)          BLE/WiFi
         │                    │                    │
         ▼                    ▼                    ▼
┌──────────────┐    ┌──────────────┐    ┌──────────────┐
│  Mosquitto   │    │  Controller  │    │  Access Nodes│
│  Broker      │    │  Spring Boot │    │  (ESP32)     │
└──────────────┘    └──────────────┘    └──────────────┘
```

---

## 3. Fases de Implementación

### Fase 1: Configuración del Build System
- [x] Actualizar `libs.versions.toml` con todas las dependencias
- [x] Agregar plugins de Kotlin y Compose al proyecto
- [x] Configurar `build.gradle.kts` (app) con Compose, Retrofit, MQTT, DataStore
- [x] Configurar `build.gradle.kts` (root) con plugins Kotlin y Compose

### Fase 2: Permisos y Manifiesto
- [x] Agregar todos los permisos necesarios en `AndroidManifest.xml`
  - Internet, Network, WiFi
  - Bluetooth (legacy API < 31 + nuevos API 31+)
  - Location (requerido para BLE y WiFi scanning)
  - BLE feature declaration
- [x] Habilitar `usesCleartextTraffic` para HTTP (sin HTTPS en prototipo)
- [x] Registrar `SDNApplication` como Application class

### Fase 3: Capa de Datos (Data Layer)
- [x] **Modelos** (`data/model/`):
  - `Command.kt` — Comando del controlador
  - `DeviceInfo.kt` — Info de registro del dispositivo
  - `Metrics.kt` — Telemetría periódica
  - `SessionModels.kt` — SessionRequest + RequestSession
- [x] **MQTT** (`data/mqtt/MqttManager.kt`):
  - Conexión/desconexión al broker
  - Suscripción a `dispositivo/{MAC}/comando`
  - Publicación de métricas y registro
  - Reconexión automática
- [x] **REST** (`data/remote/SdnApi.kt`):
  - Interface Retrofit con todos los endpoints
  - Singleton con OkHttp + logging interceptor
- [x] **Preferencias** (`data/preferences/AppPreferences.kt`):
  - DataStore para persistir configuración
  - IP broker, puerto MQTT, puerto REST, nombre dispositivo

### Fase 4: Capa de Servicios (Service Layer)
- [x] **BleManager** (`service/BleManager.kt`):
  - Detección de soporte BLE 5.0 Coded PHY
  - Scan BLE con filtro por UUID de servicio
  - BLE Advertising
  - Conexión GATT con nodos de acceso
  - Lectura/escritura de características
- [x] **WifiController** (`service/WifiController.kt`):
  - Conexión a WiFi de datos (WifiNetworkSpecifier API 29+)
  - Fallback a WifiConfiguration (API < 29)
  - Desconexión de WiFi de datos
  - Obtención de RSSI e IP
- [x] **TelemetryCollector** (`service/TelemetryCollector.kt`):
  - Recopilación de métricas del dispositivo
  - RSSI WiFi, nivel batería, IP, radio activa
- [x] **CommandHandler** (`service/CommandHandler.kt`):
  - Despacho de comandos PREPARE_BT, SWITCH_WIFI, RELEASE_RADIO
  - Callbacks para actualizar estado de UI

### Fase 5: Capa de Presentación (UI Layer)
- [x] **Tema Compose** (`ui/theme/`):
  - Color.kt, Type.kt, Theme.kt
  - Soporte Material 3 con colores dinámicos (Android 12+)
- [x] **Pantallas** (`ui/screens/`):
  - `DashboardScreen` — Estado MQTT, radio activa, BLE, sesiones
  - `SearchScreen` — Campo de búsqueda, solicitud REST, resultado
  - `LogScreen` — Historial de comandos con colores por tipo
  - `ConfigScreen` — IP broker, puertos, nombre, conexión
- [x] **Navegación** (`ui/navigation/AppNavigation.kt`):
  - Bottom Navigation Bar con 4 pestañas
  - NavHost con Navigation Compose

### Fase 6: ViewModel y Orquestación
- [x] **MainViewModel** (`viewmodel/MainViewModel.kt`):
  - Orquesta todos los componentes
  - Expone StateFlows para UI reactiva
  - Maneja ciclo de vida de telemetría
  - Gestión de sesiones REST
  - Registro automático al conectar

### Fase 7: Entry Point
- [x] **MainActivity** — ComponentActivity con Compose
  - Solicitud de permisos en runtime
  - Inicialización de ViewModel
  - Montaje de UI theme + navigation
- [x] **SDNApplication** — Application class

---

## 4. Stack Tecnológico Final

| Componente | Tecnología | Versión |
|-----------|-----------|---------|
| Lenguaje | Kotlin | 2.1.0 |
| UI | Jetpack Compose | BOM 2024.12.01 |
| Material | Material 3 | (via Compose BOM) |
| Navegación | Navigation Compose | 2.8.5 |
| MQTT | Eclipse Paho | 1.2.5 |
| HTTP | Retrofit 2 + OkHttp | 2.11.0 / 4.12.0 |
| JSON | Gson | (via Retrofit) |
| Persistencia | DataStore Preferences | 1.1.1 |
| Coroutines | Kotlinx Coroutines | 1.9.0 |
| Min SDK | 26 (Android 8.0) | — |
| Target SDK | 35 | — |
| Build | AGP 9.0.0 + Gradle 9.1.0 | — |

---

## 5. Estructura de Archivos

```
app/src/main/
├── AndroidManifest.xml
└── java/org/sdn/sdn_mobile_agent/
    ├── MainActivity.kt              ← Entry point + permisos
    ├── SDNApplication.kt            ← Application class
    ├── data/
    │   ├── model/
    │   │   ├── Command.kt           ← Modelo de comando MQTT
    │   │   ├── DeviceInfo.kt        ← Modelo de registro
    │   │   ├── Metrics.kt           ← Modelo de telemetría
    │   │   └── SessionModels.kt     ← Request/Response sesiones
    │   ├── mqtt/
    │   │   └── MqttManager.kt       ← Cliente MQTT (Paho)
    │   ├── remote/
    │   │   └── SdnApi.kt            ← Cliente REST (Retrofit)
    │   └── preferences/
    │       └── AppPreferences.kt    ← DataStore settings
    ├── service/
    │   ├── BleManager.kt            ← Bluetooth LE manager
    │   ├── WifiController.kt        ← WiFi data controller
    │   ├── TelemetryCollector.kt    ← Recopilación de métricas
    │   └── CommandHandler.kt        ← Procesador de comandos
    ├── ui/
    │   ├── theme/
    │   │   ├── Color.kt             ← Paleta de colores
    │   │   ├── Type.kt              ← Tipografía
    │   │   └── Theme.kt             ← Tema Material 3
    │   ├── screens/
    │   │   ├── ConfigScreen.kt      ← Pantalla configuración
    │   │   ├── DashboardScreen.kt   ← Pantalla dashboard
    │   │   ├── LogScreen.kt         ← Pantalla log comandos
    │   │   └── SearchScreen.kt      ← Pantalla búsqueda
    │   └── navigation/
    │       └── AppNavigation.kt     ← Bottom nav + NavHost
    └── viewmodel/
        └── MainViewModel.kt         ← ViewModel principal
```

---

## 6. Contrato MQTT Implementado

| Tópico | Dirección | QoS | Frecuencia |
|--------|-----------|-----|-----------|
| `dispositivo/{MAC}/comando` | Controlador → App | 1 | Por evento |
| `dispositivo/{MAC}/metrics` | App → Controlador | 0 | Cada 30s |
| `dispositivo/{MAC}/registro` | App → Controlador | 1 | Al conectar |

---

## 7. Flujo de Sesión Completo

```
[Usuario]  →  Escribe query  →  [SearchScreen]
                                       │
                            POST /sessions/request
                                       │
                                       ▼
                              [Controlador SDN]
                                       │
                              Responde sessionId
                            + Publica PREPARE_BT (MQTT)
                                       │
                                       ▼
                    [App recibe PREPARE_BT]  →  BLE ON + Scan + Advertising
                                       │
                              (red SDN procesa solicitud)
                                       │
                              Publica SWITCH_WIFI (MQTT)
                                       │
                                       ▼
                    [App recibe SWITCH_WIFI]  →  Conecta WiFi datos
                                       │
                              (contenido llega por WiFi)
                                       │
                              Usuario ve resultado
                                       │
                            POST /sessions/{id}/delivered
                                       │
                                       ▼
                              [Controlador responde]
                            + Publica RELEASE_RADIO (MQTT)
                                       │
                                       ▼
                    [App recibe RELEASE_RADIO]  →  BT OFF + WiFi datos OFF
```
