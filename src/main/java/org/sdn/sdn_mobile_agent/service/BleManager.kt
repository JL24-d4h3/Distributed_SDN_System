package org.sdn.sdn_mobile_agent.service

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.*

/**
 * Gestiona todas las operaciones Bluetooth Low Energy (BLE).
 *
 * Arquitectura SDN — Plano de Control BLE:
 * ═════════════════════════════════════════
 * BT siempre ON en el celular (~0.3 mA)
 * WiFi = Plano de Datos (toggle vía ADB por la CDN/laptop)
 *
 * Roles:
 *   Laptop (CDN)  → GATT Server (Python, siempre ON)
 *   Celular (App)  → GATT Client (conecta al CDN cuando necesita)
 *
 * GATT Service de la CDN (laptop):
 *   SDN_SERVICE_UUID
 *     ├─ REQUEST_CHAR   (Write)  → App escribe solicitudes JSON
 *     ├─ RESPONSE_CHAR  (Notify) ← CDN notifica respuestas JSON
 *     └─ CONTROL_CHAR   (Notify) ← CDN notifica comandos (WIFI_READY, etc.)
 *
 * Flujo típico:
 *   1. App escanea → encuentra CDN por SDN_SERVICE_UUID
 *   2. App conecta GATT Client → descubre servicios
 *   3. App escribe solicitud en REQUEST_CHAR
 *   4. CDN evalúa → si texto, responde por RESPONSE_CHAR
 *      → si video, envía CONTROL: enable_wifi vía ADB, transmite WiFi
 *   5. CDN envía CONTROL: disable_wifi → ADB apaga WiFi
 *   6. App desconecta (o CDN cierra sesión)
 *
 * Adicionalmente mantiene scan/advertising para nodos ESP32.
 */
class BleManager(private val context: Context) {

    companion object {
        private const val TAG = "BleManager"

        // ── UUIDs del GATT Server CDN (laptop) ──
        val SDN_SERVICE_UUID: UUID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890")
        /** App ESCRIBE solicitudes aquí (Write) */
        val SDN_REQUEST_UUID: UUID = UUID.fromString("a1b2c3d4-0001-7890-abcd-ef1234567890")
        /** CDN NOTIFICA respuestas aquí (Notify) */
        val SDN_RESPONSE_UUID: UUID = UUID.fromString("a1b2c3d4-0002-7890-abcd-ef1234567890")
        /** CDN NOTIFICA comandos de control aquí (Notify, ej: WIFI_READY) */
        val SDN_CONTROL_UUID: UUID = UUID.fromString("a1b2c3d4-0003-7890-abcd-ef1234567890")
        /** Descriptor estándar Client Characteristic Configuration */
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // MAC conocida de la CDN (laptop) — fallback cuando MIUI bloquea BLE scan
        const val CDN_MAC_FALLBACK = "C8:15:4E:EC:76:64"

        // ── UUIDs de nodos de acceso (ESP32/LILYGO) ──
        val NODE_SERVICE_UUID: UUID = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b")
        val NODE_TX_CHAR_UUID: UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8")
        val NODE_RX_CHAR_UUID: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
    }

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private var scanner: BluetoothLeScanner? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var isScanning = false
    private var isAdvertising = false

    // ── GATT Client hacia CDN (laptop) ──
    private var cdnGatt: BluetoothGatt? = null
    private var requestCharacteristic: BluetoothGattCharacteristic? = null

    // ── Reassembly buffers para mensajes chunked ──
    private val responseBuffer = StringBuilder()
    private val controlBuffer = StringBuilder()

    // ── Auto-connect ──
    /** MAC de la CDN para auto-reconexión */
    private var cdnMacAddress: String? = null
    private var autoConnectEnabled = true

    // ── GATT Client hacia nodos ESP32 ──
    private var nodeGatt: BluetoothGatt? = null

    /** Callback: CDN envía respuesta (contenido, datos ligeros) */
    var onCdnResponse: ((String) -> Unit)? = null

    /** Callback: CDN envía comando de control (WIFI_READY, WIFI_DISABLED, etc.) */
    var onCdnControl: ((String) -> Unit)? = null

    /** Callback: conexión GATT a CDN cambió */
    var onCdnConnectionChanged: ((Boolean) -> Unit)? = null

    /** Dispositivos BLE descubiertos durante el scan */
    private val _discoveredDevices = MutableStateFlow<List<ScanResult>>(emptyList())
    val discoveredDevices: StateFlow<List<ScanResult>> = _discoveredDevices

    /** Estado actual del módulo BLE */
    private val _bleState = MutableStateFlow("idle")
    val bleState: StateFlow<String> = _bleState

    /** Estado de conexión con la CDN */
    private val _cdnConnectionState = MutableStateFlow("disconnected")
    val cdnConnectionState: StateFlow<String> = _cdnConnectionState

    /** Indica si el hardware soporta BLE 5.0 Coded PHY (Long Range) */
    val supportsCodedPhy: Boolean
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            bluetoothAdapter?.isLeCodedPhySupported == true
        } else false

    /** Indica si Bluetooth está habilitado en el dispositivo */
    val isBluetoothEnabled: Boolean
        get() = bluetoothAdapter?.isEnabled == true

    /** Indica si estamos conectados al GATT Server de la CDN */
    val isCdnConnected: Boolean
        get() = _cdnConnectionState.value == "ready"

    // ─── Verificación de permisos ───────────────────────────────

    private fun hasPermission(permission: String): Boolean {
        return ActivityCompat.checkSelfPermission(
            context, permission
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasBlePermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hasPermission(Manifest.permission.BLUETOOTH_SCAN) &&
                    hasPermission(Manifest.permission.BLUETOOTH_CONNECT) &&
                    hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    // ════════════════════════════════════════════════════════════
    // ═══ GATT CLIENT → CDN (Laptop) ═══════════════════════════
    // ════════════════════════════════════════════════════════════

    /**
     * Conecta al GATT Server de la CDN (laptop).
     * El dispositivo debe descubrirse primero vía scan con SDN_SERVICE_UUID.
     */
    fun connectToCdn(device: BluetoothDevice) {
        if (!hasBlePermissions() || !isBluetoothEnabled) {
            Log.w(TAG, "No se puede conectar a CDN: permisos=${hasBlePermissions()}, bt=$isBluetoothEnabled")
            _cdnConnectionState.value = "error"
            return
        }

        // Guardar MAC para auto-reconexión
        cdnMacAddress = device.address
        _cdnConnectionState.value = "connecting"
        // Reset reconexión completa solo en conexión fresca (no en reconnect por stale cache)
        if (fullReconnectCount == 0) serviceDiscoveryRetries = 0
        Log.i(TAG, "Conectando a CDN GATT Server: ${device.address} (reconnect=#$fullReconnectCount)...")

        try {
            // Siempre usar TRANSPORT_LE con 1M PHY — el servidor CDN (BlueZ)
            // anuncia en 1M PHY; Coded PHY causa timeout status=147.
            cdnGatt = device.connectGatt(context, false, cdnGattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException conectando a CDN", e)
            _cdnConnectionState.value = "error"
        }
    }

    /**
     * Conecta a la CDN por dirección MAC directa (sin scan previo).
     * Útil cuando ya se conoce la MAC de la laptop.
     */
    fun connectToCdnByAddress(macAddress: String) {
        if (!hasBlePermissions() || !isBluetoothEnabled) {
            _cdnConnectionState.value = "error"
            return
        }

        try {
            val device = bluetoothAdapter?.getRemoteDevice(macAddress)
            if (device != null) {
                connectToCdn(device)
            } else {
                Log.e(TAG, "No se pudo obtener dispositivo para MAC: $macAddress")
                _cdnConnectionState.value = "error"
            }
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "MAC inválida: $macAddress", e)
            _cdnConnectionState.value = "error"
        }
    }

    /**
     * Escribe una solicitud JSON en la CDN vía BLE GATT.
     * La CDN la procesará y responderá por RESPONSE o CONTROL characteristic.
     */
    fun sendRequestToCdn(json: String): Boolean {
        val gatt = cdnGatt ?: return false
        val char = requestCharacteristic ?: return false

        if (_cdnConnectionState.value != "ready") {
            Log.w(TAG, "No conectado a CDN, estado: ${_cdnConnectionState.value}")
            return false
        }

        try {
            @Suppress("DEPRECATION")
            char.value = json.toByteArray(Charsets.UTF_8)
            @Suppress("DEPRECATION")
            val success = gatt.writeCharacteristic(char)
            Log.i(TAG, "Solicitud enviada a CDN (${json.length} bytes): ${json.take(80)}... → $success")
            return success
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException escribiendo a CDN", e)
            return false
        }
    }

    /** Desconecta de la CDN */
    fun disconnectCdn() {
        try {
            cdnGatt?.disconnect()
            cdnGatt?.close()
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException desconectando CDN", e)
        }
        cdnGatt = null
        requestCharacteristic = null
        _cdnConnectionState.value = "disconnected"
        Log.i(TAG, "Desconectado de CDN")
    }

    /** Cola de características pendientes de habilitar notificaciones */
    private val pendingNotifyChars: Queue<BluetoothGattCharacteristic> = LinkedList()

    /** Contador de CCCD writes pendientes antes de pasar a "ready" */
    private var pendingCccdCount = 0

    /** Guard: evitar que onMtuChanged llame discoverServices más de una vez */
    @Volatile
    private var serviceDiscoveryStarted = false

    /** Contador de reintentos de service discovery */
    private var serviceDiscoveryRetries = 0
    private val MAX_SERVICE_DISCOVERY_RETRIES = 3

    /** Reconexiones completas (close + re-scan) tras caché stale */
    private var fullReconnectCount = 0
    private val MAX_FULL_RECONNECTS = 2

    /** Guard: evitar reconexiones superpuestas */
    @Volatile
    private var isReconnecting = false

    /** Reset del contador — llamar solo desde entry points de usuario (no desde recovery) */
    fun resetReconnectCount() {
        fullReconnectCount = 0
        isReconnecting = false
    }

    /**
     * Limpia la caché GATT de Android para un dispositivo.
     * Usa reflexión porque BluetoothGatt.refresh() es API oculta.
     * Necesario cuando BlueZ cambia servicios y Android cachea los viejos.
     */
    private fun refreshGattCache(gatt: BluetoothGatt): Boolean {
        return try {
            val method = gatt.javaClass.getMethod("refresh")
            val result = method.invoke(gatt) as Boolean
            Log.i(TAG, "GATT cache refresh: $result")
            result
        } catch (e: Exception) {
            Log.w(TAG, "BluetoothGatt.refresh() no disponible: ${e.message}")
            false
        }
    }

    /** Callback del GATT Client conectado a la CDN */
    private val cdnGattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "Conectado al GATT Server de la CDN (status=$status, reconnect=#$fullReconnectCount)")
                    _cdnConnectionState.value = "discovering"
                    serviceDiscoveryStarted = false
                    serviceDiscoveryRetries = 0  // reset per-connection retries
                    // Limpiar caché GATT de Android — BlueZ puede haber cambiado servicios
                    // En reconexión (close+re-scan) no llamar refresh(), close() ya limpió
                    if (gatt != null && fullReconnectCount == 0) {
                        refreshGattCache(gatt)
                    } else {
                        Log.i(TAG, "Reconexión #$fullReconnectCount — skip refresh (close ya limpió)")
                    }
                    // Paso 1: Negociar MTU con delay post-refresh.
                    // refresh() necesita ~1.5s para que Android realmente limpie la caché
                    // antes de hacer cualquier operación GATT.
                    val mtuDelay = if (fullReconnectCount > 0) 500L else 1500L
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        try {
                            Log.i(TAG, "Solicitando MTU 512 (post-refresh delay=${mtuDelay}ms)")
                            gatt?.requestMtu(512)
                        } catch (e: SecurityException) {
                            Log.e(TAG, "SecurityException al solicitar MTU", e)
                            try { gatt?.discoverServices() } catch (_: SecurityException) {}
                        }
                    }, mtuDelay)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "Desconectado del GATT Server de la CDN (status=$status)")
                    _cdnConnectionState.value = "disconnected"
                    requestCharacteristic = null
                    serviceDiscoveryStarted = false
                    onCdnConnectionChanged?.invoke(false)
                    // CRÍTICO: cerrar el GATT viejo para evitar leak de recursos BLE.
                    // Sin esto, Android acumula conexiones GATT fantasma que corrompen el stack.
                    try {
                        gatt?.close()
                    } catch (_: SecurityException) {}
                    if (cdnGatt == gatt) cdnGatt = null
                    // Auto-reconectar si fue desconexión inesperada
                    if (autoConnectEnabled && cdnMacAddress != null && !isReconnecting) {
                        isReconnecting = true
                        Log.i(TAG, "Intentando auto-reconexión a CDN en 3s...")
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            isReconnecting = false
                            connectToCdnByAddress(cdnMacAddress!!)
                        }, 3000)
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Error descubriendo servicios CDN: status=$status")
                _cdnConnectionState.value = "error"
                return
            }

            // Log todos los servicios descubiertos para debug
            val allServices = gatt?.services ?: emptyList()
            Log.i(TAG, "Servicios encontrados (${allServices.size}): ${allServices.map { it.uuid }}")

            val sdnService = gatt?.getService(SDN_SERVICE_UUID)
            if (sdnService == null) {
                serviceDiscoveryRetries++
                if (serviceDiscoveryRetries <= MAX_SERVICE_DISCOVERY_RETRIES) {
                    Log.w(TAG, "Servicio SDN no encontrado — reintento $serviceDiscoveryRetries/$MAX_SERVICE_DISCOVERY_RETRIES en 500ms")
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        try {
                            serviceDiscoveryStarted = false
                            gatt?.discoverServices()
                            serviceDiscoveryStarted = true
                        } catch (_: SecurityException) {
                            _cdnConnectionState.value = "error"
                        }
                    }, 500)
                    return
                }

                // ── Caché GATT stale → cerrar y reconectar ──
                if (fullReconnectCount < MAX_FULL_RECONNECTS) {
                    fullReconnectCount++
                    Log.w(TAG, "Caché GATT stale → disconnect + close + re-scan #$fullReconnectCount/$MAX_FULL_RECONNECTS")
                    serviceDiscoveryStarted = false
                    serviceDiscoveryRetries = 0
                    isReconnecting = true
                    try {
                        gatt?.disconnect()  // señalizar desconexión al remoto
                    } catch (_: SecurityException) {}
                    try {
                        gatt?.close()  // close() limpia recursos + caché local
                    } catch (_: SecurityException) {}
                    cdnGatt = null
                    requestCharacteristic = null
                    _cdnConnectionState.value = "reconnecting"

                    // Esperar que BlueZ y Android limpien estado, luego re-scan
                    // Delay largo (4s) para que el stack BT realmente descarte la caché
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        isReconnecting = false
                        Log.i(TAG, "Re-scanning CDN tras close (intento #$fullReconnectCount)...")
                        scanAndConnectCdn()
                    }, 4000)
                    return
                }

                // Máximo de reconexiones alcanzado — informar al usuario.
                // BT NUNCA se desactiva programáticamente: es el plano de control SDN.
                Log.e(TAG, "Servicio SDN no encontrado tras $MAX_FULL_RECONNECTS reconexiones completas.")
                Log.e(TAG, "Posibles causas: servidor GATT no corriendo con sudo, instancias duplicadas, o BlueZ corrupto.")
                Log.e(TAG, "Solución: en laptop → sudo pkill -f sdn_cdn_gatt && sudo systemctl restart bluetooth && sudo python3 sdn_cdn_gatt.py ...")
                _cdnConnectionState.value = "error"
                fullReconnectCount = 0
                isReconnecting = false
                return
            }

            // Guardar referencia a la característica de REQUEST (para escribir)
            requestCharacteristic = sdnService.getCharacteristic(SDN_REQUEST_UUID)
            fullReconnectCount = 0  // Reset: conexión exitosa
            if (requestCharacteristic == null) {
                Log.e(TAG, "Característica REQUEST no encontrada en CDN")
                _cdnConnectionState.value = "error"
                return
            }

            // Habilitar notificaciones en RESPONSE y CONTROL (serializadas)
            val responseChar = sdnService.getCharacteristic(SDN_RESPONSE_UUID)
            val controlChar = sdnService.getCharacteristic(SDN_CONTROL_UUID)

            // Encolar CONTROL para después de RESPONSE
            if (controlChar != null) {
                pendingNotifyChars.add(controlChar)
            }

            // pendingCccdCount = cuántos CCCD writes faltan antes de estar "ready"
            pendingCccdCount = (if (responseChar != null) 1 else 0) + (if (controlChar != null) 1 else 0)

            if (responseChar != null) {
                enableNotification(gatt, responseChar)
            } else if (pendingCccdCount == 0) {
                // No hay características de notificación, estamos listos
                _cdnConnectionState.value = "ready"
                Log.i(TAG, "✓ CDN GATT listo (sin notificaciones)")
                onCdnConnectionChanged?.invoke(true)
            }

            Log.i(TAG, "Servicios descubiertos — configurando $pendingCccdCount notificaciones...")
        }

        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "MTU negociado: $mtu bytes")
            } else {
                Log.w(TAG, "MTU negotiation failed: status=$status (continuando con MTU default)")
            }
            // Guard: algunos dispositivos (Motorola, etc.) disparan onMtuChanged 2 veces.
            // Solo llamar discoverServices una vez.
            if (serviceDiscoveryStarted) {
                Log.w(TAG, "onMtuChanged duplicado — ignorando (discoverServices ya iniciado)")
                return
            }
            serviceDiscoveryStarted = true
            // Paso 2: Ahora que MTU terminó, descubrir servicios.
            // Delay mínimo de 300ms para estabilización post-MTU.
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try {
                    Log.i(TAG, "Descubriendo servicios CDN (reconnect=#$fullReconnectCount)...")
                    gatt?.discoverServices()
                } catch (e: SecurityException) {
                    Log.e(TAG, "SecurityException descubriendo servicios CDN", e)
                    _cdnConnectionState.value = "error"
                }
            }, 300)
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?
        ) {
            val data = characteristic?.value ?: return
            if (data.isEmpty()) return

            // Protocolo de chunking: byte 0 = flag, bytes 1..N = payload
            val flag = data[0].toInt() and 0xFF
            val payload = if (data.size > 1) String(data, 1, data.size - 1, Charsets.UTF_8) else ""

            when (characteristic.uuid) {
                SDN_RESPONSE_UUID -> {
                    when (flag) {
                        0x00 -> { // SINGLE — mensaje completo
                            responseBuffer.clear()
                            Log.i(TAG, "Respuesta CDN (single): ${payload.take(120)}")
                            onCdnResponse?.invoke(payload)
                        }
                        0x01 -> { // FIRST — inicio de mensaje
                            responseBuffer.clear()
                            responseBuffer.append(payload)
                            Log.d(TAG, "Respuesta CDN chunk START (${payload.length}B)")
                        }
                        0x02 -> { // CONT — continuación
                            responseBuffer.append(payload)
                            Log.d(TAG, "Respuesta CDN chunk CONT (+${payload.length}B = ${responseBuffer.length}B)")
                        }
                        0x03 -> { // LAST — fin de mensaje
                            responseBuffer.append(payload)
                            val complete = responseBuffer.toString()
                            responseBuffer.clear()
                            Log.i(TAG, "Respuesta CDN (${complete.length}B reassembled): ${complete.take(120)}")
                            onCdnResponse?.invoke(complete)
                        }
                    }
                }
                SDN_CONTROL_UUID -> {
                    when (flag) {
                        0x00 -> {
                            controlBuffer.clear()
                            Log.i(TAG, "Control CDN (single): ${payload.take(120)}")
                            onCdnControl?.invoke(payload)
                        }
                        0x01 -> {
                            controlBuffer.clear()
                            controlBuffer.append(payload)
                        }
                        0x02 -> controlBuffer.append(payload)
                        0x03 -> {
                            controlBuffer.append(payload)
                            val complete = controlBuffer.toString()
                            controlBuffer.clear()
                            Log.i(TAG, "Control CDN (${complete.length}B reassembled): ${complete.take(120)}")
                            onCdnControl?.invoke(complete)
                        }
                    }
                }
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt?,
            descriptor: BluetoothGattDescriptor?,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "CCCD escrito exitosamente para ${descriptor?.characteristic?.uuid}")
                pendingCccdCount--
                // Habilitar la siguiente notificación pendiente
                val next = pendingNotifyChars.poll()
                if (next != null && gatt != null) {
                    enableNotification(gatt, next)
                } else if (pendingCccdCount <= 0 && _cdnConnectionState.value != "ready") {
                    // Todas las notificaciones habilitadas → LISTO
                    _cdnConnectionState.value = "ready"
                    Log.i(TAG, "✓ CDN GATT listo — REQUEST/RESPONSE/CONTROL configurados")
                    onCdnConnectionChanged?.invoke(true)
                }
            } else {
                Log.e(TAG, "Error escribiendo CCCD: status=$status")
                pendingCccdCount--
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Escritura a CDN exitosa (${characteristic?.uuid})")
            } else {
                Log.e(TAG, "Error escribiendo a CDN: status=$status")
            }
        }
    }

    /** Habilita notificaciones + escribe CCCD para una característica */
    private fun enableNotification(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        try {
            gatt.setCharacteristicNotification(characteristic, true)
            val cccd = characteristic.getDescriptor(CCCD_UUID)
            if (cccd != null) {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(cccd)
                Log.d(TAG, "Habilitando notificaciones para ${characteristic.uuid}")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException habilitando notificaciones", e)
        }
    }

    // ════════════════════════════════════════════════════════════
    // ═══ SCAN BLE ═════════════════════════════════════════════
    // ════════════════════════════════════════════════════════════

    /**
     * Inicia un scan BLE buscando:
     * - CDN (laptop) por SDN_SERVICE_UUID
     * - Nodos de acceso (ESP32) por NODE_SERVICE_UUID
     */
    fun startScan() {
        if (!hasBlePermissions() || !isBluetoothEnabled) {
            Log.w(TAG, "No se puede escanear: permisos=${hasBlePermissions()}, bt=$isBluetoothEnabled")
            return
        }

        scanner = bluetoothAdapter?.bluetoothLeScanner

        val settingsBuilder = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && supportsCodedPhy) {
            settingsBuilder.setPhy(BluetoothDevice.PHY_LE_CODED)
            Log.i(TAG, "Scan: usando Coded PHY (Long Range)")
        }

        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(SDN_SERVICE_UUID))
                .build(),
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(NODE_SERVICE_UUID))
                .build()
        )

        try {
            scanner?.startScan(filters, settingsBuilder.build(), scanCallback)
            isScanning = true
            _bleState.value = "scanning"
            Log.i(TAG, "Scan BLE iniciado (CDN + nodos)")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException al iniciar scan", e)
        }
    }

    /** Detiene el scan BLE activo */
    fun stopScan() {
        if (!isScanning) return
        try {
            scanner?.stopScan(scanCallback)
            isScanning = false
            _bleState.value = if (isAdvertising) "advertising" else "idle"
            Log.i(TAG, "Scan BLE detenido")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException al detener scan", e)
        }
    }

    // ═══ ADVERTISING BLE ═════════════════════════════════════

    /** Inicia BLE advertising — permite que nodos ESP32 descubran este dispositivo */
    fun startAdvertising() {
        if (!hasBlePermissions() || !isBluetoothEnabled) return

        advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        if (advertiser == null) {
            Log.w(TAG, "BLE advertising no soportado en este dispositivo")
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(SDN_SERVICE_UUID))
            .build()

        try {
            advertiser?.startAdvertising(settings, data, advertiseCallback)
            isAdvertising = true
            _bleState.value = "advertising"
            Log.i(TAG, "BLE advertising iniciado")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException al iniciar advertising", e)
        }
    }

    /** Detiene el BLE advertising */
    fun stopAdvertising() {
        if (!isAdvertising) return
        try {
            advertiser?.stopAdvertising(advertiseCallback)
            isAdvertising = false
            _bleState.value = if (isScanning) "scanning" else "idle"
            Log.i(TAG, "BLE advertising detenido")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException al detener advertising", e)
        }
    }

    // ═══ GATT CLIENT → NODOS ESP32 ══════════════════════════

    /** Conecta a un nodo de acceso ESP32 vía GATT Client */
    fun connectToNode(device: BluetoothDevice, onDataReceived: (ByteArray) -> Unit) {
        if (!hasBlePermissions()) return

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        Log.i(TAG, "Conectado a nodo ESP32: ${device.address}")
                        try { gatt?.discoverServices() } catch (e: SecurityException) {
                            Log.e(TAG, "SecurityException descubriendo servicios nodo", e)
                        }
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.i(TAG, "Desconectado de nodo ESP32")
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    val service = gatt?.getService(NODE_SERVICE_UUID)
                    val txChar = service?.getCharacteristic(NODE_TX_CHAR_UUID)
                    txChar?.let {
                        try {
                            gatt.setCharacteristicNotification(it, true)
                            Log.i(TAG, "Notificaciones habilitadas en nodo TX")
                        } catch (e: SecurityException) {
                            Log.e(TAG, "SecurityException configurando notificaciones nodo", e)
                        }
                    }
                }
            }

            @Deprecated("Deprecated in API 33")
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt?,
                characteristic: BluetoothGattCharacteristic?
            ) {
                characteristic?.value?.let { data ->
                    Log.d(TAG, "Datos recibidos del nodo: ${data.size} bytes")
                    onDataReceived(data)
                }
            }
        }

        try {
            nodeGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && supportsCodedPhy) {
                device.connectGatt(
                    context, false, callback,
                    BluetoothDevice.TRANSPORT_LE,
                    BluetoothDevice.PHY_LE_CODED_MASK
                )
            } else {
                device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            }
            Log.i(TAG, "Conectando a nodo ${device.address}...")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException al conectar a nodo", e)
        }
    }

    /** Envía datos a un nodo ESP32 por la característica RX */
    fun sendDataToNode(data: ByteArray) {
        val service = nodeGatt?.getService(NODE_SERVICE_UUID)
        val rxChar = service?.getCharacteristic(NODE_RX_CHAR_UUID)
        rxChar?.let {
            try {
                @Suppress("DEPRECATION")
                it.value = data
                @Suppress("DEPRECATION")
                nodeGatt?.writeCharacteristic(it)
                Log.d(TAG, "Datos enviados al nodo: ${data.size} bytes")
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException al escribir a nodo", e)
            }
        }
    }

    /** Desconecta del nodo ESP32 */
    fun disconnectNode() {
        try {
            nodeGatt?.disconnect()
            nodeGatt?.close()
            nodeGatt = null
            Log.i(TAG, "Nodo GATT desconectado")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException al desconectar nodo", e)
        }
    }

    // ═══ UTILIDADES ═════════════════════════════════════════

    /** Detiene scan, advertising, desconecta nodo (NO toca CDN) */
    fun stopOperations() {
        stopScan()
        stopAdvertising()
        disconnectNode()
        _discoveredDevices.value = emptyList()
        _bleState.value = "idle"
    }

    /** Detiene todo incluyendo conexión CDN */
    fun stopEverything() {
        autoConnectEnabled = false
        stopOperations()
        disconnectCdn()
    }

    /** Habilita o deshabilita auto-reconexión a CDN */
    fun setAutoConnect(enabled: Boolean) {
        autoConnectEnabled = enabled
    }

    /**
     * Escanea, busca la CDN por UUID y conecta automáticamente.
     * Se auto-detiene el scan al encontrar la CDN.
     */
    fun scanAndConnectCdn() {
        if (!hasBlePermissions() || !isBluetoothEnabled) {
            Log.w(TAG, "No se puede escanear CDN: permisos=${hasBlePermissions()}, bt=$isBluetoothEnabled")
            return
        }

        _cdnConnectionState.value = "scanning"
        // NO resetear fullReconnectCount aquí — puede ser llamado desde recovery por caché stale
        Log.i(TAG, "Escaneando para auto-conectar a CDN (reconnect=#$fullReconnectCount)...")

        scanner = bluetoothAdapter?.bluetoothLeScanner

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        // MIUI/HyperOS deniega scans sin filtro → usar UUID filter para pasar la check.
        // El filtro HW puede no coincidir (UUID en scan response), pero MIUI permite el scan.
        // La coincidencia real se hace en onScanResult por nombre/UUID del scan record.
        val miuiFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SDN_SERVICE_UUID))
            .build()

        var cdnFound = false  // Guard: evitar doble conexión

        // Función auxiliar para evaluar un ScanResult
        fun evaluateResult(sr: ScanResult): Boolean {
            if (cdnFound) return false
            val name = sr.scanRecord?.deviceName ?: try { sr.device.name } catch (_: SecurityException) { null } ?: ""
            val uuids = sr.scanRecord?.serviceUuids ?: emptyList()
            val matchByUuid = uuids.any { it.uuid == SDN_SERVICE_UUID }
            val matchByName = name == "SDN-CDN"

            Log.d(TAG, "Scan: ${sr.device.address} name='$name' uuids=$uuids rssi=${sr.rssi}")

            return matchByUuid || matchByName
        }

        val autoCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                if (cdnFound) return
                result?.let { sr ->
                    if (evaluateResult(sr)) {
                        cdnFound = true
                        try { scanner?.stopScan(this) } catch (_: SecurityException) {}
                        // Scan descubre CDN en dirección LE random; conectar
                        // siempre por la dirección pública para que BlueZ sirva
                        // el GATT Application completo.
                        val publicMac = cdnMacAddress ?: CDN_MAC_FALLBACK
                        Log.i(TAG, "CDN encontrada: ${sr.device.address} (scan) — conectando por MAC pública $publicMac")
                        connectToCdnByAddress(publicMac)
                    }
                }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>?) {
                if (cdnFound) return
                results?.forEach { sr ->
                    if (evaluateResult(sr)) {
                        cdnFound = true
                        try { scanner?.stopScan(this) } catch (_: SecurityException) {}
                        val publicMac = cdnMacAddress ?: CDN_MAC_FALLBACK
                        Log.i(TAG, "CDN encontrada (batch): ${sr.device.address} (scan) — conectando por MAC pública $publicMac")
                        connectToCdnByAddress(publicMac)
                        return
                    }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "Scan CDN fallido ($errorCode) → conexión directa por MAC")
                val mac = cdnMacAddress ?: CDN_MAC_FALLBACK
                connectToCdnByAddress(mac)
            }
        }

        try {
            scanner?.startScan(listOf(miuiFilter), settings, autoCallback)
            // Si no encuentra CDN en 8s, intentar conexión directa por MAC conocida
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (_cdnConnectionState.value == "scanning") {
                    try {
                        scanner?.stopScan(autoCallback)
                    } catch (_: SecurityException) {}
                    // Fallback: MIUI puede bloquear scan → conectar directo por MAC
                    val mac = cdnMacAddress ?: CDN_MAC_FALLBACK
                    Log.w(TAG, "Scan sin resultados (MIUI?) → conexión directa a $mac")
                    connectToCdnByAddress(mac)
                }
            }, 8000)
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException al escanear CDN", e)
            _cdnConnectionState.value = "error"
        }
    }

    // ─── Callbacks ──────────────────────────────────────────────

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.let { sr ->
                val current = _discoveredDevices.value.toMutableList()
                val existing = current.indexOfFirst { it.device.address == sr.device.address }
                if (existing >= 0) {
                    current[existing] = sr
                } else {
                    current.add(sr)
                }
                _discoveredDevices.value = current
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan fallido con error: $errorCode")
            isScanning = false
            _bleState.value = "error"
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.i(TAG, "Advertising iniciado exitosamente")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "Advertising fallido con error: $errorCode")
            isAdvertising = false
        }
    }
}
