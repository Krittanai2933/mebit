package com.mebit.app.feature.settings

import com.mebit.app.data.NetworkTap
import com.mebit.app.data.VaultsHealth
import com.mebit.app.data.chainName
import com.mebit.app.data.networkChip
import com.mebit.app.data.SwitchHold
import com.mebit.app.data.networkRefusal
import com.mebit.app.data.networkTap
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
    fun welcomesChipNamesTheNetworkAndAnArrow() {
        assertEquals("Testnet4 ›", networkChip(test))
        assertEquals("Mainnet ›", networkChip(main))
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

    @Test
    fun theSwitchStaysAsksOrSwitches() {
        assertEquals(NetworkTap.Stay, networkTap(test, test, refused = false))
        assertEquals(NetworkTap.Stay, networkTap(main, main, refused = true), "the picked side does nothing, even while refused")
        assertEquals(NetworkTap.Ask, networkTap(test, main, refused = false), "mainnet asks first")
        assertEquals(NetworkTap.Switch, networkTap(main, test, refused = false), "testnet switches straight away")
        assertEquals(NetworkTap.Refuse, networkTap(test, main, refused = true))
        assertEquals(NetworkTap.Refuse, networkTap(main, test, refused = true))
    }

    @Test
    fun theSheetAndTheRefusalsAreTheApprovedTexts() {
        assertEquals("เปลี่ยนเป็น Mainnet", MAINNET_SHEET_TITLE)
        assertEquals("Mainnet ใช้ Bitcoin จริง ส่งแล้วยกเลิกไม่ได้ กระเป๋าบน Testnet4 จะซ่อนไว้จนกว่าจะเปลี่ยนกลับ", MAINNET_SHEET_BODY)
        assertEquals(null, networkRefusal(SwitchHold.Clear, VaultsHealth.Ok))
        assertEquals("เครื่องนี้ยังบันทึกกระเป๋าไม่ได้ ตั้งรหัสล็อกหน้าจอก่อน จึงจะเปลี่ยนเครือข่ายได้", networkRefusal(SwitchHold.PhoneCannotHold, VaultsHealth.Ok))
        assertEquals("บันทึก Wallet และกุญแจล่าสุดไม่สำเร็จ ปิดแล้วเปิดแอปใหม่ก่อนเปลี่ยนเครือข่าย", networkRefusal(SwitchHold.Clear, VaultsHealth.SaveFailed))
        assertEquals("เครื่องนี้ยังบันทึกกระเป๋าไม่ได้ ตั้งรหัสล็อกหน้าจอก่อน จึงจะเปลี่ยนเครือข่ายได้", networkRefusal(SwitchHold.PhoneCannotHold, VaultsHealth.SaveFailed), "words in memory come first")
        val reopen = "บันทึก Wallet และกุญแจล่าสุดไม่สำเร็จ ปิดแล้วเปิดแอปใหม่ก่อนเปลี่ยนเครือข่าย"
        assertEquals(reopen, networkRefusal(SwitchHold.Unsaved, VaultsHealth.Ok), "a wallet not saved for another cause, or a failed Replace")
        assertEquals(reopen, networkRefusal(SwitchHold.Unsaved, VaultsHealth.SaveFailed))
    }
}
