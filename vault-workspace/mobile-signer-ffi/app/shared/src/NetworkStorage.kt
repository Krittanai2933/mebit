package com.mebit.app

import com.mebit.ffi.signer.KeyNetwork

/**
 * Where each network keeps its data. Testnet keeps every name it always had, so no testnet file moves. Mainnet sits in a sibling
 * `main/` folder, and under aliases and services of its own, where an older build never lists, reads or deletes it.
 */
internal fun KeyNetwork.folder(name: String): String = if (this == KeyNetwork.MAIN) "main/$name" else name

/** A Keystore alias or Keychain service. A Forget deletes a whole alias or service, so mainnet's is never testnet's. */
internal fun KeyNetwork.alias(name: String): String = if (this == KeyNetwork.MAIN) "$name.main" else name
