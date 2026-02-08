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

    // ── GATT Client hacia nodos ESP32 ──
    private var nodeGatt: BluetoothGatt? = null

    /** Callback: CDN envía respuesta (contenido, datos ligeros) */
    var onCdnResponse: ((String) -> Unit)? = null

    /** Callback: CDN envía comando de control (WIFI_READY, WIFI_DISABLED, etc.) */
    var onCdnControl: ((String) -> Unit)? = null

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

        _cdnConnectionState.value = "connecting"
        Log.i(TAG, "Conectando a CDN GATT Server: ${device.address}...")

        try {
            cdnGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && supportsCodedPhy) {
                device.connectGatt(
                    context, false, cdnGattCallback,
                    BluetoothDevice.TRANSPORT_LE,
                    BluetoothDevice.PHY_LE_CODED_MASK
                )
            } else {
                device.connectGatt(context, false, cdnGattCallback, BluetoothDevice.TRANSPORT_LE)
            }
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

    /** Callback del GATT Client conectado a la CDN */
    private val cdnGattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "Conectado al GATT Server de la CDN (status=$status)")
                    _cdnConnectionState.value = "discovering"
                    try {
                        gatt?.discoverServices()
                    } catch (e: SecurityException) {
                        Log.e(TAG, "SecurityException descubriendo servicios CDN", e)
                        _cdnConnectionState.value = "error"
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "Desconectado del GATT Server de la CDN (status=$status)")
                    _cdnConnectionState.value = "disconnected"
                    requestCharacteristic = null
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Error descubriendo servicios CDN: status=$status")
                _cdnConnectionState.value = "error"
                return
            }

            val sdnService = gatt?.getService(SDN_SERVICE_UUID)
            if (sdnService == null) {
                Log.e(TAG, "Servicio SDN no encontrado en CDN")
                _cdnConnectionState.value = "error"
                return
            }

            // Guardar referencia a la característica de REQUEST (para escribir)
            requestCharacteristic = sdnService.getCharacteristic(SDN_REQUEST_UUID)
            if (requestCharacteristic == null) {
                Log.e(TAG, "Característica REQUEST no encontrada en CDN")
                _cdnConnectionState.value = "error"
                return
            }

            // Habilitar notificaciones en RESPONSE
            val responseChar = sdnService.getCharacteristic(SDN_RESPONSE_UUID)
            if (responseChar != null) {
                enableNotification(gatt, responseChar)
            }

            // Habilitar notificaciones en CONTROL (encolar, BLE solo permite uno a la vez)
            val controlChar = sdnService.getCharacteristic(SDN_CONTROL_UUID)
            if (controlChar != null) {
                pendingNotifyChars.add(controlChar)
            }

            _cdnConnectionState.value = "ready"
            Log.i(TAG, "✓ CDN GATT listo — REQUEST/RESPONSE/CONTROL configurados")
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?
        ) {
            val data = characteristic?.value ?: return
            val json = String(data, Charsets.UTF_8)
            Log.i(TAG, "Notificación CDN (${characteristic.uuid}): ${json.take(120)}")

            when (characteristic.uuid) {
                SDN_RESPONSE_UUID -> onCdnResponse?.invoke(json)
                SDN_CONTROL_UUID -> onCdnControl?.invoke(json)
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt?,
            descriptor: BluetoothGattDescriptor?,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "CCCD escrito exitosamente para ${descriptor?.characteristic?.uuid}")
                // Habilitar la siguiente notificación pendiente
                val next = pendingNotifyChars.poll()
                if (next != null && gatt != null) {
                    enableNotification(gatt, next)
                }
            } else {
                Log.e(TAG, "Error escribiendo CCCD: status=$status")
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
        stopOperations()
        disconnectCdn()
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
