package com.bloqueador.android.bloqueo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import com.bloqueador.android.R
import com.bloqueador.android.datos.Almacen
import com.bloqueador.android.datos.AlmacenUso
import com.bloqueador.android.dominio.Config
import java.io.Closeable
import java.io.DataInputStream
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * Filtro de sitios web: una VPN local que solo intercepta DNS.
 *
 * El túnel enruta una única dirección, un servidor DNS falso. Android manda ahí las consultas
 * DNS de todas las apps (menos la nuestra, que queda fuera del túnel); el resto del tráfico,
 * IPv4 e IPv6, sigue por la red real sin pasar por la VPN. Por cada consulta: si hay un bloqueo
 * activo y el dominio está en la lista (o es un subdominio de uno), o si es de un sitio cuyo
 * límite diario se agotó, se responde NXDOMAIN ("no existe"); si no, se reenvía al DNS real de
 * la red y se devuelve su respuesta.
 *
 * Fuera de los bloqueos la VPN se puede evitar (allowBypass), así que funcionan las apps que se
 * atan a otra red, como las de cámaras o aparatos IoT. Durante un bloqueo, no.
 *
 * Si el filtro no consigue salir a internet con una red que funciona (por ejemplo, porque el
 * sistema le quitó la red a Bloqueador), fuera de un bloqueo cambia a un túnel "de paso": sin
 * rutas y con los DNS reales, así que las apps resuelven directo contra la red real. La VPN
 * sigue conectada, así que Android no detiene el servicio, y este sigue probando la red. Cuando
 * la red responde, o al empezar un bloqueo, vuelve el túnel normal.
 *
 * Corre siempre, también fuera de los bloqueos. No registra los dominios consultados.
 */
class FiltroVpnService : VpnService() {

    /** Reglas en caché, para no recalcular el estado en cada paquete. */
    private class Reglas(
        val calculadas: Long,          // SystemClock.elapsedRealtime()
        val activo: Boolean,
        val fuente: List<String>,      // lista de la que salió [sitios], para reutilizar el Set
        val sitios: Set<String>,
        val agotados: Set<String>,     // sitios cuyo tiempo de hoy se acabó (bloqueados con o sin bloqueo)
    )

    /**
     * Una interfaz del túnel y su hilo lector. [dePaso]: es el túnel de paso, sin rutas ni
     * servidores DNS propios (ver [pasarDePaso]).
     */
    private inner class Tunel(
        private val descriptor: ParcelFileDescriptor,
        val dePaso: Boolean = false,
    ) {
        private val fd: FileDescriptor = descriptor.fileDescriptor
        private val entrada = FileInputStream(fd)
        private val salida = FileOutputStream(fd)
        val creado: Long = SystemClock.elapsedRealtime()

        @Volatile
        var cerrado = false
            private set

        private val hilo = Thread({ bucleLector(this) }, "Bloqueador-VPN-lector").apply { isDaemon = true }

        fun iniciar() = hilo.start()

        /**
         * Espera hasta [esperaMs] a que haya un paquete para leer. Así el hilo lector revisa
         * [cerrado] de vez en cuando y termina aunque el cierre no lo despierte.
         */
        fun hayDatos(esperaMs: Int): Boolean {
            val sondeo = StructPollfd()
            sondeo.fd = fd
            sondeo.events = OsConstants.POLLIN.toShort()
            return try {
                Os.poll(arrayOf(sondeo), esperaMs) > 0
            } catch (e: ErrnoException) {
                if (e.errno == OsConstants.EINTR) false else throw IOException(e)
            }
        }

        fun leer(buffer: ByteArray): Int = entrada.read(buffer)

        /** Escribe un paquete de respuesta. Varios hilos responden a la vez: se sincroniza. */
        fun escribir(paquete: ByteArray) {
            synchronized(salida) {
                if (cerrado) return
                try {
                    salida.write(paquete)
                } catch (e: Exception) {
                    // El túnel se cerró o se cayó: la respuesta se pierde y el sistema reintenta.
                }
            }
        }

        fun cerrar() {
            synchronized(salida) {
                if (cerrado) return
                cerrado = true
            }
            try {
                descriptor.close()
            } catch (e: IOException) {
                // Ya estaba cerrado.
            }
            hilo.interrupt()
        }
    }

    private val cerrojo = Any()

    /** Túnel en uso. Se cambia solo con [cerrojo]. */
    @Volatile
    private var tunel: Tunel? = null

    @Volatile
    private var destruido = false

    // Protegidos por [cerrojo].
    private var ultimoActivo = false
    private var sitiosAplicados: Set<String> = emptySet()
    private var agotadosAplicados: Set<String> = emptySet()
    private var tunelesCreados = 0
    private var fallosSeguidos = 0

    /**
     * True mientras el filtro está pausado porque no conseguía salir a internet (empieza solo
     * fuera de un bloqueo). [tunel] es entonces el túnel de paso, o null si ni ese se pudo
     * crear. Se cambia con [cerrojo].
     */
    @Volatile
    private var sinRed = false

    /** Veces seguidas que se pasó al túnel de paso sin que el DNS real respondiera entre medio. */
    @Volatile
    private var pausas = 0

    /** Pruebas de red seguidas que fallaron en el túnel de paso (en la red actual). */
    @Volatile
    private var pruebasFallidas = 0

    /** SystemClock.elapsedRealtime() desde el que se vuelve a probar la red en el túnel de paso. */
    @Volatile
    private var reintentarEn = 0L

    /** Si había bloqueo en la comprobación anterior de cada segundo (solo la usa el reloj). */
    private var activoAntes = false

    /** Consultas seguidas en las que no respondió ningún servidor DNS real. */
    private val fallosRed = AtomicInteger()

    /** SystemClock.elapsedRealtime() de la última respuesta del DNS real (o del último túnel nuevo). */
    @Volatile
    private var ultimoExito = 0L

    @Volatile
    private var reglas: Reglas? = null

    /** Servidores DNS de la red real (nuestra app está fuera del túnel, así que ve esa red). */
    @Volatile
    private var dnsReales: List<InetAddress> = emptyList()

    /** El último servidor que respondió: se le pregunta primero. */
    @Volatile
    private var servidorPreferido: InetAddress? = null

    /** Servidor del «DNS privado» fijado en Ajustes, si lo hay. Nunca se bloquea. */
    @Volatile
    private var dnsPrivado: String? = null

    @Volatile
    private var redPorDefecto: Network? = null

    /**
     * La última red que Android validó (comprobó que tiene internet), o null. Solo cuenta si es
     * [redPorDefecto] (ver [redPorDefectoValidada]).
     */
    @Volatile
    private var redValidada: Network? = null

    @Volatile
    private var trabajadores: ThreadPoolExecutor? = null

    @Volatile
    private var reloj: ScheduledExecutorService? = null

    private var conectividad: ConnectivityManager? = null
    private var quitarOyente: (() -> Unit)? = null
    private var quitarOyenteUso: (() -> Unit)? = null

    /** True mientras se muestra el aviso de primer plano del arranque. Solo en el hilo principal. */
    private var enPrimerPlano = false

    /** Sockets abiertos hacia el DNS real, para cerrarlos al destruir el servicio. */
    private val sockets: MutableSet<Closeable> =
        Collections.newSetFromMap(ConcurrentHashMap<Closeable, Boolean>())

    private val bufferRespuesta = object : ThreadLocal<ByteArray>() {
        override fun initialValue(): ByteArray = ByteArray(PaquetesDns.MAX_CARGA_UDP)
    }

    private val callbackRed = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capacidades: NetworkCapabilities) {
            if (capacidades.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                redValidada = network
            } else if (redValidada == network) {
                redValidada = null
            }
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            try {
                val redNueva = network != redPorDefecto
                if (redNueva) {
                    // Red nueva: los fallos de la anterior no cuentan y se puede volver a probar.
                    fallosRed.set(0)
                    ultimoExito = SystemClock.elapsedRealtime()
                    reintentarEn = 0L
                    pruebasFallidas = 0
                }
                redPorDefecto = network
                val servidores = linkProperties.dnsServers.filter { utilizable(it) }
                val otrosDns = servidores != dnsReales
                dnsReales = servidores
                val preferido = servidorPreferido
                if (preferido != null && preferido !in servidores) servidorPreferido = null
                dnsPrivado = leerDnsPrivado(linkProperties)
                // En el túnel de paso: con otra red u otros DNS, probar la red cuanto antes.
                if (sinRed && (redNueva || otrosDns)) {
                    reintentarEn = 0L
                    programar(0L) { revisar() }
                }
            } catch (e: Exception) {
                // Se siguen usando los servidores conocidos.
            }
        }

        override fun onLost(network: Network) {
            if (network == redValidada) redValidada = null
            if (network == redPorDefecto) {
                redPorDefecto = null
                dnsReales = emptyList()
                servidorPreferido = null
            }
        }
    }

    // --- Ciclo de vida ------------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        seguro { Almacen.iniciar(applicationContext) }
        trabajadores = ThreadPoolExecutor(
            HILOS_DNS, HILOS_DNS, 30L, TimeUnit.SECONDS,
            ArrayBlockingQueue<Runnable>(COLA_DNS),
            fabricaHilos("Bloqueador-DNS"),
            ThreadPoolExecutor.DiscardPolicy(),   // cola llena: la consulta se descarta y el sistema reintenta
        ).apply { allowCoreThreadTimeOut(true) }
        reloj = Executors.newSingleThreadScheduledExecutor(fabricaHilos("Bloqueador-VPN-reloj")).apply {
            scheduleWithFixedDelay(Runnable { seguro { revisar() } }, REVISION_S, REVISION_S, TimeUnit.SECONDS)
            // Con el filtro pausado no pasa ninguna consulta por él que avise de que empezó un
            // horario: se mira cada segundo y, justo cuando empieza un bloqueo, se vuelve al
            // túnel normal. Si eso falla, los reintentos quedan para la revisión de cada 15 s.
            scheduleWithFixedDelay(Runnable {
                seguro {
                    val activo = !destruido && estaActivo(Almacen.config)
                    val empieza = activo && !activoAntes
                    activoAntes = activo
                    if (empieza && sinRed) revisar()
                }
            }, 1L, 1L, TimeUnit.SECONDS)
        }
        quitarOyente = Almacen.escuchar {
            reglas = null   // aplicar el cambio desde la próxima consulta
            programar(RETRASO_CAMBIO_MS) { revisar() }
        }
        // Se acabó el tiempo de un sitio: bloquearlo desde ya (y renovar el túnel, ver revisar).
        quitarOyenteUso = AlmacenUso.escuchar {
            reglas = null
            programar(RETRASO_CAMBIO_MS) { revisar() }
        }
        registrarRed()
    }

    /**
     * Lo llaman la app (Servicios.iniciarVpn), el receptor de arranque y el sistema (acción
     * android.net.VpnService, en el modo "VPN siempre activada"). Establece el túnel si todavía
     * no lo está.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Arrancado con startForegroundService (Android 8-11, al encender el teléfono): hay que
        // llamar a startForeground() antes que nada, o Android cierra la app.
        if (intent?.getBooleanExtra(Servicios.EXTRA_PRIMER_PLANO, false) == true) entrarEnPrimerPlano()
        val listo = try {
            synchronized(cerrojo) {
                when {
                    sinRed && !estaActivo(Almacen.config) -> {
                        // Filtro pausado por falta de red: se prueba la red ya, sin dejar el
                        // teléfono sin DNS mientras tanto.
                        reintentarEn = 0L
                        programar(0L) { revisar() }
                        true
                    }
                    tunel != null && !sinRed -> true
                    // Si no se pudo crear el túnel normal pero queda el de paso, el servicio
                    // sigue y la revisión periódica lo vuelve a intentar.
                    else -> establecerConCerrojo() || tunel != null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo iniciar el filtro de sitios", e)
            false
        }
        salirDePrimerPlano()
        if (!listo) {
            // Sin permiso de VPN (o lo tiene otra app): no tiene sentido seguir.
            Servicios.vpnActiva = false
            stopSelf()
            return START_NOT_STICKY
        }
        leerEstadoSistema()
        return START_STICKY
    }

    /** El usuario desconectó la VPN desde Ajustes, o otra app de VPN tomó el control. */
    override fun onRevoke() {
        borrarEstado()
        seguro {
            synchronized(cerrojo) {
                tunel?.cerrar()
                tunel = null
                sinRed = false
                borrarEstado()   // por si otro hilo lo cambió mientras se esperaba el cerrojo
            }
        }
        stopSelf()
    }

    override fun onDestroy() {
        destruido = true
        borrarEstado()
        salirDePrimerPlano()
        seguro { quitarOyente?.invoke() }
        quitarOyente = null
        seguro { quitarOyenteUso?.invoke() }
        quitarOyenteUso = null
        desregistrarRed()
        seguro { reloj?.shutdownNow() }
        seguro { trabajadores?.shutdownNow() }
        for (s in sockets) cerrarSinError(s)
        sockets.clear()
        seguro {
            synchronized(cerrojo) {
                tunel?.cerrar()
                tunel = null
                sinRed = false
                // Un hilo que ya estaba creando un túnel pudo volver a marcar la VPN como activa
                // antes de que se tomara el cerrojo. De aquí en adelante, quien escribe estos
                // valores con el cerrojo ve [destruido] y no los toca.
                borrarEstado()
            }
        }
        super.onDestroy()
    }

    /** Lo que ve la interfaz cuando el filtro no corre. */
    private fun borrarEstado() {
        Servicios.vpnActiva = false
        Servicios.vpnLockdown = false
        Servicios.vpnSiempreActiva = false
        Servicios.vpnSinRed = false
    }

    // --- Túnel ------------------------------------------------------------------------------

    /**
     * Crea una interfaz nueva y, si se pudo, cierra la anterior. Llamar con [cerrojo].
     * Devuelve false si Android no la creó (por ejemplo, porque no somos la app de VPN).
     * [dePaso]: crea el túnel de paso (ver [pasarDePaso]); durante un bloqueo, nunca.
     *
     * Los túneles se alternan entre dos configuraciones (servidor DNS falso .2 / .3 y la lista
     * de apps excluidas). Así Android crea siempre una red VPN nueva en lugar de actualizar la
     * anterior: las apps ven el cambio de red y la caché DNS empieza vacía. El túnel de paso
     * también se alterna, así que entrar y salir de él crea siempre una red nueva.
     */
    private fun establecerConCerrojo(dePaso: Boolean = false): Boolean {
        if (destruido) return false
        val config = Almacen.config
        val activo = estaActivo(config)
        if (dePaso && activo) return false
        val alterno = tunelesCreados % 2 == 1
        val descriptor = try {
            construir(activo, alterno, dePaso).establish()
        } catch (e: Exception) {
            // Error del sistema al crear la interfaz: no dice nada del permiso.
            Log.w(TAG, "No se pudo crear el túnel", e)
            return false
        }
        // null: Android no nos tiene como app de VPN (por ejemplo, tras reiniciar sin llamar a
        // prepare()) u otra VPN tiene el control. No se toca la bandera del permiso: puede
        // seguir vigente.
        if (descriptor == null) return false
        if (destruido) {
            cerrarSinError(descriptor)
            return false
        }
        Servicios.marcarPermisoVpn(this, true)
        val nuevo = try {
            Tunel(descriptor, dePaso)
        } catch (e: Exception) {
            cerrarSinError(descriptor)
            return false
        }
        tunelesCreados++
        val anterior = tunel
        tunel = nuevo
        reglas = null
        ultimoActivo = activo
        sitiosAplicados = config.sitios.toHashSet()
        agotadosAplicados = sitiosAgotados(config)
        sinRed = dePaso
        fallosRed.set(0)
        ultimoExito = SystemClock.elapsedRealtime()
        // En el túnel de paso el filtro no funciona: la interfaz no lo da por activo y muestra
        // el aviso de que no sale a internet (con «Activar», que prueba la red al instante).
        Servicios.vpnActiva = !dePaso
        if (dePaso) Servicios.vpnSinRed = true
        nuevo.iniciar()
        anterior?.cerrar()   // ya no le llega tráfico: se cierra y su hilo lector termina
        leerEstadoSistema()
        return true
    }

    /**
     * [bloqueo]: hay un bloqueo activo, así que la VPN no se puede evitar. [alterno]: usa la
     * segunda configuración (ver [establecerConCerrojo]). [dePaso]: arma el túnel de paso, sin
     * rutas ni servidores DNS propios.
     */
    private fun construir(bloqueo: Boolean, alterno: Boolean, dePaso: Boolean): VpnService.Builder {
        val b = Builder()
            .setSession(SESION)
            .addAddress(DIRECCION_TUNEL, 32)
            // Sin esto, Android corta todo el IPv6 del teléfono (no tiene rutas en el túnel).
            .allowFamily(OsConstants.AF_INET)
            .allowFamily(OsConstants.AF_INET6)
            .setBlocking(true)
        if (!dePaso) {
            val dnsFalso = DNS_FALSOS[if (alterno) 1 else 0]
            b.addDnsServer(dnsFalso)
            b.addRoute(dnsFalso, 32)   // solo esta dirección entra al túnel
        }
        // El túnel de paso no tiene rutas (no entra nada) ni servidores DNS: así Android usa los
        // DNS de la red real (también los de enlace local y los internos de cada red) y los sigue
        // solo cuando cambias de red, sin rehacer el túnel.
        // Fuera de un bloqueo, las apps pueden atarse a otra red (cámaras, drones, aparatos IoT).
        // Durante un bloqueo no, para que nadie se salte el DNS del filtro.
        if (!bloqueo) b.allowBypass()
        try {
            // Nuestro propio tráfico (y el NetworkCallback) usan la red real. En la configuración
            // alterna el paquete va repetido: lo excluido es lo mismo, pero la lista es otra y
            // Android no puede reutilizar la red VPN anterior.
            b.addDisallowedApplication(packageName)
            if (alterno) b.addDisallowedApplication(packageName)
        } catch (e: PackageManager.NameNotFoundException) {
            // No pasa: es nuestro propio paquete.
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) b.setMetered(false)
        intencionConfigurar()?.let { b.setConfigureIntent(it) }
        return b
    }

    /** Lo que abre el sistema al tocar "Configurar" en los ajustes de VPN: nuestra app. */
    private fun intencionConfigurar(): PendingIntent? = try {
        val abrir = Intent()
            .setClassName(this, CLASE_PRINCIPAL)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        PendingIntent.getActivity(
            this, 0, abrir, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    } catch (e: Exception) {
        null
    }

    private fun bucleLector(t: Tunel) {
        val buffer = ByteArray(TAMANO_LECTURA)
        try {
            while (!t.cerrado) {
                if (!t.hayDatos(ESPERA_LECTURA_MS)) continue
                if (t.cerrado) break
                val n = t.leer(buffer)
                if (n <= 0) break
                if (t.dePaso) continue   // no tiene rutas: no debería llegar nada; si llega, se descarta
                try {
                    procesar(t, buffer, n)
                } catch (e: Exception) {
                    // Paquete malformado o error puntual: se descarta.
                }
            }
        } catch (e: Exception) {
            // Error de lectura: el túnel se cerró o dejó de funcionar.
        }
        if (!t.cerrado) programar(ESPERA_REPARAR_MS) { tunelCaido(t) }
    }

    /** El hilo lector de [t] terminó sin que lo cerráramos: se intenta crear otro túnel. */
    private fun tunelCaido(t: Tunel) {
        synchronized(cerrojo) {
            if (destruido || tunel !== t || t.cerrado) return
            val vidaCorta = SystemClock.elapsedRealtime() - t.creado < VIDA_MINIMA_MS
            fallosSeguidos = if (vidaCorta) fallosSeguidos + 1 else 1
            // El de paso se cambia por otro de paso, salvo que haya empezado un bloqueo.
            val dePaso = t.dePaso && !estaActivo(Almacen.config)
            if (fallosSeguidos <= MAX_FALLOS && establecerConCerrojo(dePaso)) return
            t.cerrar()
            tunel = null
            sinRed = false
            Servicios.vpnActiva = false
            Servicios.vpnSinRed = false
        }
        Log.w(TAG, "El túnel dejó de funcionar; se detiene el filtro de sitios")
        stopSelf()
    }

    /**
     * Cada [REVISION_S] segundos y tras cada cambio de configuración. Renueva el túnel si empezó
     * o terminó un bloqueo, o si durante uno se agregaron sitios. En el túnel de paso, prueba la
     * red y vuelve al túnel normal cuando responde (ver [volverDeSinRed]).
     */
    private fun revisar() {
        if (destruido) return
        leerEstadoSistema()
        val config = Almacen.config
        val activo = estaActivo(config)
        if (sinRed) {
            volverDeSinRed(activo)
            return
        }
        val sitios = config.sitios.toHashSet()
        val agotados = sitiosAgotados(config)
        val renovado = synchronized(cerrojo) { aplicarRevision(activo, sitios, agotados) }
        if (renovado == false) Log.w(TAG, "No se pudo renovar el túnel")
    }

    /** Llamar con [cerrojo]. Devuelve null si no hizo falta renovar el túnel. */
    private fun aplicarRevision(activo: Boolean, sitios: Set<String>, agotados: Set<String>): Boolean? {
        // Si se acaba de pasar al túnel de paso, se encarga la próxima revisión (volverDeSinRed).
        if (destruido || tunel == null || sinRed) return null
        // Empieza o termina un bloqueo (cambia allowBypass y hace falta una red nueva), o durante
        // uno aparecieron sitios que antes no estaban.
        // También si se acabó el tiempo de un sitio: la red nueva empieza con la caché DNS vacía.
        val renovar = activo != ultimoActivo || (activo && !sitiosAplicados.containsAll(sitios)) ||
            !agotadosAplicados.containsAll(agotados)
        if (!renovar) {
            sitiosAplicados = sitios
            agotadosAplicados = agotados
            return null
        }
        // Si falla, ultimoActivo y sitiosAplicados no cambian y se reintenta en la próxima revisión.
        return establecerConCerrojo()
    }

    // --- Sin red: no dejar el teléfono sin DNS -------------------------------------------------

    /**
     * Ningún servidor respondió. Si pasa durante un buen rato con una red que Android validó
     * (comprobó que tiene internet), el problema es nuestro (por ejemplo, el sistema le quitó la
     * red a Bloqueador): se avisa y, fuera de un bloqueo, se pasa al túnel de paso para que el
     * resto del teléfono no se quede sin DNS. Con la red sin validar (sin señal, Wi-Fi sin
     * internet) no se hace nada: el problema es la red, no el filtro.
     */
    private fun anotarFallo(t: Tunel) {
        if (fallosRed.incrementAndGet() < FALLOS_SIN_RED) return
        if (!redPorDefectoValidada() || SystemClock.elapsedRealtime() - ultimoExito < SIN_RED_MS) return
        synchronized(cerrojo) {
            if (destruido || tunel !== t) return
            Servicios.vpnSinRed = true
        }
        if (!reglasActuales().activo && !Servicios.vpnLockdown) programar(0L) { pasarDePaso(t) }
    }

    private fun anotarExito() {
        fallosRed.set(0)
        ultimoExito = SystemClock.elapsedRealtime()
        if (pausas != 0) pausas = 0
        if (Servicios.vpnSinRed) {
            // En el túnel de paso, el aviso sigue hasta que responda la prueba de red (puede
            // llegar tarde la respuesta a una consulta del túnel anterior).
            synchronized(cerrojo) {
                if (!sinRed && !destruido) Servicios.vpnSinRed = false
            }
        }
    }

    /**
     * Fuera de un bloqueo, cambia [t] por el túnel de paso hasta que el DNS real vuelva a
     * responder. El de paso no tiene rutas y usa los DNS reales, así que las apps resuelven sin
     * pasar por el filtro. La VPN sigue conectada, así que Android no detiene el servicio, y
     * este sigue probando la red (ver [volverDeSinRed]).
     */
    private fun pasarDePaso(t: Tunel) {
        val dePaso = synchronized(cerrojo) {
            if (destruido || tunel !== t || t.dePaso || fallosRed.get() < FALLOS_SIN_RED) return
            // Durante un bloqueo el filtro nunca se hace a un lado. Con la red sin validar, el
            // problema es la red. Con «Bloquear conexiones sin VPN», lo que sale por fuera del
            // túnel no llega, así que el de paso tampoco daría internet.
            if (estaActivo(Almacen.config) || !redPorDefectoValidada() || Servicios.vpnLockdown) return
            val creado = establecerConCerrojo(dePaso = true)
            if (!creado) {
                // Si justo empezó un bloqueo, el túnel normal se queda.
                if (estaActivo(Almacen.config)) return
                // Último recurso: soltar el túnel, para no dejar el teléfono sin DNS. La
                // revisión periódica vuelve a intentar el túnel de paso.
                t.cerrar()
                tunel = null
                sinRed = true
                Servicios.vpnActiva = false
                Servicios.vpnSinRed = true
            }
            pausas++
            pruebasFallidas = 0
            reintentarEn = SystemClock.elapsedRealtime() + esperaReintento(pausas)
            creado
        }
        if (dePaso) {
            Log.w(TAG, "El filtro no consigue salir a internet; se usa el túnel de paso hasta que vuelva la red")
        } else {
            Log.w(TAG, "El filtro no consigue salir a internet y no se pudo crear el túnel de paso; se suelta el túnel")
        }
    }

    /**
     * En el túnel de paso: vuelve al túnel normal en cuanto empieza un bloqueo (aunque no haya
     * red: el bloqueo manda) o cuando el DNS real vuelve a responder. Mientras tanto, el túnel
     * de paso sigue en pie: como no tiene DNS propios, Android usa los de la red actual.
     */
    private fun volverDeSinRed(activo: Boolean) {
        if (!activo) {
            renovarDePaso()
            if (SystemClock.elapsedRealtime() < reintentarEn) return
            // Nuestra app está fuera del túnel: se puede probar la red sin pasar por él.
            if (preguntarRed(PaquetesDns.consultaPrueba(Random.nextInt(0x10000))) == null) {
                pruebasFallidas++
                reintentarEn = SystemClock.elapsedRealtime() +
                    minOf(PRUEBA_MAX_MS, esperaReintento(pruebasFallidas))
                return
            }
        }
        val listo = synchronized(cerrojo) {
            if (destruido || !sinRed) return
            val creado = establecerConCerrojo()
            // La prueba de red acaba de responder. Durante un bloqueo, el aviso sigue hasta que
            // responda una consulta (ver anotarExito).
            if (creado && !activo) Servicios.vpnSinRed = false
            creado
        }
        if (!listo) {
            reintentarEn = SystemClock.elapsedRealtime() + esperaReintento(pausas)
            Log.w(TAG, "No se pudo volver a crear el túnel")
        }
    }

    /**
     * Fuera de un bloqueo, con el filtro pausado: crea el túnel de paso si falta (por ejemplo,
     * porque no se pudo crear al pausar). Si ya existe no hace nada: sigue solo los DNS de la red.
     */
    private fun renovarDePaso() {
        synchronized(cerrojo) {
            if (destruido || !sinRed || tunel != null) return
            if (!establecerConCerrojo(dePaso = true)) Log.w(TAG, "No se pudo crear el túnel de paso")
        }
    }

    /** True si Android validó la red por defecto, es decir, si comprobó que tiene internet. */
    private fun redPorDefectoValidada(): Boolean {
        val red = redPorDefecto ?: return false
        return red == redValidada
    }

    /** 15 s, 30 s, 1 min... hasta 30 min, según [veces] (pausas o pruebas fallidas seguidas). */
    private fun esperaReintento(veces: Int): Long =
        minOf(REINTENTO_MAX_MS, REINTENTO_BASE_MS shl (veces - 1).coerceIn(0, 8))

    // --- Consultas --------------------------------------------------------------------------

    private fun procesar(t: Tunel, paquete: ByteArray, largo: Int) {
        val consulta = PaquetesDns.analizar(paquete, largo) ?: return        // no es DNS por IPv4/UDP
        val nombre = PaquetesDns.nombreConsultado(consulta.dns) ?: return   // consulta malformada
        val r = reglasActuales()
        if (PaquetesDns.bloquearConsulta(nombre, r.activo, r.sitios, dnsPrivado, r.agotados)) {
            // Se responde al instante, sin salir del teléfono.
            val respuesta = PaquetesDns.respuestaNxdomain(consulta.dns) ?: return
            PaquetesDns.envolverRespuesta(consulta, respuesta)?.let { t.escribir(it) }
        } else {
            trabajadores?.execute(Runnable {
                try {
                    reenviar(t, consulta)
                } catch (e: Exception) {
                    // Consulta perdida: el sistema la reintenta.
                }
            })
        }
    }

    private fun reglasActuales(): Reglas {
        val ahora = SystemClock.elapsedRealtime()
        val previas = reglas
        if (previas != null && ahora - previas.calculadas < VIGENCIA_REGLAS_MS) return previas
        val config = Almacen.config
        val activo = estaActivo(config)
        val sitios = if (previas != null && previas.fuente === config.sitios) previas.sitios else config.sitios.toHashSet()
        val nuevas = Reglas(ahora, activo, config.sitios, sitios, sitiosAgotados(config))
        reglas = nuevas
        // Si el bloqueo acaba de empezar o de terminar, renovar el túnel cuanto antes (ver revisar).
        if (activo != (previas?.activo ?: false)) programar(0L) { revisar() }
        return nuevas
    }

    /** Reenvía [consulta] al DNS real y escribe la respuesta en el túnel. */
    private fun reenviar(t: Tunel, consulta: PaquetesDns.Consulta) {
        val respuesta = preguntarRed(consulta.dns) { t.cerrado }
        if (respuesta == null) {
            if (!t.cerrado && !destruido) anotarFallo(t)
            return
        }
        anotarExito()
        PaquetesDns.envolverRespuesta(consulta, respuesta)?.let { t.escribir(it) }
    }

    /**
     * Pregunta a los servidores DNS de la red real, empezando por el último que respondió.
     * Todos menos el último esperan poco, para que un servidor caído no frene cada consulta.
     * Si la respuesta llega truncada (TC=1), la pide completa por TCP. Devuelve null si
     * ninguno respondió o si [cancelado] se cumple.
     */
    private fun preguntarRed(dns: ByteArray, cancelado: () -> Boolean = { false }): ByteArray? {
        val lista = dnsReales.ifEmpty { RESPALDO }
        val preferido = servidorPreferido
        val servidores = if (preferido != null && preferido in lista) {
            listOf(preferido) + lista.filter { it != preferido }
        } else {
            lista
        }
        for ((i, servidor) in servidores.withIndex()) {
            if (cancelado() || destruido) return null
            val espera = if (i == servidores.lastIndex) ESPERA_DNS_MS else ESPERA_CORTA_MS
            val respuesta = preguntar(servidor, dns, espera) ?: continue
            servidorPreferido = servidor
            if (!PaquetesDns.truncada(respuesta)) return respuesta
            return preguntarTcp(servidor, dns) ?: respuesta   // si TCP falla, va la truncada
        }
        return null
    }

    private fun preguntar(servidor: InetAddress, consulta: ByteArray, esperaMs: Int): ByteArray? {
        val socket = try {
            DatagramSocket()
        } catch (e: Exception) {
            return null
        }
        sockets += socket
        try {
            if (destruido) return null
            protect(socket)   // por si acaso: nuestra app ya está fuera del túnel
            socket.connect(servidor, PaquetesDns.PUERTO_DNS)
            socket.send(DatagramPacket(consulta, consulta.size, servidor, PaquetesDns.PUERTO_DNS))
            val buffer = bufferRespuesta.get() ?: ByteArray(PaquetesDns.MAX_CARGA_UDP)
            val limite = SystemClock.elapsedRealtime() + esperaMs
            var restante = esperaMs.toLong()
            while (restante > 0L) {
                socket.soTimeout = restante.toInt()
                val recibido = DatagramPacket(buffer, buffer.size)
                socket.receive(recibido)
                val n = recibido.length
                // Debe ser la respuesta a esta consulta (mismo ID).
                if (PaquetesDns.respondeA(buffer, n, consulta)) return buffer.copyOf(n)
                restante = limite - SystemClock.elapsedRealtime()
            }
            return null
        } catch (e: Exception) {
            return null   // tiempo agotado, servidor inalcanzable o red caída: se prueba el siguiente
        } finally {
            sockets -= socket
            socket.close()
        }
    }

    /**
     * Repite [consulta] por TCP, para cuando la respuesta por UDP llegó truncada. Por TCP cada
     * mensaje lleva delante su largo (2 bytes). Devuelve null si falla o si la respuesta no
     * cabe en un datagrama de vuelta al túnel.
     */
    private fun preguntarTcp(servidor: InetAddress, consulta: ByteArray): ByteArray? {
        val mensaje = PaquetesDns.conLargo(consulta) ?: return null
        val socket = Socket()
        sockets += socket
        try {
            if (destruido) return null
            socket.soTimeout = ESPERA_DNS_MS   // de paso crea el descriptor, que protect() necesita
            protect(socket)                    // por si acaso: nuestra app ya está fuera del túnel
            socket.connect(InetSocketAddress(servidor, PaquetesDns.PUERTO_DNS), ESPERA_DNS_MS)
            val salida = socket.getOutputStream()
            salida.write(mensaje)
            salida.flush()
            val entrada = DataInputStream(socket.getInputStream())
            val largo = entrada.readUnsignedShort()
            if (largo > PaquetesDns.MAX_CARGA_UDP) return null
            val respuesta = ByteArray(largo)
            entrada.readFully(respuesta)
            return if (PaquetesDns.respondeA(respuesta, largo, consulta)) respuesta else null
        } catch (e: Exception) {
            return null
        } finally {
            sockets -= socket
            cerrarSinError(socket)
        }
    }

    // --- Red real y estado del sistema ----------------------------------------------------------

    private fun registrarRed() {
        try {
            val cm = getSystemService(ConnectivityManager::class.java) ?: return
            cm.registerDefaultNetworkCallback(callbackRed)
            conectividad = cm
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo vigilar la red; se usarán servidores DNS de respaldo", e)
        }
    }

    private fun desregistrarRed() {
        val cm = conectividad ?: return
        conectividad = null
        try {
            cm.unregisterNetworkCallback(callbackRed)
        } catch (e: Exception) {
            // No estaba registrado.
        }
    }

    private fun utilizable(direccion: InetAddress): Boolean =
        !direccion.isAnyLocalAddress && !direccion.isLoopbackAddress && direccion.hostAddress !in DNS_FALSOS

    /** Servidor del «DNS privado» en modo estricto (Android 9+), en minúsculas; null si no hay. */
    private fun leerDnsPrivado(lp: LinkProperties): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        val nombre = lp.privateDnsServerName ?: try {
            if (Settings.Global.getString(contentResolver, "private_dns_mode") == "hostname") {
                Settings.Global.getString(contentResolver, "private_dns_specifier")
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
        return nombre?.trim()?.trimEnd('.')?.lowercase()?.takeIf { it.isNotEmpty() }
    }

    /** «VPN siempre activada» y «Bloquear conexiones sin VPN». Solo se pueden leer desde Android 10. */
    private fun leerEstadoSistema() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            val siempreActiva = isAlwaysOn()
            val lockdown = isLockdownEnabled()
            // Con el cerrojo, para no pisar lo que dejó onDestroy si el servicio se está cerrando.
            synchronized(cerrojo) {
                if (destruido) return
                Servicios.vpnSiempreActiva = siempreActiva
                Servicios.vpnLockdown = lockdown
            }
        } catch (e: Exception) {
            // Se vuelve a leer en la próxima revisión.
        }
    }

    /**
     * Android 8-11, arranque desde el receptor: startForeground() con un aviso mínimo. Se quita
     * en cuanto el túnel está listo o el servicio termina.
     */
    private fun entrarEnPrimerPlano() {
        if (enPrimerPlano || Build.VERSION.SDK_INT > Build.VERSION_CODES.R) return
        try {
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CANAL_ARRANQUE, "Arranque del bloqueo de sitios", NotificationManager.IMPORTANCE_MIN)
            )
            val aviso = Notification.Builder(this, CANAL_ARRANQUE)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("Activando el bloqueo de sitios…")
                .build()
            startForeground(ID_AVISO, aviso)
            enPrimerPlano = true
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo mostrar el aviso de arranque", e)
        }
    }

    private fun salirDePrimerPlano() {
        if (!enPrimerPlano) return
        enPrimerPlano = false
        seguro { stopForeground(STOP_FOREGROUND_REMOVE) }
    }

    // --- Utilidades -------------------------------------------------------------------------

    private fun estaActivo(config: Config): Boolean = try {
        config.estado().activo
    } catch (e: Exception) {
        false
    }

    private fun sitiosAgotados(config: Config): Set<String> = try {
        AlmacenUso.sitiosAgotados(config)
    } catch (e: Exception) {
        emptySet()
    }

    private fun programar(retrasoMs: Long, accion: () -> Unit) {
        val r = reloj ?: return
        try {
            r.schedule(Runnable { seguro(accion) }, retrasoMs, TimeUnit.MILLISECONDS)
        } catch (e: RejectedExecutionException) {
            // El servicio se está cerrando.
        }
    }

    private inline fun seguro(bloque: () -> Unit) {
        try {
            bloque()
        } catch (e: Exception) {
            Log.w(TAG, "Error interno del filtro de sitios", e)
        }
    }

    private fun cerrarSinError(recurso: Closeable) {
        try {
            recurso.close()
        } catch (e: IOException) {
            // Ya estaba cerrado.
        }
    }

    private fun fabricaHilos(nombre: String): ThreadFactory {
        val contador = AtomicInteger()
        return ThreadFactory { tarea ->
            Thread(tarea, "$nombre-${contador.incrementAndGet()}").apply { isDaemon = true }
        }
    }

    private companion object {
        const val TAG = "Bloqueador.Vpn"
        const val SESION = "Bloqueador"
        const val DIRECCION_TUNEL = "10.111.222.1"

        /** Servidores DNS falsos (los únicos destinos que entran al túnel). Se alternan. */
        val DNS_FALSOS = arrayOf("10.111.222.2", "10.111.222.3")

        const val CLASE_PRINCIPAL = "com.bloqueador.android.ui.MainActivity"

        /** Si la red no informa servidores DNS. */
        val RESPALDO: List<InetAddress> = listOf(
            InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1)),
            InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8)),
        )

        const val ESPERA_DNS_MS = 3_000          // el último servidor de la lista (o el único)
        const val ESPERA_CORTA_MS = 1_500        // los demás
        const val HILOS_DNS = 8
        const val COLA_DNS = 64
        const val TAMANO_LECTURA = 32 * 1024
        const val ESPERA_LECTURA_MS = 5_000
        const val VIGENCIA_REGLAS_MS = 1_000L
        const val REVISION_S = 15L
        const val RETRASO_CAMBIO_MS = 500L
        const val ESPERA_REPARAR_MS = 2_000L
        const val VIDA_MINIMA_MS = 30_000L
        const val MAX_FALLOS = 3

        /** Sin red: tantas consultas seguidas sin respuesta durante tanto tiempo. */
        const val FALLOS_SIN_RED = 10
        const val SIN_RED_MS = 30_000L
        const val REINTENTO_BASE_MS = 15_000L
        const val REINTENTO_MAX_MS = 30 * 60_000L

        /** En el túnel de paso, la espera máxima entre dos pruebas de red que fallan. */
        const val PRUEBA_MAX_MS = 5 * 60_000L

        const val CANAL_ARRANQUE = "arranque"
        const val ID_AVISO = 1
    }
}
