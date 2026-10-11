# Diagnostic startup capture

Use this only for an explicitly requested startup investigation. The diagnostic
option is off by default and production builds keep it off even when requested.
It records host stage entry/exit before potentially blocking binding calls and
works without successful MDK initialization, audit consent, or a remote collector.

Build the existing staging release variant with `-PstartupDiagnostics=true`, or
manually dispatch **Android Staging APK** on the reviewed branch with the
`startup_diagnostics` input enabled. The existing audit-attestation policy allows
manual dispatch only by `Datawav` from a `datawav/` branch. Keep that policy; do
not disable audit requirements or substitute a different signer to obtain an APK.
The workflow uses the existing staging key.
The version name ends in `-startup-diagnostics`; package and versionCode stay on
the staging update track. Do not substitute a dev/preview APK for an update to
an affected staging installation.

Before delivery compare the candidate with the affected signed APK: package,
certificate digest, acceptable versionCode, and the exact MarmotKit archive/native
libraries. Check Android migration changes separately. Never infer compatibility
from matching package names or a green build alone. Preserve a private backup
before any physical-device qualification; never uninstall or clear storage.

The diagnostic build writes one bounded latest receipt under private app storage.
Writes run on a separate thread with at most one pending snapshot; disk faults
cannot turn into bootstrap failures. The memory ring keeps 96 events and at most
24 active stage observations. Retry observes the existing attempt and does not
reset the record. Between-stage gaps are named rather than blamed on an earlier
completed stage. Thrown failures retain the innermost failed stage after unwind.

On a timeout, **Copy** includes the active stage, durations and native pin.
**Share startup diagnostics** creates a host-only text file (32 KiB maximum) and
opens the system chooser after the user's tap. It never enables uploads, queries
MDK log files, or changes diagnostic-sharing preferences. Only three export copies
are retained. Export has a five-second presentation wait and at most one writer;
if disk remains blocked, repeated taps cannot create extra writers. A late result
cannot present after the failure route changes or the app locks.

The file includes fixed stage/event names, monotonic durations, UTC process start,
Android API, OS build fingerprint and build revisions. It excludes throwable text/stacks, private paths,
account/group/message identifiers, keys, payloads and message contents. The
recipient is selected by the user in the share sheet.

For the affected user: install the verified signed APK as an in-place update,
open staging, wait for Retry, tap Copy, then tap Share startup diagnostics and send
the text file to the investigating developer. A recorded `NATIVE_CONSTRUCT` or
`MARMOT_START` stall identifies a host boundary, not a native root cause. Follow
that evidence with targeted native instrumentation or a thread/system trace;
this diagnostic build does not repair the underlying failure.

## Privacy-call follow-up capture

The diagnostic stage schema now distinguishes IO dispatch, telemetry installation
ID/configuration, audit mutex acquisition versus consent preparation, individual
audit OTLP/tracker/settings calls, local disclosure persistence, product analytics,
account listing and each attachment policy read/write. It records no call
arguments or return values, including account identifiers and diagnostic IDs.
Handled optional errors retain their existing fallback behavior.

Suspendable stage observers update their last executing thread on every coroutine
resume, including dispatcher hops; nested and parent observers retain separate
context keys. Restore callbacks do not overwrite a newer observation. These are
last-observed JVM threads, not a claim that a suspended Rust future runs on them.

At a timeout or thrown bootstrap failure, one independent daemon samples JVM
thread stacks. Active-stage thread IDs get priority, followed by the main thread.
The bounded receipt includes capture UTC/duration, thread IDs/states, sanitized
code class/method names, native-frame flags and line numbers. Thread names,
source filenames, private paths, locals and exception text are excluded. At most
16 threads and 16 frames each are retained within an 8 KiB ASCII receipt. The
report says when sampling is pending or unavailable, counts dropped history events
and explicitly marks truncated thread text; retries cannot start extra
blocked samplers. The full report remains limited to 32 KiB. Capture does not
wait for a native call, acquire the audit mutex, or query MDK storage.

Existing stages continue to cover the rest of bootstrap. A JVM stack cannot
reveal a suspended Rust future or a native lock's owner; if the exact binding
boundary is identified but its implementation remains opaque, use that evidence
for targeted MDK investigation rather than attributing a native root cause to
this host capture alone. No APK can guarantee capture of every possible stall.

The diagnostic staging workflow also retains a separate
`startup-diagnostic-evidence-<run>-<sha>` artifact: exact R8 mapping, MarmotKit
pin and source/run/APK/mapping hash receipt. Keep this developer artifact alongside
the signed APK so obfuscated JVM frames can be decoded without rebuilding. Send
the APK to the affected user; retrieve the matching evidence for investigation.
