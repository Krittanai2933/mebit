@file:OptIn(ExperimentalForeignApi::class)

package com.mebit.app

import androidx.compose.runtime.Composable
import cnames.structs.__CFData
import cnames.structs.__CFDictionary
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
import platform.Foundation.NSDate
import platform.Foundation.NSLock
import platform.LocalAuthentication.LAContext
import platform.Security.SecAccessControlCreateWithFlags
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecDuplicateItem
import platform.Security.errSecItemNotFound
import platform.Security.errSecParam
import platform.Security.errSecSuccess
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
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnAttributes
import platform.Security.kSecReturnData
import platform.Security.kSecUseAuthenticationContext
import platform.Security.kSecValueData

/** Never the wallet's service, because the wallet's probe lists everything under that one and its erase deletes it. */
private const val SERVICE = "com.mebit.app.vaults"

/** The account of this slot's copy of the book. */
private val Slot.book: String get() = if (this == Slot.A) "book.a" else "book.b"

/** The saved vaults in the Keychain, kept as the wallet is: add then delete across two named accounts, never SecItemUpdate and never a listing, for the reasons in `KeychainStore`. */
internal object KeychainVaults : VaultsStorage {
    private val lock = NSLock()

    /** The context the wallet's owner check just passed, which is what lets a read show no second sheet. */
    private var passed: LAContext? = null

    /** The slot holding the saved copy, as this process last learned it. */
    private var current: Slot? = null

    /** A delete this run left nothing saved, so a read needs no context. Without this or a context, a read must not answer "empty", or a later save would replace a book it never saw. */
    private var emptied = false

    internal fun ownerPassed(context: LAContext) {
        locked { passed = context }
    }

    override fun read(): String? = locked {
        val context = passed ?: return@locked if (emptied) "" else null
        val answers = Slot.entries.associateWith { readData(it, context) }
        if (answers.values.any { (status, _) -> status != errSecSuccess && status != errSecItemNotFound }) return@locked null
        passed = null
        val held = answers.entries.mapNotNull { (slot, answer) -> answer.second?.let { slot to it } }.toMap()
        // Two copies mean a crash between a write's add and its delete, so the newer one is the book.
        current = when (held.size) {
            0 -> null
            1 -> held.keys.single()
            else -> pickNewest(Slot.entries.associateWith { creationDate(it, context) }).also { delete(it.other) }
        }
        current?.let { held.getValue(it) }.orEmpty()
    }

    override fun write(text: String): Boolean = locked {
        val slot = current?.other ?: Slot.A
        val bytes = text.encodeToByteArray()
        var added = add(slot, bytes)
        if (added == errSecDuplicateItem) {
            delete(slot)
            added = add(slot, bytes)
        }
        if (added == errSecSuccess) {
            delete(slot.other)
            current = slot
            emptied = false
        }
        added == errSecSuccess
    }

    override fun delete(): Boolean = locked {
        val statuses = Slot.entries.map { delete(it) }
        passed = null
        current = null
        emptied = statuses.all { it == errSecSuccess || it == errSecItemNotFound }
        emptied
    }

    private inline fun <T> locked(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}

/** The Keychain store of the saved vaults. One per process, so every caller shares its lock. */
@Composable
internal actual fun rememberVaultsStorage(): VaultsStorage? = KeychainVaults

/** A data-only read of [slot] with [context], the query an iPhone answered with no second sheet: its status, and its text when it has one. */
private fun readData(slot: Slot, context: LAContext): Pair<Int, String?> = memScoped {
    val out = alloc<CFTypeRefVar>()
    val status = SecItemCopyMatching(
        query(slot.book, kSecReturnData to kCFBooleanTrue, kSecMatchLimit to kSecMatchLimitOne, kSecUseAuthenticationContext to owned(CFBridgingRetain(context))),
        out.ptr,
    )
    val data = owned(out.value)?.reinterpret<__CFData>()
    status to data?.let { (CFDataGetBytePtr(it)?.readBytes(CFDataGetLength(it).toInt()) ?: ByteArray(0)).decodeToString() }
}

/** When [slot]'s item was added, in seconds from 1 Jan 2001, by an attributes-only read with [context], or null if unreadable. */
private fun creationDate(slot: Slot, context: LAContext): Double? = memScoped {
    val out = alloc<CFTypeRefVar>()
    SecItemCopyMatching(
        query(slot.book, kSecReturnAttributes to kCFBooleanTrue, kSecMatchLimit to kSecMatchLimitOne, kSecUseAuthenticationContext to owned(CFBridgingRetain(context))),
        out.ptr,
    )
    val item = owned(out.value)?.reinterpret<__CFDictionary>() ?: return@memScoped null
    CFDictionaryGetValue(item, kSecAttrCreationDate)?.let { (CFBridgingRelease(CFRetain(it)) as? NSDate)?.timeIntervalSinceReferenceDate }
}

/** Adds [bytes] as [slot]'s item under the access control, the wallet's. */
private fun add(slot: Slot, bytes: ByteArray): Int {
    if (bytes.isEmpty()) return errSecParam // pinning needs an element, and an empty book is never written
    return bytes.usePinned { pinned ->
        memScoped {
            val access = owned(SecAccessControlCreateWithFlags(null, kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly, kSecAccessControlUserPresence, null))
                ?: return@memScoped errSecParam
            val data = owned(CFDataCreateWithBytesNoCopy(null, pinned.addressOf(0).reinterpret(), bytes.size.toLong(), kCFAllocatorNull))
            SecItemAdd(query(slot.book, kSecAttrAccessControl to access, kSecValueData to data), null)
        }
    }
}

private fun delete(slot: Slot): Int = memScoped { SecItemDelete(query(slot.book)) }

/** A query with what every query carries (generic password, the service, never synchronizable), [account], and [extra]. */
private fun MemScope.query(account: String, vararg extra: Pair<CFStringRef?, CFTypeRef?>): CFDictionaryRef? {
    val query = owned(CFDictionaryCreateMutable(null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr))
    CFDictionaryAddValue(query, kSecClass, kSecClassGenericPassword)
    CFDictionaryAddValue(query, kSecAttrService, cfString(SERVICE))
    CFDictionaryAddValue(query, kSecAttrSynchronizable, kCFBooleanFalse)
    CFDictionaryAddValue(query, kSecAttrAccount, cfString(account))
    extra.forEach { (key, value) -> CFDictionaryAddValue(query, key, value) }
    return query
}

private fun MemScope.cfString(text: String): CFStringRef? = owned(CFStringCreateWithCString(null, text, kCFStringEncodingUTF8))

private fun <T : CPointer<*>> MemScope.owned(ref: T?): T? = ref?.also { defer { CFRelease(it) } }
