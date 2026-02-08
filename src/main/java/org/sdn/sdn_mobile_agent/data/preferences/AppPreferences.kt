package org.sdn.sdn_mobile_agent.data.preferences

import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "sdn_settings")

/**
 * Gestiona las preferencias persistentes de la app usando DataStore.
 * Almacena: IP del broker, puerto MQTT, puerto REST, nombre del dispositivo.
 */
class AppPreferences(private val context: Context) {

    companion object {
        val BROKER_IP = stringPreferencesKey("broker_ip")
        val BROKER_PORT = intPreferencesKey("broker_port")
        val DEVICE_NAME = stringPreferencesKey("device_name")
        val REST_PORT = intPreferencesKey("rest_port")
    }

    val brokerIp: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[BROKER_IP] ?: "192.168.18.1"
    }

    val brokerPort: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[BROKER_PORT] ?: 1883
    }

    val deviceName: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[DEVICE_NAME] ?: "${Build.BRAND.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"
    }

    val restPort: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[REST_PORT] ?: 8081
    }

    suspend fun saveBrokerIp(ip: String) {
        context.dataStore.edit { it[BROKER_IP] = ip }
    }

    suspend fun saveBrokerPort(port: Int) {
        context.dataStore.edit { it[BROKER_PORT] = port }
    }

    suspend fun saveDeviceName(name: String) {
        context.dataStore.edit { it[DEVICE_NAME] = name }
    }

    suspend fun saveRestPort(port: Int) {
        context.dataStore.edit { it[REST_PORT] = port }
    }
}
