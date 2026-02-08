package org.sdn.sdn_mobile_agent.service

import android.content.Context
import android.net.*
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Controla las conexiones WiFi del dispositivo.
 *
 * Funcionalidades:
 * - Conectar a red WiFi de datos indicada por el controlador (SWITCH_WIFI)
 * - Desconectar WiFi de datos (RELEASE_RADIO)
 * - Obtener RSSI e IP actual
 * - Mantener WiFi de control (para MQTT) separada de WiFi de datos
 *
 * Usa WifiNetworkSpecifier en Android 10+ (API 29)
 * y WifiConfiguration legacy en versiones anteriores.
 */
class WifiController(private val context: Context) {

    companion object {
        private const val TAG = "WifiController"
    }

    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** Estado actual de la conexión WiFi de datos */
    private val _wifiState = MutableStateFlow("disconnected")
    val wifiState: StateFlow<String> = _wifiState

    /** Indica si hay una conexión WiFi de datos activa */
    private val _dataWifiConnected = MutableStateFlow(false)
    val dataWifiConnected: StateFlow<Boolean> = _dataWifiConnected

    /**
     * Conecta a una red WiFi específica para transferencia de datos.
     * Usa WifiNetworkSpecifier (API 29+) o WifiConfiguration (legacy).
     *
     * @param ssid Nombre de la red WiFi
     * @param password Contraseña WPA2
     * @param onResult Callback con resultado (true = conectado)
     */
    fun connectToWifi(ssid: String, password: String, onResult: (Boolean) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ usa WifiNetworkSpecifier
            val specifier = WifiNetworkSpecifier.Builder()
                .setSsid(ssid)
                .setWpa2Passphrase(password)
                .build()

            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .setNetworkSpecifier(specifier)
                .build()

            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.i(TAG, "WiFi datos conectado: $ssid")
                    connectivityManager.bindProcessToNetwork(network)
                    _wifiState.value = "data_wifi"
                    _dataWifiConnected.value = true
                    onResult(true)
                }

                override fun onUnavailable() {
                    Log.w(TAG, "WiFi datos no disponible: $ssid")
                    _wifiState.value = "unavailable"
                    _dataWifiConnected.value = false
                    onResult(false)
                }

                override fun onLost(network: Network) {
                    Log.w(TAG, "WiFi datos perdido")
                    connectivityManager.bindProcessToNetwork(null)
                    _dataWifiConnected.value = false
                    _wifiState.value = "disconnected"
                }
            }

            try {
                connectivityManager.requestNetwork(request, networkCallback!!)
            } catch (e: Exception) {
                Log.e(TAG, "Error solicitando red", e)
                onResult(false)
            }
        } else {
            // Android < 10: API legacy
            @Suppress("DEPRECATION")
            val config = android.net.wifi.WifiConfiguration().apply {
                SSID = "\"$ssid\""
                preSharedKey = "\"$password\""
            }
            @Suppress("DEPRECATION")
            val netId = wifiManager.addNetwork(config)
            @Suppress("DEPRECATION")
            val success = wifiManager.enableNetwork(netId, true)
            if (success) {
                _wifiState.value = "data_wifi"
                _dataWifiConnected.value = true
            }
            onResult(success)
        }
    }

    /**
     * Desconecta la red WiFi de datos.
     * Libera el binding de red y desregistra el callback.
     */
    fun disconnectDataWifi() {
        networkCallback?.let {
            try {
                connectivityManager.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error desregistrando callback", e)
            }
            networkCallback = null
        }
        connectivityManager.bindProcessToNetwork(null)
        _dataWifiConnected.value = false
        _wifiState.value = "disconnected"
        Log.i(TAG, "WiFi datos desconectado")
    }

    /**
     * Obtiene el RSSI de la conexión WiFi actual.
     * @return RSSI en dBm, -100 si no hay conexión
     */
    @Suppress("DEPRECATION")
    fun getCurrentRssi(): Int {
        return try {
            wifiManager.connectionInfo.rssi
        } catch (e: Exception) {
            -100
        }
    }

    /**
     * Obtiene la dirección IP del dispositivo en la red WiFi actual.
     * @return IP en formato "x.x.x.x" o "0.0.0.0" si no hay conexión
     */
    @Suppress("DEPRECATION")
    fun getCurrentIp(): String {
        return try {
            val ip = wifiManager.connectionInfo.ipAddress
            if (ip == 0) return "0.0.0.0"
            String.format(
                "%d.%d.%d.%d",
                ip and 0xff,
                ip shr 8 and 0xff,
                ip shr 16 and 0xff,
                ip shr 24 and 0xff
            )
        } catch (e: Exception) {
            "0.0.0.0"
        }
    }

    /**
     * Verifica si hay una conexión WiFi activa.
     */
    fun isWifiConnected(): Boolean {
        val network = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(network)
        return capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }
}
