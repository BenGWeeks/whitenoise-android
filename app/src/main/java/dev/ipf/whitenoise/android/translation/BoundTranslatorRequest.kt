package dev.ipf.whitenoise.android.translation

import dev.davidv.translator.ErrorType
import dev.davidv.translator.ITranslationCallback
import dev.davidv.translator.ITranslationService
import dev.davidv.translator.TranslationError
import kotlinx.coroutines.CompletableDeferred

/** Recovery stays on the same verified binding and within the caller's overall deadline. */
internal suspend fun translateOnBoundTranslator(
    service: ITranslationService,
    text: String,
    source: String,
    target: String,
    prepareSubmission: suspend (CompletableDeferred<String>) -> Unit,
): String {
    repeat(2) { attempt ->
        val result = CompletableDeferred<String>()
        try {
            prepareSubmission(result)
            runTranslatorBinderCall { service.translate(text, source, target, translationCallback(result)) }
            return result.await()
        } catch (error: TranslationException) {
            // The reviewed engine resolves explicit languages before its catalog finishes loading.
            // Its detection-error callback arrives after initialization; retry that explicit choice once.
            if (attempt != 0 || source.isEmpty() || error.failure != TranslationFailure.DETECTION) throw error
        } finally {
            result.cancel()
        }
    }
    error("Translator retry exhausted without a result")
}

private fun translationCallback(result: CompletableDeferred<String>): ITranslationCallback.Stub =
    object : ITranslationCallback.Stub() {
        override fun onTranslationResult(translatedText: String?) {
            val text = translatedText?.takeIf { it.isNotBlank() && it.length <= MAX_TRANSLATION_TEXT }
            if (text == null) {
                result.completeExceptionally(TranslationException(TranslationFailure.ENGINE))
            } else {
                result.complete(text)
            }
        }

        override fun onTranslationError(error: TranslationError?) {
            val failure =
                when (error?.type) {
                    ErrorType.COULD_NOT_DETECT_LANGUAGE -> TranslationFailure.DETECTION
                    ErrorType.DETECTED_BUT_UNAVAILABLE -> TranslationFailure.SOURCE_MODEL
                    else -> TranslationFailure.ENGINE
                }
            // Engine message/language payloads are never retained, displayed or logged.
            result.completeExceptionally(TranslationException(failure))
        }
    }
