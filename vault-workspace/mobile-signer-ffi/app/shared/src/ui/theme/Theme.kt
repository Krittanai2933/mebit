package com.mebit.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val MebitLightColors = lightColorScheme(
    primary = MbTeal,
    onPrimary = MbWhite,
    primaryContainer = MbMintTint,
    onPrimaryContainer = MbTealDeep,
    secondary = MbGreen,
    onSecondary = MbWhite,
    tertiary = MbYellow,
    onTertiary = MbInk,
    background = MbMintTint,
    onBackground = MbInk,
    surface = MbWhite,
    onSurface = MbInk,
    surfaceVariant = MbGray100,
    onSurfaceVariant = MbGray700,
    outline = MbGray200,
    error = MbOrange,
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
        typography = MebitTypography,
        shapes = MebitShapes,
        content = content,
    )
}
