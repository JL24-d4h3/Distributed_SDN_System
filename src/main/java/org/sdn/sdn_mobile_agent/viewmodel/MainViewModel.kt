package org.sdn.sdn_mobile_agent.viewmodel

import android.app.Application
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.sdn.sdn_mobile_agent.data.model.*
import org.sdn.sdn_mobile_agent.data.mqtt.MqttManager
import org.sdn.sdn_mobile_agent.data.preferences.AppPreferences
import org.sdn.sdn_mobile_agent.data.remote.SdnApi
import org.sdn.sdn_mobile_agent.service.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * ViewModel principal de la aplicación SDN Mobile Agent.
 *
 * Arquitectura SDN:
 * ═════════════════
 * BLE = Plano de Control (siempre ON, ~0.3 mA)
 *   - GATT Client conecta al CDN (laptop GATT Server)
 *   - App escribe requests, CDN notifica control/responses
 *
 * WiFi = Plano de Datos (ON/OFF bajo demanda)
 *   - CDN enciende WiFi vía ADB cuando hay datos pesados
 *   - Apagado cuando termina → ahorro energético real
 *   - MQTT opcional como fallback si WiFi está activo
 *
 * Orquesta todos los componentes:
 * - BleManager: GATT Client CDN + BLE scan/advertising
 * - MqttManager: MQTT (fallback cuando WiFi está ON)
 * - WifiController: conexiones WiFi de datos
 * - RadioController: diagnóstico de privilegios
 * - TelemetryCollector: recopilación de métricas
 * - CommandHandler: procesamiento de comandos SDN
 * - SdnApi: comunicación REST con el controlador
 * - AppPreferences: persistencia de configuración
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MainViewModel"
    }

    private val context = application.applicationContext

    // ─── Componentes ────────────────────────────────────────────
    val preferences = AppPreferences(context)
    val mqttManager = MqttManager()
    val bleManager = BleManager(context)
    val wifiController = WifiController(context)
    val radioController = RadioController(context)
    private val telemetryCollector = TelemetryCollector(context, wifiController)
    private val commandHandler: CommandHandler

    // ─── Estado de la UI ────────────────────────────────────────

    /** Radio activa actual: "idle", "bluetooth", "wifi", "wifi+bluetooth" */
    private val _activeRadio = MutableStateFlow("idle")
    val activeRadio: StateFlow<String> = _activeRadio

    /** Log de comandos y eventos (más reciente primero) */
    private val _commandLog = MutableStateFlow<List<String>>(emptyList())
    val commandLog: StateFlow<List<String>> = _commandLog

    /** Sesión de solicitud activa */
    private val _currentSession = MutableStateFlow<RequestSession?>(null)
    val currentSession: StateFlow<RequestSession?> = _currentSession

    /** Resultado de la última búsqueda para mostrar en UI */
    private val _searchResult = MutableStateFlow("")
    val searchResult: StateFlow<String> = _searchResult

    /** Indica si hay una operación de red en curso */
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    /** Último mensaje de error */
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    /** MAC del dispositivo (identificador único) */
    private val _deviceMac = MutableStateFlow("")
    val deviceMac: StateFlow<String> = _deviceMac

    /** Señal para que la Activity solicite habilitar Bluetooth */
    private val _requestBluetoothEnable = MutableStateFlow(false)
    val requestBluetoothEnable: StateFlow<Boolean> = _requestBluetoothEnable

    private var telemetryJob: Job? = null
    private var connectionObserverJob: Job? = null
    private var errorObserverJob: Job? = null

    // ─── Inicialización ─────────────────────────────────────────

    init {
        // Configurar CommandHandler con callbacks
        commandHandler = CommandHandler(
            radioController = radioController,
            bleManager = bleManager,
            wifiController = wifiController,
            onRadioChanged = { radio -> _activeRadio.value = radio },
            onLog = { msg -> addLog(msg) },
            onRequestBluetoothEnable = {
                _requestBluetoothEnable.value = true
            },
            onPublishRadioRequest = { action, reason ->
                // Fallback MQTT cuando BLE GATT no disponible
                mqttManager.publishRadioRequest(action, reason)
            }
        )

        // ── BLE GATT Client: recibir comandos de control de la CDN ──
        bleManager.onCdnControl = { json ->
            try {
                val command = parseGattCommand(json)
                viewModelScope.launch(Dispatchers.Main) {
                    addLog("⬇ Control CDN vía BLE: ${command.action}")
                    commandHandler.handle(command)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parseando control CDN: $json", e)
            }
        }

        // ── BLE GATT Client: recibir respuestas de datos de la CDN ──
        bleManager.onCdnResponse = { json ->
            viewModelScope.launch(Dispatchers.Main) {
                addLog("⬇ Respuesta CDN vía BLE: ${json.take(80)}")
                _searchResult.value = json
            }
        }

        // ── MQTT: recibir comandos como fallback (cuando WiFi activo) ──
        mqttManager.onCommandReceived = { command ->
            viewModelScope.launch(Dispatchers.Main) {
                addLog("⬇ Comando vía MQTT: ${command.action}")
                commandHandler.handle(command)
            }
        }

        // Observar errores de MQTT permanentemente
        errorObserverJob = viewModelScope.launch {
            mqttManager.lastError.collect { error ->
                if (error != null) {
                    addLog("✗ $error")
                    _errorMessage.value = error
                }
            }
        }

        // Obtener MAC del dispositivo
        _deviceMac.value = getDeviceMac()
        Log.i(TAG, "Device MAC: ${_deviceMac.value}")

        // ── Auto-iniciar plano de control BLE si BT ya está ON ──
        if (bleManager.isBluetoothEnabled) {
            addLog("BT encendido → iniciando plano de control BLE...")
            initBleControlPlane()
        }
    }

    /**
     * Inicializa el plano de control BLE: GATT Server + Advertising.
     * Debe llamarse cuando BT está habilitado.
     */
    fun initBleControlPlane() {
        if (!bleManager.isBluetoothEnabled) {
            addLog("⚠ BT apagado — no se puede iniciar plano de control BLE")
            return
        }
        bleManager.startScan()
        bleManager.startAdvertising()
        addLog("✓ Plano de control BLE activo (scan CDN + advertising)")
        _activeRadio.value = "bluetooth"
    }

    /**
     * Parsea un comando JSON recibido vía BLE GATT.
     * Formato: {"action":"...", "sessionId":"...", "ssid":"...", "password":"...", "reason":"..."}
     */
    private fun parseGattCommand(json: String): Command {
        // Parse simple sin dependencias extras
        val action = extractJsonField(json, "action") ?: "UNKNOWN"
        val sessionId = extractJsonField(json, "sessionId") ?: "ble-ctrl"
        val ssid = extractJsonField(json, "ssid")
        val password = extractJsonField(json, "password")
        val reason = extractJsonField(json, "reason")
        return Command(
            sessionId = sessionId,
            action = action,
            ssid = ssid,
            password = password,
            reason = reason
        )
    }

    /** Extrae un campo string de un JSON simple */
    private fun extractJsonField(json: String, field: String): String? {
        val regex = """"$field"\s*:\s*"([^"]*)"""".toRegex()
        return regex.find(json)?.groupValues?.get(1)
    }

    // ─── Consola de Comandos Locales ────────────────────────────

    /**
     * Ejecuta un comando local desde la consola de búsqueda.
     * Retorna true si era un comando reconocido, false si debe tratarse
     * como búsqueda REST normal.
     *
     * Arquitectura SDN:
     * ═════════════════
     * BLE  = Plano de Control (GATT Server, siempre ON)
     * WiFi = Plano de Datos (ON/OFF bajo demanda vía BLE→ADB)
     *
     * bt on / bt off   → Radio-request (BLE GATT o MQTT fallback) → ADB
     * wifi on / wifi off → Radio-request → ADB (plano de datos)
     * ble start / stop  → Control local BLE
     * prepare / release → Simula comandos SDN
     * status / diag     → Solo lectura
     */
    fun executeLocalCommand(input: String): Boolean {
        val cmd = input.trim().lowercase()

        when {
            cmd == "help" || cmd == "?" -> {
                _searchResult.value = buildString {
                    appendLine("═══ Consola SDN Mobile Agent ═══")
                    appendLine()
                    appendLine("─── Plano de Control (BLE) ───")
                    appendLine("▸ ble start     Scan CDN + advertising")
                    appendLine("▸ ble stop      Detener BLE operaciones")
                    appendLine("▸ bt on         Encender BT vía ADB (radio-request)")
                    appendLine("▸ bt off        Apagar BT vía ADB (radio-request)")
                    appendLine()
                    appendLine("─── Plano de Datos (WiFi) ───")
                    appendLine("▸ wifi on       Encender WiFi vía ADB (datos)")
                    appendLine("▸ wifi off      Apagar WiFi vía ADB (ahorro)")
                    appendLine()
                    appendLine("─── Simulación de Comandos SDN ───")
                    appendLine("▸ prepare       Simular PREPARE_BT")
                    appendLine("▸ release       Simular RELEASE_RADIO")
                    appendLine()
                    appendLine("─── Diagnóstico ───")
                    appendLine("▸ status        Estado de radios y planos")
                    appendLine("▸ diag          Diagnóstico de privilegios")
                    appendLine("▸ adb           Comandos ADB de referencia")
                    appendLine()
                    appendLine("─── Arquitectura ───")
                    appendLine("BLE  = plano de control (siempre ON)")
                    appendLine("WiFi = plano de datos (ON/OFF bajo demanda)")
                    appendLine()
                    appendLine("Cualquier otro texto → búsqueda REST")
                }
                addLog("Consola: help")
                return true
            }

            cmd == "bt on" -> {
                addLog("Consola: bt on")
                sendRadioRequest("enable_bt", "Solicitud desde consola")
                _searchResult.value = buildString {
                    appendLine("→ Solicitud enviada al controlador")
                    appendLine()
                    appendLine("Canal: ${if (bleManager.isCdnConnected) "BLE GATT → CDN" else "MQTT (fallback)"}")
                    appendLine()
                    appendLine("Flujo:")
                    appendLine("  App → radio-request → CDN (laptop)")
                    appendLine("  CDN → adb shell svc bluetooth enable")
                    appendLine("  CDN → BT_READY → App")
                    appendLine()
                    appendLine("Esperando confirmación BT_READY...")
                }
                return true
            }

            cmd == "bt off" -> {
                addLog("Consola: bt off")
                // Detener BLE operaciones primero (local)
                bleManager.stopEverything()
                sendRadioRequest("disable_bt", "Solicitud desde consola")
                _searchResult.value = buildString {
                    appendLine("→ BLE detenido + solicitud enviada a CDN")
                    appendLine()
                    appendLine("⚠ Esto apagará el plano de control BLE!")
                    appendLine("  La app perderá comunicación con el controlador")
                    appendLine("  hasta que BT se encienda de nuevo.")
                }
                _activeRadio.value = "idle"
                return true
            }

            cmd == "wifi on" -> {
                addLog("Consola: wifi on (plano de datos)")
                sendRadioRequest("enable_wifi", "Solicitud desde consola — datos")
                _searchResult.value = buildString {
                    appendLine("→ Solicitud enviada: encender WiFi (plano de datos)")
                    appendLine()
                    appendLine("Canal: ${if (bleManager.isCdnConnected) "BLE GATT → CDN" else "MQTT (fallback)"}")
                    appendLine()
                    appendLine("Flujo:")
                    appendLine("  App → radio-request → CDN (laptop)")
                    appendLine("  CDN → adb shell svc wifi enable")
                    appendLine("  CDN → WIFI_READY → App")
                    appendLine()
                    appendLine("WiFi se usará para transferencia de datos pesados.")
                    appendLine("Cuando termines: 'wifi off' para ahorrar energía.")
                }
                return true
            }

            cmd == "wifi off" -> {
                addLog("Consola: wifi off (plano de datos)")
                wifiController.disconnectDataWifi()
                sendRadioRequest("disable_wifi", "Solicitud desde consola — ahorro")
                _searchResult.value = buildString {
                    appendLine("→ WiFi datos desconectado + solicitud: apagar radio WiFi")
                    appendLine()
                    appendLine("Canal: ${if (bleManager.isCdnConnected) "BLE GATT → CDN" else "MQTT (fallback)"}")
                    appendLine()
                    appendLine("✓ Plano de datos WiFi apagándose → ahorro energético")
                    appendLine("  BLE (plano de control) sigue activo")
                }
                if (bleManager.isBluetoothEnabled) {
                    _activeRadio.value = "bluetooth"
                }
                return true
            }

            cmd == "ble start" -> {
                addLog("Consola: ble start (plano de control)")
                initBleControlPlane()
                _searchResult.value = if (bleManager.isBluetoothEnabled) {
                    "✓ Plano de control BLE iniciado\n" +
                            "  Scan CDN + advertising\n\n" +
                            "CDN: ${bleManager.cdnConnectionState.value}\n" +
                            "CDN conectada: ${bleManager.isCdnConnected}"
                } else {
                    "✗ BT apagado — enciéndelo primero:\n" +
                            "  Escribe: bt on\n" +
                            "  O desde laptop: adb shell svc bluetooth enable"
                }
                return true
            }

            cmd == "ble stop" -> {
                addLog("Consola: ble stop")
                bleManager.stopOperations()
                _searchResult.value = "✓ BLE scan + advertising detenidos\n" +
                        "  Conexión CDN se mantiene si estaba activa"
                addLog("✓ BLE scan/adv detenidos")
                return true
            }

            cmd == "prepare" -> {
                addLog("Consola: simular PREPARE_BT")
                val fakeCmd = Command(
                    sessionId = "local-test",
                    action = "PREPARE_BT",
                    reason = "Prueba local desde consola"
                )
                commandHandler.handle(fakeCmd)
                _searchResult.value = "→ PREPARE_BT ejecutado (ver Log para detalles)"
                return true
            }

            cmd == "release" -> {
                addLog("Consola: simular RELEASE_RADIO")
                val fakeCmd = Command(
                    sessionId = "local-test",
                    action = "RELEASE_RADIO",
                    reason = "Prueba local desde consola"
                )
                commandHandler.handle(fakeCmd)
                _searchResult.value = "→ RELEASE_RADIO ejecutado (ver Log para detalles)"
                return true
            }

            cmd == "diag" -> {
                val diagnostic = commandHandler.runDiagnostic()
                _searchResult.value = diagnostic
                addLog("Consola: diagnóstico ejecutado")
                return true
            }

            cmd == "status" -> {
                val wifiMode = wifiController.getWifiMode()
                _searchResult.value = buildString {
                    appendLine("═══ Estado Actual ═══")
                    appendLine()
                    appendLine("─── Plano de Control (BLE → CDN) ───")
                    appendLine("BT Radio: ${if (bleManager.isBluetoothEnabled) "ON ✓" else "OFF ✗"}")
                    appendLine("CDN GATT: ${bleManager.cdnConnectionState.value}")
                    appendLine("CDN conectada: ${if (bleManager.isCdnConnected) "Sí ✓" else "No"}")
                    appendLine("BLE State: ${bleManager.bleState.value}")
                    appendLine()
                    appendLine("─── Plano de Datos (WiFi) ───")
                    appendLine("WiFi Radio: $wifiMode")
                    appendLine("WiFi IP: ${wifiController.getCurrentIp()}")
                    appendLine("WiFi RSSI: ${wifiController.getCurrentRssi()} dBm")
                    appendLine("WiFi Datos: ${if (wifiController.dataWifiConnected.value) "Conectado" else "No"}")
                    if (wifiController.isHotspotActive) {
                        appendLine("⚠ Hotspot activo (usa la misma radio WiFi)")
                    }
                    appendLine()
                    appendLine("─── Conexión SDN ───")
                    appendLine("MQTT: ${if (mqttManager.isConnected.value) "Conectado ✓ (fallback)" else "Desconectado"}")
                    appendLine("MAC: ${_deviceMac.value}")
                    appendLine("Radio Activa: ${_activeRadio.value}")
                    appendLine()
                    appendLine("─── Arquitectura ───")
                    appendLine("Plano control: BLE GATT (siempre ON, ~0.3 mA)")
                    appendLine("Plano datos:   WiFi (ON/OFF bajo demanda)")
                    appendLine("Fallback:      MQTT si WiFi activo")
                }
                addLog("Consola: status")
                return true
            }

            cmd == "adb" -> {
                _searchResult.value = buildString {
                    appendLine("═══ Referencia ADB ═══")
                    appendLine()
                    appendLine("─── Ejecutar desde la laptop ───")
                    appendLine()
                    appendLine("# Encender/apagar Bluetooth:")
                    appendLine("adb shell svc bluetooth enable")
                    appendLine("adb shell svc bluetooth disable")
                    appendLine()
                    appendLine("# Encender/apagar WiFi:")
                    appendLine("adb shell svc wifi enable")
                    appendLine("adb shell svc wifi disable")
                    appendLine()
                    appendLine("# Estado de radios:")
                    appendLine("adb shell settings get global bluetooth_on")
                    appendLine("adb shell settings get global wifi_on")
                    appendLine()
                    appendLine("─── Puentes BLE ───")
                    appendLine("sdn_cdn_gatt.py      → BLE GATT Server CDN (laptop)")
                    appendLine("sdn_controller_daemon.sh → MQTT fallback (legacy)")
                    appendLine()
                    appendLine("─── Arquitectura ───")
                    appendLine("BLE GATT = plano de control (preferido)")
                    appendLine("MQTT     = fallback (requiere WiFi activo)")
                }
                addLog("Consola: referencia ADB")
                return true
            }

            else -> return false // No es un comando → tratar como búsqueda REST
        }
    }

    // ─── Conexión MQTT ──────────────────────────────────────────

    /**
     * Conecta al broker MQTT y comienza el ciclo de telemetría.
     */
    fun connectMqtt(brokerIp: String, brokerPort: Int) {
        val brokerUrl = "tcp://$brokerIp:$brokerPort"
        val mac = _deviceMac.value

        addLog("Conectando a $brokerUrl...")
        mqttManager.connect(brokerUrl, mac)

        // Solo crear observer UNA vez
        if (connectionObserverJob == null || connectionObserverJob?.isActive != true) {
            connectionObserverJob = viewModelScope.launch {
                mqttManager.isConnected.collect { connected ->
                    if (connected) {
                        addLog("✓ Conectado al broker MQTT")
                        registerDevice()
                        startTelemetry()
                    } else {
                        stopTelemetry()
                    }
                }
            }
        }
    }

    /**
     * Desconecta del broker MQTT y detiene la telemetría.
     */
    fun disconnectMqtt() {
        stopTelemetry()
        connectionObserverJob?.cancel()
        mqttManager.disconnect()
        addLog("Desconectado del broker MQTT")
    }

    // ─── REST API ───────────────────────────────────────────────

    /**
     * Inicializa el cliente REST API con la URL del controlador.
     */
    fun initRestApi(brokerIp: String, restPort: Int) {
        val baseUrl = "http://$brokerIp:$restPort/"
        SdnApi.initialize(baseUrl)
        addLog("REST API configurada: $baseUrl")
    }

    /**
     * Solicita una nueva sesión de contenido al controlador.
     * POST /sessions/request al controlador.
     * Si el texto empieza con un comando local, lo ejecuta directamente.
     */
    fun requestSession(query: String) {
        // Intentar ejecutar como comando local primero
        if (executeLocalCommand(query)) return

        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            try {
                val request = SessionRequest(
                    originMac = _deviceMac.value,
                    query = query,
                    expectedContentType = "text"
                )
                val session = withContext(Dispatchers.IO) {
                    SdnApi.getService().requestSession(request)
                }
                _currentSession.value = session
                _searchResult.value = "Sesión creada: ${session.sessionId}\n" +
                        "Esperando comandos del controlador..."
                addLog("Sesión solicitada: ${session.sessionId} - query: \"$query\"")
            } catch (e: Exception) {
                _errorMessage.value = e.message
                _searchResult.value = "Error: ${e.message}"
                addLog("✗ Error en solicitud: ${e.message}")
                Log.e(TAG, "Error requesting session", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Confirma que el contenido fue entregado al usuario.
     * POST /sessions/{sessionId}/delivered
     * El controlador responderá con RELEASE_RADIO por MQTT.
     */
    fun confirmDelivery() {
        val sessionId = _currentSession.value?.sessionId ?: return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    SdnApi.getService().confirmDelivery(sessionId)
                }
                _searchResult.value = "Entrega confirmada para sesión $sessionId"
                addLog("✓ Entrega confirmada: $sessionId")
                _currentSession.value = null
            } catch (e: Exception) {
                _errorMessage.value = e.message
                addLog("✗ Error confirmando entrega: ${e.message}")
                Log.e(TAG, "Error confirming delivery", e)
            }
        }
    }

    // ─── Registro y Telemetría ──────────────────────────────────

    /**
     * Publica el registro del dispositivo en MQTT.
     * Tópico: dispositivo/{MAC}/registro (QoS 1, una vez)
     */
    private fun registerDevice() {
        val mac = _deviceMac.value
        val deviceInfo = DeviceInfo(
            mac = mac,
            name = Build.MODEL,
            deviceType = "PHONE",
            ipAddress = wifiController.getCurrentIp()
        )
        mqttManager.publishRegistration(deviceInfo)
        addLog("Registro enviado: $mac (${Build.MODEL})")
    }

    /**
     * Inicia la publicación periódica de telemetría (cada 30s).
     * Tópico: dispositivo/{MAC}/metrics (QoS 0)
     */
    private fun startTelemetry() {
        stopTelemetry()
        telemetryJob = viewModelScope.launch {
            while (isActive) {
                try {
                    val metrics = telemetryCollector.collect(
                        mac = _deviceMac.value,
                        activeRadio = _activeRadio.value
                    )
                    mqttManager.publishMetrics(metrics)
                    Log.d(TAG, "Telemetría enviada: rssi=${metrics.rssi}, bat=${metrics.batteryLevel}%")
                } catch (e: Exception) {
                    Log.e(TAG, "Error enviando telemetría", e)
                }
                delay(30_000) // 30 segundos
            }
        }
        addLog("Telemetría iniciada (cada 30s)")
    }

    /** Detiene la publicación de telemetría */
    private fun stopTelemetry() {
        telemetryJob?.cancel()
        telemetryJob = null
    }

    // ─── Utilidades ─────────────────────────────────────────────

    /**
     * Obtiene un identificador MAC único para el dispositivo.
     * Intenta usar la MAC WiFi real; si no está disponible (Android 10+),
     * genera un pseudo-MAC estable basado en ANDROID_ID.
     */
    @Suppress("DEPRECATION")
    private fun getDeviceMac(): String {
        return try {
            val wifiManager =
                context.getSystemService(android.content.Context.WIFI_SERVICE) as WifiManager
            val info = wifiManager.connectionInfo
            val mac = info.macAddress
            if (mac != null && mac != "02:00:00:00:00:00") {
                mac.uppercase()
            } else {
                // Android 10+ no expone la MAC real, generar pseudo-MAC
                val androidId = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ANDROID_ID
                )
                val hash = androidId.hashCode()
                String.format(
                    "A2:%02X:%02X:%02X:%02X:%02X",
                    (hash shr 0) and 0xFF,
                    (hash shr 8) and 0xFF,
                    (hash shr 16) and 0xFF,
                    (hash shr 24) and 0xFF,
                    (hash shr 4) and 0xFF
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error obteniendo MAC", e)
            "00:00:00:00:00:00"
        }
    }

    /**
     * Agrega una entrada al log con timestamp.
     */
    private fun addLog(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val entry = "[$timestamp] $message"
        _commandLog.value = listOf(entry) + _commandLog.value
        Log.d(TAG, entry)
    }

    /** Limpia todo el log de comandos */
    fun clearLog() {
        _commandLog.value = emptyList()
    }

    /** Limpia el mensaje de error */
    fun clearError() {
        _errorMessage.value = null
    }

    /**
     * Llamado por la Activity cuando el usuario habilita/rechaza BT.
     * Si BT fue habilitado, inicia el plano de control BLE.
     */
    fun onBluetoothEnableResult(enabled: Boolean) {
        _requestBluetoothEnable.value = false
        if (enabled) {
            addLog("✓ Bluetooth habilitado por el usuario")
            initBleControlPlane()
            commandHandler.retryPendingBtCommand()
        } else {
            addLog("✗ El usuario rechazó habilitar Bluetooth")
        }
    }

    /**
     * Envía un radio-request al controlador.
     * Prioridad: BLE GATT (plano de control nativo) → MQTT (fallback)
     */
    private fun sendRadioRequest(action: String, reason: String) {
        if (bleManager.isCdnConnected) {
            val json = """{"action":"$action","reason":"$reason"}"""
            bleManager.sendRequestToCdn(json)
            addLog("→ Radio-request vía BLE → CDN: $action")
        } else if (mqttManager.isConnected.value) {
            mqttManager.publishRadioRequest(action, reason)
            addLog("→ Radio-request vía MQTT (fallback): $action")
        } else {
            addLog("⚠ Sin canal para radio-request: $action")
            _searchResult.value = buildString {
                appendLine("✗ Sin canal de comunicación")
                appendLine()
                appendLine("No hay conexión BLE a CDN ni MQTT disponible.")
                appendLine()
                appendLine("Opciones:")
                appendLine("  1. 'ble start' para plano de control BLE")
                appendLine("  2. Conectar MQTT en pestaña Config (fallback)")
                appendLine("  3. Ejecutar desde laptop:")
                appendLine("     adb shell svc ${if (action.contains("bt")) "bluetooth" else "wifi"} ${if (action.contains("enable")) "enable" else "disable"}")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopTelemetry()
        connectionObserverJob?.cancel()
        errorObserverJob?.cancel()
        commandHandler.destroy()
        bleManager.stopEverything()  // Stop all + disconnect CDN
        mqttManager.disconnect()
        Log.i(TAG, "ViewModel cleared, recursos liberados")
    }

    /** Diagnóstico del nivel de control de radios disponible */
    fun getRadioDiagnostic(): String = commandHandler.runDiagnostic()
}
