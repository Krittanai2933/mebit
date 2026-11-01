package com.mebit.app.data

private const val SCHEME = "bitcoin:"
private const val MAX_INPUT_LENGTH = 2_048
private const val MAX_ADDRESS_LENGTH = 100
private const val MAX_DECIMALS = 8
private const val MAX_SATS = 21_000_000L * 100_000_000L

/** What a pasted, typed or scanned text turned out to be. Only [Valid] may fill the Send form. */
sealed interface PaymentLink {
    /** A `bitcoin:` link whose [address] passed the check. [amountSats] is a positive amount, or null when the link has none. */
    data class Valid(val address: String, val amountSats: Long?) : PaymentLink

    /** The text does not start with `bitcoin:` in any case, so the caller treats it as a plain address. */
    data object NotALink : PaymentLink

    /** A `bitcoin:` link that must fill nothing. [reason] is the first rule it broke. */
    data class Refused(val reason: Refusal) : PaymentLink
}

/** The rules a `bitcoin:` link can break, in the order [parsePaymentLink] checks them. */
enum class Refusal {
    /** More than 2,048 characters once the ends are trimmed. */
    TooLong,

    /** A control character or any whitespace inside the link. */
    ControlOrWhitespace,

    /** A `#` anywhere. */
    Fragment,

    /** `bitcoin://`. The address must follow the colon directly. */
    SchemeSlashes,

    /** Nothing between `bitcoin:` and the first `?`. */
    EmptyAddress,

    /** An address longer than 100 characters, or with a `%` or `&` in it. */
    AddressShape,

    /** The address check said no. Another network, a bad checksum and a mixed-case address all end here. */
    InvalidAddress,

    /** A parameter whose name starts with `req-`, in any case, with or without a value. The link says the wallet must understand it, and this one understands none. */
    RequiredParameter,

    /** A parameter name with a `%` in it, which could hide `req-`. */
    EncodedKey,

    /** A `%` in a value that is not followed by two hex digits. */
    BadPercentEscape,

    /** A second `amount` parameter. */
    DuplicateAmount,

    /** An amount that is not digits with at most one dot: empty, signed, an exponent, a separator, a second dot, or no digit at all. */
    AmountMalformed,

    /** An amount with more than 8 decimals. It is refused, never rounded or cut. */
    AmountTooPrecise,

    /** An amount of zero. */
    AmountNotPositive,

    /** An amount above 21,000,000 BTC. */
    AmountTooLarge,
}

/**
 * Reads a BIP-21 link such as `bitcoin:tb1q...?amount=0.005&label=Rent` from text that is hostile until proven otherwise.
 * It is stricter than most wallets. A link is taken whole or refused whole, and nothing in it is decoded, rounded or guessed.
 * The one change it makes is lower-casing an address that has no lower-case letter, as a QR in alphanumeric mode has.
 *
 * It is pure and answers every input. [isValidAddress] is the real address check for the network in use, injected because it is a call into Rust.
 * Only the text before the first `?` is the address. `label`, `message`, an `address` parameter and every other
 * parameter that does not start with `req-` are ignored.
 */
fun parsePaymentLink(input: String, isValidAddress: (String) -> Boolean): PaymentLink {
    val text = input.trim()
    if (text.take(SCHEME.length).asciiLowercase() != SCHEME) return PaymentLink.NotALink
    if (text.length > MAX_INPUT_LENGTH) return refused(Refusal.TooLong)
    // isISOControl also covers U+0080 to U+009F, which a list of U+0000 to U+001F and U+007F leaves out.
    if (text.any { it.isISOControl() || it.isWhitespace() }) return refused(Refusal.ControlOrWhitespace)
    if ('#' in text) return refused(Refusal.Fragment)
    val rest = text.drop(SCHEME.length)
    if (rest.startsWith("//")) return refused(Refusal.SchemeSlashes)
    val written = rest.substringBefore('?')
    if (written.isEmpty()) return refused(Refusal.EmptyAddress)
    if (written.length > MAX_ADDRESS_LENGTH || '%' in written || '&' in written) return refused(Refusal.AddressShape)
    // A QR in alphanumeric mode is all upper case, and bech32 reads the same in either case.
    val address = if (written.none { it in 'a'..'z' }) written.asciiLowercase() else written
    if (!isValidAddress(address)) return refused(Refusal.InvalidAddress)
    return readQuery(address, rest.substringAfter('?', ""))
}

private fun readQuery(address: String, query: String): PaymentLink {
    var amountSats: Long? = null
    for (part in query.split('&')) {
        val key = part.substringBefore('=')
        if (key.asciiLowercase().startsWith("req-")) return refused(Refusal.RequiredParameter)
        if ('%' in key) return refused(Refusal.EncodedKey)
        if ('=' !in part) continue
        val value = part.substringAfter('=')
        if (hasMalformedEscape(value)) return refused(Refusal.BadPercentEscape)
        if (key != "amount") continue
        if (amountSats != null) return refused(Refusal.DuplicateAmount)
        amountRefusal(value)?.let { return refused(it) }
        // parseBtc checks the whole part of 21000000.00000001 but not the total, so the total is checked here.
        amountSats = parseBtc(value)?.takeIf { it <= MAX_SATS } ?: return refused(Refusal.AmountTooLarge)
    }
    return PaymentLink.Valid(address, amountSats)
}

/** The rule [value] breaks as an amount, other than being too large, which only the conversion to sats can tell. */
private fun amountRefusal(value: String): Refusal? {
    val digits = value.filter { it != '.' }
    return when {
        value.count { it == '.' } > 1 || digits.isEmpty() || !digits.all { it in '0'..'9' } -> Refusal.AmountMalformed
        value.substringAfter('.', "").length > MAX_DECIMALS -> Refusal.AmountTooPrecise
        digits.all { it == '0' } -> Refusal.AmountNotPositive
        else -> null
    }
}

private fun hasMalformedEscape(value: String) = value.indices.any { i ->
    value[i] == '%' && !(i + 2 < value.length && value[i + 1].isAsciiHex() && value[i + 2].isAsciiHex())
}

private fun Char.isAsciiHex() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

// Only A to Z move. lowercase() turns a Kelvin sign into k and ignoreCase matches a dotless i with i, so look-alike text would pass as ASCII.
private fun String.asciiLowercase() = map { if (it in 'A'..'Z') it.lowercaseChar() else it }.joinToString("")

private fun refused(reason: Refusal) = PaymentLink.Refused(reason)

/**
 * Takes what the user typed or pasted in the destination field. A valid link fills the address and, when it has one, the amount.
 * Plain text and a refused link only set the field, without whitespace, so the screen's own address check shows the problem.
 */
fun SendDraft.enterDestination(text: String, isValidAddress: (String) -> Boolean) {
    when (val link = parsePaymentLink(text, isValidAddress)) {
        is PaymentLink.Valid -> {
            destination = link.address
            link.amountSats?.let { setAmountBtc(formatBtc(it).trimEnd('0').trimEnd('.')) }
        }
        PaymentLink.NotALink, is PaymentLink.Refused -> destination = text.filterNot(Char::isWhitespace)
    }
}
