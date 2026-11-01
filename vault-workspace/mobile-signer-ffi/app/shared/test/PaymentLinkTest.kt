package com.mebit.app.data

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val BECH32 = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
private const val MAX_SATS = 2_100_000_000_000_000L

// A testnet P2WPKH address is "tb1q" and 38 characters of the bech32 alphabet.
private val ADDR = "tb1q" + (BECH32 + BECH32).take(38)
private val OTHER = "tb1q" + (BECH32 + BECH32).reversed().take(38)
private val MAINNET = "bc1q" + (BECH32 + BECH32).take(38)

/** The address check these tests inject: that testnet shape, all in lower case or all in upper case. Everything else, a bc1 address included, is refused. */
private fun fakeIsValid(address: String): Boolean {
    val lower = address.lowercase()
    return (address == lower || address == address.uppercase()) &&
        lower.length == 42 && lower.startsWith("tb1q") && lower.drop(4).all { it in BECH32 }
}

private fun parse(input: String) = parsePaymentLink(input, ::fakeIsValid)

/** [input] with every character that is not printable ASCII spelled out, so a failure names the control character in it. */
private fun show(input: String) = input.map { if (it.code in 0x21..0x7E) "$it" else "<U+${it.code.toString(16)}>" }.joinToString("")

private fun assertValid(address: String, amountSats: Long?, vararg inputs: String) =
    inputs.forEach { assertEquals(PaymentLink.Valid(address, amountSats), parse(it), show(it)) }

private fun assertRefused(reason: Refusal, vararg inputs: String) =
    inputs.forEach { assertEquals(PaymentLink.Refused(reason), parse(it), show(it)) }

private fun assertNotALink(vararg inputs: String) = inputs.forEach { assertEquals(PaymentLink.NotALink, parse(it), show(it)) }

private fun withAmount(vararg values: String) = values.map { "bitcoin:$ADDR?amount=$it" }.toTypedArray()

private fun String.ascii() = map { if (it in 'A'..'Z') it + 32 else it }.joinToString("")

private fun Char.isHex() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

/** What anyone holding a result may rely on, whatever the input was. These are plain checks on the input, written apart from the parser, so the two have to agree. */
private fun assertSound(input: String, link: PaymentLink) {
    val text = input.trim()
    val startsLikeALink = text.take(8).ascii() == "bitcoin:"
    when (link) {
        PaymentLink.NotALink -> assertFalse(startsLikeALink, show(input))
        is PaymentLink.Refused -> assertTrue(startsLikeALink, show(input))
        is PaymentLink.Valid -> {
            val rest = text.drop(8)
            val written = rest.substringBefore('?')
            val parts = rest.substringAfter('?', "").split('&')
            val amounts = parts.filter { it.startsWith("amount=") }
            fun holds(claim: Boolean, what: String) = assertTrue(claim, "$what: ${show(input)}")
            holds(startsLikeALink && text.length <= 2_048, "a link of at most 2048 characters")
            holds(text.none { it.isISOControl() || it.isWhitespace() || it == '#' }, "no control character, whitespace or #")
            holds(fakeIsValid(link.address), "an address the check accepts")
            holds(link.address == written || (written.none { it in 'a'..'z' } && link.address == written.ascii()), "the address the link wrote")
            holds(parts.none { it.substringBefore('=').ascii().startsWith("req-") }, "no required parameter")
            holds(parts.none { '%' in it.substringBefore('=') }, "no escaped parameter name")
            holds(parts.filter { '=' in it }.all { part -> part.substringAfter('=').split('%').drop(1).all { it.length >= 2 && it[0].isHex() && it[1].isHex() } }, "only whole percent escapes")
            holds(amounts.size <= 1 && (link.amountSats != null) == amounts.isNotEmpty(), "an amount only when the link has one, and once")
            holds((link.amountSats ?: 1L) in 1L..MAX_SATS, "a positive amount of at most 21 million coins")
        }
    }
}

class PaymentLinkTest {
    @Test
    fun aBareLinkGivesTheAddressAndNoAmount() {
        assertValid(ADDR, null, "bitcoin:$ADDR", "bitcoin:$ADDR?")
    }

    @Test
    fun theExamplesInBip21BehaveAsTheBipSays() {
        assertValid(ADDR, 2_030_000_000, "bitcoin:$ADDR?amount=20.3&label=Luke-Jr")
        assertValid(ADDR, 5_000_000_000, "bitcoin:$ADDR?amount=50&label=Luke-Jr&message=Donation%20for%20project%20xyz")
        assertValid(ADDR, null, "bitcoin:$ADDR?somethingyoudontunderstand=50&somethingelseyoudontget=999")
        assertRefused(Refusal.RequiredParameter, "bitcoin:$ADDR?req-somethingyoudontunderstand=50&req-somethingelseyoudontget=999")
    }

    @Test
    fun theAmountIsReadInEveryShapeBip21Allows() {
        mapOf(
            "0.005" to 500_000L,
            "1" to 100_000_000L,
            ".5" to 50_000_000L,
            "5." to 500_000_000L,
            "0.00000001" to 1L,
            "0.10000000" to 10_000_000L,
            "007.50" to 750_000_000L,
            "21000000" to 2_100_000_000_000_000L,
        ).forEach { (text, sats) -> assertValid(ADDR, sats, "bitcoin:$ADDR?amount=$text") }
    }

    @Test
    fun labelMessageAndUnknownKeysAreIgnoredEscapesAndAll() {
        assertValid(
            ADDR,
            500_000,
            "bitcoin:$ADDR?amount=0.005&label=Rent%20for%20May&message=%E0%B8%AA%E0%B8%A7&lightning=lnbc1u1p3xyz&foo=bar",
            "bitcoin:$ADDR?label=a%2fb%2Fc%00&amount=0.005",
        )
    }

    @Test
    fun theSchemeIsMatchedIgnoringCase() {
        assertValid(ADDR, 1, "BITCOIN:$ADDR?amount=0.00000001", "Bitcoin:$ADDR?amount=0.00000001", "bItCoIn:$ADDR?amount=0.00000001")
    }

    @Test
    fun anAllUppercaseAddressFromAQrIsLowercasedBeforeTheCheck() {
        assertValid(ADDR, null, "BITCOIN:${ADDR.uppercase()}")
        assertValid(ADDR, 100_000_000, "bitcoin:${ADDR.uppercase()}?amount=1")
    }

    @Test
    fun anAddressKeyInTheQueryIsIgnoredAndThePathAddressWins() {
        assertValid(ADDR, null, "bitcoin:$ADDR?address=$OTHER")
        assertValid(ADDR, 100_000_000, "bitcoin:$ADDR?amount=1&address=$OTHER&address=$MAINNET")
    }

    @Test
    fun keysAreMatchedExactlyAndPartsWithoutAnEqualsSignAreIgnored() {
        assertValid(
            ADDR,
            null,
            "bitcoin:$ADDR?AMOUNT=5",
            "bitcoin:$ADDR?Amount=5",
            "bitcoin:$ADDR?amount",
            "bitcoin:$ADDR?amounts=5",
            "bitcoin:$ADDR?xamount=5",
            "bitcoin:$ADDR?=5",
            "bitcoin:$ADDR?foo",
            "bitcoin:$ADDR?&&",
            "bitcoin:$ADDR?label=req-foo",
            "bitcoin:$ADDR?reqfoo=1",
            "bitcoin:$ADDR?xreq-foo=1",
        )
        assertValid(ADDR, 100_000_000, "bitcoin:$ADDR?amount=1&AMOUNT=2")
    }

    @Test
    fun aParameterSplitsAtItsFirstEqualsSignAndAQuestionMarkInTheQueryIsData() {
        assertValid(ADDR, 100_000_000, "bitcoin:$ADDR?label=a=b&amount=1", "bitcoin:$ADDR?label=a?b&amount=1")
        assertRefused(Refusal.AmountMalformed, "bitcoin:$ADDR?amount=1=2", "bitcoin:$ADDR?amount=1?x=2")
    }

    @Test
    fun surroundingWhitespaceAndATrailingNewlineAreTrimmed() {
        assertValid(ADDR, null, "  bitcoin:$ADDR\n", "\tbitcoin:$ADDR\r\n")
        assertValid(ADDR, 100_000_000, "\r\n\tbitcoin:$ADDR?amount=1 \t\n")
    }

    @Test
    fun plainTextIsNotALink() {
        assertNotALink(
            ADDR,
            "  $ADDR\n",
            "",
            "   ",
            "bitcoin",
            "bitcoi:$ADDR",
            "bitcoin.$ADDR",
            "lightning:lnbc1u1p3xyz",
            "http://example.com/bitcoin:$ADDR",
            "b\u0131tco\u0131n:$ADDR",
            "\uFF42\uFF49\uFF54\uFF43\uFF4F\uFF49\uFF4E:$ADDR",
            "\u200Bbitcoin:$ADDR",
        )
    }

    @Test
    fun anAddressOfAnotherNetworkIsRefused() {
        assertRefused(Refusal.InvalidAddress, "bitcoin:$MAINNET", "bitcoin:$MAINNET?amount=1")
    }

    @Test
    fun anAddressTheCheckRejectsIsRefusedWhateverTheRestOfTheLinkSays() {
        // The check is injected, so this proves the parser asks it and obeys. The real checksum is Rust's.
        assertRefused(Refusal.InvalidAddress, "bitcoin:${ADDR.dropLast(1)}b", "bitcoin:${ADDR.dropLast(1)}", "bitcoin:${ADDR}q", "bitcoin:${ADDR.dropLast(1)}b?amount=1&label=x")
    }

    @Test
    fun aMixedCaseAddressIsLeftAsItIsAndRefused() {
        assertRefused(Refusal.InvalidAddress, "bitcoin:${ADDR.take(10).uppercase() + ADDR.drop(10)}")
    }

    @Test
    fun aLookAlikeCharacterIsNotFoldedIntoTheAddress() {
        // A Kelvin sign is upper case and lowercases to the k that ADDR has in it.
        val kelvin = ADDR.uppercase().replace('K', '\u212A')
        assertEquals(PaymentLink.Refused(Refusal.InvalidAddress), parsePaymentLink("bitcoin:$kelvin") { it == ADDR })
    }

    @Test
    fun slashesAfterTheSchemeAreRefused() {
        assertRefused(Refusal.SchemeSlashes, "bitcoin://$ADDR", "bitcoin://", "BITCOIN://$ADDR?amount=1")
    }

    @Test
    fun anEmptyAddressIsRefused() {
        assertRefused(Refusal.EmptyAddress, "bitcoin:", "BITCOIN:", "bitcoin:?amount=1", "bitcoin:?")
    }

    @Test
    fun anAddressThatCannotBePlainTextIsRefused() {
        assertRefused(Refusal.AddressShape, "bitcoin:$ADDR%41", "bitcoin:%41$ADDR", "bitcoin:$ADDR&amount=1", "bitcoin:${"a".repeat(101)}")
        // 100 characters pass the shape rule and then meet the address check.
        assertRefused(Refusal.InvalidAddress, "bitcoin:${"a".repeat(100)}")
    }

    @Test
    fun aFragmentIsRefusedWhereverItStands() {
        assertRefused(Refusal.Fragment, "bitcoin:$ADDR#top", "bitcoin:$ADDR?amount=1#top", "bitcoin:$ADDR?label=a#b&amount=1", "bitcoin:#$ADDR")
    }

    @Test
    fun aRequiredParameterIsRefusedWhereverItStands() {
        assertRefused(
            Refusal.RequiredParameter,
            "bitcoin:$ADDR?req-foo=1",
            "bitcoin:$ADDR?amount=1&req-foo=1",
            "bitcoin:$ADDR?label=x&req-bar=2&amount=1",
            "bitcoin:$ADDR?req-foo",
            "bitcoin:$ADDR?req-=1",
            "bitcoin:$ADDR?REQ-foo=1",
            "bitcoin:$ADDR?Req-Foo=1",
        )
    }

    @Test
    fun aParameterNameHidingBehindPercentEscapesIsRefused() {
        assertRefused(Refusal.EncodedKey, "bitcoin:$ADDR?%72eq-foo=1", "bitcoin:$ADDR?%72eq-foo", "bitcoin:$ADDR?amount=1&re%71-foo=1", "bitcoin:$ADDR?%61mount=1")
    }

    @Test
    fun aSecondAmountIsRefused() {
        assertRefused(
            Refusal.DuplicateAmount,
            "bitcoin:$ADDR?amount=1&amount=1",
            "bitcoin:$ADDR?amount=1&amount=2",
            "bitcoin:$ADDR?amount=1&label=x&amount=2",
            "bitcoin:$ADDR?amount=1&amount=",
        )
    }

    @Test
    fun anUnreadableAmountIsRefused() {
        assertRefused(
            Refusal.AmountMalformed,
            *withAmount("", "abc", "1e-3", "1E3", "-0.1", "+0.1", "0.1.2", "1,5", ".", "..", "0x10", "1_000", "\u0661", "1%2E5", "NaN", "0.5BTC", "1/2", "1;label=x"),
        )
    }

    @Test
    fun anAmountWithMoreThanEightDecimalsIsRefusedNotCut() {
        assertRefused(Refusal.AmountTooPrecise, *withAmount("0.123456789", "0.100000000", "1.000000000", "0.000000001"))
    }

    @Test
    fun anAmountOfZeroIsRefused() {
        assertRefused(Refusal.AmountNotPositive, *withAmount("0", "0.00000000", "0.0", ".0", "0.", "000"))
    }

    @Test
    fun anAmountAboveAllTheCoinsThereWillBeIsRefused() {
        // parseBtc lets 21000000.00000001 through, because it only checks the whole part.
        assertRefused(Refusal.AmountTooLarge, *withAmount("21000001", "21000000.00000001", "21000000.5", "99999999999999999999", "999999999999999999999999999999.99999999"))
    }

    @Test
    fun whitespaceAndControlCharactersInsideAreRefused() {
        val inside = listOf(" ", "\n", "\t", "\r", "\u0000", "\u0001", "\u001F", "\u007F", "\u0085", "\u00A0", "\u2028", "\u3000")
        inside.forEach { c ->
            assertRefused(
                Refusal.ControlOrWhitespace,
                "bitcoin:$c$ADDR",
                "bitcoin:$ADDR$c?amount=1",
                "bitcoin:$ADDR?amount=1${c}&label=x",
                "bitcoin:$ADDR?label=a${c}b",
            )
        }
        // Trimming takes whitespace off the ends, not a control character that is not whitespace.
        assertRefused(Refusal.ControlOrWhitespace, "bitcoin:$ADDR\u0000", "bitcoin:$ADDR?amount=1\u007F")
    }

    @Test
    fun aMalformedPercentEscapeIsRefused() {
        assertRefused(
            Refusal.BadPercentEscape,
            "bitcoin:$ADDR?label=%zz",
            "bitcoin:$ADDR?label=%2",
            "bitcoin:$ADDR?label=abc%",
            "bitcoin:$ADDR?message=%G1",
            "bitcoin:$ADDR?lightning=%",
            "bitcoin:$ADDR?label=%%41",
            "bitcoin:$ADDR?label=%41%zz",
            "bitcoin:$ADDR?label=a%41%2",
            "bitcoin:$ADDR?label=%\u0661\u0661",
            "bitcoin:$ADDR?amount=1&label=%zz",
            "bitcoin:$ADDR?label=%zz&amount=1",
        )
    }

    @Test
    fun anInputOfMoreThan2048CharactersIsRefusedAfterTheEndsAreTrimmed() {
        val head = "bitcoin:$ADDR?label="
        val atTheLimit = head + "a".repeat(2_048 - head.length)
        assertValid(ADDR, null, atTheLimit, "$atTheLimit   \n")
        assertRefused(Refusal.TooLong, atTheLimit + "a", atTheLimit + "a".repeat(5_000))
    }

    @Test
    fun theAddressCheckNeverSeesTextThatCannotBeAnAddress() {
        val seen = mutableListOf<String>()
        val record = { address: String -> seen += address; true }
        listOf(
            ADDR,
            "",
            "bitcoin:",
            "bitcoin:?amount=1",
            "bitcoin://$ADDR",
            "bitcoin:$ADDR#x",
            "bitcoin:$ADDR%41",
            "bitcoin:$ADDR&x",
            "bitcoin: $ADDR",
            "bitcoin:${"a".repeat(101)}",
            "bitcoin:$ADDR?label=" + "a".repeat(3_000),
        ).forEach { parsePaymentLink(it, record) }
        assertEquals(emptyList(), seen)
    }

    @Test
    fun theAddressCheckGetsTheAddressAloneInLowerCaseOnce() {
        val seen = mutableListOf<String>()
        parsePaymentLink("BITCOIN:${ADDR.uppercase()}?amount=1&label=x", { address -> seen += address; true })
        assertEquals(listOf(ADDR), seen)
    }

    @Test
    fun noInputMakesAValidResultThatTheLinkDidNotSay() {
        val pieces = listOf(
            "bitcoin:", "BITCOIN:", ADDR, ADDR.uppercase(), MAINNET, OTHER, "?", "&", "=", "?amount=", "&amount=", "?label=", "&req-x=1", "?req-x", "&address=", "&AMOUNT=",
            "0.5", "21000000", "1e-3", "0.123456789", ".", "-", "%", "%41", "%4", "%zz", "#", " ", "\n", "\u0000", "a", "Z", "\u212A", "\u0661", "/",
        )
        val heads = listOf("", "bitcoin:", "bitcoin:$ADDR", "bitcoin:$ADDR", "BITCOIN:${ADDR.uppercase()}")
        val random = Random(2026)
        var valid = 0
        var refused = 0
        var plain = 0
        repeat(30_000) {
            val input = heads.random(random) + List(random.nextInt(0, 9)) { pieces.random(random) }.joinToString("")
            val link = parse(input)
            assertSound(input, link)
            when (link) {
                is PaymentLink.Valid -> valid++
                is PaymentLink.Refused -> refused++
                PaymentLink.NotALink -> plain++
            }
        }
        // A sweep that never reaches one of the three results proves nothing about it.
        assertTrue(valid > 300 && refused > 300 && plain > 300, "valid=$valid refused=$refused plain=$plain")
    }

    private fun draftWith(destination: String = "", amount: String = "") = SendDraft().apply {
        this.destination = destination
        if (amount.isNotEmpty()) setAmountBtc(amount)
    }

    @Test
    fun aRefusedLinkKeepsTheTypedAmountAndPutsTheRawTextInTheField() {
        val draft = draftWith(destination = "tb1qold", amount = "0.25")
        draft.enterDestination("bitcoin:$ADDR?label=a b&amount=1e-3", ::fakeIsValid)
        assertEquals("bitcoin:$ADDR?label=ab&amount=1e-3", draft.destination)
        assertEquals("0.25", draft.amountText)
        assertEquals(25_000_000L, draft.amountSats)
        draft.enterDestination("bitcoin:$MAINNET?amount=1", ::fakeIsValid)
        assertEquals("bitcoin:$MAINNET?amount=1", draft.destination)
        assertEquals(25_000_000L, draft.amountSats)
    }

    @Test
    fun aValidLinkWithoutAnAmountKeepsTheTypedAmount() {
        val draft = draftWith(destination = "tb1qold", amount = "0.25")
        draft.enterDestination("bitcoin:$ADDR?label=Rent", ::fakeIsValid)
        assertEquals(ADDR, draft.destination)
        assertEquals("0.25", draft.amountText)
        assertEquals(25_000_000L, draft.amountSats)
    }

    @Test
    fun aValidLinkWithAnAmountReplacesTheTypedOne() {
        val draft = draftWith(destination = "tb1qold", amount = "0.25")
        draft.enterDestination("bitcoin:${ADDR.uppercase()}?amount=0.005&label=Rent", ::fakeIsValid)
        assertEquals(ADDR, draft.destination)
        assertEquals("0.005", draft.amountText)
        assertEquals(500_000L, draft.amountSats)
    }

    @Test
    fun aLinkAmountIsWrittenAsAPlainDecimalWithNoTrailingZeros() {
        mapOf(
            "1" to "1",
            "10" to "10",
            "100.10" to "100.1",
            "0.50000000" to "0.5",
            ".5" to "0.5",
            "5." to "5",
            "0.00000001" to "0.00000001",
            "21000000" to "21000000",
        ).forEach { (link, field) ->
            val draft = draftWith()
            draft.enterDestination("bitcoin:$ADDR?amount=$link", ::fakeIsValid)
            assertEquals(field, draft.amountText, link)
            assertEquals(parseBtc(link), draft.amountSats, link)
        }
    }

    @Test
    fun plainTextEntersTheFieldWithoutWhitespaceAndLeavesTheAmount() {
        val draft = draftWith(amount = "0.25")
        draft.enterDestination(" $ADDR\n ab\tc ", ::fakeIsValid)
        assertEquals(ADDR + "abc", draft.destination)
        assertEquals(25_000_000L, draft.amountSats)
        draft.enterDestination("", ::fakeIsValid)
        assertEquals("", draft.destination)
        assertEquals("0.25", draft.amountText)
    }
}
