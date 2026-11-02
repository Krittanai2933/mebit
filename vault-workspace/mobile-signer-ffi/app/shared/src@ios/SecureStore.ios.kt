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
import platform.Foundation.NSFileManager
import platform.Foundation.NSLock
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
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

/** The Keychain service every item of the wallet lives under. */
private const val SERVICE = "com.mebit.app.wallet"

/** Each item's account is this followed by its generation, counted from 1. */
private const val ACCOUNT_PREFIX = "wallet."

/** What the marker file holds for each marker it can hold. No other marker is ever written. */
private val MARKER_CONTENT = mapOf(Marker.Empty to "empty", Marker.Kept to "kept")

/**
 * True: [KeychainStore.read] asks for the owner with LocalAuthentication first and hands the passed context to the Keychain,
 * so the Face ID card shows and blocks on a simulator too, where the Keychain enforces no access control. Setting it to false
 * returns to the Keychain's own sheet, the fallback if an iPhone shows two sheets for one read.
 */
private const val EVALUATE_BEFORE_READ = true

/**
 * The wallet in the iOS Keychain. Each write is a generic-password item under [SERVICE] with account `wallet.<generation>`,
 * never synchronizable, protected by `SecAccessControl(WhenPasscodeSetThisDeviceOnly, UserPresence)` and never also by
 * `kSecAttrAccessible`, in the default access group with no entitlements file. So the item never syncs, never enters a backup,
 * never leaves this phone, is refused on a phone without a passcode, and the OS owner check runs inside [read].
 *
 * This is the default arrangement. The simulator enforces no access control, so the iPhone decides whether it holds, and
 * each fallback plugs in here without changing anything above [SecureStore]:
 * - if an add or a delete of this item shows a sheet, [write] and [readNewest] move to an EC P-256 key pair (the private half
 *   under this access control, the public half in its own item with none) and an ECIES blob in a plain ThisDeviceOnly item;
 * - [read] evaluates its LAContext before the Keychain read and hands it in, so the sheet shows on a simulator too; if that
 *   gives two sheets on the iPhone, not one, [EVALUATE_BEFORE_READ] returns to the Keychain's own sheet.
 *
 * One object and one lock per process, because the Keychain slot is one shared write target. Logs nothing.
 */
private object KeychainStore : SecureStore {
    /**
     * Taken by every call, so a Replace's add and deletes never interleave with another call. A read takes it for its Keychain
     * calls only, never while its owner check's card is up, so the card never blocks a probe or a flush on the main thread.
     */
    private val lock = NSLock()

    /** Application Support/mebit/wallet-marker. Application Support is in backups, which the reinstall rule needs. */
    private val markerFile =
        (NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true).first() as String) + "/mebit/wallet-marker"

    override fun probe(): StoreProbe = locked {
        val (status, accounts) = list()
        probeOutcome(status, accounts, passcodeSet())
    }

    override suspend fun read(reason: String): ReadResult {
        val context = LAContext().apply { localizedReason = reason }
        if (EVALUATE_BEFORE_READ) {
            if (!awaitActive()) return ReadResult.Cancelled
            ownerCheck(context, reason)?.let { return it }
        }
        return coroutineScope {
            // The Keychain call blocks while the OS sheet is up, so it runs off the caller's thread.
            val reading = async(Dispatchers.Default) { locked { readNewest(context) } }
            try {
                reading.await()
            } catch (e: CancellationException) {
                context.invalidate() // takes the sheet down, so the blocked call returns and frees the lock
                throw e
            }
        }
    }

    override fun write(bytes: ByteArray, mode: WriteMode): WriteResult = locked {
        val (listed, accounts) = list()
        // Without the list the newest generation is unknown, and a Replace could add one below it.
        if (listed != errSecSuccess && listed != errSecItemNotFound) return@locked writeOutcome(listed, mode, passcodeSet())
        val generation = generationToAdd(accounts, mode) ?: return@locked WriteResult.AlreadyExists
        // Add, then delete, and never SecItemUpdate, which may prompt for an item with this access control.
        val added = add(ACCOUNT_PREFIX + generation, bytes)
        if (added == errSecSuccess) accountsToDelete(accounts, generation).forEach { delete(it) }
        writeOutcome(added, mode, passcodeSet())
    }

    override fun erase(): EraseResult = locked { eraseOutcome(delete(null)) }

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

    /** Runs [block] holding the store's lock. It never suspends, so the lock is released on the thread that took it. */
    private inline fun <T> locked(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    /** The newest generation's bytes, read with [context], which carries the sheet's reason. Blocks while the sheet is up. */
    private fun readNewest(context: LAContext): ReadResult = memScoped {
        val (listed, accounts) = list()
        val newest = newestGeneration(accounts) ?: return@memScoped readOutcome(listed, null)
        val out = alloc<CFTypeRefVar>()
        val status = SecItemCopyMatching(
            query(ACCOUNT_PREFIX + newest, kSecReturnData to kCFBooleanTrue, kSecMatchLimit to kSecMatchLimitOne, kSecUseAuthenticationContext to owned(CFBridgingRetain(context))),
            out.ptr,
        )
        val data = owned(out.value)?.reinterpret<__CFData>()
        readOutcome(status, data?.let { CFDataGetBytePtr(it)?.readBytes(CFDataGetLength(it).toInt()) ?: ByteArray(0) })
    }
}

/** The Keychain store. One per process, so every caller shares its lock. */
@Composable
internal actual fun rememberSecureStore(): SecureStore = KeychainStore

/** The status of a listing of every item under the service, and their accounts. Never prompts. */
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

/** An item's account, or "" when it has none, so it still counts as an item but never as a generation. */
private fun accountOf(item: COpaquePointer?): String =
    CFDictionaryGetValue(item?.reinterpret<__CFDictionary>(), kSecAttrAccount)?.let { CFBridgingRelease(CFRetain(it)) as? String } ?: ""

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

/** What the probe makes of its listing. Any item is Present; with none, a phone without a passcode cannot hold one. */
internal fun probeOutcome(status: Int, accounts: List<String>, passcodeSet: Boolean): StoreProbe = when {
    accounts.isNotEmpty() -> StoreProbe.Present
    status == errSecSuccess || status == errSecItemNotFound -> if (passcodeSet) StoreProbe.Empty else StoreProbe.Unavailable(Why.NoScreenLock)
    status == errSecInteractionNotAllowed || status == errSecNotAvailable -> StoreProbe.Unavailable(Why.TryLater)
    else -> StoreProbe.Unavailable(Why.Other)
}

/** What a read's status means. [data] is the copy of the item's bytes, present only on success. */
internal fun readOutcome(status: Int, data: ByteArray?): ReadResult = when (status) {
    errSecSuccess -> data?.let { ReadResult.Bytes(it) } ?: ReadResult.NotFound
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

/** What a write's status means. Only a Create can meet a wallet that is already there; a duplicate under Replace is a fault. */
internal fun writeOutcome(status: Int, mode: WriteMode, passcodeSet: Boolean): WriteResult = when {
    status == errSecSuccess -> WriteResult.Written
    status == errSecDuplicateItem && mode == WriteMode.Create -> WriteResult.AlreadyExists
    !passcodeSet -> WriteResult.Unavailable(Why.NoScreenLock)
    else -> WriteResult.Unavailable(Why.Other)
}

/** What an erase's status means. Nothing left to delete counts as erased, so erasing twice is safe. */
internal fun eraseOutcome(status: Int): EraseResult =
    if (status == errSecSuccess || status == errSecItemNotFound) EraseResult.Erased else EraseResult.Failed(Why.Other)

/** The newest wallet generation among [accounts], or null when none is one. */
internal fun newestGeneration(accounts: List<String>): Int? = accounts.mapNotNull(::generationOf).maxOrNull()

/** The generation a write adds, or null when a Create meets a wallet that is already there. */
internal fun generationToAdd(accounts: List<String>, mode: WriteMode): Int? =
    if (mode == WriteMode.Create && accounts.any { it.startsWith(ACCOUNT_PREFIX) }) null else (newestGeneration(accounts) ?: 0) + 1

/** What a write deletes after adding generation [added]: every older generation, and nothing else. */
internal fun accountsToDelete(accounts: List<String>, added: Int): List<String> =
    accounts.filter { account -> generationOf(account)?.let { it < added } ?: false }

/** What the marker file's [content] says. Anything but the two words, or a file that could not be read (null), is doubt. */
internal fun markerOf(content: String?): Marker = MARKER_CONTENT.entries.firstOrNull { it.value == content }?.key ?: Marker.Unreadable

/** What [marker] is written as, or null for a marker the file never holds. */
internal fun markerContent(marker: Marker): String? = MARKER_CONTENT[marker]

/** The generation in a canonical `wallet.<n>` account (n from 1, no sign, no leading zero), or null for any other account. */
private fun generationOf(account: String): Int? {
    if (!account.startsWith(ACCOUNT_PREFIX)) return null
    val digits = account.substring(ACCOUNT_PREFIX.length)
    return digits.toIntOrNull()?.takeIf { it > 0 && it.toString() == digits }
}
