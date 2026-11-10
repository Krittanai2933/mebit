@file:OptIn(ExperimentalForeignApi::class)

package com.mebit.app

import androidx.compose.runtime.Composable
import cnames.structs.__CFArray
import cnames.structs.__CFData
import cnames.structs.__CFDictionary
import kotlin.coroutines.resume
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import platform.CoreFoundation.CFArrayGetCount
import platform.CoreFoundation.CFArrayGetValueAtIndex
import platform.CoreFoundation.CFDataCreateWithBytesNoCopy
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryGetValue
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFRetain
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFAllocatorNull
import platform.CoreFoundation.kCFBooleanFalse
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSLock
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSThread
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dataWithBytes
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile
import platform.LocalAuthentication.LAContext
import platform.LocalAuthentication.LAErrorAppCancel
import platform.LocalAuthentication.LAErrorAuthenticationFailed
import platform.LocalAuthentication.LAErrorBiometryLockout
import platform.LocalAuthentication.LAErrorPasscodeNotSet
import platform.LocalAuthentication.LAErrorSystemCancel
import platform.LocalAuthentication.LAErrorUserCancel
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthentication
import platform.Security.SecAccessControlCreateWithFlags
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecAuthFailed
import platform.Security.errSecDuplicateItem
import platform.Security.errSecInteractionNotAllowed
import platform.Security.errSecItemNotFound
import platform.Security.errSecNotAvailable
import platform.Security.errSecParam
import platform.Security.errSecSuccess
import platform.Security.errSecUserCanceled
import platform.Security.kSecAccessControlUserPresence
import platform.Security.kSecAttrAccessControl
import platform.Security.kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrCreationDate
import platform.Security.kSecAttrService
import platform.Security.kSecAttrSynchronizable
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitAll
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnAttributes
import platform.Security.kSecReturnData
import platform.Security.kSecUseAuthenticationContext
import platform.Security.kSecValueData
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationState
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_sync

/** The Keychain service every item of the wallet lives under. */
private const val SERVICE = "com.mebit.app.wallet"

/** What the marker file holds for each marker it can hold. No other marker is ever written. */
private val MARKER_CONTENT = mapOf(Marker.Empty to "empty", Marker.Kept to "kept")

/**
 * True: [KeychainShelf] asks for the owner with LocalAuthentication first and hands the passed context to the Keychain, so the
 * Face ID card shows and blocks on a simulator too, where the Keychain enforces no access control. Setting it to false returns
 * to the Keychain's own sheet, the fallback if an iPhone shows two sheets for one read.
 */
private const val EVALUATE_BEFORE_READ = true

/**
 * The wallets in the iOS Keychain, one place per wallet. Each write is a generic-password item under [SERVICE] in one of the
 * two accounts of [Slot] at its place ([account]), never synchronizable, protected by
 * `SecAccessControl(WhenPasscodeSetThisDeviceOnly, UserPresence)` and never also by `kSecAttrAccessible`, in the default access
 * group with no entitlements file. So the item never syncs, never enters a backup, never leaves this phone, is refused on a
 * phone without a passcode, and the OS owner check runs inside a read.
 *
 * On an iPhone no listing of these items is both silent and useful (2 Nov 2026): a list under a non-interactive LAContext answers
 * errSecInteractionNotAllowed and returns no item, and a list with no context is slow and likely shows a sheet. So every read
 * and write names its accounts and never lists. Only the probe lists, silently, and [probeOutcome] reads that refusal on an
 * unlocked phone as an item that needs its owner.
 *
 * This is the default arrangement. The simulator enforces no access control, so the iPhone decides whether it holds, and
 * each fallback plugs in here without changing anything above [SecureStore]:
 * - if an add or a delete of this item shows a sheet, the writes and reads move to an EC P-256 key pair (the private half
 *   under this access control, the public half in its own item with none) and an ECIES blob in a plain ThisDeviceOnly item;
 * - a read evaluates its LAContext before the Keychain read and hands it in, so the sheet shows on a simulator too; if that
 *   gives two sheets on the iPhone, not one, [EVALUATE_BEFORE_READ] returns to the Keychain's own sheet.
 *
 * One object and one lock per process. Logs nothing.
 */
private object KeychainShelf : SecureShelf {
    /**
     * Taken by every call, so a write's adds and deletes never interleave with another call. A read takes it for its Keychain
     * calls only, never while its owner check's card is up, so the card never blocks a probe or a flush on the main thread.
     */
    private val lock = NSLock()

    /** Which copy each place's two slots hold, as this process counts it. Guarded by [lock]. One per process, because this object is. */
    private val copies: Map<Place, CopyCounter> = Place.all.associateWith { CopyCounter() }

    private val stores: Map<Place, SecureStore> = Place.all.associateWith { KeychainPlace(it) }

    /** Application Support/mebit/wallet-marker. Application Support is in backups, which the reinstall rule needs. */
    private val markerFile =
        (NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true).first() as String) + "/mebit/wallet-marker"

    override fun store(place: Place): SecureStore = stores.getValue(place)

    /**
     * One owner check and one context for every place, read by name, so no listing is involved. An absent account answers
     * -25300 silently, so this is how the app learns which wallets exist, and it needs no list stored anywhere.
     */
    override suspend fun readEvery(reason: String): EveryRead {
        val context = LAContext().apply { localizedReason = reason }
        ownerPassed(context, reason)?.let { return EveryRead.Refused(it) }
        val read = offTheCaller(context) { Place.all.associateWith { place -> locked { readSlots(place, context) } } }
        return EveryRead.Found(read.filterValues { it != ReadResult.NotFound })
    }

    /** One SecItemDelete of the whole service, the call that already passed on the iPhone. */
    override fun eraseEvery(): Boolean = locked {
        copies.values.forEach { it.advance() }
        delete(null).let { it == errSecSuccess || it == errSecItemNotFound }
    }

    /** Asks for the owner on [context] and gives the book the same context. Null when the owner passed, else what the read answers. */
    private suspend fun ownerPassed(context: LAContext, reason: String): ReadResult? {
        if (!EVALUATE_BEFORE_READ) return null
        if (!awaitActive()) return ReadResult.Cancelled
        ownerCheck(context, reason)?.let { return it }
        KeychainVaults.ownerPassed(context)
        return null
    }

    /** Runs [read] off the caller's thread, because a Keychain call blocks while the OS sheet is up. */
    private suspend fun <T> offTheCaller(context: LAContext, read: () -> T): T = coroutineScope {
        val reading = async(Dispatchers.Default) { read() }
        try {
            reading.await()
        } catch (e: CancellationException) {
            context.invalidate() // takes the sheet down, so the blocked call returns and frees the lock
            throw e
        }
    }

    /** Runs [block] holding the store's lock. It never suspends, so the lock is released on the thread that took it. */
    private inline fun <T> locked(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    /**
     * [place]'s bytes, read from each slot by name with [context], which carries the sheet's reason, so nothing is listed.
     * Blocks while a sheet is up. Every copy it does not return is zeroed.
     */
    private fun readSlots(place: Place, context: LAContext): ReadResult {
        val version = copies.getValue(place).current
        val reads = Slot.entries.associateWith { readData(place.account(it), context, version) }
        val result = slotsOutcome(reads) {
            // Two copies mean a write's delete did not happen, so the older copy is deleted here, best effort. The counter does not
            // advance: both copies are this process's own writer's, and advancing would give that writer a false Conflict.
            pickNewest(Slot.entries.associateWith { creationDate(place.account(it), context) }).also { delete(place.account(it.other)) }
        }
        reads.values.forEach { if (it !== result) (it as? ReadResult.Bytes)?.bytes?.fill(0) }
        return result
    }

    private class KeychainPlace(private val place: Place) : SecureStore {
        override fun probe(): StoreProbe {
            val unlocked = protectedDataAvailable()
            return locked { silentProbe(unlocked) }
        }

        override suspend fun read(reason: String): ReadResult {
            val context = LAContext().apply { localizedReason = reason }
            ownerPassed(context, reason)?.let { return it }
            return offTheCaller(context) { locked { readSlots(place, context) } }
        }

        override fun write(bytes: ByteArray, mode: WriteMode): WriteResult = locked {
            val copy = copies.getValue(place)
            copy.refuse(mode)?.let { return@locked it } // a stale Replace makes no Keychain call
            val status = if (mode == WriteMode.Create) {
                createInBothSlots({ add(place.account(it), bytes) }, { delete(place.account(it)) })
            } else {
                replaceInFreeSlot(place, bytes)
            }
            // An add that succeeded advances even if its delete of the other slot failed, because the phone did change.
            writeRefusal(status, mode, passcodeSet()) ?: WriteResult.Written(copy.advance())
        }

        /** Deletes this place's two accounts by name, so the other places' items are not touched. */
        override fun erase(): EraseResult = locked {
            val version = copies.getValue(place).advance()
            val statuses = Slot.entries.map { delete(place.account(it)) }
            eraseOutcome(statuses.firstOrNull { it != errSecSuccess && it != errSecItemNotFound } ?: errSecSuccess, version)
        }

        override fun marker(): Marker = locked {
            if (!NSFileManager.defaultManager.fileExistsAtPath(markerFile)) {
                Marker.Absent
            } else {
                markerOf(NSString.stringWithContentsOfFile(markerFile, encoding = NSUTF8StringEncoding, error = null))
            }
        }

        override fun writeMarker(marker: Marker): Boolean = locked {
            val content = markerContent(marker)?.encodeToByteArray() ?: return@locked false
            val data = content.usePinned { NSData.dataWithBytes(it.addressOf(0), content.size.toULong()) }
            NSFileManager.defaultManager.createDirectoryAtPath(markerFile.substringBeforeLast('/'), withIntermediateDirectories = true, attributes = null, error = null) &&
                data.writeToFile(markerFile, atomically = true)
        }
    }
}

/** The Keychain shelf. One per process, so every caller shares its lock. */
@Composable
internal actual fun rememberSecureShelf(): SecureShelf = KeychainShelf

/**
 * The status of a listing of every item under the service, and their accounts. Never prompts. A simulator lists the wallet's
 * item; an iPhone answers errSecInteractionNotAllowed and lists nothing while the item is there (2 Nov 2026).
 */
private fun list(): Pair<Int, List<String>> = memScoped {
    val out = alloc<CFTypeRefVar>()
    val silent = LAContext().apply { interactionNotAllowed = true }
    val status = SecItemCopyMatching(
        query(null, kSecReturnAttributes to kCFBooleanTrue, kSecMatchLimit to kSecMatchLimitAll, kSecUseAuthenticationContext to owned(CFBridgingRetain(silent))),
        out.ptr,
    )
    val items = owned(out.value)?.reinterpret<__CFArray>() ?: return@memScoped status to emptyList()
    status to List(CFArrayGetCount(items).toInt()) { accountOf(CFArrayGetValueAtIndex(items, it.toLong())) }
}

/** An item's account, or "" when it has none, so it still counts as an item. */
private fun accountOf(item: COpaquePointer?): String =
    CFDictionaryGetValue(item?.reinterpret<__CFDictionary>(), kSecAttrAccount)?.let { CFBridgingRelease(CFRetain(it)) as? String } ?: ""

/** What the silent [list] says the store holds. [unlocked] comes from [protectedDataAvailable]. Never prompts. */
private fun silentProbe(unlocked: Boolean): StoreProbe {
    val (status, accounts) = list()
    return probeOutcome(status, accounts, passcodeSet(), unlocked)
}

/**
 * Whether the phone is unlocked, so that a refused [list] means an item that needs its owner rather than a locked Keychain.
 * UIKit wants the main thread, so a call from another thread waits for it. Read it before taking the store's lock, because
 * the main thread may itself be waiting for that lock.
 */
private fun protectedDataAvailable(): Boolean {
    if (NSThread.isMainThread) return UIApplication.sharedApplication.protectedDataAvailable
    var available = false
    dispatch_sync(dispatch_get_main_queue()) { available = UIApplication.sharedApplication.protectedDataAvailable }
    return available
}

/** A data-only read of [account] with [context]: the query an iPhone answered with no second sheet once the context had passed. */
private fun readData(account: String, context: LAContext, version: StoreVersion): ReadResult = memScoped {
    val out = alloc<CFTypeRefVar>()
    val status = SecItemCopyMatching(
        query(account, kSecReturnData to kCFBooleanTrue, kSecMatchLimit to kSecMatchLimitOne, kSecUseAuthenticationContext to owned(CFBridgingRetain(context))),
        out.ptr,
    )
    val data = owned(out.value)?.reinterpret<__CFData>()
    readOutcome(status, data?.let { CFDataGetBytePtr(it)?.readBytes(CFDataGetLength(it).toInt()) ?: ByteArray(0) }, version)
}

/** When [account]'s item was added, in seconds from 1 Jan 2001, by an attributes-only read with [context], or null if unreadable. */
private fun creationDate(account: String, context: LAContext): Double? = memScoped {
    val out = alloc<CFTypeRefVar>()
    SecItemCopyMatching(
        query(account, kSecReturnAttributes to kCFBooleanTrue, kSecMatchLimit to kSecMatchLimitOne, kSecUseAuthenticationContext to owned(CFBridgingRetain(context))),
        out.ptr,
    )
    val item = owned(out.value)?.reinterpret<__CFDictionary>() ?: return@memScoped null
    CFDictionaryGetValue(item, kSecAttrCreationDate)?.let { (CFBridgingRelease(CFRetain(it)) as? NSDate)?.timeIntervalSinceReferenceDate }
}

/**
 * A Replace: adds [bytes] to the slot [slotToAdd] names, moving on while an add answers duplicate, and returns the last add's status,
 * then deletes the other slot, which held the copy this one replaces, best effort. Never SecItemUpdate, which may prompt
 * for an item with this access control.
 */
private tailrec fun replaceInFreeSlot(place: Place, bytes: ByteArray, taken: Set<Slot> = emptySet()): Int {
    val slot = slotToAdd(taken) ?: return errSecDuplicateItem
    val added = add(place.account(slot), bytes)
    if (added == errSecDuplicateItem) return replaceInFreeSlot(place, bytes, taken + slot)
    if (added == errSecSuccess) delete(place.account(slot.other))
    return added
}

/** Adds [bytes] as [account] under the access control, without copying them: the caller zeroes its array afterwards. */
private fun add(account: String, bytes: ByteArray): Int {
    if (bytes.isEmpty()) return errSecParam // pinning needs an element, and no wallet record is empty
    return bytes.usePinned { pinned ->
        memScoped {
            val access = owned(SecAccessControlCreateWithFlags(null, kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly, kSecAccessControlUserPresence, null))
                ?: return@memScoped errSecParam
            val data = owned(CFDataCreateWithBytesNoCopy(null, pinned.addressOf(0).reinterpret(), bytes.size.toLong(), kCFAllocatorNull))
            SecItemAdd(query(account, kSecAttrAccessControl to access, kSecValueData to data), null)
        }
    }
}

/** Deletes [account], or every item under the service when it is null. */
private fun delete(account: String?): Int = memScoped { SecItemDelete(query(account)) }

/** Whether this phone has a passcode, without which an item of this class cannot exist. Never prompts. */
private fun passcodeSet(): Boolean = LAContext().canEvaluatePolicy(LAPolicyDeviceOwnerAuthentication, null)

/**
 * Whether the app became active within 3 s, polled on the main queue every 100 ms, so the owner check is never asked from an
 * app that is not active, as at a cold start's first frame. iOS may cancel it there; the simulator does not.
 */
private suspend fun awaitActive(): Boolean = withTimeoutOrNull(3_000) {
    withContext(Dispatchers.Main) {
        while (UIApplication.sharedApplication.applicationState != UIApplicationState.UIApplicationStateActive) delay(100)
    }
    true
} ?: false

/**
 * Asks for the owner on [context], Face ID first and the passcode inside the same OS card. Null when the owner passed,
 * else what the read answers. Cancelling the caller takes the card down.
 */
private suspend fun ownerCheck(context: LAContext, reason: String): ReadResult? = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { context.invalidate() }
    context.evaluatePolicy(LAPolicyDeviceOwnerAuthentication, localizedReason = reason) { ok, error ->
        // A refusal that names no error still refuses, and never goes on to the read.
        continuation.resume(if (ok) null else evaluateOutcome(error?.code) ?: ReadResult.Unavailable(Why.Other))
    }
}

/** A query with what every query carries (generic password, the service, never synchronizable), [account] when given, and [extra]. */
private fun MemScope.query(account: String?, vararg extra: Pair<CFStringRef?, CFTypeRef?>): CFDictionaryRef? {
    val query = owned(CFDictionaryCreateMutable(null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr))
    CFDictionaryAddValue(query, kSecClass, kSecClassGenericPassword)
    CFDictionaryAddValue(query, kSecAttrService, cfString(SERVICE))
    CFDictionaryAddValue(query, kSecAttrSynchronizable, kCFBooleanFalse)
    if (account != null) CFDictionaryAddValue(query, kSecAttrAccount, cfString(account))
    extra.forEach { (key, value) -> CFDictionaryAddValue(query, key, value) }
    return query
}

/** [text] as a CFString that this scope releases. */
private fun MemScope.cfString(text: String): CFStringRef? = owned(CFStringCreateWithCString(null, text, kCFStringEncodingUTF8))

/** Releases [ref] when this scope ends, so every CF object a call creates is freed on every path. */
private fun <T : CPointer<*>> MemScope.owned(ref: T?): T? = ref?.also { defer { CFRelease(it) } }

/**
 * What the probe makes of its silent listing. Any item listed is Present, as on a simulator. A refused listing with none is an
 * item that needs its owner while the phone is unlocked, which is how an iPhone answers for this access control, and TryLater
 * while it is locked, which answers the same. With nothing found, a phone without a passcode cannot hold a wallet.
 */
internal fun probeOutcome(status: Int, accounts: List<String>, passcodeSet: Boolean, protectedDataAvailable: Boolean): StoreProbe = when {
    accounts.isNotEmpty() -> StoreProbe.Present
    status == errSecSuccess || status == errSecItemNotFound -> if (passcodeSet) StoreProbe.Empty else StoreProbe.Unavailable(Why.NoScreenLock)
    status == errSecInteractionNotAllowed -> if (protectedDataAvailable) StoreProbe.Present else StoreProbe.Unavailable(Why.TryLater)
    status == errSecNotAvailable -> StoreProbe.Unavailable(Why.TryLater)
    else -> StoreProbe.Unavailable(Why.Other)
}

/** What a read's status means. [data] is the copy of the item's bytes, present only on success, and [version] the copy it came from. */
internal fun readOutcome(status: Int, data: ByteArray?, version: StoreVersion): ReadResult = when (status) {
    errSecSuccess -> data?.let { ReadResult.Bytes(it, version) } ?: ReadResult.NotFound
    errSecItemNotFound -> ReadResult.NotFound
    errSecUserCanceled -> ReadResult.Cancelled
    errSecAuthFailed -> ReadResult.Denied
    errSecInteractionNotAllowed -> ReadResult.Unavailable(Why.TryLater)
    else -> ReadResult.Unavailable(Why.Other)
}

/** What the owner check's LocalAuthentication error code means for the read. Null is no error: the owner passed and the read goes on. */
internal fun evaluateOutcome(laErrorCode: Long?): ReadResult? = when (laErrorCode) {
    null -> null
    LAErrorUserCancel, LAErrorSystemCancel, LAErrorAppCancel -> ReadResult.Cancelled
    LAErrorAuthenticationFailed -> ReadResult.Denied
    LAErrorBiometryLockout -> ReadResult.LockedOut
    LAErrorPasscodeNotSet -> ReadResult.Unavailable(Why.NoScreenLock)
    else -> ReadResult.Unavailable(Why.Other)
}

/** What stops a write, from its add's status, or null when the add succeeded. Only a Create can meet a wallet that is already there; a duplicate under Replace is a fault. */
internal fun writeRefusal(status: Int, mode: WriteMode, passcodeSet: Boolean): WriteResult? = when {
    status == errSecSuccess -> null
    status == errSecDuplicateItem && mode == WriteMode.Create -> WriteResult.AlreadyExists
    !passcodeSet -> WriteResult.Unavailable(Why.NoScreenLock)
    else -> WriteResult.Unavailable(Why.Other)
}

/** What an erase's status means. Nothing left to delete counts as erased, so erasing twice is safe. */
internal fun eraseOutcome(status: Int, version: StoreVersion): EraseResult =
    if (status == errSecSuccess || status == errSecItemNotFound) EraseResult.Erased else EraseResult.Failed(Why.Other, version)

/**
 * The two accounts under [SERVICE] that can hold place 1's wallet. A Replace adds to the free one and then deletes the other, so two
 * copies exist only when that delete did not happen (a crash between the calls, or a failed delete), and no call needs a
 * listing to find the wallet.
 */
internal enum class Slot(val account: String) {
    A("wallet.a"),
    B("wallet.b"),
    ;

    /** The slot a write empties after adding to this one. */
    val other: Slot get() = if (this == A) B else A
}

/** The account of [slot] at this place. Place 1 keeps today's two accounts, so the wallet already on a phone is read by the same call as before. */
internal fun Place.account(slot: Slot): String = if (this == Place.FIRST) slot.account else "wallet.$number." + slot.account.substringAfterLast('.')

/**
 * A Create at one place: adds to slot A, adds to slot B, and only then deletes B. So a copy in either slot answers duplicate, and
 * the failed add's partner is undone, leaving the phone as it was, and a Create can never land beside a wallet already there.
 * A failure to delete B leaves two equal copies, which the next read settles. Uses only SecItemAdd and SecItemDelete, which
 * never prompted on the iPhone. Returns the first status that was not a success.
 */
internal fun createInBothSlots(add: (Slot) -> Int, delete: (Slot) -> Unit): Int {
    val first = add(Slot.A)
    if (first != errSecSuccess) return first
    val second = add(Slot.B)
    if (second != errSecSuccess) {
        delete(Slot.A)
        return second
    }
    delete(Slot.B)
    return errSecSuccess
}

/** The slot a Replace adds to, given the slots whose add answered duplicate: the first not taken, or null when there is none. */
internal fun slotToAdd(taken: Set<Slot>): Slot? = Slot.entries.firstOrNull { it !in taken }

/**
 * What a read of both slots answers. One slot with bytes holds the wallet. Two (a write's add whose delete did not happen) go
 * to [newest], which reads the creation dates, so that read happens on this rare path only. With none it is NotFound when
 * both slots found nothing, else the first slot's other answer, so a refusal is never taken for a missing wallet.
 */
internal fun slotsOutcome(reads: Map<Slot, ReadResult>, newest: () -> Slot): ReadResult {
    val inOrder = Slot.entries.mapNotNull { reads[it] }
    val copies = inOrder.filterIsInstance<ReadResult.Bytes>()
    return when (copies.size) {
        0 -> inOrder.firstOrNull { it != ReadResult.NotFound } ?: ReadResult.NotFound
        1 -> copies.single()
        else -> reads.getValue(newest())
    }
}

/**
 * The slot whose copy was added last, from each slot's creation date in seconds. A date that could not be read loses to any
 * that could, and a tie goes to A, so two copies always settle to one. Both copies are the same wallet and differ at most in
 * its flags and its payment of unknown outcome, so a wrong pick (a clock moved back between the two adds) loses only those.
 */
internal fun pickNewest(dates: Map<Slot, Double?>): Slot =
    Slot.entries.filter { it in dates }.maxBy { dates[it] ?: Double.NEGATIVE_INFINITY }

/** What the marker file's [content] says. Anything but the two words, or a file that could not be read (null), is doubt. */
internal fun markerOf(content: String?): Marker = MARKER_CONTENT.entries.firstOrNull { it.value == content }?.key ?: Marker.Unreadable

/** What [marker] is written as, or null for a marker the file never holds. */
internal fun markerContent(marker: Marker): String? = MARKER_CONTENT[marker]
