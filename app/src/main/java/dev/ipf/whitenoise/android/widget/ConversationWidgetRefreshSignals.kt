package dev.ipf.whitenoise.android.widget

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest

/** A mounted observer compares privacy boundaries independently of changing unread/chat rows. */
internal data class WidgetRefreshSignal(
    val privacy: List<Any?>,
    val content: List<Any?>,
)

/** Privacy changes redact immediately; ordinary row bursts replace the last displayed rows once settled. */
internal suspend fun collectWidgetRefreshes(
    signals: Flow<WidgetRefreshSignal>,
    refresh: suspend (redactFirst: Boolean) -> Unit,
) {
    var previousPrivacy: List<Any?>? = null
    signals.collectLatest { signal ->
        val privacyChanged = signal.privacy != previousPrivacy
        previousPrivacy = signal.privacy
        if (!privacyChanged) delay(WIDGET_REFRESH_SETTLE_MS)
        refresh(privacyChanged)
    }
}

private const val WIDGET_REFRESH_SETTLE_MS = 250L
