package com.bloqueador.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bloqueador.android.datos.Almacen
import com.bloqueador.android.datos.AlmacenUso
import com.bloqueador.android.dominio.Config
import com.bloqueador.android.dominio.Dominios
import com.bloqueador.android.dominio.Limite
import com.bloqueador.android.dominio.MAX_MINUTOS_LIMITE
import com.bloqueador.android.dominio.TipoLimite
import com.bloqueador.android.dominio.UsoDiario
import com.bloqueador.android.dominio.etiquetaMinutos
import com.bloqueador.android.dominio.minutosElegidos
import com.bloqueador.android.dominio.sePuedeAflojar
import kotlinx.coroutines.launch
import java.time.LocalDateTime

/** Tiempos rápidos para un límite diario. */
private val LIMITES_RAPIDOS = listOf(15, 30, 60, 120)

/** Reloj de arena («hourglass_empty» de Material Icons), para la pestaña Límites. */
internal val IconoLimite: ImageVector by lazy {
    ImageVector.Builder(
        name = "Limite",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).addPath(
        pathData = PathParser().parsePathString(
            "M6,2v6h0.01L6,8.01 10,12l-4,4 0.01,0.01H6V22h12v-5.99h-0.01L18,16l-4,-4 4,-3.99 " +
                "-0.01,-0.01H18V2H6zM16,16.5V20H8v-3.5l4,-4 4,4zM12,11.5l-4,-4V4h8v3.5l-4,4z"
        ).toNodes(),
        fill = SolidColor(Color.Black),
    ).build()
}

/**
 * Guarda [nuevo] (agrega el límite o cambia el de esa app o sitio). Vuelve a comprobar la regla
 * dentro de la actualización: aumentar un límite solo se puede si [sePuedeAflojar]. Devuelve
 * false si no se pudo.
 */
internal fun guardarLimite(nuevo: Limite): Boolean {
    var hecho = false
    Almacen.actualizar { c ->
        val actual = c.limite(nuevo.tipo, nuevo.nombre)
        if (actual != null && nuevo.minutos > actual.minutos &&
            !sePuedeAflojar(actual, c.estado().activo, AlmacenUso.hoy())
        ) {
            c
        } else {
            hecho = true
            c.conLimite(nuevo)
        }
    }
    return hecho
}

/** Quita [limite] si [sePuedeAflojar]. Devuelve false si no se pudo. */
internal fun quitarLimite(limite: Limite): Boolean {
    var hecho = false
    Almacen.actualizar { c ->
        val actual = c.limite(limite.tipo, limite.nombre)
        if (actual == null || !sePuedeAflojar(actual, c.estado().activo, AlmacenUso.hoy())) {
            c
        } else {
            hecho = true
            c.copy(limites = c.limites.filter { it.clave != actual.clave })
        }
    }
    return hecho
}

/** Por qué no se puede quitar o aumentar el límite de [nombre]. [accion]: "quitar" o "aumentar". */
private fun motivoNoAflojar(nombre: String, accion: String): String =
    if (Almacen.config.estado().activo) {
        "Durante un bloqueo no puedes $accion límites."
    } else {
        "Hoy ya se acabó el tiempo de $nombre: podrás $accion su límite mañana."
    }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PantallaLimites(
    config: Config,
    bloqueado: Boolean,
    ahora: LocalDateTime,
    servicios: EstadoServicios,
    catalogo: CatalogoApps,
    reanudaciones: Int,
    avisar: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val foco = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val listaEstado = rememberLazyListState()
    val tamIcono = with(LocalDensity.current) { TAM_ICONO.roundToPx() }
    var tipo by rememberSaveable { mutableStateOf(TipoLimite.SITIO) }
    var paquete by rememberSaveable { mutableStateOf("") }
    var textoSitio by rememberSaveable { mutableStateOf("") }
    var elegido by rememberSaveable { mutableStateOf(30) }
    var textoHoras by rememberSaveable { mutableStateOf("") }
    var textoMinutos by rememberSaveable { mutableStateOf("") }
    var eligiendoApp by rememberSaveable { mutableStateOf(false) }
    var pendiente by remember { mutableStateOf<Limite?>(null) }
    var aQuitar by remember { mutableStateOf<Limite?>(null) }

    DisposableEffect(Unit) {
        catalogo.enUso()
        onDispose { catalogo.sinUso() }
    }
    // Para mostrar el nombre y el ícono de las apps (y elegir una).
    LaunchedEffect(reanudaciones, tamIcono) { catalogo.cargar(context, tamIcono) }

    // Se vuelve a leer cada segundo (con [ahora]): el tiempo de hoy avanza mientras usas algo.
    val uso = remember(ahora, config) { AlmacenUso.hoy() }
    val lista = catalogo.lista
    fun nombreDe(l: Limite): String =
        if (l.tipo == TipoLimite.APP) lista?.todas?.get(l.nombre)?.nombre ?: l.nombre else l.nombre

    // Las casillas, si tienen algo escrito, mandan sobre los chips.
    val escrito = textoHoras.isNotBlank() || textoMinutos.isNotBlank()
    val minutos: Int? = if (escrito) minutosElegidos(textoHoras, textoMinutos, MAX_MINUTOS_LIMITE) else elegido
    val tiempoInvalido = escrito && minutos == null
    val sitio = Dominios.normalizar(textoSitio)
    val sitioInvalido = tipo == TipoLimite.SITIO && textoSitio.isNotBlank() && sitio == null
    val objetivo: String? = if (tipo == TipoLimite.APP) paquete.takeIf { it.isNotEmpty() } else sitio
    val existente = objetivo?.let { config.limite(tipo, it) }

    fun limpiar() {
        paquete = ""
        textoSitio = ""
        textoHoras = ""
        textoMinutos = ""
    }

    fun aplicar(nuevo: Limite) {
        if (guardarLimite(nuevo)) {
            avisar("Límite guardado: ${nombreDe(nuevo)}, ${etiquetaMinutos(nuevo.minutos)} por día.")
            limpiar()
        } else {
            avisar(motivoNoAflojar(nombreDe(nuevo), "aumentar"))
        }
    }

    fun guardar() {
        val nombre = objetivo ?: return
        val m = minutos ?: return
        foco.clearFocus()
        val nuevo = Limite(tipo, nombre, m)
        val hoy = AlmacenUso.hoy()
        val actual = Almacen.config.limite(tipo, nombre)
        when {
            actual?.minutos == m -> avisar("Ese límite ya existe.")
            actual != null && m > actual.minutos && !sePuedeAflojar(actual, Almacen.config.estado().activo, hoy) ->
                avisar(motivoNoAflojar(nombreDe(actual), "aumentar"))
            // Hoy ya usó más que eso (tenía un límite y lo quitó): se bloquea en seguida.
            hoy.agotado(nuevo) && !(actual != null && hoy.agotado(actual)) -> pendiente = nuevo
            else -> aplicar(nuevo)
        }
    }

    /** Al tocar un límite de la lista: lo carga en el formulario para cambiarlo. */
    fun editar(l: Limite) {
        tipo = l.tipo
        if (l.tipo == TipoLimite.APP) paquete = l.nombre else textoSitio = l.nombre
        if (l.minutos in LIMITES_RAPIDOS) {
            elegido = l.minutos
            textoHoras = ""
            textoMinutos = ""
        } else {
            textoHoras = (l.minutos / 60).takeIf { it > 0 }?.toString().orEmpty()
            textoMinutos = (l.minutos % 60).toString()
        }
        scope.launch { listaEstado.animateScrollToItem(0) }
    }

    LazyColumn(
        modifier = modifier,
        state = listaEstado,
        contentPadding = PaddingValues(MARGEN),
        verticalArrangement = Arrangement.spacedBy(MARGEN),
    ) {
        if (!servicios.accesibilidad) {
            item(key = "sin_accesibilidad") {
                AvisoBloqueo(
                    "Para contar el tiempo y bloquear al acabarse, activa el servicio de " +
                        "accesibilidad del Bloqueador (en la pestaña Inicio)."
                )
            }
        }
        item(key = "formulario") {
            Tarjeta(titulo = if (existente != null) "Cambiar límite" else "Nuevo límite diario") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = tipo == TipoLimite.SITIO,
                        onClick = { tipo = TipoLimite.SITIO },
                        label = { Text("Sitio web") },
                        colors = coloresChip(),
                    )
                    FilterChip(
                        selected = tipo == TipoLimite.APP,
                        onClick = { tipo = TipoLimite.APP },
                        label = { Text("App") },
                        colors = coloresChip(),
                    )
                }
                if (tipo == TipoLimite.SITIO) {
                    OutlinedTextField(
                        value = textoSitio,
                        onValueChange = { textoSitio = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Dirección") },
                        placeholder = { Text("instagram.com") },
                        singleLine = true,
                        isError = sitioInvalido,
                        supportingText = if (sitioInvalido) {
                            { Text("No parece una dirección web. Ejemplo: instagram.com") }
                        } else {
                            null
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    )
                } else {
                    val app = lista?.todas?.get(paquete)
                    OutlinedButton(onClick = { eligiendoApp = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            if (paquete.isEmpty()) "Elegir app" else app?.nombre ?: paquete,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Text("Máximo por día", style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LIMITES_RAPIDOS.forEach { m ->
                        FilterChip(
                            selected = !escrito && elegido == m,
                            onClick = {
                                elegido = m
                                textoHoras = ""
                                textoMinutos = ""
                            },
                            label = { Text(etiquetaMinutos(m)) },
                            colors = coloresChip(),
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = textoHoras,
                        onValueChange = { nuevo -> textoHoras = nuevo.filter { it.isDigit() }.take(2) },
                        modifier = Modifier.weight(1f),
                        label = { Text("Horas") },
                        singleLine = true,
                        isError = tiempoInvalido,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                    )
                    OutlinedTextField(
                        value = textoMinutos,
                        onValueChange = { nuevo -> textoMinutos = nuevo.filter { it.isDigit() }.take(2) },
                        modifier = Modifier.weight(1f),
                        label = { Text("Minutos") },
                        singleLine = true,
                        isError = tiempoInvalido,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { foco.clearFocus() }),
                    )
                }
                if (tiempoInvalido) {
                    Text(
                        "Elige entre 1 minuto y 23 h 59 min (los minutos van de 0 a 59).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Button(
                    onClick = { guardar() },
                    enabled = objetivo != null && minutos != null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (existente != null) "Cambiar límite" else "Agregar límite")
                }
                TextoAyuda(
                    if (tipo == TipoLimite.SITIO) {
                        "Cuenta el tiempo que el sitio está a la vista en Chrome, Samsung Internet, " +
                            "Firefox, Edge, Brave, Opera u otro navegador conocido. Al acabarse se tapa y, " +
                            "con el filtro de sitios activado, deja de cargar."
                    } else {
                        "Cuenta el tiempo que la app está en pantalla. Al acabarse, se tapa con la " +
                            "pantalla de bloqueo."
                    }
                )
            }
        }
        item(key = "titulo_lista") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Tus límites (${config.limites.size})", style = MaterialTheme.typography.titleSmall)
                TextoAyuda(
                    "Se reinician cada día a las 00:00. Agregar o bajar un límite se puede siempre; " +
                        "quitarlo o aumentarlo, no durante un bloqueo ni si hoy ya se acabó su tiempo."
                )
            }
        }
        if (bloqueado && config.limites.isNotEmpty()) {
            item(key = "aviso_bloqueo") { AvisoBloqueo("No puedes quitar ni aumentar límites durante un bloqueo.") }
        }
        if (config.limites.isEmpty()) {
            item(key = "vacio") {
                TextoAyuda("Todavía no hay límites. Por ejemplo: Instagram, 30 minutos por día.")
            }
        }
        items(config.limites, key = { it.clave }) { l ->
            FilaLimite(
                limite = l,
                nombre = nombreDe(l),
                app = if (l.tipo == TipoLimite.APP) lista?.todas?.get(l.nombre) else null,
                uso = uso,
                sePuedeQuitar = sePuedeAflojar(l, bloqueado, uso),
                alTocar = { editar(l) },
                alQuitar = { aQuitar = l },
            )
        }
    }

    if (eligiendoApp) {
        DialogoElegirApp(
            lista = lista,
            alElegir = { app ->
                paquete = app.paquete
                eligiendoApp = false
            },
            alCerrar = { eligiendoApp = false },
        )
    }

    pendiente?.let { p ->
        val usados = uso.usadosMs(p) / 60_000L
        DialogoConfirmar(
            titulo = "¿Bloquear ${nombreDe(p)} ahora?",
            texto = "Hoy ya usaste ${nombreDe(p)} $usados min: con este límite se bloquea de inmediato " +
                "hasta mañana, y no podrás quitarlo ni aumentarlo hasta entonces.",
            textoConfirmar = "Agregar",
            onConfirmar = {
                pendiente = null
                aplicar(p)
            },
            onCancelar = { pendiente = null },
        )
    }

    aQuitar?.let { l ->
        DialogoConfirmar(
            titulo = "¿Quitar el límite de ${nombreDe(l)}?",
            texto = "Dejará de contarse su tiempo y no se bloqueará por uso.",
            textoConfirmar = "Quitar",
            onConfirmar = {
                aQuitar = null
                if (!quitarLimite(l)) avisar(motivoNoAflojar(nombreDe(l), "quitar"))
            },
            onCancelar = { aQuitar = null },
        )
    }
}

@Composable
private fun FilaLimite(
    limite: Limite,
    nombre: String,
    app: AppInstalada?,
    uso: UsoDiario,
    sePuedeQuitar: Boolean,
    alTocar: () -> Unit,
    alQuitar: () -> Unit,
) {
    val agotado = uso.agotado(limite)
    val usados = uso.usadosMs(limite)
    val total = limite.minutos * 60_000L
    val restantes = (uso.restantesMs(limite) + 59_999L) / 60_000L   // redondeado hacia arriba
    val color = if (agotado) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    OutlinedCard(onClick = alTocar, modifier = Modifier.fillMaxWidth()) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            headlineContent = { Text(nombre, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = {
                Column {
                    Text("${etiquetaMinutos(limite.minutos)} por día")
                    Text(
                        if (agotado) "Se acabó por hoy · vuelve a las 00:00"
                        else "Hoy: ${usados / 60_000L} min · quedan ${etiquetaMinutos(restantes.toInt())}",
                        color = if (agotado) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { (usados.toFloat() / total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                        color = color,
                    )
                }
            },
            leadingContent = {
                if (app != null) {
                    IconoApp(app)
                } else {
                    Box(Modifier.size(TAM_ICONO), contentAlignment = Alignment.Center) {
                        Icon(
                            if (limite.tipo == TipoLimite.SITIO) Icons.AutoMirrored.Filled.List else IconoLimite,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            trailingContent = {
                IconButton(onClick = alQuitar, enabled = sePuedeQuitar) {
                    Icon(Icons.Filled.Delete, contentDescription = "Quitar el límite de $nombre")
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DialogoElegirApp(lista: ListaApps?, alElegir: (AppInstalada) -> Unit, alCerrar: () -> Unit) {
    var busqueda by rememberSaveable { mutableStateOf("") }
    val filtro = remember(busqueda) { claveBusqueda(busqueda.trim()) }
    val apps = remember(lista, filtro) {
        lista?.elegibles.orEmpty().filter { filtro.isEmpty() || filtro in it.clave }
    }
    AlertDialog(
        onDismissRequest = alCerrar,
        title = { Text("Elegir app") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = busqueda,
                    onValueChange = { busqueda = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Buscar por nombre") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                )
                when {
                    lista == null -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
                        Text("Cargando apps…")
                    }
                    apps.isEmpty() -> Text("Ninguna app coincide con «${busqueda.trim()}».")
                    else -> LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(apps, key = { it.paquete }) { app ->
                            ListItem(
                                modifier = Modifier.clickable { alElegir(app) },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                headlineContent = { Text(app.nombre, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                leadingContent = { IconoApp(app) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = alCerrar) { Text("Cancelar") } },
    )
}
