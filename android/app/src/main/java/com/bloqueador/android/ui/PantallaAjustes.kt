package com.bloqueador.android.ui

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.bloqueador.android.bloqueo.AdminDispositivo
import com.bloqueador.android.bloqueo.Servicios
import com.bloqueador.android.datos.Almacen
import com.bloqueador.android.dominio.Config
import java.time.LocalDateTime

@Composable
fun PantallaAjustes(
    config: Config,
    bloqueado: Boolean,
    servicios: EstadoServicios,
    avisar: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val version = remember { versionApp(context) }
    // Texto del diálogo para activar el modo estricto durante un bloqueo (null = cerrado).
    var confirmarEstricto by rememberSaveable { mutableStateOf<String?>(null) }
    // Durante un bloqueo se puede activar, pero no desactivar.
    val switchFijo = bloqueado && config.modoEstricto
    // Con el modo estricto funcionando, Ajustes del sistema no abre hasta que termine el bloqueo.
    val ajustesBloqueados = bloqueado && config.modoEstricto && servicios.accesibilidad

    fun cambiarEstricto(activar: Boolean) {
        if (activar) {
            val estado = Almacen.config.estado()
            if (estado.activo) {
                confirmarEstricto = "Hay un bloqueo activo: Ajustes, el instalador de apps y las " +
                    "tiendas se bloquearán de inmediato, y no podrás desactivar el modo estricto " +
                    "${hastaQueTermine(estado, LocalDateTime.now())}."
            } else {
                Almacen.actualizar { it.copy(modoEstricto = true) }
            }
        } else if (!quitarSiLibre { it.copy(modoEstricto = false) }) {
            avisar("No puedes desactivar el modo estricto durante un bloqueo.")
        }
    }

    // Pantalla del sistema para activar la protección contra desinstalación. Se abre "para
    // resultado": así va sin FLAG_ACTIVITY_NEW_TASK, que Ajustes rechaza.
    val pedirProteccion = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { resultado ->
        if (resultado.resultCode == Activity.RESULT_OK) avisar("Protección contra desinstalación activada.")
    }

    fun activarProteccion() {
        try {
            pedirProteccion.launch(AdminDispositivo.intentActivar(context, EXPLICACION_PROTECCION))
        } catch (e: RuntimeException) {
            avisar("Este teléfono no dejó abrir la protección contra desinstalación.")
        }
    }

    fun quitarProteccion() {
        // Se vuelve a comprobar con la configuración guardada, como al quitar cualquier otra cosa.
        if (Almacen.config.estado().activo) {
            avisar("No puedes quitar la protección durante un bloqueo.")
            return
        }
        try {
            AdminDispositivo.quitar(context)
            avisar("Protección contra desinstalación quitada.")
        } catch (e: RuntimeException) {
            avisar("No se pudo quitar la protección.")
        }
    }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(MARGEN),
        verticalArrangement = Arrangement.spacedBy(MARGEN),
    ) {
        Tarjeta {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = config.modoEstricto,
                        enabled = !switchFijo,
                        role = Role.Switch,
                        onValueChange = { cambiarEstricto(it) },
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Modo estricto",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = config.modoEstricto, onCheckedChange = null, enabled = !switchFijo)
            }
            Text(
                "Durante un bloqueo también se bloquean Ajustes, los gestores de apps del fabricante, " +
                    "el instalador de apps y las tiendas, para que no puedas desactivar el Bloqueador " +
                    "ni instalar otra app que se lo salte. En una emergencia, reinicia el teléfono en " +
                    "modo seguro.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextoAyuda(
                "Desinstalar el Bloqueador desde Ajustes o con el instalador queda bloqueado. Desde " +
                    "algunos launchers (por ejemplo, el de Xiaomi), solo queda bloqueado si activas " +
                    "la Protección contra desinstalación, aquí abajo."
            )
            TextoAyuda(
                "Sí puedes dar permisos a una app, vincular un Bluetooth y escribir la contraseña de " +
                    "una Wi-Fi. Como Google Play queda bloqueado, tampoco funcionan las compras dentro " +
                    "de otras apps hasta que termine el bloqueo."
            )
            TextoAyuda(
                "Para que no queden atajos, desactiva «Fijar apps» (búscalo en Ajustes): con una " +
                    "app fijada en pantalla, el Bloqueador no puede taparla."
            )
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                TextoAyuda(
                    "No asignes el Bloqueador al atajo de accesibilidad (mantener las teclas de " +
                        "volumen): en esta versión de Android, ese atajo lo apaga sin pasar por Ajustes."
                )
            }
            if (config.modoEstricto && !servicios.accesibilidad) {
                TextoAyuda("Para que funcione, activa el bloqueo de apps en Inicio.")
            }
            if (Servicios.necesitaAccesoUso) {
                if (servicios.accesoUso) {
                    TextoAyuda(
                        "«Acceso a datos de uso» concedido: el modo estricto también tapa las " +
                            "pantallas de Ajustes que Android oculta al bloqueo de apps."
                    )
                } else {
                    TextoAyuda(
                        "En esta versión de Android, algunas pantallas de Ajustes se ocultan al " +
                            "bloqueo de apps. Para que el modo estricto las tape también, activa " +
                            "«Acceso a datos de uso» para Bloqueador."
                    )
                    if (ajustesBloqueados) {
                        TextoAyuda(DISPONIBLE_AL_TERMINAR)
                    } else {
                        OutlinedButton(onClick = { if (!abrirAccesoUso(context)) avisar(SIN_AJUSTES) }) {
                            Text("Dar acceso a datos de uso")
                        }
                    }
                }
            }
            if (switchFijo) {
                AvisoBloqueo("No puedes desactivarlo durante un bloqueo.")
            }
        }

        TarjetaProteccion(
            activa = servicios.proteccionDesinstalacion,
            bloqueado = bloqueado,
            modoEstricto = config.modoEstricto,
            ajustesBloqueados = ajustesBloqueados,
            onActivar = { activarProteccion() },
            onQuitar = { quitarProteccion() },
        )

        Tarjeta(titulo = "Bloqueo de sitios") {
            val siempreActiva = if (servicios.vpnSiempreActiva) {
                "«VPN siempre activada» ya está activada para el Bloqueador: el bloqueo de sitios " +
                    "vuelve a funcionar solo, incluso después de reiniciar el teléfono."
            } else {
                "Activa «VPN siempre activada» para el Bloqueador: así el bloqueo de sitios vuelve " +
                    "a funcionar solo, incluso después de reiniciar el teléfono."
            }
            Text(
                "$siempreActiva No actives «Bloquear conexiones sin VPN»: te dejaría sin internet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (ajustesBloqueados) {
                TextoAyuda(DISPONIBLE_AL_TERMINAR)
            } else {
                OutlinedButton(
                    onClick = { if (!abrirAjustes(context, Settings.ACTION_VPN_SETTINGS)) avisar(SIN_AJUSTES) },
                ) {
                    Text("Abrir ajustes de VPN")
                }
            }
        }

        Tarjeta(titulo = "Cómo funciona") {
            Punto(
                "Las apps se bloquean con el servicio de accesibilidad del Bloqueador: si abres una " +
                    "app de tu lista durante un bloqueo, la tapa al instante la pantalla de bloqueo. " +
                    "La app no se cierra: queda en segundo plano."
            )
            Punto(
                "Los sitios se bloquean con una VPN local que solo revisa los nombres de los sitios " +
                    "que visitas. El resto de tu tráfico no pasa por ella, y nada se envía a " +
                    "servidores del Bloqueador."
            )
            Punto(
                "Durante un bloqueo puedes agregar sitios, apps y horarios, o extender la sesión, " +
                    "pero no quitar nada ni detener el bloqueo."
            )
            Punto(
                "Los límites diarios cuentan el tiempo que cada app o sitio está en pantalla. Para " +
                    "los sitios, el servicio lee la barra de direcciones del navegador (no guarda las " +
                    "direcciones). Al acabarse el tiempo, se tapa hasta las 00:00."
            )
            Punto(
                "Las pestañas que ya estaban abiertas pueden seguir funcionando un rato: ciérralas o " +
                    "cierra el navegador al empezar el bloqueo."
            )
            Punto(
                "En una emergencia, reinicia el teléfono en modo seguro: ahí el Bloqueador no " +
                    "funciona, y también puedes quitar la Protección contra desinstalación (en " +
                    "Ajustes, busca «administración del dispositivo» o «administradores de dispositivos»)."
            )

            Text(
                "Qué no cubre",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .semantics { heading() },
            )
            Punto(
                "Un DNS seguro personalizado en el navegador (por ejemplo, una dirección con IP) o una " +
                    "VPN integrada (como la de Opera) se saltan el filtro de sitios: para bloquearlo " +
                    "del todo, agrega el navegador a Apps."
            )
            Punto(
                "Si tu navegador usa «DNS seguro» con un proveedor conocido, durante un bloqueo puede " +
                    "que no cargue ninguna página. Desactívalo o deja que use el DNS del teléfono."
            )
            Punto(
                "Los sitios abiertos con el traductor de Google también se bloquean, pero no los que " +
                    "abras con otros proxies o archivos web, como archive.org o la caché de un buscador."
            )
            Punto(
                "El perfil de trabajo, el Espacio privado, la Carpeta segura, las apps duplicadas y " +
                    "otros usuarios no pasan por el filtro de sitios."
            )
            Punto(
                "Si pones una app bloqueada en pantalla dividida, en una vista emergente o en una " +
                    "ventana flotante, sigue a la vista y se puede usar en esa parte de la pantalla. " +
                    "Su ventana de imagen en imagen (PiP) se cierra, salvo que Android no lo permita."
            )
            Punto(
                "Al tapar una app de tu lista, la pantalla de bloqueo pausa lo que esté sonando, " +
                    "aunque sea de una app permitida."
            )
            Punto(
                "Si fuerzas la detención del Bloqueador (o lo cierra un limpiador del teléfono), " +
                    "Android apaga el bloqueo de apps hasta que lo vuelvas a activar."
            )
            Punto(
                "Sin la Protección contra desinstalación, algunos launchers (como el de Xiaomi) " +
                    "pueden desinstalar el Bloqueador incluso durante un bloqueo con modo estricto."
            )
            Punto(
                "Android permite una sola VPN a la vez: activar el filtro de sitios desconecta otra VPN " +
                    "que estés usando (como la del trabajo), y conectar otra apaga el filtro. Durante " +
                    "un bloqueo, el Bloqueador intenta volver a encenderlo."
            )
            Punto("Las notificaciones y los widgets de las apps bloqueadas siguen apareciendo.")
        }

        TextoAyuda(
            "Bloqueador · versión $version",
            Modifier
                .align(Alignment.CenterHorizontally)
                .padding(bottom = 8.dp),
        )
    }

    confirmarEstricto?.let { texto ->
        DialogoConfirmar(
            titulo = "Activar modo estricto",
            texto = texto,
            textoConfirmar = "Activar",
            onConfirmar = {
                confirmarEstricto = null
                Almacen.actualizar { it.copy(modoEstricto = true) }
            },
            onCancelar = { confirmarEstricto = null },
        )
    }
}

/**
 * Texto que Android agrega en su pantalla para activar la protección contra desinstalación.
 * Esa pantalla ya muestra la descripción del manifiesto (qué hace y qué no), así que esto dice
 * cómo quitarla.
 */
private const val EXPLICACION_PROTECCION = "Para desinstalar Bloqueador más adelante, primero quita " +
    "esta protección desde la app: pestaña Ajustes > «Quitar protección» (fuera de un bloqueo)."

/** Protección contra desinstalación: estado, explicación y el botón que corresponda. */
@Composable
private fun TarjetaProteccion(
    activa: Boolean,
    bloqueado: Boolean,
    modoEstricto: Boolean,
    ajustesBloqueados: Boolean,
    onActivar: () -> Unit,
    onQuitar: () -> Unit,
) {
    Tarjeta {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Protección contra desinstalación",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() },
            )
            Text(
                if (activa) "Activa" else "Inactiva",
                style = MaterialTheme.typography.labelLarge,
                color = if (activa) coloresEstado(rojo = false).texto else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "Si está activa, para desinstalar el Bloqueador primero hay que quitar la protección. " +
                "Con modo estricto, eso no se puede durante un bloqueo, porque esa pantalla está en " +
                "Ajustes. Así también se cubren los launchers que desinstalan por su cuenta, como el " +
                "de Xiaomi.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextoAyuda(
            "Para desinstalarlo, primero toca «Quitar protección» (fuera de un bloqueo). Usa el " +
                "permiso de administrador del dispositivo, pero sin ninguna función: no puede " +
                "bloquear el teléfono, borrar datos ni ver lo que haces."
        )
        when {
            // Quitarla es "quitar algo": durante un bloqueo no se puede.
            activa && bloqueado -> AvisoBloqueo("No puedes quitarla durante un bloqueo.")
            activa -> OutlinedButton(onClick = onQuitar) { Text("Quitar protección") }
            // La pantalla para activarla está en Ajustes, que el modo estricto bloquea.
            ajustesBloqueados -> TextoAyuda(DISPONIBLE_AL_TERMINAR)
            else -> FilledTonalButton(onClick = onActivar, colors = coloresBotonTonal()) { Text("Activar") }
        }
        if (activa && !modoEstricto) {
            TextoAyuda("Sin modo estricto, durante un bloqueo se puede quitar desde Ajustes del teléfono.")
        }
    }
}

/** versionName de la app (ej. "1.0"), o "?" si no se puede leer. */
@Suppress("DEPRECATION")
private fun versionApp(context: Context): String = try {
    val pm = context.packageManager
    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0L))
    } else {
        pm.getPackageInfo(context.packageName, 0)
    }
    info.versionName ?: "?"
} catch (e: Exception) {
    "?"
}
