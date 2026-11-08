package com.mebit.app.data

private const val MAX_SCAN_LENGTH = 2_000

/**
 * Whether Send takes a scanned QR text. True means Send enters [scanned] into the destination field exactly as a paste
 * would. False keeps the scan sheet open on its scan failed dialog. A code longer than any payment link is false, unparsed.
 */
fun acceptsScan(scanned: String, isValidAddress: (String) -> Boolean): Boolean {
    if (scanned.length > MAX_SCAN_LENGTH) return false
    return when (parsePaymentLink(scanned, isValidAddress)) {
        is PaymentLink.Valid -> true
        // The text enterDestination puts in the field for plain text, which the screen then checks.
        PaymentLink.NotALink -> isValidAddress(scanned.filterNot(Char::isWhitespace))
        is PaymentLink.Refused -> false
    }
}
