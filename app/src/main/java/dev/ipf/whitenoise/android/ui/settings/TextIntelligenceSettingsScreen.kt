@file:Suppress("FunctionNaming") // Unit-returning Compose controls use the established PascalCase convention.

package dev.ipf.whitenoise.android.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.intelligence.TextIntelligenceModelStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun TextIntelligenceSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state =
        remember(context.applicationContext) {
            TextModelSettingsState(TextIntelligenceModelStore(context.applicationContext))
        }
    LaunchedEffect(state) { state.load() }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val expected = state.pendingChecksum
            state.pendingChecksum = null
            if (uri != null && expected != null) scope.launch { state.import(uri, expected) }
        }
    SettingsScaffold(title = stringResource(R.string.intelligence_title), onBack = onBack) {
        SettingsList {
            item {
                textModelSettingsContent(
                    state,
                    onImport = {
                        state.pendingChecksum = state.checksum
                        picker.launch(arrayOf("application/octet-stream", "application/*"))
                    },
                    onRemove = { scope.launch { state.remove() } },
                )
            }
        }
    }
}

private class TextModelSettingsState(
    private val models: TextIntelligenceModelStore,
) {
    var checksum by mutableStateOf("")
    var pendingChecksum by mutableStateOf<String?>(null)
    var installedChecksum by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
    var failed by mutableStateOf(false)

    suspend fun load() {
        try {
            installedChecksum = models.installed()?.sha256
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        }
    }

    suspend fun import(
        uri: Uri,
        expected: String,
    ) {
        busy = true
        failed = false
        try {
            installedChecksum = models.import(uri, expected).sha256
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        } finally {
            busy = false
        }
    }

    suspend fun remove() {
        busy = true
        failed = false
        try {
            models.remove()
            installedChecksum = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        } finally {
            busy = false
        }
    }
}

@Composable
private fun textModelSettingsContent(
    state: TextModelSettingsState,
    onImport: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.intelligence_local_only))
        Text(stringResource(R.string.intelligence_model_help))
        Text(stringResource(R.string.intelligence_aicore_help))
        state.installedChecksum?.let {
            Text(stringResource(R.string.intelligence_model_ready))
            Text(it)
        }
        OutlinedTextField(
            state.checksum,
            { value -> state.checksum = value.take(MODEL_CHECKSUM_LENGTH).lowercase() },
            label = { Text(stringResource(R.string.intelligence_checksum)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
        )
        TextButton(enabled = !state.busy && state.checksum.matches(Regex("[a-f0-9]{64}")), onClick = onImport) {
            Text(stringResource(R.string.intelligence_import_model))
        }
        TextButton(enabled = !state.busy && state.installedChecksum != null, onClick = onRemove) {
            Text(stringResource(R.string.intelligence_remove_model))
        }
        if (state.busy) Text(stringResource(R.string.intelligence_model_verifying))
        if (state.failed) Text(stringResource(R.string.intelligence_model_import_failed))
    }
}

private const val MODEL_CHECKSUM_LENGTH = 64
