package dev.ipf.whitenoise.android.intelligence

internal enum class TextIntelligenceOperation { PROOFREAD, REWRITE, SUMMARIZE }

internal enum class TextIntelligenceExecution { LOCAL, NETWORK }

internal enum class TextIntelligenceAvailability { READY, UNAVAILABLE, MODEL_REQUIRED }

internal enum class TextIntelligenceFailure {
    UNAVAILABLE,
    MODEL_REQUIRED,
    INVALID_INPUT,
    CONSENT_REQUIRED,
    ENGINE,
    TIMEOUT,
}

internal class TextIntelligenceException(
    val failure: TextIntelligenceFailure,
) : Exception(failure.name)

internal data class TextIntelligenceCapability(
    val identity: String,
    val operations: Set<TextIntelligenceOperation>,
    val execution: TextIntelligenceExecution,
    val availability: TextIntelligenceAvailability,
    val inputLimit: Int,
)

internal interface TextIntelligenceProvider {
    suspend fun capability(operation: TextIntelligenceOperation): TextIntelligenceCapability

    suspend fun transform(
        operation: TextIntelligenceOperation,
        text: String,
    ): String
}

/** Local-only is an invocation boundary, not a preference for trying local before cloud. */
internal suspend fun runTextIntelligence(
    provider: TextIntelligenceProvider,
    operation: TextIntelligenceOperation,
    text: String,
    localOnly: Boolean = true,
    acknowledgedIdentity: String? = null,
): String {
    val capability = provider.capability(operation)
    textIntelligenceInputFailure(capability, operation, text, localOnly, acknowledgedIdentity)?.let {
        throw TextIntelligenceException(it)
    }
    return provider.transform(operation, text).also {
        if (it.isBlank() || it.length > MAX_INTELLIGENCE_OUTPUT) throw TextIntelligenceException(TextIntelligenceFailure.ENGINE)
    }
}

private fun textIntelligenceInputFailure(
    capability: TextIntelligenceCapability,
    operation: TextIntelligenceOperation,
    text: String,
    localOnly: Boolean,
    acknowledgedIdentity: String?,
): TextIntelligenceFailure? =
    when {
        capability.execution == TextIntelligenceExecution.NETWORK &&
            (localOnly || acknowledgedIdentity != capability.identity) -> TextIntelligenceFailure.CONSENT_REQUIRED
        operation !in capability.operations ||
            capability.availability == TextIntelligenceAvailability.UNAVAILABLE -> TextIntelligenceFailure.UNAVAILABLE
        capability.availability == TextIntelligenceAvailability.MODEL_REQUIRED -> TextIntelligenceFailure.MODEL_REQUIRED
        text.isBlank() || text.length > capability.inputLimit -> TextIntelligenceFailure.INVALID_INPUT
        else -> null
    }

internal const val MAX_INTELLIGENCE_OUTPUT = 8_192
internal const val INTELLIGENCE_REQUEST_TIMEOUT_MS = 60_000L
