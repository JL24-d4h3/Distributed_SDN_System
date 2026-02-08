package org.sdn.sdn_mobile_agent.data.remote

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.sdn.sdn_mobile_agent.data.model.DeviceInfo
import org.sdn.sdn_mobile_agent.data.model.RequestSession
import org.sdn.sdn_mobile_agent.data.model.SessionRequest
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.util.concurrent.TimeUnit

/**
 * Interfaz Retrofit para la REST API del controlador SDN.
 * Base URL: http://{IP}:8081/
 */
interface SdnApiService {

    /** Iniciar una sesión de solicitud de contenido */
    @POST("sessions/request")
    suspend fun requestSession(@Body request: SessionRequest): RequestSession

    /** Confirmar que el contenido fue entregado */
    @POST("sessions/{sessionId}/delivered")
    suspend fun confirmDelivery(@Path("sessionId") sessionId: String): Any

    /** Obtener todos los dispositivos registrados */
    @GET("devices")
    suspend fun getDevices(): List<DeviceInfo>

    /** Obtener un dispositivo por MAC */
    @GET("devices/{mac}")
    suspend fun getDevice(@Path("mac") mac: String): DeviceInfo

    /** Obtener dispositivos en línea */
    @GET("devices/online")
    suspend fun getOnlineDevices(): List<DeviceInfo>

    /** Obtener sesión por ID */
    @GET("sessions/{sessionId}")
    suspend fun getSession(@Path("sessionId") sessionId: String): RequestSession

    /** Obtener sesiones activas */
    @GET("sessions/active")
    suspend fun getActiveSessions(): List<RequestSession>

    /** Obtener sesiones de un dispositivo */
    @GET("sessions/device/{mac}")
    suspend fun getDeviceSessions(@Path("mac") mac: String): List<RequestSession>
}

/**
 * Singleton que proporciona la instancia de Retrofit configurada.
 * Debe llamarse initialize() antes de usar getService().
 */
object SdnApi {
    private var retrofit: Retrofit? = null
    private var service: SdnApiService? = null

    /**
     * Inicializa Retrofit con la URL base del controlador.
     * @param baseUrl ej: "http://192.168.18.1:8081/"
     */
    fun initialize(baseUrl: String) {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()

        retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        service = retrofit?.create(SdnApiService::class.java)
    }

    fun getService(): SdnApiService {
        return service ?: throw IllegalStateException(
            "SdnApi no inicializado. Llama a initialize() primero."
        )
    }
}
