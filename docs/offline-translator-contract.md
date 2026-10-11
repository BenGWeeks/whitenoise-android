# Offline Translator Binder contract

The four files in `app/src/main/aidl/dev/davidv/translator/` reproduce only the
interoperability declarations from Offline Translator release0.8.3, pinned at
[`eb0341f0f0498bb54b11c8cac665e6f740a2a0c1`](https://github.com/DavidVentura/offline-translator/tree/eb0341f0f0498bb54b11c8cac665e6f740a2a0c1/app/src/main/aidl/dev/davidv/translator).
The implementation, translation models and app are separate installations.
These declarations retain GPL-3.0-only attribution; the license is retained at
`third_party/offline-translator/LICENSE`. White Noise uses AGPL-3.0-only.

The client accepts only the documented publisher certificateSHA256
`2b3806e745d809018a51be58d0635ffc74cc9733439407ab1ed0424a4db3e1fb`
and exact version0.8.3, resolves an exported enabled service within
`dev.davidv.translator`, and binds the explicit component with action
`dev.davidv.translator.ITranslationService`. Package updates invalidate active
conversation requests. Future publisher/version compatibility needs fresh
contract and signer verification.

The service takes source text, source language, target language and a callback.
Empty source means automatic detection. Its error parcel contains an enum and
nullable language/message strings. Only enum values become app-owned stable
failure codes; engine error text is discarded. The API has no installed-pack
list, model installation or remote cancellation operation. Cancellation retires
our callback and binding; it cannot stop the engine's native work. The synchronous
Binder submission also cannot be forcibly interrupted by coroutine cancellation.
Do not interpret app deadlines as proof of remote cancellation.

An unavailable target uses the generic engine-error path. The app offers model
management without claiming a dedicated target-model diagnostic. Source and
translated text remain conversation-owned transient state and never enter MDK,
preferences, files, diagnostics or logs.

Real-engine execution with installed packs, Binder compatibility and device
lifecycle behavior still require the candidate qualification recorded in the
manual release guide. Source inspection and fake-provider tests do not certify
those results.
