package dev.ipf.whitenoise.android.intelligence

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TextIntelligenceModelDeliveryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before fun reset() = runBlocking { TextIntelligenceModelStore(context).remove() }

    @Test fun optionalModelIsAbsentUntilVerifiedImportAndIsRemovable() =
        runBlocking {
            val store = TextIntelligenceModelStore(context)
            assertNull(store.installed())
            val bytes = "synthetic model fixture".toByteArray()
            val file = temporary.newFile("fixture.litertlm").apply { writeBytes(bytes) }
            val hash = hash(bytes)
            val model = store.import(Uri.fromFile(file), hash)
            assertEquals(hash, store.installed()?.sha256)
            assertEquals(hash, model.file.name.removeSuffix(".litertlm"))
            assertFalse(model.file.absolutePath.startsWith(context.cacheDir.absolutePath))
            store.remove()
            assertNull(store.installed())
            assertFalse(model.file.exists())
        }

    @Test fun wrongChecksumAndTruncatedImportPreservePreviousVerifiedVersion() =
        runBlocking {
            val store = TextIntelligenceModelStore(context)
            val original = "original fixture".toByteArray()
            val first = temporary.newFile("first").apply { writeBytes(original) }
            store.import(Uri.fromFile(first), hash(original))
            val truncated = temporary.newFile("truncated").apply { writeText("partial") }
            val failed = runCatching { store.import(Uri.fromFile(truncated), hash("complete fixture".toByteArray())) }
            assertEquals(true, failed.isFailure)
            assertEquals(hash(original), store.installed()?.sha256)
            val modelDirectory = java.io.File(context.noBackupFilesDir, "text_intelligence_models")
            assertFalse(modelDirectory.listFiles().orEmpty().any { it.name.endsWith(".partial") })
        }

    @Test fun alteredBytesCannotBecomeAReadyModel() =
        runBlocking {
            val store = TextIntelligenceModelStore(context)
            val bytes = "verified fixture".toByteArray()
            val file = temporary.newFile("source").apply { writeBytes(bytes) }
            val imported = store.import(Uri.fromFile(file), hash(bytes))
            imported.file.writeText("corrupted")
            assertNull(store.installed())
        }

    private fun hash(bytes: ByteArray) =
        MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
