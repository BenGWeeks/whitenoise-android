package dev.ipf.whitenoise.android.intelligence

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerTextState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en")
class ComposerBarTextIntelligenceTest {
    @get:Rule val composeRule = createComposeRule()
    private val context: android.content.Context = ApplicationProvider.getApplicationContext()

    @Test fun selectedRangeIsPreviewedAndOnlyExplicitApplyChangesTheDraft() {
        val state = ComposerTextState(TextFieldValue("prefix draft suffix", TextRange(7, 12)))
        val token = state.assistanceToken()
        var input: String? = null
        var applied: TextFieldValue? = null
        composeRule.setContent {
            WhiteNoiseTheme {
                ComposerTextAssistanceDialog(
                    token,
                    { state.isCurrentAssistance(token) },
                    { applied = it },
                    {},
                    providerFactory = {
                        fakeProvider { text ->
                            input = text
                            "corrected"
                        }
                    },
                )
            }
        }
        composeRule.onNodeWithText(context.getString(R.string.intelligence_proofread)).performClick()
        composeRule.waitForIdle()
        assertEquals("draft", input)
        assertNull(applied)
        assertEquals("prefix draft suffix", state.valueState.value.text)
        composeRule.onNodeWithText(context.getString(R.string.intelligence_apply)).performClick()
        assertEquals("prefix corrected suffix", applied?.text)
    }

    @Test fun newerUserEditDisablesApply() {
        val state = ComposerTextState(TextFieldValue("draft"))
        val token = state.assistanceToken()
        composeRule.setContent {
            WhiteNoiseTheme {
                ComposerTextAssistanceDialog(
                    token,
                    { state.isCurrentAssistance(token) },
                    {},
                    {},
                    providerFactory = { fakeProvider { "suggestion" } },
                )
            }
        }
        composeRule.onNodeWithText(context.getString(R.string.intelligence_rewrite)).performClick()
        composeRule.waitForIdle()
        composeRule.runOnIdle { state.updateValue(TextFieldValue("newer edit")) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(context.getString(R.string.intelligence_apply)).assertDoesNotExist()
        assertEquals("newer edit", state.valueState.value.text)
    }

    private fun fakeProvider(transform: (String) -> String) =
        object : TextIntelligenceProvider {
            override suspend fun capability(operation: TextIntelligenceOperation) =
                TextIntelligenceCapability(
                    "local fixture",
                    TextIntelligenceOperation.entries.toSet(),
                    TextIntelligenceExecution.LOCAL,
                    TextIntelligenceAvailability.READY,
                    4_096,
                )

            override suspend fun transform(
                operation: TextIntelligenceOperation,
                text: String,
            ): String = transform(text)
        }
}
