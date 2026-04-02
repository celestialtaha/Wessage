package com.wapp.wearmessage.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal data class WearLayoutSpec(
    val compact: Boolean,
    val round: Boolean,
    val cardWidthFraction: Float,
    val topActionRowWidthFraction: Float,
    val edgeActionRowWidthFraction: Float,
    val messageBubbleWidthFraction: Float,
    val messageBubbleHorizontalInset: Dp,
    val actionButtonMinWidth: Dp,
    val edgeButtonSpacing: Dp,
    val emptyHomeTopSpacer: Dp,
)

@Composable
internal fun rememberWearLayoutSpec(): WearLayoutSpec {
    val configuration = LocalConfiguration.current
    val minScreenDp = minOf(configuration.screenWidthDp, configuration.screenHeightDp)
    val compact = minScreenDp <= 220
    val round = configuration.isScreenRound
    return remember(configuration.screenWidthDp, configuration.screenHeightDp, round) {
        WearLayoutSpec(
            compact = compact,
            round = round,
            cardWidthFraction =
                when {
                    round && compact -> 0.9f
                    round -> 0.93f
                    else -> 1f
                },
            topActionRowWidthFraction =
                when {
                    round && compact -> 0.88f
                    round -> 0.92f
                    else -> 1f
                },
            edgeActionRowWidthFraction =
                when {
                    round && compact -> 0.9f
                    round -> 0.94f
                    else -> 1f
                },
            messageBubbleWidthFraction =
                when {
                    round && compact -> 0.66f
                    round -> 0.7f
                    compact -> 0.74f
                    else -> 0.78f
                },
            messageBubbleHorizontalInset =
                when {
                    round && compact -> 3.dp
                    round -> 2.dp
                    else -> 0.dp
                },
            actionButtonMinWidth = if (compact) 48.dp else 52.dp,
            edgeButtonSpacing = if (round && compact) 12.dp else 16.dp,
            emptyHomeTopSpacer = if (round && compact) 18.dp else 26.dp,
        )
    }
}
