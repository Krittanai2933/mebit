package com.mebit.app.data

import com.mebit.ffi.signer.HotSendResult
import com.mebit.ffi.signer.PreparedSend
import com.mebit.ffi.signer.VaultPsbt
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Organize section 5, Penpot 5.2. A several-key wallet's send may finish and post a payment only while at least m distinct fingerprints
 * of that wallet have signed it, as Rust read the signatures from the PSBT it returned, and only the payment the last prepare made.
 * Walks every sequence of the calls the screens make on [VaultSend], replayed on a fresh one, with fake Rust answers, two of them lies a
 * bindings bug could tell. The fakes keep the model: which keys truly signed the payment the last prepare made.
 */
class OrganizeVaultSendSpec {
    @Test
    fun broadcastRequiresDistinctVaultFingerprints() = runBlocking {
        val violations = mutableListOf<Violation>()
        var visits = 0
        var expectedVisits = 0
        for ((keys, m) in vaults()) {
            val steps = alphabet(keys.size)
            expectedVisits += sequencesUpTo(steps.size, MAX_SEQUENCE)
            visits += walk(keys, m, steps, ArrayList(), violations)
        }
        assertEquals(expectedVisits, visits, "sequence walk stopped early")
        if (violations.isEmpty()) return@runBlocking
        val ordered = violations.sortedWith(compareBy({ it.steps.size }, { it.line() }))
        ordered.forEach { println(it.line()) }
        fail(
            buildString {
                appendLine("violations=${ordered.size}")
                appendLine("shortest=${ordered.first().line()}")
                appendLine("duplicateFingerprint=${ordered.firstOrNull { it.keys.toSet().size < it.keys.size && !it.lied() }?.line() ?: "absent"}")
                appendLine("lie=${ordered.firstOrNull { it.lied() }?.line() ?: "absent"}")
            },
        )
    }

    /** Every m-of-n over the first n of the three Organize devices, and two keys of one seed, which share a fingerprint, at 2-of-3. */
    private fun vaults(): List<Pair<List<String>, Int>> =
        (1..MAX_VAULT_KEYS).flatMap { n -> (1..n).map { m -> DEVICE_KEYS.take(n) to m } } + (listOf("IPHONE", "IPHONE", "JADE") to 2)

    private fun alphabet(n: Int): List<Step> =
        listOf(Step.Prepare) + (0 until n).map { Step.Sign(it) } + listOf(Step.SignRefused, Step.LieOtherTxid, Step.LieOutOfRange, Step.Broadcast)

    /** Visits every sequence over [steps] up to [MAX_SEQUENCE] from [prefix], replaying each on a fresh [World], and counts them. */
    private suspend fun walk(keys: List<String>, m: Int, steps: List<Step>, prefix: MutableList<Step>, violations: MutableList<Violation>): Int {
        val world = World(keys, m)
        prefix.forEachIndexed { i, step -> world.run(step, last = i == prefix.lastIndex) }
        world.observe()
        world.found.forEach { violations += Violation(keys, m, prefix.map { it.label }, it) }
        if (prefix.size == MAX_SEQUENCE) return 1
        var visits = 1
        for (step in steps) {
            prefix.add(step)
            visits += walk(keys, m, steps, prefix, violations)
            prefix.removeAt(prefix.lastIndex)
        }
        return visits
    }

    private fun sequencesUpTo(actions: Int, maxLen: Int): Int {
        var total = 1
        var pow = 1
        repeat(maxLen) {
            pow *= actions
            total += pow
        }
        return total
    }

    /** One replay: a [VaultSend] over fakes of the Rust calls, the model they keep, and what broke the property on the last step. */
    private class World(val keys: List<String>, val m: Int) {
        private var prepared = 0
        private var modelTxid: String? = null
        private val modelSigned = mutableSetOf<Int>()
        private var answer: (VaultPsbt, Int) -> VaultPsbt = { held, _ -> held }
        private var signCalls = 0
        private var last = false
        val found = mutableListOf<String>()

        private fun distinct(): Int = modelSigned.map { keys[it] }.toSet().size

        private fun signedBy(held: VaultPsbt, key: Int) {
            if (held.txid == modelTxid) modelSigned += key
        }

        private fun note(what: String) {
            if (last) found += what
        }

        val send = VaultSend(
            vault = CreatedVault(
                id = "vault",
                name = "Vault",
                m = m,
                n = keys.size,
                policy = CustodyPolicy.PersonalCustody,
                address = "tb1qvault${keys.size}of$m",
                bsms = "",
                keyFingerprints = keys,
            ),
            guards = PendingPayments(FakePlainFile()),
            nowSeconds = { 0L },
            worker = EmptyCoroutineContext,
            typicalVsize = { _, _ -> 201uL },
            prepareSend = { _, _, _, _, _ ->
                prepared++
                modelTxid = prepared.toString(16).padStart(64, '0')
                modelSigned.clear()
                record(modelTxid!!, emptyList())
            },
            signPsbt = { _, _, held, keyIndex, _, _ ->
                signCalls++
                answer(held, keyIndex.toInt())
            },
            finalizePsbt = { _, _, held ->
                if (held.txid != modelTxid) note("finalize of ${held.txid.take(8)}, not the payment prepared last")
                if (distinct() < m) note("finalize at ${distinct()} distinct fingerprints of $m")
                PreparedSend(held.txid, "", 0uL, 0uL)
            },
            postHex = { _, _, txid ->
                if (distinct() < m) note("post at ${distinct()} distinct fingerprints of $m")
                HotSendResult(txid)
            },
        )

        suspend fun run(step: Step, last: Boolean) {
            this.last = last
            when (step) {
                Step.Prepare -> send.prepare()
                is Step.Sign -> {
                    answer = { held, key -> signedBy(held, key); held.copy(signedKeys = (held.signedKeys + key.toUInt()).distinct().sorted()) }
                    send.sign(phone(step.key)) { true }
                }
                Step.SignRefused -> {
                    val before = signCalls
                    send.sign(phone(0)) { false }
                    if (signCalls != before) note("a refused owner check reached Rust")
                }
                Step.LieOtherTxid -> {
                    answer = { held, key -> held.copy(txid = LIE_TXID, signedKeys = (held.signedKeys + key.toUInt()).distinct().sorted()) }
                    send.sign(phone(0)) { true }
                }
                Step.LieOutOfRange -> {
                    answer = { held, key ->
                        signedBy(held, key)
                        held.copy(signedKeys = (held.signedKeys + key.toUInt() + keys.size.toUInt()).distinct().sorted())
                    }
                    send.sign(phone(0)) { true }
                }
                Step.Broadcast -> send.broadcast()
            }
        }

        fun observe() {
            last = true
            if (send.readyToBroadcast() && distinct() < m) note("readyToBroadcast() at ${distinct()} distinct fingerprints of $m")
        }

        private fun phone(key: Int) = KeySigner.Phone(key, emptyList(), "")

        private fun record(txid: String, signed: List<UInt>) =
            VaultPsbt("", txid, "", 0uL, 0uL, 0uL, 0u, 0uL, signed)
    }

    private class Violation(val keys: List<String>, val m: Int, val steps: List<String>, val what: String) {
        fun lied(): Boolean = steps.any { it.startsWith("Lie") }

        fun line(): String {
            val calls = if (steps.isEmpty()) "(no calls)" else steps.joinToString("; ")
            return "m=$m vault=$keys steps=$calls: $what"
        }
    }

    private sealed class Step(val label: String) {
        /** 5.1's ตรวจสอบ: [VaultSend.prepare]. Rust answers a new payment that no key signed. */
        data object Prepare : Step("Prepare")

        /** 5.2's เซ็น on key [key]'s row, the owner check passing. Rust answers the held payment with that key's signature added. */
        data class Sign(val key: Int) : Step("Sign($key)")

        /** 5.2's เซ็น on key 0's row with the owner check failing. Rust must not hear of it. */
        data object SignRefused : Step("SignRefused")

        /** 5.2's เซ็น on key 0's row, Rust answering key 0 on a payment of another txid. */
        data object LieOtherTxid : Step("LieOtherTxid")

        /** 5.2's เซ็น on key 0's row, Rust answering key 0 and a position past the wallet's keys. */
        data object LieOutOfRange : Step("LieOutOfRange")

        /** 5.2's ส่งธุรกรรม: [VaultSend.broadcast]. */
        data object Broadcast : Step("Broadcast")
    }

    private companion object {
        const val MAX_VAULT_KEYS = 3
        const val MAX_SEQUENCE = 5
        val DEVICE_KEYS = listOf("IPHONE", "JADE", "TREZOR")
        val LIE_TXID = "f".repeat(64)
    }
}
