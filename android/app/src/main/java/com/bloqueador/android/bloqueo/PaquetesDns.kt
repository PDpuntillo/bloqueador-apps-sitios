package com.bloqueador.android.bloqueo

import com.bloqueador.android.dominio.Dominios
import java.io.ByteArrayOutputStream

/**
 * Lectura y escritura de los paquetes que pasan por el túnel de [FiltroVpnService] y la regla
 * de qué consultas se bloquean.
 *
 * Son funciones puras (sin clases de Android) para poder probarlas en la computadora. Ante
 * cualquier dato truncado o malformado devuelven null; nunca lanzan.
 */
object PaquetesDns {
    /** Puerto estándar de DNS. */
    const val PUERTO_DNS = 53

    /** Carga máxima de un datagrama UDP sobre IPv4 (65535 - 20 - 8). */
    const val MAX_CARGA_UDP = 65507

    private const val VERSION_IPV4 = 4
    private const val PROTOCOLO_UDP = 17
    private const val LARGO_IP = 20       // encabezado IPv4 sin opciones
    private const val LARGO_UDP = 8
    private const val LARGO_DNS = 12      // encabezado DNS
    private const val MAX_NOMBRE = 255    // largo máximo de un nombre en formato de red
    private const val MAX_SALTOS = 32     // punteros de compresión permitidos en un nombre
    private const val TIPO_SOA = 6
    private const val CLASE_IN = 1

    /**
     * Firefox consulta este nombre para decidir si activa por su cuenta el DNS cifrado (DoH).
     * Si "no existe", no lo activa.
     */
    const val CANARIO_FIREFOX = "use-application-dns.net"

    private val CANARIO = setOf(CANARIO_FIREFOX)

    /**
     * Servidores de DNS cifrado (DoH) conocidos, sobre todo los que ofrecen Chrome, Edge, Brave
     * y Firefox en su opción «DNS seguro». Cuentan también sus subdominios: cloudflare-dns.com
     * cubre chrome.cloudflare-dns.com y mozilla.cloudflare-dns.com.
     */
    val SERVIDORES_DOH: Set<String> = setOf(
        "dns.google", "dns.google.com", "8888.google",                      // Google
        "cloudflare-dns.com", "one.one.one.one",                            // Cloudflare
        "quad9.net",                                                        // Quad9
        "dns.nextdns.io",                                                   // NextDNS
        "doh.opendns.com", "doh.familyshield.opendns.com",                  // OpenDNS
        "doh.cleanbrowsing.org",                                            // CleanBrowsing
        "adguard-dns.com", "dns.adguard.com", "dns-family.adguard.com",     // AdGuard
        "dns-unfiltered.adguard.com",
        "dns.mullvad.net", "doh.mullvad.net",                               // Mullvad
        "dns.controld.com", "freedns.controld.com",                         // Control D
        "canadianshield.cira.ca",                                           // CIRA (Canadá)
        "doh.xfinity.com",                                                  // Comcast
        "odvr.nic.cz",                                                      // CZ.NIC
        "doh.dns.sb",                                                       // DNS.SB
        "dns.switch.ch",                                                    // SWITCH (Suiza)
        "public.dns.iij.jp",                                                // IIJ (Japón)
        "dnsnl.alekberg.net",                                               // AlekBerg (Países Bajos)
        "dns.alidns.com", "doh.pub", "doh.360.cn",                          // AliDNS, DNSPod, 360
    )

    /** Una consulta DNS que llegó por el túnel, con lo necesario para responderla. */
    class Consulta(
        val ipOrigen: ByteArray,     // 4 bytes: quien pregunta (la dirección del túnel)
        val ipDestino: ByteArray,    // 4 bytes: nuestro servidor DNS falso
        val puertoOrigen: Int,
        val puertoDestino: Int,      // siempre 53
        val dns: ByteArray,          // mensaje DNS (carga UDP), copiado del paquete
    )

    /** Nombre leído: texto en minúsculas, forma de red sin comprimir y dónde termina en el mensaje. */
    private class Nombre(val texto: String, val crudo: ByteArray, val fin: Int)

    /** Pregunta leída: nombre en minúsculas y bytes listos para copiar (nombre + tipo + clase). */
    private class Pregunta(val nombre: String, val crudo: ByteArray)

    /**
     * Si [paquete] (sus primeros [longitud] bytes) es un datagrama IPv4/UDP con destino al
     * puerto 53, devuelve la consulta. Si no lo es, está fragmentado, truncado o malformado,
     * devuelve null.
     */
    fun analizar(paquete: ByteArray, longitud: Int = paquete.size): Consulta? {
        val n = minOf(longitud, paquete.size)
        if (n < LARGO_IP + LARGO_UDP) return null
        val b0 = u8(paquete, 0)
        if ((b0 ushr 4) != VERSION_IPV4) return null
        val ihl = (b0 and 0x0F) * 4
        if (ihl < LARGO_IP) return null
        val total = u16(paquete, 2)
        if (total > n || total < ihl + LARGO_UDP) return null
        if ((u16(paquete, 6) and 0x3FFF) != 0) return null       // fragmento (MF o desplazamiento)
        if (u8(paquete, 9) != PROTOCOLO_UDP) return null
        val puertoDestino = u16(paquete, ihl + 2)
        if (puertoDestino != PUERTO_DNS) return null
        val largoUdp = u16(paquete, ihl + 4)
        if (largoUdp < LARGO_UDP + LARGO_DNS || ihl + largoUdp > total) return null
        return Consulta(
            ipOrigen = paquete.copyOfRange(12, 16),
            ipDestino = paquete.copyOfRange(16, 20),
            puertoOrigen = u16(paquete, ihl),
            puertoDestino = puertoDestino,
            dns = paquete.copyOfRange(ihl + LARGO_UDP, ihl + largoUdp),
        )
    }

    /**
     * Nombre consultado (QNAME) en minúsculas y sin el punto final, por ejemplo
     * "www.youtube.com". La raíz da "". Devuelve null si el mensaje no tiene exactamente una
     * pregunta completa y bien formada.
     */
    fun nombreConsultado(dns: ByteArray): String? = leerPregunta(dns)?.nombre

    /**
     * Respuesta NXDOMAIN ("ese dominio no existe") para la consulta [consulta]: mismo ID,
     * QR=1, conserva opcode y RD, RA=1, RCODE=3 y una sola pregunta, copiada de la consulta.
     * No lleva respuestas, autoridad ni registros adicionales (se descarta el OPT/EDNS).
     * Devuelve null si la consulta está malformada.
     */
    fun respuestaNxdomain(consulta: ByteArray): ByteArray? {
        val pregunta = leerPregunta(consulta) ?: return null
        val r = ByteArray(LARGO_DNS + pregunta.crudo.size)
        r[0] = consulta[0]                                        // ID
        r[1] = consulta[1]
        r[2] = (0x80 or (u8(consulta, 2) and 0x79)).toByte()     // QR=1 + opcode + RD; AA=TC=0
        r[3] = 0x83.toByte()                                     // RA=1, RCODE=3 (NXDOMAIN)
        r[5] = 1                                                 // QDCOUNT=1; los demás en 0
        System.arraycopy(pregunta.crudo, 0, r, LARGO_DNS, pregunta.crudo.size)
        return r
    }

    /**
     * Envuelve el mensaje DNS [dns] (sus primeros [longitud] bytes) en un paquete IPv4/UDP de
     * respuesta a [consulta]: IPs y puertos invertidos, checksums IPv4 y UDP correctos.
     * Devuelve null si los datos no caben en un datagrama.
     */
    fun envolverRespuesta(consulta: Consulta, dns: ByteArray, longitud: Int = dns.size): ByteArray? {
        if (longitud < 0 || longitud > dns.size || longitud > MAX_CARGA_UDP) return null
        if (consulta.ipOrigen.size != 4 || consulta.ipDestino.size != 4) return null
        val largoUdp = LARGO_UDP + longitud
        val total = LARGO_IP + largoUdp
        val p = ByteArray(total)

        // Encabezado IPv4 (20 bytes, sin opciones).
        p[0] = 0x45                                               // versión 4, IHL 5
        poner16(p, 2, total)
        p[6] = 0x40                                               // "no fragmentar"; identificación 0
        p[8] = 64                                                 // TTL
        p[9] = PROTOCOLO_UDP.toByte()
        System.arraycopy(consulta.ipDestino, 0, p, 12, 4)         // origen: a quien se preguntó
        System.arraycopy(consulta.ipOrigen, 0, p, 16, 4)          // destino: quien preguntó
        poner16(p, 10, checksum(sumar(p, 0, LARGO_IP)))

        // Encabezado UDP y datos.
        poner16(p, LARGO_IP, consulta.puertoDestino)
        poner16(p, LARGO_IP + 2, consulta.puertoOrigen)
        poner16(p, LARGO_IP + 4, largoUdp)
        System.arraycopy(dns, 0, p, LARGO_IP + LARGO_UDP, longitud)

        // Checksum UDP: pseudo-encabezado (IPs, protocolo y largo) + encabezado + datos.
        val pseudo = sumar(p, 12, 8) + PROTOCOLO_UDP + largoUdp
        val suma = checksum(sumar(p, LARGO_IP, largoUdp, pseudo))
        poner16(p, LARGO_IP + 6, if (suma == 0) 0xFFFF else suma)   // 0 significa "sin checksum"
        return p
    }

    /**
     * True si a la consulta por [nombre] (en minúsculas, como lo da [nombreConsultado]) se
     * responde NXDOMAIN en lugar de reenviarla al DNS real.
     * - Siempre: el canario de Firefox, para que no active solo su DNS cifrado.
     * - Durante un bloqueo ([activo]): los [sitios] y los [SERVIDORES_DOH], para que el «DNS
     *   seguro» del navegador no se salte el filtro.
     * - Con o sin bloqueo: los [agotados] (sitios cuyo límite diario se acabó) y, si hay alguno,
     *   también los [SERVIDORES_DOH].
     * - Nunca: [dnsPrivado], el servidor del «DNS privado» fijado en Ajustes. Android lo busca
     *   por el DNS de la VPN; si no lo encuentra, el teléfono entero se queda sin DNS.
     */
    fun bloquearConsulta(
        nombre: String,
        activo: Boolean,
        sitios: Set<String>,
        dnsPrivado: String? = null,
        agotados: Set<String> = emptySet(),
    ): Boolean {
        if (Dominios.bloqueado(nombre, CANARIO)) return true
        if (nombre == dnsPrivado) return false
        if (Dominios.bloqueado(nombre, agotados)) return true
        if (!activo && agotados.isEmpty()) return false
        return (activo && Dominios.bloqueado(nombre, sitios)) || Dominios.bloqueado(nombre, SERVIDORES_DOH)
    }

    /** True si el mensaje DNS [dns] llegó truncado (bit TC): la respuesta completa va por TCP. */
    fun truncada(dns: ByteArray, longitud: Int = dns.size): Boolean =
        minOf(longitud, dns.size) >= LARGO_DNS && (u8(dns, 2) and 0x02) != 0

    /**
     * True si [respuesta] (sus primeros [longitud] bytes) responde a [consulta]: encabezado
     * completo, el mismo ID y el bit QR ("es una respuesta") encendido.
     */
    fun respondeA(respuesta: ByteArray, longitud: Int, consulta: ByteArray): Boolean =
        minOf(longitud, respuesta.size) >= LARGO_DNS && consulta.size >= 2 &&
            respuesta[0] == consulta[0] && respuesta[1] == consulta[1] &&
            (u8(respuesta, 2) and 0x80) != 0

    /** El mensaje [dns] con su largo delante (2 bytes), como se manda por TCP. Null si no cabe. */
    fun conLargo(dns: ByteArray): ByteArray? {
        if (dns.size > 0xFFFF) return null
        val r = ByteArray(2 + dns.size)
        poner16(r, 0, dns.size)
        System.arraycopy(dns, 0, r, 2, dns.size)
        return r
    }

    /**
     * Consulta mínima para comprobar si un servidor DNS responde, sin nombrar ningún sitio:
     * el registro SOA de la raíz, con RD=1 y el ID [id] (se usan sus 16 bits bajos).
     */
    fun consultaPrueba(id: Int): ByteArray {
        val r = ByteArray(LARGO_DNS + 5)
        poner16(r, 0, id and 0xFFFF)
        r[2] = 0x01                                  // RD
        r[5] = 1                                     // QDCOUNT=1
        // r[12] = 0: nombre raíz
        poner16(r, LARGO_DNS + 1, TIPO_SOA)
        poner16(r, LARGO_DNS + 3, CLASE_IN)
        return r
    }

    /** Lee un nombre que empieza en [inicio] de un mensaje DNS (para pruebas). */
    internal fun nombreEn(dns: ByteArray, inicio: Int): String? = leerNombre(dns, inicio)?.texto

    // ---------------------------------------------------------------------------------------

    private fun leerPregunta(dns: ByteArray): Pregunta? {
        if (dns.size < LARGO_DNS || u16(dns, 4) != 1) return null   // exactamente una pregunta
        val nombre = leerNombre(dns, LARGO_DNS) ?: return null
        val finPregunta = nombre.fin + 4                             // + QTYPE y QCLASS
        if (finPregunta > dns.size) return null
        return Pregunta(nombre.texto, nombre.crudo + dns.copyOfRange(nombre.fin, finPregunta))
    }

    /**
     * Lee un nombre con soporte de punteros de compresión. Cada puntero debe apuntar
     * estrictamente antes que el anterior (y antes de [inicio]), así que no puede haber bucles;
     * además se limitan los saltos y el largo total.
     */
    private fun leerNombre(dns: ByteArray, inicio: Int): Nombre? {
        val texto = StringBuilder()
        val crudo = ByteArrayOutputStream()
        var pos = inicio
        var limite = inicio      // el próximo puntero debe apuntar antes de aquí
        var fin = -1             // dónde termina el nombre en el mensaje original
        var saltos = 0
        var largo = 1            // largo en formato de red, con el 0 final
        while (pos >= 0 && pos < dns.size) {
            val b = u8(dns, pos)
            when (b and 0xC0) {
                0x00 -> {
                    if (b == 0) {
                        crudo.write(0)
                        return Nombre(texto.toString(), crudo.toByteArray(), if (fin < 0) pos + 1 else fin)
                    }
                    val finEtiqueta = pos + 1 + b
                    largo += b + 1
                    if (finEtiqueta > dns.size || largo > MAX_NOMBRE) return null
                    if (texto.isNotEmpty()) texto.append('.')
                    for (i in pos + 1 until finEtiqueta) texto.append(minuscula(dns[i]))
                    crudo.write(dns, pos, b + 1)
                    pos = finEtiqueta
                }
                0xC0 -> {
                    if (pos + 1 >= dns.size) return null
                    val destino = ((b and 0x3F) shl 8) or u8(dns, pos + 1)
                    saltos++
                    if (destino >= limite || saltos > MAX_SALTOS) return null
                    if (fin < 0) fin = pos + 2
                    limite = destino
                    pos = destino
                }
                else -> return null   // tipos de etiqueta 01 y 10: obsoletos o reservados
            }
        }
        return null   // se acabó el mensaje antes del final del nombre
    }

    private fun minuscula(b: Byte): Char {
        val c = b.toInt() and 0xFF
        return if (c in 0x41..0x5A) (c + 0x20).toChar() else c.toChar()
    }

    private fun u8(d: ByteArray, i: Int): Int = d[i].toInt() and 0xFF

    private fun u16(d: ByteArray, i: Int): Int = (u8(d, i) shl 8) or u8(d, i + 1)

    private fun poner16(d: ByteArray, i: Int, valor: Int) {
        d[i] = (valor ushr 8).toByte()
        d[i + 1] = valor.toByte()
    }

    /** Suma de palabras de 16 bits (big-endian) para el checksum de Internet, sin plegar. */
    private fun sumar(d: ByteArray, inicio: Int, largo: Int, previa: Long = 0L): Long {
        var suma = previa
        var i = inicio
        val fin = inicio + largo
        while (i + 1 < fin) {
            suma += u16(d, i).toLong()
            i += 2
        }
        if (i < fin) suma += (u8(d, i) shl 8).toLong()   // byte suelto: se completa con un 0
        return suma
    }

    /** Pliega la suma a 16 bits y la complementa a uno. */
    private fun checksum(suma: Long): Int {
        var s = suma
        while ((s ushr 16) != 0L) s = (s and 0xFFFFL) + (s ushr 16)
        return s.toInt().inv() and 0xFFFF
    }
}
