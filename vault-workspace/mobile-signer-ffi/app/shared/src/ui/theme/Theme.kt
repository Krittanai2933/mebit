package com.mebit.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val MebitLightColors = lightColorScheme(
    primary = MbNavy,
    onPrimary = MbWhite,
    primaryContainer = MbChip,
    onPrimaryContainer = MbNavy,
    secondary = MbSuccess,
    onSecondary = MbWhite,
    tertiary = MbEyebrow,
    onTertiary = MbWhite,
    background = MbPage,
    onBackground = MbNavyInk,
    surface = MbWhite,
    onSurface = MbNavyInk,
    surfaceVariant = MbChip,
    onSurfaceVariant = MbBody,
    outline = MbBorder,
    error = MbBtcOrange,
    onError = MbWhite,
)

private val MebitShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(28.dp),
)

@Composable
fun MebitTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MebitLightColors,
        typography = mebitTypography(),
        shapes = MebitShapes,
        content = content,
    )
}
