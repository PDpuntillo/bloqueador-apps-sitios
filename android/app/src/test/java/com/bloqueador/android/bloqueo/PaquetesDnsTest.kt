package com.bloqueador.android.bloqueo

import com.bloqueador.android.dominio.Dominios
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.random.Random

class PaquetesDnsTest {

    private val ipCliente = byteArrayOf(10, 111, 222.toByte(), 1)
    private val ipServidor = byteArrayOf(10, 111, 222.toByte(), 2)
    private val puertoCliente = 40_000

    /** Registro OPT (EDNS): nombre raíz, tipo 41, tamaño 4096, TTL 0, sin datos. */
    private val opt = byteArrayOf(0, 0, 41, 0x10, 0, 0, 0, 0, 0, 0, 0)

    // --- Construcción de paquetes a mano -------------------------------------------------

    private fun u16(d: ByteArray, i: Int): Int = ((d[i].toInt() and 0xFF) shl 8) or (d[i + 1].toInt() and 0xFF)

    private fun <T> noNulo(valor: T?): T {
        assertNotNull(valor)
        return valor!!
    }

    /** "www.youtube.com" -> 3 www 7 youtube 3 com 0. */
    private fun codificarNombre(nombre: String): ByteArray {
        val s = ByteArrayOutputStream()
        if (nombre.isNotEmpty()) {
            for (etiqueta in nombre.split('.')) {
                val b = etiqueta.toByteArray(Charsets.US_ASCII)
                s.write(b.size)
                s.write(b, 0, b.size)
            }
        }
        s.write(0)
        return s.toByteArray()
    }

    /** Pregunta: nombre + tipo + clase IN. */
    private fun pregunta(nombre: String, tipo: Int = 1): ByteArray =
        codificarNombre(nombre) + byteArrayOf((tipo shr 8).toByte(), tipo.toByte(), 0, 1)

    /** Encabezado DNS (12 bytes) seguido de [resto]. */
    private fun mensaje(
        resto: ByteArray,
        id: Int = 0xBEEF,
        banderas: Int = 0x0100,
        qd: Int = 1,
        ar: Int = 0,
    ): ByteArray {
        val encabezado = byteArrayOf(
            (id shr 8).toByte(), id.toByte(),
            (banderas shr 8).toByte(), banderas.toByte(),
            (qd shr 8).toByte(), qd.toByte(),
            0, 0,
            0, 0,
            (ar shr 8).toByte(), ar.toByte(),
        )
        return encabezado + resto
    }

    /** Consulta estándar con RD=1 y, por defecto, un registro OPT como manda Android. */
    private fun consultaDns(
        nombre: String,
        tipo: Int = 1,
        id: Int = 0xBEEF,
        banderas: Int = 0x0100,
        conEdns: Boolean = true,
    ): ByteArray =
        if (conEdns) mensaje(pregunta(nombre, tipo) + opt, id, banderas, ar = 1)
        else mensaje(pregunta(nombre, tipo), id, banderas)

    /** Paquete IPv4/UDP de 10.111.222.1:40000 a 10.111.222.2:[puertoDestino]. */
    private fun paqueteIp(carga: ByteArray, protocolo: Int = 17, puertoDestino: Int = 53): ByteArray {
        val largoUdp = 8 + carga.size
        val total = 20 + largoUdp
        val p = ByteArray(total)
        p[0] = 0x45
        p[2] = (total shr 8).toByte()
        p[3] = total.toByte()
        p[4] = 0x12
        p[5] = 0x34
        p[8] = 64
        p[9] = protocolo.toByte()
        System.arraycopy(ipCliente, 0, p, 12, 4)
        System.arraycopy(ipServidor, 0, p, 16, 4)
        val ck = complemento(suma(p, 0, 20))
        p[10] = (ck shr 8).toByte()
        p[11] = ck.toByte()
        p[20] = (puertoCliente shr 8).toByte()
        p[21] = puertoCliente.toByte()
        p[22] = (puertoDestino shr 8).toByte()
        p[23] = puertoDestino.toByte()
        p[24] = (largoUdp shr 8).toByte()
        p[25] = largoUdp.toByte()
        System.arraycopy(carga, 0, p, 28, carga.size)   // checksum UDP 0: "sin checksum" en IPv4
        return p
    }

    // --- Checksum de Internet, implementado aparte para verificar ---------------------------

    private fun suma(d: ByteArray, inicio: Int, largo: Int): Long {
        var s = 0L
        for (i in 0 until largo) {
            val b = d[inicio + i].toLong() and 0xFF
            s += if (i % 2 == 0) b shl 8 else b
        }
        return s
    }

    private fun plegar(valor: Long): Int {
        var s = valor
        while (s > 0xFFFF) s = (s and 0xFFFF) + (s shr 16)
        return s.toInt()
    }

    private fun complemento(s: Long): Int = plegar(s).inv() and 0xFFFF

    /** Comprueba encabezados, inversión de direcciones y checksums de una respuesta envuelta. */
    private fun verificarRespuesta(p: ByteArray, dns: ByteArray) {
        assertEquals(28 + dns.size, p.size)
        assertEquals(0x45, p[0].toInt() and 0xFF)
        assertEquals(p.size, u16(p, 2))
        assertEquals(0, u16(p, 6) and 0x3FFF)                    // sin fragmentar
        assertTrue((p[8].toInt() and 0xFF) > 0)                  // TTL
        assertEquals(17, p[9].toInt() and 0xFF)
        assertArrayEquals(ipServidor, p.copyOfRange(12, 16))     // IPs invertidas
        assertArrayEquals(ipCliente, p.copyOfRange(16, 20))
        assertEquals(53, u16(p, 20))                             // puertos invertidos
        assertEquals(puertoCliente, u16(p, 22))
        assertEquals(8 + dns.size, u16(p, 24))
        assertArrayEquals(dns, p.copyOfRange(28, p.size))

        // Checksum IPv4: recalculado con el campo en cero, y la suma total debe dar 0xFFFF.
        val ip = p.copyOfRange(0, 20)
        ip[10] = 0
        ip[11] = 0
        assertEquals(complemento(suma(ip, 0, 20)), u16(p, 10))
        assertEquals(0xFFFF, plegar(suma(p, 0, 20)))

        // Checksum UDP con pseudo-encabezado (IP origen, IP destino, protocolo, largo UDP).
        val udp = p.copyOfRange(20, p.size)
        udp[6] = 0
        udp[7] = 0
        val pseudo = suma(p, 12, 8) + 17 + udp.size
        val calculado = complemento(pseudo + suma(udp, 0, udp.size))
        assertEquals(if (calculado == 0) 0xFFFF else calculado, u16(p, 26))
        assertNotEquals(0, u16(p, 26))
        assertEquals(0xFFFF, plegar(pseudo + suma(p, 20, p.size - 20)))
    }

    // --- Pruebas ----------------------------------------------------------------------------

    @Test
    fun analizaConsultaTipoAParaYoutube() {
        val dns = consultaDns("www.youtube.com")
        val c = noNulo(PaquetesDns.analizar(paqueteIp(dns)))
        assertArrayEquals(ipCliente, c.ipOrigen)
        assertArrayEquals(ipServidor, c.ipDestino)
        assertEquals(puertoCliente, c.puertoOrigen)
        assertEquals(53, c.puertoDestino)
        assertArrayEquals(dns, c.dns)
        assertEquals("www.youtube.com", PaquetesDns.nombreConsultado(c.dns))
    }

    @Test
    fun nombreEnMinusculas() {
        assertEquals("www.youtube.com", PaquetesDns.nombreConsultado(consultaDns("WwW.YouTube.COM")))
        assertEquals("", PaquetesDns.nombreConsultado(mensaje(byteArrayOf(0, 0, 2, 0, 1))))   // raíz
    }

    @Test
    fun respetaLongitudYRellenoDelBuffer() {
        val dns = consultaDns("m.youtube.com", tipo = 28)
        val p = paqueteIp(dns)
        // El lector usa un buffer grande: lo que sigue al paquete es basura.
        val buffer = p + ByteArray(500) { 0x5A }
        val c = noNulo(PaquetesDns.analizar(buffer, p.size))
        assertArrayEquals(dns, c.dns)
        // Si el largo UDP es menor que el IP, se usa el UDP (lo demás es relleno).
        val conRelleno = paqueteIp(dns + byteArrayOf(9, 9, 9))
        conRelleno[24] = ((8 + dns.size) shr 8).toByte()
        conRelleno[25] = (8 + dns.size).toByte()
        assertArrayEquals(dns, noNulo(PaquetesDns.analizar(conRelleno)).dns)
    }

    @Test
    fun nxdomainCorrecto() {
        // AA y CD encendidos en la consulta para comprobar que no se copian.
        val dns = consultaDns("www.youtube.com", id = 0xBEEF, banderas = 0x0100 or 0x0400 or 0x0010)
        val r = noNulo(PaquetesDns.respuestaNxdomain(dns))
        val q = pregunta("www.youtube.com")
        assertEquals(12 + q.size, r.size)                    // sin OPT/EDNS
        assertEquals(0xBEEF, u16(r, 0))                      // mismo ID
        val banderas = u16(r, 2)
        assertEquals(1, banderas shr 15)                     // QR
        assertEquals(0, (banderas shr 11) and 0xF)           // opcode
        assertEquals(0, (banderas shr 10) and 1)             // AA
        assertEquals(0, (banderas shr 9) and 1)              // TC
        assertEquals(1, (banderas shr 8) and 1)              // RD
        assertEquals(1, (banderas shr 7) and 1)              // RA
        assertEquals(3, banderas and 0xF)                    // RCODE = NXDOMAIN
        assertEquals(0x8183, banderas)
        assertEquals(1, u16(r, 4))                           // QDCOUNT
        assertEquals(0, u16(r, 6))                           // ANCOUNT
        assertEquals(0, u16(r, 8))                           // NSCOUNT
        assertEquals(0, u16(r, 10))                          // ARCOUNT
        assertArrayEquals(q, r.copyOfRange(12, r.size))
        assertEquals("www.youtube.com", PaquetesDns.nombreConsultado(r))
    }

    @Test
    fun nxdomainConservaOpcodeRdYMayusculas() {
        val conOpcode = noNulo(PaquetesDns.respuestaNxdomain(consultaDns("a.com", banderas = (2 shl 11) or 0x0100)))
        assertEquals(0x91, conOpcode[2].toInt() and 0xFF)
        val sinRd = noNulo(PaquetesDns.respuestaNxdomain(consultaDns("a.com", banderas = 0)))
        assertEquals(0x80, sinRd[2].toInt() and 0xFF)
        assertEquals(0x83, sinRd[3].toInt() and 0xFF)
        // La pregunta se copia tal cual (algunos resolvedores mezclan mayúsculas y lo verifican).
        val mixta = noNulo(PaquetesDns.respuestaNxdomain(consultaDns("YouTube.Com", tipo = 65)))
        assertArrayEquals(pregunta("YouTube.Com", tipo = 65), mixta.copyOfRange(12, mixta.size))
    }

    @Test
    fun envolverInvierteDireccionesYCalculaChecksums() {
        val consulta = noNulo(PaquetesDns.analizar(paqueteIp(consultaDns("www.youtube.com"))))
        val dns = noNulo(PaquetesDns.respuestaNxdomain(consulta.dns))   // largo impar (33)
        assertEquals(1, dns.size % 2)
        val p = noNulo(PaquetesDns.envolverRespuesta(consulta, dns))
        verificarRespuesta(p, dns)
        // Va al puerto del cliente, no al 53: no se confunde con una consulta.
        assertNull(PaquetesDns.analizar(p))

        val par = ByteArray(40) { (it * 7).toByte() }
        verificarRespuesta(noNulo(PaquetesDns.envolverRespuesta(consulta, par)), par)

        // Solo los primeros bytes de un buffer más grande.
        val buffer = dns + ByteArray(100)
        verificarRespuesta(noNulo(PaquetesDns.envolverRespuesta(consulta, buffer, dns.size)), dns)
    }

    @Test
    fun checksumUdpCeroSeEnviaComoFFFF() {
        val consulta = noNulo(PaquetesDns.analizar(paqueteIp(consultaDns("a.com"))))
        val carga = ByteArray(16) { (it + 1).toByte() }
        carga[14] = 0
        carga[15] = 0
        // Con la última palabra en 0, el checksum vale c = ~S. Si esa palabra vale c, la suma
        // queda en 0xFFFF y el checksum calculado sería 0, que debe enviarse como 0xFFFF.
        val c = u16(noNulo(PaquetesDns.envolverRespuesta(consulta, carga)), 26)
        carga[14] = (c shr 8).toByte()
        carga[15] = c.toByte()
        val p = noNulo(PaquetesDns.envolverRespuesta(consulta, carga))
        assertEquals(0xFFFF, u16(p, 26))
        verificarRespuesta(p, carga)
    }

    @Test
    fun envolverRechazaCargasDemasiadoGrandes() {
        val consulta = noNulo(PaquetesDns.analizar(paqueteIp(consultaDns("a.com"))))
        assertNull(PaquetesDns.envolverRespuesta(consulta, ByteArray(PaquetesDns.MAX_CARGA_UDP + 1)))
        assertNull(PaquetesDns.envolverRespuesta(consulta, ByteArray(10), 11))
        assertNull(PaquetesDns.envolverRespuesta(consulta, ByteArray(10), -1))
        val maxima = ByteArray(PaquetesDns.MAX_CARGA_UDP) { it.toByte() }
        val p = noNulo(PaquetesDns.envolverRespuesta(consulta, maxima))
        assertEquals(65535, p.size)
        verificarRespuesta(p, maxima)
    }

    @Test
    fun paquetesTruncadosDevuelvenNull() {
        val p = paqueteIp(consultaDns("www.youtube.com"))
        for (n in 0 until p.size) {
            assertNull("largo $n", PaquetesDns.analizar(p.copyOf(n)))
            assertNull("largo $n", PaquetesDns.analizar(p, n))
        }
        assertNull(PaquetesDns.analizar(p, -1))
        assertNotNull(PaquetesDns.analizar(p, p.size + 100))     // largo mayor que el arreglo

        // Mensaje DNS cortado antes del final de la pregunta.
        val dns = consultaDns("www.youtube.com", conEdns = false)
        for (n in 0 until dns.size) {
            assertNull("largo $n", PaquetesDns.nombreConsultado(dns.copyOf(n)))
            assertNull("largo $n", PaquetesDns.respuestaNxdomain(dns.copyOf(n)))
        }

        // Largo UDP mayor que el paquete, o largo IP menor que los encabezados.
        val udpLargo = p.copyOf().also { it[24] = 0x7F }
        assertNull(PaquetesDns.analizar(udpLargo))
        val ipCorto = p.copyOf().also { it[2] = 0; it[3] = 20 }
        assertNull(PaquetesDns.analizar(ipCorto))
        val ihlInvalido = p.copyOf().also { it[0] = 0x44 }
        assertNull(PaquetesDns.analizar(ihlInvalido))
    }

    @Test
    fun ipv6TcpOtrosPuertosYFragmentosDevuelvenNull() {
        val dns = consultaDns("www.youtube.com")
        assertNull(PaquetesDns.analizar(paqueteIp(dns).also { it[0] = 0x60 }))

        // IPv6 de verdad: 40 bytes de encabezado, siguiente cabecera UDP, puerto 53.
        val v6 = ByteArray(40 + 8 + dns.size)
        v6[0] = 0x60
        v6[4] = ((8 + dns.size) shr 8).toByte()
        v6[5] = (8 + dns.size).toByte()
        v6[6] = 17
        v6[7] = 64
        v6[42] = 0
        v6[43] = 53
        System.arraycopy(dns, 0, v6, 48, dns.size)
        assertNull(PaquetesDns.analizar(v6))

        assertNull(PaquetesDns.analizar(paqueteIp(dns, protocolo = 6)))        // TCP
        assertNull(PaquetesDns.analizar(paqueteIp(dns, protocolo = 1)))        // ICMP
        assertNull(PaquetesDns.analizar(paqueteIp(dns, puertoDestino = 853)))  // DNS sobre TLS
        assertNull(PaquetesDns.analizar(paqueteIp(dns, puertoDestino = 443)))
        assertNull(PaquetesDns.analizar(paqueteIp(dns).also { it[6] = 0x20 }))  // MF: más fragmentos
        assertNull(PaquetesDns.analizar(paqueteIp(dns).also { it[7] = 1 }))     // desplazamiento != 0
        assertNotNull(PaquetesDns.analizar(paqueteIp(dns).also { it[6] = 0x40 })) // DF es válido
    }

    @Test
    fun cantidadDePreguntasDistintaDeUnoEsMalformada() {
        assertNull(PaquetesDns.nombreConsultado(mensaje(pregunta("a.com"), qd = 0)))
        assertNull(PaquetesDns.nombreConsultado(mensaje(pregunta("a.com") + pregunta("b.com"), qd = 2)))
        assertNull(PaquetesDns.respuestaNxdomain(mensaje(pregunta("a.com"), qd = 0)))
    }

    @Test
    fun nombresDemasiadoLargosOEtiquetasInvalidas() {
        val largo = (1..5).joinToString(".") { "a".repeat(63) }
        assertNull(PaquetesDns.nombreConsultado(consultaDns(largo)))
        // Justo en el límite: 255 bytes en formato de red.
        val justo = listOf("a".repeat(63), "b".repeat(63), "c".repeat(63), "d".repeat(61)).joinToString(".")
        assertEquals(justo, PaquetesDns.nombreConsultado(consultaDns(justo)))
        val pasado = listOf("a".repeat(63), "b".repeat(63), "c".repeat(63), "d".repeat(62)).joinToString(".")
        assertNull(PaquetesDns.nombreConsultado(consultaDns(pasado)))
        // Tipos de etiqueta 01 y 10 (reservados).
        assertNull(PaquetesDns.nombreConsultado(mensaje(byteArrayOf(0x41, 0x61, 0, 0, 1, 0, 1))))
        assertNull(PaquetesDns.nombreConsultado(mensaje(byteArrayOf(0x81.toByte(), 0x61, 0, 0, 1, 0, 1))))
        // Etiqueta que dice ser más larga que el mensaje.
        assertNull(PaquetesDns.nombreConsultado(mensaje(byteArrayOf(30, 0x61, 0x62))))
    }

    @Test(timeout = 2000)
    fun punterosEnBucleNoCuelgan() {
        val cola = byteArrayOf(0, 1, 0, 1)   // tipo y clase, por si el nombre "terminara"
        val c0 = 0xC0.toByte()
        // Puntero a sí mismo (offset 12).
        assertNull(PaquetesDns.nombreConsultado(mensaje(byteArrayOf(c0, 12) + cola)))
        // Dos punteros que se apuntan entre sí (12 -> 14 -> 12).
        assertNull(PaquetesDns.nombreConsultado(mensaje(byteArrayOf(c0, 14, c0, 12) + cola)))
        // Etiqueta y puntero al comienzo: "a.a.a.a..." sin fin.
        assertNull(PaquetesDns.nombreConsultado(mensaje(byteArrayOf(1, 0x61, c0, 12) + cola)))
        // Puntero al encabezado, donde hay otro puntero que se apunta a sí mismo (offset 2).
        assertNull(PaquetesDns.nombreConsultado(mensaje(byteArrayOf(c0, 2) + cola, banderas = 0xC002)))
        assertNull(PaquetesDns.respuestaNxdomain(mensaje(byteArrayOf(c0, 2) + cola, banderas = 0xC002)))
        // Puntero hacia adelante.
        assertNull(PaquetesDns.nombreConsultado(mensaje(byteArrayOf(c0, 14, 1, 0x61, 0) + cola)))
        // Puntero cortado a la mitad.
        assertNull(PaquetesDns.nombreConsultado(mensaje(byteArrayOf(c0))))
        // Cadena larga de punteros, cada uno al anterior: se corta por el límite de saltos.
        val cadena = ByteArrayOutputStream()
        cadena.write(0)                                         // offset 12: raíz
        for (i in 0 until 2000) {
            // El puntero i está en 13 + 2i y apunta al anterior (el primero, a la raíz).
            val anterior = if (i == 0) 12 else 11 + 2 * i
            cadena.write(0xC0 or (anterior shr 8))
            cadena.write(anterior and 0xFF)
        }
        val largoMensaje = mensaje(cadena.toByteArray())
        assertNull(PaquetesDns.nombreEn(largoMensaje, largoMensaje.size - 2))
        // Una cadena corta sí es válida: tres punteros hasta la raíz.
        assertEquals("", PaquetesDns.nombreEn(largoMensaje, 13 + 2 * 2))
    }

    @Test
    fun nombresComprimidosValidos() {
        // "youtube.com" en el offset 12 y luego "www" + puntero a 12.
        val base = mensaje(pregunta("youtube.com"))
        val www = base.size
        val conWww = base + byteArrayOf(3, 0x77, 0x77, 0x77, 0xC0.toByte(), 12)
        assertEquals("www.youtube.com", PaquetesDns.nombreEn(conWww, www))
        // Cadena de dos punteros: "a" -> "m" -> "youtube.com".
        val m = conWww.size
        val conM = conWww + byteArrayOf(1, 0x6D, 0xC0.toByte(), 12)
        val a = conM.size
        val conA = conM + byteArrayOf(1, 0x61, 0xC0.toByte(), m.toByte())
        assertEquals("m.youtube.com", PaquetesDns.nombreEn(conA, m))
        assertEquals("a.m.youtube.com", PaquetesDns.nombreEn(conA, a))
        assertNull(PaquetesDns.nombreEn(conA, conA.size))
        assertNull(PaquetesDns.nombreEn(conA, -1))
    }

    // --- Qué consultas se bloquean ---------------------------------------------------------

    @Test
    fun canarioDeFirefoxSiempreNoExiste() {
        assertTrue(PaquetesDns.bloquearConsulta("use-application-dns.net", false, emptySet()))
        assertTrue(PaquetesDns.bloquearConsulta("use-application-dns.net", true, emptySet()))
        assertTrue(PaquetesDns.bloquearConsulta("x.use-application-dns.net", false, emptySet()))
        assertFalse(PaquetesDns.bloquearConsulta("application-dns.net", false, emptySet()))
        assertFalse(PaquetesDns.bloquearConsulta("www.use-application-dns.network", true, emptySet()))
    }

    @Test
    fun sitiosYServidoresDohSoloDuranteUnBloqueo() {
        val sitios = setOf("youtube.com")
        assertFalse(PaquetesDns.bloquearConsulta("www.youtube.com", false, sitios))
        assertTrue(PaquetesDns.bloquearConsulta("www.youtube.com", true, sitios))
        assertFalse(PaquetesDns.bloquearConsulta("dns.google", false, sitios))
        assertTrue(PaquetesDns.bloquearConsulta("dns.google", true, sitios))
        // Los nombres que usan Chrome y Firefox, también como subdominios.
        assertTrue(PaquetesDns.bloquearConsulta("chrome.cloudflare-dns.com", true, emptySet()))
        assertTrue(PaquetesDns.bloquearConsulta("mozilla.cloudflare-dns.com", true, emptySet()))
        assertTrue(PaquetesDns.bloquearConsulta("dns11.quad9.net", true, emptySet()))
        assertTrue(PaquetesDns.bloquearConsulta("chromium.dns.nextdns.io", true, emptySet()))
        assertTrue(PaquetesDns.bloquearConsulta("dns64.dns.google", true, emptySet()))
        // El resto de cada proveedor no se toca.
        assertFalse(PaquetesDns.bloquearConsulta("www.google.com", true, sitios))
        assertFalse(PaquetesDns.bloquearConsulta("cloudflare.com", true, sitios))
        assertFalse(PaquetesDns.bloquearConsulta("nextdns.io", true, sitios))
        assertFalse(PaquetesDns.bloquearConsulta("google", true, sitios))
        assertFalse(PaquetesDns.bloquearConsulta("", true, sitios))
    }

    @Test
    fun elDnsPrivadoDelSistemaNuncaSeBloquea() {
        assertFalse(PaquetesDns.bloquearConsulta("dns.google", true, emptySet(), dnsPrivado = "dns.google"))
        assertTrue(PaquetesDns.bloquearConsulta("dns64.dns.google", true, emptySet(), dnsPrivado = "dns.google"))
        val cloudflare = "1dot1dot1dot1.cloudflare-dns.com"
        assertFalse(PaquetesDns.bloquearConsulta(cloudflare, true, emptySet(), dnsPrivado = cloudflare))
        assertTrue(PaquetesDns.bloquearConsulta("chrome.cloudflare-dns.com", true, emptySet(), dnsPrivado = cloudflare))
        // Ni aunque esté en la lista de sitios: sin él, el teléfono entero se queda sin DNS.
        assertFalse(PaquetesDns.bloquearConsulta("dns.google", true, setOf("dns.google"), dnsPrivado = "dns.google"))
        // El canario sigue sin existir.
        assertTrue(PaquetesDns.bloquearConsulta("use-application-dns.net", true, emptySet(), dnsPrivado = "dns.google"))
    }

    @Test
    fun losServidoresDohSonDominiosNormalizados() {
        for (s in PaquetesDns.SERVIDORES_DOH) assertEquals(s, Dominios.normalizar(s))
        assertEquals(PaquetesDns.CANARIO_FIREFOX, Dominios.normalizar(PaquetesDns.CANARIO_FIREFOX))
    }

    // --- Reenvío al DNS real ---------------------------------------------------------------

    @Test
    fun detectaRespuestasTruncadas() {
        val normal = mensaje(pregunta("a.com"), banderas = 0x8180)     // QR, RD, RA
        val truncada = mensaje(pregunta("a.com"), banderas = 0x8380)   // y además TC
        assertFalse(PaquetesDns.truncada(normal))
        assertTrue(PaquetesDns.truncada(truncada))
        assertTrue(PaquetesDns.truncada(truncada + ByteArray(50), truncada.size))
        assertFalse(PaquetesDns.truncada(truncada.copyOf(11)))         // encabezado incompleto
        assertFalse(PaquetesDns.truncada(truncada, 11))
        assertFalse(PaquetesDns.truncada(ByteArray(0)))
    }

    @Test
    fun reconoceLaRespuestaALaConsulta() {
        val consulta = consultaDns("a.com", id = 0x1234)
        val respuesta = mensaje(pregunta("a.com"), id = 0x1234, banderas = 0x8180)
        assertTrue(PaquetesDns.respondeA(respuesta, respuesta.size, consulta))
        // Buffer más grande que la respuesta: solo cuentan los primeros bytes.
        assertTrue(PaquetesDns.respondeA(respuesta + ByteArray(100), respuesta.size, consulta))
        assertFalse(PaquetesDns.respondeA(respuesta, 11, consulta))                  // cortada
        assertFalse(PaquetesDns.respondeA(respuesta, respuesta.size + 5, ByteArray(1)))
        val otroId = mensaje(pregunta("a.com"), id = 0x1235, banderas = 0x8180)
        assertFalse(PaquetesDns.respondeA(otroId, otroId.size, consulta))
        assertFalse(PaquetesDns.respondeA(consulta, consulta.size, consulta))       // QR=0: es una consulta
    }

    @Test
    fun prefijoDeLargoParaTcp() {
        val dns = consultaDns("www.youtube.com")
        val tcp = noNulo(PaquetesDns.conLargo(dns))
        assertEquals(dns.size + 2, tcp.size)
        assertEquals(dns.size, u16(tcp, 0))
        assertArrayEquals(dns, tcp.copyOfRange(2, tcp.size))
        assertEquals(300, u16(noNulo(PaquetesDns.conLargo(ByteArray(300))), 0))
        assertEquals(0xFFFF, u16(noNulo(PaquetesDns.conLargo(ByteArray(0xFFFF))), 0))
        assertNull(PaquetesDns.conLargo(ByteArray(0x10000)))
    }

    @Test
    fun consultaDePruebaEsValidaYNoNombraNingunSitio() {
        val q = PaquetesDns.consultaPrueba(0x1234)
        assertEquals(0x1234, u16(q, 0))
        assertEquals(0x0100, u16(q, 2))                                    // consulta estándar, RD=1
        assertEquals(1, u16(q, 4))                                         // una pregunta
        assertEquals(0, u16(q, 6))
        assertEquals(0, u16(q, 8))
        assertEquals(0, u16(q, 10))
        assertArrayEquals(byteArrayOf(0, 0, 6, 0, 1), q.copyOfRange(12, q.size))   // raíz, SOA, IN
        assertEquals("", PaquetesDns.nombreConsultado(q))
        assertEquals(0xABCD, u16(PaquetesDns.consultaPrueba(0x1ABCD), 0))  // solo 16 bits
        // Es una consulta como cualquier otra: viaja por UDP y su respuesta se reconoce.
        val c = noNulo(PaquetesDns.analizar(paqueteIp(q)))
        assertArrayEquals(q, c.dns)
        val r = noNulo(PaquetesDns.respuestaNxdomain(q))
        assertTrue(PaquetesDns.respondeA(r, r.size, q))
    }

    @Test
    fun basuraNoLanzaExcepciones() {
        val azar = Random(20261117)
        var analizados = 0
        repeat(20_000) {
            val datos = azar.nextBytes(azar.nextInt(0, 160))
            if (datos.size >= 34 && azar.nextBoolean()) {
                // Que parezca IPv4/UDP al puerto 53 para llegar a la parte DNS.
                datos[0] = 0x45
                datos[2] = (datos.size shr 8).toByte()
                datos[3] = datos.size.toByte()
                datos[6] = 0
                datos[7] = 0
                datos[9] = 17
                datos[22] = 0
                datos[23] = 53
                val udp = datos.size - 20
                datos[24] = (udp shr 8).toByte()
                datos[25] = udp.toByte()
                if (azar.nextBoolean()) {
                    datos[32] = 0   // QDCOUNT = 1
                    datos[33] = 1
                }
            }
            val c = PaquetesDns.analizar(datos)
            PaquetesDns.analizar(datos, azar.nextInt(-10, 200))
            PaquetesDns.nombreConsultado(datos)
            PaquetesDns.respuestaNxdomain(datos)
            PaquetesDns.nombreEn(datos, azar.nextInt(-5, 170))
            if (c != null) {
                analizados++
                PaquetesDns.nombreConsultado(c.dns)
                val r = PaquetesDns.respuestaNxdomain(c.dns)
                if (r != null) noNulo(PaquetesDns.envolverRespuesta(c, r))
            }
        }
        assertTrue(analizados > 0)
    }
}
