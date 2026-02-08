package org.sdn.sdn_mobile_agent.data.model

/**
 * Telemetría periódica del dispositivo.
 * Se publica en: dispositivo/{MAC}/metrics cada 30 segundos.
 */
data class Metrics(
    val mac: String = "",
    val rssi: Int = 0,
    val technology: String = "wifi",
    val batteryLevel: Int = 0,
    val ipAddress: String = ""
)
