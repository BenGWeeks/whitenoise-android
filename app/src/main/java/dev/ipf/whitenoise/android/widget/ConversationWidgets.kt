package dev.ipf.whitenoise.android.widget

import android.app.KeyguardManager
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.runtime.snapshotFlow
import androidx.core.content.ContextCompat
import dev.ipf.marmotkit.ChatListViewFfi
import dev.ipf.marmotkit.ChatListWindowSubscription
import dev.ipf.whitenoise.android.WhiteNoiseApplication
import dev.ipf.whitenoise.android.core.ProfileSanitizer
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicLong

internal object ConversationWidgets {
    private val refreshEpoch = AtomicLong()

    fun observe(
        application: WhiteNoiseApplication,
        state: WhiteNoiseAppState,
    ) {
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) {
                    redact(context)
                    if (intent.action == Intent.ACTION_USER_PRESENT) {
                        application.applicationScope.launch { refresh(application, state) }
                    }
                }
            }
        ContextCompat.registerReceiver(
            application,
            receiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        application.applicationScope.launch(Dispatchers.Main) {
            snapshotFlow {
                listOf(
                    state.runtimeGeneration,
                    state.appLockScreenVisible,
                    state.requireAppUnlock,
                    state.appUnlockEvaluationPending,
                    state.accounts,
                    state.accountUnreadCounts,
                    state.chatListItems,
                    state.activeAccountRef,
                )
            }.collectLatest { refresh(application, state) }
        }
    }

    fun redact(context: Context) {
        refreshEpoch.incrementAndGet()
        val manager = AppWidgetManager.getInstance(context)
        manager.getAppWidgetIds(ComponentName(context, ConversationWidgetProvider::class.java)).forEach { id ->
            manager.updateAppWidget(id, conversationWidgetViews(context, id, emptyList(), null))
        }
    }

    suspend fun refresh(
        context: Context,
        state: WhiteNoiseAppState?,
    ) = withContext(Dispatchers.Main) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, ConversationWidgetProvider::class.java))
        val epoch = refreshEpoch.incrementAndGet()
        ids.forEach { id -> manager.updateAppWidget(id, conversationWidgetViews(context, id, emptyList(), null)) }
        if (ids.isEmpty()) return@withContext
        val store = ConversationWidgetConfiguration(context)
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val generation = state?.runtimeGeneration
        val accounts =
            if (state != null &&
                !state.requireAppUnlock &&
                !state.appUnlockEvaluationPending &&
                !state.appLockScreenVisible &&
                keyguard?.isDeviceLocked == false
            ) {
                try {
                    withTimeout(4_000L) { state.marmotIo { listAccounts().map { it.label }.toSet() } }
                } catch (
                    _: TimeoutCancellationException,
                ) {
                    null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }
        for (id in ids) {
            val choice = store.read(id) ?: continue
            if (state == null ||
                state.requireAppUnlock ||
                state.appUnlockEvaluationPending ||
                state.appLockScreenVisible ||
                keyguard?.isDeviceLocked != false
            ) {
                continue
            }
            if (accounts == null) continue
            if (choice.account !in accounts) {
                store.remove(id)
                continue
            }
            val result =
                try {
                    withTimeout(4_000L) { loadRows(state, choice, accounts) }
                } catch (
                    _: TimeoutCancellationException,
                ) {
                    continue
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    continue
                }
            currentCoroutineContext().ensureActive()
            if (refreshEpoch.get() != epoch ||
                state.runtimeGeneration != generation ||
                state.appLockScreenVisible ||
                state.requireAppUnlock ||
                state.appUnlockEvaluationPending ||
                keyguard.isDeviceLocked ||
                state.accounts.map { it.label }.toSet() != accounts ||
                store.read(id) != choice
            ) {
                continue
            }
            val height = manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 160)
            val rows = selectConversationWidgetRows(choice, accounts, true, result, height / 52)
            manager.updateAppWidget(id, conversationWidgetViews(context, id, rows, choice))
        }
    }

    private suspend fun loadRows(
        state: WhiteNoiseAppState,
        choice: ConversationWidgetChoice,
        accounts: Set<String>,
    ): List<ConversationWidgetRow> {
        val selected = if (choice.allAccounts) accounts.take(8) else listOf(choice.account)
        return selected.flatMap { account ->
            var handle: ChatListWindowSubscription? = null
            try {
                state.marmotIo {
                    handle = openChatListWindow(account, ChatListViewFfi.CHATS, 50u)
                    checkNotNull(handle?.snapshot()).rows.take(50).map { presented ->
                        val row = presented.row
                        ConversationWidgetRow(
                            account,
                            row.groupIdHex,
                            ProfileSanitizer.displayName(row.groupName).orEmpty(),
                            row.unreadCount > 0uL,
                            // Launcher hosts retain RemoteViews after process death; never hand them plaintext.
                            preview = null,
                            activity = row.activitySortAt,
                        )
                    }
                }
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { handle?.close() }
            }
        }
    }
}
