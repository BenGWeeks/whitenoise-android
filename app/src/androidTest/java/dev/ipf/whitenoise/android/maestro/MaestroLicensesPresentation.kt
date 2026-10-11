package dev.ipf.whitenoise.android.maestro

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.ipf.whitenoise.android.ui.settings.OpenSourceLicensesContent
import dev.ipf.whitenoise.android.ui.settings.OpenSourceNotice
import dev.ipf.whitenoise.android.ui.settings.SettingsScaffold

/** Controlled resource failures use actual license content; generated resources have a separate native journey. */
@Composable
@Suppress("FunctionNaming")
internal fun MaestroLicensesPresentation(fixture: MaestroPresentationFixture) {
    val notice = remember { OpenSourceNotice("fixture", "Maestro local notice", "Complete local notice text") }
    var notices by remember {
        mutableStateOf<Result<List<OpenSourceNotice>>?>(
            if (fixture.scenario == "extra-licenses-loading") {
                null
            } else {
                Result.failure(IllegalStateException("Fixture"))
            },
        )
    }
    var selected by remember { mutableStateOf<OpenSourceNotice?>(null) }
    val back = {
        if (selected != null) {
            selected = null
            fixture.record("list-return")
        } else {
            fixture.finish("dismiss")
        }
    }
    BackHandler(onBack = back)
    SettingsScaffold(title = selected?.name ?: "Open source licenses", onBack = back) {
        OpenSourceLicensesContent(
            notices = notices,
            selected = selected,
            onSelect = {
                selected = it
                fixture.record("notice-selected")
            },
            onRetry = {
                fixture.record("retry")
                notices = Result.success(listOf(notice))
            },
        )
    }
}
