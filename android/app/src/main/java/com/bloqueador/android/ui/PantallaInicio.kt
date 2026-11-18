package com.bloqueador.android.ui

import android.app.Activity
import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bloqueador.android.R
import com.bloqueador.android.bloqueo.Servicios
import com.bloqueador.android.datos.Almacen
import com.bloqueador.android.dominio.Config
import com.bloqueador.android.dominio.Estado
import com.bloqueador.android.dominio.aInstante
import com.bloqueador.android.dominio.aLocal
import com.bloqueador.android.dominio.cuando
import com.bloqueador.android.dominio.duracion
import com.bloqueador.android.dominio.plural
import java.time.Instant
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/** Duraciones rápidas de la sesión de concentración, en minutos. */
private val MINUTOS_RAPIDOS = listOf(15, 30, 60, 90, 120, 240)

/** Marcas cuyas capas cierran apps en segundo plano (y, con ellas, el bloqueo de apps). */
private val MARCAS_QUE_CIERRAN_APPS =
    setOf("xiaomi", "redmi", "poco", "huawei", "honor", "oppo", "realme", "oneplus", "vivo")

/** Marcas con MIUI o HyperOS. */
private val MARCAS_XIAOMI = setOf("xiaomi", "redmi", "poco")

/** Aviso neutro cuando la pantalla de bloqueo tardó en verse en un teléfono que no es Xiaomi. */
private const val PANTALLA_TARDO = "A veces la pantalla de bloqueo tardó en aparecer, y el Bloqueador " +
    "te llevó a la pantalla de inicio. Puede pasar si el teléfono va lento."

/** Fabricante del teléfono en minúsculas (ej. "xiaomi"). */
private fun marcaTelefono(): String = Build.MANUFACTURER.orEmpty().lowercase()

/** Diálogos de la tarjeta de sesión. */
private sealed interface DialogoSesion {
    data class Aviso(val titulo: String, val texto: String) : DialogoSesion

    /** Guarda los minutos, no el fin: el fin se calcula al confirmar, desde ese momento. */
    data class Confirmar(val minutos: Int) : DialogoSesion
}

@Composable
fun PantallaInicio(
    config: Config,
    estado: Estado,
    ahora: LocalDateTime,
    servicios: EstadoServicios,
    avisar: (String) -> Unit,
    refrescarServicios: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Con modo estricto y el bloqueo de apps funcionando, las pantallas de Ajustes no se abren.
    val bloqueoEstricto = estado.activo && config.modoEstricto && servicios.accesibilidad
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(MARGEN),
        verticalArrangement = Arrangement.spacedBy(MARGEN),
    ) {
        TarjetaEstado(config, estado, ahora)
        TarjetaSesion(config, estado, ahora, servicios, avisar)
        TarjetaRequisitos(servicios, bloqueoEstricto, config.modoEstricto, avisar, refrescarServicios)
    }
}

// --------------------------------------------------------------------------------- estado

@Composable
private fun TarjetaEstado(config: Config, estado: Estado, ahora: LocalDateTime) {
    val activo = estado.activo
    val colores = coloresEstado(rojo = activo)
    val titulo: String
    val detalle: String
    if (activo) {
        val origen = when (estado.motivo) {
            "sesión" -> "Sesión de concentración"
            "horario" -> "Horario programado"
            else -> "Sesión y horario"
        }
        val hasta = estado.hasta
        titulo = "Bloqueo activo"
        detalle = when {
            estado.permanente -> "$origen · sin fin: tus horarios cubren toda la semana. Quita alguno en Horarios."
            hasta != null -> "$origen · termina ${cuando(hasta, ahora)} · faltan ${duracion(ahora, hasta)}"
            else -> origen
        }
    } else {
        val proximo = config.proximoInicio(ahora)
        titulo = "Sin bloqueo activo"
        detalle = if (proximo != null) {
            "Próximo horario: ${cuando(proximo, ahora)}."
        } else {
            "Inicia una sesión de concentración o programa un horario."
        }
    }
    val resumen = listOf(
        plural(config.sitios.size, "sitio", "sitios"),
        plural(config.apps.size, "aplicación", "aplicaciones"),
        plural(config.horarios.size, "horario", "horarios"),
    ).joinToString(" · ")

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = colores.fondo,
    ) {
        Row(Modifier.padding(MARGEN)) {
            Icon(
                imageVector = if (activo) Icons.Filled.Lock else Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = colores.texto,
                modifier = Modifier.size(32.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(titulo, style = MaterialTheme.typography.titleLarge, color = colores.texto)
                Text(detalle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(resumen, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// --------------------------------------------------------------------- sesión de concentración

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun TarjetaSesion(
    config: Config,
    estado: Estado,
    ahora: LocalDateTime,
    servicios: EstadoServicios,
    avisar: (String) -> Unit,
) {
    val context = LocalContext.current
    val foco = LocalFocusManager.current
    var elegido by rememberSaveable { mutableStateOf(60) }
    var texto by rememberSaveable { mutableStateOf("") }
    var dialogo by remember { mutableStateOf<DialogoSesion?>(null) }

    // El campo, si tiene algo escrito, manda sobre los chips.
    val minutos: Int? = if (texto.isBlank()) elegido else texto.toIntOrNull()?.takeIf { it in 1..1440 }
    val invalido = texto.isNotBlank() && minutos == null

    Tarjeta(titulo = "Sesión de concentración") {
        Text("Bloquear durante", style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MINUTOS_RAPIDOS.forEach { m ->
                FilterChip(
                    selected = texto.isBlank() && elegido == m,
                    onClick = {
                        elegido = m
                        texto = ""
                    },
                    label = { Text(etiquetaMinutos(m)) },
                    colors = coloresChip(),
                )
            }
        }
        OutlinedTextField(
            value = texto,
            onValueChange = { nuevo -> texto = nuevo.filter { it.isDigit() }.take(4) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Otra duración (minutos)") },
            singleLine = true,
            isError = invalido,
            supportingText = {
                Text(if (invalido) "Escribe un número de minutos entre 1 y 1440." else "Opcional: de 1 a 1440 minutos.")
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { foco.clearFocus() }),
        )
        Button(
            onClick = {
                val m = minutos
                if (m != null) {
                    foco.clearFocus()
                    dialogo = prepararSesion(m)
                }
            },
            enabled = minutos != null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (estado.activo) "Extender bloqueo" else "Iniciar bloqueo")
        }
        if (minutos != null) {
            TextoAyuda("Terminaría ${cuando(finSesion(ahora, minutos), ahora)}.")
        }
    }

    when (val d = dialogo) {
        null -> Unit
        is DialogoSesion.Aviso -> DialogoAviso(
            titulo = d.titulo,
            texto = d.texto,
            onCerrar = { dialogo = null },
        )
        is DialogoSesion.Confirmar -> DialogoConfirmar(
            titulo = if (estado.activo) "Extender bloqueo" else "Confirmar bloqueo",
            // Se arma con la hora actual: si el diálogo sigue abierto, el fin que muestra avanza.
            texto = textoConfirmacion(d.minutos, config, servicios, ahora),
            textoConfirmar = if (estado.activo) "Extender" else "Empezar",
            onConfirmar = { dialogo = empezarSesion(d.minutos, context, avisar) },
            onCancelar = { dialogo = null },
        )
    }
}

private fun etiquetaMinutos(m: Int): String = if (m >= 60 && m % 60 == 0) "${m / 60} h" else "$m min"

/** Cuándo terminaría una sesión de [minutos] que empieza en [ahora] (en tiempo real, no de reloj). */
private fun finSesion(ahora: LocalDateTime, minutos: Int): LocalDateTime =
    aLocal(aInstante(ahora).plus(minutos.toLong(), ChronoUnit.MINUTES))

/** Decide qué diálogo mostrar al tocar "Iniciar bloqueo" (con la configuración guardada). */
private fun prepararSesion(minutos: Int): DialogoSesion {
    val cfg = Almacen.config
    if (cfg.sitios.isEmpty() && cfg.apps.isEmpty()) {
        return DialogoSesion.Aviso("Nada que bloquear", "Primero agrega sitios web o aplicaciones a tus listas.")
    }
    val inicio = Instant.now()
    val ahora = aLocal(inicio)
    val fin = inicio.plus(minutos.toLong(), ChronoUnit.MINUTES)
    return avisoBloqueoMasLargo(cfg.estado(ahora), fin, ahora) ?: DialogoSesion.Confirmar(minutos)
}

/**
 * Al confirmar: el fin se cuenta desde este momento, no desde que se abrió el diálogo.
 * Devuelve el aviso que hay que mostrar si ya hay un bloqueo más largo, o null.
 */
private fun empezarSesion(minutos: Int, context: Context, avisar: (String) -> Unit): DialogoSesion? {
    val inicio = Instant.now().truncatedTo(ChronoUnit.SECONDS)
    val ahora = aLocal(inicio)
    val fin = inicio.plus(minutos.toLong(), ChronoUnit.MINUTES)
    val actual = Almacen.config.estado(ahora)
    avisoBloqueoMasLargo(actual, fin, ahora)?.let { return it }
    guardarSesion(fin)
    reafirmarVpn(context)
    val que = if (actual.activo) "Bloqueo extendido" else "Bloqueo iniciado"
    val finLocal = aLocal(fin)
    val mensaje = "$que hasta ${cuando(finLocal, ahora)}."
    // Con la sesión ya guardada: si justo al terminar sigue un horario, se dice hasta cuándo dura.
    val encadenado = textoEncadenado(Almacen.config.estado(ahora), finLocal, ahora)
    avisar(if (encadenado != null) "$mensaje $encadenado" else mensaje)
    return null
}

/**
 * Si al terminar la sesión (en [fin]) sigue un horario sin pausa, el bloqueo [previsto] dura
 * más: devuelve la frase que lo explica. Si no, null.
 */
private fun textoEncadenado(previsto: Estado, fin: LocalDateTime, ahora: LocalDateTime): String? {
    val hasta = previsto.hasta
    return when {
        previsto.permanente -> "Justo después sigue un horario, y tus horarios cubren toda la semana: " +
            "el bloqueo no terminará hasta que quites alguno en Horarios."
        hasta != null && hasta.isAfter(fin) ->
            "Justo después sigue un horario: el bloqueo durará hasta ${cuando(hasta, ahora)}."
        else -> null
    }
}

/** Aviso si el bloqueo en curso ya dura al menos hasta [fin] (o no termina nunca); si no, null. */
private fun avisoBloqueoMasLargo(estado: Estado, fin: Instant, ahora: LocalDateTime): DialogoSesion.Aviso? {
    if (!estado.activo) return null
    if (estado.permanente) {
        return DialogoSesion.Aviso(
            "El bloqueo no tiene fin",
            "Tus horarios cubren todas las horas de la semana, así que el bloqueo no termina. " +
                "Quita alguno en la pestaña Horarios.",
        )
    }
    // estado.hasta ya es el fin real: incluye los horarios que empiezan justo al terminar otro.
    val hasta = estado.hasta ?: return null
    if (aInstante(hasta).isBefore(fin)) return null
    return DialogoSesion.Aviso("Ya hay un bloqueo más largo", "El bloqueo actual ya termina ${cuando(hasta, ahora)}.")
}

/** Texto del diálogo de confirmación, armado con la hora actual. */
private fun textoConfirmacion(
    minutos: Int,
    cfg: Config,
    servicios: EstadoServicios,
    ahora: LocalDateTime,
): String {
    val que = plural(cfg.sitios.size, "sitio", "sitios") + " y " +
        plural(cfg.apps.size, "aplicación", "aplicaciones")
    val fin = finSesion(ahora, minutos)
    val texto = StringBuilder("Se bloquearán $que hasta ${cuando(fin, ahora)}.")
    // Va justo después, para que el "hasta entonces" de abajo se lea como el fin real.
    val previsto = cfg.copy(sesionFin = aInstante(fin)).estado(ahora)
    textoEncadenado(previsto, fin, ahora)?.let { texto.append(' ').append(it) }
    if (cfg.modoEstricto) {
        texto.append("\n\nNo podrás detenerlo ni quitar elementos de las listas hasta entonces.")
    } else {
        texto.append(
            "\n\nDesde la app no podrás detenerlo ni quitar elementos de las listas hasta entonces. " +
                "Pero sin modo estricto se puede desactivar desde Ajustes del teléfono; para evitarlo, " +
                "activa el modo estricto en la pestaña Ajustes."
        )
    }
    if (cfg.sitios.isNotEmpty()) {
        texto.append(
            "\n\nSi ya tienes abierto algún sitio de tu lista, cierra esa pestaña: una página " +
                "abierta puede seguir funcionando un rato."
        )
    }
    val faltan = buildList {
        if (cfg.apps.isNotEmpty() && !servicios.accesibilidad) add("activar el bloqueo de apps")
        // Con el filtro en el túnel de paso, al empezar el bloqueo vuelve solo al túnel normal:
        // no falta activarlo, sino que salga a internet.
        if (cfg.sitios.isNotEmpty() && servicios.vpnSinRed) {
            add("revisar el bloqueo de sitios")
        } else if (cfg.sitios.isNotEmpty() && !servicios.vpnActiva) {
            add("activar el bloqueo de sitios")
        }
        if (cfg.sitios.isNotEmpty() && servicios.dnsPrivadoEstricto) add("cambiar el DNS privado")
    }
    if (faltan.isNotEmpty()) {
        texto.append("\n\nAtención: falta ${enumerar(faltan)} (más abajo en esta pantalla).")
    }
    return texto.toString()
}

/** "a", "a y b", "a, b y c". */
private fun enumerar(partes: List<String>): String =
    if (partes.size < 2) partes.joinToString() else partes.dropLast(1).joinToString(", ") + " y " + partes.last()

/** Guarda el fin de la sesión. Nunca acorta una sesión que ya termina más tarde. */
private fun guardarSesion(fin: Instant) {
    Almacen.actualizar { c ->
        val actual = c.sesionFin
        if (actual != null && actual.isAfter(fin)) c else c.copy(sesionFin = fin)
    }
}

// ------------------------------------------------------------------- lista de comprobación

@Composable
private fun TarjetaRequisitos(
    servicios: EstadoServicios,
    bloqueoEstricto: Boolean,
    modoEstricto: Boolean,
    avisar: (String) -> Unit,
    refrescar: () -> Unit,
) {
    val context = LocalContext.current
    val nombreServicio = stringResource(R.string.servicio_accesibilidad)
    val marca = remember { marcaTelefono() }
    val xiaomi = marca in MARCAS_XIAOMI

    val pedirPermisoVpn = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { resultado ->
        Servicios.permitirDialogoVpnHasta = 0L
        if (resultado.resultCode == Activity.RESULT_OK) {
            Servicios.marcarPermisoVpn(context, true)
            if (!iniciarVpnSeguro(context)) avisar("No se pudo iniciar el bloqueo de sitios. Inténtalo de nuevo.")
        } else {
            avisar("Sin el permiso de VPN no se pueden bloquear sitios.")
        }
        refrescar()
    }

    // No depende de la bandera guardada: pregunta a Android si falta el permiso.
    fun activarSitios() {
        val pedir = try {
            Servicios.prepararVpn(context)
        } catch (e: RuntimeException) {
            avisar("No se pudo pedir el permiso de VPN.")
            return
        }
        if (pedir == null) {
            // El permiso ya estaba concedido: basta con arrancar el filtro.
            if (!iniciarVpnSeguro(context)) avisar("No se pudo iniciar el bloqueo de sitios. Inténtalo de nuevo.")
            refrescar()
            return
        }
        // El modo estricto deja abrir el diálogo del sistema durante un minuto.
        Servicios.permitirDialogoVpnHasta = System.currentTimeMillis() + 60_000L
        try {
            pedirPermisoVpn.launch(pedir)
        } catch (e: RuntimeException) {
            Servicios.permitirDialogoVpnHasta = 0L
            avisar("Este teléfono no dejó abrir la solicitud de VPN.")
        }
    }

    fun abrir(accion: String) {
        if (!abrirAjustes(context, accion)) avisar(SIN_AJUSTES)
    }

    fun abrirFichaApp() {
        if (!abrirAjustesDeApp(context)) avisar(SIN_AJUSTES)
    }

    fun abrirPermisos() {
        val abierto = if (xiaomi) abrirPermisosXiaomi(context) else abrirAjustesDeApp(context)
        if (!abierto) avisar(SIN_AJUSTES)
    }

    Tarjeta(titulo = "Para que el bloqueo funcione") {
        FilaBloqueoApps(
            servicios = servicios,
            nombreServicio = nombreServicio,
            onAbrirAccesibilidad = { abrir(Settings.ACTION_ACCESSIBILITY_SETTINGS) },
            // Fuera de Xiaomi no se conoce un permiso que lo impida: lo normal es que solo haya tardado.
            avisoPantalla = if (servicios.pantallaNoAparece && !xiaomi) PANTALLA_TARDO else null,
        )
        // En Xiaomi suele faltar el permiso de ventanas emergentes en segundo plano.
        if (servicios.pantallaNoAparece && xiaomi) {
            HorizontalDivider()
            Requisito(
                titulo = "Pantalla de bloqueo",
                activo = false,
                estadoTexto = "No aparece",
                descripcion = "No apareció a tiempo, así que las apps de tu lista te devuelven a la " +
                    "pantalla de inicio sin aviso. Suele faltar un permiso: en «Otros permisos» de " +
                    "Bloqueador, activa «Mostrar ventanas emergentes en segundo plano».",
                boton = if (bloqueoEstricto) null else "Abrir permisos",
                onBoton = { abrirPermisos() },
            ) {
                if (bloqueoEstricto) TextoAyuda(DISPONIBLE_AL_TERMINAR)
            }
        }
        // Android 14+: sin este acceso, el modo estricto no ve algunas pantallas de Ajustes.
        if (modoEstricto && Servicios.necesitaAccesoUso && !servicios.accesoUso) {
            HorizontalDivider()
            Requisito(
                titulo = "Modo estricto",
                activo = false,
                estadoTexto = "Falta un permiso",
                descripcion = "Android oculta algunas pantallas de Ajustes al bloqueo de apps. Para " +
                    "que el modo estricto las tape también, activa «Acceso a datos de uso» para " +
                    "Bloqueador.",
                boton = if (bloqueoEstricto) null else "Dar acceso",
                onBoton = { if (!abrirAccesoUso(context)) avisar(SIN_AJUSTES) },
            ) {
                if (bloqueoEstricto) TextoAyuda(DISPONIBLE_AL_TERMINAR)
            }
        }
        HorizontalDivider()
        FilaBloqueoSitios(
            servicios = servicios,
            bloqueoEstricto = bloqueoEstricto,
            onActivar = { activarSitios() },
            onAjustesVpn = { abrir(Settings.ACTION_VPN_SETTINGS) },
            onFichaApp = { abrirFichaApp() },
        )
        if (servicios.dnsPrivadoEstricto) {
            HorizontalDivider()
            Requisito(
                titulo = "DNS privado",
                activo = false,
                estadoTexto = "Revisar",
                descripcion = "Tienes «DNS privado» fijado a un proveedor: el bloqueo de sitios puede " +
                    "no funcionar. Cámbialo a Automático o Desactivado.",
                boton = if (bloqueoEstricto) null else "Abrir ajustes de red",
                onBoton = { abrir(Settings.ACTION_WIRELESS_SETTINGS) },
            ) {
                if (bloqueoEstricto) TextoAyuda(DISPONIBLE_AL_TERMINAR)
            }
        }
        if (marca in MARCAS_QUE_CIERRAN_APPS) {
            HorizontalDivider()
            PasosFabricante(
                xiaomi = xiaomi,
                bloqueoEstricto = bloqueoEstricto,
                onFichaApp = { abrirFichaApp() },
                onBateria = { abrir(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) },
                onPermisosXiaomi = { abrirPermisos() },
            )
        }
    }
}

/** Fila del bloqueo de apps (servicio de accesibilidad). Si funciona, muestra [avisoPantalla] debajo. */
@Composable
private fun FilaBloqueoApps(
    servicios: EstadoServicios,
    nombreServicio: String,
    onAbrirAccesibilidad: () -> Unit,
    avisoPantalla: String? = null,
) {
    val titulo = "Bloqueo de apps"
    when {
        servicios.accesibilidad -> Requisito(
            titulo = titulo,
            activo = true,
            descripcion = "Durante un bloqueo, la pantalla de bloqueo tapa las apps de tu lista.",
            boton = null,
            onBoton = {},
        ) {
            avisoPantalla?.let { TextoAyuda(it) }
        }
        // Activado en Ajustes, pero Android no lo ejecuta (se cerró por un error o lo cerró el teléfono).
        servicios.accesibilidadNoResponde -> Requisito(
            titulo = titulo,
            activo = false,
            estadoTexto = "No responde",
            descripcion = "Está activado, pero Android no lo está ejecutando. En Ajustes > " +
                "Accesibilidad, desactiva «$nombreServicio» y vuelve a activarlo.",
            boton = "Abrir Accesibilidad",
            onBoton = onAbrirAccesibilidad,
        )
        // Recién activado: Android tarda un momento en conectarlo.
        servicios.accesibilidadHabilitada -> Requisito(
            titulo = titulo,
            activo = true,
            estadoTexto = "Iniciando…",
            descripcion = "Android está poniendo en marcha el servicio.",
            boton = null,
            onBoton = {},
        )
        else -> Requisito(
            titulo = titulo,
            activo = false,
            descripcion = "En Ajustes > Accesibilidad, abre «$nombreServicio» y actívalo. Si ya " +
                "lo habías activado y se apagó solo, es porque se forzó la detención del Bloqueador " +
                "o lo cerró un limpiador del teléfono: vuelve a activarlo.",
            boton = "Activar",
            onBoton = onAbrirAccesibilidad,
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                TextoAyuda(
                    "Si el interruptor aparece gris: Ajustes > Apps > Bloqueador > menú ⋮ > " +
                        "«Permitir ajustes restringidos». Luego vuelve a intentarlo."
                )
            }
        }
    }
}

/** Fila del bloqueo de sitios (VPN local). El estado sale de si el filtro corre de verdad. */
@Composable
private fun FilaBloqueoSitios(
    servicios: EstadoServicios,
    bloqueoEstricto: Boolean,
    onActivar: () -> Unit,
    onAjustesVpn: () -> Unit,
    onFichaApp: () -> Unit,
) {
    val titulo = "Bloqueo de sitios"
    when {
        // Lo más grave primero: con esto el resto del teléfono se queda sin internet.
        servicios.vpnLockdown -> Requisito(
            titulo = titulo,
            activo = false,
            estadoTexto = "Revisar",
            descripcion = "«Bloquear conexiones sin VPN» está activado: el resto de apps se queda sin " +
                "internet. Desactívalo en Ajustes de VPN, en ⚙ junto a Bloqueador.",
            boton = if (bloqueoEstricto) null else "Abrir ajustes de VPN",
            onBoton = onAjustesVpn,
        ) {
            if (bloqueoEstricto) TextoAyuda(DISPONIBLE_AL_TERMINAR)
        }
        // Antes que !vpnActiva: fuera de un bloqueo, el filtro pasa al túnel de paso (y vpnActiva
        // pasa a false) cuando no consigue salir a internet, para no dejar el teléfono sin DNS.
        servicios.vpnSinRed -> Requisito(
            titulo = titulo,
            activo = false,
            estadoTexto = "Revisar",
            descripcion = "El filtro de sitios no consigue salir a internet. Si el resto del teléfono " +
                "tiene conexión, revisa que Bloqueador tenga permiso de red y de datos (Wi-Fi y datos " +
                "móviles). Fuera de un bloqueo, el filtro se pausa solo para no dejarte sin internet.",
            boton = if (bloqueoEstricto) null else "Abrir ajustes de Bloqueador",
            onBoton = onFichaApp,
        ) {
            if (bloqueoEstricto) TextoAyuda(DISPONIBLE_AL_TERMINAR)
            // Activar prueba la red al instante y no deja el teléfono sin DNS.
            if (!servicios.vpnActiva) TextButton(onClick = onActivar) { Text("Activar") }
        }
        !servicios.vpnActiva -> Requisito(
            titulo = titulo,
            activo = false,
            descripcion = "Toca Activar y, si Android lo pide, acepta la conexión VPN. " +
                "Si usas otra VPN, se desconectará.",
            boton = "Activar",
            onBoton = onActivar,
        ) {
            ConsejoVpn(servicios.vpnSiempreActiva, bloqueoEstricto, onAjustesVpn)
        }
        else -> Requisito(
            titulo = titulo,
            activo = true,
            descripcion = "Durante un bloqueo, los sitios de tu lista no cargan en los navegadores que " +
                "usan el DNS del teléfono.",
            boton = null,
            onBoton = {},
        ) {
            ConsejoVpn(servicios.vpnSiempreActiva, bloqueoEstricto, onAjustesVpn)
        }
    }
}

/** Recomienda «VPN siempre activada», o confirma que ya lo está. */
@Composable
private fun ConsejoVpn(siempreActiva: Boolean, bloqueoEstricto: Boolean, onAjustesVpn: () -> Unit) {
    if (siempreActiva) {
        TextoAyuda("«VPN siempre activada» está activada: el filtro vuelve solo después de reiniciar.")
    } else {
        TextoAyuda(
            "Recomendado: en Ajustes de VPN, toca ⚙ junto a Bloqueador y activa " +
                "«VPN siempre activada». Así vuelve a funcionar solo después de reiniciar. " +
                "No actives «Bloquear conexiones sin VPN»: te dejaría sin internet."
        )
        if (bloqueoEstricto) {
            TextoAyuda(DISPONIBLE_AL_TERMINAR)
        } else {
            TextButton(onClick = onAjustesVpn) { Text("Abrir ajustes de VPN") }
        }
    }
}

/** Pasos para los teléfonos que cierran apps en segundo plano (Xiaomi, Huawei, Oppo...). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PasosFabricante(
    xiaomi: Boolean,
    bloqueoEstricto: Boolean,
    onFichaApp: () -> Unit,
    onBateria: () -> Unit,
    onPermisosXiaomi: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Evita que el teléfono cierre el Bloqueador", style = MaterialTheme.typography.titleSmall)
        TextoAyuda(
            "Este teléfono suele cerrar las apps en segundo plano. Si cierra el Bloqueador, el " +
                "bloqueo de apps se apaga hasta que lo vuelvas a activar."
        )
        Punto(
            "En Recientes, fija el Bloqueador con el candado (mantén pulsada su tarjeta o abre su " +
                "menú), para que «Limpiar todo» no lo cierre."
        )
        Punto("Permite su «Inicio automático».")
        Punto("En Batería, déjalo «Sin restricciones».")
        if (xiaomi) {
            Punto(
                "En «Otros permisos», activa «Mostrar ventanas emergentes en segundo plano»: sin " +
                    "eso, la pantalla de bloqueo no aparece."
            )
        }
        if (bloqueoEstricto) {
            TextoAyuda(DISPONIBLE_AL_TERMINAR)
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onFichaApp) { Text("Ajustes de la app") }
                TextButton(onClick = onBateria) { Text("Batería") }
                if (xiaomi) {
                    TextButton(onClick = onPermisosXiaomi) { Text("Otros permisos") }
                }
            }
        }
    }
}

/** Una fila de la lista de comprobación: ícono, título, estado, explicación y botón. */
@Composable
private fun Requisito(
    titulo: String,
    activo: Boolean,
    descripcion: String,
    boton: String?,
    onBoton: () -> Unit,
    estadoTexto: String = if (activo) "Activo" else "Inactivo",
    extra: @Composable ColumnScope.() -> Unit = {},
) {
    val colores = coloresEstado(rojo = !activo)
    Row(Modifier.fillMaxWidth()) {
        Icon(
            imageVector = if (activo) Icons.Filled.CheckCircle else Icons.Filled.Warning,
            contentDescription = null,
            tint = colores.texto,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(24.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(titulo, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(estadoTexto, style = MaterialTheme.typography.labelLarge, color = colores.texto)
            }
            Text(
                text = descripcion,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (boton != null) {
                FilledTonalButton(onClick = onBoton, colors = coloresBotonTonal()) { Text(boton) }
            }
            extra()
        }
    }
}
