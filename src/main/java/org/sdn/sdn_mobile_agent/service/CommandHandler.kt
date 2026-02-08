package org.sdn.sdn_mobile_agent.service

import android.util.Log
import kotlinx.coroutines.*
import org.sdn.sdn_mobile_agent.data.model.Command

/**
 * Procesa los comandos recibidos del controlador SDN vía MQTT.
 *
 * Arquitectura de control de radios (Android 13+):
 * ═══════════════════════════════════════════════════
 * Las apps normales NO pueden encender/apagar BT/WiFi en Android 13+.
 * El control de radios se delega al CONTROLADOR (laptop) vía ADB:
 *
 *   App publica → MQTT radio-request → Controlador escucha
 *   Controlador ejecuta → adb shell svc bluetooth enable/disable
 *   Controlador confirma → MQTT BT_READY / BT_DISABLED → App procede
 *
 * Comandos soportados:
 * - PREPARE_BT: Si BT ON → activa BLE. Si BT OFF → pide al controlador.
 * - BT_READY: Controlador confirma que BT fue encendido vía ADB.
 * - BT_DISABLED: Controlador confirma que BT fue apagado vía ADB.
 * - SWITCH_WIFI: Conecta a la red WiFi de datos indicada.
 * - RELEASE_RADIO: Detiene BLE + pide al controlador apagar BT.
 *
 * @param radioController Diagnóstico de privilegios (Device Owner, root)
 * @param bleManager Gestor de operaciones BLE (scan, advertising, GATT)
 * @param wifiController Controlador de conexiones WiFi de datos
 * @param onRadioChanged Callback cuando cambia la radio activa
 * @param onLog Callback para registrar eventos en el log de la UI
 * @param onRequestBluetoothEnable Fallback: pedir al usuario encender BT
 * @param onPublishRadioRequest Publica un pedido MQTT al controlador para toggle de radio
 */
class CommandHandler(
    private val radioController: RadioController,
    private val bleManager: BleManager,
    private val wifiController: WifiController,
    private val onRadioChanged: (String) -> Unit,
    private val onLog: (String) -> Unit,
    private val onRequestBluetoothEnable: (() -> Unit)? = null,
    private val onPublishRadioRequest: ((action: String, reason: String) -> Unit)? = null
) {

    companion object {
        private const val TAG = "CommandHandler"
    }

    private val handlerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Último comando pendiente de ejecutar cuando BT se habilite */
    private var pendingBtCommand: Command? = null

    /** Job del timeout para el radio request */
    private var radioRequestTimeoutJob: Job? = null

    /**
     * Procesa un comando recibido del controlador.
     * Despacha al handler específico según la acción.
     */
    fun handle(command: Command) {
        Log.i(TAG, "Procesando comando: ${command.action} (sesión: ${command.sessionId})")
        onLog("[${command.sessionId}] ${command.action}: ${command.reason ?: "sin razón"}")

        when (command.action) {
            "PREPARE_BT"    -> handlePrepareBt(command)
            "BT_READY"      -> handleBtReady(command)
            "BT_DISABLED"   -> handleBtDisabled(command)
            "SWITCH_WIFI"   -> handleSwitchWifi(command)
            "RELEASE_RADIO" -> handleReleaseRadio(command)
            else -> {
                Log.w(TAG, "Acción desconocida: ${command.action}")
                onLog("Acción desconocida: ${command.action}")
            }
        }
    }

    /**
     * PREPARE_BT: Activa BLE scan + advertising.
     *
     * Flujo:
     * 1. Si BT ya está ON → inicia BLE directamente
     * 2. Si BT OFF y hay MQTT → publica radio-request al controlador
     *    → El controlador ejecuta adb shell svc bluetooth enable
     *    → El controlador envía BT_READY cuando el radio está encendido
     * 3. Timeout 12s → fallback: pedir al usuario encender BT manualmente
     */
    private fun handlePrepareBt(command: Command) {
        if (bleManager.isBluetoothEnabled) {
            onLog("BT ya encendido → activando BLE directamente")
            executeBtActivation()
            return
        }

        // BT está apagado → delegar al controlador vía MQTT+ADB
        pendingBtCommand = command

        if (onPublishRadioRequest != null) {
            onLog("BT apagado → solicitando al controlador (MQTT → ADB)...")
            onPublishRadioRequest.invoke("enable_bt", "PREPARE_BT requiere BT encendido")

            // Timeout: si en 12s no recibimos BT_READY, fallback al usuario
            radioRequestTimeoutJob?.cancel()
            radioRequestTimeoutJob = handlerScope.launch {
                delay(12_000)
                if (pendingBtCommand != null && !bleManager.isBluetoothEnabled) {
                    withContext(Dispatchers.Main) {
                        onLog("⚠ Controlador no respondió en 12s — pidiendo al usuario...")
                        onRequestBluetoothEnable?.invoke()
                    }
                }
            }
        } else {
            // Sin canal MQTT → fallback directo al usuario
            requestUserBtEnable(command)
        }
    }

    /**
     * BT_READY: El controlador confirma que BT fue encendido vía ADB.
     * Si hay un PREPARE_BT pendiente, activa BLE ahora.
     */
    private fun handleBtReady(command: Command) {
        radioRequestTimeoutJob?.cancel()
        onLog("✓ Controlador: BT encendido vía ADB")

        // Esperar un momento para que el adapter refleje el cambio
        handlerScope.launch {
            delay(1500)
            withContext(Dispatchers.Main) {
                if (pendingBtCommand != null) {
                    onLog("Activando BLE tras confirmación del controlador...")
                    executeBtActivation()
                } else {
                    onLog("BT encendido (sin comando pendiente)")
                }
            }
        }
    }

    /**
     * BT_DISABLED: El controlador confirma que BT fue apagado vía ADB.
     */
    private fun handleBtDisabled(command: Command) {
        radioRequestTimeoutJob?.cancel()
        onLog("✓ Controlador: BT apagado vía ADB")
        onRadioChanged("idle")
    }

    /** Fallback: pide al usuario encender BT vía diálogo del sistema */
    private fun requestUserBtEnable(command: Command) {
        onLog("⚠ Sin MQTT — solicitando BT al usuario...")
        pendingBtCommand = command
        onRequestBluetoothEnable?.invoke()
    }

    /**
     * Ejecuta la activación real de BLE (scan + advertising).
     * Llamado después de confirmar que BT está habilitado.
     */
    fun executeBtActivation() {
        if (!bleManager.isBluetoothEnabled) {
            onLog("✗ Bluetooth sigue apagado — no se pudo activar BLE")
            pendingBtCommand = null
            return
        }

        bleManager.startAdvertising()
        bleManager.startScan()

        val phyInfo = if (bleManager.supportsCodedPhy) " (Coded PHY)" else " (estándar)"
        onRadioChanged("bluetooth")
        onLog("✓ BLE activado$phyInfo — scan + advertising iniciados")
        pendingBtCommand = null
    }

    /**
     * Reintenta el comando BT pendiente (llamado cuando el usuario habilita BT).
     */
    fun retryPendingBtCommand() {
        if (pendingBtCommand != null) {
            onLog("Reintentando PREPARE_BT tras habilitar Bluetooth...")
            executeBtActivation()
        }
    }

    /**
     * SWITCH_WIFI: Conecta a la red WiFi de datos indicada.
     * El controlador envía SSID y password en el comando.
     * El radio WiFi debe estar encendido (lo está para MQTT).
     */
    private fun handleSwitchWifi(command: Command) {
        val ssid = command.ssid
        val password = command.password

        if (ssid == null || password == null) {
            onLog("⚠ SWITCH_WIFI sin ssid/password")
            return
        }

        onLog("Conectando a WiFi datos: $ssid...")
        wifiController.connectToWifi(ssid, password) { success ->
            if (success) {
                onRadioChanged("wifi")
                onLog("✓ Conectado a WiFi de datos: $ssid")
            } else {
                onLog("✗ Error al conectar a WiFi: $ssid")
            }
        }
    }

    /**
     * RELEASE_RADIO: Libera radios.
     *
     * Flujo:
     * 1. Detiene operaciones BLE (scan, advertising, GATT)
     * 2. Si hay MQTT → pide al controlador apagar BT vía ADB
     * 3. Desconecta WiFi de datos (WiFi control/MQTT se mantiene)
     */
    private fun handleReleaseRadio(command: Command) {
        val wasBtActive = bleManager.bleState.value != "idle"
        val wasWifiActive = wifiController.dataWifiConnected.value

        // 1. Detener operaciones BLE siempre
        bleManager.stopAll()

        // 2. Pedir al controlador apagar BT vía ADB (si está encendido)
        if (bleManager.isBluetoothEnabled && onPublishRadioRequest != null) {
            onLog("→ Solicitando al controlador apagar BT vía ADB...")
            onPublishRadioRequest.invoke("disable_bt", "RELEASE_RADIO: liberando radios")
        }

        // 3. Desconectar WiFi datos
        wifiController.disconnectDataWifi()
        onRadioChanged("idle")

        val details = buildString {
            append("✓ Radios liberadas — ")
            if (wasBtActive) append("BLE detenido")
            if (wasWifiActive) {
                if (wasBtActive) append(", ")
                append("WiFi datos desconectado")
            }
            if (bleManager.isBluetoothEnabled) {
                append(" (esperando ADB para apagar radio BT)")
            }
            if (!wasBtActive && !wasWifiActive) append("ya estaban inactivas")
        }
        onLog(details)
    }

    /** Diagnóstico del nivel de control de radios disponible */
    fun runDiagnostic(): String = radioController.getDiagnostic()

    /** Libera recursos del scope de corrutinas */
    fun destroy() {
        radioRequestTimeoutJob?.cancel()
        handlerScope.cancel()
    }
}
