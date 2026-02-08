package org.sdn.sdn_mobile_agent

import android.app.Application
import android.util.Log

/**
 * Clase Application de SDN Mobile Agent.
 * Punto de entrada de la aplicación Android.
 * Referenciada en AndroidManifest.xml como android:name=".SDNApplication"
 */
class SDNApplication : Application() {

    companion object {
        private const val TAG = "SDNApplication"
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "SDN Mobile Agent iniciado")
    }
}
