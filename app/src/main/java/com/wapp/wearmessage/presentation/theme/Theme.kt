package com.wapp.wearmessage.presentation.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.dynamicColorScheme

@Composable
fun WessageTheme(
    content: @Composable () -> Unit
) {
    val dynamicScheme = dynamicColorScheme(LocalContext.current) ?: ColorScheme()
    val glassScheme =
        dynamicScheme.copy(
            primary = Color(0xFFA9E4FF),
            primaryDim = Color(0xFF5DB8E0),
            primaryContainer = Color(0xFF1A2B45),
            onPrimary = Color(0xFF001420),
            onPrimaryContainer = Color(0xFFE0F4FF),
            secondary = Color(0xFFACEBEA),
            secondaryContainer = Color(0xFF17393F),
            tertiary = Color(0xFFFFD3B8),
            tertiaryContainer = Color(0xFF3E2A22),
            surfaceContainerLow = Color(0x8C132238),
            surfaceContainer = Color(0xAA1B2E48),
            surfaceContainerHigh = Color(0xBD243650),
            background = Color(0xFF05080F),
            onBackground = Color(0xFFE6EDF8),
            outlineVariant = Color(0xFF6A7B92),
        )
    MaterialTheme(
        colorScheme = glassScheme,
        content = content
    )
}
