package dev.ipf.whitenoise.android.ui.conversation.nostr

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R

/** A ceiling, never reserved space; follow the current window and keyboard target. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun nostrEventPreviewMaximumHeight(): Dp {
    val density = LocalDensity.current
    val measured = LocalWindowInfo.current.containerSize.height
    val window =
        measured.takeIf { it > 0 } ?: with(density) {
            LocalConfiguration.current.screenHeightDp.dp
                .roundToPx()
        }
    val available =
        window - WindowInsets.statusBars.getTop(density) -
            maxOf(WindowInsets.navigationBars.getBottom(density), WindowInsets.imeAnimationTarget.getBottom(density))
    return with(density) { (available.coerceAtLeast(1) * 0.6f).toDp() }
}

/** Measures one content composition; only overflowing cards place an expansion action. */
@Composable
internal fun NostrEventCardLayout(
    maximumHeight: Dp,
    onExpand: () -> Unit,
    content: @Composable () -> Unit,
) {
    SubcomposeLayout(Modifier.fillMaxWidth()) { constraints ->
        val ceiling = minOf(maximumHeight.roundToPx().coerceAtLeast(1), constraints.maxHeight)
        val loose = constraints.copy(minHeight = 0, maxHeight = ceiling)
        val footer =
            subcompose(CardSlot.Expand) {
                TextButton(onClick = onExpand, modifier = Modifier.fillMaxWidth().testTag(NOSTR_CARD_EXPAND_TAG)) {
                    Text(stringResource(R.string.nostr_event_expand))
                }
            }.single().measure(loose)
        // This measure-pass object deliberately changes with each subcomposition.
        // It is not Compose state and never triggers a second UI pass.
        val measurement = CardMeasurement()
        val body =
            subcompose(CardSlot.Body) {
                NostrEventCardViewport(ceiling, footer.height, measurement, content)
            }.single().measure(loose)
        val height = body.height + if (measurement.overflow) footer.height else 0
        layout(body.width, constraints.constrainHeight(height)) {
            body.placeRelative(0, 0)
            if (measurement.overflow) footer.placeRelative(0, body.height)
        }
    }
}

@Composable
private fun NostrEventCardViewport(
    ceiling: Int,
    footerHeight: Int,
    measurement: CardMeasurement,
    content: @Composable () -> Unit,
) {
    Layout(
        content = { Box(Modifier.fillMaxWidth()) { content() } },
        modifier = Modifier.clipToBounds(),
    ) { children, constraints ->
        val natural = children.single().measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
        measurement.overflow = natural.height > ceiling
        val height = if (measurement.overflow) (ceiling - footerHeight).coerceAtLeast(0) else natural.height
        layout(natural.width, height) { natural.placeRelative(0, 0) }
    }
}

private class CardMeasurement(
    var overflow: Boolean = false,
)

private enum class CardSlot { Body, Expand }

internal const val NOSTR_CARD_EXPAND_TAG = "nostr-card-expand"
