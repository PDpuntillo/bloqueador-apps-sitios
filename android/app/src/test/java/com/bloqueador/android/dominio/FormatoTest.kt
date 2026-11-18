package com.bloqueador.android.dominio

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

// Pruebas de los textos de fecha, tiempo restante y plurales.

class FormatoTest {

    private companion object {
        /** Lunes 16/11/2026 a las 00:00. */
        val LUNES: LocalDateTime = LocalDateTime.of(2026, 11, 16, 0, 0)

        /** El día [LUNES] + [dias], a la hora indicada. */
        fun dia(dias: Long, hora: Int = 0, minuto: Int = 0): LocalDateTime =
            LUNES.plusDays(dias).withHour(hora).withMinute(minuto)
    }

    // ---------- cuando() ----------

    @Test
    fun cuandoEsHoy() {
        assertEquals("hoy a las 15:30", cuando(dia(0, 15, 30), dia(0, 8)))
        assertEquals("hoy a las 09:05", cuando(dia(0, 9, 5), LUNES))
    }

    @Test
    fun cuandoEsManana() {
        assertEquals("mañana a las 07:00", cuando(dia(1, 7), dia(0, 23)))
        // Cuenta por fecha, no por 24 horas.
        assertEquals("mañana a las 00:30", cuando(dia(1, 0, 30), dia(0, 23, 59)))
        assertEquals("mañana a las 23:00", cuando(dia(1, 23), dia(0, 1)))
    }

    @Test
    fun cuandoEsOtroDia() {
        assertEquals("el Mié 18/11 a las 09:00", cuando(dia(2, 9), LUNES))
        assertEquals("el Lun 23/11 a las 09:00", cuando(dia(7, 9), LUNES))
        assertEquals("el Mar 01/12 a las 21:45", cuando(dia(15, 21, 45), LUNES))
    }

    // ---------- duracion() ----------

    @Test
    fun duracionConHoras() {
        assertEquals("1 h 05 min", duracion(LUNES, LUNES.plusHours(1).plusMinutes(5)))
        assertEquals("2 h 00 min", duracion(LUNES, LUNES.plusHours(2).plusSeconds(59)))
        assertEquals("25 h 30 min", duracion(LUNES, LUNES.plusHours(25).plusMinutes(30)))
    }

    @Test
    fun duracionSinHoras() {
        assertEquals("12 min 30 s", duracion(LUNES, LUNES.plusMinutes(12).plusSeconds(30)))
        assertEquals("0 min 05 s", duracion(LUNES, LUNES.plusSeconds(5)))
        assertEquals("59 min 59 s", duracion(LUNES, LUNES.plusMinutes(59).plusSeconds(59)))
    }

    @Test
    fun duracionNegativaEsCero() {
        assertEquals("0 min 00 s", duracion(LUNES.plusMinutes(1), LUNES))
        assertEquals("0 min 00 s", duracion(LUNES, LUNES))
    }

    // ---------- plural() ----------

    @Test
    fun pluralSegunLaCantidad() {
        assertEquals("1 sitio", plural(1, "sitio", "sitios"))
        assertEquals("0 sitios", plural(0, "sitio", "sitios"))
        assertEquals("2 sitios", plural(2, "sitio", "sitios"))
        assertEquals("1 aplicación", plural(1, "aplicación", "aplicaciones"))
        assertEquals("3 aplicaciones", plural(3, "aplicación", "aplicaciones"))
    }
}
