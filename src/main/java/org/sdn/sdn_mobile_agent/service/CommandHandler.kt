package org.sdn.sdn_mobile_agent.service

import android.util.Log
import kotlinx.coroutines.*
import org.sdn.sdn_mobile_agent.data.model.Command

/**
 * Procesa los comandos recibidos del controlador SDN.
 *
 * Arquitectura SDN (plano de control BLE):
 * ═══════════════════════════════════════════
 * BLE = Plano de Control (siempre ON, ~0.3 mA)
 *   - GATT Client conecta al CDN (laptop GATT Server)
 *   - App escribe requests, CDN notifica control/responses
 *
 * WiFi = Plano de Datos (ON/OFF bajo demanda)
 *   - CDN enciende WiFi vía ADB cuando hay datos pesados
 *   - Se apaga cuando termina → ahorro energético real
 *
 * Transport: BLE GATT Client→CDN (primario) o MQTT (fallback si WiFi ON)
 *
 * Flujo radio-request (BLE → ADB):
 *   App → GATT Write request → CDN (laptop) → ADB toggle
 *   CDN → GATT Notify control → WIFI_READY / WIFI_DISABLED
 *
 * Comandos soportados:
 * - PREPARE_BT: Si BT ON → activa BLE. Si BT OFF → pide al controlador.
 * - BT_READY: Controlador confirma BT encendido vía ADB.
 * - BT_DISABLED: Controlador confirma BT apagado vía ADB.
 * - ENABLE_WIFI: Solicita encender WiFi para transferencia de datos.
 * - WIFI_READY: Controlador confirma WiFi encendido vía ADB.
 * - DISABLE_WIFI: Solicita apagar WiFi (datos terminados).
 * - WIFI_DISABLED: Controlador confirma WiFi apagado vía ADB.
 * - SWITCH_WIFI: Conecta a una red WiFi específica (datos).
 * - RELEASE_RADIO: Apaga WiFi datos + detiene BLE scan/adv.
 */
class CommandHandler(
    private val radioController: RadioController,
    private val bleManager: BleManager,
    private val wifiController: WifiController,
    private val onRadioChanged: (String) -> Unit,
    private val onLog: (String) -> Unit,
    private val onRequestBluetoothEnable: (() -> Unit)? = null,
    /** Publica radio-request: primero BLE GATT, fallback MQTT */
    private val onPublishRadioRequest: ((action: String, reason: String) -> Unit)? = null
) {

    companion object {
        private const val TAG = "CommandHandler"
    }

    private val handlerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Último comando pendiente de ejecutar cuando BT se habilite */
    private var pendingBtCommand: Command? = null

    /** Comando WiFi pendiente */
    private var pendingWifiCommand: Command? = null

    /** Job del timeout para el radio request */
    private var radioRequestTimeoutJob: Job? = null

    /**
     * Procesa un comando recibido del controlador.
     * Origen: BLE GATT Server (cmd_write) o MQTT (fallback).
     */
    fun handle(command: Command) {
        Log.i(TAG, "Procesando comando: ${command.action} (sesión: ${command.sessionId})")
        onLog("[${command.sessionId}] ${command.action}: ${command.reason ?: "sin razón"}")

        when (command.action) {
            // ── BT/BLE ──
            "PREPARE_BT"     -> handlePrepareBt(command)
            "BT_READY"       -> handleBtReady(command)
            "BT_DISABLED"    -> handleBtDisabled(command)
            // ── WiFi (plano de datos) ──
            "ENABLE_WIFI"    -> handleEnableWifi(command)
            "WIFI_READY"     -> handleWifiReady(command)
            "DISABLE_WIFI"   -> handleDisableWifi(command)
            "WIFI_DISABLED"  -> handleWifiDisabled(command)
            "SWITCH_WIFI"    -> handleSwitchWifi(command)
            // ── General ──
            "RELEASE_RADIO"  -> handleReleaseRadio(command)
            else -> {
                Log.w(TAG, "Acción desconocida: ${command.action}")
                onLog("Acción desconocida: ${command.action}")
            }
        }
    }

    // ════════════════════════════════════════════════════════════
    // ═══ BLUETOOTH / BLE ═══════════════════════════════════════
    // ════════════════════════════════════════════════════════════

    private fun handlePrepareBt(command: Command) {
        if (bleManager.isBluetoothEnabled) {
            onLog("BT ya encendido → activando BLE directamente")
            executeBtActivation()
            return
        }

        pendingBtCommand = command
        publishRadioRequest("enable_bt", "PREPARE_BT requiere BT encendido")
    }

    private fun handleBtReady(command: Command) {
        radioRequestTimeoutJob?.cancel()
        onLog("✓ Controlador: BT encendido vía ADB")

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

    private fun handleBtDisabled(command: Command) {
        radioRequestTimeoutJob?.cancel()
        onLog("✓ Controlador: BT apagado vía ADB")
        onRadioChanged("idle")
    }

    fun executeBtActivation() {
        if (!bleManager.isBluetoothEnabled) {
            onLog("✗ Bluetooth sigue apagado — no se pudo activar BLE")
            pendingBtCommand = null
            return
        }

        // Iniciar scan para encontrar CDN y conectar
        bleManager.startScan()
        bleManager.startAdvertising()

        val phyInfo = if (bleManager.supportsCodedPhy) " (Coded PHY)" else " (estándar)"
        onRadioChanged("bluetooth")
        onLog("✓ BLE activado$phyInfo — scan CDN + advertising")
        pendingBtCommand = null
    }

    fun retryPendingBtCommand() {
        if (pendingBtCommand != null) {
            onLog("Reintentando PREPARE_BT tras habilitar Bluetooth...")
            executeBtActivation()
        }
    }

    // ════════════════════════════════════════════════════════════
    // ═══ WIFI — Plano de Datos ════════════════════════════════
    // ════════════════════════════════════════════════════════════

    /**
     * ENABLE_WIFI: El controlador pide encender WiFi para datos.
     * Se envía radio-request vía BLE GATT → Python bridge → ADB.
     */
    private fun handleEnableWifi(command: Command) {
        if (wifiController.isWifiClientEnabled) {
            onLog("WiFi ya encendido → listo para datos")
            onRadioChanged("wifi")
            return
        }

        pendingWifiCommand = command
        publishRadioRequest("enable_wifi", command.reason ?: "Datos pesados requieren WiFi")
    }

    /**
     * WIFI_READY: Controlador confirma WiFi encendido vía ADB.
     * Si hay SWITCH_WIFI pendiente (ssid/password), conectar.
     */
    private fun handleWifiReady(command: Command) {
        radioRequestTimeoutJob?.cancel()
        onLog("✓ Controlador: WiFi encendido vía ADB")
        onRadioChanged("wifi")

        // Si el comando original tenía ssid/password, conectar automáticamente
        val pending = pendingWifiCommand
        if (pending?.ssid != null && pending.password != null) {
            handlerScope.launch {
                delay(2000) // Esperar que WiFi se estabilice
                withContext(Dispatchers.Main) {
                    onLog("Conectando a WiFi datos: ${pending.ssid}...")
                    wifiController.connectToWifi(pending.ssid, pending.password) { success ->
                        if (success) {
                            onLog("✓ Conectado a WiFi de datos: ${pending.ssid}")
                        } else {
                            onLog("✗ Error al conectar a WiFi: ${pending.ssid}")
                        }
                    }
                }
            }
        }
        pendingWifiCommand = null
    }

    /**
     * DISABLE_WIFI: El controlador pide apagar WiFi (datos terminados).
     * Primero desconecta WiFi datos, luego pide ADB para apagar radio.
     */
    private fun handleDisableWifi(command: Command) {
        wifiController.disconnectDataWifi()
        publishRadioRequest("disable_wifi", command.reason ?: "Datos terminados, apagar WiFi")
    }

    /**
     * WIFI_DISABLED: Controlador confirma WiFi apagado vía ADB.
     */
    private fun handleWifiDisabled(command: Command) {
        radioRequestTimeoutJob?.cancel()
        onLog("✓ Controlador: WiFi apagado vía ADB → ahorro energético")
        // BLE sigue como plano de control
        if (bleManager.isBluetoothEnabled) {
            onRadioChanged("bluetooth")
        } else {
            onRadioChanged("idle")
        }
    }

    /**
     * SWITCH_WIFI: Conecta a una red WiFi específica.
     * Si WiFi ya está ON, conecta directamente.
     * Si WiFi está OFF, pide encender primero.
     */
    private fun handleSwitchWifi(command: Command) {
        val ssid = command.ssid
        val password = command.password

        if (ssid == null || password == null) {
            onLog("⚠ SWITCH_WIFI sin ssid/password")
            return
        }

        if (wifiController.isWifiClientEnabled) {
            // WiFi ya encendido → conectar directamente
            onLog("Conectando a WiFi datos: $ssid...")
            wifiController.connectToWifi(ssid, password) { success ->
                if (success) {
                    onRadioChanged("wifi")
                    onLog("✓ Conectado a WiFi de datos: $ssid")
                } else {
                    onLog("✗ Error al conectar a WiFi: $ssid")
                }
            }
        } else {
            // WiFi apagado → guardar y pedir encender primero
            onLog("WiFi apagado → solicitando encender para datos...")
            pendingWifiCommand = command
            publishRadioRequest("enable_wifi", "SWITCH_WIFI requiere WiFi encendido para $ssid")
        }
    }

    // ════════════════════════════════════════════════════════════
    // ═══ RELEASE ═══════════════════════════════════════════════
    // ════════════════════════════════════════════════════════════

    /**
     * RELEASE_RADIO: Libera plano de datos (WiFi).
     * BLE (plano de control) se mantiene activo.
     */
    private fun handleReleaseRadio(command: Command) {
        val wasWifiActive = wifiController.isWifiClientEnabled

        // 1. Desconectar WiFi datos
        wifiController.disconnectDataWifi()

        // 2. Pedir al controlador apagar WiFi vía ADB
        if (wasWifiActive) {
            publishRadioRequest("disable_wifi", "RELEASE_RADIO: liberando plano de datos")
        }

        // BLE (plano de control) se MANTIENE activo
        if (bleManager.isBluetoothEnabled) {
            onRadioChanged("bluetooth")
        } else {
            onRadioChanged("idle")
        }

        val details = buildString {
            append("✓ Plano datos liberado — ")
            if (wasWifiActive) {
                append("WiFi desconectado (esperando ADB para apagar radio)")
            } else {
                append("WiFi ya estaba apagado")
            }
            append(" | BLE control: activo")
        }
        onLog(details)
    }

    // ════════════════════════════════════════════════════════════
    // ═══ Utilidades ═══════════════════════════════════════════
    // ════════════════════════════════════════════════════════════

    /**
     * Publica un radio-request.
     * Primero intenta vía BLE GATT (plano de control nativo).
     * Si no hay controlador BLE conectado, usa MQTT como fallback.
     */
    private fun publishRadioRequest(action: String, reason: String) {
        if (bleManager.isCdnConnected) {
            // Usar BLE GATT Client → CDN (plano de control nativo)
            val json = """{"action":"$action","reason":"$reason"}"""
            bleManager.sendRequestToCdn(json)
            onLog("→ Radio-request vía BLE → CDN: $action")
        } else if (onPublishRadioRequest != null) {
            // Fallback: MQTT (si WiFi está activo)
            onPublishRadioRequest.invoke(action, reason)
            onLog("→ Radio-request vía MQTT (fallback): $action")
        } else {
            onLog("⚠ Sin canal para radio-request ($action) — sin BLE ni MQTT")
            // Fallback: pedir al usuario
            if (action == "enable_bt") {
                onRequestBluetoothEnable?.invoke()
            }
        }

        // Timeout
        radioRequestTimeoutJob?.cancel()
        radioRequestTimeoutJob = handlerScope.launch {
            delay(15_000)
            withContext(Dispatchers.Main) {
                if (action.startsWith("enable_bt") && pendingBtCommand != null && !bleManager.isBluetoothEnabled) {
                    onLog("⚠ Controlador no respondió en 15s — pidiendo BT al usuario...")
                    onRequestBluetoothEnable?.invoke()
                } else if (action.startsWith("enable_wifi") && pendingWifiCommand != null) {
                    onLog("⚠ Controlador no respondió en 15s para WiFi")
                }
            }
        }
    }

    /** Diagnóstico del nivel de control de radios disponible */
    fun runDiagnostic(): String = radioController.getDiagnostic()

    /** Libera recursos del scope de corrutinas */
    fun destroy() {
        radioRequestTimeoutJob?.cancel()
        handlerScope.cancel()
    }
}
