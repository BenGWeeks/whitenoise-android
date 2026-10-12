package dev.ipf.whitenoise.android.ui.conversation.nostr

/** Called only after the renderer's ordinary link confirmation, when applicable. */
internal fun activateNostrMediaLink(
    raw: String,
    media: NostrEventMediaCandidates,
    onViewImage: ((String) -> Unit)?,
    onPlayVideo: ((String) -> Unit)?,
): Boolean {
    val safe = safeNostrMediaUrl(raw) ?: return false
    val kind =
        when (safe) {
            in media.imageUrls -> NostrLinkedMediaKind.Image
            in media.videoUrls -> NostrLinkedMediaKind.Video
            else -> nostrLinkedMediaKind(safe)
        }
    return when {
        kind == NostrLinkedMediaKind.Image && onViewImage != null -> {
            onViewImage(safe)
            true
        }
        kind == NostrLinkedMediaKind.Video && onPlayVideo != null -> {
            onPlayVideo(safe)
            true
        }
        else -> false
    }
}
