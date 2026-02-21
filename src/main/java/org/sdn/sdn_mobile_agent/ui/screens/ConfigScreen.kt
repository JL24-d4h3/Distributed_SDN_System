package org.sdn.sdn_mobile_agent.ui.screens

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.sdn.sdn_mobile_agent.viewmodel.MainViewModel

/**
 * Pantalla de Configuración — BLE-First.
 *
 * Conexión principal: BLE GATT (sin WiFi, sin broker IP)
 * Fallback opcional: MQTT + REST (requiere WiFi)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigScreen(viewModel: MainViewModel) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val deviceName by viewModel.preferences.deviceName.collectAsState(
        initial = "${Build.BRAND.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"
    )
    val deviceMac by viewModel.deviceMac.collectAsState()
    val cdnConnectionState by viewModel.bleManager.cdnConnectionState.collectAsState()
    val bleState by viewModel.bleManager.bleState.collectAsState()

    // MQTT (secundario)
    val brokerIp by viewModel.preferences.brokerIp.collectAsState(initial = "192.168.18.1")
    val brokerPort by viewModel.preferences.brokerPort.collectAsState(initial = 1883)
    val restPort by viewModel.preferences.restPort.collectAsState(initial = 8081)
    val isConnected by viewModel.mqttManager.isConnected.collectAsState()
    val isConnecting by viewModel.mqttManager.isConnecting.collectAsState()
    val mqttError by viewModel.mqttManager.lastError.collectAsState()

    var nameInput by remember(deviceName) { mutableStateOf(deviceName) }
    var showMqttSection by remember { mutableStateOf(false) }

    // MQTT inputs (solo si se expande la sección)
    var ipInput by remember(brokerIp) { mutableStateOf(brokerIp) }
    var portInput by remember(brokerPort) { mutableStateOf(brokerPort.toString()) }
    var restPortInput by remember(restPort) { mutableStateOf(restPort.toString()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Configuración") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── Tarjeta info del dispositivo ──
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Dispositivo", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.PhoneAndroid, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("MAC: $deviceMac", style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Bluetooth, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "BLE Coded PHY: ${
                                if (viewModel.bleManager.supportsCodedPhy) "Soportado ✓" else "Estándar"
                            }"
                        )
                    }
                }
            }

            // ── Nombre ──
            OutlinedTextField(
                value = nameInput,
                onValueChange = { nameInput = it },
                label = { Text("Nombre del Dispositivo") },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.Label, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Button(
                onClick = {
                    scope.launch {
                        viewModel.preferences.saveDeviceName(nameInput)
                        snackbarHostState.showSnackbar("✓ Guardado", duration = SnackbarDuration.Short)
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Save, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Guardar Nombre")
            }

            HorizontalDivider()

            // ═══════════════════════════════════════════
            // ── CONEXIÓN PRINCIPAL: BLE GATT ──
            // ═══════════════════════════════════════════
            Text("Conexión BLE (Principal)", style = MaterialTheme.typography.titleMedium)

            Text(
                "Conexión directa por Bluetooth al servidor CDN. No requiere WiFi ni IP.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Estado BLE
            val isCdnConnected = cdnConnectionState == "ready"
            val isCdnScanning = cdnConnectionState == "scanning"
            val isCdnConnecting = cdnConnectionState == "connecting" || cdnConnectionState == "discovering"

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = when {
                        isCdnConnected -> MaterialTheme.colorScheme.primaryContainer
                        isCdnScanning || isCdnConnecting -> MaterialTheme.colorScheme.tertiaryContainer
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    }
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        when {
                            isCdnConnected -> Icon(
                                Icons.Default.CheckCircle, null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            isCdnScanning || isCdnConnecting -> CircularProgressIndicator(
                                modifier = Modifier.size(20.dp), strokeWidth = 2.dp
                            )
                            else -> Icon(
                                Icons.Default.Bluetooth, null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            when {
                                isCdnConnected -> "CDN conectada vía BLE ✓"
                                isCdnScanning -> "Buscando CDN..."
                                isCdnConnecting -> "Conectando a CDN..."
                                else -> "CDN no conectada"
                            },
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Estado: $cdnConnectionState | BLE: $bleState",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { viewModel.initBleControlPlane() },
                    modifier = Modifier.weight(1f),
                    enabled = !isCdnConnected && !isCdnScanning && !isCdnConnecting
                ) {
                    if (isCdnScanning || isCdnConnecting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp), strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Icon(Icons.Default.Bluetooth, contentDescription = null)
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(when {
                        isCdnScanning -> "Buscando..."
                        isCdnConnecting -> "Conectando..."
                        else -> "Conectar BLE"
                    })
                }

                OutlinedButton(
                    onClick = { viewModel.bleManager.disconnectCdn() },
                    modifier = Modifier.weight(1f),
                    enabled = isCdnConnected
                ) {
                    Icon(Icons.Default.BluetoothDisabled, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Desconectar")
                }
            }

            HorizontalDivider()

            // ═══════════════════════════════════════════
            // ── CONEXIÓN SECUNDARIA: MQTT (colapsable) ──
            // ═══════════════════════════════════════════
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("WiFi / MQTT (Opcional)", style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = { showMqttSection = !showMqttSection }) {
                    Icon(
                        if (showMqttSection) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = "Expandir"
                    )
                }
            }

            if (showMqttSection) {
                Text(
                    "Solo necesario si WiFi está encendido. El control principal va por BLE.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = ipInput,
                    onValueChange = { ipInput = it },
                    label = { Text("IP del Servidor") },
                    leadingIcon = { Icon(Icons.Default.Cloud, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                )

                OutlinedTextField(
                    value = portInput,
                    onValueChange = { portInput = it },
                    label = { Text("Puerto MQTT") },
                    leadingIcon = { Icon(Icons.Default.SettingsEthernet, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )

                OutlinedTextField(
                    value = restPortInput,
                    onValueChange = { restPortInput = it },
                    label = { Text("Puerto REST API") },
                    leadingIcon = { Icon(Icons.Default.Http, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            scope.launch {
                                viewModel.preferences.saveBrokerIp(ipInput)
                                viewModel.preferences.saveBrokerPort(portInput.toIntOrNull() ?: 1883)
                                viewModel.preferences.saveRestPort(restPortInput.toIntOrNull() ?: 8081)
                            }
                            viewModel.initRestApi(ipInput, restPortInput.toIntOrNull() ?: 8081)
                            viewModel.connectMqtt(ipInput, portInput.toIntOrNull() ?: 1883)
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !isConnected && !isConnecting
                    ) {
                        if (isConnecting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp), strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Icon(Icons.Default.Wifi, contentDescription = null)
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (isConnecting) "Conectando..." else "Conectar WiFi")
                    }

                    OutlinedButton(
                        onClick = { viewModel.disconnectMqtt() },
                        modifier = Modifier.weight(1f),
                        enabled = isConnected
                    ) {
                        Icon(Icons.Default.WifiOff, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Desconectar")
                    }
                }

                // Estado MQTT
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = when {
                            isConnected -> MaterialTheme.colorScheme.primaryContainer
                            isConnecting -> MaterialTheme.colorScheme.tertiaryContainer
                            mqttError != null -> MaterialTheme.colorScheme.errorContainer
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        }
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        when {
                            isConnected -> {
                                Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("MQTT conectado", style = MaterialTheme.typography.bodyMedium)
                            }
                            isConnecting -> {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Conectando MQTT...", style = MaterialTheme.typography.bodyMedium)
                            }
                            mqttError != null -> {
                                Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(mqttError ?: "Error", style = MaterialTheme.typography.bodySmall)
                            }
                            else -> {
                                Icon(Icons.Default.WifiOff, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("MQTT desconectado", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}
