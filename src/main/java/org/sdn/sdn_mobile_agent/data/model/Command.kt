package org.sdn.sdn_mobile_agent.data.model

/**
 * Comando recibido del controlador SDN vía MQTT.
 * Tópico: dispositivo/{MAC}/comando
 */
data class Command(
    val sessionId: String = "",
    val action: String = "",
    val ssid: String? = null,
    val password: String? = null,
    val reason: String? = null
)
