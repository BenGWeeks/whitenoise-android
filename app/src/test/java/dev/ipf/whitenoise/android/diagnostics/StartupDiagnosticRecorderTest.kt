package dev.ipf.whitenoise.android.diagnostics

import dev.ipf.whitenoise.android.BuildConfig
import dev.ipf.whitenoise.android.state.privacySafeErrorPresentation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors

/** Stalled native work remains observable independently of timeout, retries and persistence failures. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class StartupDiagnosticRecorderTest {
    /** Parent and leaf observations follow each real dispatcher hop rather than retaining the entry caller. */
    @Test fun suspendedStagesTrackTheirMostRecentlyExecutingThreadAcrossDispatchers() =
        runTest {
            val recorder = StartupDiagnosticRecorder({ 0L })
            val first = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
            val second = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
            try {
                recorder.stage(StartupDiagnosticStage.PRIVACY_RUNTIME_CONFIGURATION) {
                    withContext(first) {
                        val worker = Thread.currentThread().id
                        assertEquals(setOf(worker), recorder.activeThreadIds)
                        recorder.stage(StartupDiagnosticStage.TELEMETRY_RUNTIME_CONFIG) {
                            withContext(second) {
                                val resumed = Thread.currentThread().id
                                assertTrue(resumed != worker)
                                assertEquals(setOf(resumed), recorder.activeThreadIds)
                                assertEquals(
                                    2,
                                    recorder.snapshot().lineSequence().count {
                                        it.startsWith("active ") && it.contains("thread_id=$resumed")
                                    },
                                )
                            }
                            assertEquals(setOf(worker), recorder.activeThreadIds)
                        }
                    }
                }
                assertTrue(recorder.activeThreadIds.isEmpty())
            } finally {
                first.close()
                second.close()
            }
        }

    /** Observing a suspended operation preserves the original native error instead of recovering a copy. */
    @Test fun suspendedStagePreservesOriginalError() =
        runTest {
            val recorder = StartupDiagnosticRecorder({ 0L })
            val error = IllegalStateException("private exception")
            val actual =
                runCatching {
                    recorder.stage(StartupDiagnosticStage.TELEMETRY_RUNTIME_CONFIG) { throw error }
                }.exceptionOrNull()
            assertSame(error, actual)
            assertTrue(recorder.activeThreadIds.isEmpty())
            assertTrue(recorder.failureDetail().contains("failed_stage=TELEMETRY_RUNTIME_CONFIG"))
            assertFalse(recorder.snapshot().contains("private exception"))
        }

    /** Distinguishes waiting for the audit lock from a held native call after acquiring it. */
    @Test fun auditMutexWaitAndAcquiredNativeCallHaveDifferentAttribution() =
        runTest {
            val recorder = StartupDiagnosticRecorder({ testScheduler.currentTime })
            val mutex = Mutex(locked = true)
            val held = CompletableDeferred<Unit>()
            recorder.beginAttempt()
            val work =
                launch {
                    recorder.stage(StartupDiagnosticStage.PRIVACY_RUNTIME_CONFIGURATION) {
                        recorder.stage(StartupDiagnosticStage.AUDIT_SETTINGS_MUTEX) {
                            mutex.withLock {
                                recorder.stage(StartupDiagnosticStage.AUDIT_CONSENT_PREPARE) {
                                    recorder.stage(StartupDiagnosticStage.AUDIT_OTLP_CONFIG) { held.await() }
                                }
                            }
                        }
                    }
                }
            runCurrent()
            assertTrue(recorder.timeoutDetail().contains("stage=AUDIT_SETTINGS_MUTEX"))
            mutex.unlock()
            runCurrent()
            assertTrue(recorder.timeoutDetail().contains("stage=AUDIT_OTLP_CONFIG"))
            assertTrue(recorder.snapshot().contains("thread_id="))
            assertTrue(recorder.activeThreadIds.isNotEmpty())
            held.complete(Unit)
            work.join()
            assertTrue(recorder.activeThreadIds.isEmpty())
        }

    /** The actual Copy formatter preserves long stage attribution and every numeric/native field. */
    @Test fun copiedReportRetainsCompleteStartupFieldsAndRejectsFreeText() {
        var clock = 0L
        val recorder = StartupDiagnosticRecorder({ clock })
        recorder.beginAttempt()
        val token = recorder.enter(StartupDiagnosticStage.PRIVACY_RUNTIME_CONFIGURATION)
        clock = 15_000L

        fun report(detail: String) =
            privacySafeErrorPresentation(
                operationCode = "APP_BOOTSTRAP_TIMEOUT",
                throwable = IllegalStateException("private exception"),
                appVersion = "test",
                androidVersion = "test",
                occurredAtUtc = "test",
                startupDetail = detail,
            ).report
        val mdkSha = BuildConfig.MDK_SHORT_SHA
        val timeout = report(recorder.timeoutDetail() + " mdk_sha=$mdkSha")
        assertTrue(timeout.contains("startup_stage=PRIVACY_RUNTIME_CONFIGURATION"))
        assertTrue(timeout.contains("startup_attempt_elapsed_ms=15000"))
        assertTrue(timeout.contains("startup_active_count=1"))
        assertTrue(timeout.contains("startup_mdk_sha=$mdkSha"))
        recorder.exit(token, PerformanceResult.FAILURE)
        val failure = report(recorder.failureDetail() + " mdk_sha=$mdkSha")
        assertTrue(failure.contains("startup_failed_stage=PRIVACY_RUNTIME_CONFIGURATION"))
        assertTrue(failure.contains("startup_stage=BETWEEN_STAGES"))
        assertTrue(failure.contains("startup_mdk_sha=$mdkSha"))
        assertFalse(report("stage=private-account").contains("private-account"))
        assertFalse(timeout.contains("private exception"))
    }

    /** A native call that never returns still has an entry and remains the same operation after Retry. */
    @Test fun heldConstructionReportsItsInnermostStageWithoutCancellingOrDuplicatingWork() =
        runTest {
            var clock = 0L
            val recorder = StartupDiagnosticRecorder({ clock })
            val held = CompletableDeferred<Unit>()
            var calls = 0
            recorder.beginAttempt()
            val work =
                launch {
                    recorder.stage(StartupDiagnosticStage.CLIENT_CONSTRUCTION) {
                        recorder.stage(StartupDiagnosticStage.NATIVE_CONSTRUCT) {
                            calls++
                            held.await()
                        }
                    }
                }
            runCurrent()
            clock = 15_000L
            repeat(3) {
                val detail = recorder.timeoutDetail()
                assertTrue(detail.contains("stage=NATIVE_CONSTRUCT stage_elapsed_ms=15000"))
                assertTrue(detail.contains("attempt=1 attempt_elapsed_ms=15000 active_count=2"))
                assertTrue(work.isActive)
            }
            assertEquals(1, calls)
            held.complete(Unit)
            work.join()
            assertTrue(recorder.snapshot().contains("stage=BETWEEN_STAGES"))
            assertTrue(recorder.snapshot().contains("active_count=0"))
        }

    /** Native start can be distinguished from construction even when its FFI call is still suspended. */
    @Test fun heldNativeStartRemainsObservableAndSuccessfulCompletionClosesIt() =
        runTest {
            val recorder = StartupDiagnosticRecorder({ testScheduler.currentTime })
            val held = CompletableDeferred<Unit>()
            recorder.beginAttempt()
            val work = launch { recorder.stage(StartupDiagnosticStage.MARMOT_START) { held.await() } }
            runCurrent()
            assertTrue(recorder.timeoutDetail().contains("stage=MARMOT_START"))
            held.complete(Unit)
            work.join()
            assertTrue(recorder.snapshot().contains("result=SUCCESS"))
            assertTrue(recorder.snapshot().contains("active_count=0"))
        }

    /** An older concurrent completion cannot erase the newer active stage. */
    @Test fun concurrentStagesHaveIndependentTokens() {
        var clock = 0L
        val recorder = StartupDiagnosticRecorder({ clock })
        recorder.beginAttempt()
        val first = recorder.enter(StartupDiagnosticStage.MARMOT_START)
        clock = 5L
        val second = recorder.enter(StartupDiagnosticStage.ACCOUNT_REFRESH)
        clock = 12L
        recorder.exit(first, PerformanceResult.SUCCESS)
        assertTrue(recorder.timeoutDetail().contains("stage=ACCOUNT_REFRESH stage_elapsed_ms=7"))
        recorder.exit(second, PerformanceResult.SUCCESS)
        assertTrue(recorder.timeoutDetail().contains("stage=BETWEEN_STAGES"))
    }

    /** Native exceptions propagate unchanged; only the innermost fixed stage survives the unwind. */
    @Test fun failedStageSurvivesUnwindWithoutRetainingSensitiveExceptionText() {
        val recorder = StartupDiagnosticRecorder({ 0L })
        val secret = "nsec1-secret /private/database.db https://private.example/token"
        val error = IllegalStateException(secret)
        recorder.beginAttempt()
        val actual =
            runCatching {
                recorder.measure(StartupDiagnosticStage.CLIENT_CONSTRUCTION) {
                    recorder.measure(StartupDiagnosticStage.NATIVE_CONSTRUCT) { throw error }
                }
            }.exceptionOrNull()
        assertSame(error, actual)
        assertTrue(recorder.failureDetail().contains("failed_stage=NATIVE_CONSTRUCT"))
        assertFalse(recorder.snapshot().contains(secret))
        assertFalse(recorder.snapshot().contains("IllegalStateException"))
        recorder.beginAttempt()
        assertFalse(recorder.snapshot().contains("failed_stage="))
    }

    /** Writer faults are diagnostic failures, never application failures or barriers to memory export. */
    @Test fun brokenSinkCannotChangeNativeResultOrPreventSnapshot() {
        val recorder = StartupDiagnosticRecorder({ 0L }) { error("disk unavailable") }
        recorder.beginAttempt()
        assertEquals(42, recorder.measure(StartupDiagnosticStage.NATIVE_INITIALIZE) { 42 })
        assertTrue(recorder.snapshot().contains("result=SUCCESS"))
        assertFalse(recorder.snapshot().contains("disk unavailable"))
    }

    /** Repeated user actions and stages stay within the event and export ceilings. */
    @Test fun retryAndStageHistoryRemainBounded() {
        val recorder = StartupDiagnosticRecorder({ 1L })
        recorder.beginAttempt()
        repeat(300) {
            recorder.measure(StartupDiagnosticStage.ACCOUNT_REFRESH) { Unit }
            recorder.timeoutDetail()
        }
        val snapshot = recorder.snapshot()
        val eventCount = snapshot.lineSequence().count { it.startsWith("elapsed_ms=") }
        assertEquals(StartupDiagnosticRecorder.MAX_EVENTS, eventCount)
        assertTrue(snapshot.contains("events_dropped=805"))
        assertTrue(snapshot.toByteArray().size < MAX_STARTUP_REPORT_BYTES)
        assertTrue(snapshot.contains("attempt=1"))
    }
}
