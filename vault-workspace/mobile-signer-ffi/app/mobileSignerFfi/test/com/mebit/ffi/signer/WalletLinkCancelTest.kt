package com.mebit.ffi.signer

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// A link that was never connected touches no radio: info() fails Disconnected and disconnect()
// returns at once. Never call connect or scanWallets here, the host library has a real backend.
// Every link is closed in `use`, so no cleaner can change another test's baseline later.
private fun link() = WalletLink(FoundWallet(WalletKind.JADE, "id of Jade 1a2b3c", "Jade 1a2b3c"))

class WalletLinkCancelTest {
    @Test
    fun aClosedLinkIsDropped() = runTest {
        val before = liveWalletLinks()
        link().use { link ->
            assertEquals(before + 1u, liveWalletLinks(), "the counter sees the new link")
            assertFailsWith<BleException.Disconnected> { link.info() }
            link.disconnect()
            assertEquals(before + 1u, liveWalletLinks(), "finished calls did not drop a link that is still open")
        }
        assertEquals(before, liveWalletLinks())
    }

    // Gobley's wrapper made the Rust call before `withContext` threw, and freed it only inside
    // the block that never ran. The call kept the object alive, so close() could not drop it.
    // info() returns a value and disconnect() returns nothing: two different free paths.
    @Test
    fun callsFromACancelledCoroutineLeaveNothingBehind() = runTest {
        val before = liveWalletLinks()
        launch {
            cancel()
            link().use { link ->
                assertFailsWith<CancellationException> { link.info() }
                assertFailsWith<CancellationException> { link.disconnect() }
                assertEquals(before + 1u, liveWalletLinks(), "cancelled calls did not drop a link that is still open")
                withContext(NonCancellable) { link.disconnect() }
            }
        }.join()
        assertEquals(before, liveWalletLinks(), "a call from a cancelled coroutine kept the Rust WalletLink alive")
    }
}
