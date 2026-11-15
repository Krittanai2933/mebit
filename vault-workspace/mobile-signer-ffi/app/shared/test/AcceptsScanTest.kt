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

private fun accepts(scanned: String) = acceptsScan(scanned, ::fakeIsValid)

class AcceptsScanTest {
    @Test
    fun aBareTestnetAddressIsAccepted() {
        assertEquals(true, accepts(ADDR))
        assertEquals(true, accepts(ADDR.uppercase()))
    }

    @Test
    fun aPaymentLinkWithAnAmountIsAccepted() {
        assertEquals(true, accepts("bitcoin:$ADDR?amount=0.005&label=Rent"))
    }

    @Test
    fun aScanWithSpacesAroundTheAddressIsAcceptedAsAPasteWouldBe() {
        assertEquals(true, accepts(" $ADDR\n"))
    }

    @Test
    fun junkAnEmptyCodeAndAMainnetAddressAreRefused() {
        listOf("https://example.com", "lightning:lnbc1u1p3xyz", "", MAINNET, "bitcoin:$MAINNET?amount=1", "bitcoin:$ADDR?req-x=1").forEach {
            assertEquals(false, accepts(it), it)
        }
    }

    @Test
    fun aCodeLongerThanAnyPaymentLinkIsRefused() {
        val head = "bitcoin:$ADDR?label="
        val atTheLimit = head + "a".repeat(2_000 - head.length)
        assertEquals(2_000, atTheLimit.length)
        assertEquals(true, accepts(atTheLimit))
        assertEquals(false, accepts(atTheLimit + "a"))
        assertEquals(false, accepts(atTheLimit + "a".repeat(10_000)))
    }
}
