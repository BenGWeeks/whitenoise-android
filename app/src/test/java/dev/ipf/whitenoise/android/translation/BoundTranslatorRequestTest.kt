package dev.ipf.whitenoise.android.translation

import dev.davidv.translator.ErrorType
import dev.davidv.translator.ITranslationCallback
import dev.davidv.translator.ITranslationService
import dev.davidv.translator.TranslationError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BoundTranslatorRequestTest {
    @Test
    fun explicitLanguageRecoversColdCatalogOnTheSameService() =
        runBlocking {
            val service =
                FakeTranslator { attempt, callback ->
                    if (attempt == 1) {
                        callback.onTranslationError(failure(ErrorType.COULD_NOT_DETECT_LANGUAGE))
                    } else {
                        callback.onTranslationResult("Hello world")
                    }
                }
            var validations = 0
            val translated = request(service, beforeSubmission = { validations += 1 })
            assertEquals("Hello world", translated)
            assertEquals(2, validations)
            assertEquals(
                listOf(listOf("Hola mundo", "es", "en"), listOf("Hola mundo", "es", "en")),
                service.submissions,
            )
        }

    @Test
    fun automaticDetectionFailureIsNotRetried() =
        runBlocking {
            val service =
                FakeTranslator { _, callback ->
                    callback.onTranslationError(failure(ErrorType.COULD_NOT_DETECT_LANGUAGE))
                }
            assertEquals(TranslationFailure.DETECTION, failureOf { request(service, source = "") })
            assertEquals(1, service.submissions.size)
        }

    @Test
    fun repeatedExplicitDetectionFailureStopsAfterOneRetry() =
        runBlocking {
            val service =
                FakeTranslator { _, callback ->
                    callback.onTranslationError(failure(ErrorType.COULD_NOT_DETECT_LANGUAGE))
                }
            assertEquals(TranslationFailure.DETECTION, failureOf { request(service) })
            assertEquals(2, service.submissions.size)
        }

    @Test
    fun unavailableLanguagePackIsNotRetried() =
        runBlocking {
            val service =
                FakeTranslator { _, callback ->
                    callback.onTranslationError(failure(ErrorType.DETECTED_BUT_UNAVAILABLE))
                }
            assertEquals(TranslationFailure.SOURCE_MODEL, failureOf { request(service) })
            assertEquals(1, service.submissions.size)
        }

    @Test
    fun changedEngineIdentityReceivesNoRetryPlaintext() =
        runBlocking {
            val service =
                FakeTranslator { _, callback ->
                    callback.onTranslationError(failure(ErrorType.COULD_NOT_DETECT_LANGUAGE))
                }
            var validations = 0
            val observed =
                failureOf {
                    request(
                        service,
                        beforeSubmission = {
                            validations += 1
                            if (validations == 2) throw TranslationException(TranslationFailure.UNVERIFIED_ENGINE)
                        },
                    )
                }
            assertEquals(TranslationFailure.UNVERIFIED_ENGINE, observed)
            assertEquals(1, service.submissions.size)
        }

    @Test
    fun cancelledRetryCannotDeliverALateResult() =
        runBlocking {
            val retryStarted = CompletableDeferred<Unit>()
            val retryCallback = CompletableDeferred<ITranslationCallback>()
            var active: CompletableDeferred<String>? = null
            val service =
                FakeTranslator { attempt, callback ->
                    if (attempt == 1) {
                        callback.onTranslationError(failure(ErrorType.COULD_NOT_DETECT_LANGUAGE))
                    } else {
                        retryCallback.complete(callback)
                        retryStarted.complete(Unit)
                    }
                }
            val pending = async { request(service, activeResult = { active = it }) }
            withTimeout(2_000L) { retryStarted.await() }
            withTimeout(2_000L) { pending.cancelAndJoin() }
            retryCallback.await().onTranslationResult("Late synthetic output")
            assertTrue(pending.isCancelled)
            assertTrue(checkNotNull(active).isCancelled)
            assertEquals(2, service.submissions.size)
        }

    private suspend fun request(
        service: ITranslationService,
        source: String = "es",
        beforeSubmission: suspend () -> Unit = {},
        activeResult: (CompletableDeferred<String>) -> Unit = {},
    ): String =
        translateOnBoundTranslator(service, "Hola mundo", source, "en") {
            activeResult(it)
            beforeSubmission()
        }

    private suspend fun failureOf(operation: suspend () -> String): TranslationFailure =
        (runCatching { operation() }.exceptionOrNull() as TranslationException).failure

    private fun failure(type: Byte): TranslationError = TranslationError().apply { this.type = type }

    private class FakeTranslator(
        private val deliver: (Int, ITranslationCallback) -> Unit,
    ) : ITranslationService.Stub() {
        val submissions = CopyOnWriteArrayList<List<String?>>()

        override fun translate(
            text: String?,
            source: String?,
            target: String?,
            callback: ITranslationCallback?,
        ) {
            submissions.add(listOf(text, source, target))
            deliver(submissions.size, checkNotNull(callback))
        }
    }
}
