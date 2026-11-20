package com.bloqueador.android.bloqueo

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import com.bloqueador.android.datos.Almacen
import com.bloqueador.android.datos.AlmacenUso
import com.bloqueador.android.dominio.Limite
import com.bloqueador.android.dominio.Paquetes
import com.bloqueador.android.dominio.TipoLimite
import com.bloqueador.android.dominio.cuando
import com.bloqueador.android.dominio.duracion
import com.bloqueador.android.dominio.etiquetaMinutos
import com.bloqueador.android.ui.ColoresEstado
import com.bloqueador.android.ui.TemaBloqueador
import kotlinx.coroutines.delay
import java.time.LocalDateTime

/**
 * Pantalla que tapa una app bloqueada, o una app o un sitio cuyo tiempo de hoy se acabó. La abre
 * [ServicioAccesibilidad] directamente encima de la app, sin pulsar inicio antes.
 *
 * En el manifiesto es singleTask + noHistory y va en su propia tarea: si ya está abierta y
 * se bloquea otra app, llega por [onNewIntent] y solo se actualizan los datos.
 */
class PantallaBloqueoActivity : ComponentActivity() {

    // Estado de Compose: al cambiar (onNewIntent) la pantalla se redibuja sola.
    private var nombre by mutableStateOf("")
    private var paquete by mutableStateOf("")

    // Si tapa algo cuyo tiempo de hoy se acabó: su límite. Con un sitio, [paquete] es el navegador.
    private var limite by mutableStateOf<Limite?>(null)

    // App por la que ya se pausó el audio mientras esta pantalla está abierta.
    private var audioPausadoPara: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Al recrearse (por ejemplo, al girar el teléfono) no se vuelve a pausar el audio.
        audioPausadoPara = savedInstanceState?.getString(CLAVE_AUDIO_PAUSADO)
        enableEdgeToEdge()
        ponerFondoVentana()
        leer(intent)

        // Atrás hace lo mismo que el botón.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                salir(vigente())
            }
        })

        setContent {
            TemaBloqueador {
                PantallaBloqueo(
                    nombre = nombre,
                    paquete = paquete,
                    limite = limite,
                    alPulsar = { activo -> salir(activo) },
                    alAbrirOtraPagina = { abrirOtraPagina() },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Servicios.pantallaBloqueoEnFrente = true
        alMostrarse()
    }

    override fun onPause() {
        Servicios.pantallaBloqueoEnFrente = false
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(CLAVE_AUDIO_PAUSADO, audioPausadoPara)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        leer(intent)
        // Si ya estaba en primer plano, Android puede entregar el aviso sin volver a pasar por onResume.
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) alMostrarse()
    }

    private fun leer(intent: Intent?) {
        paquete = intent?.getStringExtra(EXTRA_PAQUETE).orEmpty()
        nombre = intent?.getStringExtra(EXTRA_NOMBRE)?.trim()?.takeIf { it.isNotEmpty() } ?: paquete
        val tipo = TipoLimite.entries.firstOrNull { it.clave == intent?.getStringExtra(EXTRA_LIMITE_TIPO) }
        val nombreLimite = intent?.getStringExtra(EXTRA_LIMITE_NOMBRE).orEmpty()
        val minutos = intent?.getIntExtra(EXTRA_LIMITE_MINUTOS, 0) ?: 0
        limite = if (tipo != null && nombreLimite.isNotEmpty() && minutos > 0) Limite(tipo, nombreLimite, minutos) else null
    }

    /** True si lo que tapa sigue bloqueado: su tiempo de hoy sigue agotado, o sigue el bloqueo. */
    private fun vigente(): Boolean {
        val l = limite
        return if (l != null) AlmacenUso.hoy().agotado(l) else Almacen.config.estado().activo
    }

    /**
     * Con un sitio sin tiempo: abre una pestaña nueva en el mismo navegador, para que puedas
     * seguir usándolo con otras páginas. La del sitio queda atrás y se vuelve a tapar si vuelves.
     */
    private fun abrirOtraPagina() {
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(PAGINA_NUEVA))
                    .setPackage(paquete)
                    .addCategory(Intent.CATEGORY_BROWSABLE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            finish()
        } catch (e: Exception) {
            salir(true)
        }
    }

    /** Avisa al servicio de que la pantalla se ve y pausa lo que suene de la app bloqueada. */
    private fun alMostrarse() {
        Servicios.pantallaBloqueoVista = SystemClock.elapsedRealtime()
        if (Servicios.pantallaNoAparece(this)) Servicios.marcarPantallaNoAparece(this, false)
        // Una sola vez por app mientras la pantalla está abierta: si se pausa y se reanuda (por
        // ejemplo, al llegar un onNewIntent), no vuelve a cortar lo que pongas en una app permitida.
        // Solo con las apps de tu lista (no con Ajustes ni las tiendas del modo estricto).
        if (paquete != audioPausadoPara && (paquete in Almacen.config.apps || limite != null)) {
            audioPausadoPara = paquete
            pausarAudio()
        }
    }

    /**
     * Pausa la música o el video que esté sonando: al tomar el foco de audio, el reproductor
     * recibe AUDIOFOCUS_LOSS y se pausa. El foco se suelta enseguida. Android no dice qué app
     * suena, así que también pausa la de una app permitida.
     */
    private fun pausarAudio() {
        try {
            val audio = getSystemService(AudioManager::class.java) ?: return
            if (!audio.isMusicActive) return
            val pedido = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setOnAudioFocusChangeListener { }
                .build()
            audio.requestAudioFocus(pedido)
            audio.abandonAudioFocusRequest(pedido)
        } catch (e: Exception) {
            // Solo es una ayuda: si falla, el audio sigue.
        }
    }

    /** Con bloqueo activo abre la pantalla de inicio; si ya terminó, solo cierra. */
    private fun salir(activo: Boolean) {
        if (activo) {
            try {
                startActivity(
                    Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_HOME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: Exception) {
                // Sin launcher disponible: igual se cierra la pantalla.
            }
        }
        finish()
    }

    /** Fondo de la ventana del mismo color que la pantalla, para que no haya un destello blanco. */
    private fun ponerFondoVentana() {
        val oscuro = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val color = if (oscuro) ColoresEstado.RojoFondoOscuro else ColoresEstado.RojoFondo
        try {
            window.setBackgroundDrawable(ColorDrawable(color.toArgb()))
        } catch (e: Exception) {
            // Solo es estético.
        }
    }

    companion object {
        const val EXTRA_NOMBRE = "com.bloqueador.android.extra.NOMBRE"
        const val EXTRA_PAQUETE = "com.bloqueador.android.extra.PAQUETE"
        private const val EXTRA_LIMITE_TIPO = "com.bloqueador.android.extra.LIMITE_TIPO"
        private const val EXTRA_LIMITE_NOMBRE = "com.bloqueador.android.extra.LIMITE_NOMBRE"
        private const val EXTRA_LIMITE_MINUTOS = "com.bloqueador.android.extra.LIMITE_MINUTOS"
        private const val CLAVE_AUDIO_PAUSADO = "audio_pausado_para"

        /** Lo que abre «Abrir otra página» en el navegador de un sitio sin tiempo. */
        private const val PAGINA_NUEVA = "https://www.google.com/"

        /**
         * Intent para abrir esta pantalla desde un servicio. FLAG_ACTIVITY_NO_USER_ACTION evita
         * que la app tapada lo tome como "el usuario se va" y pase sola a PiP.
         */
        fun crearIntent(context: Context, nombre: String, paquete: String, limite: Limite? = null): Intent =
            Intent(context, PantallaBloqueoActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
                .putExtra(EXTRA_NOMBRE, nombre)
                .putExtra(EXTRA_PAQUETE, paquete)
                .putExtra(EXTRA_LIMITE_TIPO, limite?.tipo?.clave)
                .putExtra(EXTRA_LIMITE_NOMBRE, limite?.nombre)
                .putExtra(EXTRA_LIMITE_MINUTOS, limite?.minutos ?: 0)
    }
}

@Composable
private fun PantallaBloqueo(
    nombre: String,
    paquete: String,
    limite: Limite?,
    alPulsar: (activo: Boolean) -> Unit,
    alAbrirOtraPagina: () -> Unit,
) {
    var ahora by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            // Se despierta justo después de cada cambio de segundo para que la cuenta no salte.
            delay(1_000L - System.currentTimeMillis() % 1_000L + 10L)
            ahora = LocalDateTime.now()
        }
    }

    // Se lee en cada segundo: si la sesión se extiende, la pantalla lo muestra al instante.
    val config = Almacen.config
    val estado = config.estado(ahora)
    // Con un límite, lo bloqueado se libera a las 00:00 (con el uso del día nuevo).
    val activo = if (limite != null) AlmacenUso.hoy().agotado(limite) else estado.activo
    // Sin hora de fin si los horarios cubren toda la semana (estado.permanente).
    val hasta = if (limite != null) {
        ahora.toLocalDate().plusDays(1).atStartOfDay().takeIf { activo }
    } else {
        estado.hasta?.takeIf { activo }
    }

    val oscuro = isSystemInDarkTheme()
    val fondo = when {
        activo && oscuro -> ColoresEstado.RojoFondoOscuro
        activo -> ColoresEstado.RojoFondo
        oscuro -> ColoresEstado.VerdeFondoOscuro
        else -> ColoresEstado.VerdeFondo
    }
    val acento = when {
        activo && oscuro -> ColoresEstado.RojoTextoOscuro
        activo -> ColoresEstado.RojoTexto
        oscuro -> ColoresEstado.VerdeTextoOscuro
        else -> ColoresEstado.VerdeTexto
    }
    val texto = MaterialTheme.colorScheme.onSurface

    // Lo que no está en tu lista se bloquea por el modo estricto.
    val titulo = when {
        limite != null && !activo -> "Ya puedes volver a usar $nombre"
        limite != null -> "Se acabó el tiempo de hoy de $nombre"
        !activo -> "El bloqueo terminó"
        paquete.isEmpty() || paquete in config.apps -> "${nombre.ifEmpty { "Esta app" }} está bloqueada"
        paquete == Paquetes.DIALOGO_VPN -> "Los ajustes de VPN están bloqueados mientras dure el bloqueo (modo estricto)"
        paquete.endsWith("packageinstaller") ->
            "Instalar o desinstalar apps está bloqueado mientras dure el bloqueo (modo estricto)"
        paquete in Paquetes.TIENDAS -> "Las tiendas de apps están bloqueadas mientras dure el bloqueo (modo estricto)"
        else -> "Ajustes está bloqueado mientras dure el bloqueo (modo estricto)"
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(fondo)
            .safeDrawingPadding(),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .widthIn(max = 480.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = if (activo) Icons.Filled.Lock else Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = acento,
                modifier = Modifier.size(72.dp),
            )
            Spacer(Modifier.height(24.dp))
            Text(
                text = titulo,
                modifier = Modifier.semantics { heading() },
                color = acento,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            if (limite != null && activo) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "Usaste los ${etiquetaMinutos(limite.minutos)} que tienes por día.",
                    color = texto,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
            }
            if (hasta != null) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = if (limite != null) "Vuelve a estar disponible ${cuando(hasta, ahora)}"
                    else "El bloqueo termina ${cuando(hasta, ahora)}",
                    color = texto,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Faltan ${duracion(ahora, hasta)}",
                    color = texto,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                )
            } else if (activo && estado.permanente) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "Sin fin: tus horarios cubren toda la semana",
                    color = texto,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(32.dp))
            if (activo && limite?.tipo == TipoLimite.SITIO) {
                // El navegador sigue disponible para otras páginas.
                Button(
                    onClick = alAbrirOtraPagina,
                    colors = ButtonDefaults.buttonColors(containerColor = acento, contentColor = fondo),
                ) {
                    Text("Abrir otra página")
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { alPulsar(true) }) {
                    Text("Volver al inicio", color = acento)
                }
            } else {
                Button(
                    onClick = { alPulsar(activo) },
                    colors = ButtonDefaults.buttonColors(containerColor = acento, contentColor = fondo),
                ) {
                    Text(if (activo) "Volver al inicio" else "Cerrar")
                }
            }
        }
    }
}
