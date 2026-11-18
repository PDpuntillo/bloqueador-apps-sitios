package com.bloqueador.android.ui

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bloqueador.android.datos.Almacen
import com.bloqueador.android.dominio.Config
import com.bloqueador.android.dominio.Dominios
import com.bloqueador.android.dominio.Estado
import com.bloqueador.android.dominio.SITIOS_RAPIDOS
import com.bloqueador.android.dominio.SitioRapido
import java.time.LocalDateTime

/**
 * Confirmación pendiente en Sitios. Al confirmar se agregan [sitios] y se bloquean [apps].
 * [rapido] es el nombre del botón de "Agregar rápido" que la abrió ("" si no). Después de
 * agregar sus sitios se ofrece bloquear también sus apps. [escrito] indica que los sitios
 * vienen del campo de texto, que se vacía al confirmar.
 */
private class Pendiente(
    val titulo: String,
    val texto: String,
    val sitios: List<String>,
    val apps: List<String>,
    val rapido: String,
    val escrito: Boolean,
)

/** Así el diálogo sigue abierto al girar la pantalla. */
private val GUARDAR_PENDIENTE = listSaver<Pendiente?, Any>(
    save = { p ->
        if (p == null) {
            emptyList()
        } else {
            listOf(p.titulo, p.texto, ArrayList(p.sitios), ArrayList(p.apps), p.rapido, p.escrito)
        }
    },
    restore = { l ->
        Pendiente(
            titulo = l[0] as String,
            texto = l[1] as String,
            sitios = (l[2] as List<*>).filterIsInstance<String>(),
            apps = (l[3] as List<*>).filterIsInstance<String>(),
            rapido = l[4] as String,
            escrito = l[5] as Boolean,
        )
    },
)

/** True si [texto] trae una página concreta ("reddit.com/r/algo"), no solo el sitio. */
private fun tienePagina(texto: String): Boolean =
    texto.substringAfter("://").dropWhile { it != '/' && it != '?' && it != '#' }.trim('/').isNotEmpty()

/** "Se agregará x.com. Hay un bloqueo activo: ..." para confirmar sitios durante un bloqueo. */
private fun textoSitiosEnBloqueo(dominios: List<String>, estado: Estado): String {
    val hasta = hastaQueTermine(estado, LocalDateTime.now())
    return if (dominios.size == 1) {
        "Se agregará ${dominios[0]}. Hay un bloqueo activo: se bloqueará de inmediato y no podrás " +
            "quitarlo $hasta."
    } else {
        "Se agregarán: ${dominios.joinToString(", ")}. Hay un bloqueo activo: se bloquearán de " +
            "inmediato y no podrás quitarlos $hasta."
    }
}

/** Apps de [sitio] que están instaladas (y activadas) pero todavía no están bloqueadas. */
private fun appsSinBloquear(context: Context, sitio: SitioRapido): List<String> {
    val bloqueadas = Almacen.config.apps
    val pm = context.packageManager
    return sitio.apps.filter { it !in bloqueadas && instalada(pm, it) }
}

@Suppress("DEPRECATION")
private fun instalada(pm: PackageManager, paquete: String): Boolean = try {
    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.getApplicationInfo(paquete, PackageManager.ApplicationInfoFlags.of(0L))
    } else {
        pm.getApplicationInfo(paquete, 0)
    }
    info.enabled
} catch (e: PackageManager.NameNotFoundException) {
    false
} catch (e: RuntimeException) {
    false
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PantallaSitios(
    config: Config,
    bloqueado: Boolean,
    avisar: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val foco = LocalFocusManager.current
    var texto by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var pendiente by rememberSaveable(stateSaver = GUARDAR_PENDIENTE) { mutableStateOf<Pendiente?>(null) }
    val actuales = remember(config.sitios) { config.sitios.toHashSet() }

    /** Guarda los dominios y dice cuáles se agregaron. */
    fun guardar(dominios: List<String>, aclaracion: String = "") {
        Almacen.actualizar { c -> c.copy(sitios = unirOrdenado(c.sitios, dominios)) }
        avisar("Agregado: ${dominios.joinToString(", ")}$aclaracion.")
    }

    /** Si la app del botón rápido [nombre] está instalada y sin bloquear, ofrece bloquearla. */
    fun ofrecerApps(nombre: String) {
        val sitio = SITIOS_RAPIDOS.find { it.nombre == nombre } ?: return
        val apps = appsSinBloquear(context, sitio)
        if (apps.isEmpty()) return
        val estado = Almacen.config.estado()
        val aviso = if (estado.activo) {
            "\n\nHay un bloqueo activo: se bloqueará de inmediato y no podrás quitarla " +
                "${hastaQueTermine(estado, LocalDateTime.now())}."
        } else {
            ""
        }
        pendiente = Pendiente(
            titulo = "Bloquear la app de $nombre",
            texto = "La app de $nombre puede saltarse el filtro de sitios. ¿Bloquearla también?$aviso",
            sitios = emptyList(),
            apps = apps,
            rapido = nombre,
            escrito = false,
        )
    }

    fun agregarRapido(sitio: SitioRapido) {
        val faltan = sitio.dominios.filter { it !in Almacen.config.sitios }.distinct()
        if (faltan.isEmpty()) {
            avisar("${sitio.nombre} ya está en la lista.")
            return
        }
        val estado = Almacen.config.estado()
        if (estado.activo) {
            pendiente = Pendiente(
                titulo = "¿Bloquear ${sitio.nombre} ahora?",
                texto = textoSitiosEnBloqueo(faltan, estado),
                sitios = faltan,
                apps = emptyList(),
                rapido = sitio.nombre,
                escrito = false,
            )
        } else {
            guardar(faltan)
            ofrecerApps(sitio.nombre)
        }
    }

    fun agregarEscrito() {
        val escrito = texto.trim()
        if (escrito.isEmpty()) return
        val dominio = Dominios.normalizar(escrito)
        if (dominio == null) {
            error = "«$escrito» no parece una dirección web. Ejemplo: youtube.com"
            return
        }
        // El aviso va en el propio campo: un aviso abajo quedaría tapado por el teclado.
        if (dominio in Almacen.config.sitios) {
            error = "«$dominio» ya está en la lista."
            return
        }
        error = null
        val pagina = tienePagina(escrito)
        val estado = Almacen.config.estado()
        if (estado.activo) {
            foco.clearFocus()
            pendiente = Pendiente(
                titulo = "¿Bloquear $dominio ahora?",
                texto = textoSitiosEnBloqueo(listOf(dominio), estado) +
                    if (pagina) " Se bloqueará el sitio entero, no solo esa página." else "",
                sitios = listOf(dominio),
                apps = emptyList(),
                rapido = "",
                escrito = true,
            )
        } else {
            guardar(listOf(dominio), if (pagina) " (el sitio entero, no solo esa página)" else "")
            texto = ""
            foco.clearFocus()
        }
    }

    fun quitar(sitio: String) {
        if (quitarSiLibre { c -> c.copy(sitios = c.sitios - sitio) }) {
            avisar("Quitado: $sitio.")
        } else {
            avisar("No puedes quitar sitios durante un bloqueo.")
        }
    }

    LazyColumn(modifier = modifier, contentPadding = PaddingValues(top = 8.dp, bottom = MARGEN)) {
        item(key = "campo") {
            val mensajeError = error
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MARGEN),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = texto,
                    onValueChange = {
                        texto = it
                        error = null
                    },
                    modifier = Modifier.weight(1f),
                    label = { Text("Dirección web") },
                    placeholder = { Text("youtube.com") },
                    singleLine = true,
                    isError = mensajeError != null,
                    supportingText = if (mensajeError == null) null else {
                        { Text(mensajeError) }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { agregarEscrito() }),
                )
                // El margen superior alinea el botón con el recuadro del campo (el título ocupa 8 dp).
                Button(onClick = { agregarEscrito() }, modifier = Modifier.padding(top = 12.dp)) {
                    Text("Agregar")
                }
            }
        }
        item(key = "ayuda") {
            TextoAyuda(
                "También se bloquean sus subdominios (www., m., etc.). Si escribes una página, " +
                    "se bloquea el sitio entero.",
                Modifier.padding(start = MARGEN, end = MARGEN, top = 4.dp),
            )
        }
        item(key = "rapido") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = MARGEN, end = MARGEN, top = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("Agregar rápido", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SITIOS_RAPIDOS.forEach { sitio ->
                        // Marcado solo si ya están todos sus dominios.
                        val yaEstan = sitio.dominios.all { it in actuales }
                        FilterChip(
                            selected = yaEstan,
                            onClick = { agregarRapido(sitio) },
                            label = { Text(sitio.nombre) },
                            enabled = !yaEstan,
                            leadingIcon = {
                                Icon(
                                    imageVector = if (yaEstan) Icons.Filled.Check else Icons.Filled.Add,
                                    contentDescription = null,
                                    modifier = Modifier.size(FilterChipDefaults.IconSize),
                                )
                            },
                            colors = coloresChip(),
                        )
                    }
                }
                TextoAyuda(
                    "Incluye los dominios de sus videos e imágenes. Su app no pasa por este filtro: " +
                        "si la tienes instalada, te ofrecemos bloquearla también."
                )
            }
        }

        item(key = "titulo") { Encabezado("Sitios bloqueados (${config.sitios.size})") }
        if (bloqueado && config.sitios.isNotEmpty()) {
            item(key = "aviso_bloqueo") {
                AvisoBloqueo(
                    "No puedes quitar sitios durante un bloqueo.",
                    Modifier.padding(horizontal = MARGEN, vertical = 4.dp),
                )
            }
        }
        if (config.sitios.isEmpty()) {
            item(key = "vacio") { TextoVacio("Todavía no hay sitios en la lista.") }
        }
        items(config.sitios.distinct(), key = { "s:$it" }) { sitio ->
            ListItem(
                headlineContent = { Text(sitio, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                trailingContent = {
                    IconButton(onClick = { quitar(sitio) }, enabled = !bloqueado) {
                        Icon(Icons.Filled.Delete, contentDescription = "Quitar $sitio")
                    }
                },
            )
        }
    }

    pendiente?.let { p ->
        DialogoConfirmar(
            titulo = p.titulo,
            texto = p.texto,
            textoConfirmar = "Bloquear",
            onConfirmar = {
                pendiente = null
                if (p.sitios.isNotEmpty()) guardar(p.sitios)
                if (p.apps.isNotEmpty()) {
                    bloquearApps(p.apps)
                    avisar("App bloqueada: ${p.rapido}.")
                }
                if (p.escrito) {
                    texto = ""
                    foco.clearFocus()
                }
                // Después de los sitios de un botón rápido, se ofrecen sus apps.
                if (p.sitios.isNotEmpty() && p.rapido.isNotEmpty()) ofrecerApps(p.rapido)
            },
            onCancelar = { pendiente = null },
        )
    }
}
