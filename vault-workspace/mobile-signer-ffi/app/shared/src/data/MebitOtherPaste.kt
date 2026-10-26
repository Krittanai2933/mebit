package com.mebit.app.data

internal fun parseExternalKeyPaste(text: String): PendingKey {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) throw IllegalArgumentException("ว่าง")
    val cosigner = if (trimmed.startsWith("BSMS 1.0")) parseBsmsCosigner(trimmed) else parsePlainCosigner(trimmed)
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
