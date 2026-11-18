package com.bloqueador.android.dominio

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

/** Nombres cortos de los días. Índice 0 = lunes ... 6 = domingo (igual que en la versión de Windows). */
val DIAS = listOf("Lun", "Mar", "Mié", "Jue", "Vie", "Sáb", "Dom")

private val HORA = Regex("""^([01]?\d|2[0-3]):([0-5]\d)$""")

/** "9:00" -> "09:00". Devuelve null si el texto no es una hora HH:MM válida. */
fun normalizarHora(texto: String): String? {
    val m = HORA.matchEntire(texto.trim()) ?: return null
    return m.groupValues[1].toInt().toString().padStart(2, '0') + ":" + m.groupValues[2]
}

internal fun minutos(hhmm: String): Int {
    val (h, m) = hhmm.split(":")
    return h.toInt() * 60 + m.toInt()
}

/** Día de la semana como índice: 0 = lunes ... 6 = domingo. */
fun indiceDia(momento: LocalDateTime): Int = momento.dayOfWeek.value - 1

/** Hora local del teléfono -> instante (no cambia si después cambia la zona horaria). */
fun aInstante(momento: LocalDateTime, zona: ZoneId = ZoneId.systemDefault()): Instant =
    momento.atZone(zona).toInstant()

/** Instante -> hora local del teléfono, con la zona horaria de este momento. */
fun aLocal(instante: Instant, zona: ZoneId = ZoneId.systemDefault()): LocalDateTime =
    LocalDateTime.ofInstant(instante, zona)

private const val MINUTOS_SEMANA = 7 * 24 * 60

data class Horario(
    val dias: Set<Int>,   // 0 = lunes ... 6 = domingo
    val inicio: String,   // "HH:MM"
    val fin: String,      // "HH:MM". Si fin <= inicio, termina al día siguiente.
) {
    /** Si el horario está activo en [ahora], devuelve cuándo termina; si no, null. */
    fun finActual(ahora: LocalDateTime): LocalDateTime? {
        val ini = minutos(inicio)
        val f = minutos(fin)
        val m = ahora.hour * 60 + ahora.minute
        val hoy = indiceDia(ahora)
        val medianoche = ahora.toLocalDate().atStartOfDay()
        if (ini < f) {
            return if (hoy in dias && m >= ini && m < f) medianoche.plusMinutes(f.toLong()) else null
        }
        // Cruza la medianoche (ej. 22:00 -> 07:00), o dura 24 h si inicio == fin.
        if (hoy in dias && m >= ini) return medianoche.plusDays(1).plusMinutes(f.toLong())
        if ((hoy + 6) % 7 in dias && m < f) return medianoche.plusMinutes(f.toLong())
        return null
    }

    /** Próximo momento (estrictamente después de [ahora]) en que empieza este horario. */
    fun proximoInicio(ahora: LocalDateTime): LocalDateTime? {
        val ini = minutos(inicio).toLong()
        for (d in 0L..7L) {
            val dia = ahora.toLocalDate().plusDays(d)
            if (dia.dayOfWeek.value - 1 in dias) {
                val momento = dia.atStartOfDay().plusMinutes(ini)
                if (momento.isAfter(ahora)) return momento
            }
        }
        return null
    }

    fun describir(): String {
        val orden = dias.sorted()
        val textoDias = when (orden) {
            (0..6).toList() -> "Todos los días"
            listOf(0, 1, 2, 3, 4) -> "Lun a Vie"
            listOf(5, 6) -> "Sáb y Dom"
            else -> orden.joinToString(", ") { DIAS[it] }
        }
        val horas = when {
            inicio == fin && inicio == "00:00" -> "todo el día"
            inicio == fin -> "24 h desde las $inicio"
            minutos(fin) < minutos(inicio) -> "$inicio → $fin del día siguiente"
            else -> "$inicio → $fin"
        }
        return "$textoDias  ·  $horas"
    }
}

data class Estado(
    val activo: Boolean,
    /**
     * Cuándo termina de verdad el bloqueo actual: si al terminar empieza otro horario sin pausa,
     * cuenta hasta el final de ese también. Es null si no hay bloqueo o si es [permanente].
     */
    val hasta: LocalDateTime?,
    val motivo: String,          // "", "sesión", "horario" o "sesión y horario"
    /** Los horarios cubren todos los minutos de la semana: el bloqueo no termina nunca. */
    val permanente: Boolean = false,
) {
    companion object {
        val LIBRE = Estado(false, null, "")
    }
}

data class Config(
    val sitios: List<String> = emptyList(),     // dominios normalizados, ordenados
    val apps: List<String> = emptyList(),       // nombres de paquete, ordenados
    val horarios: List<Horario> = emptyList(),
    val sesionFin: Instant? = null,             // cuándo termina la sesión (instante: no le afecta la zona horaria)
    val modoEstricto: Boolean = false,          // durante un bloqueo, bloquear también Ajustes y el desinstalador
) {
    fun estado(ahora: LocalDateTime = LocalDateTime.now(), zona: ZoneId = ZoneId.systemDefault()): Estado {
        val finales = mutableListOf<LocalDateTime>()
        val motivos = mutableListOf<String>()
        // La sesión se pasa a hora local en cada evaluación, con la zona horaria de ese momento.
        val sesion = sesionFin?.let { aLocal(it, zona) }
        if (sesion != null && sesion.isAfter(ahora)) {
            finales += sesion
            motivos += "sesión"
        }
        for (h in horarios) {
            val f = h.finActual(ahora) ?: continue
            finales += f
            if ("horario" !in motivos) motivos += "horario"
        }
        if (finales.isEmpty()) return Estado.LIBRE
        if (horariosSinPausa()) return Estado(true, null, motivos.joinToString(" y "), permanente = true)

        // Si al terminar empieza (o sigue) otro horario, el bloqueo continúa sin pausa, y el
        // horario pasa a ser también un motivo. Termina siempre: queda al menos un minuto libre.
        var hasta = finales.reduce { a, b -> if (b.isAfter(a)) b else a }
        for (vuelta in 1..MAX_ENCADENADOS) {
            val siguiente = horarios.mapNotNull { it.finActual(hasta) }
                .reduceOrNull { a, b -> if (b.isAfter(a)) b else a }
            if (siguiente == null || !siguiente.isAfter(hasta)) break
            hasta = siguiente
            if ("horario" !in motivos) motivos += "horario"
        }
        return Estado(true, hasta, motivos.joinToString(" y "))
    }

    /**
     * True si los horarios cubren todos los minutos de la semana. Con eso el bloqueo no
     * terminaría nunca (y no se podría quitar ningún horario), así que la interfaz no lo permite.
     */
    fun horariosSinPausa(): Boolean {
        if (horarios.isEmpty()) return false
        val cubierto = BooleanArray(MINUTOS_SEMANA)
        for (h in horarios) {
            val ini = minutos(h.inicio)
            val f = minutos(h.fin)
            val duracion = if (f > ini) f - ini else f - ini + 24 * 60   // inicio == fin: 24 h
            for (d in h.dias) {
                val desde = d * 24 * 60 + ini
                for (k in 0 until duracion) cubierto[(desde + k) % MINUTOS_SEMANA] = true
            }
        }
        return cubierto.all { it }
    }

    fun proximoInicio(ahora: LocalDateTime): LocalDateTime? =
        horarios.mapNotNull { it.proximoInicio(ahora) }.reduceOrNull { a, b -> if (b.isBefore(a)) b else a }

    fun aJson(): String {
        val horariosJson = JSONArray()
        for (h in horarios) {
            horariosJson.put(
                JSONObject()
                    .put("dias", JSONArray(h.dias.sorted()))
                    .put("inicio", h.inicio)
                    .put("fin", h.fin)
            )
        }
        return JSONObject()
            .put("sitios", JSONArray(sitios))
            .put("apps", JSONArray(apps))
            .put("horarios", horariosJson)
            .put("sesion_fin_ms", sesionFin?.toEpochMilli() ?: JSONObject.NULL)
            .put("modo_estricto", modoEstricto)
            .toString()
    }

    companion object {
        /** Tope de horarios encadenados que se siguen al calcular el fin de un bloqueo. */
        private const val MAX_ENCADENADOS = 1000

        /** Lee la configuración guardada. Si el texto falta o está dañado, devuelve una vacía. */
        fun desdeJson(texto: String?): Config {
            if (texto.isNullOrBlank()) return Config()
            return try {
                val o = JSONObject(texto)
                Config(
                    sitios = cadenas(o.optJSONArray("sitios")).toSortedSet().toList(),
                    apps = cadenas(o.optJSONArray("apps")).toSortedSet().toList(),
                    horarios = leerHorarios(o.optJSONArray("horarios")),
                    sesionFin = leerSesion(o),
                    modoEstricto = o.optBoolean("modo_estricto", false),
                )
            } catch (e: JSONException) {
                Config()
            }
        }

        private fun cadenas(a: JSONArray?): List<String> {
            if (a == null) return emptyList()
            return (0 until a.length()).mapNotNull { i -> a.optString(i, "").trim().takeIf { it.isNotEmpty() } }
        }

        /**
         * La sesión se guarda como milisegundos desde 1970 ("sesion_fin_ms"). Las versiones
         * anteriores la guardaban como hora local ("sesion_fin"); esa se lee con la zona actual.
         */
        private fun leerSesion(o: JSONObject): Instant? {
            val ms = o.optLong("sesion_fin_ms", Long.MIN_VALUE)
            if (ms != Long.MIN_VALUE && !o.isNull("sesion_fin_ms")) return Instant.ofEpochMilli(ms)
            return leerFecha(o.optString("sesion_fin", ""))?.let { aInstante(it) }
        }

        private fun leerFecha(texto: String): LocalDateTime? {
            if (texto.isBlank() || texto == "null") return null
            return try {
                LocalDateTime.parse(texto)
            } catch (e: DateTimeParseException) {
                null
            }
        }

        private fun leerHorarios(a: JSONArray?): List<Horario> {
            if (a == null) return emptyList()
            val lista = mutableListOf<Horario>()
            for (i in 0 until a.length()) {
                val h = a.optJSONObject(i) ?: continue
                val inicio = normalizarHora(h.optString("inicio", "")) ?: continue
                val fin = normalizarHora(h.optString("fin", "")) ?: continue
                val diasJson = h.optJSONArray("dias") ?: continue
                val dias = (0 until diasJson.length()).map { diasJson.optInt(it, -1) }.filter { it in 0..6 }.toSet()
                if (dias.isNotEmpty()) lista += Horario(dias, inicio, fin)
            }
            return lista.distinct()
        }
    }
}
