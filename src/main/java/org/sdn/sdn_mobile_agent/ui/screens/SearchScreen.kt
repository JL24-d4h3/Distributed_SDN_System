package org.sdn.sdn_mobile_agent.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.sdn.sdn_mobile_agent.data.model.ContentItem
import org.sdn.sdn_mobile_agent.data.model.DownloadState
import org.sdn.sdn_mobile_agent.viewmodel.MainViewModel

/**
 * Pantalla de Búsqueda / Consola de Comandos / Catálogo CDN.
 *
 * Triple función:
 * 1. Consola SDN: escribir comandos directos (bt on, wifi off, status, etc.)
 * 2. Búsqueda CDN: si no es comando → busca contenido en sdn_cdn_server.py
 * 3. Resultados interactivos: tap en contenido → descarga → WiFi auto → reproduce
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(viewModel: MainViewModel) {
    var query by remember { mutableStateOf("") }
    val isLoading by viewModel.isLoading.collectAsState()
    val searchResult by viewModel.searchResult.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val isConnected by viewModel.mqttManager.isConnected.collectAsState()
    val currentSession by viewModel.currentSession.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val downloadProgress by viewModel.downloadProgress.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Consola / Búsqueda CDN") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── Campo de entrada (consola + búsqueda CDN) ──
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Comando o búsqueda") },
                placeholder = { Text("help · status · fourier · redes sdn") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading,
                singleLine = true
            )

            // ── Botones de acción rápida ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        if (query.isNotBlank()) {
                            viewModel.requestSession(query)
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !isLoading && query.isNotBlank()
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (isLoading) "..." else "Buscar")
                }

                FilledTonalButton(onClick = { query = "status"; viewModel.requestSession("status") }) {
                    Text("Status", style = MaterialTheme.typography.labelSmall)
                }
                FilledTonalButton(onClick = { query = "help"; viewModel.requestSession("help") }) {
                    Text("Help", style = MaterialTheme.typography.labelSmall)
                }
            }

            // ── Progreso de descarga ──
            if (downloadProgress.state != DownloadState.IDLE) {
                DownloadProgressCard(downloadProgress)
            }

            // ── Error ──
            errorMessage?.let { error ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Error", style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(error, color = MaterialTheme.colorScheme.onErrorContainer)
                        TextButton(onClick = { viewModel.clearError() }) { Text("Cerrar") }
                    }
                }
            }

            // ── Resultados de búsqueda CDN (lista interactiva) ──
            if (searchResults.isNotEmpty()) {
                Text(
                    "${searchResults.size} resultado(s)",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(searchResults, key = { it.id }) { item ->
                        ContentItemCard(
                            item = item,
                            onClick = { viewModel.selectContent(item) }
                        )
                    }
                }
            } else if (searchResult.isNotBlank()) {
                // ── Output de texto (comandos, mensajes) ──
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .padding(12.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Row {
                            Icon(
                                Icons.Default.Terminal,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Output",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            searchResult,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            } else {
                // ── Placeholder ──
                Spacer(modifier = Modifier.weight(1f))
            }

            // ── Confirmar entrega (si hay sesión activa) ──
            currentSession?.let { session ->
                OutlinedButton(
                    onClick = { viewModel.confirmDelivery() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.DoneAll, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Confirmar Entrega (${session.sessionId})")
                }
            }
        }
    }
}

/**
 * Tarjeta de un contenido del catálogo CDN.
 * Muestra: ícono de tipo, título, tamaño, badge WiFi/BLE.
 */
@Composable
private fun ContentItemCard(
    item: ContentItem,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Ícono del tipo de contenido
            Icon(
                imageVector = contentTypeIcon(item.contentType),
                contentDescription = item.category,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(12.dp))

            // Título y descripción
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    "${item.category} · ${item.humanSize}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Badge: WiFi necesario o transferible por BLE
            Surface(
                shape = MaterialTheme.shapes.small,
                color = if (item.requiresWifi)
                    MaterialTheme.colorScheme.tertiaryContainer
                else
                    MaterialTheme.colorScheme.secondaryContainer
            ) {
                Text(
                    text = if (item.requiresWifi) "WiFi" else "BLE",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (item.requiresWifi)
                        MaterialTheme.colorScheme.onTertiaryContainer
                    else
                        MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
    }
}

/**
 * Tarjeta de progreso de descarga.
 */
@Composable
private fun DownloadProgressCard(progress: org.sdn.sdn_mobile_agent.data.model.DownloadProgress) {
    val containerColor = when (progress.state) {
        DownloadState.WAITING_WIFI -> MaterialTheme.colorScheme.tertiaryContainer
        DownloadState.DOWNLOADING -> MaterialTheme.colorScheme.primaryContainer
        DownloadState.COMPLETED, DownloadState.PLAYING -> MaterialTheme.colorScheme.secondaryContainer
        DownloadState.ERROR -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (progress.state) {
                    DownloadState.WAITING_WIFI -> {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Esperando WiFi...", style = MaterialTheme.typography.titleSmall)
                    }
                    DownloadState.DOWNLOADING -> {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Descargando...", style = MaterialTheme.typography.titleSmall)
                    }
                    DownloadState.COMPLETED -> {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Descarga completada", style = MaterialTheme.typography.titleSmall)
                    }
                    DownloadState.PLAYING -> {
                        Icon(Icons.Default.PlayCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Reproduciendo", style = MaterialTheme.typography.titleSmall)
                    }
                    DownloadState.ERROR -> {
                        Icon(Icons.Default.Error, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Error", style = MaterialTheme.typography.titleSmall)
                    }
                    else -> {}
                }
            }

            progress.item?.let { item ->
                Text(item.title, style = MaterialTheme.typography.bodySmall)
            }

            if (progress.state == DownloadState.DOWNLOADING && progress.totalBytes > 0) {
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { progress.progressPercent / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "${progress.progressPercent}%",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.End)
                )
            }

            progress.errorMessage?.let { error ->
                Text(error, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** Retorna el ícono Material adecuado para un MIME type */
private fun contentTypeIcon(contentType: String): ImageVector {
    return when {
        contentType.startsWith("video/") -> Icons.Default.PlayCircle
        contentType.startsWith("audio/") -> Icons.Default.MusicNote
        contentType.startsWith("image/") -> Icons.Default.Image
        contentType.contains("pdf") -> Icons.Default.Description
        contentType.startsWith("text/") -> Icons.AutoMirrored.Filled.Article
        else -> Icons.AutoMirrored.Filled.InsertDriveFile
    }
}
