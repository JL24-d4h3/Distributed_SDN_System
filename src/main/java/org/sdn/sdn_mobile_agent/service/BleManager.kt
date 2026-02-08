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
 * Funcionalidades:
 * - Verificar soporte de BLE 5.0 Coded PHY (Long Range)
 * - Escanear dispositivos BLE (nodos de acceso ESP32/LILYGO)
 * - Advertising BLE para ser descubierto por nodos
 * - Conexión GATT con nodos de acceso
 * - Envío/recepción de datos por características BLE
 *
 * UUIDs de los nodos de acceso:
 * - Servicio: 4fafc201-1fb5-459e-8fcc-c5c9c331914b
 * - TX (leer):  beb5483e-36e1-4688-b7f5-ea07361b26a8
 * - RX (escribir): 6e400002-b5a3-f393-e0a9-e50e24dcca9e
 */
class BleManager(private val context: Context) {

    companion object {
        private const val TAG = "BleManager"

        val SERVICE_UUID: UUID = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b")
        val TX_CHAR_UUID: UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8")
        val RX_CHAR_UUID: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
    }

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private var scanner: BluetoothLeScanner? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var gatt: BluetoothGatt? = null
    private var isScanning = false
    private var isAdvertising = false

    /** Dispositivos BLE descubiertos durante el scan */
    private val _discoveredDevices = MutableStateFlow<List<ScanResult>>(emptyList())
    val discoveredDevices: StateFlow<List<ScanResult>> = _discoveredDevices

    /** Estado actual del módulo BLE */
    private val _bleState = MutableStateFlow("idle")
    val bleState: StateFlow<String> = _bleState

    /** Indica si el hardware soporta BLE 5.0 Coded PHY (Long Range) */
    val supportsCodedPhy: Boolean
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            bluetoothAdapter?.isLeCodedPhySupported == true
        } else false

    /** Indica si Bluetooth está habilitado en el dispositivo */
    val isBluetoothEnabled: Boolean
        get() = bluetoothAdapter?.isEnabled == true

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

    // ─── Scan BLE ───────────────────────────────────────────────

    /**
     * Inicia un scan BLE buscando nodos de acceso por UUID de servicio.
     * Usa Coded PHY si el hardware lo soporta.
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
            Log.i(TAG, "Usando Coded PHY (Long Range)")
        }

        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(SERVICE_UUID))
                .build()
        )

        try {
            scanner?.startScan(filters, settingsBuilder.build(), scanCallback)
            isScanning = true
            _bleState.value = "scanning"
            Log.i(TAG, "Scan BLE iniciado")
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

    // ─── Advertising BLE ────────────────────────────────────────

    /**
     * Inicia BLE advertising para que los nodos de acceso
     * puedan descubrir este dispositivo.
     */
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
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
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

    // ─── Conexión GATT ──────────────────────────────────────────

    /**
     * Conecta a un dispositivo BLE (nodo de acceso) vía GATT.
     * Usa Coded PHY si el hardware lo soporta.
     */
    fun connectToDevice(device: BluetoothDevice, onDataReceived: (ByteArray) -> Unit) {
        if (!hasBlePermissions()) return

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        Log.i(TAG, "Conectado a GATT server")
                        _bleState.value = "connected"
                        try {
                            gatt?.discoverServices()
                        } catch (e: SecurityException) {
                            Log.e(TAG, "SecurityException descubriendo servicios", e)
                        }
                    }

                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.i(TAG, "Desconectado de GATT server")
                        _bleState.value = "idle"
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    val service = gatt?.getService(SERVICE_UUID)
                    val txChar = service?.getCharacteristic(TX_CHAR_UUID)
                    txChar?.let {
                        try {
                            gatt.setCharacteristicNotification(it, true)
                            Log.i(TAG, "Notificaciones habilitadas en TX")
                        } catch (e: SecurityException) {
                            Log.e(TAG, "SecurityException configurando notificaciones", e)
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
                    Log.d(TAG, "Datos recibidos: ${data.size} bytes")
                    onDataReceived(data)
                }
            }
        }

        try {
            gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && supportsCodedPhy) {
                device.connectGatt(
                    context, false, callback,
                    BluetoothDevice.TRANSPORT_LE,
                    BluetoothDevice.PHY_LE_CODED_MASK
                )
            } else {
                device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            }
            Log.i(TAG, "Conectando a ${device.address}...")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException al conectar GATT", e)
        }
    }

    /**
     * Envía datos a un nodo de acceso por la característica RX.
     */
    fun sendData(data: ByteArray) {
        val service = gatt?.getService(SERVICE_UUID)
        val rxChar = service?.getCharacteristic(RX_CHAR_UUID)
        rxChar?.let {
            try {
                @Suppress("DEPRECATION")
                it.value = data
                @Suppress("DEPRECATION")
                gatt?.writeCharacteristic(it)
                Log.d(TAG, "Datos enviados: ${data.size} bytes")
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException al escribir característica", e)
            }
        }
    }

    /** Desconecta la conexión GATT activa */
    fun disconnectGatt() {
        try {
            gatt?.disconnect()
            gatt?.close()
            gatt = null
            Log.i(TAG, "GATT desconectado")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException al desconectar GATT", e)
        }
    }

    /** Detiene scan, advertising y desconecta GATT */
    fun stopAll() {
        stopScan()
        stopAdvertising()
        disconnectGatt()
        _discoveredDevices.value = emptyList()
        _bleState.value = "idle"
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
