package org.sdn.sdn_mobile_agent.admin

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Receptor de administración del dispositivo.
 *
 * Permite que la app opere como Device Owner para controlar
 * radios BT/WiFi programáticamente sin interacción del usuario.
 *
 * Configuración (una sola vez, requiere quitar cuentas Google del dispositivo):
 *   adb shell dpm set-device-owner org.sdn.sdn_mobile_agent/.admin.AdminReceiver
 *
 * Verificar:
 *   adb shell dpm list-owners
 *
 * Remover (si es necesario):
 *   adb shell dpm remove-active-admin org.sdn.sdn_mobile_agent/.admin.AdminReceiver
 */
class AdminReceiver : DeviceAdminReceiver() {

    companion object {
        private const val TAG = "AdminReceiver"
    }

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "✓ Device Admin habilitado — control de radios disponible")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.i(TAG, "Device Admin deshabilitado")
    }

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        super.onProfileProvisioningComplete(context, intent)
        Log.i(TAG, "Provisioning completo — Device Owner activo")
    }
}
