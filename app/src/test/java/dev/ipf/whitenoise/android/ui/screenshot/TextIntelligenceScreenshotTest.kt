package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.intelligence.ComposerTextAssistanceDialog
import dev.ipf.whitenoise.android.intelligence.TextIntelligenceAvailability
import dev.ipf.whitenoise.android.intelligence.TextIntelligenceCapability
import dev.ipf.whitenoise.android.intelligence.TextIntelligenceException
import dev.ipf.whitenoise.android.intelligence.TextIntelligenceExecution
import dev.ipf.whitenoise.android.intelligence.TextIntelligenceFailure
import dev.ipf.whitenoise.android.intelligence.TextIntelligenceOperation
import dev.ipf.whitenoise.android.intelligence.TextIntelligenceProvider
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerTextState
import dev.ipf.whitenoise.android.ui.settings.TextIntelligenceSettingsScreen
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w400dp-h1000dp-mdpi")
class TextIntelligenceScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun modelSetupLight() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) { TextIntelligenceSettingsScreen {} }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage("src/test/snapshots/text_assistance_model_setup_light.png")
    }

    @Test fun suggestionPreviewDark() = capture("text_assistance_preview_dark.png", dark = true, complete = true)

    @Test fun providerFailureLight() = capture("text_assistance_failure_light.png", complete = true, fail = true)

    @Test fun wholeDraftLight() = capture("text_assistance_draft_light.png")

    @Test fun selectedDark() = capture("text_assistance_selection_dark.png", selected = true, dark = true)

    @Test fun largeRtl() = capture("text_assistance_large_rtl.png", scale = 2f, rtl = true)

    private fun capture(
        name: String,
        selected: Boolean = false,
        dark: Boolean = false,
        scale: Float = 1f,
        rtl: Boolean = false,
        complete: Boolean = false,
        fail: Boolean = false,
    ) {
        val state =
            ComposerTextState(
                TextFieldValue(
                    "A short draft for review.",
                    if (selected) TextRange(2, 13) else TextRange.Zero,
                ),
            )
        val token = state.assistanceToken()
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(1f, scale),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark) {
                    ComposerTextAssistanceDialog(token, {
                        state.isCurrentAssistance(
                            token,
                        )
                    }, {}, {}, providerFactory = {
                        object : TextIntelligenceProvider {
                            override suspend fun capability(operation: TextIntelligenceOperation) =
                                TextIntelligenceCapability(
                                    "fixture",
                                    TextIntelligenceOperation.entries.toSet(),
                                    TextIntelligenceExecution.LOCAL,
                                    TextIntelligenceAvailability.READY,
                                    4096,
                                )

                            override suspend fun transform(
                                operation: TextIntelligenceOperation,
                                text: String,
                            ): String {
                                if (fail) throw TextIntelligenceException(TextIntelligenceFailure.MODEL_REQUIRED)
                                return "A clearer draft to review."
                            }
                        }
                    })
                }
            }
        }
        composeRule.waitForIdle()
        if (complete) {
            val context: android.content.Context = ApplicationProvider.getApplicationContext()
            composeRule.onNodeWithText(context.getString(R.string.intelligence_proofread)).performClick()
            composeRule.waitForIdle()
        }
        composeRule.onRoot().captureRoboImage("src/test/snapshots/$name")
    }
}
