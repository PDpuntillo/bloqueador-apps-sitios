package com.bloqueador.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bloqueador.android.datos.Almacen
import com.bloqueador.android.dominio.Config
import com.bloqueador.android.dominio.DIAS
import com.bloqueador.android.dominio.Horario
import com.bloqueador.android.dominio.cuando
import com.bloqueador.android.dominio.normalizarHora
import java.time.LocalDateTime

/** Días marcados por defecto: bit i encendido = día i (0 = lunes). Lunes a viernes. */
private const val LUN_A_VIE = 0b0011111

private const val SIN_PAUSA =
    "Con este horario el bloqueo no terminaría nunca: deja al menos un rato libre en la semana."

/** Los horarios se guardan ordenados por hora de inicio. */
private val ORDEN_HORARIOS: Comparator<Horario> =
    compareBy<Horario>({ it.inicio }, { it.fin }, { it.dias.sorted().joinToString(",") })

/** Hora que se está eligiendo en el reloj. */
private enum class CampoHora { INICIO, FIN }

/** Horario nuevo que empieza o alarga el bloqueo en curso: se confirma antes de agregarlo. */
private class HorarioEnCurso(val horario: Horario, val titulo: String, val texto: String)

private fun bitsDeDias(dias: Set<Int>): Int = dias.fold(0) { bits, d -> bits or (1 shl d) }

private fun diasDeBits(bits: Int): Set<Int> = (0..6).filter { (bits and (1 shl it)) != 0 }.toSet()

/** Así el diálogo sigue abierto al girar la pantalla. */
private val GUARDAR_EN_CURSO = listSaver<HorarioEnCurso?, Any>(
    save = { p ->
        if (p == null) {
            emptyList()
        } else {
            listOf(bitsDeDias(p.horario.dias), p.horario.inicio, p.horario.fin, p.titulo, p.texto)
        }
    },
    restore = { l ->
        HorarioEnCurso(
            Horario(diasDeBits(l[0] as Int), l[1] as String, l[2] as String),
            titulo = l[3] as String,
            texto = l[4] as String,
        )
    },
)

private fun dosDigitos(n: Int): String = n.toString().padStart(2, '0')

/**
 * Agrega un horario (sin duplicados y en orden). Devuelve false, sin guardarlo, si con él los
 * horarios cubrirían toda la semana y el bloqueo no terminaría nunca.
 */
private fun guardarHorario(nuevo: Horario): Boolean {
    var valido = true
    Almacen.actualizar { c ->
        if (nuevo in c.horarios) return@actualizar c
        val n = c.copy(horarios = (c.horarios + nuevo).distinct().sortedWith(ORDEN_HORARIOS))
        if (n.horariosSinPausa()) {
            valido = false
            c
        } else {
            n
        }
    }
    return valido
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PantallaHorarios(
    config: Config,
    bloqueado: Boolean,
    ahora: LocalDateTime,
    avisar: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var dias by rememberSaveable { mutableStateOf(LUN_A_VIE) }
    var inicio by rememberSaveable { mutableStateOf("09:00") }
    var fin by rememberSaveable { mutableStateOf("17:00") }
    var eligiendo by rememberSaveable { mutableStateOf<CampoHora?>(null) }
    var enCurso by rememberSaveable(stateSaver = GUARDAR_EN_CURSO) { mutableStateOf<HorarioEnCurso?>(null) }
    // Solo con configuraciones de versiones anteriores: quitarSiLibre deja quitar horarios.
    val sinPausa = remember(config.horarios) { config.horariosSinPausa() }

    fun agregarHorario() {
        val elegidos = diasDeBits(dias)
        if (elegidos.isEmpty()) {
            avisar("Marca al menos un día.")
            return
        }
        val ini = normalizarHora(inicio)
        val f = normalizarHora(fin)
        if (ini == null || f == null) {
            avisar("Elige la hora de inicio y la de fin.")
            return
        }
        val nuevo = Horario(elegidos, ini, f)
        val actual = Almacen.config
        if (nuevo in actual.horarios) {
            avisar("Ese horario ya existe.")
            return
        }
        val con = actual.copy(horarios = actual.horarios + nuevo)
        if (con.horariosSinPausa()) {
            avisar(SIN_PAUSA)
            return
        }
        val momento = LocalDateTime.now()
        val antes = actual.estado(momento)
        val despues = con.estado(momento)
        val hasta = despues.hasta
        // Se confirma si el horario empieza un bloqueo ahora o alarga el que está en curso
        // (también si no está en curso, pero empieza justo cuando termina el bloqueo actual).
        if (despues.activo && hasta != null && (!antes.activo || antes.hasta?.let { hasta.isAfter(it) } == true)) {
            enCurso = if (antes.activo) {
                HorarioEnCurso(
                    nuevo,
                    "El bloqueo se alargará",
                    "Con este horario, el bloqueo en curso seguirá hasta ${cuando(hasta, momento)} " +
                        "y no podrás quitarlo antes.",
                )
            } else {
                HorarioEnCurso(
                    nuevo,
                    "El bloqueo empezará ahora",
                    "Este horario está en curso: el bloqueo empezará de inmediato, seguirá hasta " +
                        "${cuando(hasta, momento)} y no podrás quitarlo antes.",
                )
            }
        } else if (guardarHorario(nuevo)) {
            avisar("Horario agregado: ${nuevo.describir()}.")
        } else {
            avisar(SIN_PAUSA)
        }
    }

    fun quitar(h: Horario) {
        if (quitarSiLibre { c -> c.copy(horarios = c.horarios - h) }) {
            avisar("Horario quitado.")
        } else {
            avisar("No puedes quitar horarios durante un bloqueo.")
        }
    }

    LazyColumn(modifier = modifier, contentPadding = PaddingValues(top = 8.dp, bottom = MARGEN)) {
        item(key = "nuevo") {
            Tarjeta(
                modifier = Modifier.padding(horizontal = MARGEN, vertical = 8.dp),
                titulo = "Nuevo horario",
            ) {
                Text("Días", style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DIAS.forEachIndexed { i, nombre ->
                        val bit = 1 shl i
                        FilterChip(
                            selected = (dias and bit) != 0,
                            onClick = { dias = dias xor bit },
                            label = { Text(nombre) },
                            colors = coloresChip(),
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { eligiendo = CampoHora.INICIO },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Desde $inicio")
                    }
                    OutlinedButton(
                        onClick = { eligiendo = CampoHora.FIN },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Hasta $fin")
                    }
                }
                TextoAyuda(
                    "Si la hora final es menor que la inicial, termina al día siguiente (ej. 22:00 → 07:00). " +
                        "Si las dos horas son iguales, dura 24 horas."
                )
                Button(onClick = { agregarHorario() }, modifier = Modifier.fillMaxWidth()) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.IconSize),
                    )
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text("Agregar horario")
                }
            }
        }

        item(key = "titulo") { Encabezado("Horarios programados (${config.horarios.size})") }
        if (sinPausa) {
            item(key = "aviso_sin_pausa") {
                AvisoBloqueo(
                    "Tus horarios cubren toda la semana y el bloqueo no terminaría nunca: quita alguno.",
                    Modifier.padding(horizontal = MARGEN, vertical = 4.dp),
                )
            }
        } else if (bloqueado && config.horarios.isNotEmpty()) {
            item(key = "aviso_bloqueo") {
                AvisoBloqueo(
                    "No puedes quitar horarios durante un bloqueo.",
                    Modifier.padding(horizontal = MARGEN, vertical = 4.dp),
                )
            }
        }
        if (config.horarios.isEmpty()) {
            item(key = "vacio") { TextoVacio("No hay horarios programados.") }
        }
        // Sin clave: una configuración vieja podría traer horarios repetidos.
        items(config.horarios) { h ->
            val termina = h.finActual(ahora)
            ListItem(
                headlineContent = { Text(h.describir()) },
                supportingContent = if (termina == null) null else {
                    {
                        Text(
                            "En curso · termina ${cuando(termina, ahora)}",
                            color = coloresEstado(rojo = true).texto,
                        )
                    }
                },
                leadingContent = { Icon(Icons.Filled.DateRange, contentDescription = null) },
                trailingContent = {
                    IconButton(onClick = { quitar(h) }, enabled = !bloqueado) {
                        Icon(Icons.Filled.Delete, contentDescription = "Quitar horario")
                    }
                },
            )
        }
    }

    when (eligiendo) {
        CampoHora.INICIO -> DialogoHora(
            titulo = "Desde",
            actual = inicio,
            onElegir = {
                inicio = it
                eligiendo = null
            },
            onCancelar = { eligiendo = null },
        )
        CampoHora.FIN -> DialogoHora(
            titulo = "Hasta",
            actual = fin,
            onElegir = {
                fin = it
                eligiendo = null
            },
            onCancelar = { eligiendo = null },
        )
        null -> Unit
    }

    enCurso?.let { p ->
        DialogoConfirmar(
            titulo = p.titulo,
            texto = p.texto,
            textoConfirmar = "Agregar",
            onConfirmar = {
                enCurso = null
                if (guardarHorario(p.horario)) {
                    avisar("Horario agregado: ${p.horario.describir()}.")
                } else {
                    avisar(SIN_PAUSA)
                }
            },
            onCancelar = { enCurso = null },
        )
    }
}

/**
 * Reloj de Material (24 h) dentro de un diálogo de Compose: al girar la pantalla no se pierde
 * (el diálogo del sistema sí se perdía) y recuerda la hora que ibas eligiendo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DialogoHora(
    titulo: String,
    actual: String,
    onElegir: (String) -> Unit,
    onCancelar: () -> Unit,
) {
    val estado = rememberTimePickerState(
        actual.substringBefore(':').toIntOrNull() ?: 9,
        actual.substringAfter(':').toIntOrNull() ?: 0,
        true,
    )
    AlertDialog(
        onDismissRequest = onCancelar,
        title = { Text(titulo) },
        text = {
            // Con la pantalla girada puede no caber entero: se desplaza.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                contentAlignment = Alignment.Center,
            ) {
                TimePicker(estado)
            }
        },
        confirmButton = {
            TextButton(onClick = { onElegir(dosDigitos(estado.hour) + ":" + dosDigitos(estado.minute)) }) {
                Text("Aceptar")
            }
        },
        dismissButton = { TextButton(onClick = onCancelar) { Text("Cancelar") } },
    )
}
