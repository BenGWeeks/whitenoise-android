@file:Suppress("FunctionNaming") // Unit-returning Compose controls use the established PascalCase convention.

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
    val request = rememberAssistanceRequest(token, ownerCurrent)
    var useAicore by remember(token) { mutableStateOf(false) }
    val current = ownerCurrent()
    if (!current) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    LaunchedEffect(current, useAicore) { request.cancel() }
    val selected = !token.value.selection.collapsed
    val start = if (selected) token.value.selection.min else 0
    val end = if (selected) token.value.selection.max else token.value.text.length
    val input = token.value.text.substring(start, end)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.intelligence_title)) },
        text = {
            assistanceDialogContent(
                AssistanceContentState(request, input, selected, useAicore),
                onEngineSelected = { useAicore = it },
                providerFactory = { aicore ->
                    providerFactory?.invoke(aicore)
                        ?: if (aicore) {
                            AicoreTextIntelligenceProvider()
                        } else {
                            LiteRtTextIntelligenceProvider(TextIntelligenceModelStore(context.applicationContext))
                        }
                },
            )
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

@Composable
private fun rememberAssistanceRequest(
    token: ComposerAssistanceToken,
    ownerCurrent: () -> Boolean,
): TextIntelligenceRequest {
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val request =
        remember(token) {
            TextIntelligenceRequest(scope) {
                ownerCurrent() && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            }
        }
    DisposableEffect(request, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) request.cancel() }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            request.cancel()
        }
    }
    return request
}

private data class AssistanceContentState(
    val request: TextIntelligenceRequest,
    val input: String,
    val selected: Boolean,
    val useAicore: Boolean,
)

@Composable
private fun assistanceDialogContent(
    state: AssistanceContentState,
    onEngineSelected: (Boolean) -> Unit,
    providerFactory: (Boolean) -> TextIntelligenceProvider,
) {
    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(
                if (state.selected) R.string.intelligence_selection else R.string.intelligence_whole_draft,
            ),
        )
        Text(stringResource(R.string.intelligence_local_only))
        Text(state.input)
        Row {
            RadioButton(!state.useAicore, onClick = { onEngineSelected(false) })
            TextButton(onClick = { onEngineSelected(false) }) { Text(stringResource(R.string.intelligence_litert)) }
        }
        Row {
            RadioButton(state.useAicore, onClick = null, enabled = false)
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
                    !state.request.running &&
                        (!state.useAicore || operation != TextIntelligenceOperation.SUMMARIZE),
                onClick = {
                    val provider = providerFactory(state.useAicore)
                    state.request.start(provider, operation, state.input)
                },
            ) { Text(stringResource(label)) }
        }
        if (state.request.running) Text(stringResource(R.string.intelligence_running))
        state.request.failure?.let { Text(stringResource(intelligenceFailureResource(it))) }
        state.request.result?.let { Text(it) }
        if (state.request.running) TextButton(onClick = state.request::cancel) { Text(stringResource(R.string.cancel)) }
    }
}

internal fun intelligenceFailureResource(failure: TextIntelligenceFailure): Int =
    when (failure) {
        TextIntelligenceFailure.MODEL_REQUIRED -> R.string.intelligence_model_required
        TextIntelligenceFailure.UNAVAILABLE -> R.string.intelligence_unavailable
        TextIntelligenceFailure.INVALID_INPUT -> R.string.intelligence_input_limit
        else -> R.string.intelligence_failed
    }
