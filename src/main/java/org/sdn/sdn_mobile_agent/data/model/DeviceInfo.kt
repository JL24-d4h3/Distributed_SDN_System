package org.sdn.sdn_mobile_agent.data.model

/**
 * Información del dispositivo para auto-registro.
 * Se publica en: dispositivo/{MAC}/registro
 */
data class DeviceInfo(
    val mac: String = "",
    val name: String = "",
    val deviceType: String = "PHONE",
    val ipAddress: String = ""
)
