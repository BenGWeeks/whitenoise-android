package dev.ipf.whitenoise.android.media.sandbox

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SharedMemory
import android.system.OsConstants
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal interface MediaDecodeTransport {
    fun decode(
        bytes: ByteArray,
        edge: Int,
        oriented: Boolean,
        operation: Int,
    ): Bitmap?

    fun exif(bytes: ByteArray): Int? = inspect(bytes)?.orientation

    fun inspect(bytes: ByteArray): ImageInspection?

    fun probe(
        file: File,
        edge: Int,
        poster: Boolean,
    ): MediaProbe? = null

    fun decodeUri(
        resolver: android.content.ContentResolver,
        uri: android.net.Uri,
        edge: Int,
    ): Bitmap? = null

    fun probeUri(
        resolver: android.content.ContentResolver,
        uri: android.net.Uri,
        edge: Int,
    ): MediaProbe? = null

    fun probeBytes(
        bytes: ByteArray,
        edge: Int,
        poster: Boolean,
    ): MediaProbe? = null

    fun waveform(file: File): FloatArray? = null
}

/** One serialized reusable worker. A live worker that misses its deadline poisons further admission. */
@Suppress("TooManyFunctions") // Cohesive binding, admission and descriptor lifecycle for one decoder lane.
internal class IsolatedMediaTransport(
    private val context: Context,
    private val instanceName: String = "images",
) : MediaDecodeTransport,
    java.io.Closeable {
    private val gate = Semaphore(1)
    private val ids = AtomicInteger()
    private val callbackThread = lazy { HandlerThread("media-decoder-ipc").apply { start() } }
    private val callbacks by callbackThread
    private val handler by lazy { Handler(callbacks.looper) }
    private val callbackExecutor = java.util.concurrent.Executor { command -> handler.post(command) }
    private val sourceTimer =
        lazy {
            java.util.concurrent.Executors
                .newSingleThreadScheduledExecutor()
        }

    private val sourceWatchdog by sourceTimer
    private val activeSource =
        java.util.concurrent.atomic
            .AtomicReference<java.util.concurrent.atomic.AtomicReference<AutoCloseable?>?>()
    private val activeCancellation =
        java.util.concurrent.atomic
            .AtomicReference<android.os.CancellationSignal?>()

    @Volatile private var remote: Messenger? = null

    @Volatile private var poisoned = false

    @Volatile private var pending: Reply? = null

    private fun responseCapability(current: Reply): Messenger =
        Messenger(
            object : android.os.Binder() {
                override fun onTransact(
                    code: Int,
                    data: android.os.Parcel,
                    reply: android.os.Parcel?,
                    flags: Int,
                ): Boolean {
                    if (code != IBinder.FIRST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags)
                    if (pending !== current || data.dataAvail() > MediaDecodePolicy.MAX_REPLY_BYTES) return false
                    val result =
                        try {
                            data.enforceInterface(MEDIA_REPLY_DESCRIPTOR)
                            if (data.readInt() != current.id) return false
                            readMediaDecodeReply(data).apply { putInt("uid", android.os.Binder.getCallingUid()) }
                        } catch (_: RuntimeException) {
                            null
                        }
                    if (pending === current && current.completed.compareAndSet(false, true)) {
                        current.result = result
                        result?.let(IsolatedMediaDecoder::recordIsolation)
                        current.ready.countDown()
                    }
                    return true
                }
            },
        )

    @Volatile private var connection: ServiceConnection? = null

    private fun newConnection(): ServiceConnection =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName,
                service: IBinder,
            ) {
                if (connection !== this) return
                remote = Messenger(service)
                pending?.connected?.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName) {
                if (connection !== this) return
                remote = null
                poisoned = false // Only a current system death notification reopens admission.
                pending?.ready?.countDown()
                pending?.connected?.countDown()
            }

            override fun onBindingDied(name: ComponentName) = onServiceDisconnected(name)
        }

    private var bound = false
    private val refusals = LinkedHashMap<String, Long>()

    override fun decode(
        bytes: ByteArray,
        edge: Int,
        oriented: Boolean,
        operation: Int,
    ): Bitmap? =
        serialized {
            val capacity = MediaDecodePolicy.outputCapacity(edge) ?: return@serialized null
            SharedMemory.create("media-pixels", capacity).use { output ->
                val result = request(bytes, edge, oriented, operation, output) ?: return@serialized null
                copyPixels(result, output, edge)
            }
        }

    override fun exif(bytes: ByteArray): Int? =
        serialized {
            SharedMemory.create("media-exif", Int.SIZE_BYTES).use { output ->
                request(bytes, 1, false, MediaDecodePolicy.EXIF, output)?.getInt("orientation")?.takeIf { it in 1..8 }
            }
        }

    override fun inspect(bytes: ByteArray): ImageInspection? =
        serialized {
            SharedMemory.create("media-inspection", Int.SIZE_BYTES).use { output ->
                val result = request(bytes, 1, false, MediaDecodePolicy.INSPECT, output) ?: return@serialized null
                val width = result.getInt("width")
                val height = result.getInt("height")
                if (width <= 0 || height <= 0) return@serialized null
                ImageInspection(width, height, result.getString("mime").orEmpty().take(64), result.getInt("orientation"))
            }
        }

    override fun decodeUri(
        resolver: android.content.ContentResolver,
        uri: android.net.Uri,
        edge: Int,
    ): Bitmap? =
        withUriInput(resolver, uri) { input ->
            serialized {
                val capacity = MediaDecodePolicy.outputCapacity(edge) ?: return@serialized null
                SharedMemory.create("media-pixels", capacity).use { output ->
                    exchange(input, output, MediaDecodeRequest(edge, true, MediaDecodePolicy.DECODE))
                        ?.let { copyPixels(it, output, edge) }
                }
            }
        }

    override fun probeUri(
        resolver: android.content.ContentResolver,
        uri: android.net.Uri,
        edge: Int,
    ): MediaProbe? =
        withProviderDeadline { signal, source ->
            resolver.openAssetFileDescriptor(uri, "r", signal)?.use { asset ->
                source.set(asset)
                val input = asset.parcelFileDescriptor
                val length = asset.length
                if (length !in 1..MAX_MEDIA_SOURCE_BYTES ||
                    asset.startOffset < 0L ||
                    input.statSize < length ||
                    asset.startOffset > input.statSize - length
                ) {
                    return@withProviderDeadline null
                }
                serialized {
                    val capacity = MediaDecodePolicy.outputCapacity(edge) ?: return@serialized null
                    SharedMemory.create("media-poster", capacity).use { output ->
                        val response =
                            exchange(input, output, MediaDecodeRequest(edge, true, MediaDecodePolicy.PROBE, asset.startOffset, length))
                                ?: return@serialized null
                        MediaProbe(
                            response.getInt("sourceWidth"),
                            response.getInt("sourceHeight"),
                            response.getLong("duration"),
                            if (response.getBoolean("pixels")) copyPixels(response, output, edge) else null,
                        )
                    }
                }
            }
        }

    override fun probe(
        file: File,
        edge: Int,
        poster: Boolean,
    ): MediaProbe? =
        serialized {
            val capacity = MediaDecodePolicy.outputCapacity(edge) ?: return@serialized null
            SharedMemory.create("media-poster", capacity).use { output ->
                val response = requestFile(file, edge, poster, MediaDecodePolicy.PROBE, output) ?: return@serialized null
                val bitmap = if (response.getBoolean("pixels")) copyPixels(response, output, edge) else null
                MediaProbe(response.getInt("sourceWidth"), response.getInt("sourceHeight"), response.getLong("duration"), bitmap)
            }
        }

    override fun probeBytes(
        bytes: ByteArray,
        edge: Int,
        poster: Boolean,
    ): MediaProbe? =
        serialized {
            val capacity = MediaDecodePolicy.outputCapacity(edge) ?: return@serialized null
            SharedMemory.create("media-poster", capacity).use { output ->
                val response = request(bytes, edge, poster, MediaDecodePolicy.PROBE, output) ?: return@serialized null
                MediaProbe(
                    response.getInt("sourceWidth"),
                    response.getInt("sourceHeight"),
                    response.getLong("duration"),
                    if (response.getBoolean("pixels")) copyPixels(response, output, edge) else null,
                )
            }
        }

    override fun waveform(file: File): FloatArray? =
        serialized {
            SharedMemory.create("media-waveform", Int.SIZE_BYTES).use { output ->
                val response = requestFile(file, 1, false, MediaDecodePolicy.WAVEFORM, output) ?: return@serialized null
                response.getFloatArray("waveform")?.takeIf { values ->
                    values.size == dev.ipf.whitenoise.android.audio.AudioWaveformExtractor.BARS && values.all { it.isFinite() && it in 0f..1f }
                }
            }
        }

    @Suppress("ReturnCount") // Source size and failure quarantine precede opening the descriptor.
    private fun requestFile(
        file: File,
        edge: Int,
        poster: Boolean,
        operation: Int,
        output: SharedMemory,
    ): Bundle? {
        if (!file.isFile || file.length() !in 1..MAX_MEDIA_SOURCE_BYTES || poisoned) return null
        val key = "$operation:$edge:${file.absolutePath}:${file.length()}:${file.lastModified()}"
        val now = android.os.SystemClock.elapsedRealtime()
        if ((refusals[key] ?: 0L) > now) return null
        val result =
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { input ->
                exchange(input, output, MediaDecodeRequest(edge, poster, operation))
            }
        if (result == null) recordRefusal(key, now)
        return result
    }

    private fun <T> serialized(block: () -> T?): T? {
        if (poisoned || !gate.tryAcquire(MediaDecodePolicy.DEADLINE_MS, TimeUnit.MILLISECONDS)) return null
        return try {
            if (poisoned) null else block()
        } finally {
            gate.release()
        }
    }

    @Suppress("ReturnCount") // Reject resource and cooldown failures before binding.
    private fun request(
        bytes: ByteArray,
        edge: Int,
        oriented: Boolean,
        operation: Int,
        output: SharedMemory,
    ): Bundle? {
        if (bytes.size !in 1..MediaDecodePolicy.MAX_INPUT_BYTES || poisoned) return null
        val digest =
            java.security.MessageDigest
                .getInstance("SHA-256")
                .digest(bytes)
        val key = "$operation:$edge:$oriented:" + digest.joinToString("") { "%02x".format(it) }
        val now = android.os.SystemClock.elapsedRealtime()
        if ((refusals[key] ?: 0L) > now) return null
        val result = withInput(bytes) { input -> exchange(input, output, MediaDecodeRequest(edge, oriented, operation)) }
        if (result == null) recordRefusal(key, now)
        return result
    }

    @Suppress("ReturnCount") // Connection, deadline and exact reply shape are separate fail-closed boundaries.
    private fun exchange(
        input: ParcelFileDescriptor,
        output: SharedMemory,
        request: MediaDecodeRequest,
    ): Bundle? {
        val reply = Reply(ids.incrementAndGet())
        try {
            pending = reply
            if (!connect(reply)) return null
            remote?.send(
                Message.obtain().apply {
                    what = MediaDecodePolicy.DECODE
                    arg1 = reply.id
                    replyTo = responseCapability(reply)
                    data =
                        Bundle().apply {
                            putInt("version", MediaDecodePolicy.VERSION)
                            putInt("operation", request.operation)
                            putInt("edge", request.edge)
                            putBoolean("oriented", request.option)
                            putLong("offset", request.offset)
                            putLong("length", request.length ?: input.statSize)
                            putParcelable("input", input)
                            putParcelable("output", output)
                        }
                },
            )
            if (!reply.ready.await(
                    if (request.operation ==
                        MediaDecodePolicy.WAVEFORM
                    ) {
                        MediaDecodePolicy.WAVEFORM_DEADLINE_MS
                    } else {
                        MediaDecodePolicy.DEADLINE_MS
                    },
                    TimeUnit.MILLISECONDS,
                )
            ) {
                poisoned = remote?.binder?.isBinderAlive == true
                return null
            }
            return reply.result?.takeIf {
                it.getBoolean("success") &&
                    it.getInt("uid") != Process.myUid() &&
                    it.getBoolean("isolated") &&
                    !it.getBoolean("internetGranted") &&
                    !it.getBoolean("runtimeInitialized")
            }
        } finally {
            pending = null
        }
    }

    private fun recordRefusal(
        key: String,
        now: Long,
    ) {
        refusals[key] = now + MediaDecodePolicy.REFUSAL_TTL_MS
        while (refusals.size > MediaDecodePolicy.MAX_REFUSALS) refusals.remove(refusals.keys.first())
    }

    private fun connect(reply: Reply): Boolean {
        if (remote != null) return true
        if (bound) connection?.let(context::unbindService)
        val selected = newConnection()
        connection = selected
        bound =
            context.bindIsolatedService(
                Intent(context, MediaDecodeService::class.java),
                Context.BIND_AUTO_CREATE,
                "$instanceName-${bindingSequence.incrementAndGet()}",
                callbackExecutor,
                selected,
            )
        val connected = bound && reply.connected.await(MediaDecodePolicy.DEADLINE_MS, TimeUnit.MILLISECONDS) && remote != null
        if (bound && !connected) poisoned = true
        return connected
    }

    private inline fun <T> withProviderDeadline(
        block: (android.os.CancellationSignal, java.util.concurrent.atomic.AtomicReference<AutoCloseable?>) -> T?,
    ): T? {
        if (poisoned || !providerAdmission.tryAcquire(MediaDecodePolicy.DEADLINE_MS, TimeUnit.MILLISECONDS)) return null
        if (poisoned) {
            providerAdmission.release()
            return null
        }
        val signal = android.os.CancellationSignal()
        val source =
            java.util.concurrent.atomic
                .AtomicReference<AutoCloseable?>()
        activeSource.set(source)
        activeCancellation.set(signal)
        var timer: java.util.concurrent.ScheduledFuture<*>? = null
        return try {
            timer =
                sourceWatchdog.schedule({
                    runCatching { signal.cancel() }
                    runCatching { source.get()?.close() }
                }, MediaDecodePolicy.DEADLINE_MS, TimeUnit.MILLISECONDS)
            block(signal, source)
        } finally {
            timer?.cancel(false)
            activeCancellation.compareAndSet(signal, null)
            activeSource.compareAndSet(source, null)
            providerAdmission.release()
        }
    }

    private fun <T> withUriInput(
        resolver: android.content.ContentResolver,
        uri: android.net.Uri,
        block: (ParcelFileDescriptor) -> T?,
    ): T? =
        withProviderDeadline { signal, opened ->
            resolver.openAssetFileDescriptor(uri, "r", signal)?.use { asset ->
                opened.set(asset)
                asset.createInputStream().use { source ->
                    withStagedInput({ writer -> copyBoundedSource(source, writer) }, block)
                }
            }
        }

    private fun copyBoundedSource(
        source: java.io.InputStream,
        writer: java.io.OutputStream,
    ): Boolean {
        val chunk = ByteArray(MediaDecodePolicy.STAGING_CHUNK_BYTES)
        var total = 0L
        var count = source.read(chunk)
        val deadline = android.os.SystemClock.elapsedRealtime() + MediaDecodePolicy.DEADLINE_MS
        while (count > 0) {
            total += count
            if (total > MediaDecodePolicy.MAX_INPUT_BYTES || android.os.SystemClock.elapsedRealtime() > deadline) return false
            writer.write(chunk, 0, count)
            count = source.read(chunk)
        }
        return count < 0 && total > 0L
    }

    private fun <T> withInput(
        bytes: ByteArray,
        block: (ParcelFileDescriptor) -> T,
    ): T? =
        withStagedInput({ writer ->
            writer.write(bytes)
            true
        }, block)

    private fun <T> withStagedInput(
        write: (java.io.OutputStream) -> Boolean,
        block: (ParcelFileDescriptor) -> T?,
    ): T? {
        val directory = File(context.cacheDir, "media-decoder-input").apply { mkdirs() }
        val file = File.createTempFile("source-", ".bin", directory)
        return try {
            file.outputStream().use { writer ->
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { input ->
                    check(file.delete()) // No plaintext pathname survives, even if the app dies during staging.
                    if (write(writer)) {
                        writer.flush()
                        block(input)
                    } else {
                        null
                    }
                }
            }
        } finally {
            file.delete()
        }
    }

    private fun copyPixels(
        result: Bundle,
        output: SharedMemory,
        edge: Int,
    ): Bitmap? {
        val width = result.getInt("width")
        val height = result.getInt("height")
        val count = MediaDecodePolicy.pixelBytes(width, height, edge, output.size) ?: return null
        if (!output.setProtect(OsConstants.PROT_READ)) return null
        val pixels = output.mapReadOnly()
        return try {
            pixels.limit(count)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                check(bitmap.byteCount == count)
                bitmap.copyPixelsFromBuffer(pixels)
                bitmap
            } catch (failure: Exception) {
                bitmap.recycle()
                throw failure
            }
        } finally {
            SharedMemory.unmap(pixels)
        }
    }

    override fun close() {
        poisoned = true
        if (sourceTimer.isInitialized()) {
            val signal = activeCancellation.get()
            val source = activeSource.get()
            sourceWatchdog.execute {
                runCatching { signal?.cancel() }
                runCatching { source?.get()?.close() }
                sourceWatchdog.shutdown()
            }
        }
        pending?.ready?.countDown()
        pending?.connected?.countDown()
        val previous = connection
        connection = null
        if (bound && previous != null) context.unbindService(previous)
        bound = false
        remote = null
        if (callbackThread.isInitialized()) callbacks.quitSafely()
    }

    companion object {
        private val bindingSequence = AtomicInteger()
        private val providerAdmission = Semaphore(1)
    }

    private class Reply(
        val id: Int,
    ) {
        val connected = CountDownLatch(1)
        val ready = CountDownLatch(1)
        val completed =
            java.util.concurrent.atomic
                .AtomicBoolean(false)

        @Volatile var result: Bundle? = null
    }
}

internal data class MediaDecodeRequest(
    val edge: Int,
    val option: Boolean,
    val operation: Int,
    val offset: Long = 0L,
    val length: Long? = null,
)
