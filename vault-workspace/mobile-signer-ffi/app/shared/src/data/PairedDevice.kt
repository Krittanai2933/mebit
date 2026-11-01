package com.mebit.app.data

/** The hardware wallet the user just paired, as the hardware backup screen shows it. [name] is the key's saved label, so a rename on Confirm Key shows here. */
data class PairedDevice(
    val name: String,
    val vendor: String,
    val monogram: String,
    val fingerprint: String,
    val isMock: Boolean = false,
) {
    val meta: String get() = (if (isMock) "โหมดจำลอง · " else "Bluetooth · ") + fingerprintGroups(fingerprint)
    val status: String get() = if (isMock) "จำลอง" else "เชื่อมแล้ว"
}

/** The newest saved Jade or Trezor Safe 7 key as a device, or null when the keyring holds none. */
fun pairedDevice(keys: List<SavedKey>): PairedDevice? {
    val key = keys.lastOrNull { it.source == KeySourceLabel.Jade || it.source == KeySourceLabel.TrezorSafe7 } ?: return null
    return PairedDevice(key.label, hardwareName(key.source), hardwareMonogram(key.source), key.fingerprint, key.isMock)
}

internal fun hardwareMonogram(source: KeySourceLabel): String = when (source) {
    KeySourceLabel.Jade -> "JD"
    KeySourceLabel.TrezorSafe7 -> "TZ"
    KeySourceLabel.ThisDevice, KeySourceLabel.MebitOther -> error("not hardware")
}

/** "7F2CA91B" becomes "7f2c a91b": lowercase, in groups of four. */
internal fun fingerprintGroups(fingerprint: String): String = fingerprint.lowercase().chunked(4).joinToString(" ")
