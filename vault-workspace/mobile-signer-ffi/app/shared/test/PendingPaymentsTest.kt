package com.mebit.app.data

import com.mebit.app.data.SingleSigWallet.DamagedGuard
import com.mebit.app.data.SingleSigWallet.UnconfirmedSend
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

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
    )

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
