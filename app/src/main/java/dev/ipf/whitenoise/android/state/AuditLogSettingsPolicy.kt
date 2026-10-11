package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AuditLogSettingsFfi
import dev.ipf.whitenoise.android.diagnostics.StartupDiagnosticStage
import dev.ipf.whitenoise.android.diagnostics.StartupDiagnostics
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes the existing settings transaction; diagnostic markers identify waits and logical lock owners. */
internal suspend fun updateAuditLogSettingsSerialized(
    mutex: Mutex,
    cachedSettings: () -> AuditLogSettingsFfi?,
    storeCachedSettings: (AuditLogSettingsFfi) -> Unit,
    loadFromEngine: suspend () -> AuditLogSettingsFfi,
    transform: (AuditLogSettingsFfi) -> AuditLogSettingsFfi,
    persistToEngine: suspend (AuditLogSettingsFfi) -> AuditLogSettingsFfi,
): AuditLogSettingsFfi =
    StartupDiagnostics.stage(StartupDiagnosticStage.AUDIT_MUTATION_WAIT) {
        mutex.withLock {
            StartupDiagnostics.stage(StartupDiagnosticStage.AUDIT_MUTATION_HELD) {
                val current = cachedSettings() ?: loadFromEngine()
                val updated = persistToEngine(transform(current))
                storeCachedSettings(updated)
                updated
            }
        }
    }
