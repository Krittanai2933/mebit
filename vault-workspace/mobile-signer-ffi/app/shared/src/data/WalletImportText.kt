package com.mebit.app.data

import com.mebit.ffi.signer.WalletFileFormat
import com.mebit.ffi.signer.WalletFileRead
import com.mebit.ffi.signer.WalletImport

/**
 * Pure. The wallet's name from the file it came from: the last extension dropped and "_" read as a space. "" when nothing is left,
 * and the caller then names it "Wallet <number>".
 */
internal fun walletNameFromFile(fileName: String): String = fileName.trim().substringBeforeLast('.', fileName.trim()).replace('_', ' ').trim()

/**
 * Pure. The text a wallet imported from a file is stored as, in the layout [walletBsms] writes: the header, [WalletImport.descriptor] on line 2
 * (the line [SavedVaults] reads at every start), one "fingerprint path" line per key in the descriptor's order, the path restriction line
 * and the two "#" lines.
 */
internal fun importedBsms(wallet: WalletImport): String = listOf(
    "BSMS 1.0",
    wallet.descriptor,
    wallet.shape.keys.joinToString("\n") { "${it.fingerprint} ${it.path}" },
    "/0/*,/1/*",
    "# mebit imported_file · receive[0]=${wallet.shape.firstAddress}",
    "# export=file · not QR",
).joinToString("\n")

/** Pure. For each key of [wallet], in the descriptor's order, the keyring key with the same fingerprint and xpub, or null. */
internal fun keyringMatches(wallet: WalletImport, keyring: List<SavedKey>): List<SavedKey?> =
    wallet.shape.keys.map { k -> keyring.firstOrNull { it.fingerprint.equals(k.fingerprint, ignoreCase = true) && it.xpub == k.xpub } }

internal fun alreadyInAppLine(name: String): String = "Wallet นี้อยู่ในแอปแล้ว · $name"

/** The first address in groups of four characters, as Review shows it for the user to compare. */
internal fun addressGroups(address: String): String = address.chunked(4).joinToString(" ")

/** The readable file Import is reviewing: its name and what the core read. */
data class ImportPending(val fileName: String, val wallet: WalletImport)

/** One ticked or plain line of the review. */
data class ReviewRow(val text: String, val ticked: Boolean, val tag: String)

/** What 3.9.3 shows for a readable file. */
data class ImportReview(val title: String, val sub: String, val rows: List<ReviewRow>, val firstAddress: String)

/** Pure. Penpot 3.9.3's card and rows for [wallet] named [name]. The row of this phone's key never blocks: it is a tick or plain information. */
internal fun importReview(wallet: WalletImport, name: String, keyring: List<SavedKey>): ImportReview {
    val shape = wallet.shape
    val m = shape.needed.toInt()
    val n = shape.keys.size
    val mine = keyringMatches(wallet, keyring.filter { it.source == KeySourceLabel.ThisDevice }).firstNotNullOfOrNull { it }
    val rows = listOf(
        ReviewRow(if (wallet.format == WalletFileFormat.DESCRIPTOR) "อ่าน descriptor ได้" else "อ่านไฟล์ BSMS 1.0 ได้", true, "import_row_read"),
        ReviewRow("เครือข่าย testnet ตรงกับแอป", true, "import_row_network"),
        ReviewRow("รูปแบบ wsh(sortedmulti) · $m-of-$n", true, "import_row_shape"),
        ReviewRow("กุญแจ $n ดอก ไม่ซ้ำกัน", true, "import_row_keys"),
        if (mine != null) ReviewRow("มีกุญแจของเครื่องนี้ · ${fingerprintGroups(mine.fingerprint)}", true, "import_row_mine")
        else ReviewRow("ไม่มีกุญแจของเครื่องนี้", false, "import_row_mine"),
    )
    return ImportReview("$name · $m-of-$n", "Native SegWit · testnet · ต้องเซ็น $m จาก $n กุญแจ", rows, addressGroups(shape.firstAddress))
}

/** What 3.9.4 and 3.9.5 and their two siblings say: a [title] and a [body] of lines. */
data class ImportProblem(val title: String, val body: String)

internal val UNREADABLE_FILE = ImportProblem("อ่านไฟล์นี้ไม่ได้", "ต้องเป็นไฟล์ BSMS 1.0 หรือ descriptor")

/** Pure. Null for a file that was read, else the dialog's words. */
internal fun problemOf(read: WalletFileRead): ImportProblem? = when (read) {
    is WalletFileRead.Read -> null
    WalletFileRead.WrongNetwork -> ImportProblem("เครือข่ายไม่ตรงกัน", "Wallet นี้อยู่บนเครือข่าย mainnet (xpub)\nแต่แอปนี้ใช้ testnet (tpub) เท่านั้น")
    is WalletFileRead.Unsupported -> ImportProblem("รูปแบบนี้ยังไม่รองรับ", "รองรับเฉพาะ wsh(sortedmulti(…))\n${unsupportedLine(read.found)}")
    is WalletFileRead.AddressMismatch -> ImportProblem("ที่อยู่แรกไม่ตรงกับกุญแจ", "ไฟล์นี้อาจถูกแก้ไข mebit จึงไม่นำเข้า")
    is WalletFileRead.Unreadable -> UNREADABLE_FILE
}

/** Pure. The Thai line for what Rust found, by the names `bsms.rs` gives: a kind of descriptor, or one of the four refusals of the keys. */
internal fun unsupportedLine(found: String): String = when {
    found == "duplicate keys" || found == "two keys from one phrase" -> "มีกุญแจจาก phrase เดียวกันสองดอก"
    found == "more than 15 keys" -> "มีกุญแจเกิน 15 ดอก"
    found.startsWith("key suffix") || found == "mixed key suffixes" || found.startsWith("path restriction") -> "เส้นทางกุญแจแบบนี้ยังไม่รองรับ"
    found.endsWith(")") -> "ไฟล์นี้เป็น ${found.dropLast(1)}(…))"
    else -> "ไฟล์นี้เป็น $found(…)"
}
