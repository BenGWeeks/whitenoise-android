package dev.ipf.whitenoise.android.translation

internal enum class TranslationTrust { LOCAL, NETWORK_CAPABLE }

internal enum class TranslationFailure {
    MISSING_ENGINE,
    UNVERIFIED_ENGINE,
    INCOMPATIBLE_ENGINE,
    CONNECTION,
    SERVICE_DIED,
    TIMEOUT,
    DETECTION,
    SOURCE_MODEL,
    ENGINE,
    INVALID_REQUEST,
    CONSENT_REQUIRED,
}

internal class TranslationException(
    val failure: TranslationFailure,
) : Exception(failure.name)

internal data class TranslationEngineIdentity(
    val packageName: String,
    val signer: String,
    val version: Long,
)

internal interface TranslationEngine {
    val identity: TranslationEngineIdentity
    val trust: TranslationTrust

    suspend fun translate(
        text: String,
        source: String,
        target: String,
    ): String
}

internal fun translationLanguageValid(code: String): Boolean = code.matches(Regex("[a-z]{2,3}(-[A-Za-z]{2,8})?"))

internal suspend fun translateWithPrivacyGate(
    engine: TranslationEngine,
    text: String,
    source: String,
    target: String,
    acknowledgedIdentity: TranslationEngineIdentity? = null,
): String {
    if (text.isBlank() ||
        text.length > MAX_TRANSLATION_TEXT ||
        (source.isNotEmpty() && !translationLanguageValid(source)) ||
        !translationLanguageValid(target)
    ) {
        throw TranslationException(TranslationFailure.INVALID_REQUEST)
    }
    if (engine.trust == TranslationTrust.NETWORK_CAPABLE && acknowledgedIdentity != engine.identity) {
        throw TranslationException(TranslationFailure.CONSENT_REQUIRED)
    }
    return engine.translate(text, source, target).also {
        if (it.isBlank() || it.length > MAX_TRANSLATION_TEXT) throw TranslationException(TranslationFailure.ENGINE)
    }
}

internal const val MAX_TRANSLATION_TEXT = 16_384
