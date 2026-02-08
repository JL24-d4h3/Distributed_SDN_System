package org.sdn.sdn_mobile_agent.data.model

/**
 * Solicitud de sesión enviada al controlador vía REST.
 * POST /sessions/request
 */
data class SessionRequest(
    val originMac: String = "",
    val accessNodeMac: String? = null,
    val query: String = "",
    val expectedContentType: String = "text"
)

/**
 * Respuesta del controlador al crear una sesión.
 */
data class RequestSession(
    val sessionId: String = "",
    val originMac: String? = null,
    val status: String? = null,
    val query: String? = null
)
