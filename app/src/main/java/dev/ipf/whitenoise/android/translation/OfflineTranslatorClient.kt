package dev.ipf.whitenoise.android.translation

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import dev.davidv.translator.ErrorType
import dev.davidv.translator.ITranslationCallback
import dev.davidv.translator.ITranslationService
import dev.davidv.translator.TranslationError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** One bound service per request. Cancellation invalidates its private callback and always unbinds. */
internal class OfflineTranslatorClient(
    private val context: Context,
) : TranslationEngine {
    private val service = discoverOfflineTranslator(context)
    override val identity = service.identity
    override val trust = TranslationTrust.LOCAL

    override suspend fun translate(
        text: String,
        source: String,
        target: String,
    ): String =
        withContext(Dispatchers.Main) {
            val connectionResult = CompletableDeferred<IBinder>()
            val translationResult = CompletableDeferred<String>()
            val connection = translationServiceConnection(connectionResult, translationResult)
            var bound = false
            var binder: IBinder? = null
            val death =
                IBinder.DeathRecipient {
                    translationResult.completeExceptionally(TranslationException(TranslationFailure.SERVICE_DIED))
                }
            try {
                withTimeout(30_000L) {
                    if (withContext(Dispatchers.IO) { discoverOfflineTranslator(context) } != service) {
                        throw TranslationException(TranslationFailure.UNVERIFIED_ENGINE)
                    }
                    bound =
                        context.bindService(
                            Intent(OFFLINE_TRANSLATOR_ACTION).setComponent(service.component),
                            connection,
                            Context.BIND_AUTO_CREATE,
                        )
                    if (!bound) throw TranslationException(TranslationFailure.CONNECTION)
                    val connected = connectionResult.await()
                    binder = connected
                    connected.linkToDeath(death, 0)
                    if (withContext(Dispatchers.IO) { connected.interfaceDescriptor } != OFFLINE_TRANSLATOR_ACTION ||
                        withContext(Dispatchers.IO) { discoverOfflineTranslator(context) } != service
                    ) {
                        throw TranslationException(TranslationFailure.INCOMPATIBLE_ENGINE)
                    }
                    withContext(Dispatchers.IO) {
                        ITranslationService.Stub.asInterface(connected).translate(
                            text,
                            source,
                            target,
                            translationCallback(translationResult),
                        )
                    }
                    translationResult.await()
                }
            } catch (_: TimeoutCancellationException) {
                throw TranslationException(TranslationFailure.TIMEOUT)
            } finally {
                connectionResult.cancel()
                translationResult.cancel()
                binder?.let { runCatching { it.unlinkToDeath(death, 0) } }
                if (bound) runCatching { context.unbindService(connection) }
            }
        }
}

private fun translationServiceConnection(
    connected: CompletableDeferred<IBinder>,
    result: CompletableDeferred<String>,
): ServiceConnection =
    object : ServiceConnection {
        override fun onServiceConnected(
            name: ComponentName,
            service: IBinder,
        ) {
            connected.complete(service)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            connected.completeExceptionally(TranslationException(TranslationFailure.SERVICE_DIED))
            result.completeExceptionally(TranslationException(TranslationFailure.SERVICE_DIED))
        }

        override fun onNullBinding(name: ComponentName) {
            connected.completeExceptionally(TranslationException(TranslationFailure.INCOMPATIBLE_ENGINE))
        }

        override fun onBindingDied(name: ComponentName) {
            onServiceDisconnected(name)
        }
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
