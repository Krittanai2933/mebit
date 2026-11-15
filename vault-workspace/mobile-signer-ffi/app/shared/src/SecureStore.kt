package com.mebit.app

import androidx.compose.runtime.Composable

/** Why this phone cannot hold or release a wallet right now. A closed set, so no message can ever carry data. */
enum class Why { NoScreenLock, OsTooOld, NotWired, TryLater, Other }

/**
 * One numbered place for a wallet on this phone, and the wallet's whole identity here. It names the secure item or file, the
 * wallet's name on screen and its Stops on the Back stack, and it is never stored in a record. The first wallet keeps place 1.
 * Ten, because a cold start reads every one of them. Raising the number later needs no migration.
 */
class Place private constructor(val number: Int) {
    override fun toString(): String = "Place $number"

    companion object {
        const val COUNT = 10
        val all: List<Place> = (1..COUNT).map(::Place)
        val FIRST: Place = all.first()

        /** The place this number names, or null. For a number read back from a file. */
        fun parse(number: Int): Place? = all.getOrNull(number - 1)
    }
}

/** The one place that turns a wallet's number into Thai. */
val Place.walletName: String get() = if (this == Place.FIRST) "กระเป๋าใช้จ่าย" else "กระเป๋า $number"

/** Which copy the store holds, as counted by this process. Opaque. Never written to disk, never compared across launches. */
data class StoreVersion internal constructor(private val n: Long)

/**
 * The copy counter of one store. Exactly one per process per store, beside the store's lock, touched only under it.
 * Advances when a write took effect and at every erase, and at nothing else, so the session that wrote last can always write again.
 */
internal class CopyCounter {
    private var n = 0L

    val current: StoreVersion get() = StoreVersion(n)

    /** Conflict for a Replace that does not expect [current]. Null for a Create, whose existence rule stays the store's own. */
    fun refuse(mode: WriteMode): WriteResult.Conflict? = if (mode is WriteMode.Replace && mode.expected != current) WriteResult.Conflict else null

    /** After a write that took effect, and at every erase whatever it answered. Returns the new [current]. */
    fun advance(): StoreVersion = StoreVersion(++n)
}

/** How a write treats a wallet that is already at its place. */
sealed interface WriteMode {
    /** Fails with [WriteResult.AlreadyExists] if a wallet is at this place. */
    data object Create : WriteMode

    /** The same wallet with new data, only while the store still holds [expected]. Creates it if absent and nothing advanced since. */
    class Replace(val expected: StoreVersion) : WriteMode
}

/**
 * A small file the iOS actual keeps in Application Support, included in backups, to tell a fresh install from a used one.
 * Android reports Unsupported. [Unreadable] is doubt: the file could not be read, which is not the same as no file ([Absent]),
 * and nothing is ever wiped on it.
 */
enum class Marker { Absent, Empty, Kept, Unsupported, Unreadable }

/** What the phone holds, learned without a prompt. */
sealed interface StoreProbe {
    data object Empty : StoreProbe

    data object Present : StoreProbe

    /** Ciphertext without its OS key (Android). */
    data object KeyGone : StoreProbe

    class Unavailable(val why: Why) : StoreProbe
}

/** How a read ended. Only [Bytes] carries data, and its array belongs to the caller, which zeroes it. */
sealed interface ReadResult {
    /** [version] is the copy these bytes came from, taken under the store's lock in the same step as the bytes. */
    class Bytes(val bytes: ByteArray, val version: StoreVersion) : ReadResult

    data object NotFound : ReadResult

    data object Cancelled : ReadResult

    data object Denied : ReadResult

    data object LockedOut : ReadResult

    data object KeyGone : ReadResult

    /** The platform saw damage: a GCM tag that fails, a truncated envelope. */
    data object Unreadable : ReadResult

    class Unavailable(val why: Why) : ReadResult
}

/** How a write ended. */
sealed interface WriteResult {
    class Written(val version: StoreVersion) : WriteResult

    data object AlreadyExists : WriteResult

    /** A Replace whose expected copy is gone or changed. Nothing was written or deleted. */
    data object Conflict : WriteResult

    class Unavailable(val why: Why) : WriteResult
}

/** How an erase ended. */
sealed interface EraseResult {
    data object Erased : EraseResult

    /** The copy is still there, under [version]: the erase advanced the counter, so a session that keeps writing to that copy must expect this one. */
    class Failed(val why: Why, val version: StoreVersion) : EraseResult
}

/**
 * The secure store of one [Place]. [read], [write] and [erase] reach this place's bytes and no other's. [probe], [marker] and
 * [writeMarker] speak for the whole phone, because the phone has one listing and one marker. Bytes that only this phone's
 * owner can read back. Contract for every actual:
 * - never throws (every failure becomes a result) and never logs bytes;
 * - device-only: no cloud sync, no backup, no transfer to another phone;
 * - gate or nothing: a phone that cannot enforce an owner check on [read] reports Unavailable and stores nothing;
 * - [write] never prompts and is all or nothing; [read] may show the OS sheet and never blocks the main thread;
 * - a [WriteMode.Replace] takes effect only while the store still holds its expected copy, checked under the lock the write
 *   holds, else [WriteResult.Conflict] and nothing changes;
 * - [erase] is idempotent and the only thing that deletes, and it advances the copy whatever it answers.
 */
internal interface SecureStore {
    /** Never prompts. */
    fun probe(): StoreProbe

    suspend fun read(reason: String): ReadResult

    fun write(bytes: ByteArray, mode: WriteMode): WriteResult

    fun erase(): EraseResult

    /** Never prompts. [Marker.Absent] only when there is no marker file. A file that cannot be read is [Marker.Unreadable], never Absent. */
    fun marker(): Marker

    /** Atomic and never prompts. True when the marker now reads as [marker], and also where the marker is Unsupported, because there it is a no-op. False when the write did not take effect. */
    fun writeMarker(marker: Marker): Boolean
}

/** How one owner check over every place ended. */
internal sealed interface EveryRead {
    /** The owner check did not pass, so nothing was read. Never [ReadResult.Bytes]. */
    class Refused(val result: ReadResult) : EveryRead

    /** The owner passed. Holds every place that answered anything but NotFound, so empty means no place holds a wallet. */
    class Found(val places: Map<Place, ReadResult>) : EveryRead
}

/**
 * Every place on this phone, and what no single place can do. A session keeps the store it was given by [store], so a write can
 * name no other wallet.
 */
internal interface SecureShelf {
    fun store(place: Place): SecureStore

    /** One OS sheet at most on a healthy phone, then every place. Never holds a lock while the sheet is up. Every Bytes array belongs to the caller, which zeroes it. */
    suspend fun readEvery(reason: String): EveryRead

    /** Deletes every place, whatever each holds, and advances every place's copy counter. True when nothing is left. */
    fun eraseEvery(): Boolean
}

/** This platform's shelf. Composable because the Android one needs a Context. */
@Composable
internal expect fun rememberSecureShelf(): SecureShelf
