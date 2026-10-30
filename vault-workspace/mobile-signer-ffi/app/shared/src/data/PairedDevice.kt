package com.mebit.app.data

/** The hardware wallet the user just paired, as the hardware backup screen shows it. */
data class PairedDevice(val vendor: String, val monogram: String, val fingerprint: String) {
    val name: String get() = "$vendor ของฉัน"
    val meta: String get() = "Bluetooth · " + fingerprintGroups(fingerprint)
}

/** The newest saved Jade or Trezor Safe 7 key as a device, or null when the keyring holds none. */
fun pairedDevice(keys: List<SavedKey>): PairedDevice? {
    val key = keys.lastOrNull { it.source == KeySourceLabel.Jade || it.source == KeySourceLabel.TrezorSafe7 } ?: return null
    val monogram = when (key.source) {
        KeySourceLabel.Jade -> "JD"
        KeySourceLabel.TrezorSafe7 -> "TZ"
        KeySourceLabel.ThisDevice, KeySourceLabel.MebitOther -> error("not hardware")
    }
    return PairedDevice(hardwareName(key.source), monogram, key.fingerprint)
}

/** "7F2CA91B" becomes "7f2c a91b": lowercase, in groups of four. */
internal fun fingerprintGroups(fingerprint: String): String = fingerprint.lowercase().chunked(4).joinToString(" ")
