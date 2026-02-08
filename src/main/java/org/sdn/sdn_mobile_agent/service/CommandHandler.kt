package org.sdn.sdn_mobile_agent.service

import android.util.Log
import org.sdn.sdn_mobile_agent.data.model.Command

/**
 * Procesa los comandos recibidos del controlador SDN vía MQTT.
 *
 * Comandos soportados:
 * - PREPARE_BT: Activa Bluetooth (scan + advertising). Si hay Coded PHY, lo usa.
 * - SWITCH_WIFI: Conecta a la red WiFi de datos indicada (ssid/password).
 * - RELEASE_RADIO: Libera todas las radios (BT off, WiFi datos off).
 *
 * @param bleManager Gestor de BLE para activar/desactivar Bluetooth
 * @param wifiController Controlador WiFi para conectar/desconectar redes
 * @param onRadioChanged Callback cuando cambia la radio activa
 * @param onLog Callback para registrar eventos en el log de la UI
 */
class CommandHandler(
    private val bleManager: BleManager,
    private val wifiController: WifiController,
    private val onRadioChanged: (String) -> Unit,
    private val onLog: (String) -> Unit
) {

    companion object {
        private const val TAG = "CommandHandler"
    }

    /**
     * Procesa un comando recibido del controlador.
     * Despacha al handler específico según la acción.
     */
    fun handle(command: Command) {
        Log.i(TAG, "Procesando comando: ${command.action} (sesión: ${command.sessionId})")
        onLog("[${command.sessionId}] ${command.action}: ${command.reason ?: "sin razón"}")

        when (command.action) {
            "PREPARE_BT" -> handlePrepareBt(command)
            "SWITCH_WIFI" -> handleSwitchWifi(command)
            "RELEASE_RADIO" -> handleReleaseRadio(command)
            else -> {
                Log.w(TAG, "Acción desconocida: ${command.action}")
                onLog("Acción desconocida: ${command.action}")
            }
        }
    }

    /**
     * PREPARE_BT: Activa Bluetooth, inicia scan y advertising.
     * Si el hardware soporta BLE 5.0 Coded PHY, lo utiliza
     * para maximizar el alcance.
     */
    private fun handlePrepareBt(command: Command) {
        if (!bleManager.isBluetoothEnabled) {
            onLog("⚠ Bluetooth no está habilitado en el dispositivo")
            return
        }

        bleManager.startAdvertising()
        bleManager.startScan()

        val phyInfo = if (bleManager.supportsCodedPhy) " (Coded PHY)" else " (estándar)"
        onRadioChanged("bluetooth")
        onLog("✓ Bluetooth activado$phyInfo - scan + advertising")
    }

    /**
     * SWITCH_WIFI: Conecta a la red WiFi de datos indicada.
     * El controlador envía SSID y password en el comando.
     * Esto permite recibir contenido pesado por WiFi.
     */
    private fun handleSwitchWifi(command: Command) {
        val ssid = command.ssid
        val password = command.password

        if (ssid == null || password == null) {
            onLog("⚠ SWITCH_WIFI sin ssid/password")
            return
        }

        onLog("Conectando a WiFi: $ssid...")
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
     * RELEASE_RADIO: Libera todas las radios.
     * Apaga BT (scan + advertising + GATT) y desconecta WiFi de datos.
     * Mantiene WiFi de control (MQTT) intacta.
     */
    private fun handleReleaseRadio(command: Command) {
        bleManager.stopAll()
        wifiController.disconnectDataWifi()
        onRadioChanged("idle")
        onLog("✓ Radios liberadas (BT off, WiFi datos off)")
    }
}
