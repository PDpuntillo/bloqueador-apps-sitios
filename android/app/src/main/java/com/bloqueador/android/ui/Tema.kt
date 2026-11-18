package com.bloqueador.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Colores del estado (los mismos que la versión de Windows). */
object ColoresEstado {
    val RojoTexto = Color(0xFFB3261E)
    val RojoFondo = Color(0xFFFBE9E7)
    val VerdeTexto = Color(0xFF1E6B34)
    val VerdeFondo = Color(0xFFE7F4EA)
    val RojoTextoOscuro = Color(0xFFF2B8B5)
    val RojoFondoOscuro = Color(0xFF5C1A15)
    val VerdeTextoOscuro = Color(0xFFA6D8B0)
    val VerdeFondoOscuro = Color(0xFF173D22)
}

// Rojo como color principal y superficies neutras (sin el tono lila por defecto de Material 3).
private val Claro = lightColorScheme(
    primary = Color(0xFFB3261E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFBE9E7),
    onPrimaryContainer = Color(0xFF410E0B),
    secondary = Color(0xFF775652),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDAD5),
    onSecondaryContainer = Color(0xFF2C1512),
    background = Color(0xFFFCFCFC),
    onBackground = Color(0xFF1C1B1B),
    surface = Color(0xFFFCFCFC),
    onSurface = Color(0xFF1C1B1B),
    surfaceVariant = Color(0xFFEDE7E6),
    onSurfaceVariant = Color(0xFF4F4544),
    outline = Color(0xFF817473),
    outlineVariant = Color(0xFFD3C3C1),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F3F2),
    surfaceContainer = Color(0xFFF2EDEC),
    surfaceContainerHigh = Color(0xFFECE7E6),
    surfaceContainerHighest = Color(0xFFE6E1E0),
)

private val Oscuro = darkColorScheme(
    primary = Color(0xFFF2B8B5),
    onPrimary = Color(0xFF601410),
    primaryContainer = Color(0xFF8C1D18),
    onPrimaryContainer = Color(0xFFF9DEDC),
    secondary = Color(0xFFE7BDB8),
    onSecondary = Color(0xFF442926),
    secondaryContainer = Color(0xFF5D3F3B),
    onSecondaryContainer = Color(0xFFFFDAD5),
    background = Color(0xFF141313),
    onBackground = Color(0xFFE6E1E0),
    surface = Color(0xFF141313),
    onSurface = Color(0xFFE6E1E0),
    surfaceVariant = Color(0xFF4F4544),
    onSurfaceVariant = Color(0xFFD3C3C1),
    outline = Color(0xFF9C8D8C),
    outlineVariant = Color(0xFF4F4544),
    surfaceContainerLowest = Color(0xFF0F0E0E),
    surfaceContainerLow = Color(0xFF1C1B1B),
    surfaceContainer = Color(0xFF201F1F),
    surfaceContainerHigh = Color(0xFF2B2929),
    surfaceContainerHighest = Color(0xFF363434),
)

@Composable
fun TemaBloqueador(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Oscuro else Claro, content = content)
}
