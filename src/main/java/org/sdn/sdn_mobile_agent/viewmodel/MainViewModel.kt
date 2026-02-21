package org.sdn.sdn_mobile_agent.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.sdn.sdn_mobile_agent.data.model.*
import org.sdn.sdn_mobile_agent.data.mqtt.MqttManager
import org.sdn.sdn_mobile_agent.data.preferences.AppPreferences
import org.sdn.sdn_mobile_agent.data.remote.ContentDeliveryResponse
import org.sdn.sdn_mobile_agent.data.remote.SdnApi
import org.sdn.sdn_mobile_agent.service.*
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import org.json.JSONObject

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
        /** Puerto del CDN Python server (sdn_cdn_server.py) */
        const val CDN_PORT = 8080
        /** Umbral de tamaño para activar WiFi automáticamente (10 MB) */
        const val WIFI_AUTO_THRESHOLD = 10_000_000L
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

    /** Resultados de búsqueda en la CDN */
    private val _searchResults = MutableStateFlow<List<ContentItem>>(emptyList())
    val searchResults: StateFlow<List<ContentItem>> = _searchResults

    /** Progreso de descarga actual */
    private val _downloadProgress = MutableStateFlow(DownloadProgress())
    val downloadProgress: StateFlow<DownloadProgress> = _downloadProgress

    /** Contenido pendiente de descargar (esperando WiFi) */
    private var pendingContentDownload: ContentItem? = null

    /** URL base de la CDN (se configura dinámicamente) */
    private var cdnBaseUrl: String = ""

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
                    // Si llega WIFI_READY y hay contenido pendiente → descargar
                    if (command.action == "WIFI_READY") {
                        onWifiReadyForContent()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parseando control CDN: $json", e)
            }
        }

        // ── BLE GATT Client: recibir respuestas de datos de la CDN ──
        bleManager.onCdnResponse = { json ->
            viewModelScope.launch(Dispatchers.Main) {
                addLog("⬇ Respuesta CDN vía BLE: ${json.take(80)}")
                handleBleResponse(json)
            }
        }

        // ── MQTT: recibir comandos como fallback (cuando WiFi activo) ──
        mqttManager.onCommandReceived = { command ->
            viewModelScope.launch(Dispatchers.Main) {
                addLog("⬇ Comando vía MQTT: ${command.action}")
                commandHandler.handle(command)
                // Si llega WIFI_READY y hay contenido pendiente → descargar
                if (command.action == "WIFI_READY") {
                    onWifiReadyForContent()
                }
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

        // ── BLE: registrar callback de conexión CDN ──
        bleManager.onCdnConnectionChanged = { connected ->
            viewModelScope.launch(Dispatchers.Main) {
                if (connected) {
                    addLog("✓ Conectado a CDN vía BLE GATT")
                    registerDeviceViaBle()
                    startTelemetryBle()
                } else {
                    addLog("✗ Desconexión de CDN BLE")
                }
            }
        }
    }

    /**
     * Parsea y maneja respuestas JSON recibidas del GATT Server CDN.
     * Tipos: search_results, content_meta, ack, error, status
     */
    private fun handleBleResponse(json: String) {
        try {
            val obj = JSONObject(json)
            when (obj.optString("type")) {
                "search_results" -> {
                    val arr = obj.getJSONArray("results")
                    val items = mutableListOf<ContentItem>()
                    for (i in 0 until arr.length()) {
                        val r = arr.getJSONObject(i)
                        val tags = mutableListOf<String>()
                        val tagsArr = r.optJSONArray("tags")
                        if (tagsArr != null) {
                            for (j in 0 until tagsArr.length()) tags.add(tagsArr.getString(j))
                        }
                        items.add(ContentItem(
                            id = r.getString("id"),
                            title = r.getString("title"),
                            contentType = r.getString("contentType"),
                            sizeBytes = r.getLong("sizeBytes"),
                            filename = r.getString("filename"),
                            tags = tags,
                            description = r.optString("description", ""),
                            thumbnailUrl = if (r.isNull("thumbnailUrl")) null else r.optString("thumbnailUrl")
                        ))
                    }
                    _searchResults.value = items
                    _searchResult.value = buildString {
                        appendLine("${items.size} resultado(s) vía BLE")
                        items.forEach { item ->
                            val tag = if (item.requiresWifi) " [WiFi]" else " [BLE]"
                            appendLine("${item.icon} ${item.title} (${item.humanSize})$tag")
                        }
                        if (items.isNotEmpty()) appendLine("\nSelecciona un resultado.")
                    }
                    addLog("Búsqueda BLE: ${obj.optString("query")} → ${items.size} resultados")
                }
                "content_meta" -> {
                    val activated = obj.optBoolean("wifiActivated", false)
                    val streamUrl = obj.optString("streamUrl", "")
                    val title = obj.optString("title", "")
                    val sizeBytes = obj.optLong("sizeBytes", 0)

                    // Siempre extraer cdnBaseUrl del streamUrl, se use WiFi o no
                    if (streamUrl.isNotEmpty() && streamUrl.contains("/api/")) {
                        cdnBaseUrl = streamUrl.substringBeforeLast("/api/")
                        addLog("CDN base URL: $cdnBaseUrl")
                    }

                    if (activated) {
                        addLog("CDN activó WiFi para: $title")
                        _searchResult.value = "📡 WiFi activándose para: $title\nEsperando WIFI_READY..."
                    } else {
                        addLog("Contenido listo: $title ($streamUrl)")
                        // Si ya hay WiFi o es pequeño, descargar
                        val pending = pendingContentDownload
                        if (pending != null && streamUrl.isNotEmpty()) {
                            downloadAndOpenContent(pending)
                        }
                    }
                }
                "ack" -> {
                    val msg = obj.optString("message", "OK")
                    addLog("ACK: $msg")
                }
                "error" -> {
                    val msg = obj.optString("message", "Error")
                    _errorMessage.value = msg
                    addLog("✗ CDN error: $msg")
                }
                else -> {
                    _searchResult.value = json
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parseando BLE response", e)
            _searchResult.value = json // Mostrar raw si falla el parse
        }
    }

    /**
     * Inicializa el plano de control BLE: GATT Server + Advertising.
     * Debe llamarse cuando BT está habilitado.
     */
    /**
     * Inicializa el plano de control BLE: scan + advertising + auto-connect CDN.
     * Debe llamarse cuando BT está habilitado.
     */
    fun initBleControlPlane() {
        if (!bleManager.isBluetoothEnabled) {
            addLog("⚠ BT apagado — no se puede iniciar plano de control BLE")
            return
        }
        bleManager.startAdvertising()
        // Escanear y auto-conectar a la CDN por SDN_SERVICE_UUID
        bleManager.resetReconnectCount()  // Reset solo en inicio manual
        bleManager.scanAndConnectCdn()
        addLog("✓ Plano de control BLE activo (buscando CDN...)")
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
                _searchResult.value = buildString {
                    appendLine("ℹ BT es el plano de control SDN — siempre debe estar ON")
                    appendLine()
                    appendLine("Si BT está apagado, encéndelo manualmente")
                    appendLine("desde los Ajustes del teléfono.")
                    appendLine()
                    appendLine("Comando ADB equivalente (ejecutar desde laptop):")
                    appendLine("  adb shell svc bluetooth enable")
                    appendLine()
                    appendLine("Para reconectar BLE: escribe 'ble start'")
                }
                return true
            }

            cmd == "bt off" -> {
                addLog("Consola: bt off (bloqueado)")
                _searchResult.value = buildString {
                    appendLine("⚠ BT NO se puede apagar desde la app")
                    appendLine()
                    appendLine("BLE es el plano de control SDN.")
                    appendLine("Si se apaga BT, se pierde toda comunicación")
                    appendLine("con el servidor CDN.")
                    appendLine()
                    appendLine("Solo WiFi se gestiona automáticamente")
                    appendLine("(plano de datos, ON/OFF por demanda).")
                    appendLine()
                    appendLine("Si necesitas apagar BT manualmente:")
                    appendLine("  Ajustes del teléfono → Bluetooth → OFF")
                }
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
     * También inicializa la conexión a la CDN (Python server en CDN_PORT).
     */
    fun initRestApi(brokerIp: String, restPort: Int) {
        val baseUrl = "http://$brokerIp:$restPort/"
        SdnApi.initialize(baseUrl)
        addLog("REST API configurada: $baseUrl")

        // Inicializar CDN en el mismo host, puerto CDN_PORT
        cdnBaseUrl = "http://$brokerIp:$CDN_PORT"
        val cdnUrl = "$cdnBaseUrl/"
        SdnApi.initializeCdn(cdnUrl)
        addLog("CDN API configurada: $cdnUrl")
    }

    /**
     * Busca contenido o ejecuta un comando local.
     * Si el texto es un comando reconocido → lo ejecuta localmente.
     * Si no → busca contenido en la CDN.
     */
    fun requestSession(query: String) {
        // Intentar ejecutar como comando local primero
        if (executeLocalCommand(query)) return

        // Buscar contenido en la CDN
        searchContent(query)
    }

    /**
     * Busca contenido en la CDN.
     * Prioridad: BLE GATT (sin WiFi) → REST HTTP (fallback si WiFi activo)
     */
    fun searchContent(query: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            _searchResults.value = emptyList()

            // ── Prioridad 1: BLE GATT (funciona sin WiFi) ──
            if (bleManager.isCdnConnected) {
                val json = """{"type":"search","query":"$query"}"""
                val sent = bleManager.sendRequestToCdn(json)
                if (sent) {
                    addLog("→ Búsqueda vía BLE GATT: \"$query\"")
                    _searchResult.value = "Buscando \"$query\" vía BLE..."
                    // La respuesta llega por onCdnResponse → handleBleResponse()
                    _isLoading.value = false
                    return@launch
                } else {
                    addLog("⚠ BLE envío falló, estado: ${bleManager.cdnConnectionState.value}")
                }
            } else {
                val bleState = bleManager.cdnConnectionState.value
                addLog("BLE no conectado (estado: $bleState), intentando alternativas...")
            }

            // ── Prioridad 2: REST HTTP (requiere WiFi activo) ──
            if (SdnApi.isCdnInitialized()) {
                try {
                    val response = withContext(Dispatchers.IO) {
                        SdnApi.getCdnService().searchContent(query)
                    }
                    _searchResults.value = response.results
                    if (response.results.isEmpty()) {
                        _searchResult.value = "No se encontraron resultados para \"$query\""
                    } else {
                        _searchResult.value = buildString {
                            appendLine("${response.totalResults} resultado(s) para \"$query\" (vía WiFi)")
                            response.results.forEach { item ->
                                val wifiTag = if (item.requiresWifi) " [WiFi]" else " [BLE]"
                                appendLine("${item.icon} ${item.title} (${item.humanSize})$wifiTag")
                            }
                            appendLine("\nSelecciona un resultado.")
                        }
                    }
                    addLog("Búsqueda REST: \"$query\" → ${response.totalResults} resultados")
                } catch (e: Exception) {
                    _errorMessage.value = "Error buscando: ${e.message}"
                    _searchResult.value = "Error: ${e.message}"
                    addLog("✗ Error búsqueda: ${e.message}")
                    Log.e(TAG, "Error searching CDN", e)
                }
            } else {
                val bleState = bleManager.cdnConnectionState.value
                _errorMessage.value = "Sin conexión a CDN. Ve a Config → Conectar BLE."
                _searchResult.value = buildString {
                    appendLine("✗ Sin canal de búsqueda disponible")
                    appendLine()
                    appendLine("BLE GATT: $bleState")
                    appendLine()
                    appendLine("Ve a Config y toca 'Conectar BLE'.")
                    appendLine("El GATT server del laptop debe estar corriendo.")
                }
            }

            _isLoading.value = false
        }
    }

    /**
     * Selecciona un contenido para descarga/reproducción.
     * Si el contenido es grande (> 10 MB):
     *   - Verifica si WiFi está ON
     *   - Si no: solicita enable_wifi vía ADB → espera WIFI_READY → descarga
     *   - Si sí: descarga directamente
     * Si el contenido es pequeño: descarga directamente.
     */
    fun selectContent(item: ContentItem) {
        addLog("Seleccionado: ${item.title} (${item.humanSize})")

        viewModelScope.launch {
            if (item.requiresWifi && !wifiController.isWifiClientEnabled) {
                // WiFi OFF y contenido grande → pedir content_request vía BLE
                _downloadProgress.value = DownloadProgress(
                    item = item,
                    state = DownloadState.WAITING_WIFI,
                    totalBytes = item.sizeBytes
                )
                pendingContentDownload = item

                if (bleManager.isCdnConnected) {
                    // Pedir al GATT server que active WiFi y devuelva streamUrl
                    val json = """{"type":"content_request","id":"${item.id}"}"""
                    bleManager.sendRequestToCdn(json)
                    _searchResult.value = buildString {
                        appendLine("📡 Contenido grande: ${item.humanSize}")
                        appendLine("Solicitando WiFi vía BLE GATT...")
                        appendLine("La CDN encenderá WiFi vía ADB.")
                    }
                    addLog("→ Content request vía BLE: ${item.id}")
                } else {
                    // Fallback: radio request directo
                    sendRadioRequest("enable_wifi", "Contenido grande: ${item.title} (${item.humanSize})")
                    _searchResult.value = buildString {
                        appendLine("📡 Contenido grande: ${item.humanSize}")
                        appendLine("WiFi OFF → Solicitando activación...")
                    }
                }
                return@launch
            }

            // WiFi ya ON o contenido pequeño → descargar directamente
            downloadAndOpenContent(item)
        }
    }

    /**
     * Llamado cuando llega WIFI_READY y hay contenido pendiente.
     * Continúa la descarga que estaba esperando WiFi.
     */
    fun onWifiReadyForContent() {
        val pending = pendingContentDownload ?: return
        pendingContentDownload = null
        addLog("✓ WiFi listo → descargando '${pending.title}'...")
        viewModelScope.launch {
            // Esperar que WiFi se estabilice
            delay(2000)

            // ── Fix: inicializar CDN Retrofit si aún no existe ──
            // En flujo BLE-only, initRestApi() nunca se llamó.
            // Derivar IP de la CDN desde cdnBaseUrl (ya seteado por content_meta)
            // o desde la config guardada.
            if (!SdnApi.isCdnInitialized() && cdnBaseUrl.isNotEmpty()) {
                val cdnUrl = if (cdnBaseUrl.endsWith("/")) cdnBaseUrl else "$cdnBaseUrl/"
                SdnApi.initializeCdn(cdnUrl)
                addLog("CDN API inicializada (auto): $cdnUrl")
            } else if (!SdnApi.isCdnInitialized()) {
                // Intentar derivar IP de las preferencias guardadas
                val savedIp = preferences.brokerIp.first()
                if (savedIp.isNotBlank()) {
                    cdnBaseUrl = "http://$savedIp:$CDN_PORT"
                    SdnApi.initializeCdn("$cdnBaseUrl/")
                    addLog("CDN API inicializada (desde config): $cdnBaseUrl/")
                }
            }

            downloadAndOpenContent(pending)
        }
    }

    /**
     * Descarga el contenido de la CDN y lo abre con la app adecuada.
     * Flujo:
     *   1. POST /api/content/{id}/request → obtener streamUrl (o directo si CDN API no init)
     *   2. Descargar archivo vía HTTP a almacenamiento local
     *   3. Abrir con Intent.ACTION_VIEW (reproductor de video, visor PDF, etc.)
     *   4. Apagar WiFi automáticamente si se encendió para esta descarga
     */
    private fun downloadAndOpenContent(item: ContentItem) {
        viewModelScope.launch {
            _downloadProgress.value = DownloadProgress(
                item = item,
                state = DownloadState.DOWNLOADING,
                totalBytes = item.sizeBytes
            )
            _searchResult.value = "⬇ Descargando: ${item.title} (${item.humanSize})..."

            try {
                // 1. Solicitar entrega a la CDN
                addLog("→ Solicitando entrega de '${item.title}'...")

                // Construir streamUrl — con o sin Retrofit CDN
                val delivery: ContentDeliveryResponse? = if (SdnApi.isCdnInitialized()) {
                    try {
                        withContext(Dispatchers.IO) {
                            SdnApi.getCdnService().requestContent(item.id)
                        }
                    } catch (e: Exception) {
                        addLog("⚠ CDN REST falló (${e.message}), usando URL directa")
                        null
                    }
                } else {
                    addLog("CDN API no inicializada — usando URL directa desde cdnBaseUrl")
                    null
                }

                if (delivery?.wifiActivated == true) {
                    addLog("CDN activó WiFi automáticamente para este contenido")
                    delay(3000) // Esperar estabilización
                }

                // 2. Descargar archivo
                val streamUrl = delivery?.streamUrl?.ifEmpty { null }
                    ?: "$cdnBaseUrl/api/content/${item.id}/stream"
                addLog("Descargando desde: $streamUrl")

                val localFile = withContext(Dispatchers.IO) {
                    downloadFile(streamUrl, item)
                }

                if (localFile == null) {
                    throw Exception("Error descargando archivo")
                }

                _downloadProgress.value = DownloadProgress(
                    item = item,
                    state = DownloadState.COMPLETED,
                    bytesDownloaded = localFile.length(),
                    totalBytes = item.sizeBytes
                )

                addLog("✓ Descargado: ${localFile.name} (${localFile.length()} bytes)")
                _searchResult.value = buildString {
                    appendLine("✓ Descarga completada: ${item.title}")
                    appendLine("Tamaño: ${item.humanSize}")
                    appendLine("Archivo: ${localFile.name}")
                }

                // 3. Abrir con la app adecuada
                openContentFile(localFile, item.contentType)

                // 4. Auto-apagar WiFi si se encendió para esta descarga
                if (item.requiresWifi) {
                    addLog("Descarga completada → apagando WiFi (ahorro energético)")
                    delay(1000) // Pequeña pausa antes de apagar
                    sendRadioRequest("disable_wifi", "Descarga completada: ${item.title}")
                }

            } catch (e: Exception) {
                _downloadProgress.value = DownloadProgress(
                    item = item,
                    state = DownloadState.ERROR,
                    errorMessage = e.message
                )
                _searchResult.value = "✗ Error descargando: ${e.message}"
                addLog("✗ Error descarga: ${e.message}")
                Log.e(TAG, "Error downloading content", e)
            }
        }
    }

    /**
     * Descarga un archivo desde una URL HTTP al almacenamiento local.
     * Actualiza el progreso conforme descarga.
     */
    private fun downloadFile(url: String, item: ContentItem): File? {
        val client = OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()

        if (!response.isSuccessful) {
            Log.e(TAG, "Download failed: HTTP ${response.code}")
            return null
        }

        // Directorio de descargas de la app
        val downloadDir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "sdn_content")
        downloadDir.mkdirs()

        // Nombre del archivo
        val extension = when {
            item.contentType.contains("mp4") -> ".mp4"
            item.contentType.contains("pdf") -> ".pdf"
            item.contentType.contains("text") -> ".txt"
            item.contentType.contains("jpeg") || item.contentType.contains("jpg") -> ".jpg"
            item.contentType.contains("png") -> ".png"
            else -> ""
        }
        val safeTitle = item.title.replace(Regex("[^a-zA-Z0-9_\\- ]"), "").take(50)
        val localFile = File(downloadDir, "${safeTitle}$extension")

        response.body?.let { body ->
            val totalBytes = body.contentLength()
            var downloadedBytes = 0L

            FileOutputStream(localFile).use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead

                        // Actualizar progreso cada 100KB
                        if (downloadedBytes % (100 * 1024) < 8192) {
                            _downloadProgress.value = DownloadProgress(
                                item = item,
                                state = DownloadState.DOWNLOADING,
                                bytesDownloaded = downloadedBytes,
                                totalBytes = if (totalBytes > 0) totalBytes else item.sizeBytes
                            )
                        }
                    }
                }
            }
        }

        return if (localFile.exists() && localFile.length() > 0) localFile else null
    }

    /**
     * Abre un archivo con la app adecuada del sistema.
     * Video → reproductor, PDF → visor, Texto → editor.
     */
    private fun openContentFile(file: File, mimeType: String) {
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            addLog("Abriendo: ${file.name} ($mimeType)")

            _downloadProgress.value = DownloadProgress(
                item = _downloadProgress.value.item,
                state = DownloadState.PLAYING
            )
        } catch (e: Exception) {
            addLog("⚠ No se pudo abrir el archivo: ${e.message}")
            Log.e(TAG, "Error opening file", e)
            // Fallback: mostrar ruta del archivo
            _searchResult.value = buildString {
                appendLine("✓ Archivo descargado: ${file.absolutePath}")
                appendLine("No se encontró app para abrir $mimeType")
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
     * Registra el dispositivo vía BLE GATT (plano de control primario).
     * No requiere WiFi.
     */
    private fun registerDeviceViaBle() {
        val mac = _deviceMac.value
        val json = """{"type":"register","mac":"$mac","name":"${Build.MODEL}","deviceType":"PHONE","ipAddress":"${wifiController.getCurrentIp()}"}"""
        bleManager.sendRequestToCdn(json)
        addLog("Registro enviado vía BLE: $mac (${Build.MODEL})")
    }

    /**
     * Publica el registro del dispositivo en MQTT (fallback, requiere WiFi).
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
        addLog("Registro enviado vía MQTT: $mac (${Build.MODEL})")
    }

    /**
     * Inicia telemetría vía BLE GATT (cada 30s, no requiere WiFi).
     */
    private fun startTelemetryBle() {
        stopTelemetry()
        telemetryJob = viewModelScope.launch {
            while (isActive) {
                try {
                    val metrics = telemetryCollector.collect(
                        mac = _deviceMac.value,
                        activeRadio = _activeRadio.value
                    )
                    if (bleManager.isCdnConnected) {
                        val json = """{"type":"telemetry","data":{"batteryLevel":${metrics.batteryLevel},"rssi":${metrics.rssi},"activeRadio":"${metrics.technology}","mac":"${metrics.mac}"}}"""
                        bleManager.sendRequestToCdn(json)
                    } else if (mqttManager.isConnected.value) {
                        mqttManager.publishMetrics(metrics)
                    }
                    Log.d(TAG, "Telemetría enviada: bat=${metrics.batteryLevel}% radio=${metrics.technology}")
                } catch (e: Exception) {
                    Log.e(TAG, "Error enviando telemetría", e)
                }
                delay(30_000)
            }
        }
        addLog("Telemetría iniciada (cada 30s vía BLE)")
    }

    /**
     * Inicia telemetría vía MQTT (fallback, requiere WiFi).
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
                    Log.d(TAG, "Telemetría MQTT: rssi=${metrics.rssi}, bat=${metrics.batteryLevel}%")
                } catch (e: Exception) {
                    Log.e(TAG, "Error enviando telemetría", e)
                }
                delay(30_000)
            }
        }
        addLog("Telemetría MQTT iniciada (cada 30s)")
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
