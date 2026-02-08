package org.sdn.sdn_mobile_agent.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.sdn.sdn_mobile_agent.viewmodel.MainViewModel

/**
 * Pantalla de Búsqueda / Consola de Comandos.
 *
 * Doble función:
 * 1. Consola SDN: escribir comandos directos (bt on, bt off, diag, etc.)
 * 2. Búsqueda REST: si no es un comando reconocido, envía POST al controlador
 *
 * Comandos disponibles: escribir "help" para ver la lista completa.
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Consola / Búsqueda SDN") },
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
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── Campo de entrada (consola + búsqueda) ──
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Comando o búsqueda") },
                placeholder = { Text("help · bt on · status · diag · o texto libre") },
                leadingIcon = { Icon(Icons.Default.Terminal, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading,
                singleLine = true
            )

            // ── Botones de acción rápida ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Botón principal: Ejecutar
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
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (isLoading) "..." else "Ejecutar")
                }

                // Accesos rápidos
                FilledTonalButton(onClick = { query = "status"; viewModel.requestSession("status") }) {
                    Text("Status", style = MaterialTheme.typography.labelSmall)
                }
                FilledTonalButton(onClick = { query = "diag"; viewModel.requestSession("diag") }) {
                    Text("Diag", style = MaterialTheme.typography.labelSmall)
                }
            }

            // ── Chip de ayuda ──
            AssistChip(
                onClick = { query = "help"; viewModel.requestSession("help") },
                label = { Text("Escribe help para ver todos los comandos") },
                leadingIcon = { Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(16.dp)) }
            )

            // ── Aviso si no está conectado ──
            if (!isConnected) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(modifier = Modifier.padding(12.dp)) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "MQTT desconectado — comandos locales (bt on, status, diag) funcionan.\nPara búsqueda REST, ve a Config y conecta.",
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            // ── Mensaje de error ──
            errorMessage?.let { error ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            "Error",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(error, color = MaterialTheme.colorScheme.onErrorContainer)
                        TextButton(onClick = { viewModel.clearError() }) {
                            Text("Cerrar")
                        }
                    }
                }
            }

            // ── Resultado / Output ──
            if (searchResult.isNotBlank()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row {
                            Icon(
                                Icons.Default.Output,
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
            }

            // ── Botón de confirmación de entrega ──
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
