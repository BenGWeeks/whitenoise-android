package dev.ipf.whitenoise.android.intelligence

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerAssistanceToken
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerTextState

@Composable
internal fun ComposerTextAssistanceAction(
    textState: ComposerTextState,
    ownerKey: Any?,
    ownerCurrent: () -> Boolean,
    onApply: (TextFieldValue) -> Unit,
) {
    var captured by remember(ownerKey) { mutableStateOf<ComposerAssistanceToken?>(null) }
    IconButton(
        onClick = { captured = textState.assistanceToken() },
        enabled =
            textState.valueState.value.text
                .isNotBlank(),
    ) {
        Icon(
            painterResource(R.drawable.ic_text_fields),
            contentDescription = stringResource(R.string.intelligence_title),
        )
    }
    captured?.let { token ->
        ComposerTextAssistanceDialog(
            token,
            { ownerCurrent() && textState.isCurrentAssistance(token) },
            onApply = { proposed ->
                if (ownerCurrent() && textState.isCurrentAssistance(token)) onApply(proposed)
                captured = null
            },
            onDismiss = { captured = null },
        )
    }
}

@Composable
internal fun ComposerTextAssistanceDialog(
    token: ComposerAssistanceToken,
    ownerCurrent: () -> Boolean,
    onApply: (TextFieldValue) -> Unit,
    onDismiss: () -> Unit,
    providerFactory: ((Boolean) -> TextIntelligenceProvider)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val request =
        remember(token) {
            TextIntelligenceRequest(scope) {
                ownerCurrent() && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            }
        }
    var useAicore by remember(token) { mutableStateOf(false) }
    val current = ownerCurrent()
    if (!current) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    LaunchedEffect(current, useAicore) { request.cancel() }
    DisposableEffect(request, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) request.cancel() }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            request.cancel()
        }
    }
    val selected = !token.value.selection.collapsed
    val start = if (selected) token.value.selection.min else 0
    val end = if (selected) token.value.selection.max else token.value.text.length
    val input = token.value.text.substring(start, end)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.intelligence_title)) },
        text = {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .semantics { liveRegion = LiveRegionMode.Polite },
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(
                        if (selected) R.string.intelligence_selection else R.string.intelligence_whole_draft,
                    ),
                )
                Text(stringResource(R.string.intelligence_local_only))
                Text(input)
                Row {
                    RadioButton(!useAicore, onClick = { useAicore = false })
                    TextButton(onClick = { useAicore = false }) { Text(stringResource(R.string.intelligence_litert)) }
                }
                Row {
                    RadioButton(useAicore, onClick = null, enabled = false)
                    TextButton(onClick = {}, enabled = false) { Text(stringResource(R.string.intelligence_aicore)) }
                }
                Text(stringResource(R.string.intelligence_aicore_help))
                TextIntelligenceOperation.entries.forEach { operation ->
                    val label =
                        when (operation) {
                            TextIntelligenceOperation.PROOFREAD -> R.string.intelligence_proofread
                            TextIntelligenceOperation.REWRITE -> R.string.intelligence_rewrite
                            TextIntelligenceOperation.SUMMARIZE -> R.string.intelligence_summarize
                        }
                    TextButton(
                        enabled =
                            current &&
                                !request.running &&
                                (!useAicore || operation != TextIntelligenceOperation.SUMMARIZE),
                        onClick = {
                            val provider =
                                providerFactory?.invoke(useAicore)
                                    ?: if (useAicore) {
                                        AicoreTextIntelligenceProvider()
                                    } else {
                                        LiteRtTextIntelligenceProvider(
                                            TextIntelligenceModelStore(context.applicationContext),
                                        )
                                    }
                            request.start(provider, operation, input)
                        },
                    ) { Text(stringResource(label)) }
                }
                if (!current) Text(stringResource(R.string.intelligence_draft_changed))
                if (request.running) Text(stringResource(R.string.intelligence_running))
                request.failure?.let { Text(stringResource(intelligenceFailureResource(it))) }
                request.result?.let { Text(it) }
                if (request.running) TextButton(onClick = request::cancel) { Text(stringResource(R.string.cancel)) }
            }
        },
        confirmButton = {
            TextButton(enabled = current && request.result != null && !request.running, onClick = {
                request.result?.let { replacement ->
                    val updated = token.value.text.replaceRange(start, end, replacement)
                    onApply(TextFieldValue(updated, TextRange(start + replacement.length)))
                }
            }) { Text(stringResource(R.string.intelligence_apply)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.intelligence_discard)) } },
    )
}

internal fun intelligenceFailureResource(failure: TextIntelligenceFailure): Int =
    when (failure) {
        TextIntelligenceFailure.MODEL_REQUIRED -> R.string.intelligence_model_required
        TextIntelligenceFailure.UNAVAILABLE -> R.string.intelligence_unavailable
        TextIntelligenceFailure.INVALID_INPUT -> R.string.intelligence_input_limit
        else -> R.string.intelligence_failed
    }
