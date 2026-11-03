package com.mebit.app.data

private const val MAX_SCAN_LENGTH = 2_000

/**
 * What the Send screen does with a scanned QR text. Either way the scan sheet closes and [text] goes into the destination
 * field exactly as a paste would, so a [Reject] shows the field's own INVALID ADDRESS instead of closing in silence.
 */
sealed interface ScanOutcome {
    val text: String

    /** [SendDraft.enterDestination] would fill the field (and, for a link with an amount, the amount) from this text. */
    data class Accept(override val text: String) : ScanOutcome

    /** Not a Bitcoin payment for this network. A code longer than any payment link is cut to [MAX_SCAN_LENGTH] characters, unparsed. */
    data class Reject(override val text: String) : ScanOutcome
}

fun scanOutcome(scanned: String, isValidAddress: (String) -> Boolean): ScanOutcome {
    if (scanned.length > MAX_SCAN_LENGTH) return ScanOutcome.Reject(scanned.take(MAX_SCAN_LENGTH))
    val accepted = when (parsePaymentLink(scanned, isValidAddress)) {
        is PaymentLink.Valid -> true
        // The text enterDestination puts in the field for plain text, which the screen then checks.
        PaymentLink.NotALink -> isValidAddress(scanned.filterNot(Char::isWhitespace))
        is PaymentLink.Refused -> false
    }
    return if (accepted) ScanOutcome.Accept(scanned) else ScanOutcome.Reject(scanned)
}
