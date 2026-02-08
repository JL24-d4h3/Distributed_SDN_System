package org.sdn.sdn_mobile_agent.service

import android.content.Context
import android.os.BatteryManager
import org.sdn.sdn_mobile_agent.data.model.Metrics

/**
 * Recopila métricas de telemetría del dispositivo.
 *
 * Datos recopilados:
 * - MAC del dispositivo
 * - RSSI de la conexión WiFi actual
 * - Tecnología de radio activa
 * - Nivel de batería
 * - Dirección IP en la LAN
 *
 * Se usa para publicar en dispositivo/{MAC}/metrics cada 30 segundos.
 */
class TelemetryCollector(
    private val context: Context,
    private val wifiController: WifiController
) {

    /**
     * Recopila todas las métricas actuales del dispositivo.
     *
     * @param mac Dirección MAC del dispositivo
     * @param activeRadio Radio activa ("wifi", "bluetooth", etc.)
     * @return Objeto Metrics con todos los datos recopilados
     */
    fun collect(mac: String, activeRadio: String): Metrics {
        val batteryManager =
            context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val batteryLevel =
            batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

        return Metrics(
            mac = mac,
            rssi = wifiController.getCurrentRssi(),
            technology = activeRadio,
            batteryLevel = batteryLevel,
            ipAddress = wifiController.getCurrentIp()
        )
    }
}
