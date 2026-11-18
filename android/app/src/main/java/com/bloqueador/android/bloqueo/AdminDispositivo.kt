package com.bloqueador.android.bloqueo

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * Protección contra desinstalación: un administrador de dispositivo sin ninguna política. No
 * puede bloquear el teléfono, borrar datos ni ver nada. Mientras está activo, Android no deja
 * desinstalar el Bloqueador, ni siquiera desde los launchers que desinstalan por su cuenta (como
 * el de Xiaomi). Para quitarlo hace falta Ajustes, que el modo estricto bloquea durante un bloqueo.
 *
 * No reacciona a ningún aviso del sistema: basta con que esté declarado en el manifiesto.
 */
class AdminDispositivo : DeviceAdminReceiver() {

    companion object {
        fun componente(context: Context): ComponentName = ComponentName(context, AdminDispositivo::class.java)

        /** True si la protección está activa. */
        fun activo(context: Context): Boolean =
            context.getSystemService(DevicePolicyManager::class.java)?.isAdminActive(componente(context)) == true

        /**
         * Pantalla del sistema para activar la protección, con [explicacion] debajo. Hay que
         * abrirla desde una actividad y sin FLAG_ACTIVITY_NEW_TASK: con esa bandera, Ajustes la cierra.
         */
        fun intentActivar(context: Context, explicacion: String): Intent =
            Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componente(context))
                .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, explicacion)

        /** Quita la protección. Android tarda un instante en darla por quitada. */
        fun quitar(context: Context) {
            context.getSystemService(DevicePolicyManager::class.java)?.removeActiveAdmin(componente(context))
        }
    }
}
