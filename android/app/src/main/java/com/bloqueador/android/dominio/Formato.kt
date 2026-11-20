package com.bloqueador.android.dominio

import java.time.Duration
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

private fun dos(n: Number): String = n.toString().padStart(2, '0')

/** "hoy a las 15:30", "mañana a las 07:00" o "el Lun 24/11 a las 09:00". */
fun cuando(momento: LocalDateTime, ahora: LocalDateTime): String {
    val hora = "${dos(momento.hour)}:${dos(momento.minute)}"
    return when (ChronoUnit.DAYS.between(ahora.toLocalDate(), momento.toLocalDate())) {
        0L -> "hoy a las $hora"
        1L -> "mañana a las $hora"
        else -> "el ${DIAS[indiceDia(momento)]} ${dos(momento.dayOfMonth)}/${dos(momento.monthValue)} a las $hora"
    }
}

/** Tiempo que falta: "1 h 05 min" o "12 min 30 s". */
fun duracion(desde: LocalDateTime, hasta: LocalDateTime): String {
    val s = maxOf(0L, Duration.between(desde, hasta).seconds)
    val h = s / 3600
    val m = (s % 3600) / 60
    return if (h > 0) "$h h ${dos(m)} min" else "$m min ${dos(s % 60)} s"
}

fun plural(n: Int, singular: String, varios: String): String = "$n ${if (n == 1) singular else varios}"

/** Lo más largo que puede durar una sesión de concentración, en minutos (24 horas). */
const val MAX_MINUTOS_SESION = 24 * 60

/** Duración corta para un botón: "45 min", "2 h" o "1 h 30 min". */
fun etiquetaMinutos(total: Int): String {
    val h = total / 60
    val m = total % 60
    return when {
        h == 0 -> "$m min"
        m == 0 -> "$h h"
        else -> "$h h $m min"
    }
}

/**
 * Minutos con lo escrito en las casillas de horas y minutos (vacía cuenta como 0).
 * Null si no vale: los minutos van de 0 a 59 y el total, de 1 minuto a [maximo] (24 horas).
 */
fun minutosElegidos(horas: String, minutos: String, maximo: Int = MAX_MINUTOS_SESION): Int? {
    val h = if (horas.isBlank()) 0 else horas.trim().toIntOrNull() ?: return null
    val m = if (minutos.isBlank()) 0 else minutos.trim().toIntOrNull() ?: return null
    if (h !in 0..24 || m !in 0..59) return null
    return (h * 60 + m).takeIf { it in 1..maximo }
}
