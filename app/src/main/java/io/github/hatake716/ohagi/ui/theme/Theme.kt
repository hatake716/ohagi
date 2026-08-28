package io.github.hatake716.ohagi.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// おはぎ由来のパレット: 小豆・きなこ・白米
val Azuki = Color(0xFFC96F7B)
val AzukiDeep = Color(0xFF8A3B4A)
val Kinako = Color(0xFFD9B382)
val Kome = Color(0xFFF5EFE6)
val Ink = Color(0xFF17121A)
val InkSoft = Color(0xFF241D2B)

/** 壁紙の上に重ねる半透明パネル色 */
val PanelScrim = Color(0xCC17121A)
val PanelScrimLight = Color(0x991F1826)
val TileBorder = Color(0x33FFFFFF)

private val OhagiColorScheme = darkColorScheme(
    primary = Azuki,
    onPrimary = Kome,
    primaryContainer = AzukiDeep,
    onPrimaryContainer = Kome,
    secondary = Kinako,
    onSecondary = Ink,
    background = Color.Transparent,
    onBackground = Kome,
    surface = InkSoft,
    onSurface = Kome,
    surfaceVariant = InkSoft,
    onSurfaceVariant = Color(0xFFCDC3D2),
    surfaceContainer = InkSoft,
    surfaceContainerHigh = Color(0xFF2C2434),
    surfaceContainerHighest = Color(0xFF352C3E),
    surfaceContainerLow = Ink,
    surfaceContainerLowest = Ink,
    outline = Color(0x66FFFFFF),
    error = Color(0xFFFFB4AB),
)

private val OhagiShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

@Composable
fun OhagiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = OhagiColorScheme,
        shapes = OhagiShapes,
        content = content,
    )
}
