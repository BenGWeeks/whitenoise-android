package dev.ipf.whitenoise.android.widget

import android.content.Context

internal data class ConversationWidgetChoice(
    val account: String,
    val allAccounts: Boolean = false,
    val previews: Boolean = false,
    val unreadOnly: Boolean = false,
)

/** Only launcher/UI configuration is durable. Rows are rebuilt from current native projections. */
internal class ConversationWidgetConfiguration(
    context: Context,
) {
    private val preferences =
        context.applicationContext.getSharedPreferences(
            "conversation_widget_choices",
            Context.MODE_PRIVATE,
        )

    fun read(id: Int): ConversationWidgetChoice? =
        runCatching {
            val account = preferences.getString("$id:account", null)?.takeIf { it.isNotBlank() } ?: return null
            ConversationWidgetChoice(
                account,
                preferences.getBoolean("$id:all", false),
                preferences.getBoolean("$id:previews", false),
                preferences.getBoolean("$id:unread", false),
            )
        }.getOrNull()

    fun save(
        id: Int,
        choice: ConversationWidgetChoice,
    ) {
        require(id > 0 && choice.account.isNotBlank())
        preferences
            .edit()
            .putString("$id:account", choice.account)
            .putBoolean("$id:all", choice.allAccounts)
            .putBoolean("$id:previews", choice.previews)
            .putBoolean("$id:unread", choice.unreadOnly)
            .apply()
    }

    fun remove(id: Int) {
        preferences
            .edit()
            .remove("$id:account")
            .remove("$id:all")
            .remove("$id:previews")
            .remove("$id:unread")
            .apply()
    }
}

internal data class ConversationWidgetRow(
    val account: String,
    val group: String,
    val title: String,
    val unread: Boolean,
    val preview: String? = null,
    val activity: ULong = 0uL,
)

internal fun selectConversationWidgetRows(
    choice: ConversationWidgetChoice,
    accounts: Set<String>,
    unlocked: Boolean,
    rows: List<ConversationWidgetRow>,
    limit: Int,
): List<ConversationWidgetRow> {
    if (!unlocked || choice.account !in accounts) return emptyList()
    return rows
        .asSequence()
        .filter { it.account in accounts && (choice.allAccounts || it.account == choice.account) }
        .filter { !choice.unreadOnly || it.unread }
        .distinctBy { it.account to it.group }
        .sortedByDescending { it.activity }
        .take(limit.coerceIn(1, 8))
        .map { if (choice.previews) it else it.copy(preview = null) }
        .toList()
}
