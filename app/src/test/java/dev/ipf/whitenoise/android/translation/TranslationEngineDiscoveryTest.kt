package dev.ipf.whitenoise.android.translation

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Unsupported and missing installations never reach a plaintext submission path. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 36])
class TranslationEngineDiscoveryTest {
    @Test
    fun absentEngineFailsClosedOnBothPackageManagerApiFamilies() {
        val failure =
            assertThrows(TranslationException::class.java) {
                discoverOfflineTranslator(ApplicationProvider.getApplicationContext())
            }
        assertEquals(TranslationFailure.MISSING_ENGINE, failure.failure)
    }
}
