package dev.ipf.whitenoise.android.intelligence

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class TextIntelligenceRequestLifecycleTest {
    @Test fun accountConversationOrDraftReplacementRejectsLateResult() =
        runTest {
            val response = CompletableDeferred<String>()
            var ownerCurrent = true
            val request = TextIntelligenceRequest(backgroundScope) { ownerCurrent }
            request.start(
                provider { withContext(NonCancellable) { response.await() } },
                TextIntelligenceOperation.REWRITE,
                "draft",
            )
            runCurrent()
            ownerCurrent = false
            response.complete("stale suggestion")
            runCurrent()
            assertNull(request.result)
        }

    @Test fun changingOperationOrProviderRetiresPriorGeneration() =
        runTest {
            val old = CompletableDeferred<String>()
            val fresh = CompletableDeferred<String>()
            val request = TextIntelligenceRequest(backgroundScope) { true }
            request.start(
                provider { withContext(NonCancellable) { old.await() } },
                TextIntelligenceOperation.REWRITE,
                "draft",
            )
            runCurrent()
            request.start(provider { fresh.await() }, TextIntelligenceOperation.PROOFREAD, "draft")
            runCurrent()
            old.complete("obsolete")
            fresh.complete("current")
            runCurrent()
            assertEquals("current", request.result)
            request.cancel()
            assertNull(request.result)
            assertFalse(request.running)
        }

    @Test fun timeoutLeavesNoResultAndAnActionableFailure() =
        runTest {
            val response = CompletableDeferred<String>()
            val request = TextIntelligenceRequest(backgroundScope) { true }
            request.start(provider { response.await() }, TextIntelligenceOperation.REWRITE, "draft")
            runCurrent()
            advanceTimeBy(60_001)
            runCurrent()
            assertNull(request.result)
            assertFalse(request.running)
            assertEquals(TextIntelligenceFailure.TIMEOUT, request.failure)
        }

    private fun provider(transform: suspend () -> String) =
        object : TextIntelligenceProvider {
            override suspend fun capability(operation: TextIntelligenceOperation) =
                TextIntelligenceCapability(
                    "local",
                    TextIntelligenceOperation.entries.toSet(),
                    TextIntelligenceExecution.LOCAL,
                    TextIntelligenceAvailability.READY,
                    100,
                )

            override suspend fun transform(
                operation: TextIntelligenceOperation,
                text: String,
            ): String = transform()
        }
}
