package dev.ipf.whitenoise.android.widget

import android.app.KeyguardManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import dev.ipf.whitenoise.android.MainActivity
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.WhiteNoiseApplication
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class ConversationWidgetConfigureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val manager = AppWidgetManager.getInstance(this)
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID ||
            manager.getAppWidgetInfo(id)?.provider != ComponentName(this, ConversationWidgetProvider::class.java)
        ) {
            finish()
            return
        }
        val application = application as WhiteNoiseApplication
        val state = application.initializedAppState()
        val keyguard = getSystemService(KeyguardManager::class.java)

        fun unlocked(): Boolean =
            state != null &&
                !state.appLockScreenVisible &&
                !state.requireAppUnlock &&
                !state.appUnlockEvaluationPending &&
                keyguard?.isDeviceLocked == false
        setContent {
            WhiteNoiseTheme {
                ConversationWidgetConfigurationContent(
                    if (unlocked()) state?.accounts?.map { it.label }.orEmpty() else emptyList(),
                    onOpenApp = { startActivity(Intent(this, MainActivity::class.java)) },
                    onSave = { choice ->
                        lifecycleScope.launch {
                            val generation = state?.runtimeGeneration
                            val valid =
                                if (unlocked()) {
                                    runCatching {
                                        withTimeout(4_000L) {
                                            state?.marmotIo { listAccounts().any { it.label == choice.account } } ==
                                                true
                                        }
                                    }.getOrDefault(false)
                                } else {
                                    false
                                }
                            if (valid && unlocked() && generation == state?.runtimeGeneration) {
                                ConversationWidgetConfiguration(
                                    this@ConversationWidgetConfigureActivity,
                                ).save(id, choice)
                                application.applicationScope.launch { ConversationWidgets.refresh(application, state) }
                                setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                                finish()
                            }
                        }
                    },
                )
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (!isChangingConfigurations) finish()
    }
}

@Composable
internal fun ConversationWidgetConfigurationContent(
    accounts: List<String>,
    onOpenApp: () -> Unit,
    onSave: (ConversationWidgetChoice) -> Unit,
) {
    var account by remember { mutableStateOf(accounts.firstOrNull()) }
    var all by remember { mutableStateOf(false) }
    var unread by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.widget_title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.widget_privacy))
        accounts.forEach { item ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(account == item, onClick = { account = item })
                Text(item)
            }
        }
        WidgetChoiceCheckbox(stringResource(R.string.widget_all_accounts), all) { all = it }
        WidgetChoiceCheckbox(stringResource(R.string.widget_unread_only), unread) { unread = it }
        if (accounts.isEmpty()) Button(onClick = onOpenApp) { Text(stringResource(R.string.widget_open_app)) }
        Button(enabled = account in accounts, onClick = {
            account?.takeIf { it in accounts }?.let { onSave(ConversationWidgetChoice(it, all, false, unread)) }
        }) {
            Text(stringResource(R.string.save))
        }
    }
}

@Composable
private fun WidgetChoiceCheckbox(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onCheckedChange = onChange, modifier = Modifier.semantics { contentDescription = label })
        Text(label)
    }
}
