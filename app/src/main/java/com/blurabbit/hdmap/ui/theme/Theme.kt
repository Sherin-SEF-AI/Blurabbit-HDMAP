package com.blurabbit.hdmap.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val BlurabbitBlue = Color(0xFF2962FF)
private val BlurabbitTeal = Color(0xFF00BFA5)

private val DarkColors = darkColorScheme(
    primary = BlurabbitBlue,
    secondary = BlurabbitTeal,
)

private val LightColors = lightColorScheme(
    primary = BlurabbitBlue,
    secondary = BlurabbitTeal,
)

@Composable
fun BlurabbitTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
