@file:OptIn(UnstableApi::class)

package com.example.dink_smb_player.data.source

import androidx.annotation.OptIn
import androidx.media3.common.ParserException
import androidx.media3.common.util.UnstableApi
import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMBApiException
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.NoSuchFileException
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeoutException

/**
 * Outcome of a best-effort metadata read over the network (tags, cover art).
 *
 * The distinction that matters is [Absent] vs [Error]: [Absent] is a FACT about the file
 * (it has no tags / no picture, it's gone, or it can't be parsed) and is safe to remember
 * ("don't probe again"); [Error] is a transient failure (NAS down, timeout, dropped
 * connection) and must NOT be remembered as absent — otherwise one flaky read permanently
 * hides a file's art or tags.
 */
sealed interface ReadResult<out T> {
    data class Found<T>(val value: T) : ReadResult<T>
    data object Absent : ReadResult<Nothing>

    /** Transient failure. [partial] carries whatever WAS read before the failure (e.g. tags
     *  found by one reader while another timed out), so callers can still use it without
     *  treating the read as conclusive. */
    data class Error<T>(val cause: Throwable?, val partial: T? = null) : ReadResult<T>

    /** The usable value: [Found.value], or [Error.partial] — null for [Absent]. */
    fun valueOrNull(): T? = when (this) {
        is Found -> value
        is Error -> partial
        Absent -> null
    }
}

/** Classifies a read failure as transient ([ReadResult.Error]) or definitive ([ReadResult.Absent]). */
internal object ReadFailures {

    /** SMB statuses that describe the FILE (gone / unreadable), not the connection. */
    private val DEFINITIVE_SMB_STATUSES = setOf(
        NtStatus.STATUS_OBJECT_NAME_NOT_FOUND,
        NtStatus.STATUS_OBJECT_PATH_NOT_FOUND,
        NtStatus.STATUS_NO_SUCH_FILE,
        NtStatus.STATUS_NOT_FOUND,
        NtStatus.STATUS_ACCESS_DENIED,
        NtStatus.STATUS_FILE_IS_A_DIRECTORY,
        NtStatus.STATUS_OBJECT_NAME_INVALID,
    )

    /**
     * True when [t] is a transient failure worth retrying later: I/O, timeouts, dropped SMB
     * sessions, interruption, memory pressure. False for definitive outcomes — a missing
     * file, access denied, or content the extractor can't parse ([ParserException] is an
     * IOException subclass, so it's checked first). Unknown non-I/O exceptions are treated
     * as definitive: a file that deterministically crashes a parser shouldn't be re-read
     * on every pass. null (no failure recorded) is not transient.
     */
    fun isTransient(t: Throwable?): Boolean {
        if (t == null) return false
        // Pass 1: a definitive marker anywhere in the cause chain wins (they're usually
        // wrapped: ExecutionException → IOException → SMBApiException / FileNotFound).
        var e: Throwable? = t
        var depth = 0
        while (e != null && depth++ < 16) {
            when (e) {
                is ParserException -> return false
                is FileNotFoundException, is NoSuchFileException -> return false
                is SMBApiException -> return e.status !in DEFINITIVE_SMB_STATUSES
            }
            e = e.cause
        }
        // Pass 2: anything I/O-, time- or connection-shaped is transient.
        e = t
        depth = 0
        while (e != null && depth++ < 16) {
            if (e is IOException || e is TimeoutException || e is InterruptedException ||
                e is CancellationException || e is VirtualMachineError ||
                e.javaClass.name.startsWith("com.hierynomus.")
            ) return true
            e = e.cause
        }
        return false
    }
}
