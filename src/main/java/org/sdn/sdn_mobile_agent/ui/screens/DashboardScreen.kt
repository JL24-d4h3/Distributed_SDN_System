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
import kotlinx.coroutines.delay
import org.sdn.sdn_mobile_agent.viewmodel.MainViewModel

/**
 * Pantalla principal de Dashboard.
 *
 * Muestra en tiempo real (auto-refresh cada 3s):
 * - Estado de conexión MQTT
 * - Radio activa (WiFi, BT, idle) con estado real del hardware
 * - Estado BLE (scan, advertising, connected)
 * - Información de red (RSSI, IP)
 * - Nivel de control de radios (Device Owner, Admin, etc.)
 * - Sesión activa (si existe)
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

    // Métricas que se actualizan cada 3 segundos
    var rssi by remember { mutableIntStateOf(-100) }
    var ipAddress by remember { mutableStateOf("...") }
    var btEnabled by remember { mutableStateOf(false) }
    var isDeviceOwner by remember { mutableStateOf(false) }
    var isDeviceAdmin by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            rssi = viewModel.wifiController.getCurrentRssi()
            ipAddress = viewModel.wifiController.getCurrentIp()
            btEnabled = viewModel.bleManager.isBluetoothEnabled
            isDeviceOwner = viewModel.radioController.isDeviceOwner
            isDeviceAdmin = viewModel.radioController.isDeviceAdmin
            delay(3_000)
        }
    }

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
                    "IP: $ipAddress",
                    "RSSI WiFi: $rssi dBm"
                )
            )

            // ── Estado de Radios (Hardware real) ──
            StatusCard(
                title = "Radios (Hardware)",
                icon = Icons.Default.SettingsInputAntenna,
                isActive = btEnabled || (ipAddress != "0.0.0.0" && ipAddress != "..."),
                statusText = when {
                    btEnabled && ipAddress != "0.0.0.0" -> "BT + WiFi ON"
                    btEnabled -> "BT ON · WiFi OFF"
                    ipAddress != "0.0.0.0" && ipAddress != "..." -> "BT OFF · WiFi ON"
                    else -> "Ambos OFF"
                },
                details = listOf(
                    "Bluetooth: ${if (btEnabled) "ON ✓" else "OFF ✗"}",
                    "WiFi: ${if (ipAddress != "0.0.0.0" && ipAddress != "...") "ON ✓ ($ipAddress)" else "OFF ✗"}",
                    "Radio SDN activa: $activeRadio"
                )
            )

            // ── BLE Operations ──
            StatusCard(
                title = "Bluetooth LE",
                icon = Icons.Default.Bluetooth,
                isActive = bleState != "idle",
                statusText = when (bleState) {
                    "idle" -> "Inactivo"
                    "scanning" -> "Escaneando"
                    "advertising" -> "Anunciando"
                    "connected" -> "Conectado"
                    "error" -> "Error"
                    else -> bleState
                },
                details = listOf(
                    "Estado: $bleState",
                    "Coded PHY: ${if (viewModel.bleManager.supportsCodedPhy) "Sí (Long Range)" else "No"}",
                    "WiFi Datos: ${if (dataWifiConnected) "Conectado ✓" else "No conectado"}"
                )
            )

            // ── Control de Radios (nivel de privilegios) ──
            val controlLevel = when {
                isDeviceOwner -> "Device Owner ✓ (control total)"
                isDeviceAdmin -> "Device Admin (control parcial)"
                else -> "Sin privilegios (requiere ADB)"
            }
            StatusCard(
                title = "Control Radios",
                icon = Icons.Default.AdminPanelSettings,
                isActive = isDeviceOwner || isDeviceAdmin,
                statusText = when {
                    isDeviceOwner -> "Device Owner"
                    isDeviceAdmin -> "Admin"
                    else -> "Normal"
                },
                details = listOf(
                    "Nivel: $controlLevel",
                    "Toggle BT: ${if (isDeviceOwner) "automático" else "ADB o manual"}",
                    "Toggle WiFi: ${if (isDeviceOwner) "automático" else "ADB o manual"}"
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
