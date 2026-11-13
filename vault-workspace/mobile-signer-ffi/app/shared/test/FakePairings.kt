package com.mebit.app.data

import com.mebit.app.Pairings
import com.mebit.app.pairingName

/**
 * A [Pairings] in memory, keyed as the platform stores key a credential ([pairingName]). It keeps copies, hands out copies and keeps every
 * array it handed out or was given, so a test can check that the caller zeroed them. [failWrite] makes every write fail.
 */
internal class FakePairings : Pairings {
    val kept = mutableMapOf<String, ByteArray>()
    val served = mutableListOf<ByteArray>()
    val given = mutableListOf<ByteArray>()
    var failWrite = false
    var deletesAll = 0
        private set

    override fun read(fingerprint: String): ByteArray? = pairingName(fingerprint)?.let { kept[it] }?.copyOf()?.also { served += it }

    override fun write(fingerprint: String, credential: ByteArray): Boolean {
        given += credential
        val name = pairingName(fingerprint) ?: return false
        if (failWrite) return false
        kept[name] = credential.copyOf()
        return true
    }

    override fun delete(fingerprint: String): Boolean {
        val name = pairingName(fingerprint) ?: return false
        kept.remove(name)
        return true
    }

    override fun deleteAll(): Boolean {
        deletesAll++
        kept.clear()
        return true
    }

    /** The credential kept for [fingerprint], a copy that is not counted as served. */
    fun of(fingerprint: String): ByteArray? = pairingName(fingerprint)?.let { kept[it] }?.copyOf()
}
