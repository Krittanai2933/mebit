@file:OptIn(ExperimentalForeignApi::class)

package com.mebit.app

import androidx.compose.runtime.Composable
import cnames.structs.__CFData
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
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFRelease
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
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecDuplicateItem
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecAttrSynchronizable
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/** Its own service, so neither the wallet's erase nor the book's touches a credential, and one delete of the service wipes them all. */
private const val SERVICE = "com.mebit.app.pairing"

/**
 * One generic password per credential, account [pairingName]: readable while the phone has a passcode, with no user check, because the
 * Safe 7 itself asks to allow each connect. ThisDeviceOnly and never synchronizable, so no backup or other device ever holds one.
 */
internal object KeychainPairings : Pairings {
    override fun read(fingerprint: String): ByteArray? {
        val account = pairingName(fingerprint) ?: return null
        return memScoped {
            val out = alloc<CFTypeRefVar>()
            val status = SecItemCopyMatching(query(account, kSecReturnData to kCFBooleanTrue, kSecMatchLimit to kSecMatchLimitOne), out.ptr)
            val data = owned(out.value)?.reinterpret<__CFData>()
            if (status != errSecSuccess || data == null) null else CFDataGetBytePtr(data)?.readBytes(CFDataGetLength(data).toInt())
        }
    }

    override fun write(fingerprint: String, credential: ByteArray): Boolean {
        val account = pairingName(fingerprint) ?: return false
        if (credential.isEmpty()) return false // pinning needs an element, and no pairing is empty
        var added = add(account, credential)
        if (added == errSecDuplicateItem) {
            memScoped { SecItemDelete(query(account)) }
            added = add(account, credential)
        }
        return added == errSecSuccess
    }

    override fun delete(fingerprint: String): Boolean {
        val account = pairingName(fingerprint) ?: return false
        return gone(memScoped { SecItemDelete(query(account)) })
    }

    override fun deleteAll(): Boolean = gone(memScoped { SecItemDelete(query(null)) })
}

@Composable
internal actual fun rememberPairings(): Pairings? = KeychainPairings

private fun gone(status: Int) = status == errSecSuccess || status == errSecItemNotFound

private fun add(account: String, bytes: ByteArray): Int = bytes.usePinned { pinned ->
    memScoped {
        val data = owned(CFDataCreateWithBytesNoCopy(null, pinned.addressOf(0).reinterpret(), bytes.size.toLong(), kCFAllocatorNull))
        SecItemAdd(query(account, kSecAttrAccessible to kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly, kSecValueData to data), null)
    }
}

/** A query with what every query carries (generic password, the service, never synchronizable), [account] when one is named, and [extra]. */
private fun MemScope.query(account: String?, vararg extra: Pair<CFStringRef?, CFTypeRef?>): CFDictionaryRef? {
    val query = owned(CFDictionaryCreateMutable(null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr))
    CFDictionaryAddValue(query, kSecClass, kSecClassGenericPassword)
    CFDictionaryAddValue(query, kSecAttrService, cfString(SERVICE))
    CFDictionaryAddValue(query, kSecAttrSynchronizable, kCFBooleanFalse)
    if (account != null) CFDictionaryAddValue(query, kSecAttrAccount, cfString(account))
    extra.forEach { (key, value) -> CFDictionaryAddValue(query, key, value) }
    return query
}

private fun MemScope.cfString(text: String): CFStringRef? = owned(CFStringCreateWithCString(null, text, kCFStringEncodingUTF8))

private fun <T : CPointer<*>> MemScope.owned(ref: T?): T? = ref?.also { defer { CFRelease(it) } }
