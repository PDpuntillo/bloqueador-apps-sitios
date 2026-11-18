package com.bloqueador.android.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telecom.TelecomManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.bloqueador.android.datos.Almacen
import com.bloqueador.android.dominio.Config
import com.bloqueador.android.dominio.Estado
import com.bloqueador.android.dominio.Paquetes
import com.bloqueador.android.dominio.cuando
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.Collator
import java.text.Normalizer
import java.time.LocalDateTime

/** Tamaño de los íconos de las apps en pantalla. Se cargan a este tamaño en píxeles reales. */
private val TAM_ICONO = 40.dp

/** Tiempo que se guardan los íconos después de salir de la pestaña Apps. */
private const val GUARDAR_ICONOS_MS = 10 * 60_000L

private val MARCAS_DIACRITICAS = Regex("\\p{Mn}+")

/** "Cámara" -> "camara": minúsculas y sin tildes, para buscar. */
private fun claveBusqueda(texto: String): String =
    MARCAS_DIACRITICAS.replace(Normalizer.normalize(texto, Normalizer.Form.NFD), "").lowercase()

/**
 * "hasta que termine (hoy a las 18:00)". Si los horarios cubren toda la semana, el bloqueo no
 * tiene fin, y el texto lo dice. Se usa al final de una frase (quien llama pone el punto).
 */
internal fun hastaQueTermine(estado: Estado, ahora: LocalDateTime): String {
    val hasta = estado.hasta
    return if (estado.permanente || hasta == null) {
        "mientras dure el bloqueo. Ahora no termina nunca porque tus horarios cubren toda la " +
            "semana: quita alguno en Horarios"
    } else {
        "hasta que termine (${cuando(hasta, ahora)})"
    }
}

/** Agrega paquetes a la lista de apps bloqueadas (ordenada y sin duplicados). */
internal fun bloquearApps(paquetes: Collection<String>) {
    Almacen.actualizar { c ->
        if (c.apps.containsAll(paquetes)) c else c.copy(apps = unirOrdenado(c.apps, paquetes))
    }
}

/** Una app con ícono en el menú (o un paquete bloqueado que ya no está instalado). */
@Immutable
class AppInstalada(
    val paquete: String,
    val nombre: String,
    val icono: ImageBitmap?,
    val instalada: Boolean = true,
) {
    /** Nombre y paquete normalizados, para el buscador. */
    val clave: String = claveBusqueda(nombre) + " " + paquete.lowercase()
}

/** Resultado de leer las apps instaladas. */
@Immutable
class ListaApps(
    /** Todas las apps con ícono en el menú, por paquete (incluye las que no se pueden bloquear). */
    val todas: Map<String, AppInstalada>,
    /** Las que se pueden bloquear, ordenadas por nombre. */
    val elegibles: List<AppInstalada>,
    /** Tamaño en píxeles de los íconos. */
    val tamIcono: Int,
)

/**
 * Apps instaladas, guardadas para todo el proceso: no se vuelven a leer desde cero al cambiar
 * de pestaña ni al girar la pantalla. Los íconos se sueltan un rato después de salir de la
 * pestaña Apps, para no ocupar memoria mientras solo corren los servicios de bloqueo.
 */
private object CacheApps {
    var lista by mutableStateOf<ListaApps?>(null)
    var fallo by mutableStateOf(false)
    var recargando by mutableStateOf(false)

    /** Una sola lectura a la vez: la siguiente espera y aprovecha lo que leyó la anterior. */
    val cerrojo = Mutex()

    private val principal = Handler(Looper.getMainLooper())
    private val liberar = Runnable {
        if (!cerrojo.isLocked) {
            lista = null
            fallo = false
        }
    }

    /** Se llama en el hilo principal al entrar en la pestaña Apps. */
    fun enUso() = principal.removeCallbacks(liberar)

    /** Se llama en el hilo principal al salir de la pestaña Apps. */
    fun sinUso() {
        principal.removeCallbacks(liberar)
        principal.postDelayed(liberar, GUARDAR_ICONOS_MS)
    }
}

/** Lo que la pantalla de apps ve de la caché. Se puede crear varias veces: todas la comparten. */
@Stable
class CatalogoApps {
    val lista: ListaApps? get() = CacheApps.lista
    val fallo: Boolean get() = CacheApps.fallo
    val recargando: Boolean get() = CacheApps.recargando

    /**
     * Vuelve a leer las apps en segundo plano. De las que ya estaban reutiliza el nombre y el
     * ícono (salvo con [forzar]), así que solo carga los de las apps nuevas. Mientras tanto se
     * sigue mostrando la lista anterior.
     */
    suspend fun cargar(context: Context, tamIcono: Int, forzar: Boolean = false) {
        val app = context.applicationContext
        CacheApps.cerrojo.withLock {
            if (forzar) CacheApps.recargando = true
            try {
                val anterior = if (forzar) null else CacheApps.lista
                val nueva = withContext(Dispatchers.IO) {
                    try {
                        leerApps(app, tamIcono, anterior) { ensureActive() }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                }
                if (nueva != null) {
                    CacheApps.lista = nueva
                    CacheApps.fallo = false
                } else if (CacheApps.lista == null) {
                    CacheApps.fallo = true
                }
            } finally {
                if (forzar) CacheApps.recargando = false
            }
        }
    }
}

/**
 * Lee las apps con ícono en el menú. De las que ya estaban en [anterior] (con íconos del mismo
 * tamaño) reutiliza el nombre y el ícono. [seguir] lanza una excepción si la carga se canceló,
 * por ejemplo porque saliste de la pestaña.
 */
private fun leerApps(context: Context, tamIcono: Int, anterior: ListaApps?, seguir: () -> Unit): ListaApps {
    val pm = context.packageManager
    val propio = context.packageName
    val lanzadores = consultar(pm, Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
        .mapNotNull { it.activityInfo?.packageName }
        .toSet()
    val marcadores = leerMarcadores(context)
    val porPaquete = consultar(pm, Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER))
        .filter { it.activityInfo != null }
        .groupBy { it.activityInfo.packageName }
    val conocidas = anterior?.takeIf { it.tamIcono == tamIcono }?.todas.orEmpty()
    val todas = HashMap<String, AppInstalada>()
    for ((paquete, actividades) in porPaquete) {
        seguir()
        todas[paquete] = conocidas[paquete] ?: describirApp(pm, paquete, actividades, tamIcono)
    }
    val orden = Collator.getInstance()
    val elegibles = todas.values
        .filter { !Paquetes.esProtegido(it.paquete, propio, lanzadores) && it.paquete !in marcadores }
        .sortedWith(Comparator { a, b -> orden.compare(a.nombre, b.nombre) })
    return ListaApps(todas, elegibles, tamIcono)
}

/** La app de llamadas predeterminada y la del sistema: nunca se ofrecen para bloquear. */
private fun leerMarcadores(context: Context): Set<String> {
    val marcadores = HashSet<String>()
    try {
        val telecom = context.getSystemService(TelecomManager::class.java) ?: return marcadores
        telecom.defaultDialerPackage?.let { marcadores += it }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            telecom.systemDialerPackage?.let { marcadores += it }
        }
    } catch (e: RuntimeException) {
        // Sin telefonía (por ejemplo, una tablet): no hay marcador que proteger.
    }
    return marcadores
}

/** Nombre e ícono de una app. Si tiene varias entradas en el menú, usa los datos de la app. */
private fun describirApp(
    pm: PackageManager,
    paquete: String,
    actividades: List<ResolveInfo>,
    tamIcono: Int,
): AppInstalada {
    val ri = actividades.first()
    val unica = actividades.size == 1
    val nombre = try {
        val etiqueta = if (unica) ri.loadLabel(pm) else ri.activityInfo.applicationInfo.loadLabel(pm)
        etiqueta.toString().trim()
    } catch (e: RuntimeException) {
        ""
    }
    val icono = try {
        val dibujo = if (unica) ri.loadIcon(pm) else ri.activityInfo.applicationInfo.loadIcon(pm)
        dibujo.toBitmap(tamIcono, tamIcono).asImageBitmap()
    } catch (e: RuntimeException) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }
    return AppInstalada(paquete, nombre.ifEmpty { paquete }, icono)
}

@Suppress("DEPRECATION")
private fun consultar(pm: PackageManager, intent: Intent): List<ResolveInfo> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
    } else {
        pm.queryIntentActivities(intent, 0)
    }

/** App que se quiere bloquear durante un bloqueo activo: se confirma antes. */
private class AppPendiente(val paquete: String, val nombre: String, val texto: String)

/** Así el diálogo sigue abierto al girar la pantalla. */
private val GUARDAR_PENDIENTE = listSaver<AppPendiente?, String>(
    save = { p -> if (p == null) emptyList() else listOf(p.paquete, p.nombre, p.texto) },
    restore = { l -> AppPendiente(l[0], l[1], l[2]) },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PantallaApps(
    config: Config,
    bloqueado: Boolean,
    catalogo: CatalogoApps,
    reanudaciones: Int,
    avisar: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val foco = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val tamIcono = with(LocalDensity.current) { TAM_ICONO.roundToPx() }
    var busqueda by rememberSaveable { mutableStateOf("") }
    var pendiente by rememberSaveable(stateSaver = GUARDAR_PENDIENTE) { mutableStateOf<AppPendiente?>(null) }

    DisposableEffect(Unit) {
        CacheApps.enUso()
        onDispose { CacheApps.sinUso() }
    }

    // Al entrar y al volver a la app (por si instalaste algo): solo se leen las apps nuevas.
    LaunchedEffect(reanudaciones, tamIcono) { catalogo.cargar(context, tamIcono) }

    // "Reintentar" y "Recargar": vuelve a leer todo, también los nombres y los íconos.
    fun recargar() {
        scope.launch { catalogo.cargar(context, tamIcono, forzar = true) }
    }

    val lista = catalogo.lista
    val filtro = remember(busqueda) { claveBusqueda(busqueda.trim()) }
    val bloqueadas = remember(config.apps, lista, filtro) {
        val orden = Collator.getInstance()
        config.apps.distinct()
            .map { p -> lista?.todas?.get(p) ?: AppInstalada(p, p, null, instalada = lista == null) }
            .filter { filtro.isEmpty() || filtro in it.clave }
            .sortedWith(Comparator { a, b -> orden.compare(a.nombre, b.nombre) })
    }
    val otras = remember(config.apps, lista, filtro) {
        val marcadas = config.apps.toHashSet()
        lista?.elegibles.orEmpty().filter { it.paquete !in marcadas && (filtro.isEmpty() || filtro in it.clave) }
    }

    fun agregar(app: AppInstalada) {
        val estado = Almacen.config.estado()
        if (estado.activo) {
            pendiente = AppPendiente(
                app.paquete,
                app.nombre,
                "Hay un bloqueo activo: ${app.nombre} se bloqueará de inmediato y no podrás " +
                    "quitarla ${hastaQueTermine(estado, LocalDateTime.now())}.",
            )
        } else {
            bloquearApps(listOf(app.paquete))
        }
    }

    fun quitar(app: AppInstalada) {
        if (!quitarSiLibre { c -> c.copy(apps = c.apps - app.paquete) }) {
            avisar("No puedes quitar apps durante un bloqueo.")
        }
    }

    Column(modifier) {
        OutlinedTextField(
            value = busqueda,
            onValueChange = { busqueda = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = MARGEN, end = MARGEN, top = 8.dp, bottom = 4.dp),
            placeholder = { Text("Buscar por nombre") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = if (busqueda.isEmpty()) {
                null
            } else {
                {
                    IconButton(onClick = { busqueda = "" }) {
                        Icon(Icons.Filled.Close, contentDescription = "Borrar búsqueda")
                    }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { foco.clearFocus() }),
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(bottom = MARGEN),
        ) {
            item(key = "titulo_bloqueadas") { Encabezado("Bloqueadas (${config.apps.size})") }
            if (bloqueado && config.apps.isNotEmpty()) {
                item(key = "aviso_bloqueo") {
                    AvisoBloqueo(
                        "No puedes quitar apps durante un bloqueo.",
                        Modifier.padding(horizontal = MARGEN, vertical = 4.dp),
                    )
                }
            }
            if (bloqueadas.isEmpty()) {
                item(key = "vacio_bloqueadas") {
                    TextoVacio(
                        if (config.apps.isEmpty()) {
                            "Todavía no hay apps bloqueadas. Activa el interruptor de las que quieras bloquear."
                        } else {
                            "Ninguna app bloqueada coincide con la búsqueda."
                        }
                    )
                }
            }
            items(bloqueadas, key = { "b:" + it.paquete }) { app ->
                FilaApp(app, marcada = true, habilitada = !bloqueado, onCambiar = { quitar(app) })
            }

            item(key = "titulo_otras") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { Encabezado("Otras apps") }
                    Box(
                        modifier = Modifier
                            .padding(end = 4.dp)
                            .size(48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (catalogo.recargando) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else if (lista != null) {
                            IconButton(onClick = { recargar() }) {
                                Icon(Icons.Filled.Refresh, contentDescription = "Recargar la lista de apps")
                            }
                        }
                    }
                }
            }
            when {
                lista == null && catalogo.fallo -> item(key = "fallo") {
                    Column(
                        modifier = Modifier.padding(horizontal = MARGEN, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("No se pudo leer la lista de apps.", style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(onClick = { recargar() }) { Text("Reintentar") }
                    }
                }
                lista == null -> item(key = "cargando") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(MARGEN),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
                        Text(
                            "Cargando apps…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                otras.isEmpty() -> item(key = "vacio_otras") {
                    TextoVacio(
                        if (filtro.isEmpty()) "No hay más apps para bloquear."
                        else "Ninguna app coincide con «${busqueda.trim()}»."
                    )
                }
                else -> items(otras, key = { "o:" + it.paquete }) { app ->
                    FilaApp(app, marcada = false, habilitada = true, onCambiar = { agregar(app) })
                }
            }
        }
    }

    pendiente?.let { p ->
        DialogoConfirmar(
            titulo = "¿Bloquear ${p.nombre}?",
            texto = p.texto,
            textoConfirmar = "Bloquear",
            onConfirmar = {
                pendiente = null
                bloquearApps(listOf(p.paquete))
            },
            onCancelar = { pendiente = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilaApp(app: AppInstalada, marcada: Boolean, habilitada: Boolean, onCambiar: (Boolean) -> Unit) {
    ListItem(
        modifier = Modifier.toggleable(
            value = marcada,
            enabled = habilitada,
            role = Role.Switch,
            onValueChange = onCambiar,
        ),
        headlineContent = { Text(app.nombre, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = if (app.instalada) null else {
            { Text("Ya no está instalada") }
        },
        leadingContent = { IconoApp(app) },
        trailingContent = { Switch(checked = marcada, onCheckedChange = null, enabled = habilitada) },
    )
}

@Composable
private fun IconoApp(app: AppInstalada) {
    val icono = app.icono
    if (icono != null) {
        Image(bitmap = icono, contentDescription = null, modifier = Modifier.size(TAM_ICONO))
    } else {
        Box(Modifier.size(TAM_ICONO), contentAlignment = Alignment.Center) {
            Icon(
                Icons.Filled.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
