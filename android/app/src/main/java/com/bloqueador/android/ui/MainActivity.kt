package com.bloqueador.android.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import com.bloqueador.android.datos.Almacen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDateTime

class MainActivity : ComponentActivity() {

    /** True mientras la app está a la vista: solo entonces corre el reloj de la pantalla. */
    private val visible = mutableStateOf(false)

    /** Aumenta cada vez que la app vuelve a primer plano, para releer al instante los servicios. */
    private val reanudaciones = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TemaBloqueador {
                AppBloqueador(visible = visible.value, reanudaciones = reanudaciones.intValue)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        visible.value = true
    }

    override fun onResume() {
        super.onResume()
        // Quizá vuelve de Ajustes tras activar el bloqueo de apps: se le da a Android unos
        // segundos para conectarlo antes de decir que no responde.
        reiniciarEsperaAccesibilidad()
        // Al abrir la app o volver a ella: si ya hay permiso de VPN pero el filtro no corre, se arranca.
        reafirmarVpn(this)
        reanudaciones.intValue += 1
    }

    override fun onStop() {
        visible.value = false
        super.onStop()
    }
}

/** Las seis pestañas de la barra inferior. */
private enum class Destino(val etiqueta: String, val titulo: String, val icono: ImageVector) {
    INICIO("Inicio", "Bloqueador", Icons.Filled.Home),
    APPS("Apps", "Aplicaciones", Icons.Filled.Lock),
    SITIOS("Sitios", "Sitios web", Icons.AutoMirrored.Filled.List),
    LIMITES("Límites", "Límites diarios", IconoLimite),
    HORARIOS("Horarios", "Horarios", Icons.Filled.DateRange),
    AJUSTES("Ajustes", "Ajustes", Icons.Filled.Settings),
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AppBloqueador(visible: Boolean, reanudaciones: Int) {
    val context = LocalContext.current
    var destino by rememberSaveable { mutableStateOf(Destino.INICIO) }
    // Guarda lo de cada pestaña (formularios, búsqueda, scroll) al pasar a otra y al rotar.
    val estadoPestanas = rememberSaveableStateHolder()
    var config by remember { mutableStateOf(Almacen.config) }
    var ahora by remember { mutableStateOf(LocalDateTime.now()) }
    var servicios by remember { mutableStateOf(leerServicios(context)) }
    val catalogo = remember { CatalogoApps() }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Muestra un aviso breve abajo (reemplaza al anterior si había uno).
    val avisar = remember<(String) -> Unit>(snackbar, scope) {
        { mensaje ->
            scope.launch {
                snackbar.currentSnackbarData?.dismiss()
                snackbar.showSnackbar(mensaje)
            }
        }
    }

    // Escucha los cambios de la configuración. Pueden llegar desde otros hilos (los servicios):
    // en ese caso se pasan al hilo principal.
    DisposableEffect(Unit) {
        val principal = Handler(Looper.getMainLooper())
        val darDeBaja = Almacen.escuchar { nueva ->
            if (Looper.myLooper() == Looper.getMainLooper()) {
                config = nueva
            } else {
                principal.post { config = Almacen.config }
            }
        }
        config = Almacen.config
        onDispose {
            darDeBaja()
            principal.removeCallbacksAndMessages(null)
        }
    }

    // Reloj de 1 s: cuenta regresiva y estado de los servicios (solo con la app a la vista).
    LaunchedEffect(visible, reanudaciones) {
        if (!visible) return@LaunchedEffect
        while (true) {
            ahora = LocalDateTime.now()
            servicios = leerServicios(context)
            val guardada = Almacen.config
            if (guardada !== config) {
                config = guardada
            }
            // Espera hasta el próximo segundo exacto para que la cuenta no salte números.
            delay(1_000L - System.currentTimeMillis() % 1_000L)
        }
    }

    val estado = config.estado(ahora)

    // "Atrás" en otra pestaña vuelve a Inicio antes de salir.
    BackHandler(enabled = destino != Destino.INICIO) { destino = Destino.INICIO }

    Scaffold(
        topBar = { TopAppBar(title = { Text(destino.titulo) }) },
        bottomBar = {
            NavigationBar {
                Destino.entries.forEach { d ->
                    NavigationBarItem(
                        selected = d == destino,
                        onClick = { destino = d },
                        icon = { Icon(d.icono, contentDescription = null) },
                        // En una línea: con letra grande se corta con "…" en vez de partir la palabra.
                        label = {
                            Text(d.etiqueta, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                        },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { relleno ->
        val contenido = Modifier
            .fillMaxSize()
            .padding(relleno)
            .consumeWindowInsets(relleno)
            .imePadding()
        estadoPestanas.SaveableStateProvider(destino.name) {
            when (destino) {
                Destino.INICIO -> PantallaInicio(
                    config = config,
                    estado = estado,
                    ahora = ahora,
                    servicios = servicios,
                    avisar = avisar,
                    refrescarServicios = { servicios = leerServicios(context) },
                    modifier = contenido,
                )
                Destino.APPS -> PantallaApps(
                    config = config,
                    bloqueado = estado.activo,
                    catalogo = catalogo,
                    reanudaciones = reanudaciones,
                    avisar = avisar,
                    modifier = contenido,
                )
                Destino.SITIOS -> PantallaSitios(
                    config = config,
                    bloqueado = estado.activo,
                    avisar = avisar,
                    modifier = contenido,
                )
                Destino.LIMITES -> PantallaLimites(
                    config = config,
                    bloqueado = estado.activo,
                    ahora = ahora,
                    servicios = servicios,
                    catalogo = catalogo,
                    reanudaciones = reanudaciones,
                    avisar = avisar,
                    modifier = contenido,
                )
                Destino.HORARIOS -> PantallaHorarios(
                    config = config,
                    // Si los horarios cubren toda la semana (configuración vieja), el bloqueo no
                    // terminaría nunca: se deja quitar horarios (quitarSiLibre lo permite).
                    bloqueado = estado.activo && !estado.permanente,
                    ahora = ahora,
                    avisar = avisar,
                    modifier = contenido,
                )
                Destino.AJUSTES -> PantallaAjustes(
                    config = config,
                    bloqueado = estado.activo,
                    servicios = servicios,
                    avisar = avisar,
                    modifier = contenido,
                )
            }
        }
    }
}
