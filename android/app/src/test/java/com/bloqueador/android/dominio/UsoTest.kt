package com.bloqueador.android.dominio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

// Pruebas de los límites diarios: configuración, tiempo de uso y reglas para aflojarlos.

class UsoTest {

    private companion object {
        val LUNES: LocalDate = LocalDate.of(2026, 11, 16)
        val INSTAGRAM = Limite(TipoLimite.APP, "com.instagram.android", 30)
        val YOUTUBE = Limite(TipoLimite.SITIO, "youtube.com", 15)
        const val MIN = 60_000L
    }

    // ---------- Config ----------

    @Test
    fun limitesSeGuardanYSeLeen() {
        val config = Config(limites = listOf(INSTAGRAM, YOUTUBE))
        assertEquals(config, Config.desdeJson(config.aJson()))
    }

    @Test
    fun descartaLimitesNoValidosYDejaElMasEstricto() {
        val json = """{"limites": [
            {"tipo": "sitio", "nombre": "YouTube.com", "minutos": 30},
            {"tipo": "sitio", "nombre": "youtube.com", "minutos": 15},
            {"tipo": "app", "nombre": "com.UCMobile.intl", "minutos": 20},
            {"tipo": "app", "nombre": "com.x", "minutos": 0},
            {"tipo": "app", "nombre": "com.y", "minutos": 1440},
            {"tipo": "carpeta", "nombre": "z", "minutos": 10},
            {"tipo": "app", "minutos": 10}
        ]}"""
        assertEquals(
            listOf(Limite(TipoLimite.APP, "com.UCMobile.intl", 20), YOUTUBE),
            Config.desdeJson(json).limites,
        )
    }

    @Test
    fun conLimiteReemplazaYOrdena() {
        val c = Config().conLimite(YOUTUBE).conLimite(INSTAGRAM).conLimite(YOUTUBE.copy(minutos = 10))
        assertEquals(listOf(INSTAGRAM, YOUTUBE.copy(minutos = 10)), c.limites)
        assertEquals(INSTAGRAM, c.limite(TipoLimite.APP, "com.instagram.android"))
        assertNull(c.limite(TipoLimite.SITIO, "com.instagram.android"))
    }

    @Test
    fun configViejaSinLimites() {
        assertEquals(emptyList<Limite>(), Config.desdeJson("""{"sitios": ["a.com"]}""").limites)
    }

    // ---------- UsoDiario ----------

    @Test
    fun sumaYSeAgota() {
        var uso = UsoDiario().alDia(LUNES)
        uso = uso.sumar(INSTAGRAM, 29 * MIN)
        assertFalse(uso.agotado(INSTAGRAM))
        assertEquals(MIN, uso.restantesMs(INSTAGRAM))
        uso = uso.sumar(INSTAGRAM, MIN)
        assertTrue(uso.agotado(INSTAGRAM))
        assertEquals(0L, uso.restantesMs(INSTAGRAM))
        assertFalse(uso.agotado(YOUTUBE))
    }

    @Test
    fun cadaDiaEmpiezaDeCero() {
        val uso = UsoDiario().alDia(LUNES).sumar(INSTAGRAM, 40 * MIN)
        assertTrue(uso.alDia(LUNES).agotado(INSTAGRAM))
        assertFalse(uso.alDia(LUNES.plusDays(1)).agotado(INSTAGRAM))
    }

    @Test
    fun usoSeGuardaYSeLee() {
        val uso = UsoDiario().alDia(LUNES).sumar(INSTAGRAM, 125_000L).sumar(YOUTUBE, 3_000L)
        assertEquals(uso, UsoDiario.desdeJson(uso.aJson()))
        assertEquals(UsoDiario(), UsoDiario.desdeJson("{roto"))
        assertEquals(UsoDiario(), UsoDiario.desdeJson("""{"fecha": "ayer", "ms": {}}"""))
        assertEquals(UsoDiario(), UsoDiario.desdeJson(null))
    }

    @Test
    fun sumarUsoAvisaAlAgotarseYCuandoQuedanCincoMinutos() {
        val base = UsoDiario().alDia(LUNES).sumar(INSTAGRAM, 25 * MIN - 2_000L)
        val antes = sumarUso(base, listOf(INSTAGRAM), 1_000L, LUNES)
        assertEquals(emptyList<Limite>(), antes.porAgotarse)
        val aviso = sumarUso(antes.uso, listOf(INSTAGRAM), 1_000L, LUNES)
        assertEquals(listOf(INSTAGRAM), aviso.porAgotarse)
        val despues = sumarUso(aviso.uso, listOf(INSTAGRAM), 1_000L, LUNES)
        assertEquals(emptyList<Limite>(), despues.porAgotarse)   // una sola vez

        val agotado = sumarUso(despues.uso, listOf(INSTAGRAM), 5 * MIN, LUNES)
        assertEquals(listOf(INSTAGRAM), agotado.agotados)
        assertEquals(emptyList<Limite>(), sumarUso(agotado.uso, listOf(INSTAGRAM), MIN, LUNES).agotados)
        // Un día nuevo empieza de cero.
        assertFalse(sumarUso(agotado.uso, listOf(INSTAGRAM), MIN, LUNES.plusDays(1)).uso.agotado(INSTAGRAM))
    }

    @Test
    fun sinAvisoPreviosEnLimitesCortos() {
        val corto = Limite(TipoLimite.APP, "a.b", 10)
        val c = sumarUso(UsoDiario().alDia(LUNES), listOf(corto), 5 * MIN, LUNES)
        assertEquals(emptyList<Limite>(), c.porAgotarse)
    }

    @Test
    fun limitesEnUsoPorAppYPorSitio() {
        val config = Config(limites = listOf(INSTAGRAM, YOUTUBE))
        assertEquals(listOf(INSTAGRAM), limitesEnUso(config, "com.instagram.android", null))
        assertEquals(listOf(YOUTUBE), limitesEnUso(config, "com.android.chrome", "m.youtube.com"))
        assertEquals(listOf(YOUTUBE), limitesEnUso(config, "com.android.chrome", "www-youtube-com.translate.goog"))
        assertEquals(emptyList<Limite>(), limitesEnUso(config, "com.android.chrome", "notyoutube.com"))
        assertEquals(emptyList<Limite>(), limitesEnUso(config, "com.android.chrome", null))
    }

    @Test
    fun soloSePuedeAflojarSinBloqueoYConTiempo() {
        val hoy = UsoDiario().alDia(LUNES)
        assertTrue(sePuedeAflojar(INSTAGRAM, bloqueoActivo = false, uso = hoy))
        assertFalse(sePuedeAflojar(INSTAGRAM, bloqueoActivo = true, uso = hoy))
        val agotado = hoy.sumar(INSTAGRAM, 30 * MIN)
        assertFalse(sePuedeAflojar(INSTAGRAM, bloqueoActivo = false, uso = agotado))
        assertTrue(sePuedeAflojar(YOUTUBE, bloqueoActivo = false, uso = agotado))
    }

    @Test
    fun minutosDeUnLimite() {
        assertEquals(30, minutosElegidos("", "30", MAX_MINUTOS_LIMITE))
        assertEquals(23 * 60 + 59, minutosElegidos("23", "59", MAX_MINUTOS_LIMITE))
        assertNull(minutosElegidos("24", "0", MAX_MINUTOS_LIMITE))
        assertEquals(24 * 60, minutosElegidos("24", "0"))   // la sesión sí llega a 24 h
    }
}
