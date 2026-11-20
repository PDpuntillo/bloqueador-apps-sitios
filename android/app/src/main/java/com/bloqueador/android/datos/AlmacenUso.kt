package com.bloqueador.android.datos

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import com.bloqueador.android.dominio.CambioUso
import com.bloqueador.android.dominio.Config
import com.bloqueador.android.dominio.Dominios
import com.bloqueador.android.dominio.Limite
import com.bloqueador.android.dominio.TipoLimite
import com.bloqueador.android.dominio.UsoDiario
import com.bloqueador.android.dominio.sumarUso
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Tiempo de uso de hoy de lo que tiene límite diario. Lo suma el servicio de accesibilidad; lo
 * leen la VPN (sitios sin tiempo) y la interfaz. Se guarda en SharedPreferences cada tanto y
 * siempre que se agota un límite, así que si el proceso se cierra se pierden unos segundos.
 */
object AlmacenUso {
    private const val ARCHIVO = "bloqueador_uso"
    private const val CLAVE = "uso"
    private const val GUARDAR_CADA_MS = 30_000L

    @Volatile
    private var uso: UsoDiario = UsoDiario()

    private var prefs: SharedPreferences? = null
    private var guardadoEn = 0L
    private val oyentes = CopyOnWriteArrayList<() -> Unit>()

    /** Se llama desde BloqueadorApp.onCreate. Llamarlo varias veces no hace nada. */
    @Synchronized
    fun iniciar(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(ARCHIVO, Context.MODE_PRIVATE)
        prefs = p
        uso = UsoDiario.desdeJson(p.getString(CLAVE, null))
    }

    /** El uso de hoy (vacío si lo guardado es de otro día). */
    fun hoy(): UsoDiario = uso.alDia(LocalDate.now())

    /**
     * Suma [extra] ms a los límites de [enUso]. Guarda cada tanto, y en seguida si alguno se
     * agotó (también avisa a los oyentes, por ejemplo a la VPN para que bloquee el sitio).
     */
    fun sumar(enUso: List<Limite>, extra: Long): CambioUso {
        val cambio = synchronized(this) {
            val c = sumarUso(uso, enUso, extra, LocalDate.now())
            uso = c.uso
            val ahora = SystemClock.elapsedRealtime()
            if (c.agotados.isNotEmpty() || ahora - guardadoEn >= GUARDAR_CADA_MS) {
                guardadoEn = ahora
                prefs?.edit()?.putString(CLAVE, c.uso.aJson())?.apply()
            }
            c
        }
        if (cambio.agotados.isNotEmpty()) oyentes.forEach { it() }
        return cambio
    }

    /** Límites de [config] sin tiempo hoy. */
    fun agotados(config: Config): List<Limite> {
        if (config.limites.isEmpty()) return emptyList()
        val hoy = hoy()
        return config.limites.filter { hoy.agotado(it) }
    }

    /** El límite agotado de la app [paquete], o null si no tiene o le queda tiempo. */
    fun appAgotada(paquete: String, config: Config): Limite? {
        val limite = config.limite(TipoLimite.APP, paquete) ?: return null
        return limite.takeIf { hoy().agotado(it) }
    }

    /** Dominios de los sitios sin tiempo hoy. */
    fun sitiosAgotados(config: Config): Set<String> =
        agotados(config).filter { it.tipo == TipoLimite.SITIO }.mapTo(HashSet()) { it.nombre }

    /** El límite agotado que corresponde al sitio [host] (o a un dominio padre), o null. */
    fun sitioAgotado(host: String, config: Config): Limite? =
        agotados(config).firstOrNull { it.tipo == TipoLimite.SITIO && Dominios.bloqueado(host, setOf(it.nombre)) }

    /** Registra un oyente que se llama cuando se agota un límite. Devuelve una función para darlo de baja. */
    fun escuchar(oyente: () -> Unit): () -> Unit {
        oyentes += oyente
        return { oyentes -= oyente }
    }
}
