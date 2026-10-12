package dev.ipf.whitenoise.android.translation

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import dev.davidv.translator.ErrorType
import dev.davidv.translator.ITranslationCallback
import dev.davidv.translator.ITranslationService
import dev.davidv.translator.TranslationError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

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
                withTimeoutOrNull(TRANSLATOR_REQUEST_TIMEOUT_MS) {
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
                    val descriptor = runTranslatorBinderCall { connected.interfaceDescriptor }
                    if (descriptor != OFFLINE_TRANSLATOR_ACTION ||
                        withContext(Dispatchers.IO) { discoverOfflineTranslator(context) } != service
                    ) {
                        throw TranslationException(TranslationFailure.INCOMPATIBLE_ENGINE)
                    }
                    runTranslatorBinderCall {
                        ITranslationService.Stub.asInterface(connected).translate(
                            text,
                            source,
                            target,
                            translationCallback(translationResult),
                        )
                    }
                    translationResult.await()
                } ?: throw TranslationException(TranslationFailure.TIMEOUT)
            } catch (_: RemoteException) {
                throw TranslationException(TranslationFailure.SERVICE_DIED)
            } finally {
                connectionResult.cancel()
                translationResult.cancel()
                binder?.let { runCatching { it.unlinkToDeath(death, 0) } }
                if (bound) runCatching { context.unbindService(connection) }
            }
        }
}

// Binder ignores coroutine cancellation while a synchronous transaction is stalled. A bounded
// pool leaves deadline and unbind ownership with the caller without creating unlimited threads.
private val translatorBinderExecutor =
    ThreadPoolExecutor(2, 2, 30L, TimeUnit.SECONDS, ArrayBlockingQueue(2)) { operation ->
        Thread(operation, "offline-translator-binder").apply { isDaemon = true }
    }.apply { allowCoreThreadTimeOut(true) }

internal suspend fun <T> runTranslatorBinderCall(operation: () -> T): T =
    suspendCancellableCoroutine { continuation ->
        try {
            val future =
                translatorBinderExecutor.submit {
                    // Relay the executor's exact outcome; only Binder death changes its public failure type.
                    val result = runCatching(operation)
                    if (result.exceptionOrNull() is RemoteException) {
                        continuation.resumeWithException(TranslationException(TranslationFailure.SERVICE_DIED))
                    } else {
                        continuation.resumeWith(result)
                    }
                }
            continuation.invokeOnCancellation { future.cancel(true) }
        } catch (_: RejectedExecutionException) {
            continuation.resumeWithException(TranslationException(TranslationFailure.CONNECTION))
        }
    }

private const val TRANSLATOR_REQUEST_TIMEOUT_MS = 30_000L

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
