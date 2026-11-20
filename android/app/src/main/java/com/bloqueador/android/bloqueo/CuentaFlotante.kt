package com.bloqueador.android.bloqueo

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.TextView
import com.bloqueador.android.dominio.AjustesCuenta
import com.bloqueador.android.dominio.LineaCuenta
import com.bloqueador.android.dominio.TamanoCuenta
import com.bloqueador.android.dominio.enAviso
import com.bloqueador.android.dominio.textoCuenta
import kotlin.math.roundToInt

/**
 * La cuenta regresiva flotante, en una esquina de la pantalla. Es una ventana del servicio de
 * accesibilidad (no hace falta el permiso de mostrar sobre otras apps) que no recibe el foco ni
 * los toques: pasan a la app de abajo. Solo se usa desde el hilo principal.
 */
internal class CuentaFlotante(private val servicio: AccessibilityService) {

    private val ventanas: WindowManager? = servicio.getSystemService(WindowManager::class.java)
    private var vista: TextView? = null
    private var lugar: Triple<Int, Int, Int>? = null      // gravedad, x, y de la ventana agregada
    private var estilo: Pair<TamanoCuenta, Boolean>? = null

    val visible: Boolean get() = vista != null

    fun mostrar(lineas: List<LineaCuenta>, ajustes: AjustesCuenta) {
        val wm = ventanas ?: return
        val nueva = vista == null
        val v = vista ?: crearVista()
        val aviso = enAviso(lineas)
        if (estilo != (ajustes.tamano to aviso)) {
            darEstilo(v, ajustes.tamano, aviso)
            estilo = ajustes.tamano to aviso
        }
        val texto = textoCuenta(lineas)
        if (v.text.toString() != texto) v.text = texto
        val p = parametros(ajustes)
        val donde = Triple(p.gravity, p.x, p.y)
        try {
            if (nueva) {
                wm.addView(v, p)
                vista = v
                lugar = donde
            } else if (donde != lugar) {
                wm.updateViewLayout(v, p)
                lugar = donde
            }
        } catch (e: Exception) {
            // Por ejemplo, si el servicio se acaba de desconectar: se reintenta en la próxima.
            if (nueva) estilo = null
        }
    }

    fun ocultar() {
        val v = vista ?: return
        vista = null
        lugar = null
        estilo = null
        try {
            ventanas?.removeViewImmediate(v)
        } catch (e: Exception) {
            // Ya no estaba.
        }
    }

    private fun crearVista(): TextView = TextView(servicio).apply {
        setTextColor(Color.WHITE)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        fontFeatureSettings = "tnum"   // cifras del mismo ancho: el cuadro no cambia de tamaño cada segundo
    }

    private fun darEstilo(v: TextView, tamano: TamanoCuenta, aviso: Boolean) {
        val sp = when (tamano) {
            TamanoCuenta.CHICO -> 13f
            TamanoCuenta.MEDIANO -> 17f
            TamanoCuenta.GRANDE -> 24f
        }
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        val horizontal = dp(sp * 0.7f)
        val vertical = dp(sp * 0.35f)
        v.setPadding(horizontal, vertical, horizontal, vertical)
        v.background = GradientDrawable().apply {
            cornerRadius = dp(sp * 0.6f).toFloat()
            setColor(if (aviso) FONDO_AVISO else FONDO)
        }
    }

    /** Las esquinas son las de la pantalla, no las del idioma: por eso LEFT y RIGHT. */
    @SuppressLint("RtlHardcoded")
    private fun parametros(ajustes: AjustesCuenta): WindowManager.LayoutParams {
        val sobreBarras = Build.VERSION.SDK_INT < Build.VERSION_CODES.R
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                (if (sobreBarras) WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN else 0),
            PixelFormat.TRANSLUCENT,
        )
        val pos = ajustes.posicion
        p.gravity = (if (pos.arriba) Gravity.TOP else Gravity.BOTTOM) or (if (pos.izquierda) Gravity.LEFT else Gravity.RIGHT)
        p.x = dp(MARGEN_DP)
        p.y = dp(MARGEN_DP)
        if (sobreBarras) {
            // Hasta Android 10 no se puede pedir que esquive las barras: se dejan las medidas habituales
            // (barra de estado de 24 dp y de navegación de 48 dp).
            p.y += dp(if (pos.arriba) 24f else 48f)
        } else {
            // Que quede dentro de la pantalla útil, sin tapar la barra de estado, la de navegación
            // ni el recorte de la cámara, aunque estén ocultas (videos y juegos a pantalla completa).
            p.fitInsetsTypes = WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            p.isFitInsetsIgnoringVisibility = true
        }
        p.title = "Bloqueador: cuenta regresiva"
        return p
    }

    private fun dp(valor: Float): Int = (valor * servicio.resources.displayMetrics.density).roundToInt()

    private companion object {
        const val MARGEN_DP = 8f
        const val FONDO = 0xE6202124.toInt()        // gris oscuro, casi opaco
        const val FONDO_AVISO = 0xF0B3261E.toInt()  // rojo: quedan 5 minutos o menos
    }
}
