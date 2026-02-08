package org.sdn.sdn_mobile_agent

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.sdn.sdn_mobile_agent.ui.navigation.AppNavigation
import org.sdn.sdn_mobile_agent.ui.theme.SDNMobileAgentTheme
import org.sdn.sdn_mobile_agent.viewmodel.MainViewModel

/**
 * Activity principal de la aplicación SDN Mobile Agent.
 *
 * Responsabilidades:
 * - Solicitar permisos en tiempo de ejecución (BLE, WiFi, Location)
 * - Manejar solicitudes de habilitación de Bluetooth
 * - Inicializar el MainViewModel
 * - Montar la UI Compose con navegación por pestañas
 *
 * La Activity usa ComponentActivity (no AppCompatActivity) para
 * compatibilidad nativa con Jetpack Compose.
 */
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var viewModel: MainViewModel

    /**
     * Launcher para solicitar múltiples permisos a la vez.
     * Registrado antes de onCreate para cumplir con el lifecycle.
     */
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.filter { it.value }.keys
        val denied = permissions.filter { !it.value }.keys

        if (granted.isNotEmpty()) {
            Log.i(TAG, "Permisos concedidos: $granted")
        }
        if (denied.isNotEmpty()) {
            Log.w(TAG, "Permisos denegados: $denied")
        }
    }

    /**
     * Launcher para solicitar al usuario que encienda Bluetooth.
     * Se activa cuando el controlador envía PREPARE_BT y BT está apagado.
     */
    private val bluetoothEnableLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val enabled = result.resultCode == RESULT_OK
        Log.i(TAG, "Bluetooth enable result: enabled=$enabled")
        viewModel.onBluetoothEnableResult(enabled)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Inicializar ViewModel
        viewModel = ViewModelProvider(this)[MainViewModel::class.java]

        // Solicitar permisos necesarios
        requestRequiredPermissions()

        // Observar solicitudes de habilitación de BT
        lifecycleScope.launch {
            viewModel.requestBluetoothEnable.collectLatest { shouldRequest ->
                if (shouldRequest) {
                    Log.i(TAG, "Solicitando al usuario habilitar Bluetooth...")
                    val enableBtIntent = android.content.Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                    bluetoothEnableLauncher.launch(enableBtIntent)
                }
            }
        }

        // Si BT está apagado, pedir encenderlo al inicio para que esté listo
        // cuando el controlador envíe PREPARE_BT
        if (!viewModel.bleManager.isBluetoothEnabled) {
            Log.i(TAG, "BT apagado al iniciar — solicitando activación proactiva")
            try {
                val enableBtIntent = android.content.Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                bluetoothEnableLauncher.launch(enableBtIntent)
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo lanzar solicitud BT al inicio", e)
            }
        }

        // Montar UI Compose
        setContent {
            SDNMobileAgentTheme {
                AppNavigation(viewModel = viewModel)
            }
        }
    }

    /**
     * Solicita todos los permisos necesarios para la operación de la app.
     *
     * Permisos por versión de Android:
     * - Todos: INTERNET, LOCATION, WIFI, NETWORK
     * - API < 31: BLUETOOTH, BLUETOOTH_ADMIN
     * - API 31+: BLUETOOTH_SCAN, BLUETOOTH_CONNECT, BLUETOOTH_ADVERTISE
     * - API 33+: POST_NOTIFICATIONS
     */
    private fun requestRequiredPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.CHANGE_NETWORK_STATE
        )

        // Permisos Bluetooth según versión de Android
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.addAll(
                listOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_ADVERTISE
                )
            )
        } else {
            @Suppress("DEPRECATION")
            permissions.addAll(
                listOf(
                    Manifest.permission.BLUETOOTH,
                    Manifest.permission.BLUETOOTH_ADMIN
                )
            )
        }

        // Permiso de notificaciones (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Filtrar solo los permisos que aún no se han concedido
        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            Log.i(TAG, "Solicitando ${needed.size} permisos: $needed")
            permissionLauncher.launch(needed.toTypedArray())
        } else {
            Log.i(TAG, "Todos los permisos ya concedidos")
        }
    }
}