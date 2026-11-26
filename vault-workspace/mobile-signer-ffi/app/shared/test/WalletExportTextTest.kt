package com.mebit.app.data

import com.mebit.app.feature.vault.bsmsFileName
import com.mebit.ffi.signer.KeyNetwork
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WalletExportTextTest {
    private val vault = CreatedVault("vault_1", "Wallet 2", 2, 3, CustodyPolicy.PersonalCustody, "tb1qa", "BSMS 1.0\nwsh(sortedmulti(2,A,B,C))\nx\ny", emptyList())

    @Test
    fun filesAreNamedFromTheWalletsName() {
        assertEquals("Wallet_2.bsms", bsmsFileName(vault.name))
        assertEquals("Wallet_2-descriptor.txt", descriptorFileName(vault.name))
        assertEquals("_-descriptor.txt", descriptorFileName("ก"))
    }

    @Test
    fun theRecordIsAskedForTheStoredDescriptorOnLineTwo() {
        val asked = mutableListOf<Pair<String, KeyNetwork>>()
        val text = bsmsRecordOf(vault, KeyNetwork.TEST) { d, n -> asked += d to n; "record" }
        assertEquals("record", text)
        assertEquals(listOf("wsh(sortedmulti(2,A,B,C))" to KeyNetwork.TEST), asked)
    }

    @Test
    fun aRefusalIsNullSoNothingIsExported() {
        assertNull(bsmsRecordOf(vault, KeyNetwork.TEST) { _, _ -> error("refused") })
    }
}
