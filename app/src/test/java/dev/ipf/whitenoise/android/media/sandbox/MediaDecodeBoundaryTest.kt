package dev.ipf.whitenoise.android.media.sandbox

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MediaDecodeBoundaryTest {
    @Test
    fun missingTransportFailsClosedWithoutCallingLocalCodec() {
        IsolatedMediaDecoder.installTestTransport(null)
        assertNull(IsolatedMediaDecoder.decode(png(), 128))
    }

    @Test
    fun explicitFixturePreservesPixelsAndBounds() {
        IsolatedMediaDecoder.installTestTransport(TestMediaDecodeTransport)
        val result = requireNotNull(IsolatedMediaDecoder.decode(png(), 1))
        try {
            assertEquals(1, result.width)
            assertEquals(Color.RED, result.getPixel(0, 0))
        } finally {
            result.recycle()
        }
    }

    @Test
    fun malformedBytesAndInvalidTargetsStayUnavailable() {
        IsolatedMediaDecoder.installTestTransport(TestMediaDecodeTransport)
        assertNull(IsolatedMediaDecoder.decode(byteArrayOf(1, 2, 3), 128))
        assertNull(IsolatedMediaDecoder.decode(png(), Int.MAX_VALUE))
    }

    private fun png(): ByteArray {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(Color.RED)
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }
}
