package dev.ipf.whitenoise.android.intelligence

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.LogSeverity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withTimeoutOrNull

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
            MAX_INPUT_CHARACTERS,
        )
    }

    override suspend fun transform(
        operation: TextIntelligenceOperation,
        text: String,
    ): String {
        var failure = TextIntelligenceFailure.TIMEOUT
        val response =
            try {
                withTimeoutOrNull(INTELLIGENCE_REQUEST_TIMEOUT_MS) { transformWithModel(operation, text) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: TextIntelligenceException) {
                failure = error.failure
                null
            } catch (_: Exception) {
                failure = TextIntelligenceFailure.ENGINE
                null
            } catch (_: LinkageError) {
                failure = TextIntelligenceFailure.UNAVAILABLE
                null
            }
        return response ?: throw TextIntelligenceException(failure)
    }

    private suspend fun transformWithModel(
        operation: TextIntelligenceOperation,
        text: String,
    ): String {
        val model = selected ?: throw TextIntelligenceException(TextIntelligenceFailure.MODEL_REQUIRED)
        return models.useModel(model.sha256) { installed ->
            // Native diagnostic messages must not expose private prompts, even on a failed request.
            Engine.setNativeMinLogSeverity(LogSeverity.INFINITY)
            val config = EngineConfig(installed.file.absolutePath, Backend.CPU(), maxNumTokens = MAX_MODEL_TOKENS)
            Engine(config).use { engine ->
                engine.initialize()
                engine
                    .createConversation(
                        ConversationConfig(
                            automaticToolCalling = false,
                            maxOutputToken = MAX_OUTPUT_TOKENS,
                        ),
                    ).use { conversation ->
                        val output = StringBuilder()
                        try {
                            val prompt = instruction(operation) + "\n\n" + text
                            conversation.sendMessageAsync(prompt).collect { chunk ->
                                val piece = chunk.toString()
                                if (output.length + piece.length > MAX_INTELLIGENCE_OUTPUT) {
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

    private companion object {
        const val MAX_INPUT_CHARACTERS = 4_096
        const val MAX_MODEL_TOKENS = 2_048
        const val MAX_OUTPUT_TOKENS = 512
    }
}
