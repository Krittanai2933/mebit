package com.mebit.app.data

import com.mebit.app.safeShareName
import com.mebit.ffi.signer.KeyNetwork
import com.mebit.ffi.signer.walletBsmsRecord

/** Pure. "Wallet 2" is saved as "Wallet_2-descriptor.txt", the name the share sheet and the FileProvider take. */
internal fun descriptorFileName(walletName: String): String = safeShareName(walletName) + "-descriptor.txt"

/** The BIP-129 record of [vault], or null when the core refuses its descriptor. [record] is injected so tests need no core. */
internal fun bsmsRecordOf(vault: CreatedVault, record: (String, KeyNetwork) -> String = { d, n -> walletBsmsRecord(d, n) }): String? =
    try {
        record(descriptorIn(vault.bsms), KeyNetwork.TEST)
    } catch (e: Exception) {
        null
    }
