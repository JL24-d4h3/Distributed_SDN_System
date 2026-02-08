package org.sdn.sdn_mobile_agent.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.sdn.sdn_mobile_agent.viewmodel.MainViewModel

/**
 * Pantalla de Búsqueda / Solicitud de Contenido.
 *
 * Permite al usuario:
 * 1. Escribir una consulta (query)
 * 2. Enviar POST /sessions/request al controlador
 * 3. Ver el resultado de la sesión creada
 * 4. Confirmar la entrega del contenido
 *
 * Flujo:
 * - Usuario escribe query → "Buscar"
 * - App envía POST al controlador con originMac + query
 * - Controlador responde con sessionId
 * - Controlador envía PREPARE_BT/SWITCH_WIFI por MQTT
 * - App recibe y ejecuta los comandos
 * - Cuando el contenido llega, usuario confirma entrega
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
                title = { Text("Búsqueda SDN") },
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── Campo de consulta ──
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Consulta") },
                placeholder = { Text("Ej: algoritmo de dijkstra") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading
            )

            // ── Botón de búsqueda ──
            Button(
                onClick = {
                    if (query.isNotBlank()) {
                        viewModel.requestSession(query)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading && isConnected && query.isNotBlank()
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(Icons.Default.Send, contentDescription = null)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (isLoading) "Enviando..." else "Buscar")
            }

            // ── Aviso si no está conectado ──
            if (!isConnected) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(modifier = Modifier.padding(16.dp)) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Conéctate al broker MQTT primero (pestaña Config)",
                            color = MaterialTheme.colorScheme.onErrorContainer
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
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Error",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            error,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        TextButton(onClick = { viewModel.clearError() }) {
                            Text("Cerrar")
                        }
                    }
                }
            }

            // ── Resultado ──
            if (searchResult.isNotBlank()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row {
                            Icon(
                                Icons.Default.Article,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Resultado",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            searchResult,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
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
