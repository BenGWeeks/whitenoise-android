# Multimedia containment

## Android decoder boundary

Still-image decode and inspection, SVG preparation, video poster/duration probes and voice waveform extraction use a nonexported `android:isolatedProcess` service. The worker has a separate UID and no application permissions. Application startup skips account state, diagnostics, locale preferences, playback, transfers and background scheduling in that process.

The app lazily binds one serialized image worker and a separate media worker. A slow waveform must not occupy the notification-avatar lane. Production calls on the main looper or without a configured transport fail closed. JVM tests explicitly inject their codec fixture; those tests do not prove process isolation.

Input bytes are written only after their temporary file is unlinked, then passed by read-only descriptor. Picked images use a separate process-wide staging gate, with provider cancellation and bounded reads. Whole-file seekable videos pass their descriptor without a full-video array copy. Provider subranges are copied into their own unlinked inode, so the worker cannot read bytes outside the selected asset. No URLs, account objects, keys or general file-access callback enter the worker.

The app owns bounded output shared memory. Replies use an explicit primitive Binder format with a per-request callback capability, kernel caller UID, first-response fencing and fixed waveform shape. The app validates dimensions, byte capacity, metadata and output protection, then copies software ARGB8888 pixels into an app-owned bitmap. It never displays mutable worker memory directly or re-decodes compressed image output. Shared-memory protection cannot revoke a worker's existing writable mapping; the bounded copy confines concurrent mutation to pixel values.

## Limits and failure

- Encoded image input: at most 64 MiB. Existing receive/presentation limits may be lower and remain in force.
- Static source canvas: at most 32768 pixels per edge and 64 Mi pixels before raster allocation.
- Output edge: at most 4096 pixels; parent-owned output: at most 64 MiB. Existing avatar, thumbnail, viewer and editor profiles remain tighter.
- Existing GIF/WebP canvas/frame/work admission precedes every still or animated decode. A refused animation cannot bypass admission through its first frame.
- File media probes: at most 512 MiB, with validated descriptor offsets and lengths; this is not an attachment transfer quota.
- Native codec watchdog: 6 seconds for images/posters, 35 seconds for waveform extraction. Host response waits are separately bounded. A worker still alive after timeout blocks replacement admission; a system-confirmed death permits a fresh generation.
- Failed source revisions have bounded in-memory quarantine. Account teardown retires both workers and pending reply capabilities; host cache-generation checks remain authoritative for UI publication.

Native temporary allocations are not bounded solely by output dimensions. Worker isolation and watchdogs contain ordinary faults but cannot force a compromised worker to obey its own watchdog or prevent a kernel/system-service escape. A malicious provider that ignores cancellation can hold one admitted off-main staging operation; new readers must not bypass its gate.

## Automatic versus explicit parsing

Remote timeline/GIPHY images and profile/list avatar overlays use isolated first frames. Admitted native animation is enabled only in the user-opened current media/avatar viewer, with existing visibility, motion and lifecycle controls. Composer previews also use isolated still frames because an external share intent does not attest a playback gesture. Outgoing original animation bytes retain their existing format/admission contract. `AnimatedImageDrawable` playback still uses the app process; full animation isolation requires separately qualified frame/surface IPC.

Received and picked video playback uses local file/content inputs and an explicit progressive source, rather than choosing a network/playlist protocol from its suffix. Media3 container extractors still execute in the app during explicit playback. Platform codec isolation is useful but does not isolate every Media3 parser. Referenced Nostr-event networking retains its existing owner and explicit loading policy.

## MDK boundary

MDK retains reference validation, authenticated encryption/hash verification, safe public-address/TLS/redirect transport, transfer policy, resumable acquisition and retained state. Native receive memory and low-space recovery remain tracked by [MDK #2241](https://github.com/marmot-protocol/mdk/issues/2241) and [MDK #2239](https://github.com/marmot-protocol/mdk/issues/2239). Shared public-image qualification/admission remains [MDK #2232](https://github.com/marmot-protocol/mdk/issues/2232). This Android boundary neither duplicates those rules nor claims an upstream fix is shipped.

## Qualification

Unit fixtures cover pixel limits, malformed bytes, primitive reply validation and explicit fail-closed transport. Hosted instrumentation must prove real UID separation, pixel fidelity, malformed-input recovery and account-reset behavior. Physical testing additionally checks OS process identity, private-data/network denial, hung-worker death, descriptor/RSS stability, normal image/animation compatibility, SVG, poster/waveform output and large seekable video sources. Record exact source and APK bytes; installation alone is not feature coverage. API 30 compatibility needs its own emulator/device evidence. Do not treat unchecked manual cases or a JVM fake as completed device proof.
