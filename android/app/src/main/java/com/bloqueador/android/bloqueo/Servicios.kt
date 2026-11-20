package com.bloqueador.android.bloqueo

import android.Manifest
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.Process
import android.provider.Settings
import com.bloqueador.android.datos.Almacen
import java.util.concurrent.CopyOnWriteArrayList

/** Estado de los dos servicios de bloqueo y ayudas para activarlos desde la interfaz. */
object Servicios {
    private const val PREFS = "bloqueador_estado"
    private const val CLAVE_PERMISO_VPN = "permiso_vpn"
    private const val CLAVE_PANTALLA_NO_APARECE = "pantalla_no_aparece"

    /** Extra para FiltroVpnService: lo arrancaron con startForegroundService (Android 8-11). */
    const val EXTRA_PRIMER_PLANO = "com.bloqueador.android.extra.PRIMER_PLANO"

    /**
     * Lo actualiza FiltroVpnService: true mientras el filtro tiene su túnel establecido. Es false
     * en el túnel de paso (filtro pausado por falta de red), aunque la VPN siga conectada.
     */
    @Volatile
    var vpnActiva: Boolean = false

    /** Lo actualiza ServicioAccesibilidad: true mientras el sistema lo tiene conectado. */
    @Volatile
    var accesibilidadConectada: Boolean = false

    /**
     * Lo actualiza FiltroVpnService (Android 10+): true si en Ajustes de VPN está activado
     * «Bloquear conexiones sin VPN». Con eso el resto de apps se queda sin internet.
     */
    @Volatile
    var vpnLockdown: Boolean = false

    /** Lo actualiza FiltroVpnService (Android 10+): true si somos la «VPN siempre activada». */
    @Volatile
    var vpnSiempreActiva: Boolean = false

    /**
     * Lo actualiza FiltroVpnService: true si el filtro no consigue reenviar consultas DNS a
     * internet (por ejemplo, porque el sistema le quitó el acceso a la red a Bloqueador). Fuera
     * de un bloqueo, mientras es true, el filtro está en el túnel de paso: las apps resuelven
     * directo contra la red real.
     */
    @Volatile
    var vpnSinRed: Boolean = false

    /**
     * SystemClock.elapsedRealtime() de la última vez que PantallaBloqueoActivity llegó a
     * onResume. ServicioAccesibilidad lo usa para saber si la pantalla de bloqueo apareció.
     */
    @Volatile
    var pantallaBloqueoVista: Long = 0L

    /**
     * Lo actualiza PantallaBloqueoActivity: true entre su onResume y su onPause. Mientras se ve,
     * ServicioAccesibilidad no la vuelve a abrir en cada revisión, porque solo la haría parpadear.
     */
    @Volatile
    var pantallaBloqueoEnFrente: Boolean = false

    /**
     * Hasta este instante (System.currentTimeMillis) el modo estricto deja abrir el diálogo
     * del sistema que pide permiso de VPN. Lo fija la interfaz justo antes de pedir el permiso.
     */
    @Volatile
    var permitirDialogoVpnHasta: Long = 0L

    private val oyentesVistaPrevia = CopyOnWriteArrayList<() -> Unit>()

    /**
     * Pide a ServicioAccesibilidad que muestre la cuenta regresiva unos segundos, para ver cómo
     * queda. Se llama desde el hilo principal. Devuelve false si el servicio no está conectado.
     */
    fun verCuentaRegresiva(): Boolean {
        oyentesVistaPrevia.forEach { it() }
        return oyentesVistaPrevia.isNotEmpty()
    }

    /** Lo usa ServicioAccesibilidad mientras está conectado. Devuelve una función para darlo de baja. */
    fun escucharVistaPrevia(oyente: () -> Unit): () -> Unit {
        oyentesVistaPrevia += oyente
        return { oyentesVistaPrevia -= oyente }
    }

    /** True si el usuario activó nuestro servicio en Ajustes > Accesibilidad. */
    fun accesibilidadHabilitada(context: Context): Boolean {
        val habilitados = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val propio = ComponentName(context, ServicioAccesibilidad::class.java)
        return habilitados.split(':').any { ComponentName.unflattenFromString(it) == propio }
    }

    /**
     * Lo último que sabemos del permiso de VPN (se guarda al pedirlo y al crear el túnel).
     * No llama a VpnService.prepare(), así que se puede consultar a menudo sin efectos.
     */
    fun permisoVpnConcedido(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(CLAVE_PERMISO_VPN, false)

    fun marcarPermisoVpn(context: Context, concedido: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(CLAVE_PERMISO_VPN, concedido).apply()
    }

    /**
     * True si la pantalla de bloqueo no llegó a verse dos veces seguidas (en Xiaomi suele faltar
     * el permiso «Mostrar ventanas emergentes en segundo plano»). Se borra cuando vuelve a verse.
     */
    fun pantallaNoAparece(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(CLAVE_PANTALLA_NO_APARECE, false)

    fun marcarPantallaNoAparece(context: Context, valor: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(CLAVE_PANTALLA_NO_APARECE, valor).apply()
    }

    /**
     * Llama a VpnService.prepare(). Devuelve null si el permiso ya está concedido, o el Intent
     * del diálogo del sistema para pedirlo.
     *
     * OJO: si otra app tiene una VPN conectada y ya nos habían dado permiso, Android la
     * desconecta para dejar la nuestra. Por eso solo se usa cuando el usuario toca "Activar"
     * o durante un bloqueo activo, nunca para mostrar el estado.
     */
    fun prepararVpn(context: Context): Intent? {
        val intent = try {
            VpnService.prepare(context)
        } catch (e: RuntimeException) {
            return Intent(Settings.ACTION_VPN_SETTINGS)
        }
        marcarPermisoVpn(context, intent == null)
        return intent
    }

    /**
     * Arranca (o reafirma) la VPN si ya tenemos permiso. Devuelve false si falta el permiso o
     * si Android no dejó arrancar el servicio (por ejemplo, desde segundo plano).
     */
    fun iniciarVpn(context: Context): Boolean = try {
        if (!permisoVpnConcedido(context)) {
            false
        } else {
            context.startService(Intent(context, FiltroVpnService::class.java))
            true
        }
    } catch (e: IllegalStateException) {
        false
    } catch (e: SecurityException) {
        false
    }

    /**
     * Al encender el teléfono o al actualizar la app. Tras reiniciar, Android no deja crear el
     * túnel hasta que llamamos a prepare(); si el permiso sigue vigente, devuelve null sin
     * mostrar nada. No se llama si hay otra VPN conectada (salvo durante un bloqueo), para no
     * desconectarla.
     *
     * En Android 8-11 un receptor no puede arrancar un servicio normal desde segundo plano: se
     * arranca en primer plano y el servicio quita su aviso en cuanto el túnel está listo.
     */
    fun iniciarVpnAlArrancar(context: Context): Boolean {
        if (otraVpnConectada(context) && !Almacen.config.estado().activo) return false
        if (prepararVpn(context) != null) return false
        val intent = Intent(context, FiltroVpnService::class.java)
        return try {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) {
                context.startForegroundService(intent.putExtra(EXTRA_PRIMER_PLANO, true))
            } else {
                context.startService(intent)
            }
            true
        } catch (e: IllegalStateException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }

    /** True si la red que usa la app ahora mismo es una VPN que no es la nuestra. */
    private fun otraVpnConectada(context: Context): Boolean {
        if (vpnActiva) return false
        return try {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
            val red = cm.activeNetwork ?: return false
            cm.getNetworkCapabilities(red)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        } catch (e: RuntimeException) {
            false
        }
    }

    /**
     * Durante un bloqueo con sitios: si la VPN no está activa (por ejemplo, porque se conectó
     * otra app de VPN para saltarse el bloqueo), la recupera sin preguntar, siempre que el
     * usuario ya nos haya dado permiso alguna vez.
     */
    fun recuperarVpnDuranteBloqueo(context: Context): Boolean =
        prepararVpn(context) == null && iniciarVpn(context)

    /**
     * Desde Android 14, algunas pantallas de Ajustes se ocultan a los servicios de accesibilidad.
     * Para que el modo estricto también las tape, hace falta «Acceso a datos de uso».
     */
    val necesitaAccesoUso: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    /** True si el usuario dio a Bloqueador «Acceso a datos de uso» (estadísticas de uso). */
    fun accesoUso(context: Context): Boolean = try {
        val ops = context.getSystemService(AppOpsManager::class.java)
        val modo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        if (modo == AppOpsManager.MODE_DEFAULT) {
            context.checkSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED
        } else {
            modo == AppOpsManager.MODE_ALLOWED
        }
    } catch (e: Exception) {
        false
    }

    /**
     * True si "DNS privado" está fijado a un proveedor concreto. En ese modo Android puede
     * saltarse el filtro de sitios, así que la interfaz avisa al usuario.
     */
    fun dnsPrivadoEstricto(context: Context): Boolean = try {
        Settings.Global.getString(context.contentResolver, "private_dns_mode") == "hostname"
    } catch (e: Exception) {
        false
    }
}
