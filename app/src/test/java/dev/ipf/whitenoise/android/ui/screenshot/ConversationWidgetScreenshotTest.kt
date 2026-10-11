package dev.ipf.whitenoise.android.ui.screenshot

import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import dev.ipf.whitenoise.android.widget.ConversationWidgetChoice
import dev.ipf.whitenoise.android.widget.ConversationWidgetConfigurationContent
import dev.ipf.whitenoise.android.widget.ConversationWidgetRow
import dev.ipf.whitenoise.android.widget.conversationWidgetViews
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w400dp-h1000dp-mdpi")
class ConversationWidgetScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun configurationDefaults() {
        composeRule.setContent {
            WhiteNoiseTheme {
                Box(Modifier.size(360.dp, 700.dp).testTag("widget-fixture")) {
                    ConversationWidgetConfigurationContent(listOf("Alice", "Bob"), {}, {})
                }
            }
        }
        capture("widget_configuration_defaults.png")
    }

    @Test fun redactedNarrow() =
        views(
            "widget_redacted_narrow.png",
            listOf(
                ConversationWidgetRow("Alice", "first", "Family", true),
                ConversationWidgetRow("Alice", "second", "Friends", false),
            ),
            ConversationWidgetChoice("Alice"),
        )

    @Test fun unavailableRtl() = views("widget_unavailable_rtl.png", emptyList(), null, rtl = true)

    private fun views(
        name: String,
        rows: List<ConversationWidgetRow>,
        choice: ConversationWidgetChoice?,
        rtl: Boolean = false,
    ) {
        val context: android.content.Context = ApplicationProvider.getApplicationContext()
        composeRule.setContent {
            Box(Modifier.size(220.dp, 260.dp).testTag("widget-fixture")) {
                AndroidView(factory = {
                    conversationWidgetViews(context, 7, rows, choice)
                        .apply(context, null)
                        .apply { layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR }
                })
            }
        }
        capture(name)
    }

    private fun capture(name: String) {
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("widget-fixture").captureRoboImage("src/test/snapshots/$name")
    }
}
