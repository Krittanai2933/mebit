package com.mebit.app.feature.settings

import com.mebit.app.data.chainName
import com.mebit.app.data.networkWord
import com.mebit.ffi.signer.KeyNetwork
import com.mebit.ffi.signer.NodeCheck
import kotlin.test.Test
import kotlin.test.assertEquals

/** The texts of Penpot 7.1, 7.1.2, 7.2 and 7.2.1, per network. Testnet's are the ones the app had before mainnet. */
class SettingsTextTest {
    private val test = KeyNetwork.TEST
    private val main = KeyNetwork.MAIN

    @Test
    fun theNetworkNamesAreTheBoards() {
        assertEquals("testnet", networkWord(test))
        assertEquals("mainnet", networkWord(main))
        assertEquals("Testnet4", chainName(test))
        assertEquals("Mainnet", chainName(main))
    }

    @Test
    fun theSubtitleFollowsTheNetwork() {
        assertEquals("เงินทดสอบ ไม่มีมูลค่าจริง", networkLine(test))
        assertEquals("เงินจริง ส่งแล้วยกเลิกไม่ได้", networkLine(main))
    }

    @Test
    fun theOwnNodeCardNamesItsChain() {
        assertEquals("Esplora ของ Testnet4 ที่คุณดูแลเอง", ownNodeLine(test))
        assertEquals("Esplora ของ Mainnet ที่คุณดูแลเอง", ownNodeLine(main))
    }

    @Test
    fun theWarningNamesTheChain() {
        assertEquals("ก่อนใช้โหนดใด แอปตรวจว่าเป็นเชน Testnet4 จริง\nแต่โหนดที่ไม่สุจริตยังแสดงยอดผิดได้", nodeWarning(test))
        assertEquals("ก่อนใช้โหนดใด แอปตรวจว่าเป็นเชน Mainnet จริง\nแต่โหนดที่ไม่สุจริตยังแสดงยอดผิดได้", nodeWarning(main))
    }

    @Test
    fun theNodeTestLineNamesTheChainItWanted() {
        assertEquals("ไม่ผ่าน · โหนดนี้ไม่ใช่เชน Testnet4", checkLine(NodeCheck.OtherChain, test))
        assertEquals("ไม่ผ่าน · โหนดนี้ไม่ใช่เชน Mainnet", checkLine(NodeCheck.OtherChain, main))
        assertEquals("ผ่าน · เป็นเชน Testnet4", checkLine(NodeCheck.Testnet4, test))
        assertEquals("ผ่าน · เป็นเชน Mainnet", checkLine(NodeCheck.Mainnet, main))
    }
}
