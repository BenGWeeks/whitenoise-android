package dev.ipf.whitenoise.android.diagnostics

import kotlinx.coroutines.withContext

/** Fixed host boundaries; no native identifiers, paths, or exception text are accepted. */
internal enum class StartupDiagnosticStage {
    CLIENT_CONSTRUCTION,
    ROOT_LEASE,
    NATIVE_INITIALIZE,
    NATIVE_CONSTRUCT,
    PRIVACY_RUNTIME_CONFIGURATION,
    PRIVACY_DISPATCH,
    TELEMETRY_INSTALL_ID,
    TELEMETRY_RUNTIME_CONFIG,
    AUDIT_SETTINGS_MUTEX,
    AUDIT_CONSENT_PREPARE,
    AUDIT_REFRESH_WAIT,
    AUDIT_REFRESH_HELD,
    AUDIT_MUTATION_WAIT,
    AUDIT_MUTATION_HELD,
    AUDIT_OTLP_CONFIG,
    AUDIT_TRACKER_CONFIG,
    AUDIT_SETTINGS_READ,
    AUDIT_SETTINGS_WRITE,
    AUDIT_DISCLOSURE_SAVE,
    PRODUCT_ANALYTICS_CONFIG,
    ATTACHMENT_ACCOUNT_LIST,
    ATTACHMENT_POLICY_READ,
    ATTACHMENT_POLICY_WRITE,
    MARMOT_START,
    NOTIFICATION_PLATFORM_SETUP,
    NOTIFICATION_PRIVACY_SETUP,
    NOTIFICATION_RECEIVER,
    POST_START_READINESS,
    ACCOUNT_REFRESH,
    LOCAL_RESTORATION,
    DRAFT_RECONCILIATION,
    EXTERNAL_SIGNER_REGISTRATION,
    ACCOUNT_ACTIVATION,
    UNREAD_AGGREGATE_REFRESH,
    FAILED_RUNTIME_CLOSE,
}

/** A bounded, synchronized observation of one process-owned bootstrap, independent of native locks. */
internal class StartupDiagnosticRecorder(
    private val nowMs: () -> Long,
    private val persist: (String) -> Unit = {},
) {
    private val lock = Any()
    private val processStartedAt = nowMs()
    private var attemptStartedAt = processStartedAt
    private var attempt = 0
    private var nextToken = 0L
    private var failedStage: StartupDiagnosticStage? = null
    private val active = linkedMapOf<Long, Entry>()
    private val events = ArrayDeque<String>()
    private var droppedEvents = 0L

    /** Starts only when a new underlying attempt starts; user Retry must not call this. */
    fun beginAttempt() =
        synchronized(lock) {
            attempt++
            attemptStartedAt = nowMs()
            failedStage = null
            addEvent("attempt_start")
        }

    /** Publishes the stage before the possibly blocking operation executes. */
    fun enter(stage: StartupDiagnosticStage): Long =
        synchronized(lock) {
            val token = ++nextToken
            if (active.size < MAX_ACTIVE_STAGES) active[token] = Entry(stage, nowMs(), Thread.currentThread().id)
            addEvent("enter stage=${stage.name} token=$token")
            token
        }

    /** Removes only this token, preserving newer and concurrently running stages. */
    fun exit(
        token: Long,
        result: PerformanceResult,
    ) = synchronized(lock) {
        val entry = active.remove(token) ?: return@synchronized
        if (result == PerformanceResult.FAILURE && failedStage == null) failedStage = entry.stage
        addEvent(
            "exit stage=${entry.stage.name} token=$token " +
                "duration_ms=${elapsed(entry.startedAt)} result=${result.name}",
        )
    }

    /** Marks an observation without cancelling or replacing the still-owned attempt. */
    fun timeoutDetail(): String =
        synchronized(lock) {
            addEvent("deadline")
            detail()
        }

    /** Retains the innermost failed stage after its finally block and native cleanup have completed. */
    fun failureDetail(): String =
        synchronized(lock) {
            "failed_stage=${failedStage?.name ?: "BETWEEN_STAGES"} ${detail()}"
        }

    /** Observes a synchronous native call while preserving its original exception and return value. */
    fun <T> measure(
        stage: StartupDiagnosticStage,
        block: () -> T,
    ): T {
        val token = enter(stage)
        var result = PerformanceResult.FAILURE
        return try {
            block().also { result = PerformanceResult.SUCCESS }
        } finally {
            exit(token, result)
        }
    }

    /** Observes suspendable native work without cancelling it when the presentation deadline passes. */
    suspend fun <T> stage(
        stage: StartupDiagnosticStage,
        block: suspend () -> T,
    ): T {
        val token = enter(stage)
        var result = PerformanceResult.FAILURE
        return try {
            val observed =
                withContext(
                    StartupStageThreadObservation {
                        synchronized(lock) {
                            active[token]?.let { entry ->
                                active[token] = entry.copy(threadId = Thread.currentThread().id)
                            }
                        }
                    },
                ) {
                    runCatching { block() }
                }
            observed.getOrThrow().also { result = PerformanceResult.SUCCESS }
        } finally {
            exit(token, result)
        }
    }

    /** Captures immutable host-only text; exporters never wait for a native stage or writer. */
    fun snapshot(): String =
        synchronized(lock) {
            buildString {
                appendLine("schema=whitenoise-startup-v1")
                appendLine(detail())
                appendLine("events_retained=${events.size} events_dropped=$droppedEvents")
                failedStage?.let { appendLine("failed_stage=${it.name}") }
                active.forEach { (token, entry) ->
                    appendLine(
                        "active stage=${entry.stage.name} token=$token " +
                            "duration_ms=${elapsed(entry.startedAt)} thread_id=${entry.threadId}",
                    )
                }
                events.forEach(::appendLine)
            }
        }

    /** Thread IDs let the independent sampler prioritize observed calls without retaining thread names. */
    val activeThreadIds: Set<Long> get() = synchronized(lock) { active.values.map { it.threadId }.toSet() }

    /** Reports the most recently entered active boundary, explicitly naming between-stage gaps. */
    private fun detail(): String {
        val latest = active.values.lastOrNull()
        return "stage=${latest?.stage?.name ?: "BETWEEN_STAGES"} " +
            "stage_elapsed_ms=${latest?.let { elapsed(it.startedAt) } ?: 0} " +
            "attempt=$attempt attempt_elapsed_ms=${elapsed(attemptStartedAt)} active_count=${active.size}"
    }

    /** Adds fixed-schema events to a ring; sink faults never become application failures. */
    private fun addEvent(event: String) {
        if (events.size == MAX_EVENTS) {
            events.removeFirst()
            droppedEvents++
        }
        events.addLast("elapsed_ms=${elapsed(processStartedAt)} attempt=$attempt $event")
        runCatching { persist(snapshot()) }
    }

    /** Clamps an injected clock defensively while keeping durations monotonic in production. */
    private fun elapsed(start: Long): Long = (nowMs() - start).coerceAtLeast(0L)

    private data class Entry(
        val stage: StartupDiagnosticStage,
        val startedAt: Long,
        val threadId: Long,
    )

    companion object {
        internal const val MAX_EVENTS = 96
        private const val MAX_ACTIVE_STAGES = 24
    }
}
