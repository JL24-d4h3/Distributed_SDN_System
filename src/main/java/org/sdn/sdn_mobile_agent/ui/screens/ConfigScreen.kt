package org.sdn.sdn_mobile_agent.ui.screens

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
 * Pantalla de Configuración.
 *
 * Permite al usuario configurar:
 * - IP del broker MQTT
 * - Puerto MQTT (default: 1883)
 * - Puerto REST API (default: 8081)
 * - Nombre del dispositivo
 *
 * También muestra información del dispositivo (MAC, soporte Coded PHY)
 * y botones para conectar/desconectar del broker.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigScreen(viewModel: MainViewModel) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val brokerIp by viewModel.preferences.brokerIp.collectAsState(initial = "192.168.18.1")
    val brokerPort by viewModel.preferences.brokerPort.collectAsState(initial = 1883)
    val deviceName by viewModel.preferences.deviceName.collectAsState(
        initial = "${Build.BRAND.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"
    )
    val restPort by viewModel.preferences.restPort.collectAsState(initial = 8081)
    val isConnected by viewModel.mqttManager.isConnected.collectAsState()
    val isConnecting by viewModel.mqttManager.isConnecting.collectAsState()
    val mqttError by viewModel.mqttManager.lastError.collectAsState()
    val deviceMac by viewModel.deviceMac.collectAsState()

    var ipInput by remember(brokerIp) { mutableStateOf(brokerIp) }
    var portInput by remember(brokerPort) { mutableStateOf(brokerPort.toString()) }
    var nameInput by remember(deviceName) { mutableStateOf(deviceName) }
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
                                if (viewModel.bleManager.supportsCodedPhy) "Soportado ✓" else "No soportado (usa BLE estándar)"
                            }"
                        )
                    }
                }
            }

            // ── Configuración del Broker ──
            Text("Broker MQTT", style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = ipInput,
                onValueChange = { ipInput = it },
                label = { Text("IP del Broker") },
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

            OutlinedTextField(
                value = nameInput,
                onValueChange = { nameInput = it },
                label = { Text("Nombre del Dispositivo") },
                leadingIcon = { Icon(Icons.Default.Label, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            // ── Botón guardar ──
            Button(
                onClick = {
                    scope.launch {
                        viewModel.preferences.saveBrokerIp(ipInput)
                        viewModel.preferences.saveBrokerPort(portInput.toIntOrNull() ?: 1883)
                        viewModel.preferences.saveDeviceName(nameInput)
                        viewModel.preferences.saveRestPort(restPortInput.toIntOrNull() ?: 8081)
                        snackbarHostState.showSnackbar(
                            message = "✓ Configuración guardada",
                            duration = SnackbarDuration.Short
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Save, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Guardar Configuración")
            }

            HorizontalDivider()

            // ── Botones de conexión ──
            Text("Conexión", style = MaterialTheme.typography.titleMedium)

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
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (isConnecting) "Conectando..." else "Conectar")
                }

                OutlinedButton(
                    onClick = { viewModel.disconnectMqtt() },
                    modifier = Modifier.weight(1f),
                    enabled = isConnected
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Desconectar")
                }
            }

            // ── Indicador de estado ──
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = when {
                        isConnected -> MaterialTheme.colorScheme.primaryContainer
                        isConnecting -> MaterialTheme.colorScheme.tertiaryContainer
                        mqttError != null -> MaterialTheme.colorScheme.errorContainer
                        else -> MaterialTheme.colorScheme.errorContainer
                    }
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    when {
                        isConnected -> {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Conectado al broker MQTT", style = MaterialTheme.typography.bodyLarge)
                        }
                        isConnecting -> {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Conectando...", style = MaterialTheme.typography.bodyLarge)
                        }
                        mqttError != null -> {
                            Icon(
                                Icons.Default.Error,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                mqttError ?: "Error",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        else -> {
                            Icon(
                                Icons.Default.Error,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Desconectado", style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        }
    }
}
