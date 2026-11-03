package com.mebit.app.data

import kotlin.test.Test
import kotlin.test.assertEquals

private const val BECH32 = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
private val ADDR = "tb1q" + (BECH32 + BECH32).take(38)
private val MAINNET = "bc1q" + (BECH32 + BECH32).take(38)

/** The same fake check as PaymentLinkTest: that testnet shape in one case. */
private fun fakeIsValid(address: String): Boolean {
    val lower = address.lowercase()
    return (address == lower || address == address.uppercase()) &&
        lower.length == 42 && lower.startsWith("tb1q") && lower.drop(4).all { it in BECH32 }
}

private fun outcome(scanned: String) = scanOutcome(scanned, ::fakeIsValid)

class ScanOutcomeTest {
    @Test
    fun aBareTestnetAddressIsAccepted() {
        assertEquals(ScanOutcome.Accept(ADDR), outcome(ADDR))
        assertEquals(ScanOutcome.Accept(ADDR.uppercase()), outcome(ADDR.uppercase()))
    }

    @Test
    fun aPaymentLinkWithAnAmountIsAcceptedWithItsTextWhole() {
        val link = "bitcoin:$ADDR?amount=0.005&label=Rent"
        assertEquals(ScanOutcome.Accept(link), outcome(link))
    }

    @Test
    fun aScanWithSpacesAroundTheAddressIsAcceptedAsAPasteWouldBe() {
        assertEquals(ScanOutcome.Accept(" $ADDR\n"), outcome(" $ADDR\n"))
    }

    @Test
    fun junkAnEmptyCodeAndAMainnetAddressAreRejectedWithTheirText() {
        listOf("https://example.com", "lightning:lnbc1u1p3xyz", "", MAINNET, "bitcoin:$MAINNET?amount=1", "bitcoin:$ADDR?req-x=1").forEach {
            assertEquals(ScanOutcome.Reject(it), outcome(it), it)
        }
    }

    @Test
    fun aCodeLongerThanAnyPaymentLinkIsRejectedAndCutBeforeItReachesTheField() {
        val head = "bitcoin:$ADDR?label="
        val atTheLimit = head + "a".repeat(2_000 - head.length)
        assertEquals(2_000, atTheLimit.length)
        assertEquals(ScanOutcome.Accept(atTheLimit), outcome(atTheLimit))
        assertEquals(ScanOutcome.Reject(atTheLimit), outcome(atTheLimit + "a"))
        assertEquals(ScanOutcome.Reject(atTheLimit), outcome(atTheLimit + "a".repeat(10_000)))
    }

    @Test
    fun theFieldGetsWhatAPasteOfTheSameTextWouldGiveIt() {
        val scanned = SendDraft()
        val pasted = SendDraft()
        listOf(ADDR, "bitcoin:${ADDR.uppercase()}?amount=0.005", "bitcoin:$MAINNET?amount=1", "junk").forEach { text ->
            scanned.enterDestination(outcome(text).text, ::fakeIsValid)
            pasted.enterDestination(text, ::fakeIsValid)
            assertEquals(pasted.destination, scanned.destination, text)
            assertEquals(pasted.amountText, scanned.amountText, text)
        }
    }
}
