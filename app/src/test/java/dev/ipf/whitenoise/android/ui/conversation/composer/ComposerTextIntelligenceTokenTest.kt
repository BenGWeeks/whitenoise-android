package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerTextIntelligenceTokenTest {
    @Test fun newerTextOrSelectionRejectsSuggestionEvenAfterRevertingToIdenticalContent() {
        val initial = TextFieldValue("private draft", TextRange(0, 7))
        val state = ComposerTextState(initial)
        val request = state.assistanceToken()
        assertTrue(state.isCurrentAssistance(request))
        state.updateValue(initial.copy(selection = TextRange(8, 13)))
        state.updateValue(initial)
        assertFalse(state.isCurrentAssistance(request))
        val newer = state.assistanceToken()
        state.updateValue(TextFieldValue("edited draft"))
        state.updateValue(initial)
        assertFalse(state.isCurrentAssistance(newer))
    }

    @Test fun replacementComposerCannotAcceptTransportedToken() {
        val initial = TextFieldValue("draft")
        val original = ComposerTextState(initial)
        val replacement = ComposerTextState(initial)
        assertFalse(replacement.isCurrentAssistance(original.assistanceToken()))
    }
}
