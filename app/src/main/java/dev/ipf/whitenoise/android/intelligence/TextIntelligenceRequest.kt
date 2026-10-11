package dev.ipf.whitenoise.android.intelligence

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** One disposable preview owner. It has no send callback and no persistent storage. */
internal class TextIntelligenceRequest(
    private val scope: CoroutineScope,
    private val ownerCurrent: () -> Boolean,
) {
    var result by mutableStateOf<String?>(null)
        private set
    var failure by mutableStateOf<TextIntelligenceFailure?>(null)
        private set
    var running by mutableStateOf(false)
        private set
    private var generation = 0L
    private var job: Job? = null

    fun start(
        provider: TextIntelligenceProvider,
        operation: TextIntelligenceOperation,
        input: String,
    ) {
        cancel()
        if (!ownerCurrent()) return
        val epoch = generation
        running = true
        job =
            scope.launch {
                try {
                    val response = withTimeout(60_000L) { runTextIntelligence(provider, operation, input) }
                    if (generation == epoch && ownerCurrent()) result = response
                } catch (_: TimeoutCancellationException) {
                    if (generation == epoch && ownerCurrent()) failure = TextIntelligenceFailure.TIMEOUT
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: TextIntelligenceException) {
                    if (generation == epoch && ownerCurrent()) failure = error.failure
                } catch (_: Exception) {
                    if (generation == epoch && ownerCurrent()) failure = TextIntelligenceFailure.ENGINE
                } finally {
                    if (generation == epoch) running = false
                }
            }
    }

    fun cancel() {
        generation += 1L
        job?.cancel()
        job = null
        running = false
        result = null
        failure = null
    }
}
