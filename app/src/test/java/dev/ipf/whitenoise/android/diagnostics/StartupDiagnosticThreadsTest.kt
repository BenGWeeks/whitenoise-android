package dev.ipf.whitenoise.android.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Diagnostic stack evidence remains bounded, privacy-safe and independent of a held platform query. */
class StartupDiagnosticThreadsTest {
    /** Preferred active-call threads survive the cap; names, paths and secret-shaped frame names do not leak. */
    @Test fun boundedFramesExcludeThreadNamesAndSourcePaths() {
        val secret = "nsec1" + "q".repeat(60)
        val preferred = Thread("private-account-$secret")
        val frames = Array(100) { StackTraceElement(secret, "blocked", "/private/$secret.kt", 42) }
        val all = (List(40) { Thread("private-$it") } + preferred).associateWith { frames }
        val report = formatStartupThreads(all, setOf(preferred.id))
        assertTrue(report.contains("thread_id=${preferred.id}"))
        assertTrue(report.contains("observed_stage_thread=true"))
        assertTrue(report.contains("threads_text_truncated=true"))
        assertTrue(report.toByteArray(Charsets.UTF_8).size <= 8 * 1024)
        assertFalse(report.contains(secret))
        assertFalse(report.contains("private-account"))
        assertFalse(report.contains("/private/"))
    }

    /** Repeated Retry cannot create additional blocked samplers, and an unavailable query becomes fixed safe text. */
    @Test fun heldCollectorDoesNotBlockCallerOrAllowParallelCollection() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val calls = AtomicInteger()
        val sampler =
            StartupDiagnosticThreads {
                calls.incrementAndGet()
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                error("private filesystem secret")
            }
        try {
            sampler.request(emptySet()) { finished.countDown() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            repeat(10) { sampler.request(emptySet()) {} }
            assertEquals(1, calls.get())
            assertTrue(sampler.snapshot().contains("threads_sampling=true"))
        } finally {
            release.countDown()
        }
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        assertTrue(sampler.snapshot().contains("threads=unavailable"))
        assertTrue(sampler.snapshot().contains("threads_sampling=false"))
        assertFalse(sampler.snapshot().contains("private filesystem"))
    }
}
