package com.bloqueador.android.datos

import android.content.Context
import android.content.SharedPreferences
import com.bloqueador.android.dominio.Config
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Guarda la configuración en SharedPreferences y la mantiene en memoria.
 *
 * La interfaz, el servicio de accesibilidad y la VPN corren en el mismo proceso, así que
 * todos leen el mismo objeto [config] (lectura instantánea, sin tocar el disco).
 */
object Almacen {
    private const val ARCHIVO = "bloqueador"
    private const val CLAVE = "config"

    @Volatile
    var config: Config = Config()
        private set

    private var prefs: SharedPreferences? = null
    private val oyentes = CopyOnWriteArrayList<(Config) -> Unit>()

    /** Se llama desde BloqueadorApp.onCreate. Llamarlo varias veces no hace nada. */
    @Synchronized
    fun iniciar(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(ARCHIVO, Context.MODE_PRIVATE)
        prefs = p
        config = Config.desdeJson(p.getString(CLAVE, null))
    }

    /**
     * Aplica [cambio], guarda el resultado y avisa a los oyentes (en el hilo que llama).
     * Devuelve la nueva configuración. Las reglas de "no quitar durante un bloqueo" las
     * aplica quien llama (la interfaz).
     */
    fun actualizar(cambio: (Config) -> Config): Config {
        val nueva = synchronized(this) {
            val n = cambio(config)
            config = n
            prefs?.edit()?.putString(CLAVE, n.aJson())?.apply()
            n
        }
        oyentes.forEach { it(nueva) }
        return nueva
    }

    /** Registra un oyente de cambios. Devuelve una función para darlo de baja. */
    fun escuchar(oyente: (Config) -> Unit): () -> Unit {
        oyentes += oyente
        return { oyentes -= oyente }
    }
}
