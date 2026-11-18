package com.bloqueador.android.dominio

import java.net.IDN

/**
 * Un botón de "Agregar rápido": los dominios propios del servicio (también los de sus videos e
 * imágenes, para que no carguen incrustados en otras páginas) y sus apps, que no pasan por el
 * filtro de sitios y hay que bloquear en la pestaña Apps. Solo dominios exclusivos del servicio:
 * nada compartido como fbcdn.net (Messenger) o googleapis.com (todo Google).
 */
data class SitioRapido(val nombre: String, val dominios: List<String>, val apps: List<String>)

val SITIOS_RAPIDOS: List<SitioRapido> = listOf(
    SitioRapido(
        "YouTube",
        listOf("youtube.com", "youtu.be", "youtube-nocookie.com", "ytimg.com"),
        listOf("com.google.android.youtube", "app.revanced.android.youtube", "com.vanced.android.youtube"),
    ),
    SitioRapido(
        "Instagram",
        listOf("instagram.com", "cdninstagram.com"),
        listOf("com.instagram.android", "com.instagram.lite"),
    ),
    SitioRapido(
        "TikTok",
        listOf("tiktok.com", "tiktokv.com", "tiktokcdn.com", "tiktokcdn-us.com", "musical.ly"),
        listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.zhiliaoapp.musically.go"),
    ),
    SitioRapido(
        "Facebook",
        listOf("facebook.com", "fb.com", "fb.watch"),
        listOf("com.facebook.katana", "com.facebook.lite"),
    ),
    SitioRapido(
        "X / Twitter",
        listOf("x.com", "twitter.com", "twimg.com"),
        listOf("com.twitter.android"),
    ),
    SitioRapido(
        "Reddit",
        listOf("reddit.com", "redd.it", "redditmedia.com", "redditstatic.com"),
        listOf("com.reddit.frontpage"),
    ),
    SitioRapido(
        "Netflix",
        listOf("netflix.com", "nflxvideo.net", "nflxso.net", "nflximg.net", "nflxext.com"),
        listOf("com.netflix.mediaclient"),
    ),
    SitioRapido(
        "Twitch",
        listOf("twitch.tv", "ttvnw.net", "jtvnw.net"),
        listOf("tv.twitch.android.app"),
    ),
)

object Dominios {
    private const val ETIQUETA = "(?!-)[a-z0-9-]{1,63}(?<!-)"
    private val DOMINIO = Regex("^$ETIQUETA(\\.$ETIQUETA)+$")

    /** "https://www.YouTube.com/watch?v=1" -> "youtube.com". Devuelve null si no es válido. */
    fun normalizar(texto: String): String? {
        var resto = texto.trim().lowercase()
        if (resto.isEmpty()) return null
        if ("://" in resto) resto = resto.substringAfter("://")
        resto = resto.takeWhile { it != '/' && it != '?' && it != '#' }
        resto = resto.substringAfterLast('@')
        if (resto.startsWith("[")) return null          // dirección IPv6
        resto = resto.substringBefore(':').trim('.')
        if (resto.isEmpty()) return null
        var host = try {
            IDN.toASCII(resto).lowercase()                // dominios con ñ o tildes
        } catch (e: IllegalArgumentException) {
            return null
        }
        if (host.startsWith("www.")) host = host.removePrefix("www.")
        if (host.length > 253 || !DOMINIO.matches(host)) return null
        if (host.substringAfterLast('.').all { it.isDigit() }) return null   // dirección IP
        return host
    }

    /**
     * True si [nombre] (una consulta DNS) es uno de los dominios [bloqueados] o un subdominio
     * de alguno. Ej.: con "youtube.com" bloqueado, también se bloquea "m.youtube.com".
     */
    fun bloqueado(nombre: String, bloqueados: Set<String>): Boolean {
        if (bloqueados.isEmpty()) return false
        val n = nombre.trimEnd('.').lowercase()
        if (coincide(n, bloqueados)) return true
        // El proxy de Google Traductor abre cualquier sitio con otro nombre:
        // www.reddit.com -> www-reddit-com.translate.goog. Se mira el sitio original.
        val original = desdeTraductor(n) ?: return false
        return coincide(original, bloqueados)
    }

    /** True si [nombre] o alguno de sus dominios padre está en [bloqueados]. */
    private fun coincide(nombre: String, bloqueados: Set<String>): Boolean {
        var n = nombre
        while (n.isNotEmpty()) {
            if (n in bloqueados) return true
            val punto = n.indexOf('.')
            if (punto < 0) return false
            n = n.substring(punto + 1)
        }
        return false
    }

    /**
     * "www-reddit-com.translate.goog" -> "www.reddit.com" (y "mi--sitio-com" -> "mi-sitio.com").
     * Devuelve null si [nombre] no es un sitio abierto con el proxy de Google Traductor.
     */
    internal fun desdeTraductor(nombre: String): String? {
        val sufijo = ".translate.goog"
        if (!nombre.endsWith(sufijo)) return null
        val etiqueta = nombre.removeSuffix(sufijo).substringAfterLast('.')
        if (etiqueta.isEmpty() || '-' !in etiqueta) return null
        return etiqueta.split("--").joinToString("-") { it.replace('-', '.') }
    }
}
