package org.sdn.sdn_mobile_agent.data.model

/**
 * Modelos para el catálogo de contenido de la CDN.
 *
 * Flujo:
 *   Búsqueda → GET /api/search?q=xxx → ContentSearchResponse
 *   Selección → evaluación de tamaño → WiFi ON si > 10MB
 *   Descarga → GET /api/content/{id}/stream → archivo binario
 *   Reproducción → Intent.ACTION_VIEW desde archivo local
 */

/** Elemento individual del catálogo de contenido */
data class ContentItem(
    val id: String = "",
    val title: String = "",
    val description: String = "",
    /** MIME type: "video/mp4", "application/pdf", "text/plain", "image/jpeg" */
    val contentType: String = "text/plain",
    /** Tamaño en bytes */
    val sizeBytes: Long = 0,
    /** Tags para búsqueda semántica */
    val tags: List<String> = emptyList(),
    /** URL relativa del thumbnail (opcional) */
    val thumbnailUrl: String? = null,
    /** Nombre del archivo original */
    val filename: String = ""
) {
    /** Tamaño en formato legible: "45.2 MB", "512 KB", "1.2 GB" */
    val humanSize: String
        get() = when {
            sizeBytes >= 1_073_741_824 -> String.format("%.1f GB", sizeBytes / 1_073_741_824.0)
            sizeBytes >= 1_048_576 -> String.format("%.1f MB", sizeBytes / 1_048_576.0)
            sizeBytes >= 1_024 -> String.format("%.0f KB", sizeBytes / 1_024.0)
            else -> "$sizeBytes B"
        }

    /** true si el contenido requiere WiFi (> 10 MB) */
    val requiresWifi: Boolean get() = sizeBytes > 10_000_000

    /** Categoría simple basada en el MIME type */
    val category: String
        get() = when {
            contentType.startsWith("video/") -> "Video"
            contentType.startsWith("audio/") -> "Audio"
            contentType.startsWith("image/") -> "Imagen"
            contentType.startsWith("application/pdf") -> "PDF"
            contentType.startsWith("text/") -> "Texto"
            else -> "Archivo"
        }

    /** Ícono representativo (emoji para simplicidad en logs) */
    val icon: String
        get() = when {
            contentType.startsWith("video/") -> "🎬"
            contentType.startsWith("audio/") -> "🎵"
            contentType.startsWith("image/") -> "🖼"
            contentType.startsWith("application/pdf") -> "📄"
            contentType.startsWith("text/") -> "📝"
            else -> "📦"
        }
}

/** Respuesta del CDN al buscar contenido */
data class ContentSearchResponse(
    val query: String = "",
    val results: List<ContentItem> = emptyList(),
    val totalResults: Int = 0
)

/** Estado de una descarga en progreso */
enum class DownloadState {
    IDLE,
    WAITING_WIFI,      // Esperando que CDN encienda WiFi vía ADB
    DOWNLOADING,        // Descargando el contenido
    COMPLETED,          // Descarga completada
    PLAYING,            // Reproduciendo el contenido
    ERROR               // Error en la descarga
}

/** Información del progreso de descarga */
data class DownloadProgress(
    val item: ContentItem? = null,
    val state: DownloadState = DownloadState.IDLE,
    val bytesDownloaded: Long = 0,
    val totalBytes: Long = 0,
    val errorMessage: String? = null
) {
    val progressPercent: Int
        get() = if (totalBytes > 0) ((bytesDownloaded * 100) / totalBytes).toInt() else 0
}
