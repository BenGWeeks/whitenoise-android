package dev.ipf.whitenoise.android.intelligence

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class TextIntelligenceProviderPolicyTest {
    @Test fun localOnlyNeverInvokesANetworkProviderEvenAfterConsent() =
        runTest {
            val provider = PolicyProvider(TextIntelligenceExecution.NETWORK)
            try {
                runTextIntelligence(
                    provider,
                    TextIntelligenceOperation.REWRITE,
                    "private draft",
                    acknowledgedIdentity = "provider",
                )
                fail("cloud invocation in local-only mode")
            } catch (error: TextIntelligenceException) {
                assertEquals(TextIntelligenceFailure.CONSENT_REQUIRED, error.failure)
            }
            assertEquals(0, provider.invocations)
        }

    @Test fun networkConsentIsProviderSpecificAndExplicit() =
        runTest {
            val provider = PolicyProvider(TextIntelligenceExecution.NETWORK)
            try {
                runTextIntelligence(provider, TextIntelligenceOperation.REWRITE, "draft", false, "previous-provider")
                fail("stale consent accepted")
            } catch (error: TextIntelligenceException) {
                assertEquals(TextIntelligenceFailure.CONSENT_REQUIRED, error.failure)
            }
            assertEquals(0, provider.invocations)
            assertEquals(
                "suggestion",
                runTextIntelligence(provider, TextIntelligenceOperation.REWRITE, "draft", false, "provider"),
            )
        }

    @Test fun unavailableOrUnsupportedLocalOperationCannotSilentlyFallBack() =
        runTest {
            val provider =
                PolicyProvider(
                    TextIntelligenceExecution.LOCAL,
                    availability = TextIntelligenceAvailability.UNAVAILABLE,
                )
            try {
                runTextIntelligence(provider, TextIntelligenceOperation.REWRITE, "draft")
                fail("unavailable local inference ran")
            } catch (error: TextIntelligenceException) {
                assertEquals(TextIntelligenceFailure.UNAVAILABLE, error.failure)
            }
            assertEquals(0, provider.invocations)
            val unsupported =
                PolicyProvider(TextIntelligenceExecution.LOCAL, operations = setOf(TextIntelligenceOperation.PROOFREAD))
            try {
                runTextIntelligence(unsupported, TextIntelligenceOperation.SUMMARIZE, "draft")
                fail("mismatched operation ran")
            } catch (error: TextIntelligenceException) {
                assertEquals(TextIntelligenceFailure.UNAVAILABLE, error.failure)
            }
            assertEquals(0, unsupported.invocations)
        }

    @Test fun inputAndOutputAreBounded() =
        runTest {
            val provider = PolicyProvider(TextIntelligenceExecution.LOCAL, limit = 3)
            try {
                runTextIntelligence(provider, TextIntelligenceOperation.REWRITE, "long")
                fail("oversized input ran")
            } catch (error: TextIntelligenceException) {
                assertEquals(TextIntelligenceFailure.INVALID_INPUT, error.failure)
            }
            assertEquals(0, provider.invocations)
            provider.output = "x".repeat(8_193)
            try {
                runTextIntelligence(provider, TextIntelligenceOperation.REWRITE, "ok")
                fail("oversized output accepted")
            } catch (error: TextIntelligenceException) {
                assertEquals(TextIntelligenceFailure.ENGINE, error.failure)
            }
        }

    private class PolicyProvider(
        private val execution: TextIntelligenceExecution,
        private val availability: TextIntelligenceAvailability = TextIntelligenceAvailability.READY,
        private val operations: Set<TextIntelligenceOperation> = TextIntelligenceOperation.entries.toSet(),
        private val limit: Int = 100,
    ) : TextIntelligenceProvider {
        var invocations = 0
        var output = "suggestion"

        override suspend fun capability(operation: TextIntelligenceOperation) =
            TextIntelligenceCapability("provider", operations, execution, availability, limit)

        override suspend fun transform(
            operation: TextIntelligenceOperation,
            text: String,
        ): String {
            invocations += 1
            return output
        }
    }
}
