package com.mebit.app

import com.mebit.ffi.signer.KeyNetwork
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class NetworkStorageTest {
    private val names = listOf("scan", "wallet", "wallet.2", "vaults/book", "pairing")

    @Test
    fun testnetKeepsEveryNameItAlwaysHad() {
        names.forEach { assertEquals(it, KeyNetwork.TEST.folder(it)) }
        listOf("com.mebit.app.wallet", "mebit_vaults_v1", "mebit_pairing_v1").forEach { assertEquals(it, KeyNetwork.TEST.alias(it)) }
        assertEquals("scan", scanFolder(KeyNetwork.TEST))
    }

    @Test
    fun mainnetSitsBesideTestnetNeverInsideOrEqual() {
        names.forEach {
            val main = KeyNetwork.MAIN.folder(it)
            assertTrue(main.startsWith("main/"), main)
            assertTrue(names.none { t -> main == t || main.startsWith("$t/") }, main)
        }
        listOf("com.mebit.app.wallet", "mebit_vaults_v1", "mebit_pairing_v1").forEach {
            assertNotEquals(KeyNetwork.TEST.alias(it), KeyNetwork.MAIN.alias(it))
            assertEquals("$it.main", KeyNetwork.MAIN.alias(it))
        }
        assertEquals("main/scan", scanFolder(KeyNetwork.MAIN))
    }
}
