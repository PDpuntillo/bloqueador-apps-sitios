package com.bloqueador.android.dominio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

// Pruebas de horarios, estado del bloqueo y JSON (equivalentes a test_nucleo.py de Windows).

class ModeloTest {

    private companion object {
        /** Lunes 16/11/2026 a las 00:00. */
        val LUNES: LocalDateTime = LocalDateTime.of(2026, 11, 16, 0, 0)

        /** El día [LUNES] + [dias], a la hora indicada. */
        fun dia(dias: Long, hora: Int = 0, minuto: Int = 0): LocalDateTime =
            LUNES.plusDays(dias).withHour(hora).withMinute(minuto)
    }

    // ---------- Días ----------

    @Test
    fun lunesEsLunes() {
        assertEquals(0, indiceDia(LUNES))
        assertEquals(4, indiceDia(dia(4)))
        assertEquals(6, indiceDia(dia(6)))
        assertEquals(7, DIAS.size)
        assertEquals("Lun", DIAS[indiceDia(LUNES)])
        assertEquals("Dom", DIAS[indiceDia(dia(6))])
    }

    // ---------- Horarios ----------

    @Test
    fun horarioNormal() {
        val h = Horario(setOf(0, 1, 2, 3, 4), "09:00", "17:00")
        assertEquals(dia(0, 17), h.finActual(dia(0, 10)))
        assertEquals(dia(0, 17), h.finActual(dia(0, 9)))       // empieza justo a las 09:00
        assertNull(h.finActual(dia(0, 8, 59)))
        assertNull(h.finActual(dia(0, 17)))                     // a las 17:00 ya terminó
        assertNull(h.finActual(dia(5, 10)))                     // sábado
    }

    @Test
    fun horarioQueCruzaMedianoche() {
        val h = Horario(setOf(4), "22:00", "07:00")             // viernes por la noche
        val sabado7 = dia(5, 7)
        assertEquals(sabado7, h.finActual(dia(4, 23)))
        assertEquals(sabado7, h.finActual(dia(5, 6)))
        assertNull(h.finActual(dia(4, 6)))                      // el jueves no está marcado
        assertNull(h.finActual(dia(5, 7)))                      // el sábado a las 07:00 ya terminó
        assertNull(h.finActual(dia(4, 21, 59)))
    }

    @Test
    fun horarioDeDiaCompleto() {
        val h = Horario(setOf(5), "00:00", "00:00")
        assertEquals(dia(6), h.finActual(dia(5, 15)))
        assertEquals(dia(6), h.finActual(dia(5, 0)))
        assertNull(h.finActual(dia(6, 1)))                      // domingo
        assertNull(h.finActual(dia(4, 23, 59)))                 // viernes
        assertEquals("Sáb  ·  todo el día", h.describir())
    }

    @Test
    fun horarioDe24HorasDesdeUnaHora() {
        val h = Horario(setOf(0), "08:00", "08:00")
        assertEquals(dia(1, 8), h.finActual(dia(0, 8)))
        assertEquals(dia(1, 8), h.finActual(dia(1, 7, 59)))
        assertNull(h.finActual(dia(0, 7, 59)))
        assertNull(h.finActual(dia(1, 8)))
    }

    @Test
    fun proximoInicioDeUnHorario() {
        val cfg = Config(horarios = listOf(Horario(setOf(0), "09:00", "10:00")))
        assertEquals(dia(0, 9), cfg.proximoInicio(dia(0, 8)))
        assertEquals(dia(7, 9), cfg.proximoInicio(dia(0, 9, 30)))
        assertEquals(dia(7, 9), cfg.proximoInicio(dia(0, 9)))   // estrictamente después de ahora
    }

    @Test
    fun proximoInicioEligeElMasCercano() {
        val cfg = Config(
            horarios = listOf(
                Horario(setOf(6), "09:00", "10:00"),            // domingo
                Horario(setOf(2), "20:00", "21:00"),            // miércoles
            )
        )
        assertEquals(dia(2, 20), cfg.proximoInicio(dia(0, 12)))
        assertEquals(dia(6, 9), cfg.proximoInicio(dia(2, 20, 30)))
        assertNull(Config().proximoInicio(LUNES))
    }

    @Test
    fun normalizaHoras() {
        assertEquals("09:00", normalizarHora("9:00"))
        assertEquals("23:59", normalizarHora("23:59"))
        assertEquals("00:00", normalizarHora(" 0:00 "))
        for (malo in listOf("24:00", "9", "09:5", "ab:cd", "", "12:60", "-1:00")) {
            assertNull("\"$malo\" no debería ser una hora válida", normalizarHora(malo))
        }
    }

    @Test
    fun describeHorarios() {
        assertEquals(
            "Lun a Vie  ·  09:00 → 17:00",
            Horario(setOf(4, 3, 2, 1, 0), "09:00", "17:00").describir(),
        )
        assertEquals(
            "Sáb y Dom  ·  10:00 → 12:30",
            Horario(setOf(5, 6), "10:00", "12:30").describir(),
        )
        assertEquals(
            "Todos los días  ·  todo el día",
            Horario((0..6).toSet(), "00:00", "00:00").describir(),
        )
        assertEquals(
            "Lun, Mié  ·  22:00 → 07:00 del día siguiente",
            Horario(setOf(2, 0), "22:00", "07:00").describir(),
        )
        assertEquals(
            "Dom  ·  24 h desde las 08:00",
            Horario(setOf(6), "08:00", "08:00").describir(),
        )
    }

    // ---------- Estado del bloqueo ----------

    @Test
    fun sinNadaNoHayBloqueo() {
        assertEquals(Estado.LIBRE, Config().estado(LUNES))
        val e = Config(sitios = listOf("youtube.com"), sesionFin = aInstante(LUNES.minusMinutes(1))).estado(LUNES)
        assertFalse(e.activo)
        assertNull(e.hasta)
        assertEquals("", e.motivo)
    }

    @Test
    fun sesionDeConcentracion() {
        val ahora = dia(0, 12)
        val cfg = Config(sesionFin = aInstante(ahora.plusMinutes(30)))
        val e = cfg.estado(ahora)
        assertTrue(e.activo)
        assertEquals("sesión", e.motivo)
        assertEquals(ahora.plusMinutes(30), e.hasta)
        assertFalse(cfg.estado(ahora.plusMinutes(31)).activo)
        assertFalse(cfg.estado(ahora.plusMinutes(30)).activo)   // termina justo a esa hora
    }

    @Test
    fun soloHorario() {
        val cfg = Config(horarios = listOf(Horario(setOf(0), "09:00", "12:00")))
        val e = cfg.estado(dia(0, 10))
        assertTrue(e.activo)
        assertEquals("horario", e.motivo)
        assertEquals(dia(0, 12), e.hasta)
        assertFalse(cfg.estado(dia(1, 10)).activo)               // martes
    }

    @Test
    fun sesionYHorarioUsaElFinalMasTardio() {
        val ahora = dia(0, 10)
        val cfg = Config(
            sesionFin = aInstante(ahora.plusMinutes(30)),
            horarios = listOf(Horario(setOf(0), "09:00", "12:00")),
        )
        val e = cfg.estado(ahora)
        assertTrue(e.activo)
        assertEquals("sesión y horario", e.motivo)
        assertEquals(dia(0, 12), e.hasta)

        // Si la sesión termina después que el horario, manda la sesión.
        val larga = cfg.copy(sesionFin = aInstante(dia(0, 14)))
        assertEquals("sesión y horario", larga.estado(ahora).motivo)
        assertEquals(dia(0, 14), larga.estado(ahora).hasta)
    }

    @Test
    fun dosHorariosALaVezCuentanComoUnMotivo() {
        val cfg = Config(
            horarios = listOf(
                Horario(setOf(0), "09:00", "11:00"),
                Horario(setOf(0), "10:00", "13:00"),
            )
        )
        val e = cfg.estado(dia(0, 10, 30))
        assertEquals("horario", e.motivo)
        assertEquals(dia(0, 13), e.hasta)
    }

    // ---------- JSON ----------

    @Test
    fun jsonIdaYVuelta() {
        val cfg = Config(
            sitios = listOf("reddit.com", "youtube.com"),
            apps = listOf("com.discord", "com.instagram.android"),
            horarios = listOf(
                Horario(setOf(0, 2), "22:00", "07:00"),
                Horario(setOf(5), "00:00", "00:00"),
            ),
            sesionFin = aInstante(dia(0, 12, 30)),
            modoEstricto = true,
        )
        assertEquals(cfg, Config.desdeJson(cfg.aJson()))
    }

    @Test
    fun jsonIdaYVueltaSinSesion() {
        val cfg = Config(sitios = listOf("youtube.com"), apps = listOf("com.zhiliaoapp.musically"))
        val leida = Config.desdeJson(cfg.aJson())
        assertEquals(cfg, leida)
        assertNull(leida.sesionFin)
        assertFalse(leida.modoEstricto)
    }

    @Test
    fun jsonDanadoDaConfigVacia() {
        for (texto in listOf("{esto no es json", "", "   ", "[1, 2, 3]", "null", "{\"sitios\": [")) {
            assertEquals("texto: $texto", Config(), Config.desdeJson(texto))
        }
        assertEquals(Config(), Config.desdeJson(null))
    }

    @Test
    fun jsonDescartaDatosInvalidos() {
        val texto = """
            {
              "sitios": ["youtube.com", " ", "reddit.com", "youtube.com"],
              "apps": ["com.b", "com.a"],
              "horarios": [
                {"dias": [1, 0], "inicio": "9:00", "fin": "17:00"},
                {"dias": [], "inicio": "09:00", "fin": "10:00"},
                {"dias": [9], "inicio": "09:00", "fin": "10:00"},
                {"dias": [2], "inicio": "25:00", "fin": "10:00"},
                {"dias": [3], "inicio": "09:00"},
                "no es un horario"
              ],
              "sesion_fin": "esto no es una fecha",
              "modo_estricto": true
            }
        """.trimIndent()
        val cfg = Config.desdeJson(texto)
        assertEquals(listOf("reddit.com", "youtube.com"), cfg.sitios)   // ordenados y sin repetir
        assertEquals(listOf("com.a", "com.b"), cfg.apps)
        assertEquals(listOf(Horario(setOf(0, 1), "09:00", "17:00")), cfg.horarios)
        assertNull(cfg.sesionFin)
        assertTrue(cfg.modoEstricto)
    }

    @Test
    fun jsonLeeFechasConSegundosYSesionNula() {
        // Formato que escribe la versión de Windows (con segundos) y campos desconocidos.
        val conSegundos = Config.desdeJson("""{"sesion_fin": "2026-11-16T12:30:00", "autoinicio": true}""")
        assertEquals(aInstante(dia(0, 12, 30)), conSegundos.sesionFin)
        assertNull(Config.desdeJson("""{"sesion_fin": null}""").sesionFin)
        assertNull(Config.desdeJson("""{"sesion_fin": "null"}""").sesionFin)
        assertNull(Config.desdeJson("{}").sesionFin)
    }

    // ---------- Bloqueos encadenados y permanentes ----------

    @Test
    fun horarioQueEmpiezaAlTerminarOtroAlargaElBloqueo() {
        val cfg = Config(
            horarios = listOf(
                Horario(setOf(0), "09:00", "12:00"),
                Horario(setOf(0), "12:00", "15:00"),
                Horario(setOf(0), "15:01", "16:00"),   // un minuto libre: ya no encadena
            )
        )
        val e = cfg.estado(dia(0, 10))
        assertTrue(e.activo)
        assertFalse(e.permanente)
        assertEquals(dia(0, 15), e.hasta)
    }

    @Test
    fun sesionQueTerminaCuandoEmpiezaUnHorarioSeEncadena() {
        val cfg = Config(
            sesionFin = aInstante(dia(0, 9)),
            horarios = listOf(Horario(setOf(0), "09:00", "12:00")),
        )
        val e = cfg.estado(dia(0, 8))
        assertEquals("sesión y horario", e.motivo)   // el horario alarga la sesión
        assertEquals(dia(0, 12), e.hasta)
        assertEquals("sesión", cfg.copy(horarios = emptyList()).estado(dia(0, 8)).motivo)
    }

    @Test
    fun nochesEncadenadasHastaElFinDeSemana() {
        // 22:00 -> 07:00 y 07:00 -> 22:00 de lunes a viernes: bloqueo continuo hasta el sábado.
        val cfg = Config(
            horarios = listOf(
                Horario(setOf(0, 1, 2, 3, 4), "22:00", "07:00"),
                Horario(setOf(0, 1, 2, 3, 4), "07:00", "22:00"),
            )
        )
        assertFalse(cfg.horariosSinPausa())
        val e = cfg.estado(dia(0, 8))
        assertEquals(dia(5, 7), e.hasta)    // el viernes a las 22:00 sigue hasta el sábado 07:00
        assertFalse(e.permanente)
    }

    @Test
    fun horariosQueCubrenTodaLaSemanaSonPermanentes() {
        val todos = (0..6).toSet()
        val veinticuatro = Config(horarios = listOf(Horario(todos, "08:00", "08:00")))
        assertTrue(veinticuatro.horariosSinPausa())
        val e = veinticuatro.estado(dia(2, 3))
        assertTrue(e.activo)
        assertTrue(e.permanente)
        assertNull(e.hasta)

        val dosMitades = Config(
            horarios = listOf(Horario(todos, "00:00", "12:00"), Horario(todos, "12:00", "00:00"))
        )
        assertTrue(dosMitades.horariosSinPausa())

        val faltaElDomingo = Config(horarios = listOf(Horario((0..5).toSet(), "00:00", "00:00")))
        assertFalse(faltaElDomingo.horariosSinPausa())
        assertEquals(dia(6, 0), faltaElDomingo.estado(dia(0, 10)).hasta)
        assertFalse(Config().horariosSinPausa())
    }

    @Test
    fun laSesionNoCambiaConLaZonaHoraria() {
        val madrid = ZoneId.of("Europe/Madrid")
        val fin = aInstante(dia(0, 12), madrid)
        val cfg = Config(sesionFin = fin)
        assertTrue(cfg.estado(dia(0, 11, 30), madrid).activo)
        // El mismo instante en Buenos Aires son las 08:00: a las 08:30 locales ya terminó.
        val baires = ZoneId.of("America/Argentina/Buenos_Aires")
        assertFalse(cfg.estado(dia(0, 8, 30), baires).activo)
        assertEquals(dia(0, 8), cfg.estado(dia(0, 7), baires).hasta)
        assertEquals(dia(0, 12), aLocal(fin, madrid))
        assertEquals(dia(0, 11), aLocal(fin, ZoneOffset.UTC))
    }

    @Test
    fun jsonGuardaLaSesionComoInstante() {
        val fin = aInstante(dia(0, 12, 30))
        val texto = Config(sesionFin = fin).aJson()
        assertTrue(texto.contains("sesion_fin_ms"))
        assertEquals(fin, Config.desdeJson(texto).sesionFin)
        assertNull(Config.desdeJson("""{"sesion_fin_ms": null}""").sesionFin)
        assertNull(Config.desdeJson("""{"sesion_fin_ms": "no"}""").sesionFin)
    }
}
