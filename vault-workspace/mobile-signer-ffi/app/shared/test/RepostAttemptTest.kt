package com.mebit.app.feature.send

import com.mebit.app.BiometricResult
import com.mebit.app.Biometrics
import com.mebit.app.data.AppLockState
import com.mebit.app.data.OwnerCheck
import com.mebit.app.data.Repost
import com.mebit.app.data.SpendingPin
import com.mebit.app.data.sessionWithKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.TestTimeSource

/**
 * A repost from Send or Review (C2). The owner check that signing asks comes first, so a stranger holding the phone cannot push out
 * a payment the owner may have given up on, and one tap posts once.
 */
class RepostAttemptTest {
    @Test
    fun aRefusedOwnerCheckPostsNothing() = runBlocking {
        val attempt = RepostAttempt()
        var posts = 0
        attempt.run(TXID, confirm = { false }) {
            posts++
            Repost.Out
        }
        assertEquals(0, posts)
        assertNull(attempt.done)
        assertFalse(attempt.running)
    }

    @Test
    fun theOwnerIsAskedBeforeTheBytesArePostedAndTheEndIsKept() = runBlocking {
        val attempt = RepostAttempt()
        val events = mutableListOf<String>()
        attempt.run(TXID, confirm = { events += "asked"; true }) {
            events += "posted"
            Repost.Unknown
        }
        assertEquals(listOf("asked", "posted"), events)
        assertEquals(Reposted(TXID, Repost.Unknown), attempt.done)
        assertNull(attempt.outTxid, "a payment that is not out")
        attempt.run(TXID, confirm = { true }) { Repost.Out }
        assertEquals(TXID, attempt.outTxid)
    }

    @Test
    fun aSecondTapWhileTheFirstWaitsOnTheOwnerPostsNothing() = runBlocking {
        val attempt = RepostAttempt()
        val owner = CompletableDeferred<Boolean>()
        var asked = 0
        var posts = 0
        val confirm: suspend () -> Boolean = {
            asked++
            owner.await()
        }
        val first = launch {
            attempt.run(TXID, confirm) {
                posts++
                Repost.Out
            }
        }
        yield()
        assertTrue(attempt.running, "the first tap waits on the owner")
        attempt.run(TXID, confirm) {
            posts++
            Repost.Out
        }
        owner.complete(true)
        first.join()
        assertEquals(1, asked, "owner checks")
        assertEquals(1, posts, "posts")
    }

    @Test
    fun onAPhoneWithNoOwnerCheckTheSpendingPinGuardsARepostAsItGuardsSigning() = runBlocking {
        val clock = TestTimeSource()
        val session = sessionWithKey(SpendingPin(clock))
        assertTrue(session.pin.set("270419"))
        val owner = OwnerCheck(NoLock, session)
        val appLock = AppLockState(clock)
        val attempt = RepostAttempt()
        var posts = 0
        val confirm: suspend () -> Boolean = { appLock.confirm(owner, enabled = false, reason = "ยืนยันการส่งรายการเดิมอีกครั้ง") }
        val post = {
            posts++
            Repost.Out
        }
        val cancelled = launch { attempt.run(TXID, confirm, post) }
        yield()
        val sheet = assertNotNull(owner.asking, "the PIN sheet before the repost, with App Lock off")
        assertEquals("ยืนยันการส่งรายการเดิมอีกครั้ง", sheet.title)
        "583920".forEach(sheet::type)
        assertEquals(0, posts, "a wrong PIN posts nothing")
        sheet.cancel()
        cancelled.join()
        assertEquals(0, posts, "a closed sheet posts nothing")
        assertNull(attempt.done)
        val passed = launch { attempt.run(TXID, confirm, post) }
        yield()
        "270419".forEach(assertNotNull(owner.asking, "the sheet again")::type)
        passed.join()
        assertEquals(1, posts, "the right PIN lets the repost post")
        assertEquals(TXID, attempt.outTxid)
    }

    /** A phone with no passcode and no screen lock, so the Spending PIN is the owner check. */
    private object NoLock : Biometrics {
        override fun available() = false

        override suspend fun authenticate(reason: String, allowPasscode: Boolean): BiometricResult = BiometricResult.Unavailable("no lock")
    }
}

private const val TXID = "c5ed075ef44b807f51123cf0c6e53c75385a17a510f995b560bf73b1102d5474"
