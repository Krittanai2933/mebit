package com.mebit.app.feature.onboarding

import com.mebit.ffi.signer.AddressType
import com.mebit.ffi.signer.KeyNetwork
import kotlin.test.Test
import kotlin.test.assertEquals

/** Penpot 1.5.1's rows and eyebrow. */
class AddressTypeScreenTest {
    @Test
    fun theRowsAreEveryTypeInPenpotsOrder() {
        assertEquals(
            listOf(AddressType.LEGACY, AddressType.NESTED_SEGWIT, AddressType.NATIVE_SEGWIT, AddressType.TAPROOT),
            AddressTypeRows.map { it.type },
        )
        assertEquals(listOf("Legacy", "Nested SegWit", "Native SegWit", "Taproot"), AddressTypeRows.map { it.label })
    }

    // The detail line names the prefixes of the network the wallet runs on, so it agrees with the address Receive shows.
    @Test
    fun eachRowsPrefixesFollowTheNetwork() {
        assertEquals(
            listOf("BIP44 · เริ่มด้วย m หรือ n", "BIP49 · เริ่มด้วย 2", "BIP84 · เริ่มด้วย tb1q", "BIP86 · เริ่มด้วย tb1p"),
            AddressTypeRows.map { it.detail(KeyNetwork.TEST) },
        )
        assertEquals(
            listOf("BIP44 · เริ่มด้วย 1", "BIP49 · เริ่มด้วย 3", "BIP84 · เริ่มด้วย bc1q", "BIP86 · เริ่มด้วย bc1p"),
            AddressTypeRows.map { it.detail(KeyNetwork.MAIN) },
        )
    }

    @Test
    fun theEyebrowNamesTheFlow() {
        assertEquals("NEW WALLET · ADDRESS TYPE", addressTypeEyebrow(imported = false))
        assertEquals("IMPORT WALLET · ADDRESS TYPE", addressTypeEyebrow(imported = true))
    }
}
