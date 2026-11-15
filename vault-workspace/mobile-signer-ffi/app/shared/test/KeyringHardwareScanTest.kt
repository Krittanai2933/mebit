package com.mebit.app.data

import com.mebit.ffi.signer.BleException
import com.mebit.ffi.signer.FoundWallet
import com.mebit.ffi.signer.WalletKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class KeyringHardwareScanTest {
    private val jade = FoundWallet(WalletKind.JADE, "jade-1", "Jade 1a2b3c")
    private val safe7 = FoundWallet(WalletKind.TREZOR_SAFE7, "safe7-1", "Safe 7 4d5e")

    @Test
    fun rowsArriveOneKindAtATimeJadeFirst() = runBlocking {
        val seen = mutableListOf<HardwareScan>()
        lateinit var store: KeyringStore
        store = KeyringStore(scan = { kind ->
            seen += store.hardwareScan
            if (kind == WalletKind.JADE) listOf(jade) else listOf(safe7)
        })
        assertEquals(HardwareScan.Idle, store.hardwareScan)
        store.scanHardware()
        val beforeEachKind: List<HardwareScan> = listOf(HardwareScan.Scanning(emptyList()), HardwareScan.Scanning(listOf(jade)))
        assertEquals(beforeEachKind, seen)
        assertEquals(HardwareScan.Done(listOf(jade, safe7), null), store.hardwareScan)
    }

    @Test
    fun aFailingKindKeepsTheOtherKindsRows() = runBlocking {
        val jadeFails = KeyringStore(scan = { kind ->
            if (kind == WalletKind.JADE) throw BleException.Link("gatt 133") else listOf(safe7)
        })
        jadeFails.scanHardware()
        assertEquals(HardwareScan.Done(listOf(safe7), ScanProblem.Failed("gatt 133")), jadeFails.hardwareScan)

        val safe7Fails = KeyringStore(scan = { kind ->
            if (kind == WalletKind.JADE) listOf(jade) else throw BleException.Timeout("slow")
        })
        safe7Fails.scanHardware()
        assertEquals(HardwareScan.Done(listOf(jade), ScanProblem.Failed("slow")), safe7Fails.hardwareScan)
    }

    @Test
    fun anExceptionWithoutAMessageStillGivesAProblemText() = runBlocking {
        val store = KeyringStore(scan = { throw IllegalStateException() })
        store.scanHardware()
        val done = assertIs<HardwareScan.Done>(store.hardwareScan)
        assertEquals(emptyList<FoundWallet>(), done.found)
        assertTrue(assertIs<ScanProblem.Failed>(done.problem).message.isNotBlank())
    }

    @Test
    fun bluetoothOffAndPermissionDeniedMapToTheirOwnProblems() = runBlocking {
        val off = KeyringStore(scan = { throw BleException.BluetoothOff("no adapter") })
        off.scanHardware()
        assertEquals(HardwareScan.Done(emptyList(), ScanProblem.BluetoothOff), off.hardwareScan)

        val denied = KeyringStore(scan = { throw BleException.PermissionDenied("open Settings") })
        denied.scanHardware()
        assertEquals(HardwareScan.Done(emptyList(), ScanProblem.PermissionDenied("open Settings")), denied.hardwareScan)
    }

    @Test
    fun nothingFoundOfEitherKindIsAnEmptyListWithNoProblem() = runBlocking {
        val calls = mutableListOf<WalletKind>()
        val store = KeyringStore(scan = { kind ->
            calls += kind
            throw BleException.NotFound("nothing seen")
        })
        store.scanHardware()
        assertEquals(HardwareScan.Done(emptyList(), null), store.hardwareScan)
        assertEquals(listOf(WalletKind.JADE, WalletKind.TREZOR_SAFE7), calls)
    }

    @Test
    fun aKindOutOfRangeAddsNoRowsAndNoProblem() = runBlocking {
        val store = KeyringStore(scan = { kind ->
            if (kind == WalletKind.JADE) throw BleException.NotFound("no Jade") else listOf(safe7)
        })
        store.scanHardware()
        assertEquals(HardwareScan.Done(listOf(safe7), null), store.hardwareScan)
    }

    @Test
    fun aSecondCallWhileScanningDoesNothing() = runBlocking {
        val gate = CompletableDeferred<List<FoundWallet>>()
        val calls = mutableListOf<WalletKind>()
        val store = KeyringStore(scan = { kind ->
            calls += kind
            if (kind == WalletKind.JADE) gate.await() else emptyList()
        })
        val first = launch(start = CoroutineStart.UNDISPATCHED) { store.scanHardware() }
        assertIs<HardwareScan.Scanning>(store.hardwareScan)

        // A call that was not ignored would wait on the gate, so the timeout turns that into a failure.
        withTimeout(1_000) { store.scanHardware() }
        assertEquals(listOf(WalletKind.JADE), calls)

        gate.complete(listOf(jade))
        first.join()
        assertEquals(listOf(WalletKind.JADE, WalletKind.TREZOR_SAFE7), calls)
        assertEquals(HardwareScan.Done(listOf(jade), null), store.hardwareScan)
    }

    @Test
    fun aScanCanBeRepeatedAndTheOldRowsDoNotLinger() = runBlocking {
        var round = 0
        val store = KeyringStore(scan = { kind ->
            when {
                round == 0 && kind == WalletKind.JADE -> listOf(jade)
                round == 1 && kind == WalletKind.TREZOR_SAFE7 -> listOf(safe7)
                else -> emptyList()
            }
        })
        store.scanHardware()
        assertEquals(HardwareScan.Done(listOf(jade), null), store.hardwareScan)

        round = 1
        store.scanHardware()
        assertEquals(HardwareScan.Done(listOf(safe7), null), store.hardwareScan)
    }

    @Test
    fun aCancelledScanKeepsItsRowsAndFreesTheNextScan() = runBlocking {
        val gate = CompletableDeferred<List<FoundWallet>>()
        val store = KeyringStore(scan = { kind ->
            if (kind == WalletKind.JADE) listOf(jade) else gate.await()
        })
        val first = launch(start = CoroutineStart.UNDISPATCHED) { store.scanHardware() }
        assertEquals(HardwareScan.Scanning(listOf(jade)), store.hardwareScan)

        first.cancelAndJoin()
        assertEquals(HardwareScan.Done(listOf(jade), null), store.hardwareScan)

        gate.complete(listOf(safe7))
        store.scanHardware()
        assertEquals(HardwareScan.Done(listOf(jade, safe7), null), store.hardwareScan)
    }
}
