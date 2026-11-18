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
