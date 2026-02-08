package org.sdn.sdn_mobile_agent.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import org.sdn.sdn_mobile_agent.viewmodel.MainViewModel

/**
 * Pantalla principal de Dashboard.
 *
 * Muestra en tiempo real:
 * - Estado de conexión MQTT
 * - Radio activa (WiFi, BT, idle)
 * - Estado BLE (scan, advertising, connected)
 * - Información de red (RSSI, IP)
 * - Sesión activa (si existe)
 * - Botón para confirmar entrega
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(viewModel: MainViewModel) {
    val isConnected by viewModel.mqttManager.isConnected.collectAsState()
    val activeRadio by viewModel.activeRadio.collectAsState()
    val bleState by viewModel.bleManager.bleState.collectAsState()
    val dataWifiConnected by viewModel.wifiController.dataWifiConnected.collectAsState()
    val deviceMac by viewModel.deviceMac.collectAsState()
    val currentSession by viewModel.currentSession.collectAsState()

    // Métricas que se actualizan cuando la pantalla se recompone
    val rssi = viewModel.wifiController.getCurrentRssi()
    val ipAddress = viewModel.wifiController.getCurrentIp()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Dashboard SDN") },
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
            // ── Estado MQTT ──
            StatusCard(
                title = "MQTT",
                icon = Icons.Default.Cloud,
                isActive = isConnected,
                statusText = if (isConnected) "Conectado" else "Desconectado",
                details = listOf(
                    "MAC: $deviceMac",
                    "IP: $ipAddress"
                )
            )

            // ── Radio activa ──
            StatusCard(
                title = "Radio Activa",
                icon = when (activeRadio) {
                    "bluetooth" -> Icons.Default.Bluetooth
                    "wifi" -> Icons.Default.Wifi
                    "wifi+bluetooth" -> Icons.Default.WifiTethering
                    else -> Icons.Default.RadioButtonUnchecked
                },
                isActive = activeRadio != "idle",
                statusText = when (activeRadio) {
                    "idle" -> "Inactivo"
                    "bluetooth" -> "Bluetooth LE"
                    "wifi" -> "WiFi Datos"
                    "wifi+bluetooth" -> "WiFi + BT"
                    "ble_coded_phy" -> "BLE Coded PHY"
                    else -> activeRadio
                },
                details = listOf(
                    "BLE: $bleState",
                    "WiFi Datos: ${if (dataWifiConnected) "Conectado" else "No conectado"}",
                    "RSSI WiFi: $rssi dBm"
                )
            )

            // ── Info BLE ──
            StatusCard(
                title = "Bluetooth LE",
                icon = Icons.Default.Bluetooth,
                isActive = bleState != "idle",
                statusText = when (bleState) {
                    "idle" -> "Inactivo"
                    "scanning" -> "Escaneando..."
                    "advertising" -> "Anunciando..."
                    "connected" -> "Conectado"
                    "error" -> "Error"
                    else -> bleState
                },
                details = listOf(
                    "Coded PHY: ${if (viewModel.bleManager.supportsCodedPhy) "Sí (Long Range)" else "No"}",
                    "BT Habilitado: ${if (viewModel.bleManager.isBluetoothEnabled) "Sí" else "No"}"
                )
            )

            // ── Sesión activa ──
            currentSession?.let { session ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Assignment, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Sesión Activa",
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("ID: ${session.sessionId}")
                        session.query?.let { Text("Query: $it") }
                        session.status?.let { Text("Estado: $it") }
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = { viewModel.confirmDelivery() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Confirmar Entrega")
                        }
                    }
                }
            }
        }
    }
}

/**
 * Tarjeta reutilizable para mostrar estado de un componente.
 */
@Composable
private fun StatusCard(
    title: String,
    icon: ImageVector,
    isActive: Boolean,
    statusText: String,
    details: List<String>
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (isActive)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.weight(1f))
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = if (isActive)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.outline
                ) {
                    Text(
                        statusText,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isActive)
                            MaterialTheme.colorScheme.onPrimary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            details.forEach { detail ->
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
