package com.bloqueador.android.dominio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

// Pruebas de la cuenta regresiva: formato, qué se muestra y cómo se guardan sus opciones.

class CuentaTest {

    private companion object {
        val LUNES: LocalDate = LocalDate.of(2026, 11, 16)
        val INSTAGRAM = Limite(TipoLimite.APP, "com.instagram.android", 30)
        val YOUTUBE = Limite(TipoLimite.SITIO, "youtube.com", 60)
        const val MIN = 60_000L
    }

    @Test
    fun formatoDelTiempoQueQueda() {
        val casos = mapOf(
            1_499_000L to "24:59",
            1_499_200L to "25:00",   // redondea hacia arriba
            59_200L to "1:00",
            400L to "0:01",
            0L to "0:00",
            -3_000L to "0:00",
            3_600_000L to "1:00:00",
            3_723_000L to "1:02:03",
        )
        casos.forEach { (ms, texto) -> assertEquals("$ms ms", texto, textoRestante(ms)) }
    }

    @Test
    fun lineasYAviso() {
        val lineas = listOf(LineaCuenta("Instagram", 5 * MIN), LineaCuenta("youtube.com", 60 * MIN))
        assertEquals("Instagram   5:00\nyoutube.com   1:00:00", textoCuenta(lineas))
        assertTrue(enAviso(lineas))
        assertFalse(enAviso(lineas.drop(1)))
    }

    @Test
    fun restantesDescuentaLoPendienteYOrdena() {
        val uso = UsoDiario().alDia(LUNES).sumar(INSTAGRAM, 20 * MIN)
        val r = restantesEnPantalla(listOf(YOUTUBE, INSTAGRAM), uso, 2_000L)
        assertEquals(listOf(INSTAGRAM to 10 * MIN - 2_000L, YOUTUBE to 60 * MIN - 2_000L), r)
        // Nunca baja de cero, y lo pendiente negativo no suma.
        assertEquals(0L, restantesEnPantalla(listOf(INSTAGRAM), uso, 11 * MIN).single().second)
        assertEquals(10 * MIN, restantesEnPantalla(listOf(INSTAGRAM), uso, -5_000L).single().second)
    }

    @Test
    fun opcionesSeGuardanYSeLeen() {
        val config = Config(cuenta = AjustesCuenta(false, TamanoCuenta.GRANDE, PosicionCuenta.ABAJO_IZQ))
        assertEquals(config, Config.desdeJson(config.aJson()))
    }

    @Test
    fun opcionesNoValidasOViejasQuedanDeFabrica() {
        assertEquals(AjustesCuenta(true, TamanoCuenta.MEDIANO, PosicionCuenta.ARRIBA_DER), Config().cuenta)
        assertEquals(AjustesCuenta(), Config.desdeJson("""{"sitios": ["a.com"]}""").cuenta)
        assertEquals(
            AjustesCuenta(visible = false),
            Config.desdeJson("""{"cuenta": {"visible": false, "tamano": "enorme", "posicion": "centro"}}""").cuenta,
        )
        assertEquals(AjustesCuenta(), Config.desdeJson("""{"cuenta": "roto"}""").cuenta)
    }

    @Test
    fun posicionesSonLasCuatroEsquinas() {
        assertEquals(
            setOf(true to true, true to false, false to true, false to false),
            PosicionCuenta.entries.map { it.arriba to it.izquierda }.toSet(),
        )
    }
}
