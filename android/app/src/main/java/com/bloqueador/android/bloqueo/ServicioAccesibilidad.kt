package com.bloqueador.android.bloqueo

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.app.KeyguardManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.telecom.TelecomManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import com.bloqueador.android.datos.Almacen
import com.bloqueador.android.datos.AlmacenUso
import com.bloqueador.android.dominio.Config
import com.bloqueador.android.dominio.Dominios
import com.bloqueador.android.dominio.Limite
import com.bloqueador.android.dominio.Paquetes
import com.bloqueador.android.dominio.TipoLimite
import com.bloqueador.android.dominio.limitesEnUso

/**
 * Detecta qué app pasa a primer plano y, si está bloqueada, la tapa con [PantallaBloqueoActivity].
 * Durante un bloqueo también revisa las ventanas que se ven sin tener el foco: la otra mitad de
 * la pantalla dividida, las ventanas flotantes y las PiP. Si la pantalla de bloqueo ya se ve, no
 * la vuelve a abrir en cada revisión, así que una app bloqueada en la otra mitad de la pantalla
 * dividida o en una ventana flotante sigue a la vista.
 *
 * También cuenta el tiempo de uso de las apps y los sitios con límite diario y, cuando se acaba,
 * los tapa hasta las 00:00, haya o no un bloqueo.
 *
 * Solo usa el nombre de paquete y la clase de cada ventana. La única excepción: si pusiste
 * límite a algún sitio, lee la barra de direcciones del navegador que está a la vista (solo esa
 * vista) para saber qué sitio es; la dirección no se guarda. Todo corre en el hilo principal
 * (eventos y revisión periódica).
 */
class ServicioAccesibilidad : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var conectado = false

    // Caché de launchers, teclado, apps de teléfono y paquetes de Ajustes (se refresca cada REFRESCO_MS).
    private var lanzadores: Set<String> = emptySet()
    private var teclado: String? = null
    private var marcadores: Set<String> = emptySet()
    private var estrictos: Set<String> = Paquetes.AJUSTES
    private var cacheLeidaEn = 0L

    // "paquete/clase" -> true si esa clase es una actividad del paquete.
    private val actividades = HashMap<String, Boolean>()

    // Última actividad que se vio de cada paquete de Ajustes (para el modo estricto).
    private val ultimaActividad = HashMap<String, String>()

    // Id de cada ventana PiP de una app bloqueada -> intentos de cerrarla.
    private val intentosPip = HashMap<Int, Int>()

    // Anti-ráfagas: último paquete bloqueado y cuándo (SystemClock.elapsedRealtime).
    private var ultimoPaquete: String? = null
    private var ultimoBloqueoEn = 0L

    // Cuándo se pidió abrir la pantalla de bloqueo por última vez (SystemClock.elapsedRealtime).
    private var pantallaAbiertaEn = 0L

    // Veces seguidas que la pantalla de bloqueo no apareció, y Servicios.pantallaBloqueoVista en
    // la última. Si la pantalla apareció en medio, la cuenta vuelve a empezar.
    private var fallosPantalla = 0
    private var vistaEnFallo = 0L

    // Cuándo pulsamos inicio por un fallo que sí contó (SystemClock.elapsedRealtime).
    private var inicioPulsadoEn = 0L

    // True mientras hay una revisión de ventanas programada (agrupa los eventos seguidos).
    private var ventanasPendientes = false

    // Última vez que se contó el tiempo de uso (SystemClock.elapsedRealtime), o 0.
    private var ultimoConteo = 0L

    /** Revisión periódica mientras el servicio está conectado. */
    private val revision = object : Runnable {
        override fun run() {
            if (!conectado) return
            try {
                revisar()
            } catch (e: Exception) {
                // Nunca debe cortar la revisión periódica.
            }
            handler.postDelayed(this, INTERVALO_MS)
        }
    }

    /** Respaldo por si la pantalla de bloqueo no llega a verse. */
    private val comprobacionPantalla = Runnable { comprobarPantalla() }

    /** Revisión de ventanas después de TYPE_WINDOWS_CHANGED. */
    private val revisionVentanas = Runnable {
        ventanasPendientes = false
        try {
            val config = Almacen.config
            val activo = config.estado().activo
            if (conectado && (activo || AlmacenUso.agotados(config).any { it.tipo == TipoLimite.APP })) {
                revisarVentanas(config, activo)
            }
        } catch (e: Exception) {
            // Se vuelve a intentar en la próxima revisión.
        }
    }

    /** Al abrir un navegador: si muestra un sitio sin tiempo, lo tapa sin esperar a la revisión. */
    private val revisionSitio = Runnable {
        try {
            val config = Almacen.config
            val vista = if (conectado) primerPlano(config) else null
            val sitio = vista?.sitio
            if (vista != null && sitio != null) {
                AlmacenUso.sitioAgotado(sitio, config)?.let { bloquearSitio(vista.paquete, it) }
            }
        } catch (e: Exception) {
            // Lo ve la próxima revisión.
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        conectado = true
        Servicios.accesibilidadConectada = true
        // Si el filtro de sitios no corre (por ejemplo, porque se reinició el proceso), lo vuelve a
        // arrancar. Tras reiniciar el teléfono lo arranca ReceptorArranque, que llama a prepare();
        // aquí no se llama, para no desconectar otra VPN fuera de un bloqueo.
        try {
            if (!Servicios.vpnActiva && Servicios.permisoVpnConcedido(this)) {
                Servicios.iniciarVpn(this)
            }
        } catch (e: Exception) {
            // La revisión periódica lo reintenta durante un bloqueo con sitios.
        }
        handler.removeCallbacks(revision)
        handler.postDelayed(revision, INTERVALO_MS)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        try {
            when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    val paquete = event.packageName?.toString()
                    if (!paquete.isNullOrEmpty()) {
                        alCambiarVentana(paquete, event.className?.toString(), event.windowId)
                    }
                }
                AccessibilityEvent.TYPE_WINDOWS_CHANGED -> programarRevisionVentanas()
                else -> Unit   // accesibilidad.xml solo pide estos dos tipos
            }
        } catch (e: Exception) {
            // Un error puntual no debe detener el servicio.
        }
    }

    override fun onInterrupt() {
        // No hay nada que interrumpir: el servicio no da respuesta hablada ni táctil.
    }

    override fun onUnbind(intent: Intent?): Boolean {
        desconectar()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        desconectar()
        super.onDestroy()
    }

    private fun desconectar() {
        conectado = false
        ventanasPendientes = false
        Servicios.accesibilidadConectada = false
        handler.removeCallbacksAndMessages(null)
    }

    // ------------------------------------------------------------------ reglas

    /**
     * Una ventana de [paquete] pasó a primer plano. [clase] es su className: no siempre es una
     * actividad. [ventana] es el id de la ventana que envió el evento.
     */
    private fun alCambiarVentana(paquete: String, clase: String?, ventana: Int) {
        val config = Almacen.config
        val actividad = clase?.takeIf { it.isNotEmpty() }
        if (paquete in config.apps || AlmacenUso.appAgotada(paquete, config) != null) {
            // Las superposiciones y burbujas de una app bloqueada no son actividades: no se tocan,
            // para no sacarte de lo que estás usando. Si las usas, revisar() las ve como ventana activa.
            if (actividad != null && !esActividad(paquete, actividad)) return
        } else if (actividad != null && esEstricto(paquete) && esActividad(paquete, actividad)) {
            // Se guarda siempre, no solo durante un bloqueo, para que nunca quede un valor viejo.
            ultimaActividad[paquete] = actividad
        }
        evaluar(paquete, config, ventana, porEvento = true)
        if (paquete in Paquetes.BARRAS_DE_DIRECCION && AlmacenUso.sitiosAgotados(config).isNotEmpty()) {
            handler.removeCallbacks(revisionSitio)
            handler.postDelayed(revisionSitio, ESPERA_SITIO_MS)
        }
    }

    /**
     * Aplica la regla de bloqueo a [paquete], la app que está en primer plano. [ventana] es el id
     * de su ventana, si se conoce. [porEvento] es true si acaba de pasar a primer plano, y false
     * si la encontró la revisión periódica.
     */
    private fun evaluar(paquete: String, config: Config, ventana: Int = SIN_VENTANA, porEvento: Boolean = false) {
        if (paquete.isEmpty()) return
        // Primero lo más barato; los launchers, el teclado y las ventanas solo se consultan si hace falta.
        val agotada = AlmacenUso.appAgotada(paquete, config)
        val activo: Boolean
        if (agotada == null) {
            if (!estaEnLista(paquete, config)) return
            if (!config.estado().activo) return
            activo = true
        } else {
            activo = config.estado().activo
        }
        if (ignorado(paquete)) return
        // Lo que solo bloquea el modo estricto se tapa si es una pantalla normal (una actividad o
        // un diálogo), no si es una superposición: la barra lateral de juegos de Xiaomi o el menú
        // de asistencia de Samsung viven en paquetes de Ajustes y no sirven para desactivar nada.
        // No se usa esActividad(): desde Android 11 da false para los paquetes que la app no ve,
        // como el diálogo de VPN, y dejarían de bloquearse.
        if (paquete !in config.apps && agotada == null && esSuperposicion(ventana)) return
        bloquear(paquete, porEvento, limiteAgotado(paquete, config, activo))
    }

    /**
     * El límite de [paquete] si se bloquea solo porque se acabó su tiempo de hoy. Si además lo
     * bloquea un bloqueo activo, null: la pantalla muestra el bloqueo.
     */
    private fun limiteAgotado(paquete: String, config: Config, activo: Boolean): Limite? {
        val limite = AlmacenUso.appAgotada(paquete, config) ?: return null
        return if (activo && estaEnLista(paquete, config)) null else limite
    }

    /** Tapa el navegador [navegador], que muestra un sitio cuyo tiempo de hoy ([limite]) se acabó. */
    private fun bloquearSitio(navegador: String, limite: Limite) {
        if (!ignorado(navegador)) bloquear(navegador, porEvento = false, limite = limite)
    }

    /** Pantalla de bloqueo para [paquete]; con [limite], explica que se acabó su tiempo de hoy. */
    private fun intentBloqueo(paquete: String, limite: Limite?): Intent {
        val nombre = if (limite?.tipo == TipoLimite.SITIO) limite.nombre else nombreVisible(paquete)
        return PantallaBloqueoActivity.crearIntent(this, nombre, paquete, limite)
    }

    /** True si [paquete] se bloquea durante un bloqueo activo. */
    private fun estaEnLista(paquete: String, config: Config): Boolean {
        if (paquete in config.apps) return true
        if (!config.modoEstricto) return false
        // El diálogo de permiso de VPN se deja abrir un momento cuando la app lo pide.
        if (paquete == Paquetes.DIALOGO_VPN && System.currentTimeMillis() < Servicios.permitirDialogoVpnHasta) {
            return false
        }
        // Las tiendas, para que no puedas instalar otra app que se salte el bloqueo.
        if (paquete in Paquetes.TIENDAS) return true
        // Ajustes, salvo las pantallas que hacen falta y no sirven para desactivar el bloqueo.
        return esEstricto(paquete) && !Paquetes.actividadPermitida(ultimaActividad[paquete])
    }

    /** True si [paquete] abre pantallas de Ajustes en este teléfono (los del fabricante incluidos). */
    private fun esEstricto(paquete: String): Boolean {
        refrescarCache()
        return paquete in estrictos
    }

    /**
     * Paquetes que nunca se tocan: el propio Bloqueador, la barra del sistema, las apps protegidas
     * (teléfono, emergencias), los launchers, el teclado y las apps de llamadas.
     */
    private fun ignorado(paquete: String): Boolean {
        if (paquete == packageName || paquete == SYSTEMUI || paquete in Paquetes.PROTEGIDOS) return true
        refrescarCache()
        return paquete in lanzadores || paquete == teclado || paquete in marcadores
    }

    /**
     * Tapa [paquete] con la pantalla de bloqueo. [porEvento] es true si la app acaba de pasar a
     * primer plano, y false si la encontró una revisión.
     */
    private fun bloquear(paquete: String, porEvento: Boolean = false, limite: Limite? = null) {
        // Con una app fijada (Fijar app), Android no deja salir de ella ni abrir otra pantalla, y
        // reintentarlo cada pocos segundos solo repetiría el aviso del sistema.
        if (pantallaFijada()) return
        val ahora = SystemClock.elapsedRealtime()
        if (Servicios.pantallaBloqueoEnFrente) {
            // La pantalla de bloqueo ya se ve, pero la app también: está en la otra mitad de la
            // pantalla dividida, en una ventana flotante o en una burbuja. Volver a abrirla en
            // cada revisión no la tapa y la haría parpadear sin fin. Solo se vuelve a abrir
            // cuando la app pasa a primer plano: así se cierra su burbuja y se muestra su nombre.
            // No hace falta el respaldo de inicio, porque la pantalla ya está a la vista.
            if (porEvento && ahora - ultimoBloqueoEn >= ANTI_RAFAGA_MS) {
                ultimoPaquete = paquete
                ultimoBloqueoEn = ahora
                abrir(intentBloqueo(paquete, limite))
            }
            return
        }
        if (paquete == ultimoPaquete && ahora - ultimoBloqueoEn < ANTI_RAFAGA_MS) return
        ultimoPaquete = paquete
        ultimoBloqueoEn = ahora

        // Se abre directamente encima de la app, sin pulsar inicio antes: el botón de inicio hace
        // que las apps de video pasen a PiP y, hasta Android 11, retrasa unos 5 s la pantalla nueva.
        val abierta = abrir(intentBloqueo(paquete, limite))
        pantallaAbiertaEn = ahora
        handler.removeCallbacks(comprobacionPantalla)
        handler.postDelayed(comprobacionPantalla, if (abierta) ESPERA_PANTALLA_MS else 0L)
    }

    /** Abre la pantalla de bloqueo. Devuelve false si Android lo rechazó con un error. */
    private fun abrir(intent: Intent): Boolean = try {
        // Los servicios de accesibilidad pueden abrir actividades desde segundo plano.
        startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }

    /**
     * Si la pantalla de bloqueo no llegó a verse (en Xiaomi, sin el permiso de ventanas emergentes
     * en segundo plano, Android la descarta sin avisar), vuelve a la pantalla de inicio. Si pasa
     * dos veces seguidas, lo guarda para que la interfaz lo explique.
     */
    private fun comprobarPantalla() {
        val vista = Servicios.pantallaBloqueoVista
        if (!conectado || vista >= pantallaAbiertaEn || Servicios.pantallaBloqueoEnFrente) return
        // Con la pantalla apagada o el teléfono bloqueado, aparece al desbloquearlo.
        if (!pantallaEnUso() || pantallaFijada()) return
        // Hasta Android 11, después de pulsar inicio Android puede retrasar unos 5 s las
        // pantallas que abren las apps en segundo plano (por ejemplo, si vuelves a la app desde
        // Recientes). Si este fallo cae en esa espera, provocada por nuestro respaldo anterior,
        // no cuenta. La espera se mide desde un fallo que sí contó, así que no se encadena: el
        // aviso se demora como mucho esos 5 s.
        val enEspera = Build.VERSION.SDK_INT <= Build.VERSION_CODES.R &&
            inicioPulsadoEn != 0L && pantallaAbiertaEn - inicioPulsadoEn < ESPERA_TRAS_INICIO_MS
        try {
            performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        } catch (e: Exception) {
            // No hay nada más que hacer.
        }
        if (enEspera) return
        inicioPulsadoEn = SystemClock.elapsedRealtime()
        // Un solo fallo puede ser un arranque lento. Por eso solo se avisa con dos seguidos.
        fallosPantalla = if (vista == vistaEnFallo) fallosPantalla + 1 else 1
        vistaEnFallo = vista
        if (fallosPantalla >= FALLOS_PARA_AVISAR) Servicios.marcarPantallaNoAparece(this, true)
    }

    /** False si la pantalla está apagada o el teléfono está bloqueado. */
    private fun pantallaEnUso(): Boolean = try {
        val encendida = getSystemService(PowerManager::class.java)?.isInteractive ?: true
        val bloqueado = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked ?: false
        encendida && !bloqueado
    } catch (e: Exception) {
        true
    }

    /** True si hay una app fijada en pantalla (Fijar app) o en modo quiosco. */
    private fun pantallaFijada(): Boolean = try {
        val modo = getSystemService(ActivityManager::class.java)?.lockTaskModeState
        modo != null && modo != ActivityManager.LOCK_TASK_MODE_NONE
    } catch (e: Exception) {
        false
    }

    // ------------------------------------------------------- revisión periódica

    /**
     * Cubre un horario que empieza mientras ya estás dentro de una app bloqueada, revisa las
     * ventanas sin foco y vuelve a levantar la VPN si se cayó durante un bloqueo con sitios.
     */
    private fun revisar() {
        val config = Almacen.config
        val activo = config.estado().activo
        if (config.limites.isEmpty()) {
            ultimoConteo = 0L
            if (!activo) {
                intentosPip.clear()
                return
            }
        }
        val vista = try {
            primerPlano(config)
        } catch (e: Exception) {
            null
        }
        try {
            contarUso(config, vista)
        } catch (e: Exception) {
            // Se pierde este rato: la próxima revisión sigue contando.
        }
        val agotados = AlmacenUso.agotados(config)
        if (!activo && agotados.isEmpty()) {
            intentosPip.clear()
            return
        }
        try {
            if (vista != null) {
                evaluar(vista.paquete, config, vista.ventana)
                vista.sitio?.let { s -> AlmacenUso.sitioAgotado(s, config)?.let { bloquearSitio(vista.paquete, it) } }
            }
        } catch (e: Exception) {
            // Se vuelve a intentar en la próxima revisión.
        }
        try {
            revisarVentanas(config, activo)
        } catch (e: Exception) {
            // Ídem.
        }
        try {
            if (activo && config.sitios.isNotEmpty() && !Servicios.vpnActiva) {
                // Si otra VPN la desplazó para saltarse el bloqueo, la recupera.
                Servicios.recuperarVpnDuranteBloqueo(this)
            }
        } catch (e: Exception) {
            // Ídem.
        }
    }

    /** La app en primer plano, el id de su ventana y, si es un navegador, el sitio que muestra. */
    private class Vista(val paquete: String, val ventana: Int, val sitio: String?)

    /**
     * Paquete e id de la ventana activa: solo lee el nodo raíz, sin recorrer el árbol de vistas.
     * Si es un navegador conocido y hay límites de sitios, también lee su barra de direcciones.
     */
    private fun primerPlano(config: Config): Vista? {
        val raiz = rootInActiveWindow ?: return null
        return try {
            val paquete = raiz.packageName?.toString()
            if (paquete.isNullOrEmpty()) {
                null
            } else {
                val conSitios = config.limites.any { it.tipo == TipoLimite.SITIO }
                Vista(paquete, raiz.windowId, if (conSitios) leerSitio(raiz, paquete) else null)
            }
        } finally {
            reciclar(raiz)
        }
    }

    /** El sitio que muestra la barra de direcciones de [paquete] ("instagram.com"), o null. */
    private fun leerSitio(raiz: AccessibilityNodeInfo, paquete: String): String? {
        val ids = Paquetes.BARRAS_DE_DIRECCION[paquete] ?: return null
        for (id in ids) {
            val nodos = try {
                raiz.findAccessibilityNodeInfosByViewId("$paquete:id/$id")
            } catch (e: Exception) {
                null
            } ?: continue
            try {
                for (nodo in nodos) {
                    val texto = nodo.text?.toString() ?: continue
                    Dominios.normalizar(texto)?.let { return it }
                }
            } finally {
                nodos.forEach { reciclar(it) }
            }
        }
        return null
    }

    /**
     * Suma el rato desde la revisión anterior a la app (y al sitio) que está a la vista, si tienen
     * límite. No cuenta con la pantalla apagada ni si pasó mucho (el teléfono estuvo dormido).
     */
    private fun contarUso(config: Config, vista: Vista?) {
        val ahora = SystemClock.elapsedRealtime()
        val paso = if (ultimoConteo == 0L) 0L else ahora - ultimoConteo
        ultimoConteo = ahora
        if (vista == null || paso <= 0L || paso > MAX_PASO_MS || vista.paquete == packageName) return
        val enUso = limitesEnUso(config, vista.paquete, vista.sitio)
        if (enUso.isEmpty() || !pantallaEnUso()) return
        val cambio = AlmacenUso.sumar(enUso, paso)
        for (l in cambio.porAgotarse) {
            val nombre = if (l.tipo == TipoLimite.SITIO) l.nombre else nombreVisible(l.nombre)
            try {
                Toast.makeText(this, "Te quedan 5 minutos de $nombre por hoy", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                // Solo es un aviso.
            }
        }
    }

    private fun programarRevisionVentanas() {
        if (!conectado || ventanasPendientes) return
        ventanasPendientes = true
        handler.postDelayed(revisionVentanas, ESPERA_VENTANAS_MS)
    }

    /**
     * Revisa las ventanas de apps que se ven aunque no tengan el foco: la otra mitad de la
     * pantalla dividida, una ventana flotante o una PiP. Las PiP de las apps de tu lista se
     * cierran; si hay otra ventana bloqueada, se abre la pantalla de bloqueo (salvo que ya se vea).
     */
    private fun revisarVentanas(config: Config, activo: Boolean) {
        val vistas = HashSet<Int>()
        var bloqueada: String? = null
        var oculta: Int? = null
        for (ventana in ventanas()) {
            try {
                if (ventana.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                vistas += ventana.id
                val paquete = revisarVentana(ventana, config, activo)
                if (bloqueada == null) bloqueada = paquete
                if (oculta == null && ventana.isActive && ventanaOculta(ventana)) oculta = ventana.id
            } finally {
                reciclar(ventana)
            }
        }
        intentosPip.keys.retainAll(vistas)
        if (bloqueada != null) {
            bloquear(bloqueada, limite = limiteAgotado(bloqueada, config, activo))
        } else if (oculta != null && activo) {
            revisarOculta(oculta, config)
        }
    }

    /** True si Android no deja ver el contenido de la ventana (ni de qué app es). */
    private fun ventanaOculta(ventana: AccessibilityWindowInfo): Boolean {
        val raiz = ventana.root ?: return true
        reciclar(raiz)
        return false
    }

    /**
     * Desde Android 14, algunas pantallas (por ejemplo, partes de Ajustes) se ocultan a los
     * servicios de accesibilidad: no mandan eventos y su ventana no dice de qué app es. Si la
     * ventana activa es una de esas, se pregunta a las estadísticas de uso qué app está en
     * primer plano. Hace falta que el usuario haya dado «Acceso a datos de uso».
     */
    private fun revisarOculta(ventana: Int, config: Config) {
        val (paquete, clase) = appEnPrimerPlano() ?: return
        if (paquete == packageName) return
        if (clase != null && paquete !in config.apps && esEstricto(paquete) && esActividad(paquete, clase)) {
            ultimaActividad[paquete] = clase
        }
        evaluar(paquete, config, ventana)
    }

    /**
     * Paquete y actividad de la última app que pasó a primer plano, según las estadísticas de
     * uso de los últimos minutos. Devuelve null sin «Acceso a datos de uso».
     */
    private fun appEnPrimerPlano(): Pair<String, String?>? {
        if (!Servicios.accesoUso(this)) return null
        return try {
            val uso = getSystemService(UsageStatsManager::class.java) ?: return null
            val ahora = System.currentTimeMillis()
            val eventos = uso.queryEvents(ahora - VENTANA_USO_MS, ahora) ?: return null
            @Suppress("DEPRECATION")
            val enFrente = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                UsageEvents.Event.ACTIVITY_RESUMED
            } else {
                UsageEvents.Event.MOVE_TO_FOREGROUND
            }
            val evento = UsageEvents.Event()
            var ultimo: Pair<String, String?>? = null
            while (eventos.hasNextEvent()) {
                eventos.getNextEvent(evento)
                if (evento.eventType == enFrente) ultimo = Pair(evento.packageName, evento.className)
            }
            ultimo
        } catch (e: Exception) {
            null
        }
    }

    /** Cierra la PiP de una app de tu lista. Si la ventana es de una app bloqueada, devuelve su paquete. */
    private fun revisarVentana(ventana: AccessibilityWindowInfo, config: Config, activo: Boolean): String? {
        val raiz = ventana.root ?: return null
        return try {
            val paquete = raiz.packageName?.toString()
            if (paquete.isNullOrEmpty()) return null
            val agotada = AlmacenUso.appAgotada(paquete, config) != null
            if (ventana.isInPictureInPictureMode) {
                // Solo las de tu lista: la PiP de una videollamada o de un mapa se respeta.
                if (((activo && paquete in config.apps) || agotada) && !ignorado(paquete)) cerrarPip(ventana.id, raiz)
                return null
            }
            paquete.takeIf { ((activo && estaEnLista(it, config)) || agotada) && !ignorado(it) }
        } finally {
            reciclar(raiz)
        }
    }

    private fun cerrarPip(id: Int, raiz: AccessibilityNodeInfo) {
        val intentos = intentosPip[id] ?: 0
        if (intentos >= MAX_INTENTOS_PIP) return
        val cerrada = raiz.performAction(AccessibilityNodeInfo.ACTION_DISMISS)
        // Si Android no deja cerrarla, no se insiste en cada revisión.
        intentosPip[id] = if (cerrada) intentos + 1 else MAX_INTENTOS_PIP
    }

    /** Ventanas visibles en todas las pantallas (Android 11+) o en la principal. */
    private fun ventanas(): List<AccessibilityWindowInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val porPantalla = windowsOnAllDisplays
            (0 until porPantalla.size()).flatMap { porPantalla.valueAt(it) }
        } else {
            windows
        }

    /**
     * True si la ventana [id] no es de una app sino una superposición o una ventana del sistema.
     * Si todavía no aparece en la lista de ventanas, o no se puede leer, devuelve false.
     */
    private fun esSuperposicion(id: Int): Boolean {
        if (id < 0) return false
        var tipo: Int? = null
        try {
            for (ventana in ventanas()) {
                try {
                    if (tipo == null && ventana.id == id) tipo = ventana.type
                } finally {
                    reciclar(ventana)
                }
            }
        } catch (e: Exception) {
            return false
        }
        return tipo != null && tipo != AccessibilityWindowInfo.TYPE_APPLICATION
    }

    @Suppress("DEPRECATION")
    private fun reciclar(nodo: AccessibilityNodeInfo) {
        // Desde Android 13 los nodos ya no se reutilizan y recycle() no hace nada.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            try {
                nodo.recycle()
            } catch (e: IllegalStateException) {
                // Ya estaba reciclado.
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun reciclar(ventana: AccessibilityWindowInfo) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            try {
                ventana.recycle()
            } catch (e: IllegalStateException) {
                // Ya estaba reciclada.
            }
        }
    }

    // ------------------------------------------------------------------- caché

    private fun refrescarCache() {
        val ahora = SystemClock.elapsedRealtime()
        if (cacheLeidaEn != 0L && ahora - cacheLeidaEn < REFRESCO_MS) return
        cacheLeidaEn = ahora
        teclado = leerTeclado()
        marcadores = leerMarcadores()
        val inicio = leerLanzadores()
        if (inicio != null) {
            lanzadores = inicio
            // Solo con launchers conocidos, para no bloquear nunca la pantalla de inicio.
            leerEstrictos()?.let { estrictos = it }
        }
        // Por si una app se actualizó y cambió sus actividades.
        actividades.clear()
    }

    /** Apps que responden a la pantalla de inicio (launchers). Devuelve null si falla. */
    private fun leerLanzadores(): Set<String>? = try {
        val inicio = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        consultarActividades(inicio)
            .mapNotNull { it.activityInfo?.packageName }
            // Ajustes trae un "inicio de reserva" (FallbackHome) que también responde a
            // CATEGORY_HOME; si no se excluye, el modo estricto nunca lo bloquearía.
            .filterNot { it in Paquetes.AJUSTES }
            .toSet()
    } catch (e: Exception) {
        null
    }

    /**
     * [Paquetes.AJUSTES] más las apps del sistema que abren las pantallas de Ajustes en este
     * teléfono (cada fabricante usa las suyas), sin launchers, apps protegidas ni la barra del
     * sistema. Devuelve null si falla.
     */
    private fun leerEstrictos(): Set<String>? = try {
        val propio = Uri.fromParts("package", packageName, null)
        val pantallas = listOf(
            Intent(Settings.ACTION_SETTINGS),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, propio),
            Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS),
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
            Intent(Settings.ACTION_VPN_SETTINGS),
            Intent(Intent.ACTION_DELETE, propio),
        )
        val fuera = lanzadores + marcadores + Paquetes.PROTEGIDOS + setOf(packageName, SYSTEMUI)
        val flags = PackageManager.MATCH_DEFAULT_ONLY or PackageManager.MATCH_SYSTEM_ONLY
        Paquetes.AJUSTES + pantallas
            .flatMap { consultarActividades(it, flags) }
            .mapNotNull { it.activityInfo?.packageName }
            .filterNot { it in fuera }
    } catch (e: Exception) {
        null
    }

    @Suppress("DEPRECATION")
    private fun consultarActividades(intent: Intent, flags: Int = 0): List<ResolveInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(flags.toLong()))
        } else {
            packageManager.queryIntentActivities(intent, flags)
        }

    /** Paquete del teclado actual ("com.ejemplo.teclado/.Servicio" -> "com.ejemplo.teclado"). */
    private fun leerTeclado(): String? = try {
        Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.let { id -> ComponentName.unflattenFromString(id)?.packageName ?: id.substringBefore('/') }
            ?.takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        teclado
    }

    /** Apps de llamadas (la predeterminada y la del sistema): nunca se bloquean. */
    private fun leerMarcadores(): Set<String> = try {
        val telecom = getSystemService(TelecomManager::class.java)
        setOfNotNull(
            telecom?.defaultDialerPackage,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) telecom?.systemDialerPackage else null,
        )
    } catch (e: Exception) {
        marcadores
    }

    /**
     * True si [clase] es una actividad de [paquete], y no una superposición, una burbuja o un
     * diálogo. Si no se puede saber, se trata como actividad.
     */
    private fun esActividad(paquete: String, clase: String): Boolean {
        val clave = "$paquete/$clase"
        actividades[clave]?.let { return it }
        val es = try {
            leerActividad(ComponentName(paquete, clase))
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        } catch (e: Exception) {
            return true
        }
        actividades[clave] = es
        return es
    }

    /** Lanza NameNotFoundException si [componente] no es una actividad instalada. */
    @Suppress("DEPRECATION")
    private fun leerActividad(componente: ComponentName) {
        val flags = PackageManager.MATCH_DISABLED_COMPONENTS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getActivityInfo(componente, PackageManager.ComponentInfoFlags.of(flags.toLong()))
        } else {
            packageManager.getActivityInfo(componente, flags)
        }
    }

    /** Nombre visible de la app; si no se puede leer, el nombre de paquete. */
    @Suppress("DEPRECATION")
    private fun nombreVisible(paquete: String): String = try {
        val pm = packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getApplicationInfo(paquete, PackageManager.ApplicationInfoFlags.of(0L))
        } else {
            pm.getApplicationInfo(paquete, 0)
        }
        pm.getApplicationLabel(info).toString().trim().ifEmpty { paquete }
    } catch (e: Exception) {
        paquete
    }

    private companion object {
        const val SYSTEMUI = "com.android.systemui"
        const val INTERVALO_MS = 3_000L          // revisión periódica
        const val ANTI_RAFAGA_MS = 1_500L        // mínimo entre dos bloqueos del mismo paquete
        const val REFRESCO_MS = 60_000L          // caché de launchers, teclado y paquetes de Ajustes
        const val ESPERA_PANTALLA_MS = 1_000L    // si la pantalla de bloqueo no se vio, se pulsa inicio
        const val ESPERA_VENTANAS_MS = 300L      // agrupa los eventos de ventanas seguidos
        const val MAX_INTENTOS_PIP = 3           // intentos de cerrar una misma ventana PiP
        const val FALLOS_PARA_AVISAR = 2         // veces seguidas sin pantalla de bloqueo antes de avisar
        const val ESPERA_TRAS_INICIO_MS = 5_500L // hasta Android 11: retraso de las apps tras pulsar inicio
        const val SIN_VENTANA = -1               // id de ventana desconocido
        const val VENTANA_USO_MS = 10 * 60_000L  // estadísticas de uso que se miran (ventanas ocultas)
        const val MAX_PASO_MS = 10_000L          // un rato más largo entre revisiones no cuenta como uso
        const val ESPERA_SITIO_MS = 500L         // al abrir un navegador, cuándo se mira qué sitio muestra
    }
}
