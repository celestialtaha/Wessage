package com.wapp.wearmessage.presentation.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.dynamicColorScheme

@Composable
fun WessageTheme(
    glassBoostEnabled: Boolean = false,
    content: @Composable () -> Unit
) {
    val dynamicScheme = dynamicColorScheme(LocalContext.current) ?: ColorScheme()
    val glassScheme =
        if (glassBoostEnabled) {
            dynamicScheme.copy(
                primary = Color(0xFFC6EEFF),
                primaryDim = Color(0xFF7FD1F2),
                primaryContainer = Color(0xFF10253D),
                onPrimary = Color(0xFF00101A),
                onPrimaryContainer = Color(0xFFF0FAFF),
                secondary = Color(0xFFC5F5F4),
                secondaryContainer = Color(0xFF11343A),
                tertiary = Color(0xFFFFDFC8),
                tertiaryContainer = Color(0xFF38251E),
                surfaceContainerLow = Color(0xA612243B),
                surfaceContainer = Color(0xBF16304D),
                surfaceContainerHigh = Color(0xD226415F),
                background = Color(0xFF03070D),
                onBackground = Color(0xFFF1F6FF),
                outlineVariant = Color(0xFF8DA1BD),
            )
        } else {
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
        }
    MaterialTheme(
        colorScheme = glassScheme,
        content = content
    )
}
