package dev.ipf.whitenoise.android.widget

import android.content.Context
import android.widget.TextView
import dev.ipf.whitenoise.android.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConversationWidgetAndroidTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before fun reset() {
        context
            .getSharedPreferences("conversation_widget_choices", 0)
            .edit()
            .clear()
            .commit()
    }

    @Test fun configurationRecreationRetainsOnlyPlatformChoices() {
        val choice = ConversationWidgetChoice("account", unreadOnly = true)
        ConversationWidgetConfiguration(context).save(7, choice)
        assertEquals(choice, ConversationWidgetConfiguration(context).read(7))
        assertEquals(
            setOf("7:account", "7:all", "7:previews", "7:unread"),
            context.getSharedPreferences("conversation_widget_choices", 0).all.keys,
        )
    }

    @Test fun widgetDeletionRemovesAllConfiguration() {
        ConversationWidgetConfiguration(context).save(7, ConversationWidgetChoice("account", previews = true))
        ConversationWidgetProvider().onDeleted(context, intArrayOf(7))
        assertNull(ConversationWidgetConfiguration(context).read(7))
    }

    @Test fun unavailableWidgetContainsGenericCopyInsteadOfLastSensitiveRows() {
        val views = conversationWidgetViews(context, 7, emptyList(), null).apply(context, null)
        assertEquals(
            context.getString(R.string.widget_unavailable),
            views.findViewById<TextView>(R.id.widget_row_title).text.toString(),
        )
        assertEquals(
            context.getString(R.string.widget_open_app),
            views.findViewById<TextView>(R.id.widget_row_subtitle).text.toString(),
        )
    }
}
