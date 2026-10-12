package dev.ipf.whitenoise.android.ui.conversation

import dev.ipf.whitenoise.android.media.editor.PhotoEditorCommitResult
import dev.ipf.whitenoise.android.media.editor.PhotoEditorRenderResult
import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoEditorSaveFailureMessageTest {
    private val messages = PhotoEditorMessages("animation", "source", "unavailable", "save", "budget")

    @Test
    fun processingLimitIsDistinguishedFromOtherFailuresWithoutClaimingSuccess() {
        assertEquals(
            "budget",
            photoEditorSaveFailureMessage(PhotoEditorCommitResult.RenderFailed(PhotoEditorRenderResult.MemoryLimit), messages),
        )
        assertEquals(
            "save",
            photoEditorSaveFailureMessage(PhotoEditorCommitResult.RenderFailed(PhotoEditorRenderResult.EncodeFailed), messages),
        )
        assertEquals("save", photoEditorSaveFailureMessage(PhotoEditorCommitResult.StaleAttachment, messages))
    }
}
