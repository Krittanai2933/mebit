package com.mebit.app.data

import com.mebit.ffi.signer.AddressType
import com.mebit.ffi.signer.WalletKind

// PUBLIC_WORDS_12, PUBLIC_FINGERPRINT and PUBLIC_ACCOUNTS live with the fake device, which holds the same public phrase (FakeDevice.kt).

/** The 24-word form of the same public test phrase. */
internal val PUBLIC_WORDS_24: List<String> = List(23) { "abandon" } + "art"

/** The [PUBLIC_WORDS_12] Native SegWit account as a Jade one-key wallet, saved first. */
internal val PUBLIC_JADE_WALLET = HardwareWallet(
    id = "hw_1",
    name = "Jade",
    kind = WalletKind.JADE,
    addressType = AddressType.NATIVE_SEGWIT,
    fingerprint = PUBLIC_FINGERPRINT,
    xpub = PUBLIC_ACCOUNTS.getValue(AddressType.NATIVE_SEGWIT).tpub,
    address = PUBLIC_ACCOUNTS.getValue(AddressType.NATIVE_SEGWIT).receive0,
)
