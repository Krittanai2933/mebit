package com.mebit.ffi.signer

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

// Texts that come from a dependency (miniscript, bitcoin, serde_json) are asserted
// by prefix or fragment, because Cargo.lock is git-ignored and a fresh resolve can reword them.

private val TPUB = listOf(
    "tpubDFkN51vYF36W4Yfn3wGv5fpmRo3ok7vZZjc1gmRJjumq33L776e6GkP4HGdCVjDqYiBahXCrXQKja8aUZ2xovQNS8WkF46MdY7TLHJLYD7H",
    "tpubDF41CiHSj6Y4iSH7syioTxCr9cvMXLXvz4sGzvg4MSYfnid68mGMStcADFnKGnYmwL5GJ6ZzqvypiZaHs3AefpGuDrzMa56PYHVELc4crdQ",
    "tpubDEFfK5S7ZsjvCNXwUWVu1Lxcdpn55Fy8nLecgU1fLP4owzS9RKQVVx9YsxyfgDps6EiLBMkeNdQ1oLzWeDcj2tLbykFmNgchhT9FcSGFAv8",
)
private val XPUB = listOf(
    "xpub6E79FaRWLSJCAgA2jDHRvyrWKwT6aSmR685zptzyYPvmUd44omcxZ1NAzDtbdFBvEADjcVbV4NzTDwQeU6oiSV9KGiMSWhjANZjbfUHkm3Y",
    "xpub6EikvjdbqbuVcGmLVQj4mrwuUX4xWSEEz8iG9C4SE7F5catvdwNZp4rmPxBdgbXDLgTcY3zKEc7dp9LQcDwKT8XYSmcJjyoob7DntQSJkYz",
    "xpub6EprBeE81P64tV7rRuMvFw23zJX1smjQdEg4jEVJ9vUv4SXrVwkdQPfW5uVhTBdnzetRthnHqwUpAbU3Z9y5yzAEzAaUG1iifJZnTHBEfRU",
)

private const val THRESHOLD_PREFIX = "invalid multisig threshold: "

// TPUB[0] re-encoded with depth byte 254: valid base58check, too deep to derive addresses from.
private const val TPUB_DEPTH_254 =
    "tpubMNAEfDKmHDBtaurJdcdvF8x6fTFbHdivNRWmujozaDspRFarxF7PBMCr6guHaEMyJzapUbLhLvKbicbVcXtLeHwYBZKguGWbGbHdHY2CsnU"

private inline fun <reified T : MobileSignerException> failureOf(call: () -> Unit): String =
    assertFailsWith<T> { call() }.message.orEmpty()

class MobileSignerFfiTest {
    @Test
    fun multisigAddressesMatchRustGoldens() {
        val testnet = "tb1q693w8s33wzc0pmrpu9yda3ngm2eu9hkzvkwagw4q69643my3xt0q9lcl0f"
        assertEquals(testnet, computeVaultAddressMultisig(TPUB, 2u))
        assertEquals(testnet, computeVaultAddressMultisig(listOf(TPUB[2], TPUB[0], TPUB[1]), 2u))
        assertEquals(
            "bc1qvmhkp7q475h5klcqm5karudgu8nhjxkqh498nghpds754vc6uy0sa7mrh9",
            computeVaultAddressMultisig(XPUB, 2u),
        )
    }

    @Test
    fun errorsKeepKindAndRustMessage() {
        val zeroOfThree = failureOf<MobileSignerException.InvalidThreshold> { computeVaultAddressMultisig(TPUB, 0u) }
        assertTrue(zeroOfThree.startsWith(THRESHOLD_PREFIX), zeroOfThree)
        assertContains(zeroOfThree, "k > 0")

        val fourOfThree = failureOf<MobileSignerException.InvalidThreshold> { computeVaultAddressMultisig(TPUB, 4u) }
        assertTrue(fourOfThree.startsWith(THRESHOLD_PREFIX), fourOfThree)
        assertContains(fourOfThree, "k > n")

        val empty = failureOf<MobileSignerException.InvalidThreshold> { computeVaultAddressMultisig(emptyList(), 1u) }
        assertTrue(empty.startsWith(THRESHOLD_PREFIX), empty)
        assertContains(empty, "nonempty")

        val notBase58 = failureOf<MobileSignerException.InvalidXpub> { computeVaultAddressMultisig(listOf(TPUB[0], "nope"), 1u) }
        assertTrue(notBase58.startsWith("key at index 1 is not a valid xpub or tpub: "), notBase58)

        assertEquals(
            "key at index 0 is too deep to derive addresses from: depth 254, the maximum is 253",
            failureOf<MobileSignerException.InvalidXpub> { computeVaultAddressMultisig(listOf(TPUB_DEPTH_254, TPUB[1]), 1u) },
        )
        assertEquals(
            "key at index 1 is not a valid xpub or tpub: 1000 characters, longer than any extended key",
            failureOf<MobileSignerException.InvalidXpub> { computeVaultAddressMultisig(listOf(TPUB[0], "z".repeat(1000)), 1u) },
        )

        assertEquals(
            "key at index 2 is for a different network than key at index 0 (xpub is mainnet, tpub is testnet)",
            failureOf<MobileSignerException.NetworkMismatch> { computeVaultAddressMultisig(listOf(TPUB[0], TPUB[1], XPUB[2]), 2u) },
        )
        assertEquals(
            "key at index 2 is the same as key at index 0",
            failureOf<MobileSignerException.DuplicateXpub> { computeVaultAddressMultisig(listOf(TPUB[0], TPUB[1], TPUB[0]), 2u) },
        )

        val notJson = failureOf<MobileSignerException.InvalidPsbtJson> { signPsbt("{", "pk") }
        assertTrue(notJson.startsWith("invalid PSBT json: "), notJson)
    }

    @Test
    fun legacyMocksUnchanged() {
        assertEquals("xpub_borrower/m/48'/0'/0'/2'/7", deriveBorrowerPubkey("xpub_borrower", 7u))
        val json = """{"loan_id":"loan-1","inputs":["utxo:0"],"outputs":[{"address":"tb1qborrower","amount_sats":100000}]}"""
        assertEquals("MOCK_SIG[loan-1:1:borrower_pk]", signPsbt(json, "borrower_pk"))
        // DefaultHasher output is not stable across Rust releases: properties only, as in the Rust tests.
        val a = computeVaultAddress("b_pk", "p_pk", "l_pk")
        assertEquals(a, computeVaultAddress("b_pk", "p_pk", "l_pk"))
        assertTrue(a.startsWith("tb1q"))
        assertNotEquals(a, computeVaultAddress("b_pk", "p_pk", "different_lender_pk"))
    }
}
