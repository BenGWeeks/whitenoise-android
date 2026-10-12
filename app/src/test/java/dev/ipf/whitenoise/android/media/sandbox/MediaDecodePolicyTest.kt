package dev.ipf.whitenoise.android.media.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaDecodePolicyTest {
    @Test
    fun pixelCapacityRejectsOverflowAndWorkerChosenDimensions() {
        assertEquals(1048576, MediaDecodePolicy.outputCapacity(512))
        assertNull(MediaDecodePolicy.outputCapacity(Int.MAX_VALUE))
        assertNull(MediaDecodePolicy.outputCapacity(0))
        assertNull(MediaDecodePolicy.pixelBytes(Int.MAX_VALUE, 1, 512, Int.MAX_VALUE))
        assertNull(MediaDecodePolicy.pixelBytes(513, 512, 512, 1048576))
        assertNull(MediaDecodePolicy.pixelBytes(512, 512, 512, 1048575))
        assertEquals(1048576, MediaDecodePolicy.pixelBytes(512, 512, 512, 1048576))
    }

    @Test
    fun sourceCeilingsDoNotDependOnEncodedFileLength() {
        assertTrue(MediaDecodePolicy.sourceAllowed(4096, 4096))
        assertFalse(MediaDecodePolicy.sourceAllowed(32769, 1))
        assertFalse(MediaDecodePolicy.sourceAllowed(32768, 32768))
        assertFalse(MediaDecodePolicy.sourceAllowed(-1, 1))
    }
}
