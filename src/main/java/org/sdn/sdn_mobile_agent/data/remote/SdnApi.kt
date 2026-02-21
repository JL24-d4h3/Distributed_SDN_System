package org.sdn.sdn_mobile_agent.data.remote

import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import org.sdn.sdn_mobile_agent.data.model.*
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
 * Interfaz Retrofit para la CDN (Python sdn_cdn_server.py).
 * Base URL: http://{IP}:8080/
 *
 * Endpoints de contenido: búsqueda, metadatos, streaming.
 */
interface CdnApiService {

    /** Buscar contenido por query */
    @GET("api/search")
    suspend fun searchContent(@Query("q") query: String): ContentSearchResponse

    /** Catálogo completo */
    @GET("api/catalog")
    suspend fun getCatalog(): ContentSearchResponse

    /** Metadatos de un contenido */
    @GET("api/content/{id}")
    suspend fun getContentMetadata(@Path("id") contentId: String): ContentItem

    /** Solicitar entrega (auto-WiFi si contenido grande) */
    @POST("api/content/{id}/request")
    suspend fun requestContent(@Path("id") contentId: String): ContentDeliveryResponse

    /** Streaming/descarga del archivo (retorna raw bytes) */
    @GET("api/content/{id}/stream")
    @Streaming
    suspend fun streamContent(@Path("id") contentId: String): ResponseBody
}

/**
 * Respuesta al solicitar entrega de contenido (POST /api/content/{id}/request).
 */
data class ContentDeliveryResponse(
    val id: String = "",
    val title: String = "",
    val contentType: String = "",
    val sizeBytes: Long = 0,
    val streamUrl: String = "",
    val wifiActivated: Boolean = false,
    val transport: String = ""
)

/**
 * Singleton que proporciona las instancias de Retrofit configuradas.
 * Debe llamarse initialize() antes de usar getService().
 */
object SdnApi {
    private var retrofit: Retrofit? = null
    private var service: SdnApiService? = null

    private var cdnRetrofit: Retrofit? = null
    private var cdnService: CdnApiService? = null

    private val client: OkHttpClient by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)  // Mayor para descargas
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Inicializa Retrofit con la URL base del controlador.
     * @param baseUrl ej: "http://192.168.18.1:8081/"
     */
    fun initialize(baseUrl: String) {
        retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        service = retrofit?.create(SdnApiService::class.java)
    }

    /**
     * Inicializa el cliente de la CDN (Python server).
     * @param cdnBaseUrl ej: "http://192.168.18.1:8080/"
     */
    fun initializeCdn(cdnBaseUrl: String) {
        cdnRetrofit = Retrofit.Builder()
            .baseUrl(cdnBaseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        cdnService = cdnRetrofit?.create(CdnApiService::class.java)
    }

    fun getService(): SdnApiService {
        return service ?: throw IllegalStateException(
            "SdnApi no inicializado. Llama a initialize() primero."
        )
    }

    fun getCdnService(): CdnApiService {
        return cdnService ?: throw IllegalStateException(
            "CDN API no inicializada. Llama a initializeCdn() primero."
        )
    }

    fun isCdnInitialized(): Boolean = cdnService != null
}
