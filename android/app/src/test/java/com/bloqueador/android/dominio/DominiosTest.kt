package com.bloqueador.android.dominio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Pruebas de normalización de dominios y de la regla de bloqueo con subdominios.

class DominiosTest {

    private val lista = setOf("youtube.com", "reddit.com")

    // ---------- Dominios.normalizar ----------

    @Test
    fun normalizaDominiosValidos() {
        val casos = mapOf(
            "youtube.com" to "youtube.com",
            "  https://www.YouTube.com/watch?v=abc " to "youtube.com",
            "m.facebook.com/" to "m.facebook.com",
            "bbc.co.uk" to "bbc.co.uk",
            "reddit.com:443" to "reddit.com",
            "youtube.com." to "youtube.com",
            "WWW.NETFLIX.COM" to "netflix.com",
            "http://usuario@twitch.tv/directorio" to "twitch.tv",
            "https://x.com?ref=1" to "x.com",
        )
        for ((entrada, esperado) in casos) {
            assertEquals("entrada: \"$entrada\"", esperado, Dominios.normalizar(entrada))
        }
    }

    @Test
    fun dominioConEnieSeConvierteAPunycode() {
        val d = Dominios.normalizar("ñandú.com")
        assertTrue("obtuvo: $d", d != null && d.startsWith("xn--") && d.endsWith(".com"))
        // Escrito en mayúsculas o ya en punycode, da lo mismo.
        assertEquals(d, Dominios.normalizar("ÑANDÚ.COM"))
        assertEquals(d, Dominios.normalizar(d ?: ""))
    }

    @Test
    fun rechazaDominiosInvalidos() {
        val malos = listOf(
            "", "   ", "hola", "no valido.com", "192.168.0.1", "-malo-.com", "http://",
            "[::1]", "http://[::1]:8080/", "ejemplo..com", "a_b.com", "a".repeat(64) + ".com",
        )
        for (entrada in malos) {
            assertNull("entrada: \"$entrada\"", Dominios.normalizar(entrada))
        }
    }

    @Test
    fun sitiosRapidosYaEstanNormalizados() {
        for ((nombre, dominios) in SITIOS_RAPIDOS) {
            for (d in dominios) {
                assertEquals("$nombre: $d", d, Dominios.normalizar(d))
            }
        }
    }

    // ---------- Dominios.bloqueado ----------

    @Test
    fun bloqueaElDominioYSusSubdominios() {
        assertTrue(Dominios.bloqueado("youtube.com", lista))
        assertTrue(Dominios.bloqueado("m.youtube.com", lista))
        assertTrue(Dominios.bloqueado("www.youtube.com", lista))
        assertTrue(Dominios.bloqueado("a.b.c.youtube.com", lista))
        assertTrue(Dominios.bloqueado("old.reddit.com", lista))
    }

    @Test
    fun noBloqueaDominiosParecidos() {
        assertFalse(Dominios.bloqueado("notyoutube.com", lista))
        assertFalse(Dominios.bloqueado("youtube.com.mx", lista))
        assertFalse(Dominios.bloqueado("youtube.co", lista))
        assertFalse(Dominios.bloqueado("google.com", lista))
        assertFalse(Dominios.bloqueado("com", lista))
        assertFalse(Dominios.bloqueado("", lista))
        assertFalse(Dominios.bloqueado(".", lista))
    }

    @Test
    fun aceptaPuntoFinalYMayusculas() {
        assertTrue(Dominios.bloqueado("youtube.com.", lista))
        assertTrue(Dominios.bloqueado("M.YouTube.COM", lista))
        assertTrue(Dominios.bloqueado("WWW.YOUTUBE.COM.", lista))
    }

    @Test
    fun listaVaciaNoBloqueaNada() {
        assertFalse(Dominios.bloqueado("youtube.com", emptySet()))
    }

    @Test
    fun bloqueaLosSitiosAbiertosConElTraductorDeGoogle() {
        assertEquals("www.reddit.com", Dominios.desdeTraductor("www-reddit-com.translate.goog"))
        assertEquals("mi-sitio.com", Dominios.desdeTraductor("mi--sitio-com.translate.goog"))
        assertNull(Dominios.desdeTraductor("translate.goog"))
        assertNull(Dominios.desdeTraductor("youtube.com"))
        assertTrue(Dominios.bloqueado("www-reddit-com.translate.goog", lista))
        assertTrue(Dominios.bloqueado("m-youtube-com.translate.goog.", lista))
        assertFalse(Dominios.bloqueado("www-google-com.translate.goog", lista))
        assertFalse(Dominios.bloqueado("translate.goog", lista))
    }
}
