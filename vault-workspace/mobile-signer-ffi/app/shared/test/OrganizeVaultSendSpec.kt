package com.mebit.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Organize section 5. A vault send may broadcast only after at least m distinct
 * fingerprints from that vault have signed. Same fingerprint twice counts once.
 */
class OrganizeVaultSendSpec {
    @Test
    fun broadcastRequiresDistinctVaultFingerprints() {
        val violations = mutableListOf<Violation>()
        var visits = 0
        var expectedVisits = 0
        for (n in 1..MAX_VAULT_KEYS) {
            val keys = DEVICE_KEYS.take(n)
            val steps = alphabet(keys)
            val perLength = sequencesUpTo(steps.size, MAX_SEQUENCE)
            for (m in 1..n) {
                expectedVisits += perLength
                val vault = CreatedVault(
                    id = "vault_${n}_$m",
                    name = "Vault $m of $n",
                    m = m,
                    n = n,
                    policy = CustodyPolicy.PersonalCustody,
                    address = "tb1qvault",
                    bsms = "",
                    keyFingerprints = keys,
                )
                visits += walk(vault, steps, ArrayList(), violations)
            }
        }
        assertEquals(expectedVisits, visits, "sequence walk stopped early")
        if (violations.isEmpty()) return
        val ordered = violations.sortedWith(compareBy({ it.steps.size }, { it.line() }))
        ordered.forEach { println(it.line()) }
        val shortest = ordered.first()
        val demoPair = ordered.firstOrNull { it.isDemoPair() }
        val duplicateVault = ordered.firstOrNull { it.isRepeatedVaultKey() }
        fail(buildString {
            appendLine("violations=${ordered.size}")
            appendLine("shortest=${shortest.line()}")
            appendLine("demoPair=${demoPair?.line() ?: "absent"}")
            appendLine("duplicateVault=${duplicateVault?.line() ?: "absent"}")
        })
    }

    private fun alphabet(vault: List<String>): List<SignStep> = buildList {
        add(SignStep.Reset)
        vault.forEach { add(SignStep.Mark(it)) }
        FOREIGN_KEYS.forEach { add(SignStep.Mark(it)) }
    }

    /** Visits every sequence over [steps] up to [MAX_SEQUENCE], replaying each prefix on a fresh draft so only the public API is used. */
    private fun walk(
        vault: CreatedVault,
        steps: List<SignStep>,
        prefix: MutableList<SignStep>,
        violations: MutableList<Violation>,
    ): Int {
        var visits = 1
        val draft = MultisigSendDraft()
        prefix.forEach { it.apply(draft, vault) }
        observe(draft, vault, prefix, violations)
        if (prefix.size == MAX_SEQUENCE) return visits
        for (step in steps) {
            prefix.add(step)
            visits += walk(vault, steps, prefix, violations)
            prefix.removeAt(prefix.lastIndex)
        }
        return visits
    }

    private fun observe(
        draft: MultisigSendDraft,
        vault: CreatedVault,
        prefix: List<SignStep>,
        violations: MutableList<Violation>,
    ) {
        val signed = draft.signedFingerprints.map { it.uppercase() }
        val vaultSigned = signed.toSet().count { it in vault.keyFingerprints }
        val ready = draft.readyToBroadcast()
        val broadcast = draft.broadcastMock()
        if (vaultSigned >= vault.m) return
        if (!ready && !broadcast) return
        violations.add(
            Violation(
                m = vault.m,
                vault = vault.keyFingerprints,
                steps = prefix.map { it.label },
                ready = ready,
                broadcast = broadcast,
                signedCount = signed.size,
                vaultSigned = vaultSigned,
            ),
        )
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

    private class Violation(
        val m: Int,
        val vault: List<String>,
        val steps: List<String>,
        val ready: Boolean,
        val broadcast: Boolean,
        val signedCount: Int,
        val vaultSigned: Int,
    ) {
        fun line(): String {
            val call = if (steps.isEmpty()) "(no calls)" else steps.joinToString("; ")
            return "m=$m vault=$vault steps=$call readyToBroadcast()=$ready broadcastMock()=$broadcast signedCount=$signedCount vaultSigned=$vaultSigned"
        }

        /** The mark steps after the last reset, which are the only calls that can still be counted. */
        private fun marksSinceReset(): List<String> =
            steps.takeLastWhile { it != RESET_LABEL }.mapNotNull { MARK.matchEntire(it)?.groupValues?.get(1) }

        fun isDemoPair(): Boolean = m == 2 && marksSinceReset() == listOf("DEMO0001", "DEMO0002")

        fun isRepeatedVaultKey(): Boolean {
            val marks = marksSinceReset()
            if (marks.size < 2) return false
            return marks.distinct().size == 1 && marks.first() in vault && marks.size > vaultSigned
        }
    }

    private sealed class SignStep {
        abstract fun apply(draft: MultisigSendDraft, vault: CreatedVault)
        abstract val label: String

        data object Reset : SignStep() {
            override fun apply(draft: MultisigSendDraft, vault: CreatedVault) = draft.resetSignatures(vault)
            override val label: String = RESET_LABEL
        }

        data class Mark(val fingerprint: String) : SignStep() {
            override fun apply(draft: MultisigSendDraft, vault: CreatedVault) {
                draft.markSigned(fingerprint)
            }
            override val label: String = "markSigned(\"$fingerprint\")"
        }
    }

    private companion object {
        const val MAX_VAULT_KEYS = 3
        const val MAX_SEQUENCE = 5
        val DEVICE_KEYS = listOf("IPHONE", "JADE", "TREZOR")
        val FOREIGN_KEYS = listOf("DEMO0001", "DEMO0002", "JADEDEMO")
        const val RESET_LABEL = "resetSignatures(vault)"
        val MARK = Regex("""markSigned\("([^"]+)"\)""")
    }
}
