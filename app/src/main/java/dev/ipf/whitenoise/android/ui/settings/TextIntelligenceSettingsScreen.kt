package dev.ipf.whitenoise.android.ui.settings

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
    val models = remember(context.applicationContext) { TextIntelligenceModelStore(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var checksum by remember { mutableStateOf("") }
    var pendingChecksum by remember { mutableStateOf<String?>(null) }
    var installedChecksum by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(models) {
        try {
            installedChecksum = models.installed()?.sha256
        } catch (
            cancelled: CancellationException,
        ) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        }
    }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val expected = pendingChecksum
            pendingChecksum = null
            if (uri != null && expected != null) {
                scope.launch {
                    busy = true
                    failed = false
                    try {
                        installedChecksum = models.import(uri, expected).sha256
                    } catch (
                        cancelled: CancellationException,
                    ) {
                        throw cancelled
                    } catch (_: Exception) {
                        failed = true
                    } finally {
                        busy = false
                    }
                }
            }
        }
    SettingsScaffold(title = stringResource(R.string.intelligence_title), onBack = onBack) {
        SettingsList {
            item {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.intelligence_local_only))
                    Text(stringResource(R.string.intelligence_model_help))
                    Text(stringResource(R.string.intelligence_aicore_help))
                    installedChecksum?.let {
                        Text(stringResource(R.string.intelligence_model_ready))
                        Text(it)
                    }
                    OutlinedTextField(
                        checksum,
                        { value -> checksum = value.take(64).lowercase() },
                        label = { Text(stringResource(R.string.intelligence_checksum)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    )
                    TextButton(enabled = !busy && checksum.matches(Regex("[a-f0-9]{64}")), onClick = {
                        pendingChecksum = checksum
                        picker.launch(arrayOf("application/octet-stream", "application/*"))
                    }) { Text(stringResource(R.string.intelligence_import_model)) }
                    TextButton(enabled = !busy && installedChecksum != null, onClick = {
                        scope.launch {
                            busy = true
                            try {
                                models.remove()
                                failed = false
                                installedChecksum = null
                            } catch (
                                cancelled: CancellationException,
                            ) {
                                throw cancelled
                            } catch (_: Exception) {
                                failed = true
                            } finally {
                                busy = false
                            }
                        }
                    }) { Text(stringResource(R.string.intelligence_remove_model)) }
                    if (busy) Text(stringResource(R.string.intelligence_model_verifying))
                    if (failed) Text(stringResource(R.string.intelligence_model_import_failed))
                }
            }
        }
    }
}
