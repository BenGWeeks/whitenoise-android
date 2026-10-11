package dev.ipf.whitenoise.android.intelligence

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.LogSeverity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withTimeout

internal class LiteRtTextIntelligenceProvider(
    private val models: TextIntelligenceModelStore,
) : TextIntelligenceProvider {
    private var selected: TextIntelligenceModel? = null

    override suspend fun capability(operation: TextIntelligenceOperation): TextIntelligenceCapability {
        selected = models.installed()
        return TextIntelligenceCapability(
            "litert:${selected?.sha256.orEmpty()}",
            TextIntelligenceOperation.entries.toSet(),
            TextIntelligenceExecution.LOCAL,
            if (selected == null) TextIntelligenceAvailability.MODEL_REQUIRED else TextIntelligenceAvailability.READY,
            4_096,
        )
    }

    override suspend fun transform(
        operation: TextIntelligenceOperation,
        text: String,
    ): String {
        val model = selected ?: throw TextIntelligenceException(TextIntelligenceFailure.MODEL_REQUIRED)
        return try {
            withTimeout(60_000L) {
                models.useModel(model.sha256) { installed ->
                    // Native diagnostic messages must not expose private prompts, even on a failed request.
                    Engine.setNativeMinLogSeverity(LogSeverity.INFINITY)
                    val config = EngineConfig(installed.file.absolutePath, Backend.CPU(), maxNumTokens = 2_048)
                    Engine(config).use { engine ->
                        engine.initialize()
                        engine
                            .createConversation(ConversationConfig(automaticToolCalling = false, maxOutputToken = 512))
                            .use { conversation ->
                                val output = StringBuilder()
                                try {
                                    val prompt = instruction(operation) + "\n\n" + text
                                    conversation.sendMessageAsync(prompt).collect { chunk ->
                                        val piece = chunk.toString()
                                        if (output.length + piece.length > 8_192) {
                                            throw TextIntelligenceException(TextIntelligenceFailure.ENGINE)
                                        }
                                        output.append(piece)
                                    }
                                    output.toString().trim()
                                } finally {
                                    conversation.cancelProcess()
                                }
                            }
                    }
                }
            }
        } catch (_: TimeoutCancellationException) {
            throw TextIntelligenceException(TextIntelligenceFailure.TIMEOUT)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: TextIntelligenceException) {
            throw failure
        } catch (_: Exception) {
            throw TextIntelligenceException(TextIntelligenceFailure.ENGINE)
        } catch (_: LinkageError) {
            throw TextIntelligenceException(TextIntelligenceFailure.UNAVAILABLE)
        }
    }

    private fun instruction(operation: TextIntelligenceOperation): String =
        when (operation) {
            TextIntelligenceOperation.PROOFREAD ->
                """
                Correct spelling and grammar. Preserve meaning and language. Return only corrected text.
                """.trimIndent()
            TextIntelligenceOperation.REWRITE ->
                """
                Rewrite clearly. Preserve meaning and language. Return only replacement text.
                """.trimIndent()
            TextIntelligenceOperation.SUMMARIZE ->
                """
                Summarize concisely in the original language. Return only the summary.
                """.trimIndent()
        }
}
