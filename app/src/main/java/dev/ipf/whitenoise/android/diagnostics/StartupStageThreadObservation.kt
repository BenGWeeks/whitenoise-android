package dev.ipf.whitenoise.android.diagnostics

import kotlinx.coroutines.ThreadContextElement
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** A unique key per stage keeps parent and nested observations attached across dispatcher changes. */
internal class StartupStageThreadObservation(
    private val observeResume: () -> Unit,
) : AbstractCoroutineContextElement(Key()),
    ThreadContextElement<Unit> {
    private class Key : CoroutineContext.Key<StartupStageThreadObservation>

    /** Updates only the bounded recorder entry; failures never escape coroutine scheduling machinery. */
    override fun updateThreadContext(context: CoroutineContext) {
        runCatching(observeResume)
    }

    /** A late restore must not replace the thread from a newer resume; no thread-local state is changed. */
    override fun restoreThreadContext(
        context: CoroutineContext,
        oldState: Unit,
    ) = Unit
}
