package com.bloqueador.android.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.SelectableChipColors
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.bloqueador.android.bloqueo.AdminDispositivo
import com.bloqueador.android.bloqueo.Servicios
import com.bloqueador.android.datos.Almacen
import com.bloqueador.android.dominio.Config

/** Margen lateral de todas las pantallas. */
internal val MARGEN = 16.dp

/** Aviso cuando el teléfono no deja abrir una pantalla de Ajustes. */
internal const val SIN_AJUSTES = "No se pudo abrir Ajustes en este teléfono."

/** Reemplaza a los botones que llevan a Ajustes mientras el modo estricto los bloquea. */
internal const val DISPONIBLE_AL_TERMINAR = "Disponible cuando termine el bloqueo (modo estricto)."

// ---------------------------------------------------------------------------------- colores

/** Fondo y texto (o ícono) de un indicador de estado. */
@Immutable
data class ParDeColores(val fondo: Color, val texto: Color)

/** Rojo (bloqueo activo, algo por activar) o verde (sin bloqueo, todo listo), con variante oscura. */
@Composable
fun coloresEstado(rojo: Boolean): ParDeColores {
    val oscuro = isSystemInDarkTheme()
    return when {
        rojo && oscuro -> ParDeColores(ColoresEstado.RojoFondoOscuro, ColoresEstado.RojoTextoOscuro)
        rojo -> ParDeColores(ColoresEstado.RojoFondo, ColoresEstado.RojoTexto)
        oscuro -> ParDeColores(ColoresEstado.VerdeFondoOscuro, ColoresEstado.VerdeTextoOscuro)
        else -> ParDeColores(ColoresEstado.VerdeFondo, ColoresEstado.VerdeTexto)
    }
}

/** Chips seleccionados con el color principal del tema (rojo suave). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun coloresChip(): SelectableChipColors = FilterChipDefaults.filterChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
    selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
)

/** Botón tonal con el color principal del tema. */
@Composable
fun coloresBotonTonal(): ButtonColors = ButtonDefaults.filledTonalButtonColors(
    containerColor = MaterialTheme.colorScheme.primaryContainer,
    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
)

// ------------------------------------------------------------------------------ componentes

/** Tarjeta con borde, título opcional y contenido en columna. */
@Composable
fun Tarjeta(
    modifier: Modifier = Modifier,
    titulo: String? = null,
    contenido: @Composable ColumnScope.() -> Unit,
) {
    OutlinedCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(MARGEN),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (titulo != null) {
                Text(
                    text = titulo,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() },
                )
            }
            contenido()
        }
    }
}

/** Texto secundario pequeño (ayudas y aclaraciones). */
@Composable
fun TextoAyuda(texto: String, modifier: Modifier = Modifier) {
    Text(
        text = texto,
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Texto de una lista vacía. */
@Composable
fun TextoVacio(texto: String) {
    Text(
        text = texto,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MARGEN, vertical = 8.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Título de una sección dentro de una lista. */
@Composable
fun Encabezado(texto: String) {
    Text(
        text = texto,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = MARGEN, end = MARGEN, top = 16.dp, bottom = 4.dp)
            .semantics { heading() },
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Línea con viñeta. */
@Composable
fun Punto(texto: String) {
    Row {
        Text("•", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(16.dp))
        Text(texto, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Franja roja que explica por qué algo está deshabilitado durante un bloqueo. */
@Composable
fun AvisoBloqueo(texto: String, modifier: Modifier = Modifier) {
    val colores = coloresEstado(rojo = true)
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = colores.fondo,
        contentColor = colores.texto,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(texto, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Diálogo de confirmación con "Cancelar". */
@Composable
fun DialogoConfirmar(
    titulo: String,
    texto: String,
    textoConfirmar: String,
    onConfirmar: () -> Unit,
    onCancelar: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancelar,
        icon = { Icon(Icons.Filled.Warning, contentDescription = null) },
        title = { Text(titulo) },
        text = { Text(texto) },
        confirmButton = { TextButton(onClick = onConfirmar) { Text(textoConfirmar) } },
        dismissButton = { TextButton(onClick = onCancelar) { Text("Cancelar") } },
    )
}

/** Diálogo informativo con un solo botón. */
@Composable
fun DialogoAviso(titulo: String, texto: String, onCerrar: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCerrar,
        icon = { Icon(Icons.Filled.Info, contentDescription = null) },
        title = { Text(titulo) },
        text = { Text(texto) },
        confirmButton = { TextButton(onClick = onCerrar) { Text("Entendido") } },
    )
}

// ------------------------------------------------------------------------- ajustes del sistema

/**
 * Abre una pantalla de Ajustes del sistema. Si el teléfono no la tiene, abre Ajustes generales.
 * Devuelve false si no pudo abrir ninguna.
 */
fun abrirAjustes(context: Context, accion: String): Boolean =
    // Si esa pantalla no existe en este teléfono (o no se puede abrir), se prueba la siguiente.
    listOf(accion, Settings.ACTION_SETTINGS).any { iniciar(context, Intent(it)) }

/** Abre la ficha de Bloqueador en Ajustes > Apps (permisos, batería, datos). Si no, Ajustes generales. */
fun abrirAjustesDeApp(context: Context): Boolean {
    val ficha = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    )
    return iniciar(context, ficha) || iniciar(context, Intent(Settings.ACTION_SETTINGS))
}

/**
 * Abre «Acceso a datos de uso». En Android 10+ va directo al interruptor de Bloqueador; si no,
 * a la lista de apps, y si tampoco, a Ajustes generales.
 */
fun abrirAccesoUso(context: Context): Boolean {
    val propio = Intent(
        Settings.ACTION_USAGE_ACCESS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    )
    return iniciar(context, propio) || abrirAjustes(context, Settings.ACTION_USAGE_ACCESS_SETTINGS)
}

/**
 * Xiaomi (MIUI / HyperOS): abre su editor de permisos, donde está «Mostrar ventanas emergentes
 * en segundo plano». Si este teléfono no lo tiene (o no deja abrirlo), abre la ficha de la app.
 */
fun abrirPermisosXiaomi(context: Context): Boolean {
    val editor = Intent("miui.intent.action.APP_PERM_EDITOR")
        .setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
        .putExtra("extra_pkgname", context.packageName)
    return iniciar(context, editor) || abrirAjustesDeApp(context)
}

/** Abre [intent] desde la actividad (o en una tarea nueva si no hay). Devuelve false si falló. */
private fun iniciar(context: Context, intent: Intent): Boolean {
    val actividad = context.buscarActividad()
    if (actividad == null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        (actividad ?: context).startActivity(intent)
        true
    } catch (e: RuntimeException) {
        false
    }
}

private fun Context.buscarActividad(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

// ------------------------------------------------------------------------ servicios de bloqueo

/** Lo que la interfaz muestra sobre los servicios de bloqueo. */
@Immutable
data class EstadoServicios(
    /** El bloqueo de apps funciona: está activado en Ajustes y Android lo tiene conectado. */
    val accesibilidad: Boolean = false,
    val vpnActiva: Boolean = false,
    val permisoVpn: Boolean = false,
    val dnsPrivadoEstricto: Boolean = false,
    /** Activado en Ajustes > Accesibilidad (aunque Android no lo esté ejecutando). */
    val accesibilidadHabilitada: Boolean = false,
    /** Activado, pero sin conectar desde hace más de unos segundos: Android no lo ejecuta. */
    val accesibilidadNoResponde: Boolean = false,
    /** «Bloquear conexiones sin VPN» activado (Android 10+): el resto de apps se queda sin internet. */
    val vpnLockdown: Boolean = false,
    /** El Bloqueador es la «VPN siempre activada» (Android 10+). */
    val vpnSiempreActiva: Boolean = false,
    /** El filtro no consigue salir a internet para resolver los nombres de los sitios. */
    val vpnSinRed: Boolean = false,
    /** La pantalla de bloqueo no llegó a verse a tiempo (en Xiaomi suele faltar un permiso). */
    val pantallaNoAparece: Boolean = false,
    /** La protección contra desinstalación (administrador de dispositivo) está activa. */
    val proteccionDesinstalacion: Boolean = false,
    /** «Acceso a datos de uso» concedido (lo necesita el modo estricto en Android 14+). */
    val accesoUso: Boolean = false,
)

private inline fun seguro(lectura: () -> Boolean): Boolean = try {
    lectura()
} catch (e: RuntimeException) {
    false
}

/** Margen para que Android conecte el servicio de accesibilidad recién activado o al arrancar la app. */
private const val MARGEN_CONEXION_MS = 3_000L

/**
 * Desde cuándo (SystemClock.elapsedRealtime) el servicio de accesibilidad está activado pero
 * sin conectar. 0 = no lo está. Solo se usa en el hilo principal.
 */
private var sinConectarDesde = 0L

/** Vuelve a dar el margen de conexión (al volver a la app, quizá tras activar el servicio). */
fun reiniciarEsperaAccesibilidad() {
    sinConectarDesde = 0L
}

/** Lee el estado de los servicios. Nunca lanza: ante un error, lo da por inactivo. */
fun leerServicios(context: Context): EstadoServicios {
    val habilitada = seguro { Servicios.accesibilidadHabilitada(context) }
    val conectada = Servicios.accesibilidadConectada
    val ahora = SystemClock.elapsedRealtime()
    val noResponde = if (habilitada && !conectada) {
        if (sinConectarDesde == 0L) sinConectarDesde = ahora
        ahora - sinConectarDesde >= MARGEN_CONEXION_MS
    } else {
        sinConectarDesde = 0L
        false
    }
    return EstadoServicios(
        accesibilidad = habilitada && conectada,
        vpnActiva = Servicios.vpnActiva,
        permisoVpn = seguro { Servicios.permisoVpnConcedido(context) },
        dnsPrivadoEstricto = seguro { Servicios.dnsPrivadoEstricto(context) },
        accesibilidadHabilitada = habilitada,
        accesibilidadNoResponde = noResponde,
        vpnLockdown = Servicios.vpnLockdown,
        vpnSiempreActiva = Servicios.vpnSiempreActiva,
        vpnSinRed = Servicios.vpnSinRed,
        pantallaNoAparece = seguro { Servicios.pantallaNoAparece(context) },
        proteccionDesinstalacion = seguro { AdminDispositivo.activo(context) },
        accesoUso = seguro { Servicios.accesoUso(context) },
    )
}

/** Arranca la VPN sin dejar escapar excepciones. Devuelve false si no arrancó. */
fun iniciarVpnSeguro(context: Context): Boolean = seguro { Servicios.iniciarVpn(context) }

/** Si el permiso de VPN ya está concedido pero el filtro de sitios no corre, lo vuelve a arrancar. */
fun reafirmarVpn(context: Context) {
    if (Servicios.vpnActiva) return
    if (seguro { Servicios.permisoVpnConcedido(context) }) iniciarVpnSeguro(context)
}

// ------------------------------------------------------------------------------ configuración

/** Une [nuevos] a [lista] sin duplicados y en orden alfabético. */
fun unirOrdenado(lista: List<String>, nuevos: Collection<String>): List<String> =
    (lista + nuevos).toSortedSet().toList()

/**
 * Aplica un cambio que QUITA algo de la configuración solo si no hay un bloqueo activo.
 * Vuelve a comprobar el estado con la configuración guardada (no con la que muestra la
 * pantalla), también dentro de la actualización. Devuelve false si había un bloqueo.
 *
 * Única excepción: si los horarios cubren toda la semana (el bloqueo no terminaría nunca),
 * deja quitar horarios, y solo horarios. La interfaz ya no deja crear algo así, pero puede
 * venir de una configuración guardada por una versión anterior.
 */
fun quitarSiLibre(cambio: (Config) -> Config): Boolean {
    val guardada = Almacen.config
    if (!sePuedeQuitar(guardada, cambio(guardada))) return false
    var hecho = false
    Almacen.actualizar { c ->
        val nueva = cambio(c)
        if (sePuedeQuitar(c, nueva)) {
            hecho = true
            nueva
        } else {
            c
        }
    }
    return hecho
}

private fun sePuedeQuitar(antes: Config, despues: Config): Boolean {
    val estado = antes.estado()
    if (!estado.activo) return true
    return estado.permanente && soloQuitaHorarios(antes, despues)
}

/** True si [despues] es [antes] con algunos horarios menos y nada más cambiado. */
private fun soloQuitaHorarios(antes: Config, despues: Config): Boolean =
    despues == antes.copy(horarios = despues.horarios) && antes.horarios.containsAll(despues.horarios)
