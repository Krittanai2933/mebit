package com.mebit.app.data

import com.mebit.ffi.signer.FoundWallet
import com.mebit.ffi.signer.HotSendResult
import com.mebit.ffi.signer.PreparedSend
import com.mebit.ffi.signer.SpendOutput
import com.mebit.ffi.signer.SpendReview
import com.mebit.ffi.signer.WalletKey
import com.mebit.ffi.signer.WalletKind
import com.mebit.ffi.signer.WalletShape
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Organize section 5, Penpot 5.2 and the review after it. A several-key wallet's send may finish and post a payment only while at least
 * m distinct fingerprints of that wallet have signed it, as Rust read the signatures from the PSBT it returned, and only the payment the
 * last prepare made. No sign reaches Rust, and no device is reached, once that payment has m, or for a key whose fingerprint already
 * signed it, and nothing reaches Rust while a device's signature holds the send. Walks every sequence of the calls the screens make on
 * [VaultSend], replayed on a fresh one, with fake Rust answers and fake devices, two of the answers lies a bindings bug could tell. The
 * fakes keep the model: which keys truly signed the payment the last prepare made.
 */
class OrganizeVaultSendSpec {
    @Test
    fun broadcastRequiresDistinctVaultFingerprints() = runBlocking {
        val violations = mutableListOf<Violation>()
        var visits = 0
        var expectedVisits = 0
        for ((keys, m) in vaults()) {
            val steps = alphabet(keys)
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

    /** The two letters that act while a device waits need a device key, which is the last key of every wallet that has one. */
    private fun alphabet(keys: List<String>): List<Step> =
        listOf(Step.Prepare) + keys.indices.map { Step.Sign(it) } + listOf(Step.SignRefused, Step.LieOtherTxid, Step.LieForeignFingerprint, Step.Broadcast) +
            if (keys.last() == "IPHONE") emptyList() else listOf(Step.PrepareWhileDeviceSigns, Step.SignWhileDeviceSigns)

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

    /** One replay: a [VaultSend] over fakes of the Rust calls and of the devices, the model they keep, and what broke the property on the last step. */
    private class World(val keys: List<String>, val m: Int) {
        private var prepared = 0
        private var modelTxid: String? = null
        private val modelSigned = mutableSetOf<Int>()
        private var answer: (SpendReview, Int) -> SpendReview = { held, _ -> held }
        private var signCalls = 0
        private var last = false

        /** What the user does while the next device reached waits for its confirm, then nothing. */
        private var meanwhile: suspend () -> Unit = {}

        /** True while a device waits for its confirm, so its signature holds the send. */
        private var claimed = false
        val found = mutableListOf<String>()

        private fun distinct(): Int = modelSigned.map { keys[it] }.toSet().size

        private fun signedBy(held: SpendReview, key: Int) {
            if (held.unsignedTxid == modelTxid) modelSigned += key
        }

        private fun note(what: String) {
            if (last) found += what
        }

        private fun whileClaimed(what: String) {
            if (claimed) note("$what while a device signature held the send")
        }

        private val vault = CreatedVault(
            id = "vault",
            name = "Vault",
            m = m,
            n = keys.size,
            policy = CustodyPolicy.PersonalCustody,
            address = "tb1qvault${keys.size}of$m",
            bsms = "",
            keyFingerprints = keys,
        )

        val send = VaultSend(
            vault = vault,
            guards = PendingPayments(FakePlainFile()),
            nowSeconds = { 0L },
            worker = EmptyCoroutineContext,
            core = DescribedCore(
                describe = { _, _ -> WalletShape(m.toUByte(), keys.map { WalletKey(it.lowercase(), "m/48'/1'/0'/2'", "") }, vault.address, 201uL) },
                draft = { _, _, to, _, _ ->
                    whileClaimed("prepare reached Rust")
                    prepared++
                    modelTxid = prepared.toString(16).padStart(64, '0')
                    modelSigned.clear()
                    record(modelTxid!!, emptyList(), to)
                },
                signWithWords = { _, _, psbt, _, words, _ ->
                    whileClaimed("sign reached Rust")
                    signCalls++
                    val key = words.single().removePrefix("key").toInt()
                    if (distinct() >= m) note("sign at ${distinct()} distinct fingerprints of $m reached Rust")
                    if (keys[key] in modelSigned.map { keys[it] }) note("sign of key $key, whose fingerprint already counts, reached Rust")
                    answer(held(psbt), key)
                },
                finish = { _, _, psbt, _ ->
                    whileClaimed("finish reached Rust")
                    val held = held(psbt)
                    if (held.unsignedTxid != modelTxid) note("finish of ${held.unsignedTxid.take(8)}, not the payment prepared last")
                    if (distinct() < m) note("finish at ${distinct()} distinct fingerprints of $m")
                    PreparedSend(held.unsignedTxid, "", 0uL, 0uL)
                },
                postHex = { _, _, txid ->
                    whileClaimed("post reached Rust")
                    if (distinct() < m) note("post at ${distinct()} distinct fingerprints of $m")
                    HotSendResult(txid)
                },
            ),
            link = HardwareLink(pairings = null) { device(it.id.removePrefix("key").toInt()) },
        )

        /** Key [key]'s device, reached: it holds that key, and while it waits for its confirm the user does [meanwhile]. Its answer is the model's. */
        private fun device(key: Int): DeviceLink {
            whileClaimed("a device was reached")
            if (distinct() >= m) note("a device reached at ${distinct()} distinct fingerprints of $m")
            if (keys[key] in modelSigned.map { keys[it] }) note("a device reached for key $key, whose fingerprint already counts")
            return FakeDeviceLink(fingerprint = keys[key].lowercase()) { _, psbt, _ ->
                val now = meanwhile
                meanwhile = {}
                claimed = true
                try {
                    now()
                } finally {
                    claimed = false
                }
                answer(held(psbt), key)
            }
        }

        suspend fun run(step: Step, last: Boolean) {
            this.last = last
            val signs: (SpendReview, Int) -> SpendReview = { held, key ->
                signedBy(held, key)
                held.with(listOf(keys[key]))
            }
            when (step) {
                Step.Prepare -> send.prepare()
                is Step.Sign -> {
                    answer = signs
                    tap(step.key)
                }
                Step.SignRefused -> {
                    val before = signCalls
                    send.sign(phone(0)) { false }
                    if (signCalls != before) note("a refused owner check reached Rust")
                }
                Step.LieOtherTxid -> {
                    answer = { held, key -> held.with(listOf(keys[key]), txid = LIE_TXID) }
                    tap(keys.lastIndex)
                }
                Step.LieForeignFingerprint -> {
                    answer = { held, key -> held.with(listOf(keys[key], FOREIGN_FINGERPRINT)) }
                    tap(keys.lastIndex)
                }
                Step.PrepareWhileDeviceSigns -> {
                    answer = signs
                    meanwhile = { send.prepare() }
                    tap(keys.lastIndex)
                }
                Step.SignWhileDeviceSigns -> {
                    answer = signs
                    meanwhile = { tap(0) }
                    tap(keys.lastIndex)
                }
                Step.Broadcast -> send.broadcast()
            }
            meanwhile = {}
        }

        fun observe() {
            last = true
            if (send.readyToBroadcast() && distinct() < m) note("readyToBroadcast() at ${distinct()} distinct fingerprints of $m")
        }

        /** 5.2's เซ็น on key [key]'s row, by its kind: this phone's with the owner check passing, or the device of that key, alone in range. A throw is a refusal. */
        private suspend fun tap(key: Int) {
            if (keys[key] == "IPHONE") {
                send.sign(phone(key)) { true }
            } else {
                val kind = if (keys[key] == "JADE") WalletKind.JADE else WalletKind.TREZOR_SAFE7
                runCatching { send.signOnDevice(KeySigner.Device(key, kind), FoundWallet(kind, "key$key", keys[key]), NO_CODES, NO_STEPS) }
            }
        }

        /** Key [key]'s row. Its words name the key, so the fake knows which key signs, as Rust knows the key the words make. */
        private fun phone(key: Int) = KeySigner.Phone(key, listOf("key$key"), "")

        /** Rust's review of a payment to [to] that [signed] signed, each fingerprint lowercase as Rust names it. Its PSBT text carries all of it. */
        private fun record(txid: String, signed: List<String>, to: String) =
            SpendReview("$txid;$to;${signed.joinToString(",")}", txid, listOf(SpendOutput(to, 1_000uL)), 0uL, 0uL, signed, m.toUByte())

        /** The review of the PSBT text [VaultSend] passed, so every answer builds on the payment it holds, as wallet_sign_with_words does. */
        private fun held(psbt: String): SpendReview {
            val (txid, to, signed) = psbt.split(";")
            return record(txid, signed.split(",").filter { it.isNotEmpty() }, to)
        }

        private fun SpendReview.with(fingerprints: List<String>, txid: String = unsignedTxid) =
            record(txid, (signed_ + fingerprints.map { it.lowercase() }).distinct(), payments.single().address)
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

        /**
         * 5.2's เซ็น on key [key]'s row, by its kind: [VaultSend.sign] with the owner check passing, or [VaultSend.signOnDevice] on that
         * key's device. Rust answers the held payment with that key's signature added.
         */
        data class Sign(val key: Int) : Step("Sign($key)")

        /** 5.2's เซ็น on key 0's row, this phone's, with the owner check failing. Rust must not hear of it. */
        data object SignRefused : Step("SignRefused")

        /** 5.2's เซ็น on the last key's row, a device's when the wallet has one, Rust answering that key on a payment of another txid. */
        data object LieOtherTxid : Step("LieOtherTxid")

        /**
         * 5.2's เซ็น on the last key's row, a device's when the wallet has one, Rust answering that key and a fingerprint that is no key of
         * the wallet. An answer that names more than the one key it was asked for is a lie as a whole, so nothing of it truly signed.
         */
        data object LieForeignFingerprint : Step("LieForeignFingerprint")

        /**
         * 5.2's เซ็น on the last key's row, a device's, and while the device waits for its confirm, 5.1's ตรวจสอบ ([VaultSend.prepare]).
         * The device then answers the payment it was given, signed.
         */
        data object PrepareWhileDeviceSigns : Step("PrepareWhileDeviceSigns")

        /**
         * 5.2's เซ็น on the last key's row, a device's, and while the device waits for its confirm, เซ็น on key 0's row, this phone's.
         * The device then answers the payment it was given, signed.
         */
        data object SignWhileDeviceSigns : Step("SignWhileDeviceSigns")

        /** The review's ส่งธุรกรรม: [VaultSend.broadcast]. */
        data object Broadcast : Step("Broadcast")
    }

    private companion object {
        const val MAX_VAULT_KEYS = 3
        const val MAX_SEQUENCE = 5
        val DEVICE_KEYS = listOf("IPHONE", "JADE", "TREZOR")
        val LIE_TXID = "f".repeat(64)
        const val FOREIGN_FINGERPRINT = "5eed0000"
    }
}
