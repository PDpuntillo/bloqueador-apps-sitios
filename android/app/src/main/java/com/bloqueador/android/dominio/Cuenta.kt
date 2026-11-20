package com.bloqueador.android.dominio

import org.json.JSONObject

// La cuenta regresiva flotante: cuánto le queda hoy a la app o al sitio con límite que está a la vista.

enum class TamanoCuenta(val clave: String, val nombre: String) {
    CHICO("chico", "Chico"),
    MEDIANO("mediano", "Mediano"),
    GRANDE("grande", "Grande"),
}

/** Siempre una esquina de la pantalla (izquierda y derecha son las de verdad, no según el idioma). */
enum class PosicionCuenta(
    val clave: String,
    val nombre: String,
    val corto: String,
    val arriba: Boolean,
    val izquierda: Boolean,
) {
    ARRIBA_IZQ("arriba_izq", "Arriba a la izquierda", "Arriba izq.", arriba = true, izquierda = true),
    ARRIBA_DER("arriba_der", "Arriba a la derecha", "Arriba der.", arriba = true, izquierda = false),
    ABAJO_IZQ("abajo_izq", "Abajo a la izquierda", "Abajo izq.", arriba = false, izquierda = true),
    ABAJO_DER("abajo_der", "Abajo a la derecha", "Abajo der.", arriba = false, izquierda = false),
}

data class AjustesCuenta(
    val visible: Boolean = true,
    val tamano: TamanoCuenta = TamanoCuenta.MEDIANO,
    val posicion: PosicionCuenta = PosicionCuenta.ARRIBA_DER,
) {
    fun aJson(): JSONObject =
        JSONObject().put("visible", visible).put("tamano", tamano.clave).put("posicion", posicion.clave)

    companion object {
        /** Lo que falte o no valga queda como de fábrica. */
        fun desdeJson(o: JSONObject?): AjustesCuenta {
            if (o == null) return AjustesCuenta()
            val base = AjustesCuenta()
            return AjustesCuenta(
                visible = o.optBoolean("visible", base.visible),
                tamano = TamanoCuenta.entries.firstOrNull { it.clave == o.optString("tamano") } ?: base.tamano,
                posicion = PosicionCuenta.entries.firstOrNull { it.clave == o.optString("posicion") } ?: base.posicion,
            )
        }
    }
}

/** Una línea de la cuenta: qué es y cuánto le queda hoy. */
data class LineaCuenta(val nombre: String, val ms: Long)

/** "12:34", "1:02:03" o "0:05". Redondea hacia arriba: muestra 0:01 hasta el último segundo. */
fun textoRestante(ms: Long): String {
    val s = (maxOf(0L, ms) + 999L) / 1000L
    val h = s / 3600
    val m = s % 3600 / 60
    val seg = (s % 60).toString().padStart(2, '0')
    return if (h > 0) "$h:${m.toString().padStart(2, '0')}:$seg" else "$m:$seg"
}

/** "instagram.com   12:34", una línea por límite. */
fun textoCuenta(lineas: List<LineaCuenta>): String =
    lineas.joinToString("\n") { "${it.nombre}   ${textoRestante(it.ms)}" }

/** True si a alguno le quedan 5 minutos o menos (la cuenta se pone roja). */
fun enAviso(lineas: List<LineaCuenta>): Boolean = lineas.any { it.ms <= AVISO_ANTES_MS }

/**
 * Lo que le queda hoy a cada límite de [enPantalla], el más urgente primero. [pendienteMs] es el
 * rato desde la última vez que se sumó el uso, que todavía no está en [uso]: así la cuenta baja
 * cada segundo aunque el uso se sume cada pocos segundos.
 */
fun restantesEnPantalla(enPantalla: List<Limite>, uso: UsoDiario, pendienteMs: Long): List<Pair<Limite, Long>> =
    enPantalla.map { it to maxOf(0L, uso.restantesMs(it) - maxOf(0L, pendienteMs)) }.sortedBy { it.second }
