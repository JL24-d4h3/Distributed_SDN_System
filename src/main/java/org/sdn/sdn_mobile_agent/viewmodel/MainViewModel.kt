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
 * Orquesta todos los componentes:
 * - MqttManager: conexión MQTT con el broker
 * - BleManager: operaciones Bluetooth LE
 * - WifiController: conexiones WiFi de datos
 * - RadioController: control directo de radios BT/WiFi
 * - TelemetryCollector: recopilación de métricas
 * - CommandHandler: procesamiento de comandos del controlador
 * - SdnApi: comunicación REST con el controlador
 * - AppPreferences: persistencia de configuración
 *
 * Expone StateFlows para que las pantallas Compose observen los cambios.
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
                mqttManager.publishRadioRequest(action, reason)
            }
        )

        // Registrar callback para comandos MQTT
        mqttManager.onCommandReceived = { command ->
            viewModelScope.launch(Dispatchers.Main) {
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
    }

    // ─── Consola de Comandos Locales ────────────────────────────

    /**
     * Ejecuta un comando local desde la consola de búsqueda.
     * Retorna true si era un comando reconocido, false si debe tratarse
     * como búsqueda REST normal.
     *
     * Arquitectura SDN para control de radios:
     * ═════════════════════════════════════════
     * bt on / bt off   → Publica MQTT radio-request → Controlador ejecuta ADB
     * prepare / release → Simula comandos PREPARE_BT / RELEASE_RADIO
     * ble start / stop  → Control directo (funciona si BT ya está ON)
     * status / diag     → Solo lectura, siempre funciona
     */
    fun executeLocalCommand(input: String): Boolean {
        val cmd = input.trim().lowercase()

        when {
            cmd == "help" || cmd == "?" -> {
                _searchResult.value = buildString {
                    appendLine("═══ Consola SDN Mobile Agent ═══")
                    appendLine()
                    appendLine("─── Control de Radios (vía Controlador) ───")
                    appendLine("▸ bt on         Solicitar encender BT (MQTT→ADB)")
                    appendLine("▸ bt off        Solicitar apagar BT (MQTT→ADB)")
                    appendLine("▸ wifi on       Solicitar encender WiFi (MQTT→ADB)")
                    appendLine()
                    appendLine("─── Operaciones BLE (locales) ───")
                    appendLine("▸ ble start     Iniciar BLE scan+advertising")
                    appendLine("▸ ble stop      Detener BLE scan+advertising")
                    appendLine()
                    appendLine("─── Simulación de Comandos SDN ───")
                    appendLine("▸ prepare       Simular PREPARE_BT del controlador")
                    appendLine("▸ release       Simular RELEASE_RADIO del controlador")
                    appendLine()
                    appendLine("─── Diagnóstico ───")
                    appendLine("▸ status        Estado actual de todas las radios")
                    appendLine("▸ diag          Diagnóstico de privilegios del sistema")
                    appendLine("▸ adb           Comandos ADB de referencia")
                    appendLine()
                    appendLine("Cualquier otro texto → búsqueda REST")
                }
                addLog("Consola: help")
                return true
            }

            cmd == "bt on" -> {
                addLog("Consola: bt on")
                if (mqttManager.isConnected.value) {
                    mqttManager.publishRadioRequest("enable_bt", "Solicitud desde consola")
                    _searchResult.value = buildString {
                        appendLine("→ Solicitud enviada al controlador vía MQTT")
                        appendLine()
                        appendLine("Flujo:")
                        appendLine("  App → MQTT radio-request → Controlador")
                        appendLine("  Controlador → adb shell svc bluetooth enable")
                        appendLine("  Controlador → MQTT BT_READY → App")
                        appendLine()
                        appendLine("Esperando confirmación BT_READY...")
                    }
                    addLog("→ Radio request enviado: enable_bt")
                } else {
                    _searchResult.value = buildString {
                        appendLine("✗ MQTT no conectado")
                        appendLine()
                        appendLine("No se puede enviar la solicitud al controlador.")
                        appendLine("Opciones:")
                        appendLine("  1. Conecta MQTT primero (pestaña Config)")
                        appendLine("  2. Ejecuta desde la laptop:")
                        appendLine("     adb shell svc bluetooth enable")
                    }
                    addLog("✗ MQTT no conectado para bt on")
                }
                return true
            }

            cmd == "bt off" -> {
                addLog("Consola: bt off")
                // Detener BLE primero (local)
                bleManager.stopAll()
                if (mqttManager.isConnected.value) {
                    mqttManager.publishRadioRequest("disable_bt", "Solicitud desde consola")
                    _searchResult.value = buildString {
                        appendLine("→ BLE detenido + solicitud enviada al controlador")
                        appendLine()
                        appendLine("Flujo:")
                        appendLine("  App → BLE stop (local)")
                        appendLine("  App → MQTT radio-request → Controlador")
                        appendLine("  Controlador → adb shell svc bluetooth disable")
                        appendLine("  Controlador → MQTT BT_DISABLED → App")
                        appendLine()
                        appendLine("Esperando confirmación BT_DISABLED...")
                    }
                    addLog("→ BLE detenido + radio request: disable_bt")
                } else {
                    _searchResult.value = buildString {
                        appendLine("✓ BLE detenido (local)")
                        appendLine("✗ MQTT no conectado — no se puede apagar radio BT")
                        appendLine()
                        appendLine("Ejecuta desde la laptop:")
                        appendLine("  adb shell svc bluetooth disable")
                    }
                    addLog("✗ MQTT no conectado para bt off")
                }
                _activeRadio.value = "idle"
                return true
            }

            cmd == "wifi on" -> {
                addLog("Consola: wifi on")
                if (mqttManager.isConnected.value) {
                    mqttManager.publishRadioRequest("enable_wifi", "Solicitud desde consola")
                    _searchResult.value = "→ Solicitud enviada al controlador (enable_wifi)\n\n" +
                            "Controlador ejecutará: adb shell svc wifi enable"
                    addLog("→ Radio request enviado: enable_wifi")
                } else {
                    _searchResult.value = "✗ MQTT no conectado\n\n" +
                            "Desde la laptop: adb shell svc wifi enable"
                    addLog("✗ MQTT no conectado para wifi on")
                }
                return true
            }

            cmd == "wifi off" -> {
                addLog("Consola: wifi off")
                _searchResult.value = "⚠ WiFi off deshabilitado — cortaría la conexión MQTT\n\n" +
                        "Para apagar WiFi:\n" +
                        "  Desde la laptop: adb shell svc wifi disable\n\n" +
                        "⚠ Esto desconectará la app del broker MQTT."
                addLog("⚠ WiFi off bloqueado (cortaría MQTT)")
                return true
            }

            cmd == "ble start" -> {
                addLog("Consola: ble start")
                commandHandler.executeBtActivation()
                _searchResult.value = if (bleManager.isBluetoothEnabled) {
                    "✓ BLE scan + advertising iniciados"
                } else {
                    "✗ BT apagado — enciéndelo primero:\n" +
                            "  Escribe: bt on\n" +
                            "  O desde laptop: adb shell svc bluetooth enable"
                }
                return true
            }

            cmd == "ble stop" -> {
                addLog("Consola: ble stop")
                bleManager.stopAll()
                _searchResult.value = "✓ BLE detenido (scan + advertising)"
                _activeRadio.value = "idle"
                addLog("✓ BLE detenido vía consola")
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
                _searchResult.value = buildString {
                    appendLine("═══ Estado Actual ═══")
                    appendLine()
                    appendLine("─── Hardware ───")
                    appendLine("BT Radio: ${if (bleManager.isBluetoothEnabled) "ON ✓" else "OFF ✗"}")
                    appendLine("WiFi IP: ${wifiController.getCurrentIp()}")
                    appendLine("WiFi RSSI: ${wifiController.getCurrentRssi()} dBm")
                    appendLine()
                    appendLine("─── Operaciones ───")
                    appendLine("BLE State: ${bleManager.bleState.value}")
                    appendLine("WiFi Datos: ${if (wifiController.dataWifiConnected.value) "Conectado" else "No"}")
                    appendLine("Radio Activa: ${_activeRadio.value}")
                    appendLine()
                    appendLine("─── Conexión SDN ───")
                    appendLine("MQTT: ${if (mqttManager.isConnected.value) "Conectado ✓" else "Desconectado ✗"}")
                    appendLine("MAC: ${_deviceMac.value}")
                    appendLine()
                    appendLine("─── Arquitectura ───")
                    appendLine("Control de radios: vía Controlador (MQTT→ADB)")
                    appendLine("Operaciones BLE: locales (directo)")
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
                    appendLine("─── Script Automático ───")
                    appendLine("./sdn_controller_daemon.sh")
                    appendLine("  → Escucha MQTT radio-requests y ejecuta ADB")
                    appendLine("  → Los comandos 'bt on/off' de esta consola")
                    appendLine("     se envían automáticamente al daemon")
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
     * Si BT fue habilitado, reintenta el comando pendiente.
     */
    fun onBluetoothEnableResult(enabled: Boolean) {
        _requestBluetoothEnable.value = false
        if (enabled) {
            addLog("✓ Bluetooth habilitado por el usuario")
            commandHandler.retryPendingBtCommand()
        } else {
            addLog("✗ El usuario rechazó habilitar Bluetooth")
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopTelemetry()
        connectionObserverJob?.cancel()
        errorObserverJob?.cancel()
        commandHandler.destroy()
        bleManager.stopAll()
        mqttManager.disconnect()
        Log.i(TAG, "ViewModel cleared, recursos liberados")
    }

    /** Diagnóstico del nivel de control de radios disponible */
    fun getRadioDiagnostic(): String = commandHandler.runDiagnostic()
}
