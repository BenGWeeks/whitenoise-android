package dev.ipf.whitenoise.android.widget

import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dev.ipf.whitenoise.android.MainActivity
import dev.ipf.whitenoise.android.WhiteNoiseApplication
import dev.ipf.whitenoise.android.notifications.NotificationNavigation
import dev.ipf.whitenoise.android.notifications.NotificationTapTokens
import dev.ipf.whitenoise.android.notifications.NotificationTarget
import dev.ipf.whitenoise.android.notifications.NotificationTargetKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** A private foreground PendingIntent target validates native identities before opening a route. */
class ConversationWidgetTapActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            val root =
                Intent(this@ConversationWidgetTapActivity, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val state = (application as WhiteNoiseApplication).initializedAppState()
            val account = intent.getStringExtra("account")
            val group = intent.getStringExtra("group")
            val widget = intent.getIntExtra("widget", 0)
            val choice = ConversationWidgetConfiguration(this@ConversationWidgetTapActivity).read(widget)
            val keyguard = getSystemService(KeyguardManager::class.java)
            if (state != null &&
                account != null &&
                group != null &&
                choice != null &&
                (choice.allAccounts || account == choice.account) &&
                !state.appLockScreenVisible &&
                !state.requireAppUnlock &&
                !state.appUnlockEvaluationPending &&
                keyguard?.isDeviceLocked == false
            ) {
                val generation = state.runtimeGeneration
                val valid =
                    try {
                        withTimeout(4_000L) {
                            state.marmotIo {
                                listAccounts().any { it.label == account } &&
                                    groupDetails(account, group).group.groupIdHex == group
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        if (cancelled !is kotlinx.coroutines.TimeoutCancellationException) throw cancelled
                        false
                    } catch (_: Exception) {
                        false
                    }
                if (valid &&
                    !state.appLockScreenVisible &&
                    !state.requireAppUnlock &&
                    !state.appUnlockEvaluationPending &&
                    !keyguard.isDeviceLocked &&
                    state.runtimeGeneration == generation &&
                    ConversationWidgetConfiguration(this@ConversationWidgetTapActivity).read(widget) == choice
                ) {
                    val key = "widget:$widget:$account:$group"
                    NotificationNavigation.applyToIntent(
                        root,
                        NotificationTarget(account, group, null, NotificationTargetKind.MESSAGE),
                        key,
                        NotificationTapTokens.create(this@ConversationWidgetTapActivity).tokenFor(key),
                    )
                }
            }
            startActivity(root)
            finish()
        }
    }
}
