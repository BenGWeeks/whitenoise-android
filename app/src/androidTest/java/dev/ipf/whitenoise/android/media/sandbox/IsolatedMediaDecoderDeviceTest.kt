package dev.ipf.whitenoise.android.media.sandbox

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.whitenoise.android.PullRequestDeviceSmoke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Real Binder/descriptor/pixel transport. No account fixture, relay or destructive app reset. */
@RunWith(AndroidJUnit4::class)
@PullRequestDeviceSmoke
class IsolatedMediaDecoderDeviceTest {
    @Test
    fun stillPixelsCrossAnIsolatedUidWithoutNetworkOrRuntimeInitialization() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val canary = java.io.File(context.filesDir, "media-containment-canary")
        check(!canary.exists())
        canary.writeText("synthetic-private-canary")
        IsolatedMediaDecoder.install(context)
        var decoded: Bitmap? = null
        try {
            val result = requireNotNull(IsolatedMediaDecoder.decode(png(), 1))
            decoded = result
            assertEquals(1, result.width)
            assertEquals(Color.RED, result.getPixel(0, 0))
            val worker = requireNotNull(IsolatedMediaDecoder.lastIsolation)
            assertNotEquals(Process.myUid(), worker.uid)
            assertTrue(worker.isolated)
            assertFalse(worker.internetGranted)
            assertFalse(worker.runtimeInitialized)
            assertTrue(canary.isFile)
            assertTrue(
                "Private canary must stay inaccessible (errno=${worker.privateCanaryErrno})",
                worker.privateCanaryDenied,
            )
            val jpeg = requireNotNull(IsolatedMediaDecoder.decode(imageBytes(Bitmap.CompressFormat.JPEG, Color.RED), 2))
            try {
                assertTrue(Color.red(jpeg.getPixel(0, 0)) >= 250)
                assertTrue(Color.green(jpeg.getPixel(0, 0)) <= 5)
                assertEquals(255, Color.alpha(jpeg.getPixel(0, 0)))
            } finally {
                jpeg.recycle()
            }
            val transparent =
                requireNotNull(
                    IsolatedMediaDecoder.decode(imageBytes(Bitmap.CompressFormat.PNG, Color.argb(128, 255, 0, 0)), 2),
                )
            try {
                assertEquals(128, Color.alpha(transparent.getPixel(0, 0)))
                assertTrue(Color.red(transparent.getPixel(0, 0)) >= 254)
            } finally {
                transparent.recycle()
            }
        } finally {
            decoded?.recycle()
            canary.delete()
        }
    }

    @Test
    fun malformedInputCannotDisableHealthySubsequentImages() {
        IsolatedMediaDecoder.install(InstrumentationRegistry.getInstrumentation().targetContext)
        assertNull(IsolatedMediaDecoder.decode(byteArrayOf(1, 2, 3), 128))
        val decoded = IsolatedMediaDecoder.decode(png(), 128)
        assertNotNull(decoded)
        decoded?.recycle()
    }

    @Test
    fun accountResetRetiresThePriorWorkerAndKeepsRenderingAvailable() {
        IsolatedMediaDecoder.install(InstrumentationRegistry.getInstrumentation().targetContext)
        requireNotNull(IsolatedMediaDecoder.decode(png(), 128)).recycle()
        val prior = requireNotNull(IsolatedMediaDecoder.lastIsolation).uid
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val app =
                instrumentation.targetContext.applicationContext as
                    dev.ipf.whitenoise.android.WhiteNoiseApplication
            app.appState.clearCrossAccountCachesForTest()
        }
        requireNotNull(IsolatedMediaDecoder.decode(png(), 128)).recycle()
        assertNotEquals(prior, requireNotNull(IsolatedMediaDecoder.lastIsolation).uid)
    }

    @Test
    fun watchdogTerminatesOnlyTheWorkerAndPermitsVerifiedDeathRecovery() {
        IsolatedMediaDecoder.install(InstrumentationRegistry.getInstrumentation().targetContext)
        requireNotNull(IsolatedMediaDecoder.decode(png(), 128)).recycle()
        val prior = requireNotNull(IsolatedMediaDecoder.lastIsolation).uid
        val caller = Process.myPid()
        IsolatedMediaDecoder.exerciseWatchdogForTest()
        requireNotNull(IsolatedMediaDecoder.decode(png(), 128)).recycle()
        assertEquals(caller, Process.myPid())
        assertNotEquals(prior, requireNotNull(IsolatedMediaDecoder.lastIsolation).uid)
    }

    @Test
    fun svgVideoAndMetadataUseTheRealIsolatedTransport() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        IsolatedMediaDecoder.install(context)
        val info = requireNotNull(IsolatedMediaDecoder.inspect(png()))
        assertEquals(2, info.width)
        assertEquals(2, info.height)
        assertEquals("image/png", info.mime)
        assertEquals(1, IsolatedMediaDecoder.exif(png()))
        val svg =
            """<svg xmlns="http://www.w3.org/2000/svg" width="2" height="2">
            <rect width="2" height="2" fill="blue"/></svg>""".toByteArray()
        val raster = requireNotNull(IsolatedMediaDecoder.svg(svg))
        try {
            assertEquals(MediaDecodePolicy.SVG_EDGE, raster.width)
            assertEquals(Color.BLUE, raster.getPixel(raster.width / 2, raster.height / 2))
        } finally {
            raster.recycle()
        }
        val video = File.createTempFile("isolated-video", ".mp4", context.cacheDir)
        try {
            video.writeBytes(verifiedVideoFixture())
            val probe = requireNotNull(IsolatedMediaDecoder.probe(video, edge = 96, poster = true))
            assertEquals(320, probe.width)
            assertEquals(240, probe.height)
            assertTrue(probe.durationMs >= 9000L)
            val poster = requireNotNull(probe.poster)
            try {
                assertTrue(poster.width in 1..96 && poster.height in 1..96)
            } finally {
                poster.recycle()
            }
        } finally {
            video.delete()
        }
    }

    @Test
    fun audioWaveformAndDurationUseOnlyTheSelectedDescriptor() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        IsolatedMediaDecoder.install(context)
        val audio = File.createTempFile("isolated-waveform", ".wav", context.cacheDir)
        try {
            audio.writeBytes(pcmWaveFixture())
            val waveform = requireNotNull(IsolatedMediaDecoder.waveform(audio))
            assertEquals(dev.ipf.whitenoise.android.audio.AudioWaveformExtractor.BARS, waveform.size)
            assertTrue(waveform.all { it.isFinite() && it in 0f..1f })
            assertTrue(waveform.any { it > 0.05f })
            val probe = requireNotNull(IsolatedMediaDecoder.probe(audio, edge = 32, poster = false))
            assertNull(probe.poster)
            assertTrue(probe.durationMs in 100L..300L)
            val worker = requireNotNull(IsolatedMediaDecoder.lastIsolation)
            assertNotEquals(Process.myUid(), worker.uid)
            assertTrue(worker.isolated)
            assertFalse(worker.internetGranted)
            assertFalse(worker.runtimeInitialized)
        } finally {
            audio.delete()
        }
    }

    /** The existing Apache-2.0 AndroidX Media fixture is shared with the video regression suites. */
    private fun verifiedVideoFixture(): ByteArray {
        val encoded =
            InstrumentationRegistry
                .getInstrumentation()
                .context.assets
                .open("silent-black-10s.mp4.b64")
                .bufferedReader()
                .use { it.readText() }
        val decoded = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
        val digest =
            MessageDigest
                .getInstance("SHA-256")
                .digest(decoded)
                .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        assertEquals("83fbcd994ece32535285a0ea6505c681cb96c471736308316eabda90cced9f51", digest)
        return decoded
    }

    /** A local 200ms, 16kHz mono PCM signal; no microphone, account or network fixture. */
    private fun pcmWaveFixture(): ByteArray {
        val samples = 3200
        val dataBytes = samples * 2
        return ByteBuffer
            .allocate(44 + dataBytes)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                put("RIFF".toByteArray(Charsets.US_ASCII))
                putInt(36 + dataBytes)
                put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
                putInt(16)
                putShort(1.toShort())
                putShort(1.toShort())
                putInt(16000)
                putInt(32000)
                putShort(2.toShort())
                putShort(16.toShort())
                put("data".toByteArray(Charsets.US_ASCII))
                putInt(dataBytes)
                repeat(samples) { putShort((if (it % 2 == 0) 8000 else -8000).toShort()) }
            }.array()
    }

    private fun png(): ByteArray = imageBytes(Bitmap.CompressFormat.PNG, Color.RED)

    private fun imageBytes(
        format: Bitmap.CompressFormat,
        color: Int,
    ): ByteArray {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(color)
            ByteArrayOutputStream().also { bitmap.compress(format, 100, it) }.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }
}
