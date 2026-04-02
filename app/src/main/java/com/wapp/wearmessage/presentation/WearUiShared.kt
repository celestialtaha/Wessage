package com.wapp.wearmessage.presentation

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.ButtonGroup
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.CompactButton
import androidx.wear.compose.material3.Icon

internal fun performActionHapticIfEnabled(
    enabled: Boolean,
    hapticFeedback: HapticFeedback,
) {
    if (enabled) {
        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
    }
}

internal fun Modifier.cardWidth(layoutSpec: WearLayoutSpec): Modifier =
    fillMaxWidth(layoutSpec.cardWidthFraction)

@Composable
internal fun LoadingIndicatorRow(indicatorSize: Dp) {
    Box(
        modifier = Modifier.fillMaxWidth().height(72.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(indicatorSize))
    }
}

@Composable
internal fun ActionIconButtonGroup(
    modifier: Modifier = Modifier,
    enabled: Boolean,
    minButtonWidth: Dp,
    leadingIcon: ImageVector,
    leadingDescription: String,
    onLeadingClick: () -> Unit,
    trailingIcon: ImageVector,
    trailingDescription: String,
    onTrailingClick: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val leadingSource = remember { MutableInteractionSource() }
    val trailingSource = remember { MutableInteractionSource() }
    ButtonGroup(
        modifier = modifier,
        contentPadding = contentPadding,
    ) {
        CompactButton(
            onClick = onLeadingClick,
            enabled = enabled,
            interactionSource = leadingSource,
            modifier =
                Modifier
                    .weight(1f)
                    .minWidth(minButtonWidth)
                    .animateWidth(leadingSource),
            icon = {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = leadingDescription,
                )
            },
        )
        CompactButton(
            onClick = onTrailingClick,
            enabled = enabled,
            interactionSource = trailingSource,
            modifier =
                Modifier
                    .weight(1f)
                    .minWidth(minButtonWidth)
                    .animateWidth(trailingSource),
            icon = {
                Icon(
                    imageVector = trailingIcon,
                    contentDescription = trailingDescription,
                )
            },
        )
    }
}
