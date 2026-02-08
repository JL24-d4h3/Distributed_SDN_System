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

    private var telemetryJob: Job? = null
    private var connectionObserverJob: Job? = null

    // ─── Inicialización ─────────────────────────────────────────

    init {
        // Configurar CommandHandler con callbacks
        commandHandler = CommandHandler(
            bleManager = bleManager,
            wifiController = wifiController,
            onRadioChanged = { radio -> _activeRadio.value = radio },
            onLog = { msg -> addLog(msg) }
        )

        // Registrar callback para comandos MQTT
        mqttManager.onCommandReceived = { command ->
            viewModelScope.launch(Dispatchers.Main) {
                commandHandler.handle(command)
            }
        }

        // Obtener MAC del dispositivo
        _deviceMac.value = getDeviceMac()
        Log.i(TAG, "Device MAC: ${_deviceMac.value}")
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

        // Observar cambios de conexión
        connectionObserverJob?.cancel()
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
     * POST /sessions/request
     */
    fun requestSession(query: String) {
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

    override fun onCleared() {
        super.onCleared()
        stopTelemetry()
        connectionObserverJob?.cancel()
        bleManager.stopAll()
        mqttManager.disconnect()
        Log.i(TAG, "ViewModel cleared, recursos liberados")
    }
}
