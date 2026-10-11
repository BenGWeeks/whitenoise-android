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
    if (capability.execution == TextIntelligenceExecution.NETWORK &&
        (localOnly || acknowledgedIdentity != capability.identity)
    ) {
        throw TextIntelligenceException(TextIntelligenceFailure.CONSENT_REQUIRED)
    }
    if (operation !in capability.operations || capability.availability == TextIntelligenceAvailability.UNAVAILABLE) {
        throw TextIntelligenceException(TextIntelligenceFailure.UNAVAILABLE)
    }
    if (capability.availability == TextIntelligenceAvailability.MODEL_REQUIRED) {
        throw TextIntelligenceException(TextIntelligenceFailure.MODEL_REQUIRED)
    }
    if (text.isBlank() || text.length > capability.inputLimit) {
        throw TextIntelligenceException(TextIntelligenceFailure.INVALID_INPUT)
    }
    return provider.transform(operation, text).also {
        if (it.isBlank() || it.length > 8_192) throw TextIntelligenceException(TextIntelligenceFailure.ENGINE)
    }
}
