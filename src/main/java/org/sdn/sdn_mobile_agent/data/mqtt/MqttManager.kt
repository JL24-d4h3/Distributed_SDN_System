package org.sdn.sdn_mobile_agent.data.mqtt

import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.sdn.sdn_mobile_agent.data.model.Command
import org.sdn.sdn_mobile_agent.data.model.DeviceInfo
import org.sdn.sdn_mobile_agent.data.model.Metrics

/**
 * Gestiona la conexión MQTT con el broker Mosquitto.
 *
 * Responsabilidades:
 * - Conectar/desconectar al broker
 * - Suscribirse a dispositivo/{MAC}/comando (QoS 1)
 * - Publicar telemetría en dispositivo/{MAC}/metrics (QoS 0)
 * - Publicar auto-registro en dispositivo/{MAC}/registro (QoS 1)
 * - Reconexión automática
 */
class MqttManager {

    companion object {
        private const val TAG = "MqttManager"
    }

    private var client: MqttAsyncClient? = null
    private val gson = Gson()
    private var deviceMac: String = ""

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    private val _isConnecting = MutableStateFlow(false)
    val isConnecting: StateFlow<Boolean> = _isConnecting

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    /** Callback invocado cuando llega un comando del controlador */
    var onCommandReceived: ((Command) -> Unit)? = null

    /** Callback invocado cuando cambia el estado de conexión */
    var onConnectionChanged: ((Boolean) -> Unit)? = null

    /**
     * Conecta al broker MQTT.
     * @param brokerUrl URL del broker, ej: tcp://192.168.18.1:1883
     * @param mac MAC del dispositivo para los tópicos
     */
    fun connect(brokerUrl: String, mac: String) {
        // Evitar conexiones duplicadas
        if (_isConnecting.value || _isConnected.value) {
            Log.w(TAG, "Ya conectado o conectando, ignorando...")
            return
        }

        // Limpiar cliente anterior si existe
        try {
            client?.let {
                if (it.isConnected) it.disconnect()
                it.close()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error cerrando cliente anterior", e)
        }
        client = null

        deviceMac = mac
        _isConnecting.value = true
        _lastError.value = null
        val clientId = "android_${mac.replace(":", "")}"

        try {
            client = MqttAsyncClient(brokerUrl, clientId, MemoryPersistence())

            client?.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    Log.i(TAG, "Conectado a $serverURI (reconnect=$reconnect)")
                    _isConnecting.value = false
                    _isConnected.value = true
                    _lastError.value = null
                    onConnectionChanged?.invoke(true)
                    subscribeToCommands()
                }

                override fun connectionLost(cause: Throwable?) {
                    Log.w(TAG, "Conexión perdida", cause)
                    _isConnecting.value = false
                    _isConnected.value = false
                    onConnectionChanged?.invoke(false)
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    message?.let {
                        try {
                            val json = String(it.payload)
                            Log.d(TAG, "Mensaje en $topic: $json")
                            val command = gson.fromJson(json, Command::class.java)
                            onCommandReceived?.invoke(command)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error parseando comando", e)
                        }
                    }
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })

            val options = MqttConnectOptions().apply {
                isCleanSession = true
                connectionTimeout = 10
                keepAliveInterval = 60
                isAutomaticReconnect = true
            }

            client?.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    Log.i(TAG, "Conexión exitosa a $brokerUrl")
                    // connectComplete del callback se encargará del estado
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    val errorMsg = exception?.message ?: "Error desconocido"
                    Log.e(TAG, "Fallo al conectar a $brokerUrl: $errorMsg", exception)
                    _isConnecting.value = false
                    _isConnected.value = false
                    _lastError.value = "No se pudo conectar: $errorMsg"
                    onConnectionChanged?.invoke(false)
                }
            })
            Log.i(TAG, "Intentando conectar a $brokerUrl...")
        } catch (e: Exception) {
            Log.e(TAG, "Error al conectar", e)
            _isConnecting.value = false
            _isConnected.value = false
            _lastError.value = "Error: ${e.message}"
        }
    }

    /**
     * Se suscribe al tópico de comandos del controlador.
     */
    private fun subscribeToCommands() {
        val topic = "dispositivo/$deviceMac/comando"
        try {
            client?.subscribe(topic, 1)
            Log.i(TAG, "Suscrito a $topic")
        } catch (e: Exception) {
            Log.e(TAG, "Error suscribiéndose a $topic", e)
        }
    }

    /**
     * Publica métricas de telemetría (QoS 0, cada 30s).
     */
    fun publishMetrics(metrics: Metrics) {
        if (client?.isConnected != true) return
        try {
            val topic = "dispositivo/$deviceMac/metrics"
            val json = gson.toJson(metrics)
            val message = MqttMessage(json.toByteArray()).apply { qos = 0 }
            client?.publish(topic, message)
            Log.d(TAG, "Métricas publicadas")
        } catch (e: Exception) {
            Log.e(TAG, "Error publicando métricas", e)
        }
    }

    /**
     * Publica el registro del dispositivo (QoS 1, una vez al conectar).
     */
    fun publishRegistration(deviceInfo: DeviceInfo) {
        if (client?.isConnected != true) return
        try {
            val topic = "dispositivo/$deviceMac/registro"
            val json = gson.toJson(deviceInfo)
            val message = MqttMessage(json.toByteArray()).apply { qos = 1 }
            client?.publish(topic, message)
            Log.i(TAG, "Registro publicado en $topic")
        } catch (e: Exception) {
            Log.e(TAG, "Error publicando registro", e)
        }
    }

    /**
     * Desconecta del broker MQTT.
     */
    fun disconnect() {
        try {
            client?.disconnect()
            client?.close()
            client = null
            _isConnected.value = false
            onConnectionChanged?.invoke(false)
            Log.i(TAG, "Desconectado del broker")
        } catch (e: Exception) {
            Log.e(TAG, "Error al desconectar", e)
        }
    }

    fun isClientConnected(): Boolean = client?.isConnected == true
}
