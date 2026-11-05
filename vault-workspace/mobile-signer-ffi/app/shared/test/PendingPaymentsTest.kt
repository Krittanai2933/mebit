package com.mebit.app.data

import com.mebit.app.data.SingleSigWallet.DamagedGuard
import com.mebit.app.data.SingleSigWallet.UnconfirmedSend
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The guard file when the phone holds a damaged one: a file that is there but cannot be read, or holds text no build writes. It
 * cannot say which wallet's payment may be out, so it stops every wallet's payments until the user clears it, and only that clear
 * deletes it.
 */
class PendingPaymentsTest {
    private val send = UnconfirmedSend(TEST_TXID, SENT_AT)

    /** Each kind of damage, on its own file: one that cannot be read, and texts the encoder never writes. */
    private fun damagedFiles(): List<Pair<String, FakePlainFile>> = listOf(
        "unreadable" to FakePlainFile().apply {
            text = "$TEST_CHECK $TEST_TXID $SENT_AT\n"
            unreadable = true
        },
        "garbage" to FakePlainFile().apply { text = "garbage\n" },
        "a short txid" to FakePlainFile().apply { text = "$TEST_CHECK abc $SENT_AT\n" },
        "no final newline" to FakePlainFile().apply { text = "$TEST_CHECK $TEST_TXID $SENT_AT" },
        // C2. Bytes beside a guard that are not the line's own transaction could be another payment.
        "another transaction's bytes" to FakePlainFile().apply { text = "$TEST_CHECK $SIGNED_TXID $SENT_AT $OTHER_SIGNED_HEX\n" },
        "an output changed" to FakePlainFile().apply { text = "$TEST_CHECK $SIGNED_TXID $SENT_AT ${SIGNED_HEX.replace("899f0500", "899f0600")}\n" },
        "bytes cut short" to FakePlainFile().apply { text = "$TEST_CHECK $SIGNED_TXID $SENT_AT ${SIGNED_HEX.dropLast(2)}\n" },
        "a byte after the transaction" to FakePlainFile().apply { text = "$TEST_CHECK $SIGNED_TXID $SENT_AT ${SIGNED_HEX}00\n" },
        "uppercase bytes" to FakePlainFile().apply { text = "$TEST_CHECK $SIGNED_TXID $SENT_AT ${SIGNED_HEX.uppercase()}\n" },
        "an empty fourth field" to FakePlainFile().apply { text = "$TEST_CHECK $SIGNED_TXID $SENT_AT \n" },
        "a fifth field" to FakePlainFile().apply { text = "$TEST_CHECK $SIGNED_TXID $SENT_AT $SIGNED_HEX $SENT_AT\n" },
        "bytes under no txid" to FakePlainFile().apply { text = "$TEST_CHECK  $SENT_AT $SIGNED_HEX\n" },
    )

    private val signed = UnconfirmedSend(SIGNED_TXID, SENT_AT, SIGNED_HEX)

    // C2. A guard written from now on keeps its signed transaction as a fourth field. A line of three, as an earlier build and the move
    // from an older record write it, still reads, with no bytes.
    @Test
    fun aGuardKeepsItsSignedTransactionAsAFourthFieldAndALineOfThreeStillReads() {
        val file = FakePlainFile()
        val payments = PendingPayments(file)
        assertEquals(Claim.Written, payments.claim(TEST_CHECK, signed))
        assertEquals("$TEST_CHECK $SIGNED_TXID $SENT_AT $SIGNED_HEX\n", file.text)
        assertEquals(signed, payments.of(TEST_CHECK))
        assertEquals(Claim.Written, payments.claim(OTHER_CHECK, send))
        assertEquals("$TEST_CHECK $SIGNED_TXID $SENT_AT $SIGNED_HEX\n$OTHER_CHECK $TEST_TXID $SENT_AT\n", file.text)
        assertEquals(send, payments.of(OTHER_CHECK), "a line of three keeps no bytes")
        assertEquals(signed, PendingPayments(file).of(TEST_CHECK), "the line with bytes, read by a new instance")
    }

    @Test
    fun aClaimNeverWritesBytesThatAreNotItsTransaction() {
        val file = FakePlainFile()
        for (hex in listOf(OTHER_SIGNED_HEX, SIGNED_HEX.dropLast(2), SIGNED_HEX.uppercase(), "zz", "")) {
            assertEquals(Claim.NotSaved, PendingPayments(file).claim(TEST_CHECK, UnconfirmedSend(SIGNED_TXID, SENT_AT, hex)), hex.take(16))
        }
        assertEquals(0, file.writes)
    }

    // C2. The bytes leave with their line, so nothing on the phone can post a payment whose guard settled or that the user gave up on.
    @Test
    fun aSettleTakesTheBytesWithTheLineAndLeavesAnotherWalletsLineWhole() {
        val file = FakePlainFile()
        val payments = PendingPayments(file)
        val other = UnconfirmedSend(OTHER_SIGNED_TXID, SENT_AT + 60, OTHER_SIGNED_HEX)
        assertEquals(Claim.Written, payments.claim(TEST_CHECK, signed))
        assertEquals(Claim.Written, payments.claim(OTHER_CHECK, other))
        assertTrue(payments.settle(TEST_CHECK, signed))
        assertEquals("$OTHER_CHECK $OTHER_SIGNED_TXID ${SENT_AT + 60} $OTHER_SIGNED_HEX\n", file.text, "only the other wallet's line")
        assertTrue(payments.settle(OTHER_CHECK, other))
        assertNull(file.text, "the file once no line is left")
    }

    // R8 S2, the review of 2026-11-06. A settle whose write failed said true, so the screen cleared while the phone still held the
    // guard, and the payment was refused only at the claim, after the owner check and the signing.
    @Test
    fun aSettleWhoseWriteFailsSaysSoAndKeepsTheGuard() {
        val file = FakePlainFile()
        val p = PendingPayments(file)
        val a = UnconfirmedSend(TEST_TXID, SENT_AT)
        assertEquals(Claim.Written, p.claim(TEST_CHECK, a))
        assertEquals(Claim.Written, p.claim(OTHER_CHECK, UnconfirmedSend(TEST_TXID, SENT_AT)))
        file.failWrite = true
        val ok = p.settle(TEST_CHECK, a)
        assertEquals(a, PendingPayments(file).of(TEST_CHECK), "guard still on the file")
        assertFalse(ok, "settle said true although the guard is still on the phone")
    }

    @Test
    fun aSettleWhoseDeleteFailsSaysSoAndKeepsTheGuard() {
        val file = FakePlainFile()
        val payments = PendingPayments(file)
        assertEquals(Claim.Written, payments.claim(TEST_CHECK, send))
        file.failDelete = true
        assertFalse(payments.settle(TEST_CHECK, send), "the settle of the last line, whose delete failed")
        assertEquals(send, payments.of(TEST_CHECK), "the guard after the failed delete")
        for ((case, damaged) in damagedFiles()) {
            damaged.failDelete = true
            assertFalse(PendingPayments(damaged).settle(TEST_CHECK, DamagedGuard), "the clear of a damaged file whose delete failed, $case")
            assertEquals(DamagedGuard, PendingPayments(damaged).of(TEST_CHECK), case)
        }
        file.failDelete = false
        assertTrue(payments.settle(TEST_CHECK, send), "the settle once the delete works")
        assertNull(file.text, "the file after the settle")
    }

    @Test
    fun theTxidOfATransactionIsTheOneTheChainGivesIt() {
        assertEquals(SIGNED_TXID, txidOf(SIGNED_HEX), "a P2WPKH payment")
        assertEquals(OTHER_SIGNED_TXID, txidOf(OTHER_SIGNED_HEX), "a taproot spend with a long witness")
        assertEquals(COINBASE_TXID, txidOf(COINBASE_HEX), "a segwit coinbase")
        assertEquals(SIGNED_TXID, txidOf(SIGNED_WITHOUT_WITNESS), "the same payment written without its witness")
        val notOne = listOf(
            "empty" to "",
            "an odd length" to SIGNED_HEX.dropLast(1),
            "uppercase" to SIGNED_HEX.uppercase(),
            "not hex" to "g" + SIGNED_HEX.drop(1),
            "cut short" to SIGNED_HEX.dropLast(2),
            "a byte after it" to SIGNED_HEX + "00",
            "only a version" to "02000000",
            "a count past the end" to "02000000fdffff",
            "a huge count" to "02000000ffffffffffffffffff",
        )
        for ((case, hex) in notOne) assertNull(txidOf(hex), case)
    }

    @Test
    fun aRepostGetsTheBytesOnlyWhileTheFileHoldsThatGuardWithThem() {
        val file = FakePlainFile()
        val payments = PendingPayments(file)
        assertNull(payments.repost(TEST_CHECK, signed) { fail("no line") })
        assertEquals(Claim.Written, payments.claim(TEST_CHECK, signed))
        assertEquals(SIGNED_HEX, payments.repost(TEST_CHECK, signed) { it })
        assertNull(payments.repost(TEST_CHECK, UnconfirmedSend("f".repeat(64), SENT_AT, null)) { fail("another payment of the wallet") })
        assertNull(payments.repost(OTHER_CHECK, signed) { fail("another wallet") })
        val lineOfThree = FakePlainFile().apply { text = "$TEST_CHECK $SIGNED_TXID $SENT_AT\n" }
        assertNull(PendingPayments(lineOfThree).repost(TEST_CHECK, signed) { fail("a line of three") }, "a line of three")
        for ((case, damaged) in damagedFiles()) assertNull(PendingPayments(damaged).repost(TEST_CHECK, signed) { fail(case) }, case)
        assertNull(PendingPayments(null).repost(TEST_CHECK, signed) { fail("no file") })
    }

    // C2, decision 4. While a repost of a payment runs, no settle drops its guard, so no clear can let another payment start while
    // its bytes may still land. Another wallet's guard settles as before, and a repost that threw leaves no mark behind.
    @Test
    fun noSettleDropsAGuardWhileARepostOfItRuns() {
        val file = FakePlainFile()
        val payments = PendingPayments(file)
        val other = UnconfirmedSend(TEST_TXID, SENT_AT)
        assertEquals(Claim.Written, payments.claim(TEST_CHECK, signed))
        assertEquals(Claim.Written, payments.claim(OTHER_CHECK, other))
        payments.repost(TEST_CHECK, signed) {
            assertFalse(PendingPayments(file).settle(TEST_CHECK, signed), "a settle from another instance during the repost")
            assertEquals(signed, payments.of(TEST_CHECK), "the guard during the repost")
            assertTrue(PendingPayments(file).settle(OTHER_CHECK, other), "another wallet's settle during the repost")
        }
        assertFailsWith<IllegalStateException> { payments.repost(TEST_CHECK, signed) { throw IllegalStateException("the post broke") } }
        assertTrue(payments.settle(TEST_CHECK, signed), "the settle once no repost runs")
        assertNull(file.text)
    }

    @Test
    fun aDamagedFileIsAPaymentOfUnknownOutcomeForEveryWallet() {
        for ((case, file) in damagedFiles()) {
            val payments = PendingPayments(file)
            assertEquals(DamagedGuard, payments.of(TEST_CHECK), case)
            assertEquals(DamagedGuard, payments.of(OTHER_CHECK), "another wallet, $case")
        }
    }

    @Test
    fun aClaimOnADamagedFileIsRefusedForEveryWalletAndWritesNothing() {
        for ((case, file) in damagedFiles()) {
            val before = file.text
            assertEquals(Claim.Damaged, PendingPayments(file).claim(TEST_CHECK, send), case)
            assertEquals(Claim.Damaged, PendingPayments(file).claim(OTHER_CHECK, send), "another wallet, $case")
            assertEquals(0, file.writes, "writes, $case")
            assertEquals(before, file.text, "the damaged file, $case")
        }
    }

    @Test
    fun theClearOfADamagedGuardDeletesTheFileSoEveryWalletCanClaimAgain() {
        for ((case, file) in damagedFiles()) {
            val payments = PendingPayments(file)
            payments.settle(TEST_CHECK, send)
            assertEquals(DamagedGuard, payments.of(TEST_CHECK), "a payment's settle left the damaged file, $case")
            payments.settle(TEST_CHECK, DamagedGuard)
            assertNull(file.text, "the file after the clear, $case")
            assertNull(payments.of(TEST_CHECK), case)
            assertNull(payments.of(OTHER_CHECK), "another wallet, $case")
            assertEquals(Claim.Written, payments.claim(TEST_CHECK, send), "a fresh guard, $case")
            assertEquals(send, payments.of(TEST_CHECK), case)
        }
    }

    // A wallet took the damaged guard at its bind, and another wallet cleared the file and posted since. The late clear must leave the
    // file that reads again, because it now names a payment that may be out.
    @Test
    fun aLateClearOfADamagedGuardKeepsAFileThatReadsAgain() {
        val file = FakePlainFile()
        val payments = PendingPayments(file)
        assertEquals(Claim.Written, payments.claim(OTHER_CHECK, send))
        payments.settle(TEST_CHECK, DamagedGuard)
        assertEquals(send, payments.of(OTHER_CHECK), "the other wallet's guard")
        assertEquals(send, assertIs<Claim.Taken>(payments.claim(OTHER_CHECK, UnconfirmedSend("f".repeat(64), SENT_AT))).earlier, "a second payment of that wallet")
    }

    @Test
    fun aPhoneWithNoFileHasNoGuardAndKeepsNone() {
        val payments = PendingPayments(null)
        assertNull(payments.of(TEST_CHECK))
        assertEquals(Claim.NotSaved, payments.claim(TEST_CHECK, send))
    }
}

private const val SENT_AT = 1_792_740_720L

/** Another wallet's fingerprint, distinct from [TEST_CHECK]. */
private const val OTHER_CHECK = "9f8e7d6c"

/** [SIGNED_HEX] with no segwit marker, flag or witness, which are the bytes its txid is the hash of. Cut from it by a Python run. */
private const val SIGNED_WITHOUT_WITNESS =
    "02000000014e1476a453b5bf2d197fe1c55d7d77c578b4eea36cdf114063c882814ec3b2280100000000fdffffff02899f050000000000160014f922f0c9f366" +
        "db682ab541cdcd4de10b289b14bbe8030000000000001600145d8e5de55dc968ddfc31227f5014930f0c7d9a5c06640200"

/** The coinbase of block 481,824, the first block of segwit's period, from `rust/fixtures/proof/mainnet_481824_coinbase_hex_real.txt`. */
private const val COINBASE_HEX =
    "010000000001010000000000000000000000000000000000000000000000000000000000000000ffffffff6403205a07f4d3f9da09acf878c2c9c96c410d6975" +
        "8f0eae0e479184e0564589052e832c42899c867100010000000000000000db9901006052ce25d80acfde2f425443432f20537570706f7274202f4e59412f00" +
        "000000000000000000000000000000000000000000025d322c57000000001976a9142c30a6aaac6d96687291475d7d52f4b469f665a688ac000000000000000026" +
        "6a24aa21a9ed6c3c4dff76b5760d58694147264d208689ee07823e5694c4872f856eacf5a5d801200000000000000000000000000000000000000000000000000000" +
        "00000000000000000000"
private const val COINBASE_TXID = "da917699942e4a96272401b534381a75512eeebe8403084500bd637bd47168b3"
