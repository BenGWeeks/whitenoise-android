package dev.ipf.whitenoise.android.translation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal data class TranslationSource(
    val messageId: String,
    val text: String,
    val target: String,
    val revision: String = "",
)

internal data class TranslationRendition(
    val text: String,
    val showTranslation: Boolean = true,
)

internal val LocalConversationTranslation = staticCompositionLocalOf<ConversationTranslationSession?> { null }

/** This object belongs to one mounted account/group/runtime route, never an application singleton. */
internal class ConversationTranslationSession(
    private val scope: CoroutineScope,
    private val engine: suspend () -> TranslationEngine,
    private val ownerCurrent: () -> Boolean,
) {
    val renditions = mutableStateMapOf<TranslationSource, TranslationRendition>()
    var failure by mutableStateOf<TranslationFailure?>(null)
        private set
    private val pending = mutableStateMapOf<TranslationSource, Job>()
    private val slots = Semaphore(2)
    var epoch by mutableStateOf(0L)
        private set
    var capacityRevision by mutableStateOf(0L)
        private set
    private val renditionOrder = LinkedHashSet<TranslationSource>()

    fun request(
        source: TranslationSource,
        from: String = "",
        automatic: Boolean = false,
    ) {
        if (!ownerCurrent() ||
            source in pending ||
            source in renditions ||
            pending.size >= 16 ||
            (automatic && failure != null)
        ) {
            return
        }
        val requestGeneration = epoch
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    slots.withPermit {
                        if (requestGeneration != epoch || !ownerCurrent()) return@withPermit
                        val translated = translateWithPrivacyGate(engine(), source.text, from, source.target)
                        if (requestGeneration == epoch &&
                            ownerCurrent() &&
                            pending[source] === currentCoroutineContext().job
                        ) {
                            if (renditions.size >= 64) {
                                renditionOrder.firstOrNull()?.let { oldest ->
                                    renditionOrder.remove(oldest)
                                    renditions.remove(oldest)
                                }
                            }
                            renditions[source] = TranslationRendition(translated)
                            renditionOrder.add(source)
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: TranslationException) {
                    if (requestGeneration == epoch &&
                        ownerCurrent() &&
                        pending[source] === currentCoroutineContext().job
                    ) {
                        failure = error.failure
                    }
                } catch (_: Exception) {
                    if (requestGeneration == epoch &&
                        ownerCurrent() &&
                        pending[source] === currentCoroutineContext().job
                    ) {
                        failure = TranslationFailure.CONNECTION
                    }
                } finally {
                    if (pending[source] === currentCoroutineContext().job) {
                        pending.remove(source)
                        capacityRevision += 1L
                    }
                }
            }
        pending[source] = job
        job.start()
    }

    fun isCurrent(): Boolean = ownerCurrent()

    fun isPending(source: TranslationSource): Boolean = source in pending

    fun remove(source: TranslationSource) {
        pending.remove(source)?.cancel()
        renditions.remove(source)
        renditionOrder.remove(source)
        capacityRevision += 1L
    }

    fun toggle(source: TranslationSource) {
        renditions[source]?.let { renditions[source] = it.copy(showTranslation = !it.showTranslation) }
    }

    fun retry() {
        failure = null
    }

    fun clear() {
        epoch += 1
        pending.values.toList().forEach(Job::cancel)
        pending.clear()
        renditions.clear()
        renditionOrder.clear()
        failure = null
    }
}
