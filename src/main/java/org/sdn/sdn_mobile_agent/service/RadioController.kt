package org.sdn.sdn_mobile_agent.service

import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import org.sdn.sdn_mobile_agent.admin.AdminReceiver

/**
 * Controla el encendido/apagado de las radios BT y WiFi.
 *
 * Estrategia de multi-nivel (intenta cada método en orden):
 *   1. API directa (BluetoothAdapter.enable/disable) — funciona en muchos dispositivos
 *   2. DevicePolicyManager — si la app es Device Owner
 *   3. Shell command (svc bluetooth/wifi) — requiere root o ADB shell
 *
 * Para que los métodos 1-2 funcionen de forma confiable en Android 13+,
 * la app debe ser Device Owner:
 *   adb shell dpm set-device-owner org.sdn.sdn_mobile_agent/.admin.AdminReceiver
 *
 * Si ningún método funciona desde la app, el controlador puede ejecutar
 * los comandos ADB remotamente (ver radio_control.sh).
 */
class RadioController(private val context: Context) {

    companion object {
        private const val TAG = "RadioController"
    }

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private val devicePolicyManager =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    private val adminComponent = ComponentName(context, AdminReceiver::class.java)

    /** Verifica si la app es Device Owner (tiene privilegios elevados) */
    val isDeviceOwner: Boolean
        get() = devicePolicyManager.isDeviceOwnerApp(context.packageName)

    /** Verifica si la app es Device Admin (puede tener algunos privilegios) */
    val isDeviceAdmin: Boolean
        get() = devicePolicyManager.isAdminActive(adminComponent)

    // ─── Control de Bluetooth ───────────────────────────────────

    /**
     * Enciende el radio Bluetooth.
     * @return true si tuvo éxito, false si falló (requiere intervención del usuario)
     */
    @SuppressLint("MissingPermission")
    fun enableBluetooth(): Boolean {
        if (bluetoothAdapter == null) {
            Log.e(TAG, "BluetoothAdapter no disponible")
            return false
        }
        if (bluetoothAdapter.isEnabled) {
            Log.d(TAG, "BT ya está encendido")
            return true
        }

        // Intento 1: API directa (deprecated pero funcional en muchos dispositivos)
        val apiResult = tryEnableBtViaApi()
        if (apiResult) return true

        // Intento 2: Shell command
        val shellResult = executeShell("svc bluetooth enable")
        if (shellResult) {
            Log.i(TAG, "✓ BT encendido vía shell command")
            return true
        }

        Log.w(TAG, "No se pudo encender BT programáticamente")
        return false
    }

    /**
     * Apaga el radio Bluetooth.
     * @return true si tuvo éxito
     */
    @SuppressLint("MissingPermission")
    fun disableBluetooth(): Boolean {
        if (bluetoothAdapter == null) return false
        if (!bluetoothAdapter.isEnabled) {
            Log.d(TAG, "BT ya está apagado")
            return true
        }

        val apiResult = tryDisableBtViaApi()
        if (apiResult) return true

        val shellResult = executeShell("svc bluetooth disable")
        if (shellResult) {
            Log.i(TAG, "✓ BT apagado vía shell command")
            return true
        }

        Log.w(TAG, "No se pudo apagar BT programáticamente")
        return false
    }

    @SuppressLint("MissingPermission")
    private fun tryEnableBtViaApi(): Boolean {
        return try {
            @Suppress("DEPRECATION")
            val result = bluetoothAdapter?.enable() ?: false
            if (result) Log.i(TAG, "✓ BT encendido vía BluetoothAdapter.enable()")
            result
        } catch (e: SecurityException) {
            Log.d(TAG, "BluetoothAdapter.enable() → SecurityException: ${e.message}")
            false
        } catch (e: Exception) {
            Log.d(TAG, "BluetoothAdapter.enable() → Exception: ${e.message}")
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun tryDisableBtViaApi(): Boolean {
        return try {
            @Suppress("DEPRECATION")
            val result = bluetoothAdapter?.disable() ?: false
            if (result) Log.i(TAG, "✓ BT apagado vía BluetoothAdapter.disable()")
            result
        } catch (e: SecurityException) {
            Log.d(TAG, "BluetoothAdapter.disable() → SecurityException: ${e.message}")
            false
        } catch (e: Exception) {
            Log.d(TAG, "BluetoothAdapter.disable() → Exception: ${e.message}")
            false
        }
    }

    // ─── Control de WiFi ────────────────────────────────────────

    /**
     * Enciende el radio WiFi.
     * NOTA: En Android 10+ setWifiEnabled() no funciona.
     * Solo shell commands (root) o ADB remoto pueden hacerlo.
     *
     * ⚠ CUIDADO: Si apagas WiFi, pierdes la conexión MQTT (canal de control).
     * Solo apaga WiFi si tienes un canal alternativo (BLE) para re-encenderlo.
     */
    fun enableWifi(): Boolean {
        if (wifiManager.isWifiEnabled) {
            Log.d(TAG, "WiFi ya está encendido")
            return true
        }

        // Intento 1: API legacy (funciona solo en Android < 10)
        @Suppress("DEPRECATION")
        val apiResult = try {
            wifiManager.setWifiEnabled(true)
        } catch (e: Exception) {
            false
        }
        if (apiResult) {
            Log.i(TAG, "✓ WiFi encendido vía WifiManager.setWifiEnabled()")
            return true
        }

        // Intento 2: Shell command (requiere root)
        val shellResult = executeShell("svc wifi enable")
        if (shellResult) {
            Log.i(TAG, "✓ WiFi encendido vía shell command")
            return true
        }

        Log.w(TAG, "No se pudo encender WiFi programáticamente")
        return false
    }

    /**
     * Apaga el radio WiFi.
     * ⚠ CUIDADO: Esto cortará la conexión MQTT.
     */
    fun disableWifi(): Boolean {
        if (!wifiManager.isWifiEnabled) {
            Log.d(TAG, "WiFi ya está apagado")
            return true
        }

        @Suppress("DEPRECATION")
        val apiResult = try {
            wifiManager.setWifiEnabled(false)
        } catch (e: Exception) {
            false
        }
        if (apiResult) {
            Log.i(TAG, "✓ WiFi apagado vía WifiManager.setWifiEnabled()")
            return true
        }

        val shellResult = executeShell("svc wifi disable")
        if (shellResult) {
            Log.i(TAG, "✓ WiFi apagado vía shell command")
            return true
        }

        Log.w(TAG, "No se pudo apagar WiFi programáticamente")
        return false
    }

    // ─── Shell Commands ─────────────────────────────────────────

    /**
     * Ejecuta un comando shell.
     * Funciona si:
     * - El dispositivo tiene root (su disponible)
     * - Se está ejecutando desde ADB shell
     * - La app tiene privilegios elevados
     */
    private fun executeShell(command: String): Boolean {
        // Intentar sin root primero
        val directResult = runCommand(arrayOf("sh", "-c", command))
        if (directResult) return true

        // Intentar con root
        val rootResult = runCommand(arrayOf("su", "-c", command))
        if (rootResult) return true

        return false
    }

    private fun runCommand(cmdArray: Array<String>): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(cmdArray)
            val exitCode = process.waitFor()
            val stderr = process.errorStream.bufferedReader().readText()
            if (exitCode == 0) {
                Log.i(TAG, "Shell '${cmdArray.joinToString(" ")}' → OK")
                true
            } else {
                Log.d(TAG, "Shell '${cmdArray.joinToString(" ")}' → exit=$exitCode err=$stderr")
                false
            }
        } catch (e: Exception) {
            Log.d(TAG, "Shell failed: ${cmdArray.joinToString(" ")} → ${e.message}")
            false
        }
    }

    /**
     * Devuelve un diagnóstico del nivel de control disponible.
     */
    fun getDiagnostic(): String {
        return buildString {
            appendLine("═══ Diagnóstico RadioController ═══")
            appendLine("Device Owner: $isDeviceOwner")
            appendLine("Device Admin: $isDeviceAdmin")
            appendLine("BT Adapter: ${bluetoothAdapter != null}")
            appendLine("BT Enabled: ${bluetoothAdapter?.isEnabled}")
            appendLine("WiFi Enabled: ${wifiManager.isWifiEnabled}")
            appendLine("Root disponible: ${isRootAvailable()}")
        }
    }

    private fun isRootAvailable(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            process.waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }
}
