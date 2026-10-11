package dev.ipf.whitenoise.android.intelligence

/** No AICore SDK is initialized until its network diagnostics have an explicit consent contract. */
internal class AicoreTextIntelligenceProvider : TextIntelligenceProvider {
    override suspend fun capability(operation: TextIntelligenceOperation): TextIntelligenceCapability =
        TextIntelligenceCapability(
            "aicore:deferred",
            emptySet(),
            TextIntelligenceExecution.LOCAL,
            TextIntelligenceAvailability.UNAVAILABLE,
            0,
        )

    override suspend fun transform(
        operation: TextIntelligenceOperation,
        text: String,
    ): String = throw TextIntelligenceException(TextIntelligenceFailure.UNAVAILABLE)
}
