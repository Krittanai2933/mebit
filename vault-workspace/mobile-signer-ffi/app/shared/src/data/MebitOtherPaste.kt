package com.mebit.app.data

internal fun parseExternalKeyPaste(text: String): PendingKey {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) throw IllegalArgumentException("ว่าง")
    val cosigner = if (trimmed.startsWith("BSMS 1.0")) parseBsmsCosigner(trimmed) else parsePlainCosigner(trimmed)
    bip48Refusal(cosigner.derivationPath, cosigner.xpub)?.let { throw IllegalArgumentException("$NOT_FOR_MULTISIG · $it") }
    return PendingKey(
        label = "mebit · เครื่องอื่น · ${cosigner.fingerprint}",
        fingerprint = cosigner.fingerprint,
        xpub = cosigner.xpub,
        derivationPath = cosigner.derivationPath,
        source = KeySourceLabel.MebitOther,
    )
}

private data class ParsedCosigner(
    val fingerprint: String,
    val xpub: String,
    val derivationPath: String,
)

private fun parsePlainCosigner(text: String): ParsedCosigner {
    val lines = pasteLines(text)
    if (lines.size != 3) {
        throw IllegalArgumentException("วาง fingerprint, xpub และ derivation path คนละบรรทัด")
    }
    return ParsedCosigner(
        fingerprint = normalizeFingerprint(lines[0]),
        xpub = requireAccountXpub(lines[1]),
        derivationPath = normalizeDerivationPath(lines[2]),
    )
}

private fun parseBsmsCosigner(text: String): ParsedCosigner {
    val lines = pasteLines(text)
    if (lines.isEmpty() || !lines[0].startsWith("BSMS 1.0")) {
        throw IllegalArgumentException("BSMS ไม่ถูกต้อง")
    }
    val descriptor = lines.firstOrNull { "sortedmulti(" in it }
        ?: throw IllegalArgumentException("BSMS ไม่มี descriptor sortedmulti")
    val keys = splitSortedMultiKeys(descriptor).map { parseDescriptorKey(it) }
    if (keys.isEmpty()) throw IllegalArgumentException("BSMS ไม่มี xpub")
    val fingerprints = lines.mapNotNull { line ->
        Regex("^([0-9a-fA-F]{8})\\s+(\\S+)$").matchEntire(line)?.let {
            it.groupValues[1] to it.groupValues[2]
        }
    }
    if (fingerprints.isNotEmpty() && fingerprints.size != keys.size) {
        throw IllegalArgumentException("จำนวน fingerprint ไม่ตรงกับ xpub")
    }
    val resolved = keys.mapIndexed { index, key ->
        val line = fingerprints.getOrNull(index)
        val fingerprint = key.fingerprint ?: line?.first
            ?: throw IllegalArgumentException("ไม่มี fingerprint")
        val path = key.path ?: line?.second
            ?: throw IllegalArgumentException("ไม่มี derivation path")
        val normalizedFingerprint = normalizeFingerprint(fingerprint)
        val normalizedPath = normalizeDerivationPath(path)
        if (line != null && normalizeFingerprint(line.first) != normalizedFingerprint) {
            throw IllegalArgumentException("fingerprint ไม่ตรงกับ descriptor")
        }
        if (line != null && normalizeDerivationPath(line.second) != normalizedPath) {
            throw IllegalArgumentException("derivation path ไม่ตรงกับ descriptor")
        }
        ParsedCosigner(normalizedFingerprint, requireAccountXpub(key.xpub), normalizedPath)
    }
    // A vault file lists every cosigner. This screen stages one key, the first record.
    return resolved.first()
}

private data class DescriptorKey(val fingerprint: String?, val path: String?, val xpub: String)

private fun parseDescriptorKey(token: String): DescriptorKey {
    val trimmed = token.trim()
    val origin = Regex("^\\[([0-9a-fA-F]{8})/([^\\]]+)]([xtuvyz]pub[1-9A-HJ-NP-Za-km-z]+)(/.*)?$")
        .matchEntire(trimmed)
    if (origin != null) {
        return DescriptorKey(origin.groupValues[1], origin.groupValues[2], origin.groupValues[3])
    }
    val bare = Regex("^([xtuvyz]pub[1-9A-HJ-NP-Za-km-z]+)(/.*)?$").matchEntire(trimmed)
        ?: throw IllegalArgumentException("xpub ไม่ถูกต้อง")
    return DescriptorKey(null, null, bare.groupValues[1])
}

private fun splitSortedMultiKeys(descriptor: String): List<String> {
    val start = descriptor.indexOf("sortedmulti(")
    if (start < 0) throw IllegalArgumentException("BSMS ไม่มี descriptor sortedmulti")
    var index = start + "sortedmulti(".length
    while (index < descriptor.length && descriptor[index].isDigit()) index++
    if (index >= descriptor.length || descriptor[index] != ',') {
        throw IllegalArgumentException("BSMS ไม่มี descriptor sortedmulti")
    }
    index++
    val keys = mutableListOf<String>()
    val current = StringBuilder()
    var brackets = 0
    var parens = 1
    while (index < descriptor.length) {
        val char = descriptor[index]
        when {
            char == '[' -> {
                brackets++
                current.append(char)
            }
            char == ']' -> {
                brackets--
                current.append(char)
            }
            char == '(' -> {
                parens++
                current.append(char)
            }
            char == ')' && brackets == 0 -> {
                parens--
                if (parens == 0) {
                    if (current.isNotBlank()) keys.add(current.toString())
                    return keys
                }
                current.append(char)
            }
            char == ',' && brackets == 0 && parens == 1 -> {
                keys.add(current.toString())
                current.clear()
            }
            else -> current.append(char)
        }
        index++
    }
    throw IllegalArgumentException("BSMS ไม่มี descriptor sortedmulti")
}

private fun pasteLines(text: String): List<String> =
    text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

private fun normalizeFingerprint(raw: String): String {
    val fingerprint = raw.trim()
    if (!Regex("^[0-9a-fA-F]{8}$").matches(fingerprint)) {
        throw IllegalArgumentException("fingerprint ต้องเป็นเลขฐานสิบหก 8 ตัว")
    }
    return fingerprint.lowercase()
}

private fun normalizeDerivationPath(raw: String): String {
    var path = raw.trim()
    if (path.isEmpty()) throw IllegalArgumentException("ไม่มี derivation path")
    path = Regex("(\\d+)[hH]").replace(path, "$1'")
    if (!path.startsWith("m/")) path = "m/$path"
    if (!Regex("^m(/\\d+'?)+$").matches(path)) {
        throw IllegalArgumentException("derivation path ไม่ถูกต้อง")
    }
    return path
}

private val accountXpubPrefixes = listOf("xpub", "ypub", "zpub", "tpub", "upub", "vpub")
private val accountPrivPrefixes = listOf("xprv", "yprv", "zprv", "tprv", "uprv", "vprv")

private fun requireAccountXpub(token: String): String {
    val xpub = token.trim()
    if (accountPrivPrefixes.any { xpub.startsWith(it) }) {
        throw IllegalArgumentException("อย่าวาง private key")
    }
    val prefixOk = accountXpubPrefixes.any { xpub.startsWith(it) }
    val alphabet = Regex("^[123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz]+$")
    if (!prefixOk || xpub.length !in 100..120 || !alphabet.matches(xpub)) {
        throw IllegalArgumentException("xpub ไม่ถูกต้อง")
    }
    return xpub
}

private const val NOT_FOR_MULTISIG = "ใช้กุญแจนี้สร้าง Wallet แบบ Multisig (Native SegWit) ไม่ได้"
private const val BIP48_PATH = "m/48'/1'/N'/2'"
private val bip48NativeSegwit = Regex("^m/48'/1'/\\d{1,9}'/2'$")
private val bip48NestedSegwit = Regex("^m/48'/1'/\\d{1,9}'/1'$")
private val singleKeyPurpose = Regex("^m/(44|49|84|86)'(/|$)")

/**
 * Why a pasted key is not a key of a several-key wallet, or null when it is one. In this testnet build that key is a tpub at
 * m/48'/1'/N'/2', BIP48's Native SegWit (P2WSH) script type, the rule script.rs holds such a key to, so the paste refuses what
 * Choose Keys could never make into a wallet.
 */
private fun bip48Refusal(path: String, xpub: String): String? {
    val prefix = xpub.take(4)
    val purpose = singleKeyPurpose.find(path)?.groupValues?.get(1)
    return when {
        prefix == "ypub" || prefix == "upub" -> "$prefix เป็นกุญแจของกระเป๋ากุญแจเดียวตาม BIP49 · ต้องเป็น tpub ที่ $BIP48_PATH ตาม BIP48"
        prefix == "zpub" || prefix == "vpub" -> "$prefix เป็นกุญแจของกระเป๋ากุญแจเดียวตาม BIP84 · ต้องเป็น tpub ที่ $BIP48_PATH ตาม BIP48"
        prefix == "xpub" -> "xpub เป็นกุญแจ mainnet · แอปนี้ใช้ tpub ของ testnet ที่ $BIP48_PATH"
        purpose != null -> "$path เป็น path ของกระเป๋ากุญแจเดียวตาม BIP$purpose · ต้องเป็น $BIP48_PATH ตาม BIP48"
        bip48NestedSegwit.matches(path) -> "$path ลงท้ายด้วย 1' คือ Nested SegWit (P2SH-P2WSH) · ต้องลงท้ายด้วย 2' คือ Native SegWit (P2WSH)"
        !bip48NativeSegwit.matches(path) -> "ต้องเป็น $BIP48_PATH ตาม BIP48 แต่กุญแจนี้อยู่ที่ $path"
        !sitsAtNativeSegwitMultisig(xpub) -> "xpub นี้ไม่ได้อยู่ที่ $path · คัดลอก xpub และ path จากกุญแจดอกเดียวกัน"
        else -> null
    }
}

private const val BASE58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

/**
 * Whether the xpub's own depth and child number are those of m/48'/1'/N'/2' (depth 4, child 2'), as script.rs checks, so a key
 * copied from another level does not pass under a path it does not sit at.
 * shortcut: the base58 checksum is not checked here; script.rs checks it before Choose Keys makes the wallet.
 */
private fun sitsAtNativeSegwitMultisig(xpub: String): Boolean {
    val bytes = mutableListOf<Int>()
    for (c in xpub) {
        var carry = BASE58.indexOf(c)
        if (carry < 0) return false
        for (i in bytes.indices) {
            carry += bytes[i] * 58
            bytes[i] = carry and 0xff
            carry = carry shr 8
        }
        while (carry > 0) {
            bytes.add(carry and 0xff)
            carry = carry shr 8
        }
    }
    if (bytes.size != 82) return false
    val serialized = bytes.asReversed()
    val child = (9..12).fold(0L) { acc, i -> (acc shl 8) or serialized[i].toLong() }
    return serialized[4] == 4 && child == 0x80000002L
}
