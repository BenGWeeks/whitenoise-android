package dev.ipf.whitenoise.android.media.sandbox

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SharedMemory
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Permissionless codec worker. The control looper and watchdog never run native decoder work. */
class MediaDecodeService : Service() {
    private val codec = Executors.newSingleThreadExecutor()
    private val watchdog = Executors.newSingleThreadScheduledExecutor()
    private val messenger = Messenger(Handler(Looper.getMainLooper(), ::accept))

    override fun onBind(intent: Intent) = messenger.binder

    @Suppress("ReturnCount") // Invalid control messages never enter the codec queue.
    private fun accept(message: Message): Boolean {
        if (message.what != MediaDecodePolicy.DECODE || !Process.isIsolated()) return true
        val reply = message.replyTo ?: return true
        val data = message.data
        val requestId = message.arg1
        codec.execute {
            val killer =
                watchdog.schedule(
                    { Process.killProcess(Process.myPid()) },
                    if (data.getInt("operation") ==
                        MediaDecodePolicy.WAVEFORM
                    ) {
                        MediaDecodePolicy.WAVEFORM_WATCHDOG_MS
                    } else {
                        MediaDecodePolicy.WATCHDOG_MS
                    },
                    TimeUnit.MILLISECONDS,
                )
            try {
                val result =
                    try {
                        execute(data)
                    } catch (_: Exception) {
                        Bundle()
                    } catch (_: OutOfMemoryError) {
                        Bundle()
                    }
                sendMediaDecodeReply(reply.binder, requestId, result)
            } catch (_: Exception) {
                // The host has gone away; never retry or decode in its privileged process.
            } finally {
                killer.cancel(false)
            }
        }
        return true
    }

    @Suppress("DEPRECATION", "ReturnCount") // Legacy framework descriptors and fail-closed operation guards.
    private fun execute(data: Bundle): Bundle {
        val response =
            Bundle().apply {
                putInt("uid", Process.myUid())
                putBoolean("isolated", Process.isIsolated())
                putBoolean("privateCanaryDenied", privateCanaryDenied())
                putBoolean("runtimeInitialized", (application as dev.ipf.whitenoise.android.WhiteNoiseApplication).initializedAppState() != null)
                putBoolean("internetGranted", checkSelfPermission(android.Manifest.permission.INTERNET) == android.content.pm.PackageManager.PERMISSION_GRANTED)
            }
        val input = data.getParcelable<ParcelFileDescriptor>("input") ?: return response
        val output = data.getParcelable<SharedMemory>("output") ?: return response.also { input.close() }
        input.use {
            output.use {
                if (data.getInt("version") != MediaDecodePolicy.VERSION) return response
                val operation = data.getInt("operation")
                if (dev.ipf.whitenoise.android.BuildConfig.DEBUG && operation == MediaDecodePolicy.DEBUG_HANG) {
                    Thread.sleep(30000L) // Controlled debug-only watchdog fixture; no production hang command.
                    return response
                }
                if (operation == MediaDecodePolicy.PROBE || operation == MediaDecodePolicy.WAVEFORM) {
                    return mediaOperation(input, output, data, response)
                }
                val bytes = readInput(input) ?: return response
                if (operation == MediaDecodePolicy.EXIF) {
                    response.putInt(
                        "orientation",
                        dev.ipf.whitenoise.android.media.MediaPipeline
                            .readExifOrientationInDecoder(bytes),
                    )
                    response.putBoolean("success", true)
                    return response
                }
                if (operation == MediaDecodePolicy.INSPECT) {
                    PlatformMediaDecoder.inspect(bytes)?.let { info ->
                        response.putInt("width", info.width)
                        response.putInt("height", info.height)
                        response.putString("mime", info.mime)
                        response.putInt("orientation", info.orientation)
                        response.putBoolean("success", true)
                    }
                    return response
                }
                val bitmap =
                    when (operation) {
                        MediaDecodePolicy.DECODE -> PlatformMediaDecoder.decode(bytes, data.getInt("edge"), data.getBoolean("oriented"))
                        MediaDecodePolicy.SVG -> PlatformMediaDecoder.svg(bytes)
                        else -> null
                    } ?: return response
                bitmap.usePixels(output, data.getInt("edge"), response)
            }
        }
        return response
    }

    @Suppress("ReturnCount") // Validate the selected file range and operation before platform parsing.
    private fun mediaOperation(
        input: ParcelFileDescriptor,
        output: SharedMemory,
        data: Bundle,
        response: Bundle,
    ): Bundle {
        if (input.statSize !in 1..MAX_MEDIA_SOURCE_BYTES) return response
        if (data.getInt("operation") == MediaDecodePolicy.WAVEFORM) {
            val waveform =
                dev.ipf.whitenoise.android.audio.AudioWaveformExtractor
                    .decodeFromDescriptor(input.fileDescriptor)
                    ?: return response
            response.putFloatArray("waveform", waveform)
            response.putBoolean("success", true)
            return response
        }
        val edge = data.getInt("edge")
        if (MediaDecodePolicy.outputCapacity(edge) == null) return response
        val offset = data.getLong("offset")
        val length = data.getLong("length")
        if (offset < 0 || length <= 0 || offset > input.statSize - length) return response
        val result = probeMediaInDecoder(input, edge, data.getBoolean("oriented"), offset, length) ?: return response
        response.putInt("sourceWidth", result.width)
        response.putInt("sourceHeight", result.height)
        response.putLong("duration", result.durationMs)
        result.poster?.let {
            it.usePixels(output, edge, response)
            response.putBoolean("pixels", response.getBoolean("success"))
        }
        response.putBoolean("success", true)
        return response
    }

    private fun readInput(input: ParcelFileDescriptor): ByteArray? {
        val size = input.statSize
        if (size !in 1..MediaDecodePolicy.MAX_INPUT_BYTES.toLong()) return null
        return ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(input.fileDescriptor)).use { stream ->
            val bytes = ByteArray(size.toInt())
            var offset = 0
            while (offset < bytes.size) {
                val count = stream.read(bytes, offset, bytes.size - offset)
                if (count <= 0) return null
                offset += count
            }
            bytes.takeIf { stream.read() == -1 }
        }
    }

    private fun Bitmap.usePixels(
        output: SharedMemory,
        edge: Int,
        response: Bundle,
    ) {
        try {
            if (config != Bitmap.Config.ARGB_8888 || rowBytes != width * Int.SIZE_BYTES || !isPremultiplied) return
            val count = MediaDecodePolicy.pixelBytes(width, height, edge, output.size) ?: return
            val buffer = output.mapReadWrite()
            try {
                buffer.limit(count)
                copyPixelsToBuffer(buffer)
                response.putInt("width", width)
                response.putInt("height", height)
                response.putBoolean("success", true)
            } finally {
                SharedMemory.unmap(buffer)
            }
        } finally {
            recycle()
        }
    }

    private fun privateCanaryDenied(): Boolean {
        if (!dev.ipf.whitenoise.android.BuildConfig.DEBUG) return false
        return try {
            val descriptor =
                android.system.Os.open(
                    java.io.File(applicationInfo.dataDir, "files/media-containment-canary").path,
                    android.system.OsConstants.O_RDONLY,
                    0,
                )
            android.system.Os.close(descriptor)
            false
        } catch (error: android.system.ErrnoException) {
            error.errno == android.system.OsConstants.EACCES || error.errno == android.system.OsConstants.EPERM
        }
    }

    override fun onDestroy() {
        codec.shutdownNow()
        watchdog.shutdownNow()
        super.onDestroy()
        if (Process.isIsolated()) Process.killProcess(Process.myPid())
    }
}
