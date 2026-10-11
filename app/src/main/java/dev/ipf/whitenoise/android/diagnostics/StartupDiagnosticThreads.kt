package dev.ipf.whitenoise.android.diagnostics

import dev.ipf.whitenoise.android.core.DiagnosticFormatter
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** One process-owned sampler; stack collection never runs on a bootstrap or screen thread. */
internal class StartupDiagnosticThreads(
    private val collect: () -> Map<Thread, Array<StackTraceElement>> = Thread::getAllStackTraces,
) {
    private val busy = AtomicBoolean()
    private val worker =
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "WNStartup-stacks").apply { isDaemon = true }
        }

    @Volatile private var receipt = "threads=not_requested\n"

    /** Keeps the last completed sample visible even if a later platform stack query cannot finish. */
    fun snapshot(): String = receipt + "threads_sampling=${busy.get()}\n"

    /** Allows at most one sampler; callbacks persist only bounded host text and cannot fail startup. */
    fun request(
        preferredIds: Set<Long>,
        onCaptured: () -> Unit,
    ) {
        if (!busy.compareAndSet(false, true)) return
        runCatching {
            worker.execute {
                val startedAt = Instant.now()
                val startedNanos = System.nanoTime()
                try {
                    receipt = "thread_sample_started_utc=$startedAt\n" +
                        runCatching { formatStartupThreads(collect(), preferredIds) }
                            .getOrDefault("threads=unavailable\n") +
                        "thread_sample_duration_ms=${(System.nanoTime() - startedNanos) / NANOS_PER_MS}\n"
                } finally {
                    busy.set(false)
                }
                runCatching(onCaptured)
            }
        }.onFailure {
            receipt = "threads=unavailable\n"
            busy.set(false)
        }
    }
}

/** Records identities of JVM code frames, never thread names, source filenames, locals or exception text. */
internal fun formatStartupThreads(
    stacks: Map<Thread, Array<StackTraceElement>>,
    preferredIds: Set<Long>,
): String =
    buildString {
        appendLine("threads=sampled total=${stacks.size} limit=$MAX_STARTUP_THREADS frames=$MAX_STARTUP_FRAMES")
        stacks.entries
            .sortedWith(
                compareByDescending<Map.Entry<Thread, Array<StackTraceElement>>> { it.key.id in preferredIds }
                    .thenByDescending { it.key.name == "main" }
                    .thenBy { it.key.id },
            ).take(MAX_STARTUP_THREADS)
            .forEach { (thread, frames) ->
                appendLine("thread_id=${thread.id} state=${thread.state.name} observed_stage_thread=${thread.id in preferredIds}")
                frames.take(MAX_STARTUP_FRAMES).forEach { frame ->
                    val owner = startupFrameName(frame.className)
                    val method = startupFrameName(frame.methodName)
                    appendLine("  frame=$owner.$method native=${frame.isNativeMethod} line=${frame.lineNumber}")
                }
            }
    }.take(MAX_STARTUP_THREAD_CHARS)

/** ASCII code identifiers keep the UTF-8 share size bounded after secret-pattern scrubbing. */
private fun startupFrameName(value: String): String =
    DiagnosticFormatter
        .redactError(value)
        .map { if (it in ' '..'~') it else '?' }
        .joinToString("")

private const val NANOS_PER_MS = 1_000_000L
private const val MAX_STARTUP_THREADS = 16
private const val MAX_STARTUP_FRAMES = 16
private const val MAX_STARTUP_THREAD_CHARS = 8 * 1024
