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
 * Arquitectura SDN:
 * ═════════════════
 * BLE  = Plano de Control (siempre ON, ~0.3 mA)
 * WiFi = Plano de Datos (ON/OFF bajo demanda)
 *
 * Muestra en tiempo real (auto-refresh cada 3s):
 * - Plano de Control BLE (GATT Server, controlador conectado)
 * - Plano de Datos WiFi (radio ON/OFF, conexión datos)
 * - Estado de Radios Hardware
 * - Sesión activa
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(viewModel: MainViewModel) {
    val isConnected by viewModel.mqttManager.isConnected.collectAsState()
    val activeRadio by viewModel.activeRadio.collectAsState()
    val bleState by viewModel.bleManager.bleState.collectAsState()
    val cdnConnectionState by viewModel.bleManager.cdnConnectionState.collectAsState()
    val dataWifiConnected by viewModel.wifiController.dataWifiConnected.collectAsState()
    val deviceMac by viewModel.deviceMac.collectAsState()
    val currentSession by viewModel.currentSession.collectAsState()

    // Métricas que se actualizan cada 3 segundos
    var rssi by remember { mutableIntStateOf(-100) }
    var ipAddress by remember { mutableStateOf("...") }
    var btEnabled by remember { mutableStateOf(false) }
    var wifiMode by remember { mutableStateOf("...") }
    var controllerConnected by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            rssi = viewModel.wifiController.getCurrentRssi()
            ipAddress = viewModel.wifiController.getCurrentIp()
            btEnabled = viewModel.bleManager.isBluetoothEnabled
            wifiMode = viewModel.wifiController.getWifiMode()
            controllerConnected = viewModel.bleManager.isCdnConnected
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
            // ── Plano de Control (BLE → CDN) ──
            val bleControlActive = btEnabled && cdnConnectionState != "disconnected"
            StatusCard(
                title = "Plano de Control (BLE → CDN)",
                icon = Icons.Default.Bluetooth,
                isActive = bleControlActive,
                statusText = when {
                    controllerConnected -> "CDN Conectada ✓"
                    cdnConnectionState == "connecting" -> "Conectando..."
                    cdnConnectionState == "discovering" -> "Descubriendo..."
                    btEnabled -> "BT ON (sin CDN)"
                    else -> "OFF"
                },
                details = listOf(
                    "BT Radio: ${if (btEnabled) "ON ✓" else "OFF ✗"}",
                    "CDN GATT: $cdnConnectionState",
                    "CDN conectada: ${if (controllerConnected) "Sí ✓" else "No"}",
                    "BLE: $bleState${if (viewModel.bleManager.supportsCodedPhy) " · Coded PHY ✓" else ""}",
                    "MAC: $deviceMac"
                )
            )

            // ── Plano de Datos (WiFi) ──
            val wifiIsOn = wifiMode != "off" && wifiMode != "..."
            val wifiStatusText = when (wifiMode) {
                "client" -> "Cliente ($ipAddress)"
                "hotspot" -> "Hotspot (AP)"
                "client (sin IP)" -> "Cliente (sin IP)"
                else -> "OFF (ahorro)"
            }
            StatusCard(
                title = "Plano de Datos (WiFi)",
                icon = Icons.Default.Wifi,
                isActive = wifiIsOn,
                statusText = if (wifiIsOn) "Activo" else "Apagado",
                details = listOf(
                    "WiFi Radio: $wifiStatusText",
                    "WiFi Datos: ${if (dataWifiConnected) "Conectado ✓" else "No"}",
                    "RSSI: $rssi dBm",
                    "MQTT (fallback): ${if (isConnected) "Conectado ✓" else "No"}",
                    if (!wifiIsOn) "→ WiFi apagado = ahorro energético ✓" else "→ WiFi encendido para transferencia de datos"
                )
            )

            // ── Radios Hardware ──
            StatusCard(
                title = "Resumen Radios",
                icon = Icons.Default.SettingsInputAntenna,
                isActive = btEnabled || wifiIsOn,
                statusText = when {
                    btEnabled && wifiIsOn -> "BT + WiFi"
                    btEnabled -> "Solo BT"
                    wifiIsOn -> "Solo WiFi"
                    else -> "Todo OFF"
                },
                details = listOf(
                    "Radio activa SDN: $activeRadio",
                    "BT: ${if (btEnabled) "ON" else "OFF"} | WiFi: ${if (wifiIsOn) "ON" else "OFF"}",
                    "Control: BLE GATT (nativo) / MQTT (fallback)"
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
