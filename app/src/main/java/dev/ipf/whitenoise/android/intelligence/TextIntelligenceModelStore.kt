package dev.ipf.whitenoise.android.intelligence

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID

internal data class TextIntelligenceModel(
    val file: File,
    val sha256: String,
)

/** Optional user-imported model bytes only; draft text and inference responses never enter this store. */
internal class TextIntelligenceModelStore(
    private val context: Context,
) {
    private val directory = File(context.noBackupFilesDir, "text_intelligence_models")
    private val preferences = context.getSharedPreferences("text_intelligence_model_metadata", Context.MODE_PRIVATE)

    suspend fun installed(): TextIntelligenceModel? =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                removeAbandonedImports()
                val hash = runCatching { preferences.getString("sha256", null) }.getOrNull() ?: return@withContext null
                if (!hash.matches(Regex("[a-f0-9]{64}"))) return@withContext null
                val file = File(directory, "$hash.litertlm")
                if (!file.isFile ||
                    Files.isSymbolicLink(file.toPath()) ||
                    file.canonicalFile.parentFile != directory.canonicalFile ||
                    digest(file) != hash
                ) {
                    return@withContext null
                }
                TextIntelligenceModel(file, hash)
            }
        }

    /** The independently obtained publisher checksum is required before bytes can become an installed model. */
    suspend fun import(
        uri: Uri,
        expectedSha256: String,
    ): TextIntelligenceModel =
        mutex.withLock {
            require(expectedSha256.matches(Regex("[a-f0-9]{64}")))
            withContext(Dispatchers.IO) {
                check(directory.isDirectory || directory.mkdirs())
                removeAbandonedImports()
                val temporary = File(directory, "${UUID.randomUUID()}.partial")
                try {
                    val hash = MessageDigest.getInstance("SHA-256")
                    var bytes = 0L
                    checkNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                        temporary.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                bytes += count
                                check(bytes <= MAX_MODEL_BYTES && directory.usableSpace > RESERVED_MODEL_SPACE)
                                hash.update(buffer, 0, count)
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                    check(bytes > 0 && hashHex(hash.digest()) == expectedSha256)
                    val destination = File(directory, "$expectedSha256.litertlm")
                    check(temporary.renameTo(destination))
                    val previous = preferences.getString("sha256", null)
                    check(preferences.edit().putString("sha256", expectedSha256).commit())
                    previous
                        ?.takeIf { it != expectedSha256 && it.matches(Regex("[a-f0-9]{64}")) }
                        ?.let { File(directory, "$it.litertlm").delete() }
                    TextIntelligenceModel(destination, expectedSha256)
                } finally {
                    temporary.delete()
                }
            }
        }

    suspend fun remove() =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                check(preferences.edit().clear().commit())
                directory
                    .listFiles()
                    ?.filter { it.isFile && it.canonicalFile.parentFile == directory.canonicalFile }
                    ?.forEach { check(it.delete()) }
            }
        }

    suspend fun <T> useModel(
        expectedSha256: String,
        block: suspend (TextIntelligenceModel) -> T,
    ): T =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val current = preferences.getString("sha256", null)
                val file = File(directory, "$expectedSha256.litertlm")
                if (current != expectedSha256 ||
                    !file.isFile ||
                    Files.isSymbolicLink(file.toPath()) ||
                    file.canonicalFile.parentFile != directory.canonicalFile ||
                    digest(file) != expectedSha256
                ) {
                    throw TextIntelligenceException(TextIntelligenceFailure.MODEL_REQUIRED)
                }
                block(TextIntelligenceModel(file, expectedSha256))
            }
        }

    private suspend fun digest(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                hash.update(buffer, 0, count)
            }
        }
        return hashHex(hash.digest())
    }

    /** The model mutex guarantees these temporary imports have no live writer. */
    private fun removeAbandonedImports() {
        directory
            .listFiles()
            ?.filter {
                it.name.endsWith(".partial") &&
                    it.isFile &&
                    !Files.isSymbolicLink(it.toPath()) &&
                    it.canonicalFile.parentFile == directory.canonicalFile
            }?.forEach { check(it.delete()) }
    }

    private companion object {
        val mutex = Mutex()
        const val MAX_MODEL_BYTES = 2L * 1024 * 1024 * 1024
        const val RESERVED_MODEL_SPACE = 128L * 1024 * 1024

        fun hashHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
    }
}
