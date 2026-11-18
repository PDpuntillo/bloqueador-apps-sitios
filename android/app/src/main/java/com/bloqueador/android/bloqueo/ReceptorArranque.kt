package com.bloqueador.android.bloqueo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Al encender el teléfono o actualizar la app, levanta la VPN de sitios.
 *
 * No basta con mirar si el permiso estaba concedido: tras reiniciar, Android no deja crear el
 * túnel hasta que la app vuelve a llamar a VpnService.prepare() (ver Servicios.iniciarVpnAlArrancar).
 * Si aun así no arranca, durante un bloqueo lo recupera el servicio de accesibilidad, y fuera de
 * uno basta con tocar Activar en la app.
 */
class ReceptorArranque : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val accion = intent.action
        if (accion != Intent.ACTION_BOOT_COMPLETED && accion != Intent.ACTION_MY_PACKAGE_REPLACED) return
        try {
            Servicios.iniciarVpnAlArrancar(context)
        } catch (e: Exception) {
            // Durante un bloqueo lo recupera ServicioAccesibilidad (recuperarVpnDuranteBloqueo).
        }
    }
}
