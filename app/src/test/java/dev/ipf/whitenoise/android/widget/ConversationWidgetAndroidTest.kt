package dev.ipf.whitenoise.android.widget

import android.content.Context
import android.widget.TextView
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.SecureStoreKeyProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConversationWidgetAndroidTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before fun reset() {
        ConversationWidgetConfiguration.installKeyProvider(
            object : SecureStoreKeyProvider {
                override fun secretKey(): SecretKey = SecretKeySpec(ByteArray(32) { 7 }, "AES")
            },
        )
        context
            .getSharedPreferences("conversation_widget_choices.keystore", 0)
            .edit()
            .clear()
            .commit()
    }

    @After fun restoreKeystore() {
        ConversationWidgetConfiguration.installKeyProvider(null)
    }

    @Test fun configurationRecreationRetainsOnlyPlatformChoices() {
        val choice = ConversationWidgetChoice("account", unreadOnly = true)
        assertTrue(ConversationWidgetConfiguration(context).save(7, choice))
        assertEquals(choice, ConversationWidgetConfiguration(context).read(7))
        val persisted = context.getSharedPreferences("conversation_widget_choices.keystore", 0).all
        assertTrue(persisted.isNotEmpty())
        assertTrue(persisted.values.none { "account" in it.toString() })
        assertTrue(context.getSharedPreferences("conversation_widget_choices", 0).all.isEmpty())
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
