package com.example.dink_smb_player.player

/**
 * Decides, once per service poll, what of the now-playing session to write to
 * [PlaybackStore]. Pure (no IO, no Compose snapshot observation) so it runs in
 * [PlayerService] whether or not an activity exists, and is JVM-testable.
 *
 * - Structure (track, order, modes, queue length — [PlayerState.saveSignature]) saves the
 *   whole session, debounced so a burst of skips writes once.
 * - Position only writes the tiny position record: every [positionEveryMs] while
 *   playing, and immediately on pause, so a resume lands where the user stopped.
 */
internal class SessionPersistPolicy(
    private val structuralDebounceMs: Long = 800L,
    private val positionEveryMs: Long = 5_000L,
) {
    enum class Action { None, SaveSession, SavePosition }

    private var savedSignature: String? = null
    private var pendingSignature: String? = null
    private var pendingSinceMs = 0L
    private var lastPositionMs = 0L
    private var wasPlaying = false

    fun onTick(nowMs: Long, signature: String, isPlaying: Boolean): Action {
        // First tick is the baseline: whatever is already on disk (or nothing) stands.
        if (savedSignature == null) {
            savedSignature = signature
            wasPlaying = isPlaying
            lastPositionMs = nowMs
            return Action.None
        }
        val paused = wasPlaying && !isPlaying
        if (isPlaying && !wasPlaying) lastPositionMs = nowMs
        wasPlaying = isPlaying

        if (signature != savedSignature) {
            if (signature != pendingSignature) {
                pendingSignature = signature
                pendingSinceMs = nowMs
            }
            if (nowMs - pendingSinceMs >= structuralDebounceMs) return commitSession(nowMs, signature)
            // A pause mid-debounce still checkpoints where it stopped.
            if (paused) return position(nowMs)
            return Action.None
        }
        pendingSignature = null
        if (paused) return position(nowMs)
        if (isPlaying && nowMs - lastPositionMs >= positionEveryMs) return position(nowMs)
        return Action.None
    }

    /** Final write before the service goes away: a pending structural change wins. */
    fun flush(nowMs: Long, signature: String): Action =
        if (savedSignature != null && signature != savedSignature) commitSession(nowMs, signature)
        else position(nowMs)

    private fun commitSession(nowMs: Long, signature: String): Action {
        savedSignature = signature
        pendingSignature = null
        lastPositionMs = nowMs // the session save carries the position too
        return Action.SaveSession
    }

    private fun position(nowMs: Long): Action {
        lastPositionMs = nowMs
        return Action.SavePosition
    }
}
