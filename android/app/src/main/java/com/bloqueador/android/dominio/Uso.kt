package com.bloqueador.android.dominio

import org.json.JSONException
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** Se avisa cuando a un límite le quedan 5 minutos (si dura más de 10). */
const val AVISO_ANTES_MS = 5 * 60_000L

/**
 * Tiempo de uso de hoy de las apps y los sitios con límite diario. Vuelve a cero cada día a las
 * 00:00. Solo guarda el tiempo de lo que tiene límite: ni otras apps ni las páginas visitadas.
 * Es inmutable: lo leen a la vez el servicio de accesibilidad, la VPN y la interfaz.
 */
data class UsoDiario(
    val fecha: LocalDate? = null,
    val ms: Map<String, Long> = emptyMap(),   // clave del límite -> milisegundos de hoy
) {
    /** El uso de [hoy]: si es de otro día, vacío. */
    fun alDia(hoy: LocalDate): UsoDiario = if (fecha == hoy) this else UsoDiario(hoy)

    fun usadosMs(limite: Limite): Long = ms[limite.clave] ?: 0L

    fun restantesMs(limite: Limite): Long = maxOf(0L, limite.minutos * 60_000L - usadosMs(limite))

    fun agotado(limite: Limite): Boolean = restantesMs(limite) <= 0L

    fun sumar(limite: Limite, extra: Long): UsoDiario = copy(ms = ms + (limite.clave to usadosMs(limite) + extra))

    fun aJson(): String {
        val segundos = JSONObject()
        ms.forEach { (clave, valor) -> segundos.put(clave, valor) }
        return JSONObject().put("fecha", fecha?.toString() ?: "").put("ms", segundos).toString()
    }

    companion object {
        fun desdeJson(texto: String?): UsoDiario {
            if (texto.isNullOrBlank()) return UsoDiario()
            return try {
                val o = JSONObject(texto)
                val fecha = try {
                    LocalDate.parse(o.optString("fecha", ""))
                } catch (e: DateTimeParseException) {
                    return UsoDiario()
                }
                val valores = o.optJSONObject("ms") ?: JSONObject()
                val ms = HashMap<String, Long>()
                for (clave in valores.keys()) {
                    val v = valores.optLong(clave, 0L)
                    if (v > 0L) ms[clave] = v
                }
                UsoDiario(fecha, ms)
            } catch (e: JSONException) {
                UsoDiario()
            }
        }
    }
}

/** Resultado de sumar tiempo de uso: qué límites se agotaron y a cuáles les quedan 5 minutos. */
data class CambioUso(val uso: UsoDiario, val agotados: List<Limite>, val porAgotarse: List<Limite>)

/** Suma [extra] ms a cada límite de [enUso], con el uso de [hoy]. */
fun sumarUso(uso: UsoDiario, enUso: List<Limite>, extra: Long, hoy: LocalDate): CambioUso {
    var nuevo = uso.alDia(hoy)
    val agotados = mutableListOf<Limite>()
    val porAgotarse = mutableListOf<Limite>()
    for (l in enUso) {
        val antes = nuevo.restantesMs(l)
        nuevo = nuevo.sumar(l, extra)
        val despues = nuevo.restantesMs(l)
        if (antes > 0L && despues <= 0L) {
            agotados += l
        } else if (antes > AVISO_ANTES_MS && despues <= AVISO_ANTES_MS && l.minutos * 60_000L > 2 * AVISO_ANTES_MS) {
            porAgotarse += l
        }
    }
    return CambioUso(nuevo, agotados, porAgotarse)
}

/** Los límites de [config] que están en uso con [paquete] en primer plano mostrando [sitio] (si es un navegador). */
fun limitesEnUso(config: Config, paquete: String, sitio: String?): List<Limite> =
    config.limites.filter {
        when (it.tipo) {
            TipoLimite.APP -> it.nombre == paquete
            TipoLimite.SITIO -> sitio != null && Dominios.bloqueado(sitio, setOf(it.nombre))
        }
    }

/**
 * Quitar o aumentar un límite solo se puede sin un bloqueo activo y si hoy no se agotó (así no
 * se puede aflojar justo cuando molesta). Agregarlo o bajarlo se puede siempre.
 */
fun sePuedeAflojar(limite: Limite, bloqueoActivo: Boolean, uso: UsoDiario): Boolean =
    !bloqueoActivo && !uso.agotado(limite)
